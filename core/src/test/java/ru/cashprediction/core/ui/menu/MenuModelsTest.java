package ru.cashprediction.core.ui.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.command.HotkeyTable;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.table.ViewStates;

/** Проверяет общую модель строки меню и панели инструментов из спецификации S1. */
class MenuModelsTest {

    @Test
    void menuBarHasSixStableMenusAndEveryActionUsesCatalogText() {
        for (ClientKind client : ClientKind.values()) {
            MenuBarModel bar = MenuModels.menuBar(ViewStates.sample(), client);
            assertEquals(List.of("file", "edit", "view", "tools", "recovery", "help"),
                    bar.menus().stream().map(MenuNode::id).toList(), client.name());
            assertTree(bar.menus(), client, new HashSet<>());
            assertFalse(bar.find("file.new").isEmpty());
            assertFalse(bar.find("view.period.M3").isEmpty());
            assertFalse(bar.find("tools.whatIf").isEmpty());
            assertFalse(bar.find("help.format").isEmpty());
        }
    }

    @Test
    void recoveryStoreIsDesktopPairOrSingleDisabledWebStore() {
        MenuBarModel desktop = MenuModels.menuBar(ViewStates.sample(), ClientKind.FX);
        MenuNode.Radio registry = assertInstanceOf(MenuNode.Radio.class, desktop.find("recovery.store.registry").orElseThrow());
        MenuNode.Radio xml = assertInstanceOf(MenuNode.Radio.class, desktop.find("recovery.store.xml").orElseThrow());
        assertEquals(MenuModels.GROUP_STORE, registry.group());
        assertEquals(MenuModels.GROUP_STORE, xml.group());
        assertTrue(registry.enabled());
        assertTrue(xml.enabled());

        MenuBarModel web = MenuModels.menuBar(ViewStates.sample(), ClientKind.WEB);
        MenuNode.Radio server = assertInstanceOf(MenuNode.Radio.class, web.find("recovery.store.server").orElseThrow());
        assertEquals(MenuModels.GROUP_STORE, server.group());
        assertTrue(server.selected());
        assertFalse(server.enabled());
        assertTrue(web.find("recovery.store.registry").isEmpty());
        assertTrue(web.find("recovery.store.xml").isEmpty());
    }

    @Test
    void toolbarContainsTheSpecifiedControlsAndSharedGroups() {
        ToolbarModel toolbar = MenuModels.toolbar(ViewStates.sample(), ClientKind.SWING);
        List<String> ids = toolbar.items().stream().map(ToolbarNode::id).toList();
        assertTrue(ids.containsAll(List.of("tb.add", "tb.table", "tb.chart", "tb.period", "tb.filter", "tb.save")));

        ToolbarNode.Toggle table = find(toolbar, "tb.table", ToolbarNode.Toggle.class);
        ToolbarNode.Toggle chart = find(toolbar, "tb.chart", ToolbarNode.Toggle.class);
        assertEquals(MenuModels.GROUP_MODE, table.group());
        assertEquals(MenuModels.GROUP_MODE, chart.group());
        assertTrue(table.selected());
        assertFalse(chart.selected());

        ToolbarNode.MenuButton period = find(toolbar, "tb.period", ToolbarNode.MenuButton.class);
        assertTrue(period.items().stream().anyMatch(node -> node.id().equals("view.period.M12")));
    }

    private static void assertTree(List<? extends MenuNode> nodes, ClientKind client, Set<String> ids) {
        boolean previousSeparator = true;
        for (MenuNode node : nodes) {
            assertTrue(ids.add(node.id()), "повторный id: " + node.id());
            assertFalse(previousSeparator && node instanceof MenuNode.Separator, "лишний разделитель: " + node.id());
            previousSeparator = node instanceof MenuNode.Separator;
            if (node instanceof MenuNode.Action action) {
                assertEquals(UiText.get(action.command().menuKey()), action.text(), action.id());
                assertEquals(HotkeyTable.shownAccelerator(action.command(), client).orElse(null), action.accel(), action.id());
            } else if (node instanceof MenuNode.Check check) {
                assertEquals(UiText.get(check.command().menuKey()), check.text(), check.id());
            } else if (node instanceof MenuNode.Radio radio) {
                assertEquals(UiText.get(radio.command().menuKey()), radio.text(), radio.id());
            } else if (node instanceof MenuNode.Submenu submenu) {
                assertNotNull(submenu.text());
                assertTree(submenu.children(), client, ids);
            }
        }
        assertFalse(previousSeparator, "разделитель в конце");
    }

    private static <T extends ToolbarNode> T find(ToolbarModel toolbar, String id, Class<T> type) {
        ToolbarNode node = toolbar.items().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        return type.cast(node);
    }
}
