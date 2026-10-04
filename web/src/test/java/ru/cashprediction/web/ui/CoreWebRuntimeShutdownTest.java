package ru.cashprediction.web.ui;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.store.MarkdownSessionStore;
import ru.cashprediction.core.ui.command.*;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет живую очередь и настоящий таймаут занятого контроллера, без подмены runtime. */
class CoreWebRuntimeShutdownTest {
    @TempDir Path home;

    /** При свободном UI сохраняется новый ввод, которого ещё нет в последнем захваченном снимке. */
    @Test void healthyQueueCapturesLatestInsteadOfOnlyRewritingOldCapture() throws Exception {
        try (CoreWebRuntime runtime = prepare()) {
            String id = read(runtime, () -> runtime.controller().state().windows().windows().getLast().windowId());
            read(runtime, () -> {
                runtime.port().form(id).orElseThrow().fieldChanged("amount", "fresh-invalid", false, 2);
                return null;
            });
            // Планировщик отключён в prepare: без saveSnapshot новый текст не запишется debounce-таймером.
            assertEquals("captured-invalid", store().load().orElseThrow().window(id).orElseThrow().fields().get("amount"));
            runtime.saveSnapshot();
            assertEquals("fresh-invalid", store().load().orElseThrow().window(id).orElseThrow().fields().get("amount"));
            assertTrue(store().readMarker().orElseThrow().isRunning());
        }
    }

    /** UI удерживается latch дольше пяти секунд: файлы появляются от fallback до освобождения UI. */
    @Test void busyControllerTimeoutStillWritesLastCaptureBeforeUiUnblocks() throws Exception {
        CoreWebRuntime runtime = prepare();
        var listenerCalls = new java.util.concurrent.atomic.AtomicInteger();
        read(runtime, () -> { runtime.controller().recorder().addStatusListener(status -> listenerCalls.incrementAndGet()); return null; });
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        CompletableFuture<Void> blocker = runtime.thread().submit(() -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test controller release timed out");
            return null;
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            SessionSnapshot expected = store().load().orElseThrow();
            Files.delete(store().sessionFile()); Files.delete(store().planFile());
            assertFalse(Files.exists(store().sessionFile()));
            long started = System.nanoTime();
            runtime.saveSnapshot();
            long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(waitedMillis >= 4800, "The real five-second queue timeout must be reached: " + waitedMillis);
            assertFalse(blocker.isDone(), "Capture queue is still blocked when fallback evidence is read");
            assertEquals(1, release.getCount());
            assertEquals(0, listenerCalls.get(), "Quiet fallback must not enqueue UI through status listeners");
            SessionSnapshot actual = store().load().orElseThrow();
            assertEquals(expected, actual, "Fallback writes the exact last capture, not guessed live fields");
            assertEquals(expected.plan().markdown(), Files.readString(store().planFile()));
            assertTrue(actual.plan().dirty());
            assertTrue(store().readMarker().orElseThrow().isRunning());
            assertEquals("captured-invalid", actual.windows().getLast().fields().get("amount"));
        } finally {
            release.countDown();
            try { blocker.get(5, TimeUnit.SECONDS); }
            finally { runtime.close(); }
        }
    }

    /** Остановленная очередь отвергает submit: запасная запись не требует UI и не вызывает слушателей. */
    @Test void rejectedControllerQueueStillWritesQuietSnapshot() throws Exception {
        CoreWebRuntime runtime = prepare();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        read(runtime, () -> { runtime.controller().recorder().addStatusListener(status -> calls.incrementAndGet()); return null; });
        SessionSnapshot expected = store().load().orElseThrow();
        try {
            runtime.thread().close();
            Files.delete(store().sessionFile()); Files.delete(store().planFile());
            runtime.saveSnapshot();
            assertEquals(expected, store().load().orElseThrow());
            assertEquals(0, calls.get(), "No listener may touch a dead controller queue");
            assertTrue(store().readMarker().orElseThrow().isRunning());
        } finally { runtime.close(); }
    }

    /** Создаёт реальный контроллер с несохранённым планом, редактором и последним захваченным снимком. */
    private CoreWebRuntime prepare() throws Exception {
        var options = LaunchOptions.parse(List.of("--home", home.toString(), "--registry", "memory",
                "--registry-node", "ru/cashprediction/selftest/" + UUID.randomUUID(), "--today", "2026-09-13"), new Properties());
        CoreWebRuntime runtime = new CoreWebRuntime(AppEnvironment.from(options));
        try {
            runtime.start();
            read(runtime, () -> {
                String wizard = runtime.controller().state().windows().windows().getLast().windowId();
                var initial = runtime.port().form(wizard).orElseThrow(); initial.shown(); initial.closeRequested();
                runtime.controller().command(CommandId.FILE_SAMPLE, CommandArgs.NONE, InvokeSource.MENU);
                runtime.controller().selectRow("r1@2026-10-05");
                runtime.controller().command(CommandId.EDIT_EDIT, CommandArgs.NONE, InvokeSource.MENU);
                String id = runtime.controller().state().windows().windows().getLast().windowId();
                var form = runtime.port().form(id).orElseThrow(); form.shown();
                form.fieldChanged("amount", "captured-invalid", false, 1);
                runtime.controller().recorder().saveNow();
                runtime.thread().shutdown(); // Только таймеры: очередь execute/submit продолжает работать.
                return null;
            });
            assertTrue(store().load().orElseThrow().plan().dirty());
            return runtime;
        } catch (Exception | AssertionError error) { runtime.close(); throw error; }
    }

    /** Читает реальные файлы независимым экземпляром хранилища. */
    private MarkdownSessionStore store() { return MarkdownSessionStore.inCashMemory(home.resolve("CashMemory")); }
    /** Выполняет setup на настоящем единственном потоке с ограниченным ожиданием. */
    private static <T> T read(CoreWebRuntime runtime, Callable<T> work) throws Exception {
        return runtime.thread().submit(work).get(5, TimeUnit.SECONDS);
    }
}
