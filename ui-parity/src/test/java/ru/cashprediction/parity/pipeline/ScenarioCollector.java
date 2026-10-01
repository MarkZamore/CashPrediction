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

    /** Собирает новый запуск; неподключённый web-браузер явно отклоняется до появления моста S3. */
    @Override public ParityPipeline.Collection collect(String client, String scenario, Path output) throws Exception {
        ClientTarget target = targets.apply(client);
        if (target.mainClass().equals(ClientTarget.WEB_MAIN))
            throw new UnsupportedOperationException("S3 web test.step browser bridge must be attached before collection");
        Path run = output.resolve("run-" + UUID.randomUUID());
        String node = RegistryNodeCleaner.newSelftestNode();
        var before = RegistryNodeCleaner.snapshotRealSessionNodes();
        LaunchRequest request = LaunchRequest.forScenario(run, client, scenario, node).withUi("core");
        try {
            try (LaunchedClient launched = ClientLauncher.launch(target, request)) {
                Path log = request.selftestOut().resolve("selftest.log");
                launched.waitUntil(() -> done(log), timeout, "SELFTEST DONE");
                String contents = Files.readString(log);
                if (contents.lines().anyMatch(line -> line.matches("SELFTEST .*\\bFAIL\\b.*")))
                    throw new IllegalStateException("Failed scenario steps: " + log);
                return new ParityPipeline.Collection(request.selftestOut().resolve(scenario),
                        request.home().resolve("CashMemory"), node + "/" + client);
            }
        } finally {
            RegistryNodeCleaner.delete(node);
            if (RegistryNodeCleaner.exists(node)) throw new IllegalStateException("Selftest registry node survived: " + node);
            if (!before.equals(RegistryNodeCleaner.snapshotRealSessionNodes()))
                throw new IllegalStateException("Real session registry changed");
        }
    }

    /** Читает окончание журнала только после его публикации клиентом. */
    private static boolean done(Path log) {
        try { return Files.isRegularFile(log) && Files.readString(log).lines().anyMatch("SELFTEST DONE"::equals); }
        catch (java.io.IOException e) { return false; }
    }
}
