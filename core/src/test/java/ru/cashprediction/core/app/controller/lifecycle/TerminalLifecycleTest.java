package ru.cashprediction.core.app.controller.lifecycle;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.app.fake.ManualScheduler;
import ru.cashprediction.core.session.UiExecutor;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionStore;
import ru.cashprediction.core.app.flow.SessionBridge;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.view.status.StatusLevel;

/** Проверяет реальную очередь исполнителя и запрет любых обращений к клиенту после выхода. */
class TerminalLifecycleTest {
    @TempDir Path home;

    @Test
    void postedStatusExpiryDoesNotCallPortAfterCleanExit() throws Exception {
        Fixture f = new Fixture(home);
        f.app.showMain(null);
        f.app.status(StatusLevel.INFO, "status.msg.snapshot");
        ((ManualScheduler) f.fake.scheduler()).advance(Duration.ofSeconds(10));
        assertFalse(f.queue.isEmpty());
        f.app.closeMainRequested();
        assertTrue(f.exited);
        f.drain();
    }

    @Test
    void recorderNotificationsAndQueuedWhatIfAreDroppedAfterCleanExit() throws Exception {
        Fixture f = new Fixture(home);
        f.app.showMain(null);
        SessionStore store = f.record();
        f.drain();
        f.app.spinnerCommit("whatIf.extra", 120);
        f.app.status(StatusLevel.INFO, "status.msg.snapshot");
        f.advance(Duration.ofSeconds(10));
        assertFalse(f.queue.isEmpty());
        var view = f.app.document().viewState();
        f.app.closeMainRequested();
        assertTrue(f.exited);
        assertFalse(f.queue.isEmpty(), "Clean recorder shutdown posts final store notifications");
        assertEquals(SessionMarker.CLOSED, store.readMarker().orElseThrow().state());
        assertFalse(store.load().orElseThrow().plan().dirty());
        f.drain();
        assertEquals(view, f.app.document().viewState());
    }

    @Test
    void lateIntentsAndDocumentListenerDoNotCallExitedPort() throws Exception {
        Fixture f = new Fixture(home);
        f.app.showMain(null);
        f.app.closeMainRequested();
        var view = f.app.document().viewState();
        f.app.command(CommandId.FILE_NEW, CommandArgs.NONE, InvokeSource.MENU);
        f.app.selectRow("late");
        f.app.activateRow("late", "income", Activation.DOUBLE_CLICK);
        f.app.filterText("late");
        f.app.sliderCommit("view.horizonSlider", 24);
        f.app.spinnerCommit("whatIf.extra", 120);
        f.app.mainGeometry(null, false);
        f.app.menuHover("file.save");
        f.app.closeMainRequested();
        f.app.uncaught(Thread.currentThread(), new IllegalStateException("late"));
        f.app.start();
        assertFalse(f.app.key(KeyChord.parse("Ctrl+N"), FocusScope.TABLE, ""));
        assertEquals(view, f.app.document().viewState());
        // Изменение документа извне проверяет именно слушатель, а не защиту намерения.
        f.app.document().setViewState(view.withMode(ViewMode.CHART));
        assertEquals(1, f.fake.calls("exit").size());
        assertTrue(f.queue.isEmpty());
    }

    @Test
    void cancelExitKeepsRecorderSnapshotAndScheduledWorkAlive() throws Exception {
        Fixture f = new Fixture(home);
        f.app.showMain(null);
        SessionStore store = f.record();
        f.app.document().replace(f.app.document().plan(), null, true, List.of());
        f.app.closeMainRequested();
        assertTrue(store.load().orElseThrow().plan().dirty());
        f.fake.pendingAlerts().getLast().press("cancel");
        assertFalse(f.exited);
        assertFalse(f.app.recorder().isClosed());
        assertFalse(((ManualScheduler) f.fake.scheduler()).isShutdown());
        assertEquals(SessionMarker.RUNNING, store.readMarker().orElseThrow().state());
        f.app.spinnerCommit("whatIf.extra", 120);
        f.advance(Duration.ofMillis(600));
        f.drain();
        assertFalse(f.app.document().viewState().whatIf().isNone());
    }

    @Test
    void discardExitKeepsFinalSnapshotCleanAndDoesNotWriteDiscardedPlan() throws Exception {
        Fixture f = new Fixture(home);
        f.app.showMain(null);
        SessionStore store = f.record();
        f.app.document().replace(f.app.document().plan(), null, true, List.of());
        f.app.closeMainRequested();
        assertTrue(store.load().orElseThrow().plan().dirty());
        f.fake.pendingAlerts().getLast().press("dontSave");
        assertTrue(f.exited);
        assertEquals(SessionMarker.CLOSED, store.readMarker().orElseThrow().state());
        assertFalse(store.load().orElseThrow().plan().dirty());
        f.drain();
        assertFalse(Files.exists(home.resolve("CashMemory/Current.md")));
    }

    @Test
    void failedSaveDoesNotTerminateOrMarkClean() throws Exception {
        Fixture f = new Fixture(home);
        f.app.showMain(null);
        SessionStore store = f.record();
        Path blocker = home.resolve("CashMemory/block");
        Files.createFile(blocker);
        f.app.document().replace(f.app.document().plan(), blocker.resolve("Current.md"), true, List.of());
        f.app.closeMainRequested();
        f.fake.pendingAlerts().getLast().press("save");
        assertFalse(f.exited);
        assertFalse(f.app.recorder().isClosed());
        assertEquals(SessionMarker.RUNNING, store.readMarker().orElseThrow().state());
        assertTrue(store.load().orElseThrow().plan().dirty());
        f.drain();
    }

    @Test
    void simulatedHaltDropsQueuesAndPreservesRunningMarker() throws Exception {
        Fixture f = new Fixture(home);
        f.app.showMain(null);
        SessionStore store = f.record();
        f.app.spinnerCommit("whatIf.extra", 120);
        f.advance(Duration.ofMillis(600));
        f.app.recovery().simulateHalt();
        f.fake.pendingAlerts().getLast().press("halt");
        assertTrue(f.exited);
        assertEquals(ExitKind.HALT, f.fake.exitKind().orElseThrow());
        assertEquals(SessionMarker.RUNNING, store.readMarker().orElseThrow().state());
        assertFalse(f.app.recorder().isClosed());
        f.drain();
        assertTrue(f.app.document().viewState().whatIf().isNone());
    }

    @Test
    void uncaughtExitKeepsCrashSnapshotAndDropsQueuedException() throws Exception {
        Fixture f = new Fixture(home);
        f.app.showMain(null);
        SessionStore store = f.record();
        f.app.document().replace(f.app.document().plan(), null, true, List.of());
        f.app.recovery().simulateException();
        f.advance(Duration.ZERO);
        f.app.uncaught(Thread.currentThread(), new IllegalStateException("test"));
        var fatal = f.fake.pendingAlerts().getLast();
        fatal.press(fatal.spec().defaultButtonId());
        assertTrue(f.exited);
        assertEquals(SessionMarker.RUNNING, store.readMarker().orElseThrow().state());
        assertTrue(store.load().orElseThrow().plan().dirty());
        f.drain();
    }

    @Test
    void lateAlertAndFileChoiceCallbacksAreIgnored() throws Exception {
        Fixture f = new Fixture(home);
        f.app.showMain(null);
        int[] callbacks = { 0 };
        // JavaFX: Alert → Swing: JDialog → Web: dialog.
        f.app.showAlert(AlertCatalog.info("recordingOff"), button -> callbacks[0]++);
        // JavaFX: FileChooser → Swing: JFileChooser → Web: dialog.
        f.app.port().chooseFile(new FileChooserSpec(FileChooserSpec.Purpose.OPEN_PLAN, FileChooserSpec.Mode.OPEN,
                "test", "test", List.of("md"), home, ""), result -> callbacks[0]++);
        f.app.port().exit(ExitKind.HALT, 3);
        f.fake.pendingAlerts().getLast().press("ok");
        f.fake.completeFileChooser(null);
        assertEquals(0, callbacks[0]);
    }

    @Test
    void synchronousExitAnswerDoesNotResumePortCalls() throws Exception {
        Fixture f = new Fixture(home);
        f.app.showMain(null);
        f.synchronousAnswer = "halt";
        f.app.recovery().simulateHalt();
        assertTrue(f.exited);
        assertEquals(1, f.fake.calls("exit").size());
    }

    @Test
    void nativeOpenAnswerAfterExitDoesNotLoadDocument() throws Exception {
        Fixture f = new Fixture(home);
        f.app.showMain(null);
        var before = f.app.document().plan();
        f.app.command(CommandId.FILE_OPEN_FILE, CommandArgs.NONE, InvokeSource.MENU);
        assertEquals(1, f.fake.calls("chooseFile").size());
        f.app.port().exit(ExitKind.HALT, 3);
        // Поздний результат реального FileFlow должен отбрасываться до чтения выбранного пути.
        f.fake.completeFileChooser(home.resolve("CashMemory/missing.md"));
        assertEquals(before, f.app.document().plan());
        f.drain();
    }

    /** Порт с независимой очередью UI: остановка таймеров не удаляет уже доставленные задачи. */
    private static final class Fixture {
        final FakeUiPort fake = new FakeUiPort(ClientProfile.swing());
        final Deque<Runnable> queue = new ArrayDeque<>();
        final AppController app;
        boolean exited;
        String synchronousAnswer;

        Fixture(Path home) throws Exception {
            Files.createDirectories(home.resolve("CashMemory"));
            UiExecutor executor = new UiExecutor() {
                /** {@inheritDoc} */
                @Override public void execute(Runnable task) { queue.addLast(task); }
                /** {@inheritDoc} */
                @Override public boolean isUiThread() { return true; }
            };
            UiPort port = (UiPort) Proxy.newProxyInstance(UiPort.class.getClassLoader(),
                    new Class<?>[] { UiPort.class }, (proxy, method, args) -> {
                        if (exited) throw new AssertionError("Port after exit: " + method.getName());
                        if (method.getName().equals("executor")) return executor;
                        if (method.getName().equals("exit")) exited = true;
                        try {
                            Object result = method.invoke(fake, args);
                            if (method.getName().equals("showAlert") && synchronousAnswer != null) {
                                String answer = synchronousAnswer;
                                synchronousAnswer = null;
                                @SuppressWarnings("unchecked") Consumer<String> callback = (Consumer<String>) args[2];
                                callback.accept(answer);
                            }
                            return result;
                        }
                        catch (InvocationTargetException error) { throw error.getCause(); }
                    });
            app = new AppController(port, new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                    home, home.resolve("CashMemory"), AppClock.fixedToday(LocalDate.of(2026, 10, 1))));
        }

        void drain() {
            while (!queue.isEmpty()) queue.removeFirst().run();
        }

        void advance(Duration delay) { ((ManualScheduler) fake.scheduler()).advance(delay); }

        SessionStore record() {
            SessionStore store = app.environment().registryStore("swing");
            SessionRecorder recorder = new SessionRecorder("swing", List.of(store), app.port().executor(),
                    new SessionBridge(app), app.port().scheduler(), app.environment().clock().clock());
            app.installRecorder(recorder);
            recorder.start();
            return store;
        }
    }
}
