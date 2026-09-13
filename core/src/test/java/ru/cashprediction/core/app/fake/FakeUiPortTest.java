package ru.cashprediction.core.app.fake;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DirectoryChooserSpec;
import ru.cashprediction.core.app.ExitKind;
import ru.cashprediction.core.app.FileChooserSpec;
import ru.cashprediction.core.app.FocusTarget;
import ru.cashprediction.core.app.MainGeometry;
import ru.cashprediction.core.app.Placement;
import ru.cashprediction.core.app.RevealMode;
import ru.cashprediction.core.app.WindowHandle;
import ru.cashprediction.core.session.Scheduler;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.alert.AlertButton;
import ru.cashprediction.core.ui.alert.AlertKind;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.ButtonRole;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.Presentation;

/**
 * Самопроверка тестового порта: запись вызовов, заготовленные ответы ровно один раз, выбор файлов, планировщик с
 * виртуальным временем и прямой исполнитель.
 */
class FakeUiPortTest {

    private static AlertSpec confirm(String purpose) {
        return new AlertSpec(AlertKind.CONFIRMATION, purpose, "", "t", "", "h", "c", "", false, 460,
                List.of(new AlertButton("ok", "ok", ButtonRole.OK, true, ""),
                        new AlertButton("off", "off", ButtonRole.OTHER, false, ""),
                        new AlertButton("cancel", "cancel", ButtonRole.CANCEL, true, "")),
                "ok", false);
    }

    @Test
    void recordsMainWindowCallsInOrder() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        port.setMainGeometry(new MainGeometry(new WindowBounds(1, 2, 1200, 800), true));
        port.focus(FocusTarget.TABLE);
        port.revealRow("r1@2026-10-05", RevealMode.SELECT_AND_SCROLL);
        port.copyToClipboard("строка");
        MainGeometry geometry = port.mainGeometry();
        port.exit(ExitKind.CLEAN, 0);

        assertEquals(List.of("focus", "revealRow", "copyToClipboard", "mainGeometry", "exit"), port.methods());
        assertEquals(RevealMode.SELECT_AND_SCROLL, port.calls("revealRow").getFirst().arg(1, RevealMode.class));
        assertEquals("строка", port.clipboard());
        assertTrue(geometry.maximized());
        assertEquals(Optional.of(ExitKind.CLEAN), port.exitKind());
        assertEquals(0, port.exitCode());
        assertTrue(port.executor().isUiThread());
        List<String> ran = new ArrayList<>();
        port.executor().execute(() -> ran.add("now"));
        assertEquals(List.of("now"), ran, "исполнитель прямой");
    }

    @Test
    void scriptedAlertAnswerArrivesAfterShowAlertExactlyOnce() {
        FakeUiPort port = new FakeUiPort(ClientProfile.fx("25"));
        port.answerAlert("cancel").answerAlert("deleteRule", "ok");
        List<String> answers = new ArrayList<>();

        port.showAlert(confirm("unsavedChanges"), null, id -> answers.add("unsaved:" + id));
        WindowHandle delete = port.showAlert(confirm("deleteRule"), null, id -> answers.add("delete:" + id));
        assertEquals(List.of(), answers, "ответ не приходит внутри showAlert");

        port.pump();
        assertEquals(List.of("unsaved:cancel", "delete:ok"), answers, "ответ по назначению важнее общей очереди");
        assertFalse(delete.showing());
        assertEquals(List.of(), port.pendingAlerts());
        assertEquals(2, port.alerts().size());
    }

    @Test
    void unscriptedAlertWaitsForPressAndRejectsSecondAnswer() {
        FakeUiPort port = new FakeUiPort(ClientProfile.web());
        List<String> answers = new ArrayList<>();
        port.showAlert(confirm("actualize"), null, answers::add);
        port.pump();
        assertEquals(1, port.pendingAlerts().size());

        FakeUiPort.PendingAlert pending = port.pendingAlerts().getFirst();
        assertThrows(IllegalArgumentException.class, () -> pending.press("off"), "отключённую кнопку нажать нельзя");
        assertThrows(IllegalArgumentException.class, () -> pending.press("absent"));
        pending.press("ok");
        assertThrows(IllegalStateException.class, () -> pending.press("cancel"));
        assertEquals(List.of("ok"), answers);
        assertTrue(pending.handle().closed());
    }

    @Test
    void fileAndDirectoryChoosersFollowTheScript(@TempDir Path dir) {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        FileChooserSpec open = new FileChooserSpec(FileChooserSpec.Purpose.OPEN_PLAN, FileChooserSpec.Mode.OPEN, "t",
                "f", List.of("md"), dir, "");
        port.chooseFileResult(dir.resolve("a.md")).chooseFileResult(null).chooseDirectoryResult(dir);
        List<Optional<Path>> results = new ArrayList<>();

        port.chooseFile(open, results::add);
        port.chooseFile(open, results::add);
        port.chooseDirectory(new DirectoryChooserSpec("d", dir), results::add);
        port.chooseFile(open, results::add);
        port.pump();
        assertEquals(List.of(Optional.of(dir.resolve("a.md")), Optional.empty(), Optional.of(dir)), results);

        port.completeFileChooser(dir.resolve("late.md"));
        assertEquals(Optional.of(dir.resolve("late.md")), results.getLast());
        assertThrows(IllegalStateException.class, () -> port.completeFileChooser(null));
        assertEquals(3, port.fileRequests().size());
        assertEquals(1, port.directoryRequests().size());
    }

    @Test
    void serverBrowserClientsMustNotUseNativeChoosers(@TempDir Path dir) {
        FakeUiPort port = new FakeUiPort(ClientProfile.web());
        FileChooserSpec save = new FileChooserSpec(FileChooserSpec.Purpose.EXPORT_CSV, FileChooserSpec.Mode.SAVE, "t",
                "f", List.of("csv"), dir, "plan.csv");
        assertThrows(IllegalStateException.class, () -> port.chooseFile(save, r -> { }));
        assertThrows(IllegalStateException.class, () -> port.chooseDirectory(new DirectoryChooserSpec("d", dir), r -> { }));
    }

    @Test
    void openFormRecordsSessionAndHandleUpdates(@TempDir Path cashMemory) throws Exception {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppState state = FakeStates.empty(ClientProfile.swing(), cashMemory);
        FormLogic logic = new FormLogic() {
            @Override
            public FormSpec spec(FormContext context) {
                return null;
            }

            @Override
            public Map<String, String> defaults(FormContext context) {
                return Map.of();
            }

            @Override
            public FormView evaluate(FormState formState, FormContext context) {
                return null;
            }

            @Override
            public FormOutcome onButton(String buttonId, FormState formState, FormContext context) {
                return new FormOutcome.Close(null);
            }
        };
        FormSession session = new FormSession(WindowType.TEXT_INPUT, true, logic,
                new FormContext("w1", WindowState.MAIN_OWNER, Map.of("purpose", "rename"), state), new NoHost());
        FormSpec spec = new FormSpec("rename", WindowType.TEXT_INPUT, "rename", Presentation.TEXT_INPUT, "t", "", 460,
                true, false, true, List.of(), List.of(), "");
        FormView view = new FormView(1, 0, "h", null, null, null, null, null, "", false);
        WindowBounds bounds = new WindowBounds(10, 20, 460, 200);

        FakeWindowHandle handle = (FakeWindowHandle) port.openForm(session, spec, view, Placement.restored("main", bounds));
        handle.update(new FormView(2, 0, "h2", null, null, null, null, null, "", false));
        handle.toFront();
        handle.setBounds(new WindowBounds(0, 0, 500, 220));

        FakeUiPort.OpenedForm opened = port.forms().getFirst();
        assertSame(session, opened.session());
        assertEquals("w1", opened.session().windowId());
        assertEquals(bounds, port.forms().getFirst().placement().bounds());
        assertEquals(List.of(2L), handle.updates().stream().map(FormView::revision).toList());
        assertEquals(1, handle.toFrontCount());
        assertEquals(500, handle.bounds().width());
        assertTrue(handle.showing());
        assertArrayEquals(FakeUiPort.PNG_SIGNATURE, port.renderChartPng(null));
        assertEquals(EnumSet.of(ru.cashprediction.core.ui.view.ScreenPart.STATUS),
                recordRender(port).arg(1, EnumSet.class));
    }

    private static FakeUiPort.Call recordRender(FakeUiPort port) {
        port.render(null, EnumSet.of(ru.cashprediction.core.ui.view.ScreenPart.STATUS));
        return port.calls("render").getFirst();
    }

    @Test
    void manualSchedulerRunsTimersInVirtualTime() {
        FakeUiPort port = new FakeUiPort(ClientProfile.fx("25"));
        ManualScheduler scheduler = port.manualScheduler();
        assertSame(scheduler, port.scheduler());
        List<String> log = new ArrayList<>();

        Scheduler.Task autosave = scheduler.schedule(() -> log.add("autosave"), Duration.ofSeconds(1));
        scheduler.schedule(() -> log.add("settings"), Duration.ofMillis(700));
        scheduler.schedule(() -> log.add("same-due-second"), Duration.ofMillis(700));
        scheduler.scheduleAtFixedRate(() -> log.add("tick"), Duration.ofSeconds(5), Duration.ofSeconds(5));
        scheduler.execute(() -> log.add("queued"));

        scheduler.advance(Duration.ofMillis(699));
        assertEquals(List.of("queued"), log);
        scheduler.advance(Duration.ofMillis(1));
        assertEquals(List.of("queued", "settings", "same-due-second"), log);
        autosave.cancel();
        scheduler.advance(Duration.ofSeconds(10));
        assertEquals(List.of("queued", "settings", "same-due-second", "tick", "tick"), log);
        assertEquals(Duration.ofMillis(10_700), scheduler.elapsed());
        assertEquals(1, scheduler.scheduledCount());
        assertThrows(IllegalArgumentException.class,
                () -> scheduler.scheduleAtFixedRate(() -> { }, Duration.ZERO, Duration.ZERO));
        scheduler.shutdown();
        assertTrue(scheduler.isShutdown());
        assertEquals(0, scheduler.scheduledCount());
    }

    /** Контроллер-заглушка: тесту порта события сеанса формы не нужны. */
    private static final class NoHost implements FormSession.Host {
        @Override
        public void registered(FormSession session) {
        }

        @Override
        public void unregistered(FormSession session) {
        }

        @Override
        public void touched(FormSession session) {
        }

        @Override
        public void closed(FormSession session, Object result) {
        }

        @Override
        public void openChild(FormSession parent, WindowState child) {
        }

        @Override
        public void applied(FormSession session, Object action) {
        }
    }
}
