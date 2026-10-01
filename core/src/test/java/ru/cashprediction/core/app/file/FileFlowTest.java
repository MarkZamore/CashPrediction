package ru.cashprediction.core.app.file;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.FileChooserSpec;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.forms.plan.NewPlanWizardForm;
import ru.cashprediction.core.ui.forms.simple.CsvExportForm;
import ru.cashprediction.core.ui.forms.simple.OpenPlanForm;

/** Проверяет реальные файловые сценарии и продолжения с каждым ответом пользователя. */
class FileFlowTest {
    @TempDir Path temp;

    /** Шесть входов, обязанных спросить о несохранённых изменениях. */
    private enum Entry { NEW, OPEN, OPEN_FILE, SAMPLE, RECENT, OTHER_FOLDER }
    /** Продолжения после ответа, включая отмену второго вопроса и настоящую ошибку записи. */
    private enum Answer { SAVE, DONT_SAVE, CANCEL, FAILED_SAVE, CANCEL_SAVE }

    @TestFactory
    List<DynamicTest> allSixEntriesConfirmDiscardAndAbortOnUnsuccessfulSave() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Entry entry : Entry.values()) for (Answer answer : Answer.values()) {
            tests.add(DynamicTest.dynamicTest(entry + ":" + answer, () -> {
                FakeFileFlowContext f = new FakeFileFlowContext(temp.resolve(entry + "-" + answer));
                Path recent = f.planFile("Recent");
                if (answer == Answer.FAILED_SAVE) {
                    Path blocked = f.environment.cashMemory().resolve("blocked");
                    Files.writeString(blocked, "block");
                    f.document.replace(f.document.plan(), blocked.resolve("Current.md"), true, List.of());
                } else {
                    if (answer == Answer.CANCEL_SAVE) f.planFile("Current");
                    f.dirty();
                }
                Plan before = f.document.plan();
                switch (entry) {
                    case NEW -> f.files.newPlan();
                    case OPEN -> f.files.open();
                    case OPEN_FILE -> f.files.openFile();
                    case SAMPLE -> f.files.openSample();
                    case RECENT -> f.files.openRecent(recent.toString());
                    case OTHER_FOLDER -> {
                        f.files.cashMemoryFolder();
                        f.answer("otherFolder");
                    }
                }
                assertEquals("unsavedChanges", f.alerts.getLast().purpose());
                assertTrue(f.requests.isEmpty());
                assertTrue(f.chooserRequests.isEmpty());
                assertNull(f.directoryAnswer);
                assertEquals(before, f.document.plan());
                if (answer == Answer.CANCEL) f.answer("cancel");
                else if (answer == Answer.DONT_SAVE) f.answer("dontSave");
                else {
                    f.answer("save");
                    if (answer == Answer.CANCEL_SAVE) {
                        assertEquals("overwriteOnFirstSave", f.alerts.getLast().purpose());
                        f.answer("cancel");
                    }
                }
                boolean continues = answer == Answer.SAVE || answer == Answer.DONT_SAVE;
                if (!continues) {
                    assertEquals(before, f.document.plan());
                    assertTrue(f.document.isDirty());
                    assertTrue(f.requests.isEmpty());
                    assertTrue(f.chooserRequests.isEmpty());
                    assertNull(f.directoryAnswer);
                    if (answer == Answer.FAILED_SAVE) assertEquals("err.save", f.alerts.getLast().purpose());
                } else {
                    switch (entry) {
                        case NEW, OPEN -> assertEquals(1, f.requests.size());
                        case OPEN_FILE -> assertEquals(1, f.chooserRequests.size());
                        case SAMPLE -> {
                            assertNotEquals(before, f.document.plan());
                            assertTrue(f.document.isDirty());
                            assertTrue(f.document.file().isEmpty());
                        }
                        case RECENT -> assertEquals(recent, f.document.file().orElseThrow());
                        case OTHER_FOLDER -> assertNotNull(f.directoryAnswer);
                    }
                    if (answer == Answer.SAVE && entry != Entry.SAMPLE) {
                        assertFalse(f.document.isDirty());
                        assertTrue(Files.isRegularFile(f.environment.cashMemory().resolve("Current.md")));
                    }
                }
            }));
        }
        return tests;
    }

    @Test
    void saveContinuationRunsOnceAndOnlyAfterWriting() throws Exception {
        FakeFileFlowContext f = fake();
        f.dirty();
        AtomicInteger calls = new AtomicInteger();
        f.files.confirmDiscard(() -> {
            assertFalse(f.document.isDirty());
            assertTrue(Files.isRegularFile(f.document.file().orElseThrow()));
            calls.incrementAndGet();
        });
        var callback = f.alertAnswer;
        f.answer("save");
        callback.accept("save");
        assertEquals(1, calls.get());
    }

    @Test
    void firstSaveCollisionCancelPreservesOtherFileThenOverwriteContinues() throws Exception {
        FakeFileFlowContext f = fake();
        Path file = f.planFile("Current");
        String old = Files.readString(file);
        f.document.replace(Plan.empty("Current", FakeFileFlowContext.TODAY.minusDays(1)), null, true, List.of());
        AtomicInteger calls = new AtomicInteger();
        f.files.save(calls::incrementAndGet);
        f.answer("cancel");
        assertEquals(old, Files.readString(file));
        assertTrue(f.document.file().isEmpty());
        assertEquals(0, calls.get());
        f.files.save(calls::incrementAndGet);
        f.answer("overwrite");
        assertEquals(1, calls.get());
        assertFalse(f.document.isDirty());
        assertNotEquals(old, Files.readString(file));
        assertFalse(f.guard.changedExternally(file));
    }

    @TestFactory
    List<DynamicTest> externalConflictOverwriteReloadAndCancel() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String answer : List.of("overwrite", "reload", "cancel")) {
            tests.add(DynamicTest.dynamicTest(answer, () -> {
                FakeFileFlowContext f = new FakeFileFlowContext(temp.resolve(answer));
                Path file = f.planFile("Current");
                f.document.replace(Plan.empty("Current", FakeFileFlowContext.TODAY), file, true, List.of());
                f.guard.remember(file);
                Plan disk = Plan.empty("Current", FakeFileFlowContext.TODAY.minusDays(10));
                new PlanRepository(f.environment.cashMemory()).save(disk, file);
                Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));
                String original = Files.readString(file);
                Plan memory = f.document.plan();
                AtomicInteger calls = new AtomicInteger();
                f.files.save(calls::incrementAndGet);
                assertEquals("externalChange", f.alerts.getLast().purpose());
                f.files.save(calls::incrementAndGet);
                assertEquals(1, f.alerts.size());
                f.answer(answer);
                if ("overwrite".equals(answer)) {
                    assertEquals(1, calls.get());
                    assertEquals(memory, new PlanRepository(f.environment.cashMemory()).load(file, FakeFileFlowContext.TODAY).plan());
                    assertFalse(f.document.isDirty());
                } else {
                    assertEquals(0, calls.get());
                    assertEquals(original, Files.readString(file));
                    if ("reload".equals(answer)) {
                        assertEquals(disk, f.document.plan());
                        assertFalse(f.document.isDirty());
                        assertTrue(f.events.contains("status:status.msg.reloaded"));
                    } else {
                        assertEquals(memory, f.document.plan());
                        assertTrue(f.document.isDirty());
                    }
                }
            }));
        }
        return tests;
    }

    @Test
    void reloadFailurePreservesDirtyMemoryAndDoesNotContinue() throws Exception {
        FakeFileFlowContext f = fake();
        Path file = f.planFile("Current");
        f.document.replace(f.document.plan(), file, true, List.of());
        f.guard.remember(file);
        Files.delete(file);
        AtomicInteger continuation = new AtomicInteger();
        f.files.save(continuation::incrementAndGet);
        f.answer("reload");
        assertEquals(0, continuation.get());
        assertTrue(f.document.isDirty());
        assertEquals("err.readPlan", f.alerts.getLast().purpose());
    }

    @Test
    void missingRecentIsRemovedOnlyAfterDiscardConfirmation() throws Exception {
        FakeFileFlowContext f = fake();
        Path missing = f.environment.cashMemory().resolve("Missing.md");
        f.appSettings = f.appSettings.withPlanOpened(missing.toString());
        f.dirty();
        f.files.openRecent(missing.toString());
        f.answer("cancel");
        assertEquals(List.of(missing.toString()), f.appSettings.recentPlans());
        f.files.openRecent(missing.toString());
        f.answer("dontSave");
        assertTrue(f.appSettings.recentPlans().isEmpty());
        assertEquals("", f.appSettings.lastPlan());
        assertEquals("err.recentMissing", f.alerts.getLast().purpose());
    }

    @Test
    void openingFileResetsHistoryAndSelectionAndUpdatesRecentAndGuard() throws Exception {
        FakeFileFlowContext f = fake();
        Path file = f.planFile("Target");
        f.selection = "old";
        f.pastExpanded = true;
        f.document.edit("edit", plan -> plan.withName("Changed"));
        f.files.openFile();
        f.answer("dontSave");
        f.fileAnswer.accept(Optional.of(file));
        assertEquals("Target", f.document.plan().name());
        assertFalse(f.document.isDirty());
        assertFalse(f.document.canUndo());
        assertEquals("", f.selection);
        assertFalse(f.pastExpanded);
        assertEquals(List.of(file.toString()), f.appSettings.recentPlans());
        assertFalse(f.guard.changedExternally(file));
    }

    @Test
    void invalidFileDoesNotReplaceDocument() throws Exception {
        FakeFileFlowContext f = fake();
        Path file = f.environment.cashMemory().resolve("Invalid.md");
        Files.writeString(file, "This is not a plan");
        Plan old = f.document.plan();
        f.files.openFile();
        f.fileAnswer.accept(Optional.of(file));
        assertEquals(old, f.document.plan());
        assertTrue(f.appSettings.recentPlans().isEmpty());
        assertEquals("err.notPlan", f.alerts.getLast().purpose());
    }

    @Test
    void openFromFileChoiceContinuesAlreadyConfirmedDiscardWithoutSecondQuestion() throws Exception {
        FakeFileFlowContext f = fake();
        f.dirty();
        f.files.open();
        f.answer("dontSave");
        f.formResult(OpenPlanForm.FROM_FILE);
        assertEquals(1, f.alerts.size());
        assertEquals(1, f.chooserRequests.size());
        f.fileAnswer.accept(Optional.empty());
        assertTrue(f.document.isDirty());
    }

    @Test
    void saveAsRenamesWithUndoAndCancelOrFailureDoesNotRename() throws Exception {
        FakeFileFlowContext f = fake();
        f.dirty();
        Plan old = f.document.plan();
        f.files.saveAs();
        f.fileAnswer.accept(Optional.empty());
        assertEquals(old, f.document.plan());
        f.files.saveAs();
        f.fileAnswer.accept(Optional.of(f.environment.cashMemory().resolve("Renamed")));
        Path file = f.environment.cashMemory().resolve("Renamed.md");
        assertEquals(file, f.document.file().orElseThrow());
        assertEquals("Renamed", f.document.plan().name());
        assertEquals("Renamed", new PlanRepository(f.environment.cashMemory()).load(file, FakeFileFlowContext.TODAY).plan().name());
        assertTrue(f.document.canUndo());
        f.document.undo();
        assertEquals(old, f.document.plan());
        assertTrue(f.document.isDirty());

        Path blocked = f.environment.cashMemory().resolve("blocked");
        Files.writeString(blocked, "block");
        f.files.saveAs();
        f.fileAnswer.accept(Optional.of(blocked.resolve("Failure.md")));
        assertEquals(old, f.document.plan());
        assertEquals(file, f.document.file().orElseThrow());
        assertEquals("err.save", f.alerts.getLast().purpose());
    }

    @Test
    void fileRenamePreservesDirtyEditsAndDiskContentAndUpdatesRecent() throws Exception {
        FakeFileFlowContext f = fake();
        Path oldFile = f.planFile("Current");
        Plan saved = new PlanRepository(f.environment.cashMemory()).load(oldFile, FakeFileFlowContext.TODAY).plan();
        Plan edited = saved.withName("Current").withStart(FakeFileFlowContext.TODAY.minusDays(2), saved.startBalance());
        f.document.replace(edited, oldFile, true, List.of());
        f.appSettings = f.appSettings.withPlanOpened(oldFile.toString());
        f.files.rename();
        f.form().fieldChanged("value", "Renamed", true, 1);
        f.form().buttonPressed("rename");
        Path target = oldFile.resolveSibling("Renamed.md");
        assertFalse(Files.exists(oldFile));
        assertEquals(target, f.document.file().orElseThrow());
        assertEquals(edited.withName("Renamed"), f.document.plan());
        assertTrue(f.document.isDirty());
        assertEquals(saved.withName("Renamed"), new PlanRepository(f.environment.cashMemory()).load(target, FakeFileFlowContext.TODAY).plan());
        assertEquals(List.of(target.toString()), f.appSettings.recentPlans());
        assertFalse(f.guard.changedExternally(target));
    }

    @Test
    void cleanFileRenameStaysCleanAndCollisionDisablesButtonWithoutClosingForm() throws Exception {
        FakeFileFlowContext f = fake();
        Path oldFile = f.planFile("Current");
        f.planFile("Taken");
        f.document.replace(f.document.plan(), oldFile, false, List.of());
        f.files.rename();
        var form = f.form();
        form.fieldChanged("value", "Taken", true, 1);
        assertEquals(Problem.Severity.ERROR, form.view().problem().severity());
        assertFalse(form.view().buttons().get("rename").enabled());
        form.buttonPressed("rename");
        assertEquals(oldFile, f.document.file().orElseThrow());
        assertTrue(Files.exists(oldFile));
        form.fieldChanged("value", "Renamed", true, 2);
        form.buttonPressed("rename");
        assertFalse(f.document.isDirty());
        assertEquals("Renamed", f.document.plan().name());
    }

    @Test
    void renameWithoutFileIsUndoable() throws Exception {
        FakeFileFlowContext f = fake();
        f.files.rename();
        f.form().fieldChanged("value", "Renamed", true, 1);
        f.form().buttonPressed("rename");
        assertEquals("Renamed", f.document.plan().name());
        f.document.undo();
        assertEquals("Current", f.document.plan().name());
    }

    @Test
    void wizardCreatesAndSavesAndFirstRunCancelPreservesEmptyPlan() throws Exception {
        FakeFileFlowContext f = fake();
        Plan empty = f.document.plan();
        f.files.firstRunWizard();
        f.form().closeRequested();
        assertEquals(empty, f.document.plan());
        f.files.newPlan();
        Plan created = Plan.empty("Created", FakeFileFlowContext.TODAY);
        f.formResult(new NewPlanWizardForm.Created(created));
        Path path = f.environment.cashMemory().resolve("Created.md");
        assertEquals(path, f.document.file().orElseThrow());
        assertEquals(created, new PlanRepository(f.environment.cashMemory()).load(path, FakeFileFlowContext.TODAY).plan());
        assertFalse(f.document.isDirty());
        assertTrue(f.events.contains("status:status.msg.created"));
    }

    @Test
    void wizardWriteFailureKeepsCreatedPlanDirty() throws Exception {
        FakeFileFlowContext f = fake();
        f.files.newPlan();
        Files.delete(f.environment.cashMemory());
        Files.writeString(f.environment.cashMemory(), "block");
        Plan created = Plan.empty("Created", FakeFileFlowContext.TODAY);
        f.formResult(new NewPlanWizardForm.Created(created));
        assertEquals(created, f.document.plan());
        assertTrue(f.document.isDirty());
        assertTrue(f.document.file().isEmpty());
        assertEquals("err.createdNotSaved", f.alerts.getLast().purpose());
    }

    @Test
    void cashMemoryFolderChangesOnlyAfterConfirmAndDirectoryAnswerAndCanReturn() throws Exception {
        FakeFileFlowContext f = fake();
        Path other = Files.createDirectories(f.environment.cashMemory().resolve("other"));
        f.files.cashMemoryFolder();
        f.answer("otherFolder");
        f.directoryAnswer.accept(Optional.of(other));
        assertEquals(other, f.plansFolder);
        assertEquals(1, f.requests.size());
        f.form().closeRequested();
        f.files.cashMemoryFolder();
        f.answer("backToCashMemory");
        assertEquals(f.environment.cashMemory(), f.plansFolder);
        assertTrue(f.events.contains("status:status.msg.backToCashMemory"));
    }

    @Test
    void pngUsesCurrentScene1200By700ThenChoosesAndWritesExactBytes() throws Exception {
        FakeFileFlowContext f = fake();
        f.files.savePng();
        assertEquals(1200, f.pngScene.width());
        assertEquals(700, f.pngScene.height());
        assertTrue(f.events.indexOf("renderPng") < f.events.indexOf("chooseFile"));
        assertEquals(FileChooserSpec.Purpose.SAVE_PNG, f.chooserRequests.getFirst().purpose());
        Path target = f.environment.cashMemory().resolve("chart.png");
        f.fileAnswer.accept(Optional.of(target));
        assertArrayEquals(f.png, Files.readAllBytes(target));
        assertTrue(f.events.contains("status:status.msg.png"));
    }

    @Test
    void pngRenderErrorAndUnavailableForecastNeverOpenChooser() throws Exception {
        FakeFileFlowContext f = fake();
        f.pngFailure = new IOException("render failed");
        f.files.savePng();
        assertTrue(f.chooserRequests.isEmpty());
        assertEquals("err.png", f.alerts.getLast().purpose());
        f.answer("ok");
        f.forecastAvailable = false;
        f.files.savePng();
        f.files.exportCsv();
        assertTrue(f.requests.isEmpty());
        assertTrue(f.chooserRequests.isEmpty());
    }

    @TestFactory
    List<DynamicTest> csvHonorsSeparatorBomAndSelectedDateRange() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String separator : List.of(";", ",", "TAB")) for (boolean bom : List.of(true, false)) {
            tests.add(DynamicTest.dynamicTest(separator + ":" + bom, () -> {
                FakeFileFlowContext f = new FakeFileFlowContext(temp.resolve("csv-" + tests.size() + "-" + separator.hashCode() + "-" + bom));
                f.document.replace(Plan.empty("Past", FakeFileFlowContext.TODAY.minusDays(5)), null, false, List.of());
                f.files.exportCsv();
                f.formResult(new CsvExportForm.Choice(separator, bom, "PERIOD"));
                Path path = f.environment.cashMemory().resolve("export.csv");
                f.fileAnswer.accept(Optional.of(path));
                String csv = Files.readString(path);
                assertEquals(bom, csv.startsWith("\uFEFF"));
                char sep = "TAB".equals(separator) ? '\t' : separator.charAt(0);
                assertTrue(csv.lines().findFirst().orElseThrow().indexOf(sep) >= 0);
                assertFalse(csv.contains("26.09.2026"));
                assertTrue(f.events.contains("status:status.msg.csv"));
            }));
        }
        return tests;
    }

    @Test
    void csvAllIncludesStartAndCancelDoesNotWrite() throws Exception {
        FakeFileFlowContext f = fake();
        f.document.replace(Plan.empty("Past", FakeFileFlowContext.TODAY.minusDays(5)), null, false, List.of());
        f.files.exportCsv();
        f.formResult(new CsvExportForm.Choice(";", false, "ALL"));
        Path path = f.environment.cashMemory().resolve("export.csv");
        f.fileAnswer.accept(Optional.of(path));
        assertTrue(Files.readString(path).contains("26.09.2026"));
        f.files.exportCsv();
        f.formResult(null);
        assertEquals(1, f.chooserRequests.size());
    }

    /** Создаёт контекст в уникальной подпапке временного каталога текущего теста. */
    private FakeFileFlowContext fake() throws IOException { return new FakeFileFlowContext(temp); }
}
