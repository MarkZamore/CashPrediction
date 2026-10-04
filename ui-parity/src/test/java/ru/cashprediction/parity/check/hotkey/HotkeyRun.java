package ru.cashprediction.parity.check.hotkey;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.net.URI;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.*;
import ru.cashprediction.parity.launch.*;
import ru.cashprediction.parity.pipeline.*;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;
import ru.cashprediction.parity.browser.CdpClient;
import ru.cashprediction.parity.driver.*;
import ru.cashprediction.parity.check.census.ClientJarSnapshot;
import static org.junit.jupiter.api.Assertions.*;

/** Изолированный опыт через штатный launcher и SelfTestRunner; журналы и сценарий сохраняются для повторения. */
public final class HotkeyRun {
    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private HotkeyRun() { }

    /** Проверяет одну привязку; отсутствие операции, дампа или полного журнала всегда является ошибкой. */
    public static void verify(ReactorLayout layout, String client, HotkeyCases.Probe probe) throws Exception {
        Path run = Files.createDirectories(layout.parityRoot().resolve("hotkey-" + UUID.randomUUID()));
        Path script = run.resolve("hotkey.cps");
        Files.writeString(script, probe.script());
        String node = RegistryNodeCleaner.newSelftestNode();
        var sessions = RegistryNodeCleaner.snapshotRealSessionNodes();
        LaunchRequest request = new LaunchRequest(client, "hotkey", run.resolve("home"), node,
                LaunchRequest.PARITY_TODAY, script.toString(), run.resolve("out"), List.of(), List.of());
        try {
            ClientTarget target = switch (client) {
                case "fx" -> ClientTarget.fx(layout);
                case "swing" -> ClientTarget.swing(layout);
                case "web" -> ClientTarget.web(layout);
                default -> throw new IllegalArgumentException(client);
            };
            target = ClientJarSnapshot.copy(target, run);
            boolean nativeInput = !client.equals("web") && (probe.name().endsWith(" en")
                    || probe.name().endsWith(" ru") || probe.chord().key().equals("ALT"));
            if (nativeInput) target = nativeTarget(target, run, probe.russian());
            try (ScenarioFixtures fixtures = ScenarioFixtures.prepare(request, TIMEOUT);
                 LaunchedClient launched = ClientLauncher.launch(target, request)) {
                Path log = request.selftestOut().resolve("selftest.log");
                if (client.equals("web")) {
                    Path profile = run.resolve("edge-profile");
                    try (var web = WebScenarioSession.attach(launched, profile, TIMEOUT)) {
                        if (probe.russian()) pumpRussian(launched, profile, log);
                        else {
                            long deadline = System.nanoTime() + TIMEOUT.toNanos();
                            while (!done(log)) {
                                requireRunning(launched, deadline, log);
                                if (!web.pump()) Thread.sleep(20);
                            }
                        }
                    }
                } else launched.waitUntil(() -> done(log), TIMEOUT, "hotkey SELFTEST DONE: " + script);
                fixtures.requireComplete();
                requireLog(SelfTestScript.load(script.toString()), Files.readString(log));
                UiDump before = read(request, client, "before"), after = read(request, client, "after");
                assertFalse(before.counters().isEmpty(), "Missing controller counters after sample");
                if (probe.hint().isEmpty())
                    HotkeyAssertions.exactlyOnce(probe.command(), before.counters(), after.counters());
                else HotkeyAssertions.disabledHint(probe.hint(), before, after);
                if (nativeInput && probe.chord().key().equals("ALT")) {
                    HotkeyAssertions.unchanged(before.counters(), read(request, client, "alt-pressed").counters());
                    HotkeyAssertions.exactlyOnce(probe.command(), before.counters(), read(request, client, "alt-released").counters());
                    HotkeyAssertions.unchanged(after.counters(), read(request, client, "alt-repeat-release").counters());
                }
            }
        } catch (Exception | AssertionError failure) {
            throw new AssertionError("Hotkey " + client + " " + probe.name() + " failed. Reproduce:"
                    + " --home " + request.home() + " --registry-node " + node + " --today 2026-09-13"
                    + " --selftest \"" + script + "\" --selftest-out " + request.selftestOut()
                    + ". Logs and actual dumps: " + run, failure);
        } finally {
            RegistryNodeCleaner.delete(node);
            assertFalse(RegistryNodeCleaner.exists(node), "Selftest registry survived");
            assertEquals(sessions, RegistryNodeCleaner.snapshotRealSessionNodes(), "Real session registry changed");
        }
    }

    /** Добавляет только тестовый декоратор ввода к существующему модульному запуску клиента. */
    private static ClientTarget nativeTarget(ClientTarget original, Path run, boolean russian) throws Exception {
        String module = "cashprediction.hotkey.probe";
        Path classes = Path.of(NativeKeyDriver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String folder = NativeKeyDriver.class.getPackageName().replace('.', '/');
        Path jar = run.resolve(module + ".jar");
        try (var files = Files.list(classes.resolve(folder)); var out = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) {
            String parent = "";
            for (String part : folder.split("/")) { parent += part + "/"; out.putNextEntry(new java.util.jar.JarEntry(parent)); out.closeEntry(); }
            for (Path file : files.filter(path -> path.getFileName().toString().matches("(NativeKeyDriver.*|RussianKeyInput)\\.class")).toList()) {
                out.putNextEntry(new java.util.jar.JarEntry(folder + "/" + file.getFileName())); Files.copy(file, out); out.closeEntry();
            }
        }
        var modules = new ArrayList<>(original.modulePath()); modules.add(jar);
        String pkg = original.client().equals("fx") ? "ru.cashprediction.fx.ui" : "ru.cashprediction.swing.ui";
        var options = new ArrayList<>(original.jvmOptions());
        options.addAll(List.of("--add-opens", original.mainModule() + "/" + pkg + "=" + module,
                "-Dparity.hotkey.russian=" + russian, "-Dparity.hotkey.client=" + original.client()));
        return new ClientTarget(original.client(), modules, module, NativeKeyDriver.class.getName(),
                List.of("ALL-MODULE-PATH", "java.desktop"), options, original.arguments());
    }

    /** Требует ровно по одному успешному результату каждой исходной строки и единственную финальную отметку. */
    public static void requireLog(SelfTestScript script, String contents) {
        List<String> actual = contents.lines().toList();
        assertEquals(script.lines().size() + 1, actual.size(), "Incomplete/duplicate selftest log");
        for (int i = 0; i < script.lines().size(); i++) {
            var line = script.lines().get(i);
            assertEquals("SELFTEST " + line.number() + " OK " + line.text(), actual.get(i), "Widget path failed");
        }
        assertEquals("SELFTEST DONE", actual.getLast());
    }

    private static UiDump read(LaunchRequest request, String client, String step) throws Exception {
        return HotkeyAssertions.observation(Files.readString(request.selftestOut().resolve("hotkey")
                .resolve(step + ".json")), client, step);
    }

    private static boolean done(Path log) {
        try { return Files.isRegularFile(log) && Files.readString(log).lines().anyMatch("SELFTEST DONE"::equals); }
        catch (java.io.IOException e) { return false; }
    }

    private static void requireRunning(LaunchedClient client, long deadline, Path log) {
        if (!client.process().isAlive() || System.nanoTime() >= deadline)
            throw new IllegalStateException("Missing SELFTEST DONE: " + log + "\n" + client.stderrTail());
    }

    /** Переиспользует живую вкладку сборщика; только события букв получают русский key при прежнем физическом code. */
    private static void pumpRussian(LaunchedClient server, Path profile, Path log) throws Exception {
        int port = Integer.parseInt(Files.readAllLines(profile.resolve("DevToolsActivePort")).getFirst());
        String handshake = Files.readAllLines(server.stdout()).stream().filter(s -> s.startsWith("PARITY_URL "))
                .reduce((a, b) -> { throw new IllegalStateException("Duplicate PARITY_URL"); }).orElseThrow();
        var sender = TestApiBridge.http(URI.create(handshake.substring("PARITY_URL ".length()).strip()));
        try (var cdp = CdpClient.connectToFirstPage(port, TIMEOUT)) {
            var api = new CdpTestApi(cdp);
            var bridge = new TestApiBridge(new UiTestDriver(ClientKind.WEB, new RussianApi(api, cdp)), sender);
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (!done(log)) {
                requireRunning(server, deadline, log);
                var step = api.takeStep();
                if (step == null) Thread.sleep(20); else bridge.accept(step);
            }
        }
    }

    /** Декоратор настоящего DOM-драйвера: не вызывает намерения контроллера и не строит фактический дамп. */
    private record RussianApi(CdpTestApi delegate, CdpClient cdp) implements UiTestDriver.TestApi {
        /** Возвращает только объявленные страницей операции. */
        @Override public Set<Class<? extends SelfTestCommand>> supported() { return delegate.supported(); }
        /** Отправляет русское событие через реальное дерево DOM или штатную операцию драйвера. */
        @Override public void execute(SelfTestCommand command) throws Exception {
            if (command instanceof SelfTestCommand.Key key && key.chord().key().matches("[A-Z]")) {
                Object result = cdp.evaluate(RussianKeyInput.expression(key.chord()), Duration.ofSeconds(5));
                assertEquals(Boolean.TRUE, result, "Russian DOM event not acknowledged");
            } else delegate.execute(command);
        }
        /** Ждёт реальной очереди страницы. */
        @Override public void awaitIdle(Duration timeout) { delegate.awaitIdle(timeout); }
        /** Читает реальный DOM-дамп. */
        @Override public UiDump dump(String step) { return delegate.dump(step); }
        /** Снимает настоящую страницу. */
        @Override public byte[] screenshot(String step) throws java.io.IOException { return delegate.screenshot(step); }
    }
}
