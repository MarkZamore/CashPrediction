package ru.cashprediction.core.service.storage;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.model.Plan;

/** Проверяет настоящий filesystem: внешний Markdown читается, но приложение пишет только в своём корне. */
class ManagedWriteScopeTest {
    @TempDir Path directory;
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Test void externalReadAllowedButSaveAndRenamePreserveExternalBytes() throws Exception {
        Path root = Files.createDirectory(directory.resolve("CashMemory"));
        Path file = directory.resolve("External.md");
        Plan plan = Plan.empty("External", TODAY);
        String text = PlanMarkdownWriter.write(plan) + "\n<!-- manual note -->\n";
        Files.writeString(file, text);
        FilePlanStorage storage = new FilePlanStorage(root);
        var reference = FilePlanStorage.reference(file);
        var read = storage.read(reference, TODAY).requireValue();
        assertEquals(plan.name(), read.plan().name());
        assertEquals(plan.startDate(), read.plan().startDate());
        assertFalse(read.plan().rawBlocks().isEmpty());
        assertFalse(storage.write(reference, plan, read.version()).succeeded());
        assertEquals(text, Files.readString(file));
        assertFalse(storage.rename(reference, "Renamed", read.version()).succeeded());
        assertEquals(text, Files.readString(file));
        assertFalse(Files.exists(directory.resolve("Renamed.md")));
        try (var children = Files.list(root)) { assertEquals(0, children.count()); }
    }

    @Test void deniedCreationDoesNotCreateExternalParentOrStaging() throws Exception {
        Path root = Files.createDirectory(directory.resolve("CashMemory"));
        Path parent = directory.resolve("Outside");
        var result = new FilePlanStorage(root).write(FilePlanStorage.reference(parent.resolve("A.md")),
                Plan.empty("A", TODAY), PlanStorage.Version.ABSENT);
        assertFalse(result.succeeded());
        assertFalse(Files.exists(parent));
    }

    @Test void readOnlyAdapterAndInternalCopyAreExplicit() throws Exception {
        Path root = Files.createDirectory(directory.resolve("CashMemory"));
        Plan plan = Plan.empty("External", TODAY);
        Path external = Files.writeString(directory.resolve("External.md"), PlanMarkdownWriter.write(plan));
        FilePlanStorage readOnly = new FilePlanStorage();
        var reference = FilePlanStorage.reference(external);
        var read = readOnly.read(reference, TODAY).requireValue();
        assertFalse(readOnly.write(reference, plan, read.version()).succeeded());
        FilePlanStorage writable = new FilePlanStorage(root);
        Path copy = root.resolve("nested/External.md");
        var saved = writable.write(FilePlanStorage.reference(copy), read.plan(), PlanStorage.Version.ABSENT).requireValue();
        assertEquals(plan, writable.read(saved.reference(), TODAY).requireValue().plan());
        assertEquals(PlanMarkdownWriter.write(plan), Files.readString(external));
    }
}
