package ru.cashprediction.core.update.model;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверки строгой схемы, неизменяемости и отказа опасным путям. */
class UpdateCodecTest {
    private static final String SHA = "a".repeat(64);

    @Test void roundTripAndDefensiveCopies() throws IOException {
        List<FileEntry> files = new ArrayList<>(List.of(new FileEntry("app/a", 0, SHA, false)));
        List<DeltaPatch> deltas = new ArrayList<>(List.of(new DeltaPatch(1, "b".repeat(40), "c".repeat(64),
                "CashPrediction.cpdelta", 12, SHA)));
        UpdateManifest m = manifest(files, deltas);
        files.clear(); deltas.clear();
        assertEquals(1, m.files().size());
        assertThrows(UnsupportedOperationException.class, () -> m.files().clear());
        assertThrows(UnsupportedOperationException.class, () -> m.deltaPatches().clear());
        String json = UpdateCodec.write(m);
        assertEquals(m, UpdateCodec.read(json));
        assertEquals(json, UpdateCodec.write(UpdateCodec.read(json)));
        assertTrue(json.contains("\"algorithmVersion\":1"));
    }

    @Test void rejectsDuplicateUnknownMissingAndWrongTypes() throws IOException {
        String json = UpdateCodec.write(manifest(List.of(), List.of()));
        for (String bad : List.of(json.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"),
                json.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
                json.replace("\"schemaVersion\":2", "\"schemaVersion\":2.0"),
                json.replace("\"releaseNumber\":2", "\"releaseNumber\":2147483648"),
                json.replace("\"sizeBytes\":12", "\"sizeBytes\":-1"),
                json.replace("\"deltaPatches\":[]", "\"deltaPatches\":null"),
                json.replace("\"version\":\"2\",", ""),
                json.replace("\"version\":\"2\"", "\"version\":false"),
                json.replace("{", "{\"unknown\":1,"), json + "{}"))
            assertThrows(IOException.class, () -> UpdateCodec.read(bad), bad);
    }

    @Test void rejectsAlgorithmsCountsAndDuplicateBases() throws IOException {
        DeltaPatch d = new DeltaPatch(1, "b".repeat(40), "c".repeat(64), "CashPrediction.cpdelta", 12, SHA);
        String json = UpdateCodec.write(manifest(List.of(), List.of(d)));
        assertThrows(IOException.class, () -> UpdateCodec.read(json.replace("cashprediction-tree-delta", "bsdiff")));
        assertThrows(IOException.class, () -> UpdateCodec.read(json.replace("\"algorithmVersion\":1", "\"algorithmVersion\":2")));
        DeltaPatch named = new DeltaPatch(1, d.baseCommitSha(), d.baseTreeSha256(), "CashPrediction.from-1.cpdelta", 12, SHA);
        assertThrows(IOException.class, () -> UpdateCodec.write(manifest(List.of(), List.of(d, named))));
        assertThrows(IOException.class, () -> UpdateCodec.write(manifest(List.of(), List.of(d, d, d))));
        assertThrows(IOException.class, () -> UpdateValidation.delta(new DeltaPatch(1, d.baseCommitSha(),
                d.baseTreeSha256(), "CashPrediction.from-2.cpdelta", 12, SHA)));
    }

    @Test void rejectsUnsafePathsWithoutRepair() {
        for (String path : List.of("", "/app/a", "app//a", "app/./a", "app/../a", "C:/a", "app\\a",
                "app/a:b", "app/a.", "app/a ", "app/CON", "app/CON .txt", "runtime/Lpt1.dll", "app/com¹",
                "app/a\u0000b", "app/a\u007fb", "app/e\u0301", "app/\ud800", "CashMemory/a", "app"))
            assertThrows(IOException.class, () -> UpdateCodec.write(manifest(
                    List.of(new FileEntry(path, 0, SHA, false)), List.of())), path);
    }

    @Test void rejectsDifferentCommitIdentitiesForTheSameBaseRelease() throws IOException {
        DeltaPatch first = new DeltaPatch(1, "b".repeat(40), "c".repeat(64), "CashPrediction.cpdelta", 12, SHA);
        DeltaPatch second = new DeltaPatch(1, "d".repeat(40), "e".repeat(64), "CashPrediction.from-1.cpdelta", 12, SHA);
        assertEquals("DELTA_BASE", assertThrows(IOException.class,
                () -> UpdateCodec.write(manifest(List.of(), List.of(first, second)))).getMessage());
        String one = UpdateCodec.write(manifest(List.of(), List.of(first)));
        String two = UpdateCodec.write(manifest(List.of(), List.of(second)));
        String firstObject = one.substring(one.indexOf("\"deltaPatches\":[") + "\"deltaPatches\":[".length(), one.length() - 2);
        String secondObject = two.substring(two.indexOf("\"deltaPatches\":[") + "\"deltaPatches\":[".length(), two.length() - 2);
        String both = one.substring(0, one.indexOf("\"deltaPatches\":["))
                + "\"deltaPatches\":[" + firstObject + "," + secondObject + "]}";
        assertEquals("DELTA_BASE", assertThrows(IOException.class, () -> UpdateCodec.read(both)).getMessage());
    }

    @Test void acceptsTwoDistinctBaseReleasesEvenWhenTheirTreesMatch() throws IOException {
        DeltaPatch first = new DeltaPatch(1, "b".repeat(40), "c".repeat(64), "CashPrediction.cpdelta", 12, SHA);
        DeltaPatch second = new DeltaPatch(2, "d".repeat(40), first.baseTreeSha256(), "CashPrediction.from-2.cpdelta", 12, SHA);
        UpdateManifest target = new UpdateManifest(3, "a".repeat(40), "3", Instant.parse("2026-10-03T00:00:00Z"),
                "CashPrediction-portable.zip", 12, SHA, SHA, List.of(), List.of(first, second));
        assertEquals(target, UpdateCodec.read(UpdateCodec.write(target)));
    }

    @Test void rejectsNonAsciiUnicodeEscapesButPreservesLiteralBackslashes() throws IOException {
        String json = UpdateCodec.write(manifest(List.of(), List.of()));
        for (String digits : List.of("\uff10\uff10\uff13\uff12", "\u0660\u0660\u0663\u0662", "00\uff26\uff26")) {
            String invalid = json.replace("\"version\":\"2\"", "\"version\":\"\\u" + digits + "\"");
            assertEquals("INVALID_JSON_ESCAPE", assertThrows(IOException.class, () -> UpdateCodec.read(invalid)).getMessage());
        }
        String ascii = json.replace("\"version\":\"2\"", "\"version\":\"\\u0032\"");
        assertEquals("2", UpdateCodec.read(ascii).version());
        String literal = json.replace("\"version\":\"2\"", "\"version\":\"\\\\u\uff10\uff10\uff13\uff12\"");
        assertEquals("\\u\uff10\uff10\uff13\uff12", UpdateCodec.read(literal).version());
    }

    @Test void checksUnicodeDirectoryCollisionsIndependentlyOfInputOrder() {
        for (List<FileEntry> files : List.of(entries("app/\u00df/a", "app/SS/b"),
                entries("app/I/a", "app/\u0131/b"), entries("app/\u03c2/a", "app/\u03c3/b"),
                entries("app/A/a", "app/a/b"))) {
            assertEquals("PATH_COLLISION", assertThrows(IOException.class,
                    () -> UpdateValidation.files(files, false)).getMessage());
        }
    }

    @Test void rejectsUnicodeCaseFileDirectoryAndOrderingCollisions() {
        for (List<FileEntry> files : List.of(entries("app/a", "app/A"), entries("app/a", "app/a/b"),
                entries("app/A/a", "app/a/b"), entries("app/\u00df/a", "app/SS/b"),
                entries("runtime/a", "app/a"), entries("app/a", "app/a")))
            assertThrows(IOException.class, () -> UpdateCodec.write(manifest(files, List.of())));
    }

    @Test void rejectsLimitsInvalidSurrogatesAndBooleanSubstitution() throws IOException {
        assertThrows(IOException.class, () -> UpdateValidation.files(
                List.of(new FileEntry("app/a", UpdateValidation.MAX_FILE + 1, SHA, false)), true));
        List<FileEntry> large = new ArrayList<>();
        for (int i = 0; i < 5; i++) large.add(new FileEntry("app/" + i, UpdateValidation.MAX_FILE, SHA, false));
        assertThrows(IOException.class, () -> UpdateValidation.files(large, true));
        assertThrows(IOException.class, () -> UpdateCodec.parse(" ".repeat(UpdateValidation.MAX_JSON + 1)));
        String json = UpdateCodec.write(manifest(entries("app/a"), List.of()));
        assertThrows(IOException.class, () -> UpdateCodec.read(json.replace("\"readOnly\":false", "\"readOnly\":0")));
        assertThrows(IOException.class, () -> UpdateCodec.read(json.replace("\"path\":\"app/a\"", "\"path\":\"app/\\ud800\"")));
    }

    private static List<FileEntry> entries(String... names) {
        return Arrays.stream(names).map(p -> new FileEntry(p, 0, SHA, false)).toList();
    }

    private static UpdateManifest manifest(List<FileEntry> files, List<DeltaPatch> deltas) {
        return new UpdateManifest(2, "a".repeat(40), "2", Instant.parse("2026-10-03T00:00:00Z"),
                "CashPrediction-portable.zip", 12, SHA, SHA, files, deltas);
    }
}
