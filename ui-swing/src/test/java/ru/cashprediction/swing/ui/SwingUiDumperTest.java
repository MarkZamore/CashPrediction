package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.menu.ToolbarModel;
import ru.cashprediction.core.ui.menu.ToolbarNode;
import ru.cashprediction.core.ui.token.ColorToken;

/** Проверяет смысл полей дампа на изменённых физических виджетах, а не копии описания. */
class SwingUiDumperTest {
    @Test void summaryCardPreservesThreeTextRowsAndOriginalInterlineGaps() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); var panel = new SwingSummaryPanel(null);
            var model = new ru.cashprediction.core.ui.view.summary.CardModel("card", "title", "value", ColorToken.TEXT_PRIMARY,
                    "caption", ColorToken.TEXT_MUTED, null, "", "");
            var card = panel.new Card(model);
            assertEquals(5, card.getComponentCount());
            assertEquals(ru.cashprediction.core.ui.token.DesignTokens.CARD_CONTENT_GAP, card.getComponent(1).getPreferredSize().height);
            assertEquals(ru.cashprediction.core.ui.token.DesignTokens.CARD_CONTENT_GAP, card.getComponent(3).getPreferredSize().height);
            var insets = card.getInsets();
            int textHeight = java.util.Arrays.stream(card.getComponents()).mapToInt(c -> c.getPreferredSize().height).sum();
            assertEquals(textHeight + insets.top + insets.bottom, card.getPreferredSize().height);
            assertEquals(ru.cashprediction.core.ui.token.DesignTokens.CARD_PAD_V + 1, insets.top);
        });
    }
    @Test void baselineFollowsRealFontAndRootCoordinates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel root = new JPanel(null), status = new JPanel(null); root.add(status); status.setBounds(0, 100, 400, 40);
            JLabel label = new JLabel("status"); label.setFont(label.getFont().deriveFont(18f)); status.add(label); label.setBounds(10, 3, 150, 32);
            var regions = new java.util.LinkedHashMap<String, ru.cashprediction.core.ui.dump.UiDump.Box>();
            SwingUiDumper.baselineRegion(regions, "status.baseline", status, root);
            assertEquals(103 + label.getBaseline(150, 32), regions.get("status.baseline").y());
            assertEquals(1, regions.get("status.baseline").height());
            assertEquals(10, regions.get("status.baseline").x());
            assertEquals(label.getFontMetrics(label.getFont()).stringWidth(label.getText()), regions.get("status.baseline").width());
            label.setFont(label.getFont().deriveFont(11f));
            SwingUiDumper.baselineRegion(regions, "status.baseline", status, root);
            assertEquals(103 + label.getBaseline(150, 32), regions.get("status.baseline").y());
        });
    }
    @Test void headerColumnsUseActualResizedWidgets() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel root = new JPanel(null); root.setSize(400, 250); JTable table = new JTable(0, 2); var header = table.getTableHeader();
            table.getColumnModel().getColumn(0).setIdentifier("date"); table.getColumnModel().getColumn(1).setIdentifier("title");
            table.getColumnModel().getColumn(0).setWidth(90); table.getColumnModel().getColumn(1).setWidth(210);
            root.add(header); header.setBounds(7, 150, 300, 28);
            var regions = new java.util.LinkedHashMap<String, ru.cashprediction.core.ui.dump.UiDump.Box>();
            SwingUiDumper.columnRegions(regions, table, root);
            assertEquals(new ru.cashprediction.core.ui.dump.UiDump.Box(7, 150, 90, 28), regions.get("table.column.date"));
            assertEquals(new ru.cashprediction.core.ui.dump.UiDump.Box(97, 150, 210, 28), regions.get("table.column.title"));
        });
    }
    @Test void htmlWidthUsesActualAwtPixels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            JLabel label = new JLabel(SwingLook.html("short", 240));
            assertEquals(240, label.getPreferredSize().width);
        });
    }
    @Test void controlsUseTokenPaddingMetricsAndUniformGap() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); SwingToolbar toolbar = new SwingToolbar(null);
            toolbar.render(new ToolbarModel(List.of(
                    new ToolbarNode.Toggle("table", ru.cashprediction.core.ui.command.CommandId.VIEW_TABLE, "Table", "", true, "mode"),
                    new ToolbarNode.Button("undo", ru.cashprediction.core.ui.command.CommandId.EDIT_UNDO, "↶", "", true, ru.cashprediction.core.ui.menu.Emphasis.NONE))));
            toolbar.setSize(1200, 36); toolbar.doLayout();
            var text = (AbstractButton) toolbar.widget("table"); var undo = toolbar.widget("undo");
            assertEquals(text.getFontMetrics(text.getFont()).stringWidth(text.getText()) + 20, text.getWidth());
            assertEquals(0, text.getX()); assertEquals(4, text.getY()); assertEquals(28, text.getHeight());
            assertEquals(text.getX() + text.getWidth() + 4, undo.getX());
            assertEquals(28, undo.getWidth()); assertEquals(28, undo.getHeight());
            assertEquals(new java.awt.Insets(4, 10, 4, 10), text.getInsets());
            var icon = (AbstractButton) undo;
            assertEquals(new java.awt.Insets(6, 6, 6, 6), icon.getInsets());
            assertEquals("", icon.getText());
            assertEquals("↶", icon.getAccessibleContext().getAccessibleName());
            assertEquals("↶", SwingUiDumper.toolbar(toolbar).items().get(1).text());
            assertEquals(16, icon.getIcon().getIconWidth());
            assertEquals(16, icon.getDisabledIcon().getIconWidth());
        });
    }
    @Test void toolbarBoundsUseRealContentRootCoordinates() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel root = new JPanel(null); root.setSize(1200, 800);
            SwingToolbar toolbar = new SwingToolbar(null); root.add(toolbar); toolbar.setBounds(0, 28, 1200, 36);
            toolbar.render(new ToolbarModel(List.of(new ToolbarNode.Separator("separator")))); toolbar.doLayout();
            var actual = SwingUiDumper.toolbar(toolbar, root).items().getFirst().bounds();
            java.awt.Point point = SwingUtilities.convertPoint(toolbar.widget("separator"), 0, 0, root);
            assertEquals(point.x, actual.x()); assertEquals(point.y, actual.y());
            assertEquals(32, actual.y()); assertEquals(28, actual.height());
        });
    }
    @Test void radioValueAndOptionsSpanBothPhysicalRowsInOrder() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JRadioButton months = new JRadioButton("months"), years = new JRadioButton("years"), date = new JRadioButton("date");
            months.setActionCommand("MONTHS"); years.setActionCommand("YEARS"); date.setActionCommand("DATE");
            ButtonGroup group = new ButtonGroup(); List<JRadioButton> buttons = List.of(months, years, date); buttons.forEach(group::add);
            JPanel first = new JPanel(), second = new JPanel(); first.add(months); first.add(years); second.add(date);
            date.setSelected(true); date.setText("changed date");
            var state = SwingUiDumper.radioState(buttons);
            assertEquals("DATE", state.value()); assertEquals(List.of("months", "years", "changed date"), state.options());
            years.setSelected(true); assertEquals("YEARS", SwingUiDumper.radioState(buttons).value());
            buttons.forEach(button -> button.setEnabled(false)); assertFalse(SwingUiDumper.radioState(buttons).enabled());
        });
    }
    @Test void nonTextToolbarNodesHaveNoTextColorButKeepActualStrokeColor() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); SwingToolbar toolbar = new SwingToolbar(null);
            toolbar.render(new ToolbarModel(List.of(new ToolbarNode.Separator("separator"), new ToolbarNode.Spacer("spacer"))));
            var dump = SwingUiDumper.toolbar(toolbar);
            assertEquals(List.of("", ""), dump.items().stream().map(item -> item.color()).toList());
            assertEquals(SwingLook.color(ColorToken.BORDER), toolbar.widget("separator").getForeground());
        });
    }
}
