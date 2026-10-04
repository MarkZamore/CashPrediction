package ru.cashprediction.core.session;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.fake.ManualScheduler;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет actual recorder/store completion, включая очереди и IO, без реестра ОС и GUI. */
class SessionRecorderIdleBarrierTest {
    @TempDir Path home;
    @Test void pendingTouchAndQueuedCaptureAndQueuedWriteAreNotIdle() throws Exception {
        try (var f = new Fixture(home)) {
            assertTrue(f.recorder.isSnapshotIdle());
            f.draft.set("new");
            f.recorder.touch();
            assertFalse(f.recorder.isSnapshotIdle());
            assertFalse(f.recorder.hasCompleteCurrentCapture(),"old complete capture cannot bypass pending touch");
            f.scheduler.advance(Duration.ofMillis(400));
            assertEquals("old",f.store.load().orElseThrow().main().filterText());
            assertFalse(f.recorder.isSnapshotIdle(),"capture queued in actual UiExecutor");
            assertFalse(f.recorder.hasCompleteCurrentCapture(),"readiness atomically includes queued work");
            f.uiQueue.removeFirst().run();
            assertEquals("new",f.recorder.lastCaptured().orElseThrow().main().filterText());
            assertFalse(f.recorder.isSnapshotIdle(),"actual write still queued");
            assertEquals("old",f.store.load().orElseThrow().main().filterText());
            f.scheduler.runPending();
            assertTrue(f.recorder.isSnapshotIdle());
            assertEquals("new",f.store.load().orElseThrow().main().filterText());
            assertEquals(Instant.EPOCH,f.recorder.lastSavedAt("registry").orElseThrow());
        }
    }
    @Test void activeActualStoreWriteAndOutcomeCallbackAreNotIdle() throws Exception {
        try (var f = new Fixture(home)) {
            f.blockWrite = true;
            f.draft.set("new");
            f.recorder.touch(); f.scheduler.advance(Duration.ofMillis(400));
            f.uiQueue.removeFirst().run();
            var worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> task = worker.submit(f.scheduler::runPending);
                assertTrue(f.entered.await(2,TimeUnit.SECONDS));
                assertFalse(f.recorder.isSnapshotIdle(),"write reserved before store IO");
                f.release.countDown(); task.get(2,TimeUnit.SECONDS);
                assertTrue(f.recorder.isSnapshotIdle());
                assertEquals("new",f.store.load().orElseThrow().main().filterText());
            } finally { f.release.countDown(); worker.shutdownNow(); worker.awaitTermination(2,TimeUnit.SECONDS); }
        }
    }
    @Test void currentFailedWriteIsSettledButNotStoragePass() throws Exception {
        try (var f = new Fixture(home)) {
            f.failWrite = true; f.draft.set("new");
            f.recorder.touch(); f.scheduler.advance(Duration.ofMillis(400));
            f.uiQueue.removeFirst().run(); f.scheduler.runPending();
            assertTrue(f.recorder.isSnapshotIdle(),"explicit current store error settles operation");
            assertFalse(f.outcomes.getLast().ok());
            assertEquals("old",f.store.load().orElseThrow().main().filterText());
            assertFalse(f.outcomes.getLast().message().isBlank());
        }
    }
    @Test void rejectedWriteDispatchIsFailClosedUntilNewActualCompletion() throws Exception {
        try (var f = new Fixture(home)) {
            f.rejectWrite = true; f.draft.set("new");
            f.recorder.touch(); f.scheduler.advance(Duration.ofMillis(400));
            assertThrows(RejectedExecutionException.class,() -> f.uiQueue.removeFirst().run());
            assertFalse(f.recorder.isSnapshotIdle());
            assertEquals("old",f.store.load().orElseThrow().main().filterText());
            f.rejectWrite = false;
            f.recorder.touch(); f.scheduler.advance(Duration.ofMillis(400));
            f.uiQueue.removeFirst().run(); f.scheduler.runPending();
            assertTrue(f.recorder.isSnapshotIdle());
            assertEquals("new",f.store.load().orElseThrow().main().filterText());
        }
    }
    @Test void unchangedPeriodicCaptureStillSettlesAndRedebounceWaitsLatestTouch() throws Exception {
        try (var f = new Fixture(home)) {
            f.scheduler.advance(Duration.ofSeconds(5)); assertFalse(f.recorder.isSnapshotIdle());
            f.uiQueue.removeFirst().run(); assertFalse(f.recorder.isSnapshotIdle());
            f.scheduler.runPending(); assertTrue(f.recorder.isSnapshotIdle());
            f.draft.set("new"); f.recorder.touch();
            f.scheduler.advance(Duration.ofMillis(399)); f.recorder.touch();
            f.scheduler.advance(Duration.ofMillis(1));
            assertFalse(f.recorder.isSnapshotIdle());
            assertTrue(f.uiQueue.isEmpty());
            f.scheduler.advance(Duration.ofMillis(399));
            f.uiQueue.removeFirst().run(); f.scheduler.runPending();
            assertTrue(f.recorder.isSnapshotIdle());
            assertEquals("new",f.store.load().orElseThrow().main().filterText());
        }
    }
    /** Собственные scheduler/queue и actual memory RegistrySessionStore; proxy только блокирует/отказывает IO. */
    @Test void omittedRegisteredWindowAndOldFallbackCannotAcknowledgeCurrentCapture() throws Exception {
        try (var f = new Fixture(home)) {
            StatefulWindow broken = new StatefulWindow() {
                @Override public String windowId() { return "w1"; }
                @Override public WindowType windowType() { return WindowType.GOAL_CALCULATOR; }
                @Override public boolean modal() { return false; }
                @Override public String ownerId() { return WindowState.MAIN_OWNER; }
                @Override public WindowState captureState() { return null; }
                @Override public void applyState(WindowState state) { }
            };
            f.recorder.register(broken);
            f.scheduler.advance(Duration.ofMillis(400)); f.uiQueue.removeFirst().run(); f.scheduler.runPending();
            assertTrue(f.store.load().orElseThrow().windows().isEmpty(),"existing partial preservation unchanged");
            assertTrue(f.recorder.isSnapshotIdle(),"operation settles without claiming completeness");
            assertFalse(f.recorder.hasCompleteCurrentCapture(),"omitted owned window is incomplete");
            f.recorder.unregister(broken);
            f.scheduler.advance(Duration.ofMillis(400)); f.uiQueue.removeFirst().run(); f.scheduler.runPending();
            assertTrue(f.recorder.isSnapshotIdle());
            f.failCapture = true;
            f.recorder.saveNow(); // actual existing fallback path, never invoked by awaitIdle
            assertTrue(f.recorder.isSnapshotIdle(),"fallback write settles");
            assertFalse(f.recorder.hasCompleteCurrentCapture(),"old fallback is not current successful capture");
        }
    }
    @Test void partialFieldPageOwnerAndReplacementAreCapturedWithoutMainRevisionClock() throws Exception {
        try (var f = new Fixture(home)) {
            var state = new AtomicReference<>(new WindowState("w1",WindowType.GOAL_CALCULATOR,false,
                    WindowState.MAIN_OWNER,new WindowBounds(1,2,300,200),Map.of("page","1"),Map.of("amount","1,")));
            StatefulWindow window = owned(state);
            f.recorder.register(window);
            f.scheduler.advance(Duration.ofMillis(400)); f.uiQueue.removeFirst().run(); f.scheduler.runPending();
            assertTrue(f.recorder.isSnapshotIdle());
            state.set(new WindowState("w1",WindowType.GOAL_CALCULATOR,false,WindowState.MAIN_OWNER,
                    new WindowBounds(3,4,400,300),Map.of("page","2"),Map.of("amount","invalid raw")));
            f.recorder.touch(); f.scheduler.advance(Duration.ofMillis(40));
            assertFalse(f.recorder.isSnapshotIdle());
            assertEquals("1,",f.store.load().orElseThrow().windows().getFirst().fields().get("amount"));
            f.scheduler.advance(Duration.ofMillis(360)); f.uiQueue.removeFirst().run(); f.scheduler.runPending();
            assertTrue(f.recorder.isSnapshotIdle());
            assertEquals(state.get(),f.store.load().orElseThrow().windows().getFirst());
            f.recorder.unregister(window);
            var replacement = new AtomicReference<>(state.get().withContext(Map.of("page","3")));
            f.recorder.register(owned(replacement));
            assertFalse(f.recorder.isSnapshotIdle());
            f.scheduler.advance(Duration.ofMillis(400)); f.uiQueue.removeFirst().run(); f.scheduler.runPending();
            assertTrue(f.recorder.isSnapshotIdle());
            assertEquals(replacement.get(),f.store.load().orElseThrow().windows().getFirst());
        }
    }
    /** Actual registered window source; запись и сериализация остаются production recorder/store. */
    @Test void cancelledOldEpochCannotAcknowledgeReenabledRecorder() throws Exception {
        try (var f = new Fixture(home)) {
            f.draft.set("new");
            f.recorder.touch(); f.scheduler.advance(Duration.ofMillis(400));
            f.recorder.setEnabled(false); f.recorder.setEnabled(true);
            f.uiQueue.removeFirst().run(); // old reserved capture is cancelled, not new epoch ack
            assertFalse(f.recorder.hasCompleteCurrentCapture());
            assertFalse(f.recorder.isSnapshotIdle(),"new enable has relevant pending touch");
            assertEquals("old",f.store.load().orElseThrow().main().filterText());
            f.scheduler.advance(Duration.ofMillis(400));
            f.uiQueue.removeFirst().run(); f.scheduler.runPending();
            assertTrue(f.recorder.isSnapshotIdle()); assertTrue(f.recorder.hasCompleteCurrentCapture());
            assertEquals("new",f.store.load().orElseThrow().main().filterText());
        }
    }
    @Test void clearInvalidatesAlreadyQueuedWriteBeforeNewSnapshot() throws Exception {
        try (var f = new Fixture(home)) {
            f.draft.set("new");
            f.recorder.touch(); f.scheduler.advance(Duration.ofMillis(400)); f.uiQueue.removeFirst().run();
            f.recorder.clearSnapshots(); f.scheduler.runPending();
            assertTrue(f.store.load().isEmpty(),"old epoch queued write must not resurrect cleared snapshot");
            assertFalse(f.recorder.hasCompleteCurrentCapture());
            assertFalse(f.recorder.isSnapshotIdle());
            f.scheduler.advance(Duration.ofMillis(400)); f.uiQueue.removeFirst().run(); f.scheduler.runPending();
            assertTrue(f.recorder.isSnapshotIdle()); assertTrue(f.recorder.hasCompleteCurrentCapture());
            assertEquals("new",f.store.load().orElseThrow().main().filterText());
        }
    }
    /** Actual registered window source; запись и сериализация остаются production recorder/store. */
    private static StatefulWindow owned(AtomicReference<WindowState> state) {
        return new StatefulWindow() {
            @Override public String windowId() { return state.get().id(); }
            @Override public WindowType windowType() { return state.get().type(); }
            @Override public boolean modal() { return state.get().modal(); }
            @Override public String ownerId() { return state.get().ownerId(); }
            @Override public WindowState captureState() { return state.get(); }
            @Override public void applyState(WindowState value) { state.set(value); }
        };
    }
    /** Собственные scheduler/queue и actual memory RegistrySessionStore; proxy только блокирует/отказывает IO. */
    private static final class Fixture implements AutoCloseable {
        final RegistrySessionStore store;
        final ManualScheduler scheduler = new ManualScheduler();
        final Deque<Runnable> uiQueue = new ArrayDeque<>();
        final AtomicReference<String> draft = new AtomicReference<>("old");
        final List<StoreStatus> outcomes = new ArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final SessionRecorder recorder;
        volatile boolean blockWrite,failWrite,rejectWrite,failCapture;
        Fixture(Path home) {
            store = RegistrySessionStore.inMemory("fx",home.resolve("CashMemory"));
            SessionStore bounded = (SessionStore)Proxy.newProxyInstance(SessionStore.class.getClassLoader(),
                    new Class<?>[]{SessionStore.class},(proxy,method,args) -> {
                        if (method.getName().equals("save")) {
                            if(blockWrite) { entered.countDown(); if(!release.await(2,TimeUnit.SECONDS)) throw new AssertionError("blocked fixture timeout"); }
                            if(failWrite) throw new SessionStoreException(SessionStoreException.Code.IO_ERROR,"fixture IO");
                        }
                        try { return method.invoke(store,args); }
                        catch(InvocationTargetException failure) { throw failure.getCause(); }
                    });
            Scheduler dispatch = new Scheduler() {
                @Override public Task schedule(Runnable action,Duration delay) { return scheduler.schedule(action,delay); }
                @Override public Task scheduleAtFixedRate(Runnable action,Duration initial,Duration period) { return scheduler.scheduleAtFixedRate(action,initial,period); }
                @Override public void execute(Runnable action) {
                    if(rejectWrite) throw new RejectedExecutionException("fixture scheduler");
                    scheduler.execute(action);
                }
                @Override public void shutdown() { scheduler.shutdown(); }
            };
            UiExecutor ui = new UiExecutor() {
                @Override public void execute(Runnable action) { uiQueue.addLast(action); }
                @Override public boolean isUiThread() { return true; }
            };
            SnapshotSource source = new SnapshotSource() {
                @Override public MainWindowState captureMain() {
                    if(failCapture) throw new IllegalStateException("fixture capture");
                    return new MainWindowState(null,false,"TABLE","","12m",Map.of(),draft.get(),"");
                }
                @Override public PlanState capturePlan() { return PlanState.CLEAN; }
            };
            recorder = new SessionRecorder("fx",List.of(bounded),ui,source,dispatch,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC),42);
            recorder.addStatusListener(outcomes::add);
            recorder.start(); recorder.saveNow();
        }
        @Override public void close() {
            release.countDown(); blockWrite=false;failWrite=false;rejectWrite=false;failCapture=false;
            recorder.shutdownClean(); scheduler.shutdown(); uiQueue.clear();
        }
    }
}
