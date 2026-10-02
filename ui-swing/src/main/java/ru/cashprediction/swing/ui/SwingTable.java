package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.font.TextAttribute;
import java.util.Map;
import javax.swing.*;
import javax.swing.table.*;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.menu.ContextTarget;
import ru.cashprediction.core.ui.token.*;
import ru.cashprediction.core.ui.view.table.*;

/** Виртуальная таблица с оформлением и поведением из моделей ядра. */
public final class SwingTable extends JPanel {
    final SwingTableAdapter adapter = new SwingTableAdapter(null);
    final JTable table;
    final JScrollPane scroll;
    final JPanel placeholder = new JPanel();
    private final SwingUiPort port;
    private boolean applying;

    /** Создаёт виджеты таблицы и передаёт только намерения пользователя. */
    public SwingTable(SwingUiPort port) {
        super(new BorderLayout()); this.port = port;
        table = SwingLook.id(new SpanningTable(), "table"); table.setModel(adapter);
        table.setRowHeight(DesignTokens.ROW_HEIGHT); table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowVerticalLines(false); table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0)); table.setAutoCreateRowSorter(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setPreferredSize(new Dimension(0, DesignTokens.HEADER_HEIGHT));
        table.setDefaultRenderer(Object.class, new Cells());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!applying && !e.getValueIsAdjusting()) {
                int row = table.getSelectedRow();
                // Потеря видимого индекса при обновлении JTable не отменяет логическое выделение ядра.
                if (row >= 0 && row < adapter.source().rowCount()) port.intents().selectRow(adapter.source().row(row).rowId());
            }
        });
        // JavaFX: ContextMenuEvent → Swing: MouseEvent.isPopupTrigger → Web: contextmenu
        table.addMouseListener(new MouseAdapter() {
            /** Выделяет строку перед показом меню. */
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            /** Показывает меню на платформе с триггером при отпускании. */
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
            /** Передаёт одинарное и двойное нажатие в ядро. */
            @Override public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint()), col = table.columnAtPoint(e.getPoint());
                if (row < 0 || col < 0 || !SwingUtilities.isLeftMouseButton(e)) return;
                TableRowView item = adapter.source().row(row);
                if (e.getClickCount() == 2 || item.kind() == RowKind.PAST_HEADER)
                    port.intents().activateRow(item.rowId(), adapter.source().columns().get(col).id(), e.getClickCount() == 2 ? Activation.DOUBLE_CLICK : Activation.CLICK);
            }
            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint()); if (row < 0) return;
                // Меню группы прошлого не меняет выделенное событие основного прогноза.
                if (adapter.source().row(row).kind() != RowKind.PAST_HEADER) table.setRowSelectionInterval(row, row);
                port.context(target(row), table, e.getX(), e.getY());
            }
        });
        scroll = new JScrollPane(table); scroll.setColumnHeaderView(table.getTableHeader()); scroll.getViewport().setBackground(SwingLook.color(ColorToken.BG_SURFACE));
        placeholder.setLayout(new BoxLayout(placeholder, BoxLayout.Y_AXIS)); placeholder.setBackground(SwingLook.color(ColorToken.BG_SURFACE));
        add(scroll); SwingLook.id(this, "center");
    }

    /** Применяет новую ревизию, не извлекая все строки. */
    public void render(ru.cashprediction.core.ui.view.table.TableModel model) {
        applying = true;
        boolean first = adapter.source() == null;
        adapter.update(model);
        if (first || table.getColumnCount() != model.columns().size()) table.createDefaultColumnsFromModel();
        for (int i = 0; i < model.columns().size(); i++) {
            ColumnSpec column = model.columns().get(i); TableColumn widget = table.getColumnModel().getColumn(i);
            widget.setIdentifier(column.id()); widget.setHeaderValue(column.title()); widget.setPreferredWidth(column.widthPx());
            if (!column.grows()) { widget.setMinWidth(column.widthPx()); widget.setMaxWidth(column.widthPx()); }
        }
        int selected = model.indexOf(model.selectedRowId());
        if (selected >= 0) table.setRowSelectionInterval(selected, selected); else table.clearSelection();
        if (model.placeholder() != null) {
            placeholder.removeAll(); placeholder.add(Box.createVerticalGlue());
            Placeholder empty = model.placeholder();
            JLabel text = SwingLook.label(SwingLook.html(empty.text(), 620), empty.color(), FontToken.BASE);
            text.putClientProperty("cp.text", empty.text()); text.setAlignmentX(.5f);
            // BoxLayout центрирует настоящий текстовый блок, а не растягивает его до левого края viewport.
            text.setMaximumSize(text.getPreferredSize()); placeholder.add(text);
            JPanel actions = new JPanel(); actions.setOpaque(false); actions.setLayout(new BoxLayout(actions, BoxLayout.X_AXIS)); actions.setAlignmentX(.5f);
            actions.add(Box.createHorizontalGlue());
            for (Placeholder.Button action : empty.buttons()) {
                JButton button = SwingLook.id(new SwingFractionalButton(action.text()), action.id());
                SwingLook.dialogButton(button);
                if (actions.getComponentCount() > 1) actions.add(Box.createHorizontalStrut(DesignTokens.FORM_VGAP));
                button.addActionListener(e -> port.intents().command(action.command(), CommandArgs.NONE, InvokeSource.MAIN)); actions.add(button);
            }
            actions.add(Box.createHorizontalGlue()); actions.setMaximumSize(new Dimension(Integer.MAX_VALUE, DesignTokens.CONTROL_HEIGHT));
            placeholder.add(actions); placeholder.add(Box.createVerticalGlue());
            if (scroll.getViewport().getView() != placeholder) scroll.setViewportView(placeholder);
        } else if (scroll.getViewport().getView() != table) scroll.setViewportView(table);
        // JTable.removeNotify при замене viewport снимает header; placeholder сохраняет настоящую шапку таблицы.
        scroll.setColumnHeaderView(table.getTableHeader());
        applying = false; revalidate(); repaint();
    }

    /** Сохраняет реальные ширины колонок видимого заголовка, когда вместо строк показан placeholder. */
    @Override public void doLayout() {
        super.doLayout(); scroll.doLayout();
        if (scroll.getViewport().getView() == placeholder) {
            table.setSize(scroll.getViewport().getWidth(), table.getPreferredSize().height);
            table.doLayout();
        }
    }

    /** Прокручивает строку и при необходимости выделяет её. */
    public void reveal(String rowId, RevealMode mode) {
        int index = adapter.source().indexOf(rowId); if (index < 0) return;
        // После обновления модели viewport должен раскладывать новую высоту строк до расчёта предела прокрутки.
        scroll.doLayout(); scroll.getViewport().doLayout();
        if (mode == RevealMode.SELECT_AND_SCROLL) table.setRowSelectionInterval(index, index);
        Rectangle cell = table.getCellRect(index, 0, true);
        if (mode == RevealMode.SCROLL_TO_TOP) scroll.getViewport().setViewPosition(new Point(0, Math.min(cell.y, Math.max(0, table.getHeight() - scroll.getViewport().getHeight()))));
        else table.scrollRectToVisible(cell);
    }

    /** Возвращает цель контекстного меню по виду строки модели. */
    public ContextTarget target(int row) {
        TableRowView item = adapter.source().row(row);
        return switch (item.kind()) {
            case MONTH_TOTAL -> new ContextTarget.Total(item.rowId());
            case PAST_HEADER -> new ContextTarget.PastHeader(item.rowId());
            default -> new ContextTarget.Row(item.rowId());
        };
    }

    /** JTable дополнительно рисует объединённую ведущую ячейку, не создавая виджет на строку. */
    private final class SpanningTable extends JTable {
        /** Запрашивает подсказку только наведённой ячейки текущей ревизии. */
        @Override public String getToolTipText(MouseEvent e) {
            int row = rowAtPoint(e.getPoint()), col = columnAtPoint(e.getPoint());
            if (row < 0 || col < 0) return null;
            // JavaFX: Tooltip → Swing: JToolTip → Web: div.tooltip
            return SwingLook.tooltipHtml(port.intents().tableTooltip(adapter.source().revision(), row, adapter.source().columns().get(col).id()));
        }
        /** Закрашивает объединённую область поверх стандартных ячеек JTable. */
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g); if (adapter.source() == null) return;
            Rectangle clip = g.getClipBounds();
            int start = Math.max(0, rowAtPoint(new Point(0, clip.y))), end = Math.min(getRowCount() - 1, (clip.y + clip.height) / getRowHeight());
            for (int row = start; row <= end; row++) {
                TableRowView item = adapter.source().row(row); if (item.leadingSpan() <= 1) continue;
                Rectangle r = getCellRect(row, 0, true);
                for (int col = 1; col < item.leadingSpan(); col++) r = r.union(getCellRect(row, col, true));
                Component renderer = prepareRenderer(getCellRenderer(row, 0), row, 0);
                SwingUtilities.paintComponent(g, renderer, this, r.x, r.y, r.width, r.height);
            }
        }
    }

    /** Оформление ячейки берётся из готовых токенов строки и исключений ячейки. */
    private final class Cells extends DefaultTableCellRenderer {
        /** Рисует цвет, начертание и зачёркивание без финансовых вычислений. */
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focused, int row, int col) {
            super.getTableCellRendererComponent(table, value, selected, focused, row, col);
            TableRowView item = adapter.source().row(row); ColumnSpec column = adapter.source().columns().get(col);
            RowStyle style = item.rowStyle(); CellStyle cell = item.cellStyles().get(column.id());
            ColorToken color = cell == null || cell.text() == null ? style.text() : cell.text();
            boolean bold = column.bold() || (cell == null ? style.bold() : cell.bold());
            boolean italic = cell == null ? style.italic() : cell.italic();
            boolean strike = cell != null && cell.strike();
            setFont(SwingLook.font(FontToken.BASE).deriveFont((bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0))
                    .deriveFont(Map.of(TextAttribute.STRIKETHROUGH, strike)));
            setForeground(SwingLook.color(color));
            setBackground(SwingLook.color(selected ? ColorToken.ACCENT_WEAK : style.background() == null ? ColorToken.BG_SURFACE : style.background()));
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
            setHorizontalAlignment(switch (column.align()) { case LEFT -> LEFT; case CENTER -> CENTER; case RIGHT -> RIGHT; });
            putClientProperty("cp.strike", strike); return this;
        }
    }
}
