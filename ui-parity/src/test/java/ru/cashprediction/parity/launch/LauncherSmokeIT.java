package ru.cashprediction.parity.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.parity.dummy.DummyClientMain;
import ru.cashprediction.parity.io.Dirs;
import ru.cashprediction.parity.process.ProcessTree;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;
import ru.cashprediction.parity.registry.RegistryTreeSnapshot;

/**
 * Дымовой тест лаунчера (стадия S0): заглушка клиента стартует отдельной JVM с module path, изолированной
 * домашней папкой и тестовым узлом реестра, порождает дочернюю JVM, а затем всё дерево процессов завершается
 * без выживших. Настоящие узлы сеанса при этом не меняются.
 */
class LauncherSmokeIT {

    /** Удаляет пустой корень тестовых узлов после всех тестов класса (решение L12). */
    @AfterAll
    static void removeEmptySelftestRoot() {
        RegistryNodeCleaner.deleteSelftestRootIfEmpty();
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void dummyClientRunsIsolatedAndItsWholeProcessTreeIsKilled() throws Exception {
        ReactorLayout layout = ReactorLayout.fromSystemProperties();
        Path jar = DummyClientJar.build(layout.parityRoot().resolve("dummy-jar"));
        ClientTarget target = ClientTarget.dummy(jar, DummyClientJar.codeSource(LaunchOptions.class));
        String node = RegistryNodeCleaner.newSelftestNode();
        LaunchRequest request = LaunchRequest.forScenario(layout.parityRoot(), "dummy", "launcher-smoke", node);
        Dirs.deleteRecursively(request.home());
        RegistryTreeSnapshot before = RegistryNodeCleaner.snapshotRealSessionNodes();

        long rootPid;
        long childPid;
        try (LaunchedClient client = ClientLauncher.launch(target, request)) {
            Path readyFile = request.selftestOut().resolve(DummyClientMain.READY_FILE);
            client.waitForFile(readyFile, Duration.ofSeconds(90));
            Properties ready = new Properties();
            try (InputStream in = Files.newInputStream(readyFile)) {
                ready.load(in);
            }

            // Аргументы §6.3 дошли до настоящего LaunchOptions.parse без предупреждений.
            rootPid = client.pid();
            assertEquals(rootPid, Long.parseLong(ready.getProperty("pid")));
            assertEquals("0", ready.getProperty("warnings"), ready.getProperty("warningText"));
            assertEquals(request.home(), Path.of(ready.getProperty("home")));
            assertEquals(request.home(), Path.of(ready.getProperty("cwd")), "process runs inside its isolated home");
            assertEquals(request.selftestOut(), Path.of(ready.getProperty("selftestOut")));
            assertEquals("2026-09-13", ready.getProperty("today"));
            assertEquals("launcher-smoke", ready.getProperty("selftest"));
            assertEquals(node, ready.getProperty("registryNode"));
            assertEquals("ru", ready.getProperty("user.language"));
            assertEquals("1", ready.getProperty("sun.java2d.uiScale"));
            assertEquals("1", ready.getProperty("glass.win.uiScale"));
            assertTrue(ready.getProperty("modulePath").contains(DummyClientJar.JAR_NAME), ready.getProperty("modulePath"));

            // Дочерняя JVM — как у лаунчера jpackage — жива и видна как потомок корня.
            childPid = Long.parseLong(ready.getProperty("childPid"));
            assertEquals(ready.getProperty("childPid"), ready.getProperty("childReportedPid"));
            final long child = childPid;
            assertTrue(client.process().toHandle().descendants().anyMatch(h -> h.pid() == child),
                    "child JVM must be a descendant of the launched process");
            Optional<ProcessHandle> childHandle = ProcessHandle.of(childPid);
            assertTrue(childHandle.map(ProcessHandle::isAlive).orElse(false), "child JVM must be alive before the kill");

            // Заглушка писала только в тестовый узел.
            assertTrue(RegistryNodeCleaner.exists(node + "/" + DummyClientMain.REGISTRY_CHILD_NODE));

            ProcessTree.KillReport report = client.kill();

            assertTrue(report.contains(rootPid) && report.contains(childPid), "tree snapshot: " + report.processes());
            assertTrue(report.isClean(), "survivors: " + report.survivors());
            assertFalse(client.process().isAlive());
            assertFalse(childHandle.get().isAlive(), "child JVM survived");
            assertTrue(ProcessHandle.allProcesses().noneMatch(h -> h.pid() == child && h.isAlive()
                            && h.info().startInstant().equals(childHandle.get().info().startInstant())),
                    "child JVM is still listed as running");

            // В домашней папке только то, что создал сам процесс; журналы стенда лежат рядом.
            assertEquals(List.of(LaunchRequest.SELFTEST_OUT_DIR), Dirs.names(request.home()));
            assertTrue(Files.isRegularFile(request.logDirectory().resolve("launcher-smoke.command.txt")));
        } finally {
            RegistryNodeCleaner.delete(node);
        }

        assertFalse(RegistryNodeCleaner.exists(node), "selftest node must be deleted");
        assertEquals(List.of(), before.differences(RegistryNodeCleaner.snapshotRealSessionNodes()),
                "real session nodes changed (was a CashPrediction client running during the test?)");
    }
}
