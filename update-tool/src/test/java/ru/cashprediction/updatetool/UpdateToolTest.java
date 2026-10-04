package ru.cashprediction.updatetool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateManifest;

import static org.junit.jupiter.api.Assertions.*;

/** Fixture-проверки настоящего CLI: две прямые базы, воспроизводимость и отказ повреждённым входам. */
class UpdateToolTest {
    private static final String COMMIT_A = "a".repeat(40);
    private static final String COMMIT_B = "b".repeat(40);
    private static final String COMMIT_C = "c".repeat(40);
    private static final String PUBLISHED = "2026-10-03T00:00:00Z";
    @TempDir Path temporary;

    @Test
    void bothIndependentBasesReconstructTheSameTargetAndKeepUserData() throws Exception {
        Fixture fixture = fixture();
        byte[] userA = Files.readAllBytes(fixture.a().resolve("CashMemory/user.md"));
        byte[] userB = Files.readAllBytes(fixture.b().resolve("CashMemory/user.md"));
        // S7-Prepare назначает основное имя новейшей базе и сохраняет порядок дескрипторов.
        Path patchA = create(fixture, fixture.a(), 11, COMMIT_A, "CashPrediction.from-11.cpdelta");
        Path patchB = create(fixture, fixture.b(), 12, COMMIT_B, "CashPrediction.cpdelta");
        Path descriptorA = descriptor(fixture.a(), 11, COMMIT_A, patchA, "a.json");
        Path descriptorB = descriptor(fixture.b(), 12, COMMIT_B, patchB, "b.json");
        Path manifest = manifest(fixture.c(), fixture.archive(), "final.json", descriptorB, descriptorA);
        UpdateManifest target = UpdateCodec.read(Files.readString(manifest));
        assertEquals(List.of(12, 11), target.deltaPatches().stream().map(delta -> delta.baseReleaseNumber()).toList());
        assertEquals(List.of("CashPrediction.cpdelta", "CashPrediction.from-11.cpdelta"),
                target.deltaPatches().stream().map(delta -> delta.assetName()).toList());
        assertEquals(List.of(COMMIT_B, COMMIT_A), target.deltaPatches().stream().map(delta -> delta.baseCommitSha()).toList());
        assertEquals(13, target.releaseNumber());
        assertEquals(COMMIT_C, target.commitSha());
        assertEquals(Files.size(fixture.archive()), target.sizeBytes());
        assertEquals(hash(Files.readAllBytes(fixture.archive())), target.sha256());
        for (int i = 0; i < 2; i++) {
            Path base = i == 0 ? fixture.a() : fixture.b();
            Path patch = i == 0 ? patchA : patchB;
            String commit = i == 0 ? COMMIT_A : COMMIT_B;
            Path destination = temporary.resolve("new-" + i);
            success("apply", "--base", base.toString(), "--base-release", Integer.toString(11 + i),
                    "--base-commit", commit, "--patch", patch.toString(), "--manifest", manifest.toString(),
                    "--out", destination.toString());
            success("verify", "--root", destination.toString(), "--manifest", manifest.toString());
            assertTreeEquals(fixture.c(), destination);
            assertFalse(Files.exists(destination.resolve("app/obsolete.txt")));
            assertFalse(Files.exists(destination.resolve("CashMemory")));
            assertFalse(Files.exists(destination.resolve("notes.txt")));
        }
        assertArrayEquals(userA, Files.readAllBytes(fixture.a().resolve("CashMemory/user.md")));
        assertArrayEquals(userB, Files.readAllBytes(fixture.b().resolve("CashMemory/user.md")));
        assertEquals("old", Files.readString(fixture.a().resolve("app/same-size.txt")));
        assertEquals("mid", Files.readString(fixture.b().resolve("app/same-size.txt")));
        assertNoScratch();
    }

    @Test
    void repeatedInventoryManifestAndPatchHaveIdenticalBytes() throws Exception {
        Fixture fixture = fixture();
        Path first = temporary.resolve("inventory-1.json");
        Path second = temporary.resolve("inventory-2.json");
        success("inventory", "--root", fixture.c().toString(), "--out", first.toString());
        success("inventory", "--root", fixture.c().toString(), "--out", second.toString());
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        Path manifest2 = manifest(fixture.c(), fixture.archive(), "manifest2.json");
        assertArrayEquals(Files.readAllBytes(fixture.manifest()), Files.readAllBytes(manifest2));
        Path patch1 = create(fixture, fixture.a(), 11, COMMIT_A, "one.cpdelta");
        Files.setLastModifiedTime(fixture.a().resolve("app/same-size.txt"),
                java.nio.file.attribute.FileTime.from(Instant.parse("2020-01-01T00:00:00Z")));
        Files.setLastModifiedTime(fixture.c().resolve("app/same-size.txt"),
                java.nio.file.attribute.FileTime.from(Instant.parse("2025-01-01T00:00:00Z")));
        Path patch2 = create(fixture, fixture.a(), 11, COMMIT_A, "two.cpdelta");
        assertArrayEquals(Files.readAllBytes(patch1), Files.readAllBytes(patch2));
        assertNoScratch();
    }

    @Test
    void inventoryHasOnlyFrozenWireFieldsAndComputedHashes() throws Exception {
        Fixture fixture = fixture();
        Path inventory = temporary.resolve("inventory.json");
        success("inventory", "--root", fixture.c().toString(), "--out", inventory.toString());
        Map<String, Object> object = JsonParser.parseObject(Files.readString(inventory));
        assertEquals(List.of("files", "treeSha256"), new ArrayList<>(object.keySet()));
        List<?> files = (List<?>) object.get("files");
        for (Object value : files) {
            Map<?, ?> file = (Map<?, ?>) value;
            assertEquals(List.of("path", "sizeBytes", "sha256", "readOnly"), new ArrayList<>(file.keySet()));
            assertFalse(file.get("path").toString().startsWith("CashMemory"));
            assertEquals(Files.size(fixture.c().resolve(file.get("path").toString())), file.get("sizeBytes"));
            assertEquals(hash(Files.readAllBytes(fixture.c().resolve(file.get("path").toString()))), file.get("sha256"));
        }
        UpdateManifest target = UpdateCodec.read(Files.readString(fixture.manifest()));
        assertEquals(object.get("treeSha256"), target.treeSha256());
        assertEquals(hash(Files.readAllBytes(fixture.archive())), target.sha256());
        assertEquals(Files.size(fixture.archive()), target.sizeBytes());
    }

    @Test
    void invalidArgumentsHaveExitTwoAndNoStdout() {
        List<String[]> invalid = List.of(new String[0], new String[]{"unknown"},
                new String[]{"inventory", "--root", "x"},
                new String[]{"inventory", "--root", "x", "--out"},
                new String[]{"inventory", "--root", "x", "--out", "y", "--root", "z"},
                new String[]{"verify", "--root", "x", "--manifest", "y", "--extra", "z"},
                manifestArgs("0", COMMIT_C, PUBLISHED), manifestArgs("2147483648", COMMIT_C, PUBLISHED),
                manifestArgs("13", "short", PUBLISHED), manifestArgs("13", COMMIT_C, "yesterday"),
                new String[]{"manifest", "--root", "x", "--archive", "y", "--release", "13", "--commit", COMMIT_C,
                        "--version", "1", "--published-at", PUBLISHED, "--out", "z",
                        "--delta", "a", "--delta", "b", "--delta", "c"});
        for (String[] args : invalid) {
            Result result = run(args);
            assertEquals(2, result.code(), Arrays.toString(args));
            assertEquals("", result.stdout());
            assertTrue(result.stderr().startsWith("UPDATE_TOOL_USAGE"));
        }
        Result help = run("--help");
        assertEquals(0, help.code());
        assertTrue(help.stdout().contains("--delta"));
        assertEquals("", help.stderr());
    }

    @Test
    void refusingExistingOutputAndTreeOverlapPreservesEveryInput() throws Exception {
        Fixture fixture = fixture();
        Path sentinel = temporary.resolve("sentinel.json");
        Files.writeString(sentinel, "keep");
        failure("inventory", "--root", fixture.c().toString(), "--out", sentinel.toString());
        failure("inventory", "--root", fixture.c().toString(), "--out", fixture.c().resolve("app/output.json").toString());
        failure("create", "--base", fixture.a().toString(), "--base-release", "11", "--base-commit", COMMIT_A,
                "--target", fixture.c().toString(), "--manifest", fixture.manifest().toString(),
                "--out", fixture.a().resolve("patch.cpdelta").toString());
        failure("create", "--base", fixture.a().toString(), "--base-release", "11", "--base-commit", COMMIT_A,
                "--target", fixture.c().toString(), "--manifest", fixture.manifest().toString(),
                "--out", fixture.manifest().toString());
        failure("inventory", "--root", fixture.c().toString(), "--out", temporary.resolve("missing/output.json").toString());
        assertEquals("keep", Files.readString(sentinel));
        assertFalse(Files.exists(fixture.c().resolve("app/output.json")));
        assertFalse(Files.exists(fixture.a().resolve("patch.cpdelta")));
        assertNoScratch();
    }

    @Test
    void traversalAdsReservedNamesAndNonNfcOutputAreRejected() throws Exception {
        Fixture fixture = fixture();
        for (String unsafe : List.of("../escape.json", "app/../escape.json", "stream:ads", "NUL.json", "CON",
                "trailing. ", "e\u0301.json", "x/./y", "x\\y", "x//y")) {
            failure("inventory", "--root", fixture.c().toString(), "--out", temporary + "/" + unsafe);
        }
        assertNoScratch();
    }

    @Test
    void wrongBaseAndDamagedContainerCannotPublishDestination() throws Exception {
        Fixture fixture = fixture();
        Path patch = create(fixture, fixture.a(), 11, COMMIT_A, "CashPrediction.cpdelta");
        Path desc = descriptor(fixture.a(), 11, COMMIT_A, patch, "desc.json");
        Path manifest = manifest(fixture.c(), fixture.archive(), "with-delta.json", desc);
        Path output = temporary.resolve("reconstructed");
        failureApply(fixture.b(), 12, COMMIT_B, patch, manifest, output);
        failureApply(fixture.a(), 10, COMMIT_A, patch, manifest, output);
        byte[] bytes = Files.readAllBytes(patch);
        bytes[bytes.length / 2] ^= 1;
        Files.write(patch, bytes);
        failureApply(fixture.a(), 11, COMMIT_A, patch, manifest, output);
        assertFalse(Files.exists(output));
        assertNoScratch();
    }

    @Test
    void correctlyHashedPatchWithTraversalIsStillRejected() throws Exception {
        Fixture fixture = fixture();
        Path good = create(fixture, fixture.a(), 11, COMMIT_A, "good.cpdelta");
        Path bad = temporary.resolve("CashPrediction.cpdelta");
        rewriteZip(good, bad, "payload/../escape.txt", "outside");
        Path desc = descriptor(fixture.a(), 11, COMMIT_A, bad, "desc.json");
        Path manifest = manifest(fixture.c(), fixture.archive(), "with-delta.json", desc);
        Path output = temporary.resolve("reconstructed");
        failureApply(fixture.a(), 11, COMMIT_A, bad, manifest, output);
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(temporary.resolve("escape.txt")));
        assertNoScratch();
    }

    @Test
    void resealedPatchRejectsWrongMetadataAndSameSizePayloadCorruption() throws Exception {
        Fixture fixture = fixture();
        Path good = create(fixture, fixture.a(), 11, COMMIT_A, "good.cpdelta");
        Path bad = temporary.resolve("CashPrediction.cpdelta");
        for (int i = 0; i < 4; i++) {
            final int corruption = i;
            try (ZipInputStream input = new ZipInputStream(Files.newInputStream(good));
                 ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(bad))) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    byte[] content = input.readAllBytes();
                    if (entry.getName().equals("patch.json") && corruption < 3) {
                        String json = new String(content, StandardCharsets.UTF_8);
                        json = switch (corruption) {
                            case 0 -> json.replace("\"schemaVersion\":1", "\"schemaVersion\":99");
                            case 1 -> json.replace("\"algorithmVersion\":1", "\"algorithmVersion\":1,\"algorithmVersion\":1");
                            case 2 -> json.replace(COMMIT_C, COMMIT_B);
                            default -> throw new AssertionError();
                        };
                        content = json.getBytes(StandardCharsets.UTF_8);
                    } else if (entry.getName().equals("payload/app/same-size.txt") && corruption == 3) {
                        content = "bad".getBytes(StandardCharsets.UTF_8);
                    }
                    output.putNextEntry(new ZipEntry(entry.getName()));
                    output.write(content);
                    output.closeEntry();
                }
            }
            Path descriptor = descriptor(fixture.a(), 11, COMMIT_A, bad, "corrupt-" + i + ".json");
            Path manifest = manifest(fixture.c(), fixture.archive(), "corrupt-manifest-" + i + ".json", descriptor);
            Path destination = temporary.resolve("reconstructed-" + i);
            failureApply(fixture.a(), 11, COMMIT_A, bad, manifest, destination);
            assertFalse(Files.exists(destination));
            assertNoScratch();
        }
    }

    @Test
    void metadataOnlyChangeCarriesContentAndRestoresReadOnly() throws Exception {
        Fixture fixture = fixture();
        Path source = fixture.c().resolve("app/shared.txt");
        var dos = Files.getFileAttributeView(source, java.nio.file.attribute.DosFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(dos != null, "DOS attributes required for this fixture");
        Path destination = temporary.resolve("readonly-tree");
        try {
            dos.setReadOnly(true);
            Path initial = manifest(fixture.c(), fixture.archive(), "readonly-initial.json");
            Fixture readOnlyFixture = new Fixture(fixture.a(), fixture.b(), fixture.c(), fixture.archive(), initial);
            Path patch = create(readOnlyFixture, fixture.a(), 11, COMMIT_A, "CashPrediction.cpdelta");
            try (var zip = new java.util.zip.ZipFile(patch.toFile())) {
                assertNotNull(zip.getEntry("payload/app/shared.txt"));
            }
            Path descriptor = descriptor(fixture.a(), 11, COMMIT_A, patch, "readonly-descriptor.json");
            Path target = manifest(fixture.c(), fixture.archive(), "readonly-final.json", descriptor);
            success("apply", "--base", fixture.a().toString(), "--base-release", "11", "--base-commit", COMMIT_A,
                    "--patch", patch.toString(), "--manifest", target.toString(), "--out", destination.toString());
            success("verify", "--root", destination.toString(), "--manifest", target.toString());
            assertTrue(Files.readAttributes(destination.resolve("app/shared.txt"),
                    java.nio.file.attribute.DosFileAttributes.class).isReadOnly());
            assertEquals("shared", Files.readString(destination.resolve("app/shared.txt")));
            assertNoScratch();
        } finally {
            dos.setReadOnly(false);
            if (Files.exists(destination.resolve("app/shared.txt"))) {
                Files.getFileAttributeView(destination.resolve("app/shared.txt"),
                        java.nio.file.attribute.DosFileAttributeView.class).setReadOnly(false);
            }
        }
    }

    @Test
    void manifestRejectsCorruptFullZipAndContentMismatch() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.c().resolve("app/same-size.txt"), "bad");
        Path mismatch = temporary.resolve("mismatch.json");
        failure(manifestCommand(fixture.c(), fixture.archive(), mismatch));
        assertFalse(Files.exists(mismatch));
        Files.write(fixture.archive(), new byte[]{1, 2, 3, 4});
        Path broken = temporary.resolve("broken.json");
        failure(manifestCommand(fixture.c(), fixture.archive(), broken));
        assertFalse(Files.exists(broken));
        assertNoScratch();
    }

    @Test
    void manifestRejectsUnexpectedFullZipEntriesIncludingCashMemory() throws Exception {
        Fixture fixture = fixture();
        Path archiveDirectory = Files.createDirectory(temporary.resolve("malicious"));
        Path bad = archiveDirectory.resolve("CashPrediction-portable.zip");
        for (String entry : List.of("Other/app/x", "CashPrediction/../escape.txt", "CashPrediction/CashMemory/user.md",
                "CashPrediction/app/file:stream", "CashPrediction/app/CON.txt", "CashPrediction/app/SHARED.txt",
                "CashPrediction/app/e\u0301.txt")) {
            Files.deleteIfExists(bad);
            rewriteZip(fixture.archive(), bad, entry, "bad");
            Path output = temporary.resolve("bad-manifest.json");
            failure(manifestCommand(fixture.c(), bad, output));
            assertFalse(Files.exists(output));
            assertNoScratch();
        }
    }

    @Test
    void manifestRejectsUnixSymlinkAttributesAndZip64Markers() throws Exception {
        Fixture fixture = fixture();
        for (int corruption = 0; corruption < 2; corruption++) {
            byte[] bytes = Files.readAllBytes(fixture.archive());
            int central = -1;
            for (int i = 0; i + 46 <= bytes.length; i++) {
                if (bytes[i] == 0x50 && bytes[i + 1] == 0x4b && bytes[i + 2] == 1 && bytes[i + 3] == 2) {
                    central = i;
                    break;
                }
            }
            assertTrue(central >= 0);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (corruption == 0) {
                bytes[central + 5] = 3; // Unix в version made by.
                buffer.putInt(central + 38, 0xa1ff << 16); // S_IFLNK в external attributes.
            } else buffer.putInt(central + 24, -1); // ZIP64 sentinel вместо обычного размера.
            Path folder = Files.createDirectory(temporary.resolve("bad-attributes-" + corruption));
            Path bad = folder.resolve("CashPrediction-portable.zip");
            Files.write(bad, bytes);
            Path output = temporary.resolve("attributes-" + corruption + ".json");
            failure(manifestCommand(fixture.c(), bad, output));
            assertFalse(Files.exists(output));
            assertNoScratch();
        }
    }

    @Test
    void nonForwardReleaseCannotCreateOrApply() throws Exception {
        Fixture fixture = fixture();
        Path output = temporary.resolve("downgrade.cpdelta");
        failure("create", "--base", fixture.a().toString(), "--base-release", "13", "--base-commit", COMMIT_A,
                "--target", fixture.c().toString(), "--manifest", fixture.manifest().toString(), "--out", output.toString());
        assertFalse(Files.exists(output));
        Path patch = create(fixture, fixture.a(), 11, COMMIT_A, "CashPrediction.cpdelta");
        Path descriptor = descriptor(fixture.a(), 11, COMMIT_A, patch, "descriptor.json");
        Path manifest = manifest(fixture.c(), fixture.archive(), "with-delta.json", descriptor);
        Path destination = temporary.resolve("downgrade-tree");
        failureApply(fixture.a(), 14, COMMIT_A, patch, manifest, destination);
        assertFalse(Files.exists(destination));
        assertNoScratch();
    }

    @Test
    void descriptorDuplicateKeysUnknownAlgorithmAndInvalidBaseFail() throws Exception {
        Fixture fixture = fixture();
        Path patch = create(fixture, fixture.a(), 11, COMMIT_A, "CashPrediction.cpdelta");
        Path good = descriptor(fixture.a(), 11, COMMIT_A, patch, "desc.json");
        String json = Files.readString(good);
        List<String> corruptions = new ArrayList<>(List.of(json.replace("cashprediction-tree-delta", "unknown"),
                json.replace("\"algorithmVersion\":1", "\"algorithmVersion\":2"),
                json.replace("\"algorithmVersion\":1", "\"algorithmVersion\":1,\"algorithmVersion\":1"),
                json.replace("\"baseReleaseNumber\":11", "\"baseReleaseNumber\":13"),
                json.replace("\"baseCommitSha\":\"" + COMMIT_A, "\"baseCommitSha\":\"" + COMMIT_C)));
        Map<String, Object> fields = JsonParser.parseObject(json);
        for (String field : List.of("algorithmVersion", "baseReleaseNumber", "sizeBytes")) {
            String token = "\"" + field + "\":" + fields.get(field);
            // Масштаб ноль исчезает при повторной записи BigDecimal, хотя исходный тип не Long.
            corruptions.add(json.replace(token, token + "e0"));
            corruptions.add(json.replace(token, token + ".0"));
        }
        for (String corrupted : corruptions) {
            Path bad = temporary.resolve("bad-descriptor.json");
            Files.writeString(bad, corrupted);
            List<String> command = new ArrayList<>(List.of(manifestCommand(fixture.c(), fixture.archive(),
                    temporary.resolve("bad-manifest.json"))));
            command.addAll(List.of("--delta", bad.toString()));
            failure(command.toArray(String[]::new));
            assertFalse(Files.exists(temporary.resolve("bad-manifest.json")));
            assertEquals(json, Files.readString(good));
            assertNoScratch();
        }
        assertNoScratch();
    }

    @Test
    void verifyDetectsSameSizeContentChangeAndSchemaTampering() throws Exception {
        Fixture fixture = fixture();
        Files.writeString(fixture.c().resolve("app/same-size.txt"), "bad");
        failure("verify", "--root", fixture.c().toString(), "--manifest", fixture.manifest().toString());
        Path bad = temporary.resolve("bad.json");
        Files.writeString(bad, Files.readString(fixture.manifest()).replace("\"schemaVersion\":2", "\"schemaVersion\":999"));
        failure("verify", "--root", fixture.c().toString(), "--manifest", bad.toString());
        Files.write(bad, new byte[]{(byte) 0xc3, (byte) 0x28});
        failure("verify", "--root", fixture.c().toString(), "--manifest", bad.toString());
    }

    private Fixture fixture() throws Exception {
        Path a = tree("base-a", "old", true);
        Path b = tree("base-b", "mid", true);
        Path c = tree("target-c", "new", false);
        Path archive = temporary.resolve("CashPrediction-portable.zip");
        fullZip(c, archive);
        Path manifest = manifest(c, archive, "initial.json");
        return new Fixture(a, b, c, archive, manifest);
    }

    private Path tree(String name, String changed, boolean obsolete) throws IOException {
        Path root = Files.createDirectory(temporary.resolve(name));
        for (String exe : List.of("CashPrediction.exe", "CashPrediction-Swing.exe", "CashPrediction-Web.exe")) {
            Files.writeString(root.resolve(exe), exe);
        }
        Files.createDirectories(root.resolve("app"));
        Files.createDirectories(root.resolve("runtime/bin"));
        Files.createDirectories(root.resolve("CashMemory"));
        Files.writeString(root.resolve("app/same-size.txt"), changed);
        Files.writeString(root.resolve("app/shared.txt"), "shared");
        Files.writeString(root.resolve("runtime/bin/java.exe"), "runtime-" + changed);
        Files.writeString(root.resolve("CashMemory/user.md"), "user-data-" + name);
        Files.writeString(root.resolve("notes.txt"), "personal");
        if (obsolete) Files.writeString(root.resolve("app/obsolete.txt"), "remove");
        else {
            Files.write(root.resolve("app/empty.txt"), new byte[0]);
            Files.writeString(root.resolve("app/\u00e9.txt"), "added");
        }
        return root;
    }

    private static List<Path> managed(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).filter(path -> {
                String relative = root.relativize(path).toString().replace('\\', '/');
                return relative.endsWith(".exe") || relative.startsWith("app/") || relative.startsWith("runtime/");
            }).sorted().toList();
        }
    }

    private static void fullZip(Path root, Path archive) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Path path : managed(root)) {
                ZipEntry entry = new ZipEntry("CashPrediction/" + root.relativize(path).toString().replace('\\', '/'));
                entry.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
                zip.putNextEntry(entry);
                Files.copy(path, zip);
                zip.closeEntry();
            }
        }
    }

    private static void rewriteZip(Path source, Path destination, String addedName, String content) throws IOException {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(source));
             ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(destination))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                output.putNextEntry(new ZipEntry(entry.getName()));
                input.transferTo(output);
                output.closeEntry();
            }
            output.putNextEntry(new ZipEntry(addedName));
            output.write(content.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private Path create(Fixture fixture, Path base, int release, String commit, String outputName) {
        Path output = temporary.resolve(outputName);
        success("create", "--base", base.toString(), "--base-release", Integer.toString(release), "--base-commit", commit,
                "--target", fixture.c().toString(), "--manifest", fixture.manifest().toString(), "--out", output.toString());
        return output;
    }

    private Path descriptor(Path base, int release, String commit, Path patch, String name) throws Exception {
        Path inventory = temporary.resolve(name + ".inventory");
        success("inventory", "--root", base.toString(), "--out", inventory.toString());
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("algorithm", "cashprediction-tree-delta");
        map.put("algorithmVersion", 1);
        map.put("baseReleaseNumber", release);
        map.put("baseCommitSha", commit);
        map.put("baseTreeSha256", JsonParser.parseObject(Files.readString(inventory)).get("treeSha256"));
        map.put("assetName", patch.getFileName().toString());
        map.put("sizeBytes", Files.size(patch));
        map.put("sha256", hash(Files.readAllBytes(patch)));
        Path output = temporary.resolve(name);
        Files.writeString(output, JsonWriter.write(map));
        return output;
    }

    private Path manifest(Path root, Path archive, String name, Path... descriptors) {
        Path output = temporary.resolve(name);
        List<String> args = new ArrayList<>(List.of(manifestCommand(root, archive, output)));
        for (Path descriptor : descriptors) args.addAll(List.of("--delta", descriptor.toString()));
        success(args.toArray(String[]::new));
        return output;
    }

    private static String[] manifestCommand(Path root, Path archive, Path out) {
        return new String[]{"manifest", "--root", root.toString(), "--archive", archive.toString(),
                "--release", "13", "--commit", COMMIT_C, "--version", "1.0.0", "--published-at", PUBLISHED,
                "--out", out.toString()};
    }

    private static String[] manifestArgs(String release, String commit, String instant) {
        return new String[]{"manifest", "--root", "x", "--archive", "y", "--release", release,
                "--commit", commit, "--version", "1", "--published-at", instant, "--out", "z"};
    }

    private static void failureApply(Path base, int release, String commit, Path patch, Path manifest, Path output) {
        failure("apply", "--base", base.toString(), "--base-release", Integer.toString(release), "--base-commit", commit,
                "--patch", patch.toString(), "--manifest", manifest.toString(), "--out", output.toString());
    }

    private static void assertTreeEquals(Path expected, Path actual) throws IOException {
        List<String> expectedPaths = managed(expected).stream().map(expected::relativize).map(Path::toString).toList();
        List<String> actualPaths = managed(actual).stream().map(actual::relativize).map(Path::toString).toList();
        assertEquals(expectedPaths, actualPaths);
        for (String path : expectedPaths) assertArrayEquals(Files.readAllBytes(expected.resolve(path)), Files.readAllBytes(actual.resolve(path)), path);
    }

    private void assertNoScratch() throws IOException {
        try (var paths = Files.list(temporary)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".cp-tool-")));
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void success(String... args) {
        Result result = run(args);
        assertEquals(0, result.code(), result.stderr() + " " + Arrays.toString(args));
        assertEquals("", result.stdout());
        assertEquals("", result.stderr());
    }

    private static void failure(String... args) {
        Result result = run(args);
        assertEquals(1, result.code(), Arrays.toString(args));
        assertEquals("", result.stdout());
        assertTrue(result.stderr().startsWith("UPDATE_TOOL_FAILED"));
        assertTrue(result.stderr().chars().allMatch(c -> c < 128));
    }

    private static Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (PrintStream stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            int code = UpdateTool.run(args, stdout, stderr);
            return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        }
    }

    /** Три независимые идентичности деревьев и полный пакет целевой версии. */
    private record Fixture(Path a, Path b, Path c, Path archive, Path manifest) { }

    /** Наблюдаемые результаты того же маршрута, который использует main. */
    private record Result(int code, String stdout, String stderr) { }
}
