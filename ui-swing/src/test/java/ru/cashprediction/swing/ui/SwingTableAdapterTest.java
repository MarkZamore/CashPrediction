package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.view.table.*;

/** Проверки ленивого адаптера на синтетическом контракте строк, без прогноза в клиенте. */
class SwingTableAdapterTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    private final AtomicInteger reads = new AtomicInteger();
    private TableModel model() {
        return new TableModel() {
            /** Возвращает ревизию тестового контракта. */
            @Override public long revision() { return 1; }
            /** Возвращает одну колонку контракта. */
            @Override public List<ColumnSpec> columns() { return List.of(new ColumnSpec("title", "title", 220, true, ColumnSpec.Align.LEFT, false, "tip")); }
            /** Возвращает большое число строк без материализации. */
            @Override public int rowCount() { return 200_000; }
            /** Учитывает извлечение конкретной строки. */
            @Override public TableRowView row(int row) { reads.incrementAndGet(); return new TableRowView("row" + row, RowKind.RULE, List.of("cell" + row), null, null, 1, false); }
            /** Возвращает пустую подсказку. */
            @Override public String tooltip(int row, String col) { return ""; }
            /** Возвращает тестовый индекс. */
            @Override public int indexOf(String id) { return -1; }
            /** Возвращает отсутствие выделения. */
            @Override public String selectedRowId() { return ""; }
            /** Возвращает отсутствие прокрутки. */
            @Override public String scrollToRowId() { return ""; }
            /** Возвращает отсутствие пустого состояния. */
            @Override public Placeholder placeholder() { return null; }
        };
    }
    @Test void constructionAndRowCountNeverReadRows() { SwingTableAdapter adapter = new SwingTableAdapter(model()); assertEquals(200_000, adapter.getRowCount()); assertEquals(1, adapter.getColumnCount()); assertEquals(0, reads.get()); }
    @Test void onlyRequestedRowIsRead() { SwingTableAdapter adapter = new SwingTableAdapter(model()); assertEquals("cell190000", adapter.getValueAt(190_000, 0)); assertEquals(1, reads.get()); }
    @Test void newRevisionDoesNotMaterializeRows() { SwingTableAdapter adapter = new SwingTableAdapter(model()); adapter.update(model()); assertEquals(0, reads.get()); assertFalse(adapter.isCellEditable(0, 0)); }

    /** Все восемь заголовков следуют модели выравнивания и не наследуют жирность ячеек. */
    @Test void actualHeadersUseCommonAlignmentTooltipAndNormalWeight() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var ids = List.of("date", "day", "title", "category", "income", "expense", "balance", "marks");
            var alignments = List.of(ColumnSpec.Align.LEFT, ColumnSpec.Align.CENTER, ColumnSpec.Align.LEFT,
                    ColumnSpec.Align.LEFT, ColumnSpec.Align.RIGHT, ColumnSpec.Align.RIGHT,
                    ColumnSpec.Align.RIGHT, ColumnSpec.Align.CENTER);
            var columns = java.util.stream.IntStream.range(0, 8).mapToObj(index ->
                    new ColumnSpec(ids.get(index), ids.get(index), 120, false, alignments.get(index),
                            index == 6, "tip-" + ids.get(index))).toList();
            var base = model();
            var source = (TableModel) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{TableModel.class}, (proxy, method, args) ->
                            method.getName().equals("columns") ? columns : method.invoke(base, args));
            var view = new SwingTable(null); view.render(source);
            for (int index = 0; index < 8; index++) {
                var column = view.table.getColumnModel().getColumn(index);
                var header = (javax.swing.JLabel) column.getHeaderRenderer().getTableCellRendererComponent(
                        view.table, column.getHeaderValue(), false, false, -1, index);
                assertFalse(header.getFont().isBold(), ids.get(index));
                assertEquals(SwingLook.font(ru.cashprediction.core.ui.token.FontToken.BASE), header.getFont());
                assertEquals(switch (alignments.get(index)) {
                    case LEFT -> javax.swing.SwingConstants.LEFT;
                    case CENTER -> javax.swing.SwingConstants.CENTER;
                    case RIGHT -> javax.swing.SwingConstants.RIGHT;
                }, header.getHorizontalAlignment(), ids.get(index));
                assertEquals("tip-" + ids.get(index), header.getToolTipText());
            }
        });
    }

    @Test void selectedCellsPaintSemanticIncomeExpenseAndNegativeBalanceColors() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); var base = model();
            var ids = List.of("income", "expense", "balance");
            var colors = List.of(ru.cashprediction.core.ui.token.ColorToken.INCOME,
                    ru.cashprediction.core.ui.token.ColorToken.EXPENSE, ru.cashprediction.core.ui.token.ColorToken.EXPENSE);
            var source = (TableModel) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TableModel.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "columns" -> ids.stream().map(id -> new ColumnSpec(id, id, 120, false, ColumnSpec.Align.RIGHT, false, "")).toList();
                        case "rowCount" -> 1;
                        case "indexOf" -> "row0".equals(args[0]) ? 0 : -1;
                        case "selectedRowId" -> "row0";
                        case "row" -> new TableRowView("row0", RowKind.RULE, List.of("81000", "12000", "-100"),
                                new RowStyle(ru.cashprediction.core.ui.token.ColorToken.NEGATIVE_BG,
                                        ru.cashprediction.core.ui.token.ColorToken.TEXT_PRIMARY, false, false),
                                java.util.Map.of("income", new CellStyle(colors.get(0), false, false, false),
                                        "expense", new CellStyle(colors.get(1), false, false, false),
                                        "balance", new CellStyle(colors.get(2), false, false, false)), 1, false);
                        default -> method.invoke(base, args);
                    });
            var view = new SwingTable(null); view.render(source); view.table.setSize(360, 26); view.table.doLayout();
            var image = new java.awt.image.BufferedImage(360, 26, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); try { view.table.paint(graphics); } finally { graphics.dispose(); }
            for (int column = 0; column < ids.size(); column++) {
                var cell = view.table.prepareRenderer(view.table.getCellRenderer(0, column), 0, column);
                assertEquals(SwingLook.color(colors.get(column)), cell.getForeground());
                assertEquals(SwingLook.color(ru.cashprediction.core.ui.token.ColorToken.ACCENT_WEAK), cell.getBackground());
                var bounds = view.table.getCellRect(0, column, true);
                assertEquals(cell.getBackground().getRGB(), image.getRGB(bounds.x + 1, 12));
                boolean textPainted = false;
                for (int y = bounds.y; y < bounds.y + bounds.height; y++)
                    for (int x = bounds.x; x < bounds.x + bounds.width; x++)
                        if (image.getRGB(x, y) == cell.getForeground().getRGB()) textPainted = true;
                assertTrue(textPainted, ids.get(column));
            }
            var row = new SwingUiDumper(null, null, "paint-test").table(view).rows().getFirst();
            assertEquals("accent.weak", row.background());
            assertEquals("income", row.styles().get("income").color());
            assertEquals("expense", row.styles().get("expense").color());
            assertEquals("expense", row.styles().get("balance").color());
        });
    }

    @Test void missingVisibleIndexDoesNotClearLogicalSelectionAndDumpReadsSelectedPaint() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var environment = ru.cashprediction.core.app.AppEnvironment.from(ru.cashprediction.core.app.LaunchOptions.parse("--home", directory.toString(), "--registry", "memory", "--selftest", "s13-undo-redo"));
            var port = new SwingUiPort(environment);
            var selections = new java.util.ArrayList<String>();
            var intents = (ru.cashprediction.core.app.UiIntents) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ru.cashprediction.core.app.UiIntents.class},
                    (proxy, method, args) -> { if (method.getName().equals("selectRow")) selections.add((String) args[0]); return null; });
            port.bind(intents);
            try {
                var base = model(); var visible = new java.util.concurrent.atomic.AtomicBoolean(true);
                var source = (TableModel) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TableModel.class},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "rowCount" -> visible.get() ? 1 : 0;
                            case "selectedRowId" -> "row0";
                            case "indexOf" -> visible.get() && "row0".equals(args[0]) ? 0 : -1;
                            default -> method.invoke(base, args);
                        });
                var view = new SwingTable(port); view.render(source); view.table.setSize(300, 26);
                var cell = view.table.prepareRenderer(view.table.getCellRenderer(0, 0), 0, 0);
                assertEquals(SwingLook.color(ru.cashprediction.core.ui.token.ColorToken.ACCENT_WEAK), cell.getBackground());
                var image = new java.awt.image.BufferedImage(300, 26, java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics(); try { view.table.paint(graphics); } finally { graphics.dispose(); }
                var paintedCell = view.table.getCellRect(0, 0, true);
                assertEquals(cell.getBackground().getRGB(), image.getRGB(paintedCell.x + paintedCell.width - 2, 12));
                var dump = new SwingUiDumper(port, null, "test").table(view);
                assertEquals("accent.weak", dump.rows().getFirst().background());
                view.table.clearSelection(); assertTrue(selections.isEmpty());
                visible.set(false); view.render(source);
                assertEquals(-1, view.table.getSelectedRow()); assertEquals("row0", source.selectedRowId()); assertTrue(selections.isEmpty());
                assertEquals("", new SwingUiDumper(port, null, "test").table(view).selectedRowId());
                visible.set(true); view.render(source); view.table.clearSelection(); view.table.setRowSelectionInterval(0, 0);
                assertEquals(List.of("row0"), selections);
            } finally { port.exit(ru.cashprediction.core.app.ExitKind.CLEAN, 0); }
        });
    }

    @Test void rendererKeepsBoldColumnWithCellStyleAndFallsBackToRowColor() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var base = model();
            var source = (TableModel) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{TableModel.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "columns" -> List.of(new ColumnSpec("title", "title", 220, true, ColumnSpec.Align.LEFT, true, "tip"));
                        case "rowCount" -> 1;
                        case "row" -> new TableRowView("row0", RowKind.RULE, List.of("cell"),
                                new RowStyle(null, ru.cashprediction.core.ui.token.ColorToken.TEXT_PAST, false, false),
                                java.util.Map.of("title", new CellStyle(null, false, true, true)), 1, false);
                        default -> method.invoke(base, args);
                    });
            var view = new SwingTable(null); view.render(source);
            var cell = view.table.prepareRenderer(view.table.getCellRenderer(0, 0), 0, 0);
            assertTrue(cell.getFont().isBold()); assertTrue(cell.getFont().isItalic());
            assertEquals(SwingLook.color(ru.cashprediction.core.ui.token.ColorToken.TEXT_PAST), cell.getForeground());
            assertEquals(Boolean.TRUE, ((javax.swing.JComponent) cell).getClientProperty("cp.strike"));
            assertFalse(source.row(0).cellStyles().get("title").bold());
        });
    }
}
