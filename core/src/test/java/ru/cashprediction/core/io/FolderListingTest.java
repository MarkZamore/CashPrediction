package ru.cashprediction.core.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Обозреватель папок окна «Выбор файла» (спецификация v2, §6.21): порядок, фильтр, служебные файлы CashMemory,
 * режим папок, обрезка и ошибки.
 */
class FolderListingTest {

    @Test
    void foldersFirstThenMatchingFilesByNameIgnoringCase(@TempDir Path home) throws IOException {
        Path dir = Files.createDirectories(home.resolve("plans"));
        Files.createDirectories(dir.resolve("b-folder"));
        Files.createDirectories(dir.resolve("A-folder"));
        Files.writeString(dir.resolve("zeta.md"), "x");
        Files.writeString(dir.resolve("Alpha.MD"), "xy");
        Files.writeString(dir.resolve("notes.txt"), "x");
        Files.setLastModifiedTime(dir.resolve("zeta.md"), FileTime.from(Instant.parse("2026-09-13T07:15:00Z")));

        FolderListing listing = new FolderListing(home.resolve("CashMemory"), ZoneOffset.ofHours(3), 100);
        FolderListing.Listing result = listing.list(dir, FolderListing.Mode.FILES, List.of("md"));

        assertEquals(List.of("A-folder", "b-folder", "Alpha.MD", "zeta.md"),
                result.entries().stream().map(FolderListing.Entry::name).toList());
        assertEquals(dir.toAbsolutePath().normalize(), result.folder());
        assertEquals(home.toAbsolutePath().normalize(), result.parent());
        assertFalse(result.truncated());
        FolderListing.Entry zeta = result.entries().get(3);
        assertEquals("13.09.2026 10:15", zeta.modifiedText());
        assertEquals(1, zeta.size());
        assertTrue(result.entries().get(0).directory());
    }

    @Test
    void emptyFilterShowsAllFilesAndDirectoryModeOnlyFolders(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(dir.resolve("a.csv"), "");
        Files.writeString(dir.resolve("b.png"), "");
        FolderListing listing = new FolderListing(dir.resolve("CashMemory"));
        assertEquals(List.of("sub", "a.csv", "b.png"),
                listing.list(dir, FolderListing.Mode.FILES, List.of()).entries().stream().map(FolderListing.Entry::name).toList());
        assertEquals(List.of("sub"),
                listing.list(dir, FolderListing.Mode.DIRECTORIES, List.of("md")).entries().stream().map(FolderListing.Entry::name).toList());
    }

    @Test
    void serviceFilesAreHiddenOnlyInCashMemory(@TempDir Path home) throws IOException {
        Path cashMemory = Files.createDirectories(home.resolve("CashMemory"));
        Path other = Files.createDirectories(home.resolve("Other"));
        for (Path dir : List.of(cashMemory, other)) {
            for (String name : List.of("settings.md", "web-session.md", "web-session.plan.md", "session-fx.xml", "Мой план.md")) {
                Files.writeString(dir.resolve(name), "");
            }
        }
        FolderListing listing = new FolderListing(cashMemory);
        assertEquals(List.of("Мой план.md"), listing.list(cashMemory, FolderListing.Mode.FILES, List.of("md", "xml"))
                .entries().stream().map(FolderListing.Entry::name).toList());
        assertEquals(5, listing.list(other, FolderListing.Mode.FILES, List.of("md", "xml")).entries().size());
        assertTrue(FolderListing.isServiceFile("SESSION-SWING.XML"));
        assertFalse(FolderListing.isServiceFile("session.md"));
    }

    @Test
    void largeFoldersAreTruncated(@TempDir Path dir) throws IOException {
        for (int i = 0; i < 5; i++) {
            Files.writeString(dir.resolve("p" + i + ".md"), "");
        }
        FolderListing.Listing result = new FolderListing(dir.resolve("CashMemory"), ZoneOffset.UTC, 3)
                .list(dir, FolderListing.Mode.FILES, List.of("md"));
        assertEquals(3, result.entries().size());
        assertTrue(result.truncated());
    }

    @Test
    void missingFolderOrFileIsNoSuchFile(@TempDir Path dir) throws IOException {
        FolderListing listing = new FolderListing(dir);
        assertThrows(NoSuchFileException.class, () -> listing.list(dir.resolve("absent"), FolderListing.Mode.FILES, List.of()));
        Path file = Files.writeString(dir.resolve("file.md"), "");
        assertThrows(NoSuchFileException.class, () -> listing.list(file, FolderListing.Mode.FILES, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FolderListing(dir, ZoneOffset.UTC, 0));
    }

    @Test
    void rootsAreReported(@TempDir Path dir) {
        List<FolderListing.Root> roots = new FolderListing(dir).roots();
        assertFalse(roots.isEmpty());
        assertTrue(roots.stream().allMatch(r -> r.path().getParent() == null));
    }
}
