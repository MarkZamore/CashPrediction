package ru.cashprediction.core.app.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.markdown.SettingsMarkdown;

/** Проверяет задержки, отмену уже отправленных вызовов, ошибки и отсутствие повторных вопросов. */
class FileServicesTest {
    @TempDir Path temp;

    @Test
    void settingsDebounceUsesLatestValuesAndFlushCancelsQueuedUiWrite() throws Exception {
        FakeFileFlowContext f = fake();
        f.appSettings = f.appSettings.withAutosave(true);
        f.settings.changed();
        f.scheduler.advance(699);
        Path path = f.environment.cashMemory().resolve("settings.md");
        assertFalse(Files.exists(path));
        f.appSettings = f.appSettings.withAutosave(false).withPlanOpened("Latest.md");
        f.settings.changed();
        f.scheduler.advance(699);
        assertFalse(Files.exists(path));
        f.scheduler.advance(1);
        assertEquals(f.appSettings, SettingsMarkdown.read(Files.readString(path)));
        assertTrue(f.persistent.isEmpty());

        f.queuedUi = true;
        f.appSettings = f.appSettings.withPlanOpened("Queued.md");
        f.settings.changed();
        f.scheduler.advance(700);
        assertEquals(1, f.uiQueue.size());
        f.appSettings = f.appSettings.withPlanOpened("Flushed.md");
        f.settings.flushNow();
        String flushed = Files.readString(path);
        f.drainUi();
        assertEquals(flushed, Files.readString(path));
        assertEquals(f.appSettings, SettingsMarkdown.read(flushed));
    }

    @Test
    void settingsErrorRemainsPersistentUntilSuccessfulRetry() throws Exception {
        FakeFileFlowContext f = fake();
        Path target = f.environment.cashMemory().resolve("settings.md");
        Files.createDirectory(target);
        Files.writeString(target.resolve("block"), "block");
        f.settings.flushNow();
        assertTrue(f.persistent.containsKey("settingsFailed"));
        assertTrue(f.alerts.isEmpty());
        Files.delete(target.resolve("block"));
        Files.delete(target);
        f.appSettings = AppSettings.defaults().withAutosave(true);
        f.settings.changed();
        f.scheduler.advance(700);
        assertFalse(f.persistent.containsKey("settingsFailed"));
        assertEquals(f.appSettings, SettingsMarkdown.read(Files.readString(target)));
    }

    @Test
    void autosaveWaitsOneSecondAndDebouncesEditsAndUpdatesRecent() throws Exception {
        FakeFileFlowContext f = fake();
        f.dirty();
        f.autosave.setEnabled(true);
        f.scheduler.advance(999);
        Path target = f.environment.cashMemory().resolve("Current.md");
        assertFalse(Files.exists(target));
        f.document.edit("edit", plan -> plan.withNote("updated"));
        f.autosave.documentChanged();
        f.scheduler.advance(999);
        assertFalse(Files.exists(target));
        f.scheduler.advance(1);
        assertTrue(Files.isRegularFile(target));
        assertFalse(f.document.isDirty());
        assertEquals(List.of(target.toString()), f.appSettings.recentPlans());
        assertEquals("", f.autosaveProblem);
        assertFalse(f.events.contains("status:status.msg.saved"));
    }

    @Test
    void autosaveDisabledOrStoppedInvalidatesAlreadyPostedUiTask() throws Exception {
        FakeFileFlowContext f = fake();
        f.queuedUi = true;
        f.dirty();
        f.autosave.setEnabled(true);
        f.scheduler.advance(1000);
        assertEquals(1, f.uiQueue.size());
        f.autosave.setEnabled(false);
        f.drainUi();
        assertTrue(f.document.isDirty());
        assertTrue(f.document.file().isEmpty());
        f.autosave.setEnabled(true);
        f.scheduler.advance(1000);
        f.autosave.stop();
        f.drainUi();
        f.autosave.setEnabled(true);
        f.scheduler.advance(2000);
        f.drainUi();
        assertTrue(f.document.file().isEmpty());
    }

    @Test
    void autosaveNameCollisionIsVisibleAndNotRepeatedForSameDocument() throws Exception {
        FakeFileFlowContext f = fake();
        Path other = f.planFile("Current");
        String original = Files.readString(other);
        f.dirty();
        f.autosave.setEnabled(true);
        f.scheduler.advance(1000);
        assertTrue(f.alerts.isEmpty());
        assertFalse(f.autosaveProblem.isBlank());
        assertEquals(1, count(f, "status:status.msg.autosaveSkipped"));
        f.autosave.documentChanged();
        f.scheduler.advance(1000);
        assertEquals(1, count(f, "status:status.msg.autosaveSkipped"));
        assertEquals(original, Files.readString(other));
        f.files.save(null);
        assertEquals("overwriteOnFirstSave", f.alerts.getLast().purpose());
        f.answer("overwrite");
        assertEquals("", f.autosaveProblem);
    }

    @Test
    void autosaveConflictKeepsOnlyOnePromptAndCancelDoesNotRepeatUntilNewEdit() throws Exception {
        FakeFileFlowContext f = conflict();
        f.autosave.setEnabled(true);
        f.scheduler.advance(1000);
        assertEquals(1, f.alerts.size());
        f.autosave.documentChanged();
        f.scheduler.advance(5000);
        assertEquals(1, f.alerts.size());
        f.answer("cancel");
        assertFalse(f.autosaveProblem.isBlank());
        f.autosave.documentChanged();
        f.scheduler.advance(1000);
        assertEquals(1, f.alerts.size());
        f.document.edit("new edit", plan -> plan.withNote("new content"));
        f.autosave.documentChanged();
        f.scheduler.advance(1000);
        assertEquals(2, f.alerts.size());
        f.answer("overwrite");
        assertFalse(f.document.isDirty());
        assertEquals("", f.autosaveProblem);
    }

    @Test
    void autosaveConflictReloadClearsProblemAndUpdatesDocument() throws Exception {
        FakeFileFlowContext f = conflict();
        f.autosave.setEnabled(true);
        f.scheduler.advance(1000);
        f.answer("reload");
        assertFalse(f.document.isDirty());
        assertEquals("", f.autosaveProblem);
        assertTrue(f.events.contains("status:status.msg.reloaded"));
    }

    @Test
    void autosaveWriteErrorUsesStatusInsteadOfRepeatedAlerts() throws Exception {
        FakeFileFlowContext f = fake();
        // Каталог вместо внутреннего файла проверяет IO_ERROR, а не readonly-политику импорта.
        Files.createDirectory(f.environment.cashMemory().resolve("Current.md"));
        f.dirty();
        f.autosave.setEnabled(true);
        f.scheduler.advance(1000);
        assertFalse(f.autosaveProblem.isBlank());
        assertFalse(f.autosaveProblem.contains("Сохранить как"));
        assertTrue(f.alerts.isEmpty());
        assertTrue(f.document.isDirty());
        String problem = f.autosaveProblem;
        f.autosave.documentChanged();
        f.scheduler.advance(3000);
        assertEquals(problem, f.autosaveProblem);
        assertTrue(f.alerts.isEmpty());
    }

    @Test
    void autosaveWaitsForModalWindowThenSaves() throws Exception {
        FakeFileFlowContext f = fake();
        f.dirty();
        f.files.confirmDiscard(() -> { });
        f.autosave.setEnabled(true);
        f.scheduler.advance(2000);
        assertTrue(f.document.file().isEmpty());
        assertEquals(1, f.alerts.size());
        f.answer("cancel");
        f.scheduler.advance(1000);
        assertTrue(f.document.file().isPresent());
        assertFalse(f.document.isDirty());
    }

    @Test
    void toggleAutosavePersistsSettingAndSchedulesDirtyDocument() throws Exception {
        FakeFileFlowContext f = fake();
        f.dirty();
        f.files.toggleAutosave();
        assertTrue(f.appSettings.autosave());
        f.scheduler.advance(1000);
        assertFalse(f.document.isDirty());
        f.files.toggleAutosave();
        assertFalse(f.appSettings.autosave());
    }

    @Test
    void persistedAutosaveSettingWorksBeforeFirstExplicitToggle() throws Exception {
        FakeFileFlowContext f = fake();
        f.appSettings = f.appSettings.withAutosave(true);
        f.dirty();
        f.autosave.documentChanged();
        f.scheduler.advance(1000);
        assertFalse(f.document.isDirty());
        assertTrue(f.document.file().isPresent());
    }

    /** Создаёт документ с действительным внешним изменением метки файла без ожидания часов ОС. */
    private FakeFileFlowContext conflict() throws IOException {
        FakeFileFlowContext f = fake();
        Path file = f.planFile("Current");
        f.document.replace(f.document.plan(), file, true, List.of());
        f.guard.remember(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 10000));
        return f;
    }

    /** Считает повторения конкретного события, не полагаясь на отсутствие исключений. */
    private static long count(FakeFileFlowContext f, String event) {
        return f.events.stream().filter(event::equals).count();
    }

    /** Создаёт изолированный контекст текущего теста. */
    private FakeFileFlowContext fake() throws IOException { return new FakeFileFlowContext(temp); }
}
