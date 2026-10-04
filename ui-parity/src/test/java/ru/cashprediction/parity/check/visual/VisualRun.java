package ru.cashprediction.parity.check.visual;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.parity.launch.*;
import ru.cashprediction.parity.pipeline.*;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;
import ru.cashprediction.parity.browser.CdpClient;

/** Собирает только настоящие dump/shot через изолированный launcher; отсутствие shot остаётся ошибкой. */
public final class VisualRun {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private VisualRun() { }

    /** Результат опыта: существующие фактические файлы и все ошибки, включая неполный сценарий. */
    public record Capture(String client, VisualPlan.Checkpoint checkpoint, Path run, Path png,
                          UiDump dump, List<String> failures) {
        /** Копирует список ошибок для независимого отчёта. */
        public Capture { failures = List.copyOf(failures); }
    }

    /** Запускает один префикс сценария и сохраняет свидетельства даже при неуспешном шаге. */
    public static Capture collect(ReactorLayout layout, String client, VisualPlan.Checkpoint checkpoint, Path output) throws Exception {
        Path run = Files.createDirectories(output.resolve(client + "-" + checkpoint.scenario() + "-" + checkpoint.step() + "-" + UUID.randomUUID()));
        Path script = run.resolve(checkpoint.script().name() + ".cps");
        Files.writeString(script, VisualPlan.text(checkpoint.script()));
        String node = RegistryNodeCleaner.newSelftestNode();
        var sessions = RegistryNodeCleaner.snapshotRealSessionNodes();
        LaunchRequest request = new LaunchRequest(client, checkpoint.scenario(), run.resolve("home"), node,
                LaunchRequest.PARITY_TODAY, script.toString(), run.resolve("out"), List.of(), List.of());
        var failures = new ArrayList<String>();
        Path log = request.selftestOut().resolve("selftest.log");
        Path png = request.selftestOut().resolve(checkpoint.script().name()).resolve(checkpoint.step() + ".png");
        UiDump dump = null;
        Path profile = null;
        try {
            ClientTarget target = switch (client) {
                case "fx" -> ClientTarget.fx(layout);
                case "swing" -> ClientTarget.swing(layout);
                case "web" -> ClientTarget.web(layout);
                default -> throw new IllegalArgumentException(client);
            };
            target = VisualTargets.snapshot(target, run.resolve("module-snapshot"));
            try (ScenarioFixtures fixtures = ScenarioFixtures.prepare(request, TIMEOUT);
                 LaunchedClient launched = ClientLauncher.launch(target, request)) {
                if (client.equals("web")) {
                    // Chromium создаёт глубокие подпапки: длинный путь отчёта превышает лимит Windows.
                    profile = Files.createTempDirectory("cp-visual-browser-");
                    try (var web = WebScenarioSession.attach(launched, profile, TIMEOUT)) {
                        int port = Integer.parseInt(Files.readAllLines(profile.resolve("DevToolsActivePort")).getFirst());
                        try (var cdp = CdpClient.connectToFirstPage(port, Duration.ofSeconds(10))) {
                            Object viewport = cdp.evaluate("innerWidth === 1200 && innerHeight === 800 && devicePixelRatio === 1", Duration.ofSeconds(5));
                            if (!Boolean.TRUE.equals(viewport)) throw new IllegalStateException("Web requires viewport 1200x800 and devicePixelRatio=1");
                        }
                        long deadline = System.nanoTime() + TIMEOUT.toNanos();
                        while (!done(log)) {
                            if (!launched.process().isAlive() || System.nanoTime() >= deadline)
                                throw new IllegalStateException("Missing visual SELFTEST DONE: " + log + "\n" + launched.stderrTail());
                            if (!web.pump()) Thread.sleep(20);
                        }
                    }
                } else launched.waitUntil(() -> done(log), TIMEOUT, "visual SELFTEST DONE: " + script);
                fixtures.requireComplete();
            }
        } catch (Exception | AssertionError failure) { failures.add("Launch/scenario: " + failure); }
        finally {
            if (profile != null) {
                try {
                    // Содержимое удаляет BrowserSession; здесь удаляется только пустая папка при ранней ошибке.
                    Files.deleteIfExists(profile);
                    Path browserLog = profile.resolveSibling(profile.getFileName() + ".browser.log");
                    if (Files.isRegularFile(browserLog)) Files.move(browserLog, run.resolve("browser.log"));
                } catch (Exception failure) { failures.add("Browser profile/log cleanup: " + failure); }
            }
            try {
                RegistryNodeCleaner.delete(node);
                if (RegistryNodeCleaner.exists(node)) failures.add("Selftest registry survived: " + node);
                if (!sessions.equals(RegistryNodeCleaner.snapshotRealSessionNodes())) failures.add("Real session registry changed");
            } catch (Exception failure) { failures.add("Registry cleanup: " + failure); }
        }
        // Неполный журнал нужен и после тайм-аута или ошибки уборки; DONE не является условием его проверки.
        try { failures.addAll(logFailures(checkpoint.script(), Files.readString(log))); }
        catch (Exception failure) { failures.add("Actual visual log missing/invalid: " + failure); }
        try {
            // Геометрия для пикселей не округляется; обычный .json остаётся отдельным эталоном паритета.
            dump = DumpTrees.read(Files.readString(png.resolveSibling(checkpoint.step() + ".raw.json")));
            if (!dump.client().equals(client) || !dump.scenario().equals(checkpoint.script().name()) || !dump.step().equals(checkpoint.step()))
                throw new IllegalArgumentException("Actual dump identity mismatch");
        } catch (Exception failure) { failures.add("Actual dump missing/invalid: " + failure); dump = null; }
        if (!Files.isRegularFile(png)) {
            failures.add("Actual shot missing: " + png);
            if (client.equals("web")) failures.add("Web Shot requires TestApiBridge -> UiDriver.screenshot -> authenticated /api/test/shot; server must persist current Shot PNG before result OK");
        } else if (dump != null) failures.addAll(VisualImages.check(dump, Files.readAllBytes(png)));
        return new Capture(client, checkpoint, run, Files.isRegularFile(png) ? png : null, dump, failures);
    }

    /** Проверяет каждую исходную и добавленную строку; DONE не скрывает FAIL, пропуск или дубликат. */
    public static List<String> logFailures(SelfTestScript script, String contents) {
        var failures = new ArrayList<String>();
        List<String> actual = contents.lines().toList();
        if (actual.size() != script.lines().size() + 1) failures.add("Incomplete/duplicate visual log");
        for (int i = 0; i < script.lines().size(); i++) {
            var line = script.lines().get(i);
            String expected = "SELFTEST " + line.number() + " OK " + line.text();
            if (i >= actual.size() || !expected.equals(actual.get(i))) failures.add("Step " + line.number()
                    + ": expected " + expected + "; actual " + (i < actual.size() ? actual.get(i) : "missing"));
        }
        if (actual.isEmpty() || !actual.getLast().equals("SELFTEST DONE")) failures.add("Missing final SELFTEST DONE");
        return List.copyOf(failures);
    }

    private static boolean done(Path log) {
        try { return Files.isRegularFile(log) && Files.readString(log).lines().anyMatch("SELFTEST DONE"::equals); }
        catch (java.io.IOException failure) { return false; }
    }
}
