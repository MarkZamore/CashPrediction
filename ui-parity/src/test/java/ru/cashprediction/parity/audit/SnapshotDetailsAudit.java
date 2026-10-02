package ru.cashprediction.parity.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.ui.dump.AllowedDiffs;
import ru.cashprediction.core.ui.dump.DumpDiff;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.text.Texts;

/** Объясняет отказ точного desktop №8, не изменяя observations, manifest или общий used-аудит. */
public final class SnapshotDetailsAudit {
    private static final String POINTER = "/alerts/lastSnapshot/details";
    private SnapshotDetailsAudit() { }

    /** Читает native пару из manifest и сохраняет ограниченное объяснение в отдельный файл. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("manifest allowedJson outputJson");
        var pairs = AllowanceEvidence.load(Path.of(args[0]));
        var entries = AllowedDiffs.parse(Files.readString(Path.of(args[1]))).entries().stream()
                .filter(e -> e.number() == 8 && e.pointer().equals(POINTER) && !e.clients().contains("web")).toList();
        if (entries.size() != 1) throw new IllegalArgumentException("Exactly one desktop details rule required");
        var selected = pairs.stream().filter(p -> java.util.Set.of(p.client(), p.second()).equals(java.util.Set.of("fx", "swing")))
                .filter(p -> p.label().equals("snapshot-prepared-native")).toList();
        if (selected.size() != 1) throw new IllegalArgumentException("Exactly one native snapshot pair required");
        var pair = selected.getFirst();
        var differences = DumpDiff.diff(pair.expected(), pair.actual(), 0).stream().filter(d -> d.pointer().equals(POINTER)).toList();
        if (differences.size() != 1) throw new IllegalArgumentException("Expected actual details difference");
        var diff = differences.getFirst();
        var report = new LinkedHashMap<>(inspect(entries.getFirst(), (String) diff.expected(), (String) diff.actual()));
        report.put("manifest", Path.of(args[0]).toAbsolutePath());
        report.put("provenance", pair.provenance());
        Files.writeString(Path.of(args[2]), UiJson.write(report));
        System.out.println("Desktop details permitted=" + report.get("permittedByExactRule")
                + " residualLines=" + report.get("residualLineCount") + " output=" + args[2]);
    }

    /** Проверяет полное исходное поле точным predicate ядра; сокращение строк используется только для объяснения. */
    static Map<String, Object> inspect(AllowedDiffs.Entry entry, String expected, String actual) {
        Object left = Map.of("alerts", Map.of("lastSnapshot", Map.of("details", expected)));
        Object right = Map.of("alerts", Map.of("lastSnapshot", Map.of("details", actual)));
        var differences = DumpDiff.diff(left, right, 0);
        var diagnosticOnly = new AllowedDiffs(List.of(entry));
        var rejected = diagnosticOnly.filter("swing", differences, left, right);
        // Этот текст не передаётся filter: только полные известные headings сокращаются для объяснения.
        String[] a = diagnosticLocations(expected).split("\\R", -1);
        String[] b = diagnosticLocations(actual).split("\\R", -1);
        var residual = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String x = i < a.length ? a[i] : "<missing line>", y = i < b.length ? b[i] : "<missing line>";
            if (!x.equals(y)) residual.add(Map.of("line", i + 1, "expected", bounded(x), "actual", bounded(y)));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("pointer", POINTER); result.put("specSection", entry.specSection());
        result.put("permittedByExactRule", !differences.isEmpty() && rejected.isEmpty());
        result.put("residualLineCount", residual.size()); result.put("firstResidualLines", residual.stream().limit(16).toList());
        result.put("role", "full observed details comparison; fixture origin is recorded separately");
        result.put("coverageMutation", "none; diagnostic AllowedDiffs is separate from matrix instance");
        return result;
    }

    /** Не скрывает похожие пути внутри RAW payload или неполные строки заголовков. */
    private static String diagnosticLocations(String text) {
        var aliases = new LinkedHashMap<String, String>();
        for (String separator : List.of("/", "\\")) for (String client : List.of("fx", "swing")) {
            aliases.put(UiText.get("s2.recovery.registryBlock", Texts.get("session.store.title.registry"),
                    "<node>" + separator + client, "").split("\n", -1)[0], "<registry heading>");
            aliases.put(UiText.get("s2.recovery.fileBlock", Texts.get("session.store.title.xml"),
                    "<CashMemory>" + separator + "session-" + client + ".xml", "").split("\n", -1)[0], "<XML heading>");
        }
        return String.join("\n", text.lines().map(line -> aliases.getOrDefault(line, line)).toList())
                + (text.endsWith("\n") ? "\n" : "");
    }

    /** Полные payload остаются в исходных JSON, а диагностический отчёт не разрастается до их размера. */
    private static String bounded(String value) { return value.length() <= 160 ? value : value.substring(0, 160) + "..."; }
}
