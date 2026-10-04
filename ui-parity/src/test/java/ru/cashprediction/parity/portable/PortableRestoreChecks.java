package ru.cashprediction.parity.portable;

import java.util.*;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.dump.*;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.text.UiText;
import static ru.cashprediction.parity.portable.PortableExeProcess.require;

/** Проверки восстановления из сохранённого S5 draft portable-parity.patch; политика сравнения не ослаблена. */
final class PortableRestoreChecks {
    private PortableRestoreChecks() { }
    /** Не принимает пустые окна или незаписанное значение за доказательство восстановления. */
    static boolean seeded(SessionSnapshot value) {
        return value.windows().size() == 1 && value.windows().getFirst().fields().containsValue("bad731")
                && value.windows().getFirst().fields().containsValue("450731,00")
                && value.main().view().equals("CHART") && value.main().period().equals("ALL")
                && value.main().whatIfExtra().equals("7319,00")
                && value.main().selectedRowId().equals("r1@2026-10-05")
                && Boolean.TRUE.equals(value.main().filters().get("pastExpanded"))
                && Boolean.TRUE.equals(value.main().filters().get("whatIfIncome"))
                && Boolean.TRUE.equals(value.main().filters().get("whatIfExpense"));
    }

    /** Требует реальную открытую форму и исходный невалидный ввод из виджетов. */
    static void seeded(UiDump dump) {
        // JavaFX: Dialog → Swing: JDialog → Web: dialog
        require(dump.windows().size() == 1 && dump.windows().getFirst().fields().stream()
                .anyMatch(f -> f.id().equals("extraSaving") && f.text().equals("bad731")), "Actual invalid field lost");
        require(dump.table() != null && dump.table().selectedRowId().equals("r1@2026-10-05"),
                "Actual table selection lost");
        require(dump.chart() != null && !dump.chart().xLabels().isEmpty(), "Actual chart absent");
        // JavaFX: MenuItem/CheckMenuItem/CustomMenuItem → Swing: JMenuItem/JCheckBoxMenuItem/JSpinner → Web: menu buttons/input
        Map<String, UiDump.MenuItem> menu = new LinkedHashMap<>();
        flattenMenu(dump.menuBar(), menu);
        require(menu.get("view.period.ALL").checked() && menu.get("whatIf.income").checked()
                && menu.get("whatIf.expense").checked() && menu.get("whatIf.extra").value().equals("7319"),
                "Actual menu sentinel missing");
        require(dump.toolbar().items().stream().filter(item -> item.kind().equals("FilterField"))
                .map(UiDump.ToolbarItem::text).toList().equals(List.of(UiText.get("sample.rule.salary"))),
                "Actual filter text lost");
    }

    /** Читает рекурсивное дерево настоящего меню, отклоняя повторные id. */
    private static void flattenMenu(List<UiDump.MenuItem> items, Map<String, UiDump.MenuItem> output) {
        // JavaFX: MenuItem → Swing: JMenuItem → Web: menu button
        for (var item : items) {
            if (item.id().startsWith("view.period.") || item.id().startsWith("whatIf."))
                require(output.put(item.id(), item) == null, "Duplicate relevant menu id");
            flattenMenu(item.children(), output);
        }
    }

    /** Сверяет persisted состояние точно; допускает только переименование идентификаторов окон. */
    static void snapshotEqual(SessionSnapshot a, SessionSnapshot b) {
        require(a.schemaVersion() == b.schemaVersion() && a.client().equals(b.client()), "Snapshot identity");
        require(a.main().equals(b.main()) && a.plan().equals(b.plan()), "Restored main/plan");
        require(a.windows().size() == b.windows().size() && !a.windows().isEmpty(), "Restored window count");
        Map<String, String> ids = new HashMap<>(); ids.put("main", "main");
        for (int i = 0; i < a.windows().size(); i++) {
            var old = a.windows().get(i); var now = b.windows().get(i);
            require(!ids.containsValue(now.id()), "Non-bijective window mapping");
            ids.put(old.id(), now.id());
        }
        for (int i = 0; i < a.windows().size(); i++) {
            var old = a.windows().get(i); var now = b.windows().get(i);
            require(ids.containsKey(old.ownerId()), "Unknown source owner");
            require(old.withIds(now.id(), ids.get(old.ownerId())).equals(now), "Restored fields/page/owner/bounds");
        }
    }

    /** Сверяет живое содержимое точно; служебное время, сообщения и счётчики команд проверяются отдельно. */
    static void rawEqual(UiDump a, UiDump b) {
        // JavaFX: MenuItem/CheckMenuItem/CustomMenuItem → Swing: JMenuItem/JCheckBoxMenuItem/JSpinner → Web: menu buttons/input
        Map<String, UiDump.MenuItem> leftMenu = new LinkedHashMap<>(), rightMenu = new LinkedHashMap<>();
        flattenMenu(a.menuBar(), leftMenu); flattenMenu(b.menuBar(), rightMenu);
        require(!leftMenu.isEmpty() && leftMenu.equals(rightMenu), "Actual menu values/checks changed");
        require(a.frame().equals(b.frame()) && a.toolbar().equals(b.toolbar()) && a.summary().equals(b.summary())
                && a.table().equals(b.table()) && a.chart().equals(b.chart()), "Actual main presentation changed");
        require(a.windows().size() == b.windows().size() && !a.windows().isEmpty(), "Actual window count");
        // JavaFX: Dialog → Swing: JDialog → Web: dialog
        var old = a.windows().getFirst(); var now = b.windows().getFirst();
        Map<String, Object> left = new LinkedHashMap<>(Json.asObject(UiJson.toTree(old), "window"));
        Map<String, Object> right = new LinkedHashMap<>(Json.asObject(UiJson.toTree(now), "window"));
        left.put("id", "restored-window"); right.put("id", "restored-window");
        require(DumpDiff.diff(left, right, 0).isEmpty(), "Actual form fields/page/owner/bounds changed");
        require(a.alerts().equals(b.alerts()) && a.screens().equals(b.screens()), "Recovery left an alert/screen");
    }

}

