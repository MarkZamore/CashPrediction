package ru.cashprediction.core.update.tree;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.update.model.*;
import static org.junit.jupiter.api.Assertions.*;

/** Проверки внутреннего контракта дельты при корректном внешнем SHA контейнера. */
class PatchValidationTest {
    @TempDir Path temporary;

    @Test void rejectsForgedPatchMetadataEvenWithMatchingContainerHash() throws IOException {
        Path baseRoot = tree("base", "old"), targetRoot = tree("target", "new");
        InstalledVersion base = new InstalledVersion(1, "1".repeat(40),
                TreeDeltaEngine.treeHash(TreeDeltaEngine.inventory(baseRoot)));
        UpdateManifest target = manifest(targetRoot, List.of());
        Path original = temporary.resolve("original.cpdelta");
        TreeDeltaEngine.create(baseRoot, base, targetRoot, target, original);
        Map<String, byte[]> pristine = read(original);
        String metadata = new String(pristine.get("patch.json"), StandardCharsets.UTF_8);
        int i = 0;
        for (String invalid : List.of(metadata.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                metadata.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
                metadata.replace("cashprediction-tree-delta", "other"),
                metadata.replace("\"algorithmVersion\":1", "\"algorithmVersion\":9"),
                metadata.replace("\"baseReleaseNumber\":1", "\"baseReleaseNumber\":2"),
                metadata.replace(base.commitSha(), "f".repeat(40)),
                metadata.replace(base.treeSha256(), "e".repeat(64)),
                metadata.replace("\"targetReleaseNumber\":3", "\"targetReleaseNumber\":4"),
                metadata.replace(target.commitSha(), "f".repeat(40)),
                metadata.replace(target.treeSha256(), "e".repeat(64)),
                metadata.replace("{", "{\"unknown\":1,"),
                metadata.replace("\"readOnly\":false", "\"readOnly\":true"),
                metadata.replace("\"path\":\"app/change\"", "\"path\":\"app/../escape\""))) {
            Map<String, byte[]> entries = new LinkedHashMap<>(pristine);
            entries.put("patch.json", invalid.getBytes(StandardCharsets.UTF_8));
            Path patch = write("metadata-" + i++ + ".cpdelta", entries);
            UpdateManifest hashedManifest = manifest(targetRoot, List.of(descriptor(base, patch)));
            Path destination = temporary.resolve("out");
            assertThrows(IOException.class, () -> TreeDeltaEngine.apply(baseRoot, base, patch, hashedManifest, destination), invalid);
            assertFalse(Files.exists(destination));
        }
    }

    @Test void rejectsMissingExtraCorruptAndTraversalPayload() throws IOException {
        Path baseRoot = tree("base", "old"), targetRoot = tree("target", "new");
        InstalledVersion base = new InstalledVersion(1, "1".repeat(40),
                TreeDeltaEngine.treeHash(TreeDeltaEngine.inventory(baseRoot)));
        Path original = temporary.resolve("original.cpdelta");
        TreeDeltaEngine.create(baseRoot, base, targetRoot, manifest(targetRoot, List.of()), original);
        Map<String, byte[]> pristine = read(original);
        for (String mode : List.of("missing", "extra", "corrupt", "traversal", "missing-json", "oversized")) {
            Map<String, byte[]> entries = new LinkedHashMap<>(pristine);
            switch (mode) {
                case "missing" -> entries.remove("payload/app/change");
                case "extra" -> entries.put("payload/app/extra", new byte[0]);
                case "corrupt" -> entries.put("payload/app/change", "bad".getBytes(StandardCharsets.UTF_8));
                case "traversal" -> entries.put("payload/../escape", new byte[0]);
                case "missing-json" -> entries.remove("patch.json");
                case "oversized" -> entries.put("payload/app/change", "long".getBytes(StandardCharsets.UTF_8));
                default -> throw new AssertionError();
            }
            Path patch = write(mode + ".cpdelta", entries);
            UpdateManifest hashedManifest = manifest(targetRoot, List.of(descriptor(base, patch)));
            assertThrows(IOException.class, () -> TreeDeltaEngine.apply(baseRoot, base, patch, hashedManifest,
                    temporary.resolve("out")), mode);
            assertFalse(Files.exists(temporary.resolve("out")), mode);
            assertEquals("old", Files.readString(baseRoot.resolve("app/change")));
        }
        assertFalse(Files.exists(temporary.resolve("escape")));
    }

    @Test void rejectsMetadataCrcAndDescriptorMismatchWithRehashedContainer() throws IOException {
        Path baseRoot = tree("base", "old"), targetRoot = tree("target", "new");
        InstalledVersion base = new InstalledVersion(1, "1".repeat(40),
                TreeDeltaEngine.treeHash(TreeDeltaEngine.inventory(baseRoot)));
        Path original = temporary.resolve("original.cpdelta");
        TreeDeltaEngine.create(baseRoot, base, targetRoot, manifest(targetRoot, List.of()), original);
        ZipDirectory.Entry metadata = ZipDirectory.inspect(original).get("patch.json");
        byte[] pristine = Files.readAllBytes(original);
        int descriptorOffset = Math.toIntExact(metadata.dataOffset() + metadata.compressedSize());
        int centralOffset = descriptorOffset + 16;
        // После patch.json ещё есть payload, поэтому центральный каталог ищется
        // по первой записи, а не вычисляется от конца локального metadata.
        while (centralOffset <= pristine.length - 46
                && ByteBuffer.wrap(pristine).order(ByteOrder.LITTLE_ENDIAN).getInt(centralOffset) != 0x02014b50)
            centralOffset++;
        assertTrue(centralOffset <= pristine.length - 46);
        assertEquals(0x08074b50, ByteBuffer.wrap(pristine).order(ByteOrder.LITTLE_ENDIAN).getInt(descriptorOffset));
        for (boolean crc : List.of(false, true)) {
            byte[] bytes = pristine.clone();
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            if (crc) {
                buffer.putInt(centralOffset + 16, 1); buffer.putInt(descriptorOffset + 4, 1);
            } else buffer.putInt(descriptorOffset + 8, 1);
            Path patch = temporary.resolve(crc ? "metadata-crc.cpdelta" : "descriptor.cpdelta");
            Files.write(patch, bytes);
            UpdateManifest target = manifest(targetRoot, List.of(descriptor(base, patch)));
            assertEquals(crc ? "ZIP_CRC_OR_SIZE" : "ZIP_DESCRIPTOR", assertThrows(IOException.class,
                    () -> TreeDeltaEngine.apply(baseRoot, base, patch, target, temporary.resolve("out"))).getMessage());
            assertFalse(Files.exists(temporary.resolve("out")));
            assertEquals("old", Files.readString(baseRoot.resolve("app/change")));
        }
    }

    private Path tree(String name, String content) throws IOException {
        Path p = Files.createDirectory(temporary.resolve(name));
        Files.createDirectory(p.resolve("app")); Files.writeString(p.resolve("app/change"), content);
        return p;
    }

    private static UpdateManifest manifest(Path root, List<DeltaPatch> deltas) throws IOException {
        List<FileEntry> files = TreeDeltaEngine.inventory(root);
        return new UpdateManifest(3, "3".repeat(40), "3", Instant.parse("2026-10-03T00:00:00Z"),
                "CashPrediction-portable.zip", 1, "a".repeat(64), TreeDeltaEngine.treeHash(files), files, deltas);
    }

    private static DeltaPatch descriptor(InstalledVersion base, Path patch) throws IOException {
        return new DeltaPatch(1, base.commitSha(), base.treeSha256(), "CashPrediction.cpdelta",
                Files.size(patch), SafeTree.hash(patch));
    }

    private static Map<String, byte[]> read(Path patch) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(patch.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                try (InputStream in = zip.getInputStream(entry)) { result.put(entry.getName(), in.readAllBytes()); }
            }
        }
        return result;
    }

    private Path write(String name, Map<String, byte[]> entries) throws IOException {
        Path patch = temporary.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(patch), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey())); zip.write(e.getValue()); zip.closeEntry();
            }
        }
        return patch;
    }
}
