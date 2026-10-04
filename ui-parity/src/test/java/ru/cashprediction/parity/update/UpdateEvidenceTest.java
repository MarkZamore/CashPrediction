package ru.cashprediction.parity.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.update.model.UpdateCodec;
import ru.cashprediction.core.update.model.FileEntry;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет запрет агрегатного успеха, неполной матрицы и пропущенных сбоев. */
final class UpdateEvidenceTest {
    @TempDir Path temp;
    @Test void pendingAndEmptyNeverApprove() {
        assertThrows(IOException.class, () -> UpdateEvidence.requireSignoff(List.of()));
        assertThrows(IOException.class, () -> UpdateEvidence.requireSignoff(UpdateEvidence.pending()));
    }

    @Test void completeFixtureCanPassStructuralValidation() throws Exception {
        // Это фикстура валидатора, не квитанция реального portable запуска.
        UpdateEvidence.requireSignoff(claimed());
    }

    @Test void missingBaseClientPathAndPhaseNeverApprove() throws Exception {
        for (String field : List.of("base", "client", "path", "phase")) {
            var rows = claimed(); rows.removeIf(row -> row.get(field).equals(rows.getFirst().get(field)));
            assertThrows(IOException.class, () -> UpdateEvidence.requireSignoff(rows), field);
        }
    }

    @Test void duplicateUnexpectedFailureAndSkipNeverApprove() throws Exception {
        var duplicate = claimed(); duplicate.add(new LinkedHashMap<>(duplicate.getFirst()));
        assertThrows(IOException.class, () -> UpdateEvidence.requireSignoff(duplicate));
        for (String status : List.of("SKIP", "FAIL", "PENDING", "TIMEOUT")) {
            var rows = claimed(); rows.getFirst().put("status", status);
            assertThrows(IOException.class, () -> UpdateEvidence.requireSignoff(rows));
        }
        for (String field : List.of("skipped", "failures", "exitCode")) {
            var rows = claimed(); rows.getFirst().put(field, 1L);
            assertThrows(IOException.class, () -> UpdateEvidence.requireSignoff(rows));
        }
        var unexpected = claimed(); unexpected.getFirst().put("phase", "NOT_A_PHASE");
        assertThrows(IOException.class, () -> UpdateEvidence.requireSignoff(unexpected));
    }

    @Test void evidenceAndExecutionAreMandatory() throws Exception {
        for (String field : List.of("executed", "baseCommit", "targetAfter", "httpTrace", "phaseLog", "args", "targetRelease")) {
            var rows = claimed(); rows.getFirst().remove(field);
            assertThrows(IOException.class, () -> UpdateEvidence.requireSignoff(rows), field);
        }
    }

    @Test void missingArtifactAndChangedUserAreRejected() throws Exception {
        var rows = claimed(); rows.getFirst().put("httpTrace", temp.resolve("absent.json").toString());
        var missingRows = rows;
        assertThrows(IOException.class, () -> UpdateEvidence.requireSignoff(missingRows));
        rows = claimed();
        Path changed = temp.resolve("changed.json"); Files.writeString(changed, "{\"user.md\":\"modified\"}");
        rows.getFirst().put("userAfter", changed.toString());
        var changedRows = rows;
        assertThrows(IOException.class, () -> UpdateEvidence.requireSignoff(changedRows));
    }

    private List<Map<String, Object>> claimed() throws Exception {
        Path inventory = temp.resolve("tree.json");
        Files.writeString(inventory, JsonWriter.write(UpdateCodec.fileObjects(List.of(new FileEntry("app/data", 0, FixtureAuthority.sha(new byte[0]), false)))));
        Path user = temp.resolve("user.json"); Files.writeString(user, "{\"user.md\":\"original\"}");
        Path http = temp.resolve("http.json"); Files.writeString(http, "[]");
        var phases = new ArrayList<>(UpdateEvidence.PHASES); phases.add("SESSION");
        Path log = temp.resolve("phases.json"); Files.writeString(log, JsonWriter.write(phases));
        for (String exe : List.of("CashPrediction.exe", "CashPrediction-Swing.exe", "CashPrediction-Web.exe")) Files.writeString(temp.resolve(exe), "fixture");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var planned : UpdateEvidence.pending()) {
            var row = new LinkedHashMap<>(planned); row.put("status", "PASS"); row.put("executed", true);
            for (String field : List.of("currentBefore", "currentAfter", "targetBefore", "targetAfter")) row.put(field, inventory.toString());
            row.put("userBefore", user.toString()); row.put("userAfter", user.toString()); row.put("httpTrace", http.toString()); row.put("phaseLog", log.toString());
            row.put("exe", temp.resolve(switch ((String) row.get("client")) { case "fx" -> "CashPrediction.exe"; case "swing" -> "CashPrediction-Swing.exe"; default -> "CashPrediction-Web.exe"; }).toString());
            row.put("command", "fixture"); row.put("startedAt", "2026-10-03T00:00:00Z"); row.put("finishedAt", "2026-10-03T00:00:01Z");
            row.put("baseCommit", "a".repeat(40)); row.put("targetCommit", "b".repeat(40));
            row.put("baseRelease", 1L); row.put("targetRelease", 3L); row.put("exitCode", 0L);
            row.put("skipped", 0L); row.put("failures", 0L); row.put("args", List.of()); rows.add(row);
        }
        return rows;
    }
}
