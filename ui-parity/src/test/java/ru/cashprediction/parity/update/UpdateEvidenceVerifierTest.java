package ru.cashprediction.parity.update;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.update.model.FileEntry;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.UpdateValidation;

/** Focused проверки CLI/envelope; synthetic positive не является доказательством native запуска. */
final class UpdateEvidenceVerifierTest {
    @TempDir Path temp;

    /** Один вызов embedding API с сохранёнными stdout/stderr без глобальной подмены System. */
    private record Result(int exit, String stdout, String stderr) { }

    private Result call(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        try (var stdout = new PrintStream(out, true, StandardCharsets.UTF_8);
             var stderr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            int exit = UpdateEvidenceVerifier.run(args, stdout, stderr);
            return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        }
    }

    private Path json(String content) throws IOException {
        Path directory = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
        return Files.writeString(directory.resolve("results.json"), content, StandardCharsets.UTF_8);
    }

    private Path report(List<Map<String, Object>> rows) throws IOException {
        return json(JsonWriter.write(Map.of("schemaVersion", 1L, "status", "PASS", "cells", rows)));
    }

    private void rejected(Path path) {
        Result result = call(path.toString());
        assertEquals(1, result.exit());
        assertEquals("", result.stdout());
        assertTrue(result.stderr().matches("UPDATE_EVIDENCE_REJECTED code=[A-Z0-9_]+\\R"), result.stderr());
        assertTrue(result.stderr().length() <= 128);
        assertFalse(result.stderr().contains(path.toString()));
    }

    @Test void exactCompleteSyntheticReportDelegatesAndNeverMutatesBytes() throws Exception {
        Path path = report(claimed());
        byte[] before = Files.readAllBytes(path);
        Result result = call(path.toString());
        assertEquals(0, result.exit());
        assertEquals("UPDATE_EVIDENCE_VERIFIED cells=" + UpdateEvidence.pending().size() + System.lineSeparator(), result.stdout());
        assertEquals("", result.stderr());
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test void argumentsMustBeSingleAbsoluteResultsPath() throws Exception {
        assertEquals(2, call().exit());
        assertEquals(2, call("one", "two").exit());
        assertEquals(1, call("results.json").exit());
        rejected(temp.resolve("missing").resolve("results.json"));
        Path wrongName = Files.writeString(temp.resolve("other.json"), "{}");
        rejected(wrongName);
        Path directory = Files.createDirectory(temp.resolve("results.json"));
        rejected(directory);
    }

    @Test void envelopeFieldsSchemaAndStatusAreStrict() throws Exception {
        for (String input : List.of("[]", "null", "{}",
                "{\"schemaVersion\":1,\"status\":\"PASS\"}",
                "{\"schemaVersion\":1,\"status\":\"PASS\",\"cells\":[],\"extra\":true}",
                "{\"schemaVersion\":1,\"status\":\"PASS\",\"cells\":{}}")) rejected(json(input));
        for (String schema : List.of("0", "2", "-1", "true", "\"1\"", "1.0", "1e0", "null")) {
            rejected(json("{\"schemaVersion\":" + schema + ",\"status\":\"PASS\",\"cells\":[]}"));
        }
        for (String status : List.of("PENDING", "FAIL", "UNKNOWN", "SKIP", "pass", "")) {
            rejected(json(JsonWriter.write(Map.of("schemaVersion", 1L, "status", status, "cells", List.of()))));
        }
        rejected(json("{\"schemaVersion\":1,\"status\":true,\"cells\":[]}"));
    }

    @Test void pendingAggregateCannotBePromotedEvenWhenAllCellsClaimPass() throws Exception {
        var root = new LinkedHashMap<String, Object>();
        root.put("schemaVersion", 1L); root.put("status", "PENDING"); root.put("cells", claimed());
        Path path = json(JsonWriter.write(root));
        byte[] before = Files.readAllBytes(path);
        rejected(path);
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test void malformedDuplicateKeysAndInvalidUtf8AreRejectedWithoutEcho() throws Exception {
        for (String text : List.of("{", "{\"schemaVersion\":1,\"schemaVersion\":1,\"status\":\"PASS\",\"cells\":[]}",
                "{\"schemaVersion\":1,\"status\":\"PASS\",\"cells\":[]} trailing",
                "\ufeff{\"schemaVersion\":1,\"status\":\"PASS\",\"cells\":[]}")) rejected(json(text));
        Path encoding = json(""); Files.write(encoding, new byte[]{(byte) 0xc3, 0x28}); rejected(encoding);
        Result secret = call(json("secret-user-payload\r\nFORGED_PASS").toString());
        assertFalse(secret.stderr().contains("secret-user-payload"));
        assertFalse(secret.stderr().contains("FORGED_PASS"));
    }

    @Test void oversizedResultsAreRejectedBeforeParser() throws Exception {
        Path path = json(" ".repeat(UpdateValidation.MAX_JSON + 1));
        rejected(path);
    }

    @Test void emptyIncompleteDuplicateAndExtraCanonicalClaimsFailAuthority() throws Exception {
        rejected(report(List.of()));
        var incomplete = claimed(); incomplete.removeLast(); rejected(report(incomplete));
        var duplicate = claimed(); duplicate.add(new LinkedHashMap<>(duplicate.getFirst())); rejected(report(duplicate));
        var extra = claimed(); var foreign = new LinkedHashMap<>(extra.getFirst());
        foreign.put("scenario", "NOT_CANONICAL"); extra.add(foreign); rejected(report(extra));
        var unknown = claimed(); unknown.getFirst().put("phase", "NOT_CANONICAL"); rejected(report(unknown));
        rejected(json("{\"schemaVersion\":1,\"status\":\"PASS\",\"cells\":[true]}"));
    }

    @Test void cellPendingExecutionAndFailureClaimsStayRejected() throws Exception {
        for (String status : List.of("PENDING", "FAIL", "SKIP", "UNKNOWN")) {
            var rows = claimed(); rows.getFirst().put("status", status); rejected(report(rows));
        }
        for (Object executed : List.of(false, "true", 1L)) {
            var rows = claimed(); rows.getFirst().put("executed", executed); rejected(report(rows));
        }
        for (String field : List.of("exitCode", "skipped", "failures")) {
            var rows = claimed(); rows.getFirst().put(field, 1L); rejected(report(rows));
        }
    }

    @Test void authoritativeArtifactChecksAreNotBypassed() throws Exception {
        for (String field : List.of("baseCommit", "targetRelease", "command", "currentAfter", "httpTrace", "phaseLog")) {
            var rows = claimed(); rows.getFirst().remove(field); rejected(report(rows));
        }
        var missing = claimed(); missing.getFirst().put("userAfter", temp.resolve("missing-user.json").toString()); rejected(report(missing));
        var user = claimed(); user.getFirst().put("userAfter", json("{\"private-user-value\":\"changed\"}").toString()); rejected(report(user));
        var tree = claimed(); tree.getFirst().put("currentAfter", json(JsonWriter.write(UpdateCodec.fileObjects(List.of(
                new FileEntry("app/foreign", 0L, "c".repeat(64), false))))).toString()); rejected(report(tree));
        var phases = claimed(); phases.getFirst().put("phaseLog", json("[]").toString()); rejected(report(phases));
        var exe = claimed(); exe.getFirst().put("exe", temp.resolve("java.exe").toString()); rejected(report(exe));
        var numeric = claimed(); numeric.getFirst().put("exitCode", "0"); rejected(report(numeric));
    }

    @Test void realConsoleCliExitsNonzeroAndDoesNotPrintPayload() throws Exception {
        Path path = json("{\"schemaVersion\":1,\"status\":\"PRIVATE_UNKNOWN\",\"cells\":[]}");
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        Path out = temp.resolve("cli.stdout.txt"), err = temp.resolve("cli.stderr.txt");
        String classpath = Path.of(UpdateEvidenceVerifier.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator
                + Path.of(UpdateCodec.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        // Только собственная console JVM валидатора, не native launcher/GUI и не shared target.
        Process process = new ProcessBuilder(java.toString(), "-XX:-UsePerfData", "-cp", classpath,
                UpdateEvidenceVerifier.class.getName(), path.toString()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(1, process.exitValue());
            assertEquals("", Files.readString(out));
            assertEquals("UPDATE_EVIDENCE_REJECTED code=RESULTS_STATUS" + System.lineSeparator(), Files.readString(err));
            assertFalse(Files.readString(err).contains("PRIVATE_UNKNOWN"));
        } finally {
            if (process.isAlive()) {process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS));}
        }
    }

    @Test void realConsoleCliAcceptsOnlyCompleteSyntheticAuthorityFixture() throws Exception {
        Path path = report(claimed());
        byte[] before = Files.readAllBytes(path);
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        String classpath = Path.of(UpdateEvidenceVerifier.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + File.pathSeparator + Path.of(UpdateCodec.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path out = temp.resolve("accepted.stdout.txt"), err = temp.resolve("accepted.stderr.txt");
        // Успех structural fixture проверяет entrypoint; реальные продукты не запускаются.
        Process process = new ProcessBuilder(java.toString(), "-XX:-UsePerfData", "-cp", classpath,
                UpdateEvidenceVerifier.class.getName(), path.toString()).redirectOutput(out.toFile()).redirectError(err.toFile()).start();
        try {
            assertTrue(process.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals("UPDATE_EVIDENCE_VERIFIED cells=" + UpdateEvidence.pending().size() + System.lineSeparator(), Files.readString(out));
            assertEquals("", Files.readString(err));
            assertArrayEquals(before, Files.readAllBytes(path));
        } finally {
            if (process.isAlive()) {process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS));}
        }
    }

    private List<Map<String, Object>> claimed() throws Exception {
        Path directory = Files.createDirectory(temp.resolve(UUID.randomUUID().toString()));
        Path inventory = Files.writeString(directory.resolve("tree.json"), JsonWriter.write(UpdateCodec.fileObjects(List.of(
                new FileEntry("app/data", 0L, FixtureAuthority.sha(new byte[0]), false)))));
        Path user = Files.writeString(directory.resolve("user.json"), "{\"user.md\":\"original\"}");
        Path http = Files.writeString(directory.resolve("http.json"), "[]");
        var phases = new ArrayList<>(UpdateEvidence.PHASES); phases.add("SESSION");
        Path phaseLog = Files.writeString(directory.resolve("phases.json"), JsonWriter.write(phases));
        Path command = Files.writeString(directory.resolve("command.json"), "{\"fixture\":\"MOCK_VALIDATOR_ONLY_NO_NATIVE_RUN\"}");
        for (String exe : List.of("CashPrediction.exe", "CashPrediction-Swing.exe", "CashPrediction-Web.exe")) {
            Files.writeString(directory.resolve(exe), "MOCK_VALIDATOR_ONLY_NO_NATIVE_RUN");
        }
        var rows = new ArrayList<Map<String, Object>>();
        for (var planned : UpdateEvidence.pending()) {
            var row = new LinkedHashMap<>(planned); row.put("status", "PASS"); row.put("executed", true);
            for (String field : List.of("currentBefore", "currentAfter", "targetBefore", "targetAfter")) row.put(field, inventory.toString());
            row.put("userBefore", user.toString()); row.put("userAfter", user.toString());
            row.put("httpTrace", http.toString()); row.put("phaseLog", phaseLog.toString()); row.put("command", command.toString());
            row.put("exe", directory.resolve(switch ((String) row.get("client")) {
                case "fx" -> "CashPrediction.exe"; case "swing" -> "CashPrediction-Swing.exe"; default -> "CashPrediction-Web.exe";
            }).toString());
            row.put("startedAt", "2026-10-04T00:00:00Z"); row.put("finishedAt", "2026-10-04T00:00:01Z");
            row.put("baseCommit", "a".repeat(40)); row.put("targetCommit", "b".repeat(40));
            row.put("baseRelease", 1L); row.put("targetRelease", 3L); row.put("exitCode", 0L);
            row.put("skipped", 0L); row.put("failures", 0L); row.put("args", List.of()); rows.add(row);
        }
        return rows;
    }
}
