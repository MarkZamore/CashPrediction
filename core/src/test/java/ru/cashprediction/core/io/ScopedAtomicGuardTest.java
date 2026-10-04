package ru.cashprediction.core.io;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Проверяет staging, no-overwrite и ограничения CSV/PNG без запуска клиента. */
class ScopedAtomicGuardTest {
    @TempDir Path directory;

    @Test void guardRunsAfterCompleteStagingAndRefusalPreservesOld() throws Exception {
        Path file = Files.writeString(directory.resolve("A.md"), "old");
        assertThrows(IOException.class, () -> AtomicFiles.writeString(file, "new", true, () -> {
            try (var children = Files.list(directory)) {
                Path staging = children.filter(path -> !path.equals(file)).findFirst().orElseThrow();
                assertEquals("new", Files.readString(staging));
            }
            assertEquals("old", Files.readString(file));
            throw new IOException("fixture conflict");
        }));
        assertEquals("old", Files.readString(file));
        try (var children = Files.list(directory)) { assertEquals(1, children.count()); }
    }

    @Test void occupiedAfterLastGuardIsNotOverwrittenByCreate() throws Exception {
        Path file = directory.resolve("A.md");
        assertThrows(IOException.class, () -> AtomicFiles.writeString(file, "new", false,
                () -> Files.writeString(file, "editor")));
        assertEquals("editor", Files.readString(file));
    }

    @Test void scopedExportsRefuseExternalParentsButPublishInside() throws Exception {
        Path root = Files.createDirectory(directory.resolve("CashMemory"));
        Path outside = directory.resolve("CashMemoryOther/nested/export.csv");
        assertThrows(IOException.class, () -> AtomicFiles.writeStringScoped(root, outside, "a;b", true, () -> { }));
        assertThrows(IOException.class, () -> AtomicFiles.writeScoped(root, outside.resolveSibling("chart.png"), new byte[] {1, 2}));
        assertFalse(Files.exists(outside.getParent().getParent()));
        Path csv = root.resolve("nested/export.csv"), png = root.resolve("chart.png");
        AtomicFiles.writeStringScoped(root, csv, "a;b", true, () -> { });
        AtomicFiles.writeScoped(root, png, new byte[] {1, 2});
        assertEquals("a;b", Files.readString(csv));
        assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(png));
    }

    @Test void renameDoesNotOverwriteDestinationCreatedAfterStaging() throws Exception {
        Path source = directory.resolve("A.md"), target = directory.resolve("B.md");
        String original = "# План: A\n\n<!-- manual note -->\n";
        Files.writeString(source, original);
        CountDownLatch staged = new CountDownLatch(1), occupied = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var editor = executor.submit(() -> {
                assertTrue(staged.await(10, TimeUnit.SECONDS));
                try { Files.writeString(target, "editor destination"); } finally { occupied.countDown(); }
                return null;
            });
            assertThrows(IOException.class, () -> new PlanRepository(directory).rename(source, "B", () -> {
                if (checks.incrementAndGet() == 2) {
                    staged.countDown();
                    try { assertTrue(occupied.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException interruption) { Thread.currentThread().interrupt(); throw new AssertionError(interruption); }
                }
            }));
            editor.get(10, TimeUnit.SECONDS);
            assertEquals(original, Files.readString(source));
            assertEquals("editor destination", Files.readString(target));
        }
    }

    @Test void renameConflictAfterPublicationPreservesBothValidCopies() throws Exception {
        Path source = directory.resolve("A.md"), target = directory.resolve("B.md");
        String original = "# План: A\n\n<!-- manual note -->\n";
        Files.writeString(source, original);
        AtomicInteger checks = new AtomicInteger();
        assertThrows(PlanRepository.VersionConflict.class, () -> new PlanRepository(directory).rename(source, "B", () -> {
            if (checks.incrementAndGet() == 3) Files.writeString(source, original + "<!-- external -->\n");
        }));
        assertEquals(original + "<!-- external -->\n", Files.readString(source));
        assertEquals(original.replace("План: A", "План: B"), Files.readString(target));
    }
}
