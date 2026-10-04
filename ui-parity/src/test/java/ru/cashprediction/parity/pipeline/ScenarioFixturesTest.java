package ru.cashprediction.parity.pipeline;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.parity.launch.LaunchRequest;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет исходные данные s14/s17/s18, изоляцию и освобождение вспомогательных процессов. */
class ScenarioFixturesTest {
    @TempDir Path root;

    /** Снимок доступен каждому клиенту, время фиксировано, поле действительно содержит ошибочный ввод. */
    @Test void recoverySnapshotUsesRealStoresAndFixedNoon() throws Exception {
        var before = RegistryNodeCleaner.snapshotRealSessionNodes();
        for (String client : List.of("fx", "swing", "web")) {
            String node = RegistryNodeCleaner.newSelftestNode();
            var request = LaunchRequest.forScenario(root, client, "s17-recovery-dialog", node);
            try (var fixtures = ScenarioFixtures.prepare(request, Duration.ofSeconds(3))) {
                var env = environment(request);
                SessionStore store = client.equals("web") ? env.webStore() : env.xmlStore(client);
                var snapshot = store.load().orElseThrow();
                assertEquals(LocalTime.NOON, snapshot.savedAt().atZone(ZoneId.systemDefault()).toLocalTime());
                assertEquals("bad", snapshot.windows().getFirst().field("startBalance"));
                assertEquals(WindowType.PLAN_SETTINGS, snapshot.windows().getFirst().type());
                assertEquals("main", snapshot.windows().getFirst().ownerId());
                assertTrue(Files.isRegularFile(env.cashMemory().resolve(snapshot.main().planPath())));
                assertEquals(CrashDetector.Status.CRASHED, CrashDetector.detect(List.of(store), client).status());
                if (!client.equals("web")) {
                    var registry = env.registryStore(client);
                    assertTrue(registry.load().isEmpty(), "s17 has only an XML snapshot");
                    assertTrue(registry.readMarker().isPresent(), "registry still records the crash marker");
                }
                fixtures.requireHealthy();
            } finally { RegistryNodeCleaner.delete(node); }
            assertFalse(RegistryNodeCleaner.exists(node));
        }
        assertEquals(before, RegistryNodeCleaner.snapshotRealSessionNodes());
    }

    /** Живой чужой pid обнаруживается как второй экземпляр, после close не остаётся процесса. */
    @Test void alreadyRunningHasLiveHolderAndReleasesIt() throws Exception {
        String node = RegistryNodeCleaner.newSelftestNode();
        var request = LaunchRequest.forScenario(root, "swing", "s18-already-running", node);
        long pid;
        try {
            try (var fixtures = ScenarioFixtures.prepare(request, Duration.ofSeconds(3))) {
                var store = environment(request).xmlStore("swing");
                assertEquals(store.load().orElseThrow(), environment(request).registryStore("swing").load().orElseThrow(),
                        "s18 retains both snapshots; the XML-only fixture is limited to s17");
                pid = store.readMarker().orElseThrow().pid();
                assertNotEquals(ProcessHandle.current().pid(), pid);
                assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive());
                assertEquals(CrashDetector.Status.ALREADY_RUNNING, CrashDetector.detect(List.of(store), "swing").status());
                fixtures.requireHealthy();
            }
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
        } finally { RegistryNodeCleaner.delete(node); }
    }

    /** Сигнал удаляется лишь после изменения mtime, содержимое плана сохраняется. */
    @Test void externalChangeAcknowledgesOnlyAfterMutation() throws Exception {
        String node = RegistryNodeCleaner.newSelftestNode();
        var request = LaunchRequest.forScenario(root, "fx", "s14-save-conflicts", node);
        try (var fixtures = ScenarioFixtures.prepare(request, Duration.ofSeconds(3))) {
            Path file = request.home().resolve("CashMemory").resolve(SamplePlan.name() + ".md");
            Files.writeString(file, "test plan");
            var before = Files.getLastModifiedTime(file);
            Path signal = request.selftestOut().resolve("external-change");
            Files.createFile(signal);
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (Files.exists(signal) && System.nanoTime() < deadline) Thread.sleep(10);
            fixtures.requireHealthy();
            assertFalse(Files.exists(signal));
            assertEquals(before.toMillis() + 2000, Files.getLastModifiedTime(file).toMillis());
            assertEquals("test plan", Files.readString(file));
            fixtures.requireComplete();
        } finally { RegistryNodeCleaner.delete(node); }
    }

    /** Просроченный сигнал и неуказанная изоляция не дают успешную подготовку. */
    @Test void timeoutAndUnsafeRegistryAreRejected() throws Exception {
        String node = RegistryNodeCleaner.newSelftestNode();
        var request = LaunchRequest.forScenario(root, "fx", "s14-save-conflicts", node);
        try (var fixtures = ScenarioFixtures.prepare(request, Duration.ofMillis(20))) {
            Thread.sleep(100);
            assertThrows(IllegalStateException.class, fixtures::requireHealthy);
        } finally { RegistryNodeCleaner.delete(node); }
        var unsafe = LaunchRequest.forScenario(root, "fx", "s17-recovery-dialog", "ru/cashprediction/session");
        assertThrows(IllegalArgumentException.class, () -> ScenarioFixtures.prepare(unsafe, Duration.ofSeconds(1)));
    }

    /** Собирает окружение чтения изолированных хранилищ без создания контроллера. */
    private static AppEnvironment environment(LaunchRequest request) {
        return AppEnvironment.from(LaunchOptions.parse("--home", request.home().toString(), "--registry-node",
                request.registryNodePrefix(), "--today", request.today().toString()));
    }
}
