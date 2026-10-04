package ru.cashprediction.core.app.file;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.model.Plan;
/** Проверяет внешний импорт и сохранение копии в CashMemory через настоящий FileFlow, без окон. */
class ImportedWorkflowTest {
    @TempDir Path directory;
    @Test void externalRenameHasHumanReadableReadOnlyRefusal() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(directory);
        Plan plan = Plan.empty("Imported", FakeFileFlowContext.TODAY);
        String text = PlanMarkdownWriter.write(plan);
        Path external = Files.writeString(directory.resolve("Imported.md"), text);
        f.document.replace(plan, external, false, List.of());
        var denied = assertThrows(IllegalArgumentException.class, () -> f.files.renameTo("Renamed"));
        assertTrue(denied.getMessage().contains("Сохранить как"));
        assertEquals(text, Files.readString(external));
        assertFalse(Files.exists(directory.resolve("Renamed.md")));
        assertTrue(f.alerts.isEmpty());
    }
    @Test void autosaveDoesNotAskAndManualSaveAsCopiesInsideThenContinues() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(directory);
        Plan plan = Plan.empty("Imported", FakeFileFlowContext.TODAY);
        String text = PlanMarkdownWriter.write(plan);
        Path external = Files.writeString(directory.resolve("Imported.md"), text);
        f.document.replace(plan, external, true, List.of());
        f.guard.remember(external);
        f.autosave.setEnabled(true);
        f.scheduler.advance(1100);
        f.autosave.documentChanged();
        f.scheduler.advance(1100);
        assertTrue(f.alerts.isEmpty());
        assertTrue(f.chooserRequests.isEmpty());
        assertTrue(f.autosaveProblem.contains("Сохранить как"));
        assertTrue(f.document.isDirty());
        assertEquals(text, Files.readString(external));
        boolean[] continued = {false};
        f.files.save(() -> continued[0] = true);
        assertEquals(f.environment.cashMemory(), f.chooserRequests.getLast().initialFolder());
        assertFalse(continued[0]);
        Path copy = f.environment.cashMemory().resolve("Imported.md");
        f.fileAnswer.accept(Optional.of(copy));
        assertTrue(continued[0]);
        assertFalse(f.document.isDirty());
        assertEquals(copy, f.document.file().orElseThrow());
        assertEquals(text, Files.readString(external));
        assertEquals(text, Files.readString(copy));
        assertTrue(f.alerts.isEmpty());
    }
}
