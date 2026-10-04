package ru.cashprediction.core.update.tree;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.update.model.*;
import static org.junit.jupiter.api.Assertions.*;

/** Реальные деревья A/B к C, полный ZIP, атрибуты и отказы повреждённым входам. */
class TreeDeltaEngineTest {
    @TempDir Path temporary;

    @AfterEach void writableForCleanup() throws IOException {
        try (var paths = Files.walk(temporary)) {
            for (Path p : paths.filter(Files::isRegularFile).toList()) SafeTree.readOnly(p, false);
        }
    }

    @Test void bothBasesMatchFullWithAddChangeDeleteEmptyAndReadOnly() throws IOException {
        Path a = tree("a", "old1"), b = tree("b", "old2"), c = tree("c-\u00e9", "new!");
        write(a, "app/delete", "remove"); write(b, "runtime/delete", "remove");
        write(c, "app/added", "added"); write(c, "runtime/empty", "");
        write(c, "app/\u00e9.jar", "unicode");
        SafeTree.readOnly(c.resolve("app/stable"), true);
        byte[] memory = Files.readAllBytes(a.resolve("CashMemory/user.md"));
        List<FileEntry> targetFiles = TreeDeltaEngine.inventory(c);
        Path full = fullZip(c, targetFiles, temporary.resolve("full.zip"));
        UpdateManifest target = manifest(c, full, List.of());
        Path extracted = temporary.resolve("full-out");
        TreeDeltaEngine.extractFull(full, target, extracted);
        assertEquals(targetFiles, TreeDeltaEngine.inventory(extracted));
        assertFalse(Files.exists(extracted.resolve("CashMemory")));
        for (Path root : List.of(a, b)) {
            int release = root.equals(a) ? 1 : 2;
            InstalledVersion base = installed(root, release);
            Path patch = temporary.resolve("patch-" + release + ".cpdelta");
            TreeDeltaEngine.create(root, base, c, target, patch);
            DeltaPatch descriptor = descriptor(base, patch);
            UpdateManifest withPatch = manifest(c, full, List.of(descriptor));
            Path out = temporary.resolve("out-" + release);
            TreeDeltaEngine.apply(root, base, patch, withPatch, out);
            assertEquals(targetFiles, TreeDeltaEngine.inventory(out));
            assertFalse(Files.exists(out.resolve("app/delete")));
            assertFalse(Files.exists(out.resolve("runtime/delete")));
            assertTrue(SafeTree.readOnly(out.resolve("app/stable")));
            assertEquals(0, Files.size(out.resolve("runtime/empty")));
            try (ZipFile zip = new ZipFile(patch.toFile())) {
                assertNotNull(zip.getEntry("payload/app/stable"));
                assertNull(zip.getEntry("payload/CashPrediction.exe"));
                assertNull(zip.getEntry("payload/app/delete"));
            }
        }
        assertArrayEquals(memory, Files.readAllBytes(a.resolve("CashMemory/user.md")));
        assertEquals("unmanaged", Files.readString(a.resolve("readme.txt")));
    }

    @Test void timestampIndependentAndDeterministicAcrossTimeZones() throws IOException {
        Path a = tree("a", "old!"), c = tree("c", "new!");
        InstalledVersion base = installed(a, 1);
        UpdateManifest target = manifest(c, null, List.of());
        TimeZone initial = TimeZone.getDefault();
        Path first = temporary.resolve("one.cpdelta"), second = temporary.resolve("two.cpdelta");
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            TreeDeltaEngine.create(a, base, c, target, first);
            Files.setLastModifiedTime(c.resolve("app/change"), FileTime.fromMillis(1));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            TreeDeltaEngine.create(a, base, c, target, second);
        } finally { TimeZone.setDefault(initial); }
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        try (ZipFile zip = new ZipFile(first.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                assertEquals(LocalDateTime.of(1980, 1, 2, 0, 0), entry.getTimeLocal());
                assertNull(entry.getExtra(), "extended timestamp must not depend on the system zone");
            }
        }
        TreeDeltaEngine.verify(c, target.files(), target.treeSha256());
    }

    @Test void emptyManagedTreesRoundTrip() throws IOException {
        Path a = Files.createDirectory(temporary.resolve("a")), c = Files.createDirectory(temporary.resolve("c"));
        write(a, "CashMemory/user", "private");
        InstalledVersion base = installed(a, 1);
        UpdateManifest target = manifest(c, null, List.of());
        Path patch = temporary.resolve("empty.cpdelta");
        TreeDeltaEngine.create(a, base, c, target, patch);
        target = manifest(c, null, List.of(descriptor(base, patch)));
        Path out = temporary.resolve("out");
        TreeDeltaEngine.apply(a, base, patch, target, out);
        assertEquals(List.of(), TreeDeltaEngine.inventory(out));
        assertEquals("private", Files.readString(a.resolve("CashMemory/user")));
        Path full = fullZip(c, List.of(), temporary.resolve("full.zip"));
        TreeDeltaEngine.extractFull(full, manifest(c, full, List.of()), temporary.resolve("full-out"));
    }

    @Test void rejectsWrongBaseContainerFileTreeAndDowngrade() throws IOException {
        Path a = tree("a", "old!"), c = tree("c", "new!");
        InstalledVersion base = installed(a, 1);
        Path patch = temporary.resolve("patch.cpdelta");
        TreeDeltaEngine.create(a, base, c, manifest(c, null, List.of()), patch);
        UpdateManifest target = manifest(c, null, List.of(descriptor(base, patch)));
        Path out = temporary.resolve("out");
        assertThrows(IOException.class, () -> TreeDeltaEngine.apply(a,
                new InstalledVersion(2, base.commitSha(), base.treeSha256()), patch, target, out));
        assertThrows(IOException.class, () -> TreeDeltaEngine.apply(a,
                new InstalledVersion(1, "f".repeat(40), base.treeSha256()), patch, target, out));
        assertThrows(IOException.class, () -> TreeDeltaEngine.apply(a,
                new InstalledVersion(1, base.commitSha(), "f".repeat(64)), patch, target, out));
        assertThrows(IOException.class, () -> TreeDeltaEngine.apply(a,
                new InstalledVersion(3, base.commitSha(), base.treeSha256()), patch, target, out));
        write(a, "app/change", "oops");
        assertThrows(IOException.class, () -> TreeDeltaEngine.apply(a, base, patch, target, out));
        write(a, "app/change", "old!");
        byte[] bytes = Files.readAllBytes(patch); bytes[bytes.length / 2] ^= 1; Files.write(patch, bytes);
        assertThrows(IOException.class, () -> TreeDeltaEngine.apply(a, base, patch, target, out));
        assertFalse(Files.exists(out));
        assertThrows(IOException.class, () -> TreeDeltaEngine.verify(c, target.files(), "0".repeat(64)));
        SafeTree.readOnly(c.resolve("app/stable"), true);
        assertThrows(IOException.class, () -> TreeDeltaEngine.verify(c, target.files(), target.treeSha256()));
    }

    @Test void existingDestinationAndNestedOutputArePreserved() throws IOException {
        Path a = tree("a", "old!"), c = tree("c", "new!");
        InstalledVersion base = installed(a, 1);
        Path patch = temporary.resolve("patch.cpdelta");
        TreeDeltaEngine.create(a, base, c, manifest(c, null, List.of()), patch);
        UpdateManifest target = manifest(c, null, List.of(descriptor(base, patch)));
        Path existing = Files.createDirectory(temporary.resolve("existing"));
        Files.writeString(existing.resolve("keep"), "keep");
        assertThrows(IOException.class, () -> TreeDeltaEngine.apply(a, base, patch, target, existing));
        assertEquals("keep", Files.readString(existing.resolve("keep")));
        assertThrows(IOException.class, () -> TreeDeltaEngine.create(a, base, c, target, a.resolve("new.cpdelta")));
        assertThrows(IOException.class, () -> TreeDeltaEngine.create(a, base, c, target, patch));
        assertThrows(IOException.class, () -> TreeDeltaEngine.apply(a, base, patch, target, a.resolve("nested")));
    }

    @Test void refusesSameAndOlderReleasesAndSameCommitBeforeCreatingOutputs() throws IOException {
        Path baseRoot = Files.createDirectory(temporary.resolve("base"));
        Path targetRoot = Files.createDirectory(temporary.resolve("target"));
        UpdateManifest target = manifest(targetRoot, null, List.of());
        String emptyHash = TreeDeltaEngine.treeHash(List.of());
        Path output = temporary.resolve("patch.cpdelta"), destination = temporary.resolve("out");
        for (int release : List.of(3, 4)) {
            InstalledVersion base = new InstalledVersion(release, "1".repeat(40), emptyHash);
            assertEquals("DOWNGRADE", assertThrows(IOException.class,
                    () -> TreeDeltaEngine.create(baseRoot, base, targetRoot, target, output)).getMessage());
            assertEquals("DOWNGRADE", assertThrows(IOException.class,
                    () -> TreeDeltaEngine.apply(baseRoot, base, output, target, destination)).getMessage());
        }
        InstalledVersion sameCommit = new InstalledVersion(1, target.commitSha(), emptyHash);
        assertEquals("SAME_COMMIT", assertThrows(IOException.class,
                () -> TreeDeltaEngine.create(baseRoot, sameCommit, targetRoot, target, output)).getMessage());
        assertEquals("SAME_COMMIT", assertThrows(IOException.class,
                () -> TreeDeltaEngine.apply(baseRoot, sameCommit, output, target, destination)).getMessage());
        assertFalse(Files.exists(output)); assertFalse(Files.exists(destination));
    }

    private Path tree(String name, String content) throws IOException {
        Path root = Files.createDirectory(temporary.resolve(name));
        write(root, "CashPrediction.exe", "launcher"); write(root, "CashPrediction-Swing.exe", "swing");
        write(root, "CashPrediction-Web.exe", "web"); write(root, "app/change", content);
        write(root, "app/stable", "stable"); write(root, "runtime/bin/java.exe", "java");
        write(root, "CashMemory/user.md", "private"); write(root, "readme.txt", "unmanaged");
        return root;
    }

    private static void write(Path root, String path, String text) throws IOException {
        Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, text);
    }

    private static InstalledVersion installed(Path root, int release) throws IOException {
        return new InstalledVersion(release, Integer.toString(release).repeat(40),
                TreeDeltaEngine.treeHash(TreeDeltaEngine.inventory(root)));
    }

    private static DeltaPatch descriptor(InstalledVersion base, Path patch) throws IOException {
        return new DeltaPatch(base.releaseNumber(), base.commitSha(), base.treeSha256(),
                "CashPrediction.cpdelta", Files.size(patch), SafeTree.hash(patch));
    }

    private static UpdateManifest manifest(Path root, Path archive, List<DeltaPatch> deltas) throws IOException {
        List<FileEntry> files = TreeDeltaEngine.inventory(root);
        return new UpdateManifest(3, "3".repeat(40), "3", Instant.parse("2026-10-03T00:00:00Z"),
                "CashPrediction-portable.zip", archive == null ? 1 : Files.size(archive),
                archive == null ? "a".repeat(64) : SafeTree.hash(archive), TreeDeltaEngine.treeHash(files), files, deltas);
    }

    private static Path fullZip(Path root, List<FileEntry> files, Path archive) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry wrapper = new ZipEntry("CashPrediction/");
            wrapper.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0)); zip.putNextEntry(wrapper); zip.closeEntry();
            for (FileEntry f : files) {
                ZipEntry e = new ZipEntry("CashPrediction/" + f.path());
                e.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0)); zip.putNextEntry(e);
                Files.copy(root.resolve(f.path()), zip); zip.closeEntry();
            }
        }
        return archive;
    }
}
