package ru.cashprediction.parity.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.ui.dump.AllowedDiffs;
import ru.cashprediction.core.ui.dump.DumpDiff;
import ru.cashprediction.core.ui.json.UiJson;

/** Собирает manifest из настоящих независимых запусков и пишет честный частичный аудит различий. */
public final class AllowanceEvidenceRun {
    private AllowanceEvidenceRun() { }

    /** Аргументы: native root, Web root, allowed JSON, output; необязательный controlled native root. */
    public static void main(String[] args) throws Exception {
        if (args.length != 4 && args.length != 5) throw new IllegalArgumentException("nativeRoot webRoot allowedJson output [controlledRoot]");
        Path nativeRoot = Path.of(args[0]), webRoot = Path.of(args[1]), output = Path.of(args[3]);
        Files.createDirectories(output);
        Path fx = unique(nativeRoot, "allowance-native-fx-"), swing = unique(nativeRoot, "allowance-native-swing-");
        var pairs = new ArrayList<Map<String, Object>>();
        add(pairs, "halt", "halt-header", nativeSource(fx, "halt-question"), webSource(webRoot, "halt-cancel", "halt-question"));
        add(pairs, "snapshot-prepared-web", "snapshot", nativeSource(fx, "snapshot-prepared"),
                webSource(webRoot, "snapshot-prepared", "snapshot-prepared"));
        // Разные реально наблюдавшиеся состояния: это покрытие различия §10 №8, не одинаковых fixture и не native absent.
        add(pairs, "snapshot-native-prepared-vs-web-absent", "snapshot", nativeSource(fx, "snapshot-prepared"),
                webSource(webRoot, "snapshot-absent", "snapshot-absent"));
        if (args.length == 4) {
            add(pairs, "snapshot-prepared-native", "snapshot", nativeSource(fx, "snapshot-prepared"), nativeSource(swing, "snapshot-prepared"));
        } else {
            for (String role : List.of("prepared", "absent")) {
                Path controlled = unique(Path.of(args[4]), "controlled-" + role + "-");
                if (!Files.readString(controlled.resolve("result.txt")).equals("PASS " + role + "\n"))
                    throw new IllegalArgumentException("Controlled probe is not PASS: " + controlled);
                add(pairs, "snapshot-" + role + "-native", "snapshot",
                        controlledSource(controlled, "fx", role), controlledSource(controlled, "swing", role));
            }
        }
        add(pairs, "replace-swing", "replace-file", nativeSource(fx, "replace-file"), nativeSource(swing, "replace-file"));
        add(pairs, "replace-web", "replace-file", nativeSource(fx, "replace-file"), webSource(webRoot, "replace-file", "replace-file"));
        add(pairs, "js-buttons", "uncaught-buttons", nativeSource(fx, "uncaught-reference"), webSource(webRoot, "js-error", "js-error"));
        add(pairs, "narrow", "narrow-toolbar", nativeSource(fx, "narrow-toolbar"), webSource(webRoot, "narrow-toolbar", "narrow-toolbar"));
        for (String kind : List.of("offline", "stopped", "crashed"))
            add(pairs, kind, kind, nativeSource(fx, "prepared-sample"), webSource(webRoot, kind, "screen-" + kind));
        Path manifest = output.resolve("evidence-manifest.json");
        Files.writeString(manifest, UiJson.write(Map.of("pairs", pairs)));
        var observations = AllowanceEvidence.load(manifest);
        var allowed = AllowedDiffs.parse(Files.readString(Path.of(args[2])));
        int before = allowed.unused().size(), rawCount = 0, rejectedCount = 0;
        var results = new ArrayList<Map<String, Object>>();
        for (var pair : observations) {
            var raw = DumpDiff.diff(pair.expected(), pair.actual(), 0);
            var remaining = allowed.filter(pair.client(), raw, pair.expected(), pair.actual());
            remaining = allowed.filter(pair.second(), remaining, pair.actual(), pair.expected());
            rawCount += raw.size(); rejectedCount += remaining.size();
            results.add(Map.of("label", pair.label(), "rawCount", raw.size(), "rejected", remaining,
                    "provenance", pair.provenance()));
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("coverage", "bounded real evidence only; not full matrix PASS");
        report.put("pairs", results); report.put("rawDifferenceCount", rawCount);
        report.put("rejectedDifferenceCount", rejectedCount); report.put("unusedBefore", before);
        report.put("unusedAfter", allowed.unused());
        Files.writeString(output.resolve("evidence-audit.json"), UiJson.write(report));
        var compact = new LinkedHashMap<String, Object>();
        compact.put("coverage", report.get("coverage")); compact.put("manifest", manifest.toAbsolutePath());
        compact.put("rawDifferenceCount", rawCount); compact.put("rejectedDifferenceCount", rejectedCount);
        compact.put("usedEntries", allowed.entries().stream().filter(e -> !allowed.unused().contains(e)).toList());
        compact.put("rejectedPointers", results.stream().flatMap(r -> ((List<?>) r.get("rejected")).stream())
                .map(d -> ((DumpDiff.Difference) d).pointer()).toList());
        compact.put("unusedBefore", before); compact.put("unusedAfterCount", allowed.unused().size());
        Files.writeString(output.resolve("evidence-summary.json"), UiJson.write(compact));
        System.out.println("Evidence pairs=" + observations.size() + " raw=" + rawCount + " rejected=" + rejectedCount
                + " unused=" + before + "->" + allowed.unused().size() + " artifacts=" + output);
        if (rejectedCount != 0) throw new AssertionError("Rejected real evidence; see " + output.resolve("evidence-audit.json"));
    }

    /** Уникальность защищает от случайного смешивания разных попыток одного состояния. */
    private static Path unique(Path root, String prefix) throws Exception {
        try (var paths = Files.list(root)) {
            var selected = paths.filter(Files::isDirectory).filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
            if (selected.size() != 1) throw new IllegalArgumentException("Expected exactly one run: " + prefix);
            return selected.getFirst();
        }
    }

    /** Контекст берётся из завершённого настоящего native запуска. */
    private static Map<String, Object> nativeSource(Path run, String step) throws Exception {
        var context = Json.asObject(JsonParser.parse(Files.readString(run.resolve("reference-context.json"))), "native context");
        if (!"real-native-widget-driver".equals(context.get("observation"))) throw new IllegalArgumentException("Native origin");
        return source(Path.of(Json.requireString(context, "dumps")).resolve(step + ".json"),
                Json.requireString(context, "cashMemory"), Json.requireString(context, "node"));
    }

    /** Полное неподменённое поле читается из успешной real-store second-instance пробы. */
    private static Map<String, Object> controlledSource(Path run, String client, String role) throws Exception {
        var context = Json.asObject(JsonParser.parse(Files.readString(run.resolve(client).resolve("reference-context.json"))), "controlled context");
        String expectedRole = role.equals("prepared") ? "controlled-identical-archive" : "controlled-absent";
        if (!expectedRole.equals(context.get("role")) || !client.equals(context.get("client"))
                || !"DISABLED_SECOND_INSTANCE".equals(context.get("recorder")))
            throw new IllegalArgumentException("Controlled origin: " + run);
        return source(Path.of(Json.requireString(context, "dumps")).resolve("controlled-snapshot.json"),
                Json.requireString(context, "cashMemory"), Json.requireString(context, "node"));
    }

    /** Только PASS-проба предоставляет Web-наблюдение; отсутствующие counters не синтезируются. */
    private static Map<String, Object> webSource(Path root, String probe, String step) throws Exception {
        Path run = unique(root, "allowance-" + probe + "-");
        if (!Files.readString(run.resolve("result.txt")).equals("PASS " + probe + "\n"))
            throw new IllegalArgumentException("Web probe is not PASS: " + run);
        var context = Json.asObject(JsonParser.parse(Files.readString(run.resolve("probe.json"))), "web context");
        return source(run.resolve(step + ".json"), run.resolve("home/CashMemory").toString(), Json.requireString(context, "node"));
    }

    /** SHA закрепляет точные исходные байты, а не пересериализованную проекцию. */
    private static Map<String, Object> source(Path path, String cashMemory, String node) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        return Map.of("path", path.toAbsolutePath().toString(), "sha256", sha, "cashMemory", cashMemory, "node", node);
    }

    /** Не добавляет каких-либо used-флагов или заранее заданных Differences. */
    private static void add(List<Map<String, Object>> pairs, String label, String scope,
                            Map<String, Object> reference, Map<String, Object> actual) {
        pairs.add(Map.of("label", label, "scope", scope, "reference", reference, "actual", actual));
    }
}
