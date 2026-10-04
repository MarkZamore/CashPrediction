package ru.cashprediction.parity.portable;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.ui.dump.*;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.parity.pipeline.DumpTrees;
import static ru.cashprediction.parity.portable.PortableExeProcess.require;

/** Отрицательные actual-UI фикстуры из сохранённого S5 draft, без GUI и реестра. */
class PortableRestoreChecksTest {
    /** Каждый проверяемый сигнал восстановления должен отклонять свою мутацию. */
    @Test
    void actualRestoreMutationsAreRejected() throws Exception {
        Path goldens = Path.of(System.getProperty("parity.reactor.root", ".."), "core/src/test/resources/ui-golden");
        String SCENARIO = "s02-sample-table";
        UiDump golden = DumpTrees.read(Files.readString(goldens.resolve(SCENARIO + "/table.json")));
        // JavaFX: Dialog → Swing: JDialog → Web: dialog
        var window = new UiDump.Window("fixture", "GOAL_CALCULATOR", "goal", "", "", "", false, "main", 0,
                new UiDump.Box(0, 0, 500, 400), List.of(), List.of(),
                List.of(new UiDump.Field("extraSaving", "TEXT", "", "bad731", "", "", "", true, true, false, List.of())), List.of(), -1,
                List.of(), "", List.of(), "", "", false);
        Map<String, Object> tree = new LinkedHashMap<>(Json.asObject(UiJson.toTree(golden), "fixture"));
        tree.put("windows", UiJson.toTree(List.of(window)));
        UiDump baseline = DumpTrees.read(UiJson.write(tree));
        PortableRestoreChecks.rawEqual(baseline, baseline);
        for (String id : List.of("view.period.ALL", "whatIf.income", "whatIf.expense")) {
            reject(() -> PortableRestoreChecks.rawEqual(baseline, mutated(baseline, "menuBar", id, "checked", true)));
        }
        reject(() -> PortableRestoreChecks.rawEqual(baseline, mutated(baseline, "menuBar", "whatIf.extra", "value", "7319")));
        reject(() -> PortableRestoreChecks.rawEqual(baseline, mutated(baseline, "toolbar", "tb.filter", "text", "changed")));
        reject(() -> PortableRestoreChecks.rawEqual(baseline, mutated(baseline, "windows", "fixture", "page", 1)));
        reject(() -> PortableRestoreChecks.rawEqual(baseline, mutated(baseline, "windows", "fixture", "ownerId", "other")));
        reject(() -> PortableRestoreChecks.rawEqual(baseline, mutated(baseline, "windows", "extraSaving", "text", "bad732")));
        reject(() -> PortableRestoreChecks.rawEqual(baseline, mutated(baseline, "windows", "fixture", "bounds", Map.of("x", 1, "y", 0, "width", 500, "height", 400))));
    }

    /** Меняет один настоящий узел дампа для отрицательной проверки rawEqual. */
    private static UiDump mutated(UiDump input, String section, String id, String key, Object value) {
        Map<String, Object> tree = new LinkedHashMap<>(Json.asObject(UiJson.toTree(input), "mutation"));
        boolean[] found = {false};
        tree.put(section, mutateTree(tree.get(section), id, key, value, found));
        require(found[0], "Fixture id missing: " + id);
        return DumpTrees.read(UiJson.write(tree));
    }

    /** Создаёт независимое дерево, изменяя настоящий узел нужной части дампа. */
    private static Object mutateTree(Object input, String id, String key, Object value, boolean[] found) {
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> output = new LinkedHashMap<>();
            map.forEach((k, v) -> output.put((String) k, mutateTree(v, id, key, value, found)));
            if (id.equals(map.get("id"))) { output.put(key, value); found[0] = true; }
            return output;
        }
        if (input instanceof List<?> list) return list.stream().map(v -> mutateTree(v, id, key, value, found)).toList();
        return input;
    }

    /** Требует отказ отрицательной фикстуры, сохраняя ошибку неожиданного успеха. */
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Mutation accepted");
    }

}

