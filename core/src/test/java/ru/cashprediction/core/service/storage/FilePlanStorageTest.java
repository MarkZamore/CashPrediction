package ru.cashprediction.core.service.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.text.Texts;

/** Проверяет файловый адаптер, неизменяемость снимков и отказы по версии без потери исходных байтов. */
class FilePlanStorageTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    @TempDir Path directory;

    @Test void createReadListAndWriteReturnStableImmutableSnapshots() {
        FilePlanStorage storage = new FilePlanStorage(directory);
        var reference = FilePlanStorage.reference(directory.resolve("A.md"));
        Plan original = Plan.empty("A", TODAY);
        var stored = storage.write(reference, original, PlanStorage.Version.ABSENT).requireValue();
        var first = storage.read(reference, TODAY, Optional.of(stored.version())).requireValue();
        assertEquals(original, first.plan());
        assertEquals(stored.version(), first.version());
        var entries = storage.list(FilePlanStorage.collection(directory)).requireValue();
        assertEquals(List.of("A"), entries.stream().map(PlanStorage.Entry::name).toList());
        assertThrows(UnsupportedOperationException.class, entries::clear);
        var next = storage.write(reference, original.withStart(TODAY.minusDays(1), original.startBalance()), first.version()).requireValue();
        assertNotEquals(first.version(), next.version());
        assertEquals(original, first.plan());
        assertEquals(next.version(), storage.version(reference).requireValue());

        List<Diagnostic> diagnostics = new ArrayList<>(List.of(Diagnostic.warning("fixture")));
        var snapshot = new PlanStorage.Snapshot(reference, first.version(), original, diagnostics);
        diagnostics.clear();
        assertEquals(1, snapshot.diagnostics().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.diagnostics().clear());
    }

    @Test void staleWriteAndReadDetectContentChangeEvenWhenTimestampAndLengthArePreserved() throws Exception {
        FilePlanStorage storage = new FilePlanStorage(directory);
        Path file = directory.resolve("A.md");
        var reference = FilePlanStorage.reference(file);
        Plan original = Plan.empty("A", TODAY);
        var first = storage.write(reference, original, PlanStorage.Version.ABSENT).requireValue();
        FileTime time = Files.getLastModifiedTime(file);
        String before = Files.readString(file);
        String outside = PlanMarkdownWriter.write(original.withStart(TODAY.minusDays(1), original.startBalance()));
        assertEquals(before.length(), outside.length());
        Files.writeString(file, outside);
        Files.setLastModifiedTime(file, time);

        var refused = storage.write(reference, original, first.version());
        assertEquals(PlanStorage.Code.CONFLICT, refused.problem().code());
        assertEquals(PlanStorage.Conflict.VERSION_CHANGED, refused.problem().conflict());
        assertEquals(outside, Files.readString(file));
        var staleRead = storage.read(reference, TODAY, Optional.of(first.version()));
        assertEquals(PlanStorage.Code.CONFLICT, staleRead.problem().code());
        var current = storage.read(reference, TODAY).requireValue();
        assertEquals(TODAY.minusDays(1), current.plan().startDate());
        storage.write(reference, original, current.version()).requireValue();
        assertEquals(before, Files.readString(file));
    }

    @Test void distinguishesMissingCorruptIoAndCreateCollisionWithoutChangingBytes() throws Exception {
        FilePlanStorage storage = new FilePlanStorage(directory);
        Path file = directory.resolve("A.md");
        var reference = FilePlanStorage.reference(file);
        assertEquals(PlanStorage.Version.ABSENT, storage.version(reference).requireValue());
        assertEquals(PlanStorage.Code.MISSING, storage.read(reference, TODAY).problem().code());
        var directoryRef = FilePlanStorage.reference(Files.createDirectory(directory.resolve("Folder.md")));
        assertEquals(PlanStorage.Version.ABSENT, storage.version(directoryRef).requireValue());
        assertEquals(PlanStorage.Code.MISSING, storage.read(directoryRef, TODAY).problem().code());
        Files.writeString(file, "This is not a plan");
        assertEquals(PlanStorage.Code.CORRUPT, storage.read(reference, TODAY).problem().code());
        var collision = storage.write(reference, Plan.empty("A", TODAY), PlanStorage.Version.ABSENT);
        assertEquals(PlanStorage.Code.CONFLICT, collision.problem().code());
        assertEquals(PlanStorage.Conflict.VERSION_CHANGED, collision.problem().conflict());
        assertEquals("This is not a plan", Files.readString(file));
        var corruptVersion = storage.version(reference).requireValue();
        storage.write(reference, Plan.empty("A", TODAY), corruptVersion).requireValue();
        assertTrue(storage.read(reference, TODAY).succeeded());

        Path blocked = Files.writeString(directory.resolve("blocked"), "block");
        var blockedRef = FilePlanStorage.reference(blocked.resolve("B.md"));
        assertIoError(storage.write(blockedRef, Plan.empty("B", TODAY), PlanStorage.Version.ABSENT));
        assertIoError(storage.version(blockedRef));
        assertIoError(storage.read(blockedRef, TODAY));
        assertIoError(storage.rename(blockedRef, "C", PlanStorage.Version.ABSENT));
        var nestedBlockedRef = FilePlanStorage.reference(blocked.resolve("missing/B.md"));
        assertIoError(storage.write(nestedBlockedRef, Plan.empty("B", TODAY), PlanStorage.Version.ABSENT));
        assertEquals("block", Files.readString(blocked));
    }

    @Test void directoryIsMissingAsAPlanButCannotBeReplacedByWrite() throws Exception {
        FilePlanStorage storage = new FilePlanStorage(directory);
        for (boolean populated : List.of(false, true)) {
            Path folder = Files.createDirectory(directory.resolve(populated ? "Populated.md" : "Empty.md"));
            Path marker = folder.resolve("marker.txt");
            if (populated) Files.writeString(marker, "directory-marker");
            var reference = FilePlanStorage.reference(folder);

            assertEquals(PlanStorage.Version.ABSENT, storage.version(reference).requireValue());
            assertEquals(PlanStorage.Code.MISSING, storage.read(reference, TODAY).problem().code());
            assertEquals(PlanStorage.Code.MISSING, storage.rename(reference, "Other", PlanStorage.Version.ABSENT).problem().code());
            assertIoError(storage.write(reference, Plan.empty("A", TODAY), PlanStorage.Version.ABSENT));

            assertTrue(Files.isDirectory(folder));
            if (populated) assertEquals("directory-marker", Files.readString(marker));
            try (var entries = Files.list(folder)) {
                assertEquals(populated ? List.of("marker.txt") : List.of(),
                        entries.map(path -> path.getFileName().toString()).toList());
            }
            assertFalse(Files.exists(directory.resolve("Other.md")));
        }
    }

    @Test void malformedReferencesAreIoErrorsWithoutCreatingOrChangingFiles() throws Exception {
        FilePlanStorage storage = new FilePlanStorage(directory);
        Path existing = Files.writeString(directory.resolve("A.md"), "existing-marker");
        byte[] before = Files.readAllBytes(existing);
        var encoder = Base64.getUrlEncoder();
        List<String> tokens = List.of("%invalid", "",
                encoder.encodeToString("relative.md".getBytes(StandardCharsets.UTF_8)),
                encoder.encodeToString(directory.resolve("missing/../A.md").toString().getBytes(StandardCharsets.UTF_8)),
                encoder.encodeToString((directory + "\0invalid.md").getBytes(StandardCharsets.UTF_8)));
        for (String token : tokens) {
            var reference = new PlanStorage.Reference(token);
            assertIoError(storage.version(reference));
            assertEquals(Texts.get("err.generic"), storage.version(reference).problem().detail());
            assertIoError(storage.read(reference, TODAY));
            assertIoError(storage.write(reference, Plan.empty("A", TODAY), PlanStorage.Version.ABSENT));
            assertIoError(storage.rename(reference, "Other", PlanStorage.Version.ABSENT));
            assertIoError(storage.list(new PlanStorage.Collection(token)));
            assertArrayEquals(before, Files.readAllBytes(existing));
            try (var entries = Files.list(directory)) {
                assertEquals(List.of("A.md"), entries.map(path -> path.getFileName().toString()).toList());
            }
        }
    }

    @Test void missingParentDirectoriesRemainValidCreateLocations() {
        FilePlanStorage storage = new FilePlanStorage(directory);
        Path file = directory.resolve("missing/nested/A.md");
        var reference = FilePlanStorage.reference(file);
        Plan plan = Plan.empty("A", TODAY);

        assertEquals(PlanStorage.Version.ABSENT, storage.version(reference).requireValue());
        assertEquals(PlanStorage.Code.MISSING, storage.read(reference, TODAY).problem().code());
        var created = storage.write(reference, plan, PlanStorage.Version.ABSENT).requireValue();

        assertTrue(Files.isRegularFile(file));
        assertEquals(plan, storage.read(reference, TODAY, Optional.of(created.version())).requireValue().plan());
    }

    @Test void renamePreservesManualMarkdownAndRejectsStaleSourceAndOccupiedTarget() throws Exception {
        FilePlanStorage storage = new FilePlanStorage(directory);
        Path file = directory.resolve("A.md");
        Plan original = Plan.empty("A", TODAY);
        new PlanRepository(directory).save(original, file);
        String manual = Files.readString(file) + "\n<!-- manual formatting -->\n";
        Files.writeString(file, manual);
        var reference = FilePlanStorage.reference(file);
        var version = storage.version(reference).requireValue();
        Path occupied = directory.resolve("Taken.md");
        Files.writeString(occupied, "taken");
        assertEquals(PlanStorage.Conflict.NAME_EXISTS, storage.rename(reference, "Taken", version).problem().conflict());
        assertEquals(manual, Files.readString(file));
        assertEquals("taken", Files.readString(occupied));
        Path occupiedFolder = Files.createDirectory(directory.resolve("Taken folder.md"));
        Path marker = Files.writeString(occupiedFolder.resolve("marker.txt"), "directory-marker");
        var collision = storage.rename(reference, " Taken\r\nfolder ", version);
        assertEquals(PlanStorage.Code.CONFLICT, collision.problem().code());
        assertEquals(PlanStorage.Conflict.NAME_EXISTS, collision.problem().conflict());
        assertEquals(manual, Files.readString(file));
        assertEquals("directory-marker", Files.readString(marker));
        Files.writeString(file, manual + "\n<!-- outside -->\n");
        String outside = Files.readString(file);
        assertEquals(PlanStorage.Conflict.VERSION_CHANGED, storage.rename(reference, "B", version).problem().conflict());
        assertEquals(outside, Files.readString(file));
        assertFalse(Files.exists(directory.resolve("B.md")));
        var renamed = storage.rename(reference, "B", storage.version(reference).requireValue()).requireValue();
        assertEquals(directory.resolve("B.md"), FilePlanStorage.path(renamed.reference()));
        assertFalse(Files.exists(file));
        String titleA = PlanMarkdownWriter.write(original).lines().findFirst().orElseThrow();
        String titleB = PlanMarkdownWriter.write(original.withName("B")).lines().findFirst().orElseThrow();
        assertEquals(outside.replace(titleA, titleB), Files.readString(FilePlanStorage.path(renamed.reference())));
        assertEquals(renamed.version(), storage.version(renamed.reference()).requireValue());
    }

    @Test void tolerantReaderRetainsDiagnosticsAndProtectedFilesRemainUntouched() throws Exception {
        FilePlanStorage storage = new FilePlanStorage(directory);
        Path file = directory.resolve("A.md");
        String text = PlanMarkdownWriter.write(Plan.empty("A", TODAY));
        Files.writeString(file, text + "\n## Unknown section\nfixture\n");
        var loaded = storage.read(FilePlanStorage.reference(file), TODAY).requireValue();
        assertFalse(loaded.diagnostics().isEmpty());
        Path settings = Files.writeString(directory.resolve("settings.md"), "service-marker");
        var protectedRef = FilePlanStorage.reference(settings);
        assertEquals(PlanStorage.Code.IO_ERROR, storage.write(protectedRef, Plan.empty("A", TODAY), storage.version(protectedRef).requireValue()).problem().code());
        assertEquals("service-marker", Files.readString(settings));
        assertEquals(List.of("A"), storage.list(FilePlanStorage.collection(directory)).requireValue().stream().map(PlanStorage.Entry::name).toList());
    }

    /** Ошибка адреса или файловой инфраструктуры не предлагает подтверждение перезаписи как бизнес-конфликт. */
    private static void assertIoError(PlanStorage.Result<?> result) {
        assertFalse(result.succeeded());
        assertEquals(PlanStorage.Code.IO_ERROR, result.problem().code());
        assertEquals(PlanStorage.Conflict.NONE, result.problem().conflict());
    }
}
