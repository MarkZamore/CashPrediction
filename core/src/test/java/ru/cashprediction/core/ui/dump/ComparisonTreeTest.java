package ru.cashprediction.core.ui.dump;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.json.UiJson;

/** Проверяет адреса именованных списков и сохранение ошибок порядка без изменения протокола. */
class ComparisonTreeTest {
    @Test
    void stableIdsAndOrderAreSeparateAndWireJsonStaysAnArray() {
        UiDump dump = DumpNormalizerTest.dump(new UiDump.Box(0, 0, 100, 100));
        Map<?, ?> menus = (Map<?, ?>) DumpNormalizer.comparisonTree(dump).get("menuBar");
        assertEquals(List.of("b", "a"), menus.get("$order"));
        assertEquals("first", ((Map<?, ?>) menus.get("b")).get("text"));
        assertInstanceOf(List.class, ((Map<?, ?>) UiJson.toTree(dump)).get("menuBar"));
        assertEquals(List.of(), DumpDiff.diff(DumpNormalizer.comparisonTree(dump), DumpNormalizer.comparisonTree(dump), 0));
        assertThrows(UnsupportedOperationException.class, () -> DumpNormalizer.comparisonTree(dump).put("schema", 2));
    }

    @Test
    void editsAreAddressedByIdAndReordersRemainFailures() {
        var original = menus(List.of(menu("a/b~c", "first"), menu("b", "second")));
        var edited = menus(List.of(menu("a/b~c", "changed"), menu("b", "second")));
        assertEquals(List.of("/menuBar/a~1b~0c/text"), pointers(original, edited));
        var reordered = menus(List.of(menu("b", "second"), menu("a/b~c", "first")));
        assertEquals(List.of("/menuBar/$order/0", "/menuBar/$order/1"), pointers(original, reordered));
    }

    @Test
    void insertionsDoNotShiftOtherAddressesButCannotHideReorders() {
        var original = menus(List.of(menu("a", "a"), menu("b", "b")));
        assertEquals(List.of("/menuBar/new"), pointers(original, menus(List.of(menu("new", "new"), menu("a", "a"), menu("b", "b")))));
        assertEquals(List.of("/menuBar/$order/0", "/menuBar/$order/1", "/menuBar/new"),
                pointers(original, menus(List.of(menu("new", "new"), menu("b", "b"), menu("a", "a")))));
    }

    @Test
    void duplicateEmptyAndReservedIdentitiesCannotOverwriteWidgets() {
        for (List<UiDump.MenuItem> list : List.of(List.of(menu("x", "a"), menu("x", "b")),
                List.of(menu("", "a")), List.of(menu("$order", "a")))) {
            assertThrows(IllegalArgumentException.class, () -> DumpNormalizer.comparisonTree(menus(list)));
        }
    }

    @Test
    void nestedListsHaveTypedKeysIncludingEmptyListsAndNullGeometry() {
        var tree = DumpNormalizer.comparisonTree(DumpNormalizerTest.dump(new UiDump.Box(0, 0, 100, 100)));
        assertEquals(List.of(), ((Map<?, ?>) tree.get("alerts")).get("$order"));
        assertEquals(List.of("now"), ((Map<?, ?>) ((Map<?, ?>) tree.get("summary")).get("cards")).get("$order"));
        var table = (Map<?, ?>) tree.get("table");
        assertEquals(List.of("r1"), ((Map<?, ?>) table.get("rows")).get("$order"));
        assertEquals(List.of("add"), ((Map<?, ?>) table.get("placeholderButtons")).get("$order"));
        assertEquals(List.of("column"), table.get("columns"));
        assertNull(tree.get("toolbar"));
    }

    @Test
    void alertsUsePurposeInsteadOfVolatileWindowIdAndDoNotEraseTheId() {
        UiDump.Alert alert = new UiDump.Alert("runtime-1", "about", "INFORMATION", "title", "", 460,
                "header", "content", "", "", false, List.of(new UiDump.Button("ok", "ok", "", true, true, 0)));
        UiDump base = menus(List.of());
        UiDump dump = new UiDump(1, "model", "s", "step", null, List.of(), null, null, null, null,
                List.of(), List.of(), List.of(), List.of(alert), List.of(), List.of(), List.of(), Map.of(), Map.of());
        var alerts = (Map<?, ?>) DumpNormalizer.comparisonTree(dump).get("alerts");
        var about = (Map<?, ?>) alerts.get("about");
        assertEquals("runtime-1", about.get("id"));
        assertEquals(List.of("ok"), ((Map<?, ?>) about.get("buttons")).get("$order"));
        assertEquals(List.of("/alerts/about"), pointers(base, dump));
    }

    @Test
    void normalizationOverloadUsesTheExistingPathAndTimePolicy() {
        UiDump dump = DumpNormalizerTest.dump(new UiDump.Box(1.1, 0, 100, 100));
        Path home = Path.of("target", "normalizer", "CashMemory").toAbsolutePath();
        assertEquals(DumpNormalizer.comparisonTree(DumpNormalizer.normalize(dump, home, "")),
                DumpNormalizer.comparisonTree(dump, home, ""));
    }

    @Test
    void windowFieldsStatusContextPopupsScreensAndToolbarUseTheirDeclaredIdentities() {
        var field = new UiDump.Field("name", "TEXT", "label", "value", "", "", "", true, true, false, List.of());
        var window = new UiDump.Window("window-1", "INPUT", "rename", "title", "header", "", true, "main", 0, null,
                List.of(), List.of(), List.of(field), List.of(), -1, List.of(), "", List.of(), "", "", false);
        var fallback = new UiDump.Window("window-2", "RULE_EDITOR", "", "title", "header", "", false, "main", 0, null,
                List.of(), List.of(), List.of(), List.of(), -1, List.of(), "", List.of(), "", "", false);
        var item = new UiDump.ToolbarItem("tb.add", "Button", "add", "", "", true, false, "text.primary", false, 0, null, List.of(menu("edit.addIncome", "add")));
        var dump = new UiDump(1, "model", "s", "step", null, List.of(), new UiDump.Toolbar(false, List.of(item)), null, null, null,
                List.of(new UiDump.Segment("session", "text", "", "text.primary", true)),
                List.of(new UiDump.ContextMenu("row:r1", List.of(menu("row.edit", "edit")))),
                List.of(window, fallback), List.of(), List.of(new UiDump.Popup("calendar", List.of("date"), null)),
                List.of(new UiDump.Screen("offline", "title", "text", List.of())),
                List.of(new UiDump.ChooserRequest("file", "OPEN", "title", "filter", "folder", "name")), Map.of(), Map.of());
        var tree = DumpNormalizer.comparisonTree(dump);
        var windows = (Map<?, ?>) tree.get("windows");
        assertEquals(List.of("rename", "window-2"), windows.get("$order"));
        assertEquals(List.of("name"), ((Map<?, ?>) ((Map<?, ?>) windows.get("rename")).get("fields")).get("$order"));
        assertEquals(List.of("session"), ((Map<?, ?>) tree.get("status")).get("$order"));
        assertEquals(List.of("row:r1"), ((Map<?, ?>) tree.get("contextMenus")).get("$order"));
        assertEquals(List.of("calendar"), ((Map<?, ?>) tree.get("popups")).get("$order"));
        assertEquals(List.of("offline"), ((Map<?, ?>) tree.get("screens")).get("$order"));
        var toolbar = (Map<?, ?>) tree.get("toolbar");
        var items = (Map<?, ?>) toolbar.get("items");
        assertEquals(List.of("tb.add"), items.get("$order"));
        assertEquals(List.of("edit.addIncome"), ((Map<?, ?>) ((Map<?, ?>) items.get("tb.add")).get("items")).get("$order"));
        assertInstanceOf(List.class, tree.get("chooserRequests"));
        assertNull(((Map<?, ?>) windows.get("rename")).get("bounds"));
    }

    private static List<String> pointers(UiDump left, UiDump right) {
        return DumpDiff.diff(DumpNormalizer.comparisonTree(left), DumpNormalizer.comparisonTree(right), 0)
                .stream().map(DumpDiff.Difference::pointer).toList();
    }

    private static UiDump.MenuItem menu(String id, String text) {
        return new UiDump.MenuItem(id, "Action", text, "", true, false, "", "", "", "", List.of());
    }

    private static UiDump menus(List<UiDump.MenuItem> menus) {
        return new UiDump(1, "model", "s", "step", null, menus, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
    }
}
