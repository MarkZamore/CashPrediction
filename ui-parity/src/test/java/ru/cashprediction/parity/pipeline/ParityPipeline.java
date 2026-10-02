package ru.cashprediction.parity.pipeline;

import java.nio.file.*;
import java.util.*;
import ru.cashprediction.core.ui.dump.*;

/** Сравнивает все шаги с моделями и попарно, сохраняя ошибки инфраструктуры в HTML. */
public final class ParityPipeline {
    /** Сборщик одного сценария; возвращает каталог дампов и контекст нормализации. */
    @FunctionalInterface public interface Collector {
        /** Запускает настоящий либо явно тестовый клиент и собирает завершённый сценарий. */
        Collection collect(String client, String scenario, Path output) throws Exception;
    }
    /** Контекст одного запуска.
     * @param dumps каталог JSON шагов
     * @param cashMemory изолированная папка
     * @param node тестовый узел
     */
    public record Collection(Path dumps, Path cashMemory, String node) { }
    /** Дополнительные реальные наблюдения, собираемые до финального unused-аудита. */
    @FunctionalInterface public interface Evidence {
        /** Возвращает ограниченные сравнения; отсутствие reference должно завершиться ошибкой. */
        List<EvidencePair> collect() throws Exception;
    }
    /** Ограниченное сравнение наблюдений; provenance содержит пути и SHA исходных файлов. */
    public record EvidencePair(String label, String client, String second, Object expected,
                               Object actual, String provenance) { }
    /** Итог сравнения.
     * @param failures непоглощённые расхождения и ошибки
     * @param report HTML отчёт
     */
    public record Result(List<String> failures, Path report) {
        /** @return успешны ли все проверки */
        public boolean ok() { return failures.isEmpty(); }
        /** Падает с указанием сохранённого отчёта. */
        public void requireSuccess() {
            if (!ok()) throw new AssertionError(String.join("\n", failures) + "\nReport: " + report);
        }
    }
    private ParityPipeline() { }

    /** Читает фильтр клиентов, отклоняя неизвестные и повторные значения. */
    public static List<String> clients(String value) {
        List<String> result = csv(value);
        if (result.isEmpty() || !Set.of("fx", "swing", "web").containsAll(result))
            throw new IllegalArgumentException("parity.clients: " + value);
        return result;
    }

    /** Выбирает сценарии по префиксам; опечатка не превращает прогон в пустой успех. */
    public static List<String> scenarios(List<String> available, String value) {
        if (value == null || value.isBlank()) return List.copyOf(available);
        List<String> prefixes = csv(value);
        for (String prefix : prefixes) if (available.stream().noneMatch(s -> s.startsWith(prefix)))
            throw new IllegalArgumentException("Unknown scenario prefix: " + prefix);
        return available.stream().filter(s -> prefixes.stream().anyMatch(s::startsWith)).toList();
    }

    /** Выполняет сравнение и всегда пишет отчёт; полная матрица проверяет также неиспользованные допуски. */
    public static Result run(Path goldens, Path output, List<String> clients, List<String> scenarios,
                             AllowedDiffs allowed, boolean fullMatrix, Collector collector) throws Exception {
        return run(goldens, output, clients, scenarios, allowed, fullMatrix, collector, List::of);
    }

    /** Использует один AllowedDiffs для основной матрицы и дополнительных реальных наблюдений. */
    public static Result run(Path goldens, Path output, List<String> clients, List<String> scenarios,
                             AllowedDiffs allowed, boolean fullMatrix, Collector collector, Evidence evidence) throws Exception {
        if (clients.isEmpty() || scenarios.isEmpty()) throw new IllegalArgumentException("Empty parity matrix");
        clients(String.join(",", clients));
        Files.createDirectories(output);
        List<String> failures = new ArrayList<>();
        StringBuilder html = new StringBuilder("<!doctype html><meta charset=\"utf-8\"><title>UI parity</title>"
                + "<h1>UI parity infrastructure</h1><p>Only widget dumps prove client parity. Synthetic runs test the harness.</p>");
        for (String scenario : scenarios) {
            safeName(scenario);
            Map<String, Map<String, Object>> collected = new LinkedHashMap<>();
            Map<String, Object> expected;
            try { expected = load(goldens.resolve(scenario), "model", scenario, goldens.resolve("model-CashMemory"), ""); }
            catch (Exception e) { fail(failures, html, scenario + " golden: " + e); continue; }
            for (String client : clients) {
                String label = scenario + " / " + client;
                try {
                    Collection run = collector.collect(client, scenario, output.resolve(client).resolve(scenario));
                    Map<String, Object> actual = load(run.dumps(), client, scenario, run.cashMemory(), run.node());
                    collected.put(client, actual);
                    compare(label + " / golden", expected, actual, client, null, 0, allowed, failures, html);
                } catch (Exception e) { fail(failures, html, label + ": " + e); }
            }
            List<String> keys = new ArrayList<>(collected.keySet());
            for (int i = 0; i < keys.size(); i++) for (int j = i + 1; j < keys.size(); j++) {
                String a = keys.get(i), b = keys.get(j);
                compare(scenario + " / " + a + " vs " + b, collected.get(a), collected.get(b),
                        a, b, 4, allowed, failures, html);
            }
        }
        try {
            for (EvidencePair pair : evidence.collect()) {
                html.append("<p>Bounded evidence: ").append(escape(pair.provenance())).append("</p>");
                compare("evidence / " + pair.label(), Map.of("observation", pair.expected()),
                        Map.of("observation", pair.actual()), pair.client(), pair.second(), 0,
                        allowed, failures, html);
            }
        } catch (Exception e) { fail(failures, html, "Evidence collection: " + e); }
        if (fullMatrix) for (AllowedDiffs.Entry entry : allowed.unused())
            fail(failures, html, "Unused allowance: " + entry.specSection() + " " + entry.pointer());
        else {
            html.append("<p>Partial matrix: full unused-allowance audit pending.</p>");
            for (AllowedDiffs.Entry entry : allowed.unused()) html.append("<p>Unexercised: ")
                    .append(escape(entry.specSection() + " " + entry.pointer())).append("</p>");
        }
        html.append("<p>Failures: ").append(failures.size()).append("</p>");
        Path report = output.resolve("report.html");
        Files.writeString(report, html);
        // Отдельный отчёт набора клиентов не перезаписывается параллельным прогоном другого набора.
        Files.writeString(output.resolve("report-" + String.join("-", clients) + ".html"), html);
        return new Result(List.copyOf(failures), report);
    }

    /** Загружает строго идентифицированные шаги, исключая успех при пустом каталоге. */
    private static Map<String, Object> load(Path directory, String client, String scenario, Path home, String node)
            throws Exception {
        Map<String, Object> steps = new TreeMap<>();
        try (var paths = Files.list(directory)) {
            // Точные измерения Shot не являются дополнительной контрольной точкой эталонного паритета.
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")
                    && !p.getFileName().toString().endsWith(".raw.json")).sorted().toList()) {
                String step = path.getFileName().toString().replaceFirst("\\.json$", "");
                UiDump dump = DumpTrees.read(Files.readString(path));
                if (!dump.client().equals(client) || !dump.scenario().equals(scenario) || !dump.step().equals(step))
                    throw new IllegalStateException("Dump identity mismatch: " + path);
                steps.put(step, DumpTrees.normalized(dump, home, node));
            }
        }
        if (steps.isEmpty()) throw new IllegalStateException("No dumps: " + directory);
        return steps;
    }

    /** Сравнивает общие шаги; отсутствие или лишний шаг нельзя поглотить допуском интерфейса. */
    private static void compare(String label, Map<String, Object> expected, Map<String, Object> actual,
                                String client, String second, double tolerance, AllowedDiffs allowed,
                                List<String> failures, StringBuilder html) {
        html.append("<h2>").append(escape(label)).append("</h2>");
        Set<String> steps = new TreeSet<>(expected.keySet()); steps.addAll(actual.keySet());
        for (String step : steps) {
            if (!expected.containsKey(step) || !actual.containsKey(step)) {
                fail(failures, html, label + " / " + step + ": missing or extra dump"); continue;
            }
            // Только эталон модели не содержит измеренных границ. Попарная проверка
            // использует полные исходные деревья, включая все координаты и размеры.
            GoldenMeasurements.Pair trees = second == null
                    ? GoldenMeasurements.project(expected.get(step), actual.get(step))
                    : new GoldenMeasurements.Pair(expected.get(step), actual.get(step));
            List<DumpDiff.Difference> diffs = allowed.filter(client,
                    DumpDiff.diff(trees.expected(), trees.actual(), tolerance), expected.get(step), actual.get(step));
            if (second != null) diffs = allowed.filter(second, diffs, actual.get(step), expected.get(step));
            for (DumpDiff.Difference diff : diffs) fail(failures, html, label + " / " + step + " "
                    + diff.pointer() + " expected=" + diff.expected() + " actual=" + diff.actual());
            if (diffs.isEmpty()) html.append("<p>").append(escape(step)).append(": PASS</p>");
        }
    }

    /** Записывает ошибку с экранированием HTML. */
    private static void fail(List<String> failures, StringBuilder html, String message) {
        failures.add(message); html.append("<pre>").append(escape(message)).append("</pre>");
    }
    /** Экранирует текст дампа, включая введённый пользователем HTML. */
    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    /** Проверяет путь сценария до создания каталогов. */
    private static void safeName(String name) {
        if (!name.matches("[a-zA-Z0-9][a-zA-Z0-9_-]*")) throw new IllegalArgumentException("scenario: " + name);
    }
    /** Разбирает CSV без пустых и повторных элементов. */
    private static List<String> csv(String text) {
        if (text == null) throw new IllegalArgumentException("null selection");
        List<String> values = Arrays.stream(text.split(",", -1)).map(String::strip).toList();
        if (values.contains("") || new HashSet<>(values).size() != values.size())
            throw new IllegalArgumentException("Invalid selection: " + text);
        return values;
    }
}
