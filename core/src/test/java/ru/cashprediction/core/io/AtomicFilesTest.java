package ru.cashprediction.core.io;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Ограниченные проверки текстовых временных копий и безопасной очистки после сбоя. */
@Timeout(10)
class AtomicFilesTest {
    @TempDir Path dir;

    /** Ищет отсутствующий PID в ограниченном диапазоне, без запуска и завершения процессов. */
    static long absentPid() {
        return LongStream.range(1_000_000, 1_001_000)
                .filter(pid -> ProcessHandle.of(pid).isEmpty()).findFirst().orElseThrow();
    }

    /** XML-декларация задаёт расширение даже с BOM; Markdown и CSV остаются обычным читаемым текстом. */
    @Test void textExtensionFollowsContent() {
        assertEquals("md", AtomicFiles.textSuffix("# Budget\n"));
        assertEquals("md", AtomicFiles.textSuffix("date,amount\n2026-10-04,10\n"));
        assertEquals("md", AtomicFiles.textSuffix(""));
        assertEquals("md", AtomicFiles.textSuffix("<?xml-stylesheet type=\"text/css\"?>\n# Budget\n"));
        for (String xml : List.of("<?xml version=\"1.0\"?><session/>",
                "\uFEFF<?xml version=\"1.0\"?><session/>", " \n<?xml\nversion=\"1.0\"?><session/>"))
            assertEquals("xml", AtomicFiles.textSuffix(xml));
    }

    /** Создаёт расходную копию с точным служебным именем и явно заданным возрастом. */
    private Path temporary(long pid, String suffix, boolean stale) throws IOException {
        Path file = Files.writeString(dir.resolve(AtomicFiles.TEMP_PREFIX + pid + "-" + UUID.randomUUID()
                + "." + suffix), suffix.equals("xml") ? "<?xml version=\"1.0\"?><session/>" : "# Temporary\n");
        if (stale) Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(3600)));
        return file;
    }

    /** Уникальные пути используют расширение содержимого и всегда принадлежат служебному пространству. */
    @Test void temporaryNamesAreReservedAndUnique() {
        for (String suffix : List.of("md", "xml", "tmp", "rename.md")) {
            Path first = AtomicFiles.temporaryPath(dir.resolve("Budget.md"), suffix);
            Path second = AtomicFiles.temporaryPath(dir.resolve("Budget.md"), suffix);
            assertNotEquals(first, second);
            assertEquals(dir.toAbsolutePath(), first.getParent());
            assertTrue(first.getFileName().toString().endsWith("." + suffix));
            assertTrue(CashMemoryLayout.isServiceFileName(first.getFileName().toString()));
        }
    }

    /** Только старые расходные копии исчезнувшего процесса удаляются, независимо от текстового формата. */
    @Test void cleanupPreservesLiveOwnersFreshFilesAndUserFiles() throws IOException {
        long dead = absentPid();
        Path markdown = temporary(dead, "md", true);
        Path xml = temporary(dead, "xml", true);
        Path binary = temporary(dead, "tmp", true);
        Path fresh = temporary(dead, "md", false);
        Path live = temporary(ProcessHandle.current().pid(), "md", true);
        Path rename = temporary(dead, "rename.md", true);
        Path ordinary = Files.writeString(dir.resolve("Budget.md"), "# Budget\n");
        Path legacy = Files.writeString(dir.resolve("Budget.md.123.456.tmp"), "user data");
        Path malformed = Files.writeString(dir.resolve(AtomicFiles.TEMP_PREFIX + dead + "-notes.md"), "user data");
        for (Path file : List.of(ordinary, legacy, malformed))
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(3600)));
        Path directory = Files.createDirectory(dir.resolve(AtomicFiles.TEMP_PREFIX + dead + "-" + UUID.randomUUID() + ".md"));
        Files.setLastModifiedTime(directory, FileTime.from(Instant.now().minusSeconds(3600)));
        assertEquals(3, AtomicFiles.cleanupStaleTemporaryFiles(dir));
        for (Path removed : List.of(markdown, xml, binary)) assertFalse(Files.exists(removed));
        for (Path kept : List.of(fresh, live, rename, ordinary, legacy, malformed, directory))
            assertTrue(Files.exists(kept), kept.toString());
        assertEquals("user data", Files.readString(legacy));
        assertEquals(0, AtomicFiles.cleanupStaleTemporaryFiles(dir));
    }

    /** Старую копию другого живого процесса нельзя удалять только из-за истёкшей минуты. */
    @Test void cleanupPreservesOtherLiveProcess() throws IOException {
        var parent = ProcessHandle.current().parent();
        assumeTrue(parent.isPresent() && parent.get().isAlive(), "Parent process unavailable");
        Path active = temporary(parent.orElseThrow().pid(), "xml", true);
        assertEquals(0, AtomicFiles.cleanupStaleTemporaryFiles(dir));
        assertTrue(Files.exists(active));
    }

    /** Очистка не следует ссылкам ни на файл, ни на папку, даже при точном служебном имени. */
    @Test void cleanupPreservesSymbolicLinksAndTheirTargets() throws IOException {
        Path user = Files.writeString(dir.resolve("User.md"), "# User\n");
        Files.setLastModifiedTime(user, FileTime.from(Instant.now().minusSeconds(3600)));
        Path link = dir.resolve(AtomicFiles.TEMP_PREFIX + absentPid() + "-" + UUID.randomUUID() + ".md");
        Path directoryLink = dir.resolve("folder-link");
        try {
            Files.createSymbolicLink(link, user);
            Files.createSymbolicLink(directoryLink, dir);
        } catch (UnsupportedOperationException | SecurityException | FileSystemException unavailable) {
            assumeTrue(false, "Symbolic links unavailable: " + unavailable.getClass().getSimpleName());
        }
        Path stale = temporary(absentPid(), "md", true);
        assertEquals(0, AtomicFiles.cleanupStaleTemporaryFiles(directoryLink));
        assertTrue(Files.exists(stale));
        assertEquals(1, AtomicFiles.cleanupStaleTemporaryFiles(dir));
        assertTrue(Files.exists(link, LinkOption.NOFOLLOW_LINKS));
        assertEquals("# User\n", Files.readString(user));
    }

    /** Атомарная замена сохраняет байты Markdown, XML и бинарного экспорта, не оставляя копий. */
    @Test void writesPreserveContentAndLeaveNoTemporaryFiles() throws IOException {
        Path target = dir.resolve("Budget.md");
        AtomicFiles.writeString(target, "# Budget\r\n\ntext\n");
        assertEquals("# Budget\r\n\ntext\n", Files.readString(target));
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<session/>\n";
        AtomicFiles.writeString(target, xml);
        assertEquals(xml, Files.readString(target));
        byte[] binary = new byte[] {0, 1, -1};
        AtomicFiles.write(target, binary);
        assertArrayEquals(binary, Files.readAllBytes(target));
        try (var entries = Files.list(dir)) { assertEquals(List.of(target), entries.toList()); }
    }

    /** Неудачная замена папки сохраняет её содержимое и удаляет созданную расходную копию. */
    @Test void failedReplacementLeavesTargetAndNoTemporaryFiles() throws IOException {
        Path target = Files.createDirectory(dir.resolve("Budget.md"));
        Path child = Files.writeString(target.resolve("keep.md"), "# Keep\n");
        assertThrows(IOException.class, () -> AtomicFiles.writeString(target, "# Replacement\n"));
        assertEquals("# Keep\n", Files.readString(child));
        try (var entries = Files.list(dir)) { assertEquals(List.of(target), entries.toList()); }
    }
}
