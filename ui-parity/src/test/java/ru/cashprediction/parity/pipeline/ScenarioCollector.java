package ru.cashprediction.parity.pipeline;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import ru.cashprediction.parity.launch.*;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;

/** Запускает самотесты S3 через стенд S0, проверяет завершение и освобождает дерево процессов и узел. */
public final class ScenarioCollector implements ParityPipeline.Collector {
    private final java.util.function.Function<String, ClientTarget> targets;
    private final Duration timeout;

    /** Создаёт сборщик с фабрикой целей и ограничением времени. */
    public ScenarioCollector(java.util.function.Function<String, ClientTarget> targets, Duration timeout) {
        this.targets = Objects.requireNonNull(targets); this.timeout = Objects.requireNonNull(timeout);
    }

    /** Собирает новый запуск настоящего клиента, включая браузерный путь test.step. */
    @Override public ParityPipeline.Collection collect(String client, String scenario, Path output) throws Exception {
        Path run = output.resolve("run-" + UUID.randomUUID());
        ClientTarget target = ModuleSnapshot.capture(targets.apply(client), run.resolve("module-snapshot"),
                ReactorLayout.fromSystemProperties().root());
        String node = RegistryNodeCleaner.newSelftestNode();
        var before = RegistryNodeCleaner.snapshotRealSessionNodes();
        LaunchRequest request = LaunchRequest.forScenario(run, client, scenario, node);
        try {
            try (ScenarioFixtures fixtures = ScenarioFixtures.prepare(request, timeout);
                 LaunchedClient launched = ClientLauncher.launch(target, request)) {
                Path log = request.selftestOut().resolve("selftest.log");
                if (target.mainClass().equals(ClientTarget.WEB_MAIN)) {
                    try (var web = WebScenarioSession.attach(launched, run.resolve("edge-profile"), timeout)) {
                        long deadline = System.nanoTime() + timeout.toNanos();
                        while (!done(log)) {
                            fixtures.requireHealthy();
                            if (!launched.process().isAlive()) throw new IllegalStateException("Web server exited before SELFTEST DONE");
                            if (System.nanoTime() >= deadline) throw new IllegalStateException("Web test.step timeout: " + log);
                            if (!web.pump()) Thread.sleep(20);
                        }
                    }
                } else launched.waitUntil(() -> { fixtures.requireHealthy(); return done(log); }, timeout, "SELFTEST DONE");
                fixtures.requireComplete();
                String contents = Files.readString(log);
                if (Set.of(ClientTarget.FX_MAIN, ClientTarget.SWING_MAIN, ClientTarget.WEB_MAIN).contains(target.mainClass()))
                    validateLog(scenario, contents);
                if (contents.lines().anyMatch(line -> line.matches("SELFTEST .*\\bFAIL\\b.*")))
                    throw new IllegalStateException("Failed scenario steps: " + log + "\n" + contents.lines()
                            .filter(line -> line.matches("SELFTEST .*\\bFAIL\\b.*")).reduce("", (a, b) -> a + b + "\n"));
                // Нормализуем только случайный префикс: значимый суффикс fx/swing должен остаться в снимке.
                return new ParityPipeline.Collection(request.selftestOut().resolve(scenario),
                        request.home().resolve("CashMemory"), node);
            }
        } finally {
            RegistryNodeCleaner.delete(node);
            if (RegistryNodeCleaner.exists(node)) throw new IllegalStateException("Selftest registry node survived: " + node);
            if (!before.equals(RegistryNodeCleaner.snapshotRealSessionNodes()))
                throw new IllegalStateException("Real session registry changed");
        }
    }

    /** Требует результат каждой строки встроенного сценария, чтобы DONE не скрывал пропуск операций. */
    static void validateLog(String scenario, String contents) {
        var expected = ru.cashprediction.core.ui.selftest.SelfTestScript.load(scenario).lines();
        var lines = contents.lines().toList();
        if (lines.size() != expected.size() + 1 || !lines.getLast().equals("SELFTEST DONE"))
            throw new IllegalStateException("Incomplete selftest log for " + scenario);
        for (int index = 0; index < expected.size(); index++) {
            var step = expected.get(index);
            String prefix = "SELFTEST " + step.number();
            String actual = lines.get(index);
            if (!actual.equals(prefix + " OK " + step.text()) && !actual.startsWith(prefix + " FAIL " + step.text() + ": "))
                throw new IllegalStateException("Selftest step mismatch: " + scenario + ":" + step.number() + " " + actual);
        }
    }

    /** Читает окончание журнала только после его публикации клиентом. */
    private static boolean done(Path log) {
        try { return Files.isRegularFile(log) && Files.readString(log).lines().anyMatch("SELFTEST DONE"::equals); }
        catch (java.io.IOException e) { return false; }
    }
}
