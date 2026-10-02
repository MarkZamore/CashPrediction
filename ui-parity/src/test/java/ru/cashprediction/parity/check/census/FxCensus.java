package ru.cashprediction.parity.check.census;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.parity.pipeline.DumpTrees;

/** Строгая перепись экземпляров из UiDump с сохранением сценария и шага каждого доказательства. */
public final class FxCensus {
    /** Канонические имена из таблицы архитектуры §4.1, без параметров обобщённых типов. */
    public static final Set<String> REQUIRED = required();
    private final Map<String, String> names = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> scenarios = new LinkedHashMap<>();
    private final Map<String, String> evidence = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();

    /** Принимает имена настоящих классов установленного OpenJFX для проверки дополнительных счётчиков. */
    public FxCensus(Set<String> toolkitClasses) {
        if (!toolkitClasses.containsAll(REQUIRED)) throw new IllegalArgumentException("OpenJFX misses required classes");
        for (String full : toolkitClasses) {
            if (!full.startsWith("javafx.")) throw new IllegalArgumentException("Unsupported toolkit class: " + full);
            names.put(full, full);
            String simple = full.substring(full.lastIndexOf('.') + 1);
            names.merge(simple, full, (a, b) -> a.equals(b) ? a : "");
        }
    }

    /** Читает только имена классов OpenJFX; наличие класса в jar само по себе не засчитывает экземпляр. */
    public static Set<String> toolkitClasses(List<Path> jars) throws Exception {
        Set<String> classes = new LinkedHashSet<>();
        for (Path path : jars) try (JarFile jar = new JarFile(path.toFile())) {
            jar.stream().map(entry -> entry.getName())
                    .filter(name -> name.startsWith("javafx/") && name.endsWith(".class") && !name.contains("$"))
                    .forEach(name -> classes.add(name.substring(0, name.length() - 6).replace('/', '.')));
        }
        return Set.copyOf(classes);
    }

    /** Загружает ровно шаги dump сценария, проверяя исходный JSON до преобразования в UiDump. */
    public static Map<String, UiDump> readScenario(Path directory, SelfTestScript script) throws Exception {
        Set<String> expected = new LinkedHashSet<>();
        for (var line : script.lines()) if (line.command() instanceof SelfTestCommand.Dump dump) {
            if (!expected.add(dump.step())) throw new IllegalArgumentException("Duplicate dump step: " + dump.step());
        }
        if (expected.isEmpty()) throw new IllegalArgumentException("No dump steps: " + script.name());
        Map<String, UiDump> dumps = new TreeMap<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                String step = path.getFileName().toString().replaceFirst("\\.json$", "");
                String json = Files.readString(path);
                Object tree = JsonParser.parse(json);
                if (!(tree instanceof Map<?, ?> root) || !(root.get("classCensus") instanceof Map<?, ?> census))
                    throw new IllegalArgumentException("Missing classCensus object: " + script.name() + "/" + step);
                for (var entry : census.entrySet()) {
                    if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof Number number))
                        throw new IllegalArgumentException("Unsupported census entry: " + script.name() + "/" + step + " " + entry);
                    int count;
                    try { count = new BigDecimal(number.toString()).intValueExact(); }
                    catch (ArithmeticException failure) {
                        throw new IllegalArgumentException("Unsupported census count: " + script.name() + "/" + step
                                + " " + entry.getKey() + "=" + number, failure);
                    }
                    if (count < 0) throw new IllegalArgumentException("Negative census: " + script.name() + "/" + step + " " + entry);
                }
                UiDump dump = DumpTrees.read(json);
                identity(script.name(), step, dump);
                dumps.put(step, dump);
            }
        }
        if (!dumps.keySet().equals(expected)) throw new IllegalArgumentException("Dump steps mismatch: " + script.name()
                + " expected=" + expected + " actual=" + dumps.keySet());
        return Map.copyOf(dumps);
    }

    /** Добавляет завершённый сценарий; накопительные снимки объединяются максимумом, а не суммой. */
    public void addScenario(String scenario, Map<String, UiDump> dumps) {
        if (scenarios.containsKey(scenario)) throw new IllegalArgumentException("Duplicate scenario: " + scenario);
        if (dumps.isEmpty()) throw new IllegalArgumentException("No dumps: " + scenario);
        Map<String, Integer> maximum = new LinkedHashMap<>();
        Map<String, String> locations = new LinkedHashMap<>();
        for (var entry : new TreeMap<>(dumps).entrySet()) {
            String step = entry.getKey();
            UiDump dump = entry.getValue();
            identity(scenario, step, dump);
            Set<String> seen = new LinkedHashSet<>();
            for (var count : dump.classCensus().entrySet()) {
                String canonical = names.get(count.getKey());
                if (canonical == null || canonical.isEmpty()) throw new IllegalArgumentException("Unsupported class: "
                        + scenario + "/" + step + " " + count.getKey());
                if (!seen.add(canonical)) throw new IllegalArgumentException("Duplicate class alias: " + scenario + "/" + step + " " + canonical);
                if (count.getValue() == null || count.getValue() < 0) throw new IllegalArgumentException("Invalid count: "
                        + scenario + "/" + step + " " + canonical + "=" + count.getValue());
                maximum.merge(canonical, count.getValue(), Math::max);
                if (count.getValue() > 0) locations.putIfAbsent(canonical, scenario + "/" + step + " count=" + count.getValue());
            }
        }
        scenarios.put(scenario, Map.copyOf(maximum));
        locations.forEach(evidence::putIfAbsent);
    }

    /** Запоминает сбой сбора и продолжает сценарии, не превращая сбой в пропуск теста. */
    public void failedScenario(String scenario, String reason) { failures.add(scenario + ": " + reason); }

    /** Строит отчёт с происхождением экземпляров и отсутствующими счётчиками каждого сценария. */
    public String report() {
        StringBuilder text = new StringBuilder("FX instantiated census: ")
                .append(REQUIRED.stream().filter(evidence::containsKey).count()).append("/23\n");
        for (String name : REQUIRED) {
            text.append(name).append(": ");
            if (evidence.containsKey(name)) text.append(evidence.get(name));
            else {
                text.append("NOT INSTANTIATED");
                scenarios.forEach((scenario, counts) -> text.append("; ").append(scenario).append("=")
                        .append(counts.containsKey(name) ? counts.get(name) : "missing"));
            }
            text.append('\n');
        }
        for (String failure : failures) text.append("FAILED scenario ").append(failure).append('\n');
        return text.toString();
    }

    /** Требует все 23 класса с положительными экземплярами и отсутствие ошибок запусков. */
    public void requireComplete(String reportPath) {
        if (!failures.isEmpty() || !evidence.keySet().containsAll(REQUIRED))
            throw new AssertionError(report() + "Report: " + reportPath);
    }

    /** Требует завершение всей заявленной матрицы, даже если часть сценариев уже создала 23 класса. */
    public void requireComplete(Set<String> expectedScenarios, String reportPath) {
        if (!scenarios.keySet().equals(expectedScenarios)) {
            var missing = new TreeSet<>(expectedScenarios); missing.removeAll(scenarios.keySet());
            var extra = new TreeSet<>(scenarios.keySet()); extra.removeAll(expectedScenarios);
            throw new AssertionError("Scenario suite incomplete: missing=" + missing + " extra=" + extra
                    + "\n" + report() + "Report: " + reportPath);
        }
        requireComplete(reportPath);
    }

    /** Проверяет происхождение, сценарий, шаг и версию без подмены метаданных. */
    private static void identity(String scenario, String step, UiDump dump) {
        if (dump.schema() != UiDump.SCHEMA || !dump.client().equals("fx")
                || !dump.scenario().equals(scenario) || !dump.step().equals(step))
            throw new IllegalArgumentException("Dump identity mismatch: " + scenario + "/" + step
                    + " actual=" + dump.client() + "/" + dump.scenario() + "/" + dump.step());
    }

    /** Использует настоящие имена JavaFX: SplitMenuButton, CheckMenuItem и RadioMenuItem. */
    private static Set<String> required() {
        Set<String> result = new LinkedHashSet<>();
        for (String name : List.of("MenuBar", "Menu", "MenuItem", "CheckMenuItem", "RadioMenuItem",
                "SeparatorMenuItem", "CustomMenuItem", "MenuButton", "SplitMenuButton", "PopupControl", "Tooltip",
                "ContextMenu", "Dialog", "DialogPane", "ButtonType", "Alert", "TextInputDialog", "ChoiceDialog"))
            result.add("javafx.scene.control." + name);
        result.add("javafx.scene.input.ContextMenuEvent");
        for (String name : List.of("PopupWindow", "Popup", "FileChooser", "DirectoryChooser")) result.add("javafx.stage." + name);
        return Collections.unmodifiableSet(result);
    }
}
