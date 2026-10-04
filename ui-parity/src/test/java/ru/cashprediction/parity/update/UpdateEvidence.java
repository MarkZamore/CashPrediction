package ru.cashprediction.parity.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.update.model.UpdateCodec;

/** Полный план T17 и строгая проверка полноты; репетиция CLI не является GUI-доказательством. */
public final class UpdateEvidence {
    /** Замороженные клиенты, варианты пути, базы и фазы журнала. */
    public static final List<String> CLIENTS = List.of("fx", "swing", "web");
    /** Идентификаторы трёх путей настоящих портативных копий. */
    public static final List<String> PATHS = List.of("ascii", "cyrillic", "unicode");
    /** Каждая база проверяется независимо. */
    public static final List<String> BASES = List.of("B1", "B2");
    /** Нельзя пропускать ни одну durable фазу при итоговой приёмке. */
    public static final List<String> PHASES = List.of("PREPARED", "WAITING", "BACKING_UP", "INSTALLING", "VERIFYING", "COMMITTED", "ROLLING_BACK");
    /** Сценарии клиентской матрицы, включая отрицательные и конкурирующие запуски. */
    public static final List<String> SCENARIOS = List.of("delta", "corrupt-delta-full", "corrupt-full-retain",
            "cancel-next-session", "offline", "timeout", "malformed", "once-three-attempts",
            "leases-normal-close", "abrupt-ready-restart", "launch-applying-safe-args",
            "two-clients", "three-clients-pid-root-isolation", "journal-fault", "per-move-fault",
            "helper-runtime-death", "locked-rollback", "readonly-rollback", "disk-full-rollback",
            "unicode-payload", "cashmemory", "unmanaged-old-or-new");
    private UpdateEvidence() { }

    /** Создаёт все клетки заранее; отсутствие реализации никогда не получает PASS. */
    public static List<Map<String, Object>> pending() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String scenario : SCENARIOS) for (String base : BASES) for (String client : CLIENTS)
            for (String path : PATHS) for (String phase : phases(scenario)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("scenario", scenario); row.put("base", base); row.put("client", client);
                row.put("path", path); row.put("phase", phase); row.put("status", "PENDING");
                row.put("reason", "PORTABLE_EXECUTION_NOT_IMPLEMENTED"); rows.add(row);
            }
        return rows;
    }

    private static List<String> phases(String scenario) {
        return scenario.equals("journal-fault") || scenario.equals("per-move-fault") ? PHASES : List.of("SESSION");
    }

    private static String key(Map<String, Object> row) throws IOException {
        return String.join("/", UpdateCodec.string(row, "scenario"), UpdateCodec.string(row, "base"),
                UpdateCodec.string(row, "client"), UpdateCodec.string(row, "path"), UpdateCodec.string(row, "phase"));
    }

    /** Отвергает missing, duplicate, skip, failure и неподтверждённый PASS по всей матрице. */
    public static void requireSignoff(List<Map<String, Object>> rows) throws IOException {
        var required = new HashSet<String>();
        for (var row : pending()) required.add(key(row));
        var actual = new HashSet<String>();
        for (var row : rows) {
            String key = key(row);
            if (!required.contains(key) || !actual.add(key)) throw new IOException("EVIDENCE_CELL_IDENTITY");
            if (!"PASS".equals(row.get("status")) || !Boolean.TRUE.equals(row.get("executed"))) throw new IOException("EVIDENCE_PENDING_OR_FAILED");
            for (String field : List.of("exe", "baseCommit", "targetCommit", "currentBefore", "currentAfter",
                    "targetBefore", "targetAfter", "userBefore", "userAfter", "httpTrace", "phaseLog", "command", "startedAt", "finishedAt")) {
                if (UpdateCodec.string(row, field).isBlank()) throw new IOException("EVIDENCE_EMPTY");
            }
            if (!UpdateCodec.string(row, "baseCommit").matches("[0-9a-f]{40}")
                    || !UpdateCodec.string(row, "targetCommit").matches("[0-9a-f]{40}")) throw new IOException("EVIDENCE_COMMIT");
            if (UpdateCodec.number(row, "baseRelease") <= 0 || UpdateCodec.number(row, "targetRelease") <= UpdateCodec.number(row, "baseRelease")
                    || UpdateCodec.number(row, "exitCode") != 0 || UpdateCodec.number(row, "skipped") != 0
                    || UpdateCodec.number(row, "failures") != 0) throw new IOException("EVIDENCE_FAILURE");
            for (Object arg : UpdateCodec.array(row.get("args"))) if (!(arg instanceof String)) throw new IOException("EVIDENCE_ARGS");
            String exe = switch (UpdateCodec.string(row, "client")) {
                case "fx" -> "CashPrediction.exe"; case "swing" -> "CashPrediction-Swing.exe"; case "web" -> "CashPrediction-Web.exe";
                default -> throw new IOException("EVIDENCE_CLIENT");
            };
            Path executable = Path.of(UpdateCodec.string(row, "exe"));
            if (!executable.isAbsolute() || !exe.equals(executable.getFileName().toString()) || !Files.isRegularFile(executable)) throw new IOException("EVIDENCE_EXE");
            FixtureAuthority.noLinks(executable);
            try {
                if (Instant.parse(UpdateCodec.string(row, "finishedAt")).isBefore(Instant.parse(UpdateCodec.string(row, "startedAt")))) throw new IOException("EVIDENCE_TIME_ORDER");
            } catch (java.time.format.DateTimeParseException invalid) { throw new IOException("EVIDENCE_TIME", invalid); }
            var old = UpdateCodec.readFiles(artifact(row, "currentBefore"));
            var current = UpdateCodec.readFiles(artifact(row, "currentAfter"));
            var target = UpdateCodec.readFiles(artifact(row, "targetBefore"));
            if (!target.equals(UpdateCodec.readFiles(artifact(row, "targetAfter")))
                    || (!current.equals(old) && !current.equals(target))) throw new IOException("EVIDENCE_PARTIAL_TREE");
            if (!UpdateCodec.object(artifact(row, "userBefore")).equals(UpdateCodec.object(artifact(row, "userAfter")))) throw new IOException("EVIDENCE_USER_CHANGED");
            UpdateCodec.array(artifact(row, "httpTrace"));
            var phases = UpdateCodec.array(artifact(row, "phaseLog"));
            if (!phases.contains(row.get("phase"))) throw new IOException("EVIDENCE_PHASE_MISSING");
        }
        if (!actual.equals(required)) throw new IOException("EVIDENCE_MISSING_CELL");
    }

    private static Object artifact(Map<String, Object> row, String field) throws IOException {
        Path file = Path.of(UpdateCodec.string(row, field));
        if (!file.isAbsolute() || !Files.isRegularFile(file) || Files.size(file) > 8L * 1024 * 1024) throw new IOException("EVIDENCE_ARTIFACT");
        FixtureAuthority.noLinks(file);
        return UpdateCodec.parse(Files.readString(file));
    }
}
