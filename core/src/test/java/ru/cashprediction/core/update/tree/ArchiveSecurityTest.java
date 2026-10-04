package ru.cashprediction.core.update.tree;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.update.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Враждебные реальные ZIP, центральные атрибуты ссылок и небезопасные назначения. */
class ArchiveSecurityTest {
    @TempDir Path temporary;
    private static final byte[] CONTENT = "abc".getBytes(StandardCharsets.UTF_8);
    private static final String CONTENT_HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test void rejectsPathAttacksBeforeDestinationCreation() throws IOException {
        int index = 0;
        for (String name : List.of("/app/a", "CashPrediction/app/../a", "CashPrediction/app//a",
                "CashPrediction/app/a:b", "CashPrediction/app/CON", "CashPrediction/app/a.",
                "CashPrediction/app/a ", "CashPrediction/app\\a", "CashPrediction/app/e\u0301",
                "CashPrediction/CashMemory/a", "Other/app/a", "CashPrediction/other", "CashPrediction/.")) {
            Path zip = archive("bad-" + index++ + ".zip", List.of(name));
            Path out = temporary.resolve("out");
            assertThrows(IOException.class, () -> TreeDeltaEngine.extractFull(zip, manifest(zip, files("app/a")), out), name);
            assertFalse(Files.exists(out), name);
        }
        assertFalse(Files.exists(temporary.resolve("a")));
    }

    @Test void rejectsCaseUnicodeAndDirectoryCollisions() throws IOException {
        int i = 0;
        for (List<String> names : List.of(List.of("CashPrediction/app/a", "CashPrediction/app/A"),
                List.of("CashPrediction/app/\u00df/a", "CashPrediction/app/SS/b"),
                List.of("CashPrediction/app/a", "CashPrediction/app/a/b"),
                List.of("CashPrediction/app/A/a", "CashPrediction/app/a/b"))) {
            Path zip = archive("collision-" + i++ + ".zip", names);
            assertThrows(IOException.class, () -> TreeDeltaEngine.extractFull(zip, manifest(zip, files("app/a")),
                    temporary.resolve("out")));
            assertFalse(Files.exists(temporary.resolve("out")));
        }
    }

    @Test void rejectsUnixSymlinkAndDosReparseBeforeWrites() throws IOException {
        for (long attr : List.of(0120777L << 16, 0x400L, 0060666L << 16)) {
            Path zip = archive("attrs-" + attr + ".zip", List.of("CashPrediction/app/a"));
            byte[] bytes = Files.readAllBytes(zip);
            int central = signature(bytes, 0x02014b50);
            put16(bytes, central + 4, (3 << 8) | 20);
            put32(bytes, central + 38, attr);
            Files.write(zip, bytes);
            assertThrows(IOException.class, () -> TreeDeltaEngine.extractFull(zip, manifest(zip, files("app/a")),
                    temporary.resolve("out")));
            assertFalse(Files.exists(temporary.resolve("out")));
        }
    }

    @Test void rejectsDuplicateEntriesLocalNameMismatchZip64AndMalformedUtf8() throws IOException {
        Path duplicate = archive("duplicate.zip", List.of("CashPrediction/app/a", "CashPrediction/app/b"));
        byte[] bytes = Files.readAllBytes(duplicate);
        // Оба заголовка второй записи меняются на имя первой; ZipOutputStream сам дубль не создаёт.
        byte[] from = "CashPrediction/app/b".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= bytes.length - from.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(bytes, i, i + from.length), from)) bytes[i + from.length - 1] = 'a';
        }
        Files.write(duplicate, bytes);
        assertThrows(IOException.class, () -> TreeDeltaEngine.extractFull(duplicate, manifest(duplicate, files("app/a")),
                temporary.resolve("out")));
        for (String kind : List.of("local-name", "zip64", "utf8", "multidisk", "hidden")) {
            Path zip = archive(kind + ".zip", List.of("CashPrediction/app/a"));
            byte[] raw = Files.readAllBytes(zip);
            int central = signature(raw, 0x02014b50), end = signature(raw, 0x06054b50);
            switch (kind) {
                case "local-name" -> raw[30] = 'X';
                case "zip64" -> put32(raw, central + 24, 0xffffffffL);
                case "utf8" -> raw[central + 46] = (byte) 0xff;
                case "multidisk" -> put16(raw, end + 4, 1);
                case "hidden" -> put32(raw, central + 42, 1);
                default -> throw new AssertionError();
            }
            Files.write(zip, raw);
            assertThrows(IOException.class, () -> TreeDeltaEngine.extractFull(zip, manifest(zip, files("app/a")),
                    temporary.resolve("out")), kind);
            assertFalse(Files.exists(temporary.resolve("out")), kind);
        }
    }

    @Test void rejectsContentHashAndCrcAndCleansOwnedDestination() throws IOException {
        Path zip = archive("hash.zip", List.of("CashPrediction/app/a"));
        List<FileEntry> wrong = List.of(new FileEntry("app/a", 3, "0".repeat(64), false));
        assertThrows(IOException.class, () -> TreeDeltaEngine.extractFull(zip, manifest(zip, wrong), temporary.resolve("out")));
        assertFalse(Files.exists(temporary.resolve("out")));
        Path crcZip = temporary.resolve("crc.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(crcZip))) {
            CRC32 crc = new CRC32(); crc.update(CONTENT);
            ZipEntry e = new ZipEntry("CashPrediction/app/a"); e.setMethod(ZipEntry.STORED);
            e.setSize(3); e.setCompressedSize(3); e.setCrc(crc.getValue());
            out.putNextEntry(e); out.write(CONTENT); out.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(crcZip);
        int central = signature(bytes, 0x02014b50);
        put32(bytes, 14, 1); put32(bytes, central + 16, 1); Files.write(crcZip, bytes);
        assertThrows(IOException.class, () -> TreeDeltaEngine.extractFull(crcZip, manifest(crcZip, files("app/a")),
                temporary.resolve("out")));
        assertFalse(Files.exists(temporary.resolve("out")));
    }

    @Test void rejectsConflictingLocalFieldsEvenWithDataDescriptor() throws IOException {
        int index = 0;
        for (int field : List.of(10, 12, 14, 18, 22)) {
            Path zip = archive("local-field-" + index++ + ".zip", List.of("CashPrediction/app/a"));
            byte[] bytes = Files.readAllBytes(zip);
            assertEquals(8, u16(bytes, 6) & 8, "fixture must use a descriptor");
            bytes[field] ^= 1;
            Files.write(zip, bytes);
            Path out = temporary.resolve("out");
            IOException failure = assertThrows(IOException.class,
                    () -> TreeDeltaEngine.extractFull(zip, manifest(zip, files("app/a")), out));
            assertEquals(field < 14 ? "ZIP_LOCAL_HEADER" : "ZIP_LOCAL_SIZE", failure.getMessage());
            assertFalse(Files.exists(out));
        }
    }

    @Test void acceptsKnownLocalFieldsAlongsideMatchingDescriptor() throws IOException {
        Path zip = archive("known-local.zip", List.of("CashPrediction/app/a"));
        byte[] bytes = Files.readAllBytes(zip);
        int central = signature(bytes, 0x02014b50);
        put32(bytes, 14, u32(bytes, central + 16));
        put32(bytes, 18, u32(bytes, central + 20));
        put32(bytes, 22, u32(bytes, central + 24));
        Files.write(zip, bytes);
        Path out = temporary.resolve("out");
        TreeDeltaEngine.extractFull(zip, manifest(zip, files("app/a")), out);
        assertArrayEquals(CONTENT, Files.readAllBytes(out.resolve("app/a")));
    }

    @Test void rejectsDeflatedCrcEvenWhenBothHeadersAgreeAndContentHashMatches() throws IOException {
        Path zip = archive("deflated-crc.zip", List.of("CashPrediction/app/a"));
        byte[] bytes = Files.readAllBytes(zip);
        int central = signature(bytes, 0x02014b50), descriptor = signature(bytes, 0x08074b50);
        put32(bytes, central + 16, 1);
        put32(bytes, descriptor + 4, 1);
        Files.write(zip, bytes);
        assertEquals("ZIP_CRC_OR_SIZE", assertThrows(IOException.class,
                () -> TreeDeltaEngine.extractFull(zip, manifest(zip, files("app/a")), temporary.resolve("out"))).getMessage());
        assertFalse(Files.exists(temporary.resolve("out")));
    }

    @Test void rejectsStoredSizeAndDeflateOnlyFlagsAtInspection() throws IOException {
        for (String mode : List.of("size", "flags")) {
            Path zip = storedArchive(mode + ".zip", "CashPrediction/app/a", CONTENT);
            byte[] bytes = Files.readAllBytes(zip);
            int central = signature(bytes, 0x02014b50);
            if (mode.equals("size")) {
                put32(bytes, 22, 4); put32(bytes, central + 24, 4);
            } else {
                put16(bytes, 6, u16(bytes, 6) | 2);
                put16(bytes, central + 8, u16(bytes, central + 8) | 2);
            }
            Files.write(zip, bytes);
            assertEquals(mode.equals("size") ? "ZIP_STORED_SIZE" : "ZIP_UNSUPPORTED",
                    assertThrows(IOException.class, () -> ZipDirectory.inspect(zip)).getMessage());
        }
    }

    @Test void acceptsUnsignedDescriptorWhoseCrcEqualsTheSignature() throws IOException {
        // Независимый четырёхбайтовый вектор CRC-32 = 08074b50.
        byte[] content = HexFormat.of().parseHex("ac0a7ad5");
        CRC32 crc = new CRC32(); crc.update(content);
        assertEquals(0x08074b50L, crc.getValue());
        Path zip = storedArchive("unsigned-descriptor.zip", "CashPrediction/app/a", content);
        byte[] original = Files.readAllBytes(zip);
        int central = signature(original, 0x02014b50);
        byte[] bytes = new byte[original.length + 12];
        System.arraycopy(original, 0, bytes, 0, central);
        put32(bytes, central, crc.getValue());
        put32(bytes, central + 4, content.length); put32(bytes, central + 8, content.length);
        System.arraycopy(original, central, bytes, central + 12, original.length - central);
        int movedCentral = central + 12, end = signature(bytes, 0x06054b50);
        put16(bytes, 4, 20); put16(bytes, movedCentral + 6, 20);
        put16(bytes, 6, u16(bytes, 6) | 8); put16(bytes, movedCentral + 8, u16(bytes, movedCentral + 8) | 8);
        put32(bytes, 14, 0); put32(bytes, 18, 0); put32(bytes, 22, 0);
        put32(bytes, end + 16, movedCentral);
        Files.write(zip, bytes);
        Map<String, ZipDirectory.Entry> entries = ZipDirectory.inspect(zip);
        try (ZipFile archive = new ZipFile(zip.toFile());
             InputStream in = ZipDirectory.stream(archive, entries.get("CashPrediction/app/a"))) {
            assertArrayEquals(content, in.readAllBytes());
        }
    }

    @Test void checksAllDirectoryEntriesAndRejectsUnmanagedEmptyDirectories() throws IOException {
        Path zip = temporary.resolve("directories.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String name : List.of("CashPrediction/", "CashPrediction/app/", "CashPrediction/runtime/")) {
                out.putNextEntry(new ZipEntry(name)); out.closeEntry();
            }
        }
        TreeDeltaEngine.extractFull(zip, manifest(zip, List.of()), temporary.resolve("empty"));
        assertEquals(List.of(), TreeDeltaEngine.inventory(temporary.resolve("empty")));
        for (String name : List.of("CashPrediction/CashMemory/", "CashPrediction/other/", "Other/")) {
            Path bad = storedArchive("directory-" + name.hashCode() + ".zip", name, new byte[0]);
            assertThrows(IOException.class, () -> TreeDeltaEngine.extractFull(bad, manifest(bad, List.of()),
                    temporary.resolve("out")));
            assertFalse(Files.exists(temporary.resolve("out")));
        }
        Path crcZip = storedArchive("directory-crc.zip", "CashPrediction/", new byte[0]);
        byte[] bytes = Files.readAllBytes(crcZip);
        put32(bytes, 14, 1); put32(bytes, signature(bytes, 0x02014b50) + 16, 1);
        Files.write(crcZip, bytes);
        assertEquals("ZIP_CRC_OR_SIZE", assertThrows(IOException.class,
                () -> TreeDeltaEngine.extractFull(crcZip, manifest(crcZip, List.of()), temporary.resolve("out"))).getMessage());
        assertFalse(Files.exists(temporary.resolve("out")));
    }

    @Test void rejectsMissingAndAdditionalFullFilesBeforeCreatingDestination() throws IOException {
        Path extra = archive("extra-full.zip", List.of("CashPrediction/app/a", "CashPrediction/runtime/extra"));
        assertEquals("FULL_ENTRIES", assertThrows(IOException.class,
                () -> TreeDeltaEngine.extractFull(extra, manifest(extra, files("app/a")), temporary.resolve("out"))).getMessage());
        Path missing = archive("missing-full.zip", List.of("CashPrediction/app/a"));
        assertEquals("FULL_ENTRIES", assertThrows(IOException.class,
                () -> TreeDeltaEngine.extractFull(missing, manifest(missing, files("app/a", "runtime/b")),
                        temporary.resolve("out"))).getMessage());
        assertFalse(Files.exists(temporary.resolve("out")));
    }

    @Test void rejectsActualUnicodeCollisionsEvenInEmptyManagedDirectories() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("unicode-root"));
        Path app = Files.createDirectory(root.resolve("app"));
        Path sharp = Files.createDirectory(app.resolve("\u00df"));
        Path expanded = Files.createDirectory(app.resolve("SS"));
        assertFalse(Files.isSameFile(sharp, expanded), "fixture must contain distinct physical directories");
        assertEquals("TREE_PATH_COLLISION", assertThrows(IOException.class, () -> TreeDeltaEngine.inventory(root)).getMessage());
    }

    @Test void rejectsAlternateUnicodeNamesAndZip64InEitherExtraHeader() throws IOException {
        int index = 0;
        for (int id : List.of(1, 0x7075)) for (boolean local : List.of(false, true)) {
            Path zip = temporary.resolve("extra-" + index++ + ".zip");
            try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                ZipEntry entry = new ZipEntry("CashPrediction/app/a");
                entry.setExtra(new byte[] {(byte) 0xfe, (byte) 0xca, 0, 0});
                out.putNextEntry(entry); out.write(CONTENT); out.closeEntry();
            }
            byte[] bytes = Files.readAllBytes(zip);
            int central = signature(bytes, 0x02014b50);
            int extraOffset = local ? 30 + u16(bytes, 26) : central + 46 + u16(bytes, central + 28);
            put16(bytes, extraOffset, id); Files.write(zip, bytes);
            assertEquals("ZIP64_OR_EXTRA_NAME", assertThrows(IOException.class,
                    () -> TreeDeltaEngine.extractFull(zip, manifest(zip, files("app/a")), temporary.resolve("out"))).getMessage());
            assertFalse(Files.exists(temporary.resolve("out")));
        }
    }

    @Test void rejectsExistingDestinationAndMissingParent() throws IOException {
        Path zip = archive("full.zip", List.of("CashPrediction/app/a"));
        UpdateManifest target = manifest(zip, files("app/a"));
        Path existing = Files.createDirectory(temporary.resolve("existing"));
        Files.writeString(existing.resolve("keep"), "keep");
        assertThrows(IOException.class, () -> TreeDeltaEngine.extractFull(zip, target, existing));
        assertEquals("keep", Files.readString(existing.resolve("keep")));
        assertThrows(IOException.class, () -> TreeDeltaEngine.extractFull(zip, target, temporary.resolve("missing/out")));
        assertThrows(IOException.class, () -> TreeDeltaEngine.extractFull(zip, target, temporary.resolve("existing/../out")));
        assertFalse(Files.exists(temporary.resolve("out")));
    }

    @Test void refusesSymbolicSourceAndDestinationAncestor() throws IOException {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("keep"), "keep");
        Path link = temporary.resolve("link");
        try { Files.createSymbolicLink(link, outside); }
        catch (IOException | UnsupportedOperationException e) { assumeTrue(false, "SYMLINK_PRIVILEGE_UNAVAILABLE"); return; }
        Path zip = archive("full.zip", List.of("CashPrediction/app/a"));
        assertThrows(IOException.class, () -> TreeDeltaEngine.extractFull(zip, manifest(zip, files("app/a")), link.resolve("out")));
        Path root = Files.createDirectory(temporary.resolve("root"));
        Files.createSymbolicLink(root.resolve("app"), outside);
        assertThrows(IOException.class, () -> TreeDeltaEngine.inventory(root));
        assertFalse(Files.exists(outside.resolve("out")));
        assertEquals("keep", Files.readString(outside.resolve("keep")));
    }

    @Test void refusesActualFileSymlinksAndDanglingLinksWithoutOverwritingTargets() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("root"));
        Files.createDirectory(root.resolve("app"));
        Path outside = Files.writeString(temporary.resolve("outside-file"), "keep");
        Path managedLink = root.resolve("app/link");
        try { Files.createSymbolicLink(managedLink, outside); }
        catch (IOException | UnsupportedOperationException e) { assumeTrue(false, "SYMLINK_PRIVILEGE_UNAVAILABLE"); return; }
        Path zip = archive("full.zip", List.of("CashPrediction/app/a"));
        UpdateManifest target = manifest(zip, files("app/a"));
        Path archiveLink = temporary.resolve("archive-link.zip"), dangling = temporary.resolve("dangling");
        try {
            Files.createSymbolicLink(archiveLink, zip);
            Files.createSymbolicLink(dangling, temporary.resolve("absent"));
            assertEquals("LINK_OR_REPARSE", assertThrows(IOException.class, () -> TreeDeltaEngine.inventory(root)).getMessage());
            assertEquals("LINK_OR_REPARSE", assertThrows(IOException.class,
                    () -> TreeDeltaEngine.extractFull(archiveLink, target, temporary.resolve("out"))).getMessage());
            assertEquals("LINK_OR_REPARSE", assertThrows(IOException.class,
                    () -> TreeDeltaEngine.extractFull(zip, target, managedLink)).getMessage());
            assertEquals("LINK_OR_REPARSE", assertThrows(IOException.class,
                    () -> TreeDeltaEngine.extractFull(zip, target, dangling)).getMessage());
            assertFalse(Files.exists(temporary.resolve("out")));
            assertFalse(Files.exists(temporary.resolve("absent")));
            assertEquals("keep", Files.readString(outside));
            assertArrayEquals(CONTENT, readArchive(zip, "CashPrediction/app/a"));
        } finally {
            Files.deleteIfExists(managedLink); Files.deleteIfExists(archiveLink); Files.deleteIfExists(dangling);
        }
    }

    @Test void rejectsActualOversizedFileWithoutReadingItsContents() throws IOException {
        Path root = Files.createDirectory(temporary.resolve("large-root"));
        Files.createDirectory(root.resolve("app"));
        Path file = root.resolve("app/large");
        try (var channel = Files.newByteChannel(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                StandardOpenOption.SPARSE)) {
            channel.position(UpdateValidation.MAX_FILE); channel.write(ByteBuffer.wrap(new byte[] {1}));
        }
        assertEquals(UpdateValidation.MAX_FILE + 1, Files.size(file));
        assertThrows(IOException.class, () -> TreeDeltaEngine.inventory(root));
    }

    private Path archive(String name, List<String> names) throws IOException {
        Path file = temporary.resolve(name);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file), StandardCharsets.UTF_8)) {
            for (String entry : names) {
                zip.putNextEntry(new ZipEntry(entry)); zip.write(CONTENT); zip.closeEntry();
            }
        }
        return file;
    }

    private Path storedArchive(String name, String entryName, byte[] content) throws IOException {
        Path zip = temporary.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            CRC32 crc = new CRC32(); crc.update(content);
            ZipEntry entry = new ZipEntry(entryName); entry.setMethod(ZipEntry.STORED);
            entry.setSize(content.length); entry.setCompressedSize(content.length); entry.setCrc(crc.getValue());
            out.putNextEntry(entry); out.write(content); out.closeEntry();
        }
        return zip;
    }

    private static byte[] readArchive(Path archive, String name) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile()); InputStream in = zip.getInputStream(zip.getEntry(name))) {
            return in.readAllBytes();
        }
    }

    private static List<FileEntry> files(String... names) {
        return Arrays.stream(names).map(n -> new FileEntry(n, 3, CONTENT_HASH, false)).toList();
    }

    private static UpdateManifest manifest(Path zip, List<FileEntry> files) throws IOException {
        return new UpdateManifest(3, "3".repeat(40), "3", Instant.parse("2026-10-03T00:00:00Z"),
                "CashPrediction-portable.zip", Files.size(zip), SafeTree.hash(zip), TreeDeltaEngine.treeHash(files), files, List.of());
    }

    private static int signature(byte[] bytes, int value) {
        for (int i = 0; i <= bytes.length - 4; i++) {
            if ((bytes[i] & 255) == (value & 255) && (bytes[i + 1] & 255) == ((value >>> 8) & 255)
                    && (bytes[i + 2] & 255) == ((value >>> 16) & 255) && (bytes[i + 3] & 255) == ((value >>> 24) & 255)) return i;
        }
        throw new AssertionError("SIGNATURE_NOT_FOUND");
    }

    private static void put16(byte[] b, int i, int value) { b[i] = (byte) value; b[i + 1] = (byte) (value >>> 8); }
    private static int u16(byte[] b, int i) { return (b[i] & 255) | (b[i + 1] & 255) << 8; }
    private static long u32(byte[] b, int i) { return Integer.toUnsignedLong(u16(b, i) | u16(b, i + 2) << 16); }
    private static void put32(byte[] b, int i, long value) {
        for (int k = 0; k < 4; k++) b[i + k] = (byte) (value >>> (k * 8));
    }
}
