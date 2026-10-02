package ru.cashprediction.parity.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import ru.cashprediction.core.ui.json.UiJson;

/** Сжимает сохранённый HTML отчёт стенда без пересчёта допусков и без запуска клиентов. */
public final class ReportAudit {
    private ReportAudit() { }

    /** Читает HTML и пишет отдельный JSON-аудит; исходный отчёт не изменяется. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected input HTML and output JSON");
        Path source = Path.of(args[0]).toRealPath(), output = Path.of(args[1]).toAbsolutePath().normalize();
        if (source.equals(output)) throw new IllegalArgumentException("Audit must not replace the report");
        byte[] bytes = Files.readAllBytes(source);
        var audit = new LinkedHashMap<>(extract(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
        audit.put("source", source.toString());
        audit.put("sourceSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        Files.createDirectories(output.getParent());
        Files.writeString(output, UiJson.write(audit) + "\n");
        System.out.println("Audit: " + output + "; failures=" + audit.get("reportedFailures"));
    }

    /** Сохраняет повторные unused-записи и все ошибки; незнакомый формат не считается успехом. */
    public static Map<String, Object> extract(String html) {
        var summary = Pattern.compile("<p>Failures: ([0-9]+)</p>").matcher(html);
        if (!summary.find()) throw new IllegalArgumentException("Missing failure summary");
        int failures = Integer.parseInt(summary.group(1));
        if (summary.find()) throw new IllegalArgumentException("Duplicate failure summary");
        var unused = new ArrayList<Map<String, Object>>();
        var collection = new ArrayList<String>();
        var unclassified = new ArrayList<String>();
        var roots = new TreeMap<String, Integer>();
        var comparisons = new TreeMap<String, Integer>();
        var scenarios = new TreeMap<String, Integer>();
        var headings = new java.util.LinkedHashSet<String>();
        var headingMatcher = Pattern.compile("<h2>(.*?)</h2>", Pattern.DOTALL).matcher(html);
        while (headingMatcher.find()) headings.add(decode(headingMatcher.group(1)));
        var blocks = Pattern.compile("<pre>(.*?)</pre>", Pattern.DOTALL).matcher(html);
        int count = 0, differences = 0;
        while (blocks.find()) {
            count++;
            String text = decode(blocks.group(1));
            var allowance = Pattern.compile("Unused allowance: (§10 №([0-9]+)) (/[\\S]+)").matcher(text);
            var diff = Pattern.compile("^([^ ]+) / (.*?) / ([^ ]+) (/[^ ]*) expected=", Pattern.DOTALL).matcher(text);
            if (allowance.matches()) {
                unused.add(Map.of("number", Integer.parseInt(allowance.group(2)),
                        "specSection", allowance.group(1), "pointer", allowance.group(3)));
            } else if (diff.find()) {
                differences++;
                String root = "/" + diff.group(4).substring(1).split("/", 2)[0];
                roots.merge(root, 1, Integer::sum);
                comparisons.merge(diff.group(2) + " " + root, 1, Integer::sum);
                scenarios.merge(diff.group(1), 1, Integer::sum);
            } else if (text.matches("(?s)^[^ ]+ / [^:]+: .*")) {
                collection.add(text.lines().findFirst().orElse(text));
            } else unclassified.add(text);
        }
        if (count != failures) throw new IllegalArgumentException("Failure block count differs from summary");
        var result = new LinkedHashMap<String, Object>();
        result.put("schema", 1);
        result.put("reportedFailures", failures);
        result.put("comparisonHeadings", headings.size());
        result.put("differenceCount", differences);
        result.put("unusedEntryCount", unused.size());
        result.put("unusedEntries", unused);
        result.put("collectionErrors", collection);
        result.put("unclassifiedErrors", unclassified);
        result.put("rootGroups", roots);
        result.put("comparisonRootGroups", comparisons);
        result.put("scenarioDifferenceCounts", scenarios);
        return java.util.Collections.unmodifiableMap(result);
    }

    /** Обращает только четыре замены, которые выполняет генератор HTML стенда. */
    private static String decode(String text) {
        return text.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }
}
