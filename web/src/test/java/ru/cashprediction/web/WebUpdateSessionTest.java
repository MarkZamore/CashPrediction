package ru.cashprediction.web;

import java.nio.file.Path;
import java.util.Properties;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.LaunchOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет настоящую привязку событий без запуска GUI, сети и обновления файлов. */
final class WebUpdateSessionTest {
    @Test void exactClientAndCoreEnvironmentWithUnmodifiedPrivateArgs() {
        var options = LaunchOptions.parse(List.of("--home", "portable/../portable-root"), new Properties());
        var environment = AppEnvironment.from(options);
        String[] args = { "--updated-from=" + "a".repeat(40), "--home=other-root",
                "--registry-node", "ru/cashprediction/selftest/unused", "--no-window" };
        String[] original = args.clone();
        AtomicInteger factories = new AtomicInteger();
        var session = WebUpdateSession.open(environment, args, (root, memory, client, passed) -> {
            factories.incrementAndGet();
            assertEquals(Path.of("portable-root").toAbsolutePath().normalize(), root);
            assertEquals(environment.appHome(), root);
            assertEquals(environment.cashMemory(), memory);
            assertEquals(root.resolve("CashMemory"), memory);
            assertEquals("web", client);
            assertArrayEquals(original, passed);
            // Только core решает, какие аргументы допускаются для перезапуска.
            passed[0] = "factory-mutated";
            return new WebUpdateSession.Calls(() -> true, () -> { }, () -> { });
        });
        args[0] = "caller-mutated";
        assertEquals(0, factories.get());
        assertTrue(session.beforeUi());
        assertTrue(session.beforeUi());
        assertEquals(1, factories.get());
        session.close();
        assertEquals("caller-mutated", args[0]);
    }

    @Test void exactOrderAndSinglePass() {
        List<String> events = new ArrayList<>();
        var session = new WebUpdateSession(() -> {
            events.add("create");
            return new WebUpdateSession.Calls(() -> { events.add("before"); return true; },
                    () -> events.add("ready"), () -> events.add("close"));
        });
        session.mainReady(); session.httpReady();
        assertTrue(events.isEmpty());
        assertTrue(session.beforeUi());
        assertTrue(session.beforeUi());
        session.mainReady(); session.httpReady();
        session.mainReady(); session.httpReady();
        session.close();
        session.close();
        session.mainReady(); session.httpReady();
        assertFalse(session.beforeUi());
        assertEquals(List.of("create", "before", "ready", "close"), events);
    }

    @Test void deferredLaunchNeverStartsPreparation() {
        List<String> events = new ArrayList<>();
        var session = new WebUpdateSession(() -> new WebUpdateSession.Calls(
                () -> { events.add("before"); return false; },
                () -> events.add("ready"), () -> events.add("close")));
        assertFalse(session.beforeUi());
        session.mainReady(); session.httpReady();
        session.close();
        assertEquals(List.of("before", "close"), events);
    }

    @Test void factoryFailureDeniesUiWithoutNotificationOrRetry() {
        AtomicInteger attempts = new AtomicInteger();
        var session = new WebUpdateSession(() -> { attempts.incrementAndGet(); throw new IllegalStateException("factory"); });
        assertFalse(session.beforeUi());
        assertFalse(session.beforeUi());
        session.mainReady(); session.httpReady();
        session.close();
        assertEquals(1, attempts.get());
    }

    @Test void beforeFailureStillClosesCreatedLifecycle() {
        AtomicInteger closes = new AtomicInteger();
        var session = new WebUpdateSession(() -> new WebUpdateSession.Calls(
                () -> { throw new IllegalStateException("before"); },
                () -> fail("ready"), () -> closes.incrementAndGet()));
        assertFalse(session.beforeUi());
        session.mainReady(); session.httpReady();
        assertEquals(1, closes.get());
    }

    @Test void readyAndCloseFailuresNeverReachUiAndAreNotRetried() {
        AtomicInteger preparations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        var session = new WebUpdateSession(() -> new WebUpdateSession.Calls(() -> true,
                () -> { preparations.incrementAndGet(); throw new IllegalStateException("ready"); },
                () -> { closes.incrementAndGet(); throw new IllegalStateException("close"); }));
        assertTrue(session.beforeUi());
        assertDoesNotThrow(() -> { session.mainReady(); session.httpReady(); session.mainReady(); session.httpReady(); session.close(); session.close(); });
        assertEquals(1, preparations.get());
        assertEquals(1, closes.get());
    }

    @Test void linkageFailureIsIsolatedButVmFailureIsNotHidden() {
        var missing = new WebUpdateSession(() -> { throw new NoClassDefFoundError("update"); });
        assertFalse(missing.beforeUi());
        var vm = new WebUpdateSession(() -> { throw new OutOfMemoryError("test"); });
        assertThrows(OutOfMemoryError.class, vm::beforeUi);
    }

    @Test void interruptIsPreservedOnSilentFailure() {
        Thread.interrupted();
        try {
            var session = new WebUpdateSession(() -> new WebUpdateSession.Calls(() -> true,
                    () -> { throw new InterruptedException("ready"); }, () -> { }));
            assertTrue(session.beforeUi());
            session.mainReady(); session.httpReady();
            assertTrue(Thread.currentThread().isInterrupted());
            session.close();
        } finally { Thread.interrupted(); }
    }

    @Test void closeBeforeStartDoesNotCreateLeaseOrPrepare() {
        var session = new WebUpdateSession(() -> { fail("create"); return null; });
        session.close();
        assertFalse(session.beforeUi());
        session.mainReady(); session.httpReady();
    }

    @Test void closeWinsAgainstLateReadyCallback() throws Exception {
        AtomicInteger preparations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        var session = new WebUpdateSession(() -> new WebUpdateSession.Calls(() -> true,
                () -> preparations.incrementAndGet(), () -> closes.incrementAndGet()));
        assertTrue(session.beforeUi());
        CountDownLatch closed = new CountDownLatch(1);
        Thread closer = new Thread(() -> { try { session.close(); } finally { closed.countDown(); } });
        closer.start();
        assertTrue(closed.await(2, TimeUnit.SECONDS));
        session.mainReady(); session.httpReady();
        closer.join(2000);
        assertFalse(closer.isAlive());
        assertEquals(0, preparations.get());
        assertEquals(1, closes.get());
    }

    @Test void noWindowReadinessNeedsHttpAndMainInEitherOrder() {
        for (boolean httpFirst : List.of(false, true)) {
            AtomicInteger ready = new AtomicInteger();
            var session = new WebUpdateSession(() -> new WebUpdateSession.Calls(() -> true,
                    () -> ready.incrementAndGet(), () -> { }));
            assertTrue(session.beforeUi());
            if (httpFirst) session.httpReady(); else session.mainReady();
            assertEquals(0, ready.get());
            if (httpFirst) session.mainReady(); else session.httpReady();
            assertEquals(1, ready.get());
            session.close();
        }
    }

    @Test void recoveryPendingOrBindFailureNeverPrepares() {
        for (boolean bound : List.of(false, true)) {
            AtomicInteger ready = new AtomicInteger();
            var session = new WebUpdateSession(() -> new WebUpdateSession.Calls(() -> true,
                    () -> ready.incrementAndGet(), () -> { }));
            assertTrue(session.beforeUi());
            if (bound) session.httpReady(); else session.mainReady();
            session.close();
            session.httpReady();
            session.mainReady();
            assertEquals(0, ready.get());
        }
    }

}
