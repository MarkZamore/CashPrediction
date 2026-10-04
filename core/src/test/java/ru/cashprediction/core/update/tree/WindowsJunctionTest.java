package ru.cashprediction.core.update.tree;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.update.model.*;
import static org.junit.jupiter.api.Assertions.*;

/** Настоящие NTFS junction и hard links проверяются без изменения привилегий ОС. */
@EnabledOnOs(OS.WINDOWS)
class WindowsJunctionTest {
    @TempDir Path temporary;

    @Test void rejectsManagedJunctionAndDestinationAncestor() throws IOException, InterruptedException {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("keep"), "keep");
        Path root = Files.createDirectory(temporary.resolve("root"));
        Path junction = root.resolve("app");
        junction(junction, outside);
        try {
            assertThrows(IOException.class, () -> TreeDeltaEngine.inventory(root));
            assertThrows(IOException.class, () -> TreeDeltaEngine.inventory(junction));
            Path zip = fullZip();
            UpdateManifest manifest = manifest(zip, List.of());
            assertThrows(IOException.class, () -> TreeDeltaEngine.extractFull(zip, manifest, junction.resolve("out")));
            assertFalse(Files.exists(outside.resolve("out")));
            assertEquals("keep", Files.readString(outside.resolve("keep")));
        } finally {
            // Удаляется сам junction, без обхода или удаления внешнего каталога.
            Files.delete(junction);
        }
    }

    @Test void rejectsJunctionAncestorsForEveryEngineInputAndOutput() throws IOException, InterruptedException {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path child = Files.createDirectory(outside.resolve("child"));
        Files.writeString(child.resolve("keep"), "keep");
        Path baseRoot = Files.createDirectory(temporary.resolve("base"));
        Path targetRoot = Files.createDirectory(temporary.resolve("target"));
        InstalledVersion base = new InstalledVersion(1, "1".repeat(40), TreeDeltaEngine.treeHash(List.of()));
        Path zip = fullZip(), patch = temporary.resolve("original.cpdelta");
        TreeDeltaEngine.create(baseRoot, base, targetRoot, manifest(zip, List.of()), patch);
        DeltaPatch delta = new DeltaPatch(1, base.commitSha(), base.treeSha256(), "CashPrediction.cpdelta",
                Files.size(patch), SafeTree.hash(patch));
        UpdateManifest target = manifest(zip, List.of(delta));
        Files.copy(zip, outside.resolve("full.zip")); Files.copy(patch, outside.resolve("patch.cpdelta"));
        Path alias = temporary.resolve("alias");
        junction(alias, outside);
        Path out = temporary.resolve("out");
        try {
            assertLinkFailure(() -> TreeDeltaEngine.inventory(alias.resolve("child")));
            assertLinkFailure(() -> TreeDeltaEngine.verify(alias.resolve("child"), List.of(), base.treeSha256()));
            assertLinkFailure(() -> TreeDeltaEngine.create(alias.resolve("child"), base, targetRoot, target, out));
            assertLinkFailure(() -> TreeDeltaEngine.create(baseRoot, base, alias.resolve("child"), target, out));
            assertLinkFailure(() -> TreeDeltaEngine.create(baseRoot, base, targetRoot, target, alias.resolve("child/new.cpdelta")));
            assertLinkFailure(() -> TreeDeltaEngine.apply(alias.resolve("child"), base, patch, target, out));
            assertLinkFailure(() -> TreeDeltaEngine.apply(baseRoot, base, alias.resolve("patch.cpdelta"), target, out));
            assertLinkFailure(() -> TreeDeltaEngine.apply(baseRoot, base, patch, target, alias.resolve("child/out")));
            assertLinkFailure(() -> TreeDeltaEngine.extractFull(alias.resolve("full.zip"), target, out));
            assertLinkFailure(() -> TreeDeltaEngine.extractFull(zip, target, alias.resolve("child/out")));
            IOException cleanupFailure = new IOException("ORIGINAL_FAILURE");
            SafeTree.cleanup(alias.resolve("child"), cleanupFailure);
            assertEquals(1, cleanupFailure.getSuppressed().length);
            assertEquals("LINK_OR_REPARSE", cleanupFailure.getSuppressed()[0].getMessage());
            assertFalse(Files.exists(out));
            assertFalse(Files.exists(child.resolve("out")));
            assertFalse(Files.exists(child.resolve("new.cpdelta")));
            assertEquals("keep", Files.readString(child.resolve("keep")));
            assertArrayEquals(Files.readAllBytes(zip), Files.readAllBytes(outside.resolve("full.zip")));
            assertArrayEquals(Files.readAllBytes(patch), Files.readAllBytes(outside.resolve("patch.cpdelta")));
        } finally { Files.delete(alias); }
    }

    @Test void rejectsNestedManagedJunctionAndStopsCleanupBeforeOutsideWrites() throws IOException, InterruptedException {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path keep = Files.writeString(outside.resolve("keep"), "keep");
        Path root = Files.createDirectory(temporary.resolve("root"));
        Files.createDirectory(root.resolve("app"));
        Path nested = root.resolve("app/nested");
        junction(nested, outside);
        try {
            SafeTree.readOnly(keep, true);
            assertLinkFailure(() -> TreeDeltaEngine.inventory(root));
            IOException failure = new IOException("ORIGINAL_FAILURE");
            SafeTree.cleanup(root, failure);
            if (Files.exists(nested, LinkOption.NOFOLLOW_LINKS))
                assertTrue(failure.getSuppressed().length > 0, "cleanup must report refusal to traverse the junction");
            assertEquals("keep", Files.readString(keep));
            assertTrue(SafeTree.readOnly(keep));
        } finally {
            Files.deleteIfExists(nested);
            SafeTree.readOnly(keep, false);
        }
    }

    @Test void rejectsActualHardLinkAliasesBetweenManagedFiles() throws IOException {
        Path outside = Files.writeString(temporary.resolve("outside-file"), "keep");
        int index = 0;
        for (List<String> names : List.of(List.of("CashPrediction.exe", "app/alias"),
                List.of("app/first", "runtime/second"), List.of("CashPrediction-Swing.exe", "CashPrediction-Web.exe"))) {
            Path root = Files.createDirectory(temporary.resolve("hardlinks-" + index++));
            for (String name : names) {
                Path link = root.resolve(name); Files.createDirectories(link.getParent()); Files.createLink(link, outside);
                assertTrue(Files.isSameFile(link, outside));
                assertFalse(Files.isSymbolicLink(link));
            }
            assertEquals("TREE_FILE_ALIAS", assertThrows(IOException.class, () -> TreeDeltaEngine.inventory(root)).getMessage());
            assertEquals("keep", Files.readString(outside));
        }
    }

    @Test void acceptsIndependentFilesWithIdenticalAndEmptyContents() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("independent"));
        Files.createDirectories(root.resolve("app"));
        Files.createDirectories(root.resolve("runtime"));
        Files.writeString(root.resolve("CashPrediction.exe"), "same");
        Files.writeString(root.resolve("app/copy"), "same");
        Files.writeString(root.resolve("app/empty"), "");
        Files.writeString(root.resolve("runtime/empty"), "");
        assertFalse(Files.isSameFile(root.resolve("CashPrediction.exe"), root.resolve("app/copy")));
        assertFalse(Files.isSameFile(root.resolve("app/empty"), root.resolve("runtime/empty")));
        assertEquals(4, TreeDeltaEngine.inventory(root).size());
    }

    private static void assertLinkFailure(org.junit.jupiter.api.function.Executable action) {
        assertEquals("LINK_OR_REPARSE", assertThrows(IOException.class, action).getMessage());
    }

    private Path fullZip() throws IOException {
        Path zip = temporary.resolve("full.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("CashPrediction/")); out.closeEntry();
        }
        return zip;
    }

    private static UpdateManifest manifest(Path zip, List<DeltaPatch> deltas) throws IOException {
        return new UpdateManifest(3, "3".repeat(40), "3", Instant.parse("2026-10-03T00:00:00Z"),
                "CashPrediction-portable.zip", Files.size(zip), SafeTree.hash(zip),
                TreeDeltaEngine.treeHash(List.of()), List.of(), deltas);
    }

    private static void junction(Path junction, Path outside) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), outside.toString()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("JUNCTION_CREATION_TIMEOUT");
        }
        assertEquals(0, process.exitValue(), "JUNCTION_CREATION_FAILED");
    }
}
