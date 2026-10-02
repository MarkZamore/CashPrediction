package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.view.table.LazyTableModel;

/** Регрессии реальных невидимых каркасов: минимум контента, одинаковые кнопки, header и фильтр. */
class SwingResidualLayoutTest {
    @TempDir Path directory;

    @Test void alertDetailsStartAtFirstCharacterRatherThanLastScrolledLine() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            var port = port();
            // JavaFX: Alert → Swing: SwingAlerts → Web: dialog
            var alert = new SwingAlerts(port, "a", AlertCatalog.hotkeys("first\n" + "long line ".repeat(100)), null, id -> { });
            try {
                assertEquals(0, alert.details.getCaretPosition());
                alert.details.setCaretPosition(alert.details.getDocument().getLength());
                alert.updateAlert(AlertCatalog.hotkeys("replacement\n" + "next ".repeat(100)));
                assertEquals(0, alert.details.getCaretPosition());
                assertTrue(alert.details.getText().startsWith("replacement"));
            } finally { alert.close(); port.exit(ExitKind.CLEAN, 0); }
        });
    }

    @Test void realViewportKeepsNearestSelectionButHonorsExplicitScrollToTop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var port = port(); var app = new AppController(port, port.environment);
            port.bind((ru.cashprediction.core.app.UiIntents) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ru.cashprediction.core.app.UiIntents.class}, (proxy, method, args) -> null));
            try {
                var initial = app.state(); var plan = SamplePlan.create(initial.today());
                var document = new DocumentView(plan, null, true, false, "", false, "",
                        ru.cashprediction.core.forecast.ForecastEngine.forecast(plan, ru.cashprediction.core.forecast.WhatIf.NONE, initial.today(), true), "", List.of());
                var state = new AppState(initial.revision(), initial.profile(), initial.today(), initial.cashMemory(), initial.plansFolder(), document,
                        initial.view(), "", initial.pastExpanded(), initial.settings(), initial.recorder(), initial.stores(), initial.windows(), initial.status(), "");
                var table = new SwingTable(port); var model = LazyTableModel.build(state, 1);
                table.render(model); table.setSize(1200, 500); layout(table);
                table.reveal(model.row(20).rowId(), RevealMode.SCROLL_TO_TOP);
                assertEquals(table.table.getCellRect(20, 0, true).y, table.scroll.getViewport().getViewPosition().y);
                assertEquals(-1, table.table.getSelectedRow());
                Point position = table.scroll.getViewport().getViewPosition();
                table.reveal(model.row(21).rowId(), RevealMode.SELECT_AND_SCROLL);
                assertEquals(position, table.scroll.getViewport().getViewPosition()); assertEquals(21, table.table.getSelectedRow());
                table.reveal(model.row(60).rowId(), RevealMode.SELECT_AND_SCROLL);
                assertTrue(table.table.getVisibleRect().contains(table.table.getCellRect(60, 0, true)));
            } finally { port.exit(ExitKind.CLEAN, 0); }
        });
    }

    @Test void menuActionReleasesRealSelectionPathBeforeActionAndDoesNotClickDisabledItem() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMenuItem item = new JMenuItem(); int[] calls = {0};
            item.addActionListener(event -> {
                assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length); calls[0]++;
            });
            MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{item});
            SwingUiDriver.activateMenuItem(item); assertEquals(1, calls[0]);
            item.setEnabled(false); assertThrows(IllegalStateException.class, () -> SwingUiDriver.activateMenuItem(item));
            assertEquals(1, calls[0]);
        });
    }

    @Test void chartContextIdKeepsRealEventCoordinates() {
        assertEquals("chart:300,200", SwingUiPort.contextId(new ContextTarget.Chart(300, 200, 1200, 600), null));
        assertEquals("row:r1@2026-10-05", SwingUiPort.contextId(new ContextTarget.Row("r1@2026-10-05"), null));
    }

    @Test void pendingModalAlertDisablesNativeTooltipsAndCloseRestoresThem() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            var port = port();
            // JavaFX: Alert → Swing: SwingAlerts → Web: dialog
            var alert = new SwingAlerts(port, "a", AlertCatalog.unsavedChanges(""), null, id -> { });
            try {
                port.alerts.put("a", alert); port.refreshTooltips();
                assertFalse(ToolTipManager.sharedInstance().isEnabled()); assertFalse(alert.dialog.isVisible());
                alert.close(); assertTrue(ToolTipManager.sharedInstance().isEnabled());
            } finally { alert.close(); port.exit(ExitKind.CLEAN, 0); ToolTipManager.sharedInstance().setEnabled(true); }
        });
    }

    @Test void alertMinimumExcludesChromeAndButtonsKeepIndividualRealCaptions() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            var port = port();
            // JavaFX: Alert → Swing: SwingAlerts → Web: dialog
            var alert = new SwingAlerts(port, "a", AlertCatalog.unsavedChanges(""), null, id -> { });
            try {
                assertFalse(alert.dialog.isVisible());
                assertEquals(460, alert.dialog.getContentPane().getMinimumSize().width);
                assertEquals(460, alert.dialog.getContentPane().getWidth());
                var buttons = List.copyOf(alert.buttons.values());
                for (JButton button : buttons) {
                    assertEquals(button.getPreferredSize().width, button.getWidth());
                    assertTrue(button.getWidth() >= ru.cashprediction.core.ui.token.DesignTokens.BUTTON_MIN_WIDTH);
                }
                for (int i = 1; i < buttons.size(); i++) assertEquals(8, buttons.get(i).getX() - buttons.get(i - 1).getX() - buttons.get(i - 1).getWidth());
                assertEquals(alert.dialog.getWidth(), alert.bounds().width());
                System.out.println("alert contentMin=" + alert.dialog.getContentPane().getMinimumSize().width + " rawMin=" + alert.dialog.getMinimumSize().width
                        + " actualWidths=" + buttons.stream().map(JButton::getWidth).toList() + " buttonXs=" + buttons.stream().map(b -> SwingUtilities.convertPoint(b, 0, 0, alert.dialog.getContentPane()).x).toList());
            } finally { alert.close(); port.exit(ExitKind.CLEAN, 0); }
        });
    }

    @Test void nestedPlaceholderButtonCoordinatesKeepOriginalContentOrigin() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel content = new JPanel(null), nested = new JPanel(null);
            content.setSize(1200, 500); nested.setBounds(300, 100, 600, 100); content.add(nested);
            JButton button = SwingLook.id(new JButton(), "empty.clearFilter"); button.setBounds(200, 8, 100, 28); nested.add(button);
            assertEquals(500, SwingUiDumper.buttons(content, null).getFirst().x());
        });
    }

    @Test void emptyTableKeepsRealScrollHeaderAndEightColumnRegions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var port = port(); var app = new AppController(port, port.environment); port.bind(app);
            try {
                var table = new SwingTable(port); var model = LazyTableModel.build(app.state(), 1);
                assertNotNull(model.placeholder());
                // Реальный lifecycle JTable снимает header при отсоединении показанного viewport.
                table.table.addNotify(); table.table.removeNotify();
                assertNull(table.scroll.getColumnHeader().getView());
                table.render(model); table.setSize(1200, 500); layout(table);
                assertSame(table.scroll, table.getComponent(0)); assertSame(table.placeholder, table.scroll.getViewport().getView());
                assertSame(table.table.getTableHeader(), table.scroll.getColumnHeader().getView());
                JLabel text = (JLabel) table.placeholder.getComponent(1);
                assertEquals(text.getPreferredSize(), text.getMaximumSize());
                assertTrue(text.getX() > 0);
                var regions = new java.util.LinkedHashMap<String, ru.cashprediction.core.ui.dump.UiDump.Box>();
                SwingUiDumper.columnRegions(regions, table.table, table);
                assertEquals(8, regions.size()); assertTrue(table.table.getTableHeader().getWidth() > 0);
                assertTrue(regions.values().stream().allMatch(box -> box.width() > 0 && box.height() == 28));
                System.out.println("empty actualHeader=" + table.table.getTableHeader().getBounds() + " columnRegions=" + regions.size());
            } finally { port.exit(ExitKind.CLEAN, 0); }
        });
    }

    @Test void filterNodeUsesDeclaredWidthWithVisibleClearAndSpacerGetsRemainder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); var toolbar = new SwingToolbar(null);
            toolbar.render(new ToolbarModel(List.of(new ToolbarNode.FilterField("filter", "text", "", "", 220, 300, true, ""), new ToolbarNode.Spacer("space"))));
            toolbar.setSize(1200, 36); layout(toolbar);
            assertEquals(220, toolbar.widget("filter").getWidth());
            assertEquals(220, toolbar.filter().getWidth());
            assertEquals(224, toolbar.widget("space").getX());
            assertEquals(976, toolbar.widget("space").getWidth());
            System.out.println("filter actualNode=" + toolbar.widget("filter").getBounds() + " input=" + toolbar.filter().getBounds() + " spacer=" + toolbar.widget("space").getBounds());
        });
    }

    /** Раскладывает существующие компоненты, не показывая окон и не генерируя события мыши. */
    private static void layout(Container parent) { parent.doLayout(); for (Component child : parent.getComponents()) if (child instanceof Container container) layout(container); }

    /** Создаёт изолированный порт без запуска приложения. */
    private SwingUiPort port() {
        SwingLook.install(); return new SwingUiPort(AppEnvironment.from(LaunchOptions.parse("--home", directory.toString(), "--registry", "memory", "--selftest", "s05-forms-plan")));
    }
}
