package ru.cashprediction.core.session;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.file.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.session.store.*;
import ru.cashprediction.core.update.lifecycle.UpdateLifecycle;
import ru.cashprediction.core.update.model.InstalledVersion;
import ru.cashprediction.core.update.net.UpdatePreparer;

/** Проверяет машинные причины настоящих producers при запуске тестов и на classpath, и на module path. */
class RecoveryUpdateMachineRegressionTest {
    @TempDir Path memory;

    /** Реальное повреждение XML имеет категорию, сохраняет причину и не меняет исходные байты. */
    @Test void corruptXmlReportsMachineCode() throws Exception {
        assertSame(SessionSnapshot.class.getModule(), XmlSessionStore.class.getModule());
        Path file = memory.resolve("session-fx.xml");
        Files.writeString(file, "<broken>");
        byte[] before = Files.readAllBytes(file);
        SessionStoreException failure = assertThrows(SessionStoreException.class, () -> new XmlSessionStore(file, "fx").load());
        assertNotNull(failure.getCause());
        assertEquals("CORRUPT", requiredMethod(SessionStoreException.class, "code").invoke(failure).toString());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    /** Реальный CRC отказ JSON chunk store классифицируется без чтения локализованной строки. */
    @Test void corruptRegistryReportsMachineCode() throws Exception {
        var backend = new InMemoryRegistryBackend();
        var store = new RegistrySessionStore(backend, "fx");
        store.save(SessionSnapshot.of(java.time.Instant.EPOCH, "fx", MainWindowState.empty(), PlanState.CLEAN, java.util.List.of()));
        backend.put(RegistrySessionStore.KEY_SNAPSHOT_CRC, "00000000");
        var before = backend.contents();
        SessionStoreException failure = assertThrows(SessionStoreException.class, store::load);
        assertEquals("CORRUPT", SessionStoreException.class.getMethod("code").invoke(failure).toString());
        assertEquals(before, backend.contents());
    }

    /** Инертный реальный lifecycle выдаёт типизированное состояние без побочных эффектов. */
    @Test void lifecycleExposesInertDecisionAndStatus() throws Exception {
        assertSame(SessionSnapshot.class.getModule(), UpdateLifecycle.class.getModule());
        assertTrue(ru.cashprediction.core.io.AppInfo.isDevelopmentBuild(), "own resources must be developer build");
        Path root = memory.resolve("inert");
        UpdateLifecycle lifecycle = UpdateLifecycle.create(root, root.resolve("CashMemory"), "fx", new String[0]);
        Object decision = requiredMethod(UpdateLifecycle.class, "beforeUiResult").invoke(lifecycle);
        assertEquals(true, decision.getClass().getMethod("allowed").invoke(decision));
        Object status = requiredMethod(UpdateLifecycle.class, "status").invoke(lifecycle);
        assertEquals("INACTIVE", status.getClass().getMethod("stage").invoke(status).toString());
        lifecycle.afterUiReady();
        lifecycle.close();
        assertFalse(Files.exists(root));
    }

    /** Настоящий подготовитель получает локальную ошибку до сети и не теряет её за boolean false. */
    @Test void preparerReportsFailureBeforeAnyNetworkRequest() throws Exception {
        assertSame(SessionSnapshot.class.getModule(), UpdatePreparer.class.getModule());
        Path root = memory.resolve("file-root");
        Files.writeString(root, "blocker");
        try (UpdatePreparer preparer = UpdatePreparer.forSelftest(root,
                new InstalledVersion(1, "a".repeat(40), "b".repeat(64)), URI.create("http://127.0.0.1:9/update.json"))) {
            assertFalse(preparer.prepare());
            Optional<?> problem = (Optional<?>) requiredMethod(UpdatePreparer.class, "lastProblem").invoke(preparer);
            Object value = problem.orElseThrow();
            assertEquals("PREPARATION_FAILED", value.getClass().getMethod("code").invoke(value).toString());
            assertFalse(value.getClass().getMethod("detail").invoke(value).toString().isBlank());
        }
        assertEquals("blocker", Files.readString(root));
    }
    /** Отсутствие API в baseline является assertion failure, а не скрытым пропуском регрессии. */
    private static java.lang.reflect.Method requiredMethod(Class<?> type, String name) {
        return java.util.Arrays.stream(type.getMethods()).filter(method -> method.getName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("Missing machine contract: " + type.getName() + "." + name));
    }
}
