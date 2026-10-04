package ru.cashprediction.core.app.file;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.flow.AutosaveService;
import ru.cashprediction.core.app.flow.ExternalChangeGuard;
import ru.cashprediction.core.app.flow.FileFlow;
import ru.cashprediction.core.app.flow.FlowContext;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.service.storage.FakePlanStorage;
import ru.cashprediction.core.service.storage.FilePlanStorage;
import ru.cashprediction.core.service.storage.PlanStorage;
import ru.cashprediction.core.ui.token.DesignTokens;

/** Проверяет настоящие файловые и автоматические сценарии с внедрённым нефайловым хранилищем. */
class FileFlowStorageTest {
    @TempDir Path home;

    @Test void listReadWriteAndFolderSelectionActuallyUseInjectedStorage() throws Exception {
        Fixture fixture = fixture();
        Path other = fixture.ui.environment.cashMemory().resolve("other");
        fixture.ui.plansFolder = other;
        Path selected = other.resolve("Stored.md");
        var reference = FilePlanStorage.reference(selected);
        Plan original = Plan.empty("Stored", FakeFileFlowContext.TODAY);
        var version = fixture.storage.put(reference, original);
        fixture.storage.collection(FilePlanStorage.collection(other), List.of(reference));

        fixture.files.open();
        assertEquals(List.of("list"), fixture.storage.calls());
        fixture.ui.formResult(selected);
        assertEquals(Optional.of(version), fixture.storage.lastReadExpected());
        assertEquals(original, fixture.ui.document.plan());
        assertEquals(selected, fixture.ui.document.file().orElseThrow());
        assertFalse(Files.exists(selected));

        fixture.ui.document.edit("change", plan -> plan.withStart(FakeFileFlowContext.TODAY.minusDays(1), plan.startBalance()));
        Plan edited = fixture.ui.document.plan();
        fixture.files.save(null);
        assertTrue(fixture.storage.calls().containsAll(List.of("list", "read", "write")));
        assertEquals(version, fixture.storage.lastExpected());
        assertEquals(reference, fixture.storage.lastReference());
        assertEquals(edited, fixture.storage.snapshot(reference).plan());
        assertFalse(fixture.ui.document.isDirty());
        assertTrue(fixture.ui.document.canUndo());
        assertFalse(fixture.guard.changedExternally(selected));
        assertFalse(Files.exists(selected));
    }

    @Test void overwriteAnswerCannotOverwriteAnotherVersionThatArrivedWhilePromptWasOpen() throws Exception {
        Fixture fixture = fixture();
        Path file = open(fixture, "Stored");
        var reference = FilePlanStorage.reference(file);
        Plan disk = fixture.storage.snapshot(reference).plan();
        fixture.ui.document.edit("change", plan -> plan.withStart(FakeFileFlowContext.TODAY.minusDays(1), plan.startBalance()));
        Plan memory = fixture.ui.document.plan();
        var firstOutside = fixture.storage.put(reference, disk.withStart(FakeFileFlowContext.TODAY.minusDays(2), disk.startBalance()));
        AtomicInteger continued = new AtomicInteger();

        fixture.files.save(continued::incrementAndGet);
        assertEquals("externalChange", fixture.ui.alerts.getLast().purpose());
        Plan secondOutside = disk.withStart(FakeFileFlowContext.TODAY.minusDays(3), disk.startBalance());
        fixture.storage.put(reference, secondOutside);
        fixture.ui.answer("overwrite");
        assertEquals(firstOutside, fixture.storage.lastExpected());
        assertEquals(secondOutside, fixture.storage.snapshot(reference).plan());
        assertEquals(memory, fixture.ui.document.plan());
        assertTrue(fixture.ui.document.isDirty());
        assertTrue(fixture.ui.document.canUndo());
        assertEquals(0, continued.get());
        assertEquals("externalChange", fixture.ui.alerts.getLast().purpose());
        fixture.ui.answer("cancel");
        assertEquals(secondOutside, fixture.storage.snapshot(reference).plan());
        assertTrue(fixture.guard.changedExternally(file));

        fixture.files.save(continued::incrementAndGet);
        fixture.ui.answer("overwrite");
        assertEquals(memory, fixture.storage.snapshot(reference).plan());
        assertFalse(fixture.ui.document.isDirty());
        assertEquals(1, continued.get());
        assertFalse(fixture.guard.changedExternally(file));
    }

    @Test void reloadUsesPromptVersionAndKeepsDirtyPlanAndUndoWhenThatVersionChanged() throws Exception {
        Fixture fixture = fixture();
        Path file = open(fixture, "Stored");
        var reference = FilePlanStorage.reference(file);
        Plan original = fixture.ui.document.plan();
        fixture.ui.document.edit("change", plan -> plan.withStart(FakeFileFlowContext.TODAY.minusDays(1), plan.startBalance()));
        Plan memory = fixture.ui.document.plan();
        var prompted = fixture.storage.put(reference, original.withStart(FakeFileFlowContext.TODAY.minusDays(2), original.startBalance()));
        AtomicInteger continued = new AtomicInteger();
        fixture.files.save(continued::incrementAndGet);
        Plan newest = original.withStart(FakeFileFlowContext.TODAY.minusDays(3), original.startBalance());
        fixture.storage.put(reference, newest);
        fixture.ui.answer("reload");
        assertEquals(Optional.of(prompted), fixture.storage.lastReadExpected());
        assertEquals(memory, fixture.ui.document.plan());
        assertTrue(fixture.ui.document.canUndo());
        assertTrue(fixture.ui.document.isDirty());
        assertEquals(0, continued.get());
        assertEquals("err.readPlan", fixture.ui.alerts.getLast().purpose());
        fixture.ui.answer("ok");
        fixture.files.save(continued::incrementAndGet);
        fixture.ui.answer("reload");
        assertEquals(newest, fixture.ui.document.plan());
        assertFalse(fixture.ui.document.isDirty());
        assertFalse(fixture.ui.document.canUndo());
        assertEquals(0, continued.get());
    }

    @Test void renameUsesSavedVersionAndPreservesUnsavedEditsWithoutWritingThem() throws Exception {
        Fixture fixture = fixture();
        Path file = open(fixture, "Stored");
        var reference = FilePlanStorage.reference(file);
        var saved = fixture.storage.snapshot(reference);
        fixture.ui.document.edit("change", plan -> plan.withStart(FakeFileFlowContext.TODAY.minusDays(1), plan.startBalance()));
        Plan memory = fixture.ui.document.plan();
        Path target = file.resolveSibling("Renamed.md");
        var targetRef = FilePlanStorage.reference(target);
        fixture.storage.renameTarget(reference, targetRef);
        fixture.files.renameTo("Renamed");
        assertTrue(fixture.storage.calls().contains("rename"));
        assertFalse(fixture.storage.calls().contains("write"));
        assertEquals(saved.version(), fixture.storage.lastExpected());
        assertEquals(saved.plan().withName("Renamed"), fixture.storage.snapshot(targetRef).plan());
        assertEquals(memory.withName("Renamed"), fixture.ui.document.plan());
        assertTrue(fixture.ui.document.isDirty());
        assertEquals(target, fixture.ui.document.file().orElseThrow());
        assertEquals(List.of(target.toString()), fixture.ui.appSettings.recentPlans());
        assertFalse(fixture.guard.changedExternally(target));
    }

    @Test void autosaveCallsSameInjectedStorageAndLeavesDirtyPlanOnIoFailure() throws Exception {
        Fixture fixture = fixture();
        Path file = open(fixture, "Stored");
        var reference = FilePlanStorage.reference(file);
        Plan disk = fixture.storage.snapshot(reference).plan();
        fixture.ui.document.edit("change", plan -> plan.withStart(FakeFileFlowContext.TODAY.minusDays(1), plan.startBalance()));
        fixture.storage.writeProblem(new PlanStorage.Problem(PlanStorage.Code.IO_ERROR, PlanStorage.Conflict.NONE, "test"));
        fixture.autosave.setEnabled(true);
        fixture.ui.scheduler.advance(DesignTokens.AUTOSAVE_DELAY_MS);
        assertTrue(fixture.storage.calls().contains("write"));
        assertEquals(disk, fixture.storage.snapshot(reference).plan());
        assertTrue(fixture.ui.document.isDirty());
        assertTrue(fixture.ui.document.canUndo());
        assertFalse(fixture.ui.autosaveProblem.isBlank());
        assertTrue(fixture.ui.alerts.isEmpty());
        fixture.storage.writeProblem(null);
        fixture.autosave.setEnabled(true);
        fixture.ui.scheduler.advance(DesignTokens.AUTOSAVE_DELAY_MS);
        assertEquals(fixture.ui.document.plan(), fixture.storage.snapshot(reference).plan());
        assertFalse(fixture.ui.document.isDirty());
        assertEquals("", fixture.ui.autosaveProblem);
    }

    @Test void selectedListVersionAndCorruptReadNeverReplaceExistingDocument() throws Exception {
        Fixture fixture = fixture();
        Path file = fixture.ui.environment.cashMemory().resolve("Stored.md");
        var reference = FilePlanStorage.reference(file);
        Plan original = fixture.ui.document.plan();
        fixture.storage.put(reference, Plan.empty("Stored", FakeFileFlowContext.TODAY));
        fixture.storage.collection(FilePlanStorage.collection(fixture.ui.plansFolder), List.of(reference));
        fixture.files.open();
        fixture.storage.put(reference, Plan.empty("Stored", FakeFileFlowContext.TODAY.minusDays(1)));
        fixture.ui.formResult(file);
        assertEquals(original, fixture.ui.document.plan());
        assertTrue(fixture.ui.document.file().isEmpty());
        assertEquals("err.readPlan", fixture.ui.alerts.getLast().purpose());
        fixture.ui.answer("ok");
        fixture.storage.readProblem(new PlanStorage.Problem(PlanStorage.Code.CORRUPT, PlanStorage.Conflict.NONE, "test"));
        fixture.files.openFile();
        fixture.ui.fileAnswer.accept(Optional.of(file));
        assertEquals(original, fixture.ui.document.plan());
        assertEquals("err.notPlan", fixture.ui.alerts.getLast().purpose());
    }

    @Test void realFileConflictWithPreservedTimestampKeepsDiskBytesDirtyPlanAndUndo() throws Exception {
        FakeFileFlowContext ui = new FakeFileFlowContext(home);
        Path file = ui.planFile("Current");
        Plan original = ui.document.plan();
        ui.document.replace(original, file, false, List.of());
        ui.guard.remember(file);
        FileTime time = Files.getLastModifiedTime(file);
        ui.document.edit("change", plan -> plan.withStart(FakeFileFlowContext.TODAY.minusDays(1), plan.startBalance()));
        Plan memory = ui.document.plan();
        String outside = PlanMarkdownWriter.write(original.withStart(FakeFileFlowContext.TODAY.minusDays(2), original.startBalance()));
        Files.writeString(file, outside);
        Files.setLastModifiedTime(file, time);
        AtomicInteger continued = new AtomicInteger();
        ui.files.save(continued::incrementAndGet);
        assertEquals("externalChange", ui.alerts.getLast().purpose());
        ui.answer("cancel");
        assertEquals(outside, Files.readString(file));
        assertEquals(memory, ui.document.plan());
        assertTrue(ui.document.isDirty());
        assertTrue(ui.document.canUndo());
        assertTrue(ui.guard.changedExternally(file));
        assertEquals(0, continued.get());
    }

    @Test void restoredDirtyPlanWithMissingFileStillRequiresExternalChangeChoice() throws Exception {
        Fixture fixture = fixture();
        Path file = fixture.ui.environment.cashMemory().resolve("Missing.md");
        var reference = FilePlanStorage.reference(file);
        Plan memory = Plan.empty("Missing", FakeFileFlowContext.TODAY);
        fixture.ui.document.replace(memory, file, true, List.of());
        fixture.guard.remember(reference, PlanStorage.Version.ABSENT);
        fixture.files.save(null);
        assertEquals("externalChange", fixture.ui.alerts.getLast().purpose());
        fixture.ui.answer("cancel");
        assertFalse(fixture.storage.calls().contains("write"));
        assertEquals(memory, fixture.ui.document.plan());
        assertTrue(fixture.ui.document.isDirty());
        fixture.files.save(null);
        fixture.ui.answer("overwrite");
        assertEquals(PlanStorage.Version.ABSENT, fixture.storage.lastExpected());
        assertEquals(memory, fixture.storage.snapshot(reference).plan());
        assertFalse(fixture.ui.document.isDirty());
    }

    /** Открывает план из имитации через настоящий файловый выбор без файла на диске. */
    private Path open(Fixture fixture, String name) {
        Path file = fixture.ui.environment.cashMemory().resolve(name + ".md");
        fixture.storage.put(FilePlanStorage.reference(file), Plan.empty(name, FakeFileFlowContext.TODAY));
        fixture.files.openFile();
        fixture.ui.fileAnswer.accept(Optional.of(file));
        return file;
    }

    /** Создаёт независимое хранилище и подключает его к настоящим потокам, сохраняя готовый тестовый UI. */
    private Fixture fixture() throws Exception {
        FakeFileFlowContext ui = new FakeFileFlowContext(home);
        ui.forecastAvailable = false;
        FakePlanStorage storage = new FakePlanStorage();
        ExternalChangeGuard guard = new ExternalChangeGuard(storage);
        FileFlow[] files = new FileFlow[1];
        AutosaveService[] autosave = new AutosaveService[1];
        FlowContext context = (FlowContext) Proxy.newProxyInstance(FlowContext.class.getClassLoader(),
                new Class<?>[] { FlowContext.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "externalChanges" -> guard;
                    case "files" -> files[0];
                    case "autosave" -> autosave[0];
                    default -> ui.invoke(proxy, method, args);
                });
        files[0] = new FileFlow(context, storage);
        autosave[0] = new AutosaveService(context);
        return new Fixture(ui, storage, guard, files[0], autosave[0]);
    }

    /** Участники одного изолированного сценария. */
    private record Fixture(FakeFileFlowContext ui, FakePlanStorage storage, ExternalChangeGuard guard,
                           FileFlow files, AutosaveService autosave) { }
}
