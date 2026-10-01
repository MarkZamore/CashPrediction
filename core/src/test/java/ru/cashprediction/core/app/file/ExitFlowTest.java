package ru.cashprediction.core.app.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.ExitKind;
import ru.cashprediction.core.app.flow.FormRequest;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.forms.simple.TextInputForms;

/** Проверяет порядок выхода на настоящем рекордере, чистый финальный снимок и сохранение работающего сеанса при отказе. */
class ExitFlowTest {
    @TempDir Path temp;

    @Test
    void cancelExitKeepsDirtySnapshotRecorderAndTimersRunning() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(temp);
        f.dirty();
        f.startRecorder();
        f.exit.requestExit();
        assertTrue(f.events.indexOf("snapshot") < f.events.indexOf("alert:unsavedChanges"));
        assertTrue(f.snapshots.getFirst().plan().dirty());
        f.answer("cancel");
        assertNull(f.exited);
        assertFalse(f.recorder.isClosed());
        assertFalse(f.scheduler.stopped);
        assertTrue(f.document.isDirty());
        assertFalse(Files.exists(f.environment.cashMemory().resolve("settings.md")));
        f.exit.requestExit();
        f.answer("dontSave");
        assertEquals(ExitKind.CLEAN, f.exited);
    }

    @Test
    void failedSaveAbortsExitAndPreservesRecoverySnapshot() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(temp);
        Path blocker = f.environment.cashMemory().resolve("block");
        Files.writeString(blocker, "block");
        f.document.replace(f.document.plan(), blocker.resolve("Current.md"), true, List.of());
        f.startRecorder();
        f.exit.requestExit();
        f.answer("save");
        assertEquals("err.save", f.alerts.getLast().purpose());
        assertNull(f.exited);
        assertFalse(f.recorder.isClosed());
        assertFalse(f.scheduler.stopped);
        assertTrue(f.snapshots.getLast().plan().dirty());
    }

    @Test
    void dontSaveExitWritesCleanSnapshotWithoutFileOrWindowsAndStopsPostedAutosave() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(temp);
        f.dirty();
        f.context.openForm(FormRequest.fresh(TextInputForms.rename(), WindowType.TEXT_INPUT, false,
                Map.of("purpose", "rename")), null, ignored -> { });
        f.startRecorder();
        // Регистрируем окно, открытое до старта рекордера, как это делает StartupFlow.
        f.recorder.register(f.form());
        f.queuedUi = true;
        f.autosave.setEnabled(true);
        f.scheduler.advance(1000);
        f.settings.changed();
        f.exit.requestExit();
        assertTrue(f.snapshots.getFirst().plan().dirty());
        f.answer("dontSave");
        assertEquals(ExitKind.CLEAN, f.exited);
        assertFalse(f.snapshots.getLast().plan().dirty(),
                "SessionBridge.capturePlan must honor ExitFlow.cleanExitSnapshot for a plan without a file");
        assertTrue(f.snapshots.getLast().windows().isEmpty());
        assertTrue(f.recorder.isClosed());
        assertTrue(f.scheduler.stopped);
        assertTrue(Files.isRegularFile(f.environment.cashMemory().resolve("settings.md")));
        f.drainUi();
        assertFalse(Files.exists(f.environment.cashMemory().resolve("Current.md")));
        assertTrue(f.events.indexOf("snapshot") < f.events.indexOf("alert:unsavedChanges"));
        assertTrue(f.events.indexOf("answer:dontSave") < f.events.indexOf("persistent:settingsFailed"));
        assertTrue(f.events.indexOf("persistent:settingsFailed") < f.events.indexOf("markClean"));
        assertTrue(f.events.indexOf("markClean") < f.events.indexOf("shutdownTimers"));
        assertTrue(f.events.indexOf("shutdownTimers") < f.events.indexOf("exit:CLEAN"));
        int exits = (int) f.events.stream().filter("exit:CLEAN"::equals).count();
        f.exit.requestExit();
        assertEquals(exits, f.events.stream().filter("exit:CLEAN"::equals).count());
    }

    @Test
    void saveExitWritesPlanBeforeCleanShutdown() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(temp);
        f.dirty();
        f.startRecorder();
        f.exit.requestExit();
        f.answer("save");
        assertEquals(ExitKind.CLEAN, f.exited);
        assertTrue(Files.isRegularFile(f.environment.cashMemory().resolve("Current.md")));
        assertFalse(f.snapshots.getLast().plan().dirty());
        assertTrue(f.events.indexOf("status:status.msg.saved") < f.events.indexOf("markClean"));
    }

    @Test
    void cleanWebExitWorksWithoutRecorderAndWithoutConfirmation() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(temp);
        f.profile = ClientProfile.web();
        f.exit.requestExit();
        assertEquals(ExitKind.WEB_STOPPED, f.exited);
        assertTrue(f.alerts.isEmpty());
        assertTrue(f.scheduler.stopped);
        assertTrue(Files.isRegularFile(f.environment.cashMemory().resolve("settings.md")));
    }

    @Test
    void settingsErrorDoesNotLeaveRunningMarkerDuringExit() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(temp);
        Path file = f.planFile("Current");
        f.document.replace(f.document.plan(), file, false, List.of());
        f.startRecorder();
        Path target = Files.createDirectory(f.environment.cashMemory().resolve("settings.md"));
        Files.writeString(target.resolve("block"), "block");
        f.exit.requestExit();
        assertEquals(ExitKind.CLEAN, f.exited);
        assertTrue(f.persistent.containsKey("settingsFailed"));
        assertTrue(f.scheduler.stopped);
        assertTrue(f.recorder.isClosed());
        assertTrue(f.events.contains("markClean"));
    }
}
