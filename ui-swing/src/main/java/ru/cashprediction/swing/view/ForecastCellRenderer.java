package ru.cashprediction.swing.view;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.swing.dialog.SwingText;

/**
 * Отрисовщик ячеек таблицы прогноза: те же смысловые цвета, что в JavaFX- и Web-клиентах.
 *
 * <p>Доход — зелёный текст, расход — красный; фон строки с отрицательным балансом — светло-красный, с балансом ниже
 * подушки — светло-жёлтый; прошедшие события — серый текст; пропущенное событие — зачёркнутое название; итог
 * месяца — полужирный на голубом фоне; заголовок «Прошедшие события» — курсив. Суммы выровнены вправо.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
public final class ForecastCellRenderer extends DefaultTableCellRenderer {

    /** Создаёт отрисовщик. */
    public ForecastCellRenderer() {
    }

    /**
     * Настраивает вид ячейки прогноза: отступы, выравнивание сумм вправо, цвета дохода и расхода, фон строки
     * (минус, ниже подушки, итог месяца, прошедшее событие) и начертание.
     *
     * @param table      таблица
     * @param value      значение ячейки
     * @param isSelected выделена ли строка
     * @param hasFocus   в фокусе ли ячейка
     * @param row        строка в представлении
     * @param column     колонка в представлении
     * @return настроенный компонент отрисовки (этот же объект)
     */
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus,
                                                   int row, int column) {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        ForecastTableModel model = (ForecastTableModel) table.getModel();
        int modelRow = table.convertRowIndexToModel(row);
        int modelColumn = table.convertColumnIndexToModel(column);
        TableItem item = model.itemAt(modelRow);
        setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        setHorizontalAlignment(isAmountColumn(modelColumn) ? SwingConstants.RIGHT
                : modelColumn == ForecastTableModel.COL_DAY || modelColumn == ForecastTableModel.COL_MARKS
                ? SwingConstants.CENTER : SwingConstants.LEFT);
        Font base = table.getFont();
        setFont(base);

        Color background = table.getBackground();
        Color foreground = table.getForeground();
        switch (item.type()) {
            case MONTH_TOTAL -> {
                background = Palette.TOTAL_BG;
                setFont(base.deriveFont(Font.BOLD));
                if (modelColumn == ForecastTableModel.COL_INCOME) {
                    foreground = Palette.INCOME;
                } else if (modelColumn == ForecastTableModel.COL_EXPENSE) {
                    foreground = Palette.EXPENSE;
                }
            }
            case PAST_HEADER -> {
                background = Palette.CARD_BG;
                foreground = Palette.PAST;
                setFont(base.deriveFont(Font.ITALIC));
            }
            case ROW -> {
                ForecastRow r = item.row();
                if (r.balanceAfter().isNegative()) {
                    background = Palette.NEGATIVE_BG;
                } else if (model.cushion().isPositive() && r.balanceAfter().compareTo(model.cushion()) < 0) {
                    background = Palette.CUSHION_BG;
                }
                if (r.flags().past()) {
                    foreground = Palette.PAST;
                } else if (modelColumn == ForecastTableModel.COL_INCOME) {
                    foreground = Palette.INCOME;
                } else if (modelColumn == ForecastTableModel.COL_EXPENSE) {
                    foreground = Palette.EXPENSE;
                } else if (modelColumn == ForecastTableModel.COL_BALANCE && r.balanceAfter().isNegative()) {
                    foreground = Palette.EXPENSE;
                }
                if (r.origin() == Origin.START) {
                    setFont(base.deriveFont(Font.BOLD));
                }
                if (modelColumn == ForecastTableModel.COL_TITLE && r.flags().skipped()) {
                    // JLabel не умеет зачёркивать простой текст, поэтому пропущенное событие показываем через HTML.
                    setText("<html><s>" + SwingText.escape(String.valueOf(value)) + "</s></html>");
                }
                if (modelColumn == ForecastTableModel.COL_BALANCE) {
                    setFont(getFont().deriveFont(Font.BOLD));
                }
            }
        }
        if (!isSelected) {
            setBackground(background);
            setForeground(foreground);
        }
        return this;
    }

    private static boolean isAmountColumn(int modelColumn) {
        return modelColumn == ForecastTableModel.COL_INCOME || modelColumn == ForecastTableModel.COL_EXPENSE
                || modelColumn == ForecastTableModel.COL_BALANCE;
    }
}
