package ru.cashprediction.swing.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.swing.table.AbstractTableModel;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Модель таблицы прогноза: колонки «Дата | День | Операция | Категория | Доход | Расход | Баланс | Отметки».
 *
 * <p>Модель хранит готовый список {@link TableItem} (события, итоги месяцев, заголовок прошедших) и отдаёт
 * текст ячеек. Цвета и начертание выбирает {@link ForecastCellRenderer} по самой строке. Список строит главное окно
 * из {@code PlanDocument.visibleRows()} при каждом изменении плана или вида.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
public final class ForecastTableModel extends AbstractTableModel {

    /** Колонка «Дата». */
    public static final int COL_DATE = 0;
    /** Колонка «День». */
    public static final int COL_DAY = 1;
    /** Колонка «Операция». */
    public static final int COL_TITLE = 2;
    /** Колонка «Категория». */
    public static final int COL_CATEGORY = 3;
    /** Колонка «Доход». */
    public static final int COL_INCOME = 4;
    /** Колонка «Расход». */
    public static final int COL_EXPENSE = 5;
    /** Колонка «Баланс». */
    public static final int COL_BALANCE = 6;
    /** Колонка «Отметки». */
    public static final int COL_MARKS = 7;

    private static final String[] COLUMNS = {"Дата", "День", "Операция", "Категория", "Доход", "Расход", "Баланс", "Отметки"};

    private List<TableItem> items = List.of();
    private String currency = "₽";
    private Money cushion = Money.ZERO;

    /** Создаёт пустую модель. */
    public ForecastTableModel() {
    }

    /**
     * Заменяет строки таблицы.
     *
     * @param newItems    строки
     * @param newCurrency валюта плана (для подсказок)
     * @param newCushion  подушка безопасности (для подсветки строк ниже неё)
     */
    public void setItems(List<TableItem> newItems, String newCurrency, Money newCushion) {
        items = List.copyOf(newItems);
        currency = Objects.requireNonNullElse(newCurrency, "₽");
        cushion = Objects.requireNonNullElse(newCushion, Money.ZERO);
        fireTableDataChanged();
    }

    /**
     * Строка модели.
     *
     * @param modelRow индекс строки модели
     * @return строка
     */
    public TableItem itemAt(int modelRow) {
        return items.get(modelRow);
    }

    /**
     * Все строки модели.
     *
     * @return неизменяемый список
     */
    public List<TableItem> items() {
        return items;
    }

    /**
     * Индекс строки по идентификатору.
     *
     * @param rowId идентификатор строки
     * @return индекс или {@code -1}
     */
    public int indexOf(String rowId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).rowId().equals(rowId)) {
                return i;
            }
        }
        return -1;
    }

    /** @return валюта плана */
    public String currency() {
        return currency;
    }

    /** @return подушка безопасности плана */
    public Money cushion() {
        return cushion;
    }

    /**
     * Число строк таблицы: события, итоги месяцев и заголовок «Прошедшие события».
     *
     * @return число строк
     */
    @Override
    public int getRowCount() {
        return items.size();
    }

    /**
     * Число колонок: Дата, День, Операция, Категория, Доход, Расход, Баланс, Отметки.
     *
     * @return число колонок
     */
    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    /**
     * Заголовок колонки на русском.
     *
     * @param column номер колонки модели
     * @return заголовок
     */
    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    /**
     * Класс значений колонки: все значения — готовый текст (суммы уже отформатированы), вид задаёт отрисовщик.
     *
     * @param columnIndex номер колонки модели
     * @return {@code String.class}
     */
    @Override
    public Class<?> getColumnClass(int columnIndex) {
        return String.class;
    }

    /**
     * Текст ячейки: для события — его поля, для итога месяца — доход, расход и баланс месяца, для заголовка
     * прошедших событий — их число.
     *
     * @param rowIndex    строка модели
     * @param columnIndex колонка модели
     * @return отображаемый текст
     */
    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        TableItem item = items.get(rowIndex);
        return switch (item.type()) {
            case ROW -> rowValue(item.row(), columnIndex);
            case MONTH_TOTAL -> switch (columnIndex) {
                case COL_TITLE -> "Итог: " + DateFormats.monthTitle(item.month());
                case COL_INCOME -> item.totals().income().format();
                case COL_EXPENSE -> item.totals().expense().format();
                case COL_BALANCE -> item.totals().closingBalance().format();
                case COL_MARKS -> "итог " + item.totals().net().formatSigned();
                default -> "";
            };
            case PAST_HEADER -> columnIndex == COL_TITLE
                    ? (item.expanded() ? "▾ " : "▸ ") + "Прошедшие события: " + item.pastCount()
                    + (item.expanded() ? " - свернуть" : " - показать")
                    : "";
        };
    }

    private static String rowValue(ForecastRow row, int column) {
        return switch (column) {
            case COL_DATE -> DateFormats.ru(row.date());
            case COL_DAY -> RuText.weekdayShort(row.date().getDayOfWeek());
            case COL_TITLE -> row.title();
            case COL_CATEGORY -> row.category();
            case COL_INCOME -> row.origin() != Origin.START && row.isIncome() && !row.flags().skipped()
                    ? row.amount().abs().format() : skippedAmount(row, true);
            case COL_EXPENSE -> row.origin() != Origin.START && row.isExpense() && !row.flags().skipped()
                    ? row.amount().abs().format() : skippedAmount(row, false);
            case COL_BALANCE -> row.balanceAfter().format();
            case COL_MARKS -> marks(row);
            default -> "";
        };
    }

    /** У пропущенного события сумма нулевая: показываем прочерк в колонке его типа. */
    private static String skippedAmount(ForecastRow row, boolean incomeColumn) {
        if (row.flags().skipped() && row.origin() != Origin.START && row.isIncome() == incomeColumn) {
            return "-";
        }
        return "";
    }

    /**
     * Значки отметок строки, как в JavaFX- и Web-клиентах: {@code ✎} сумма изменена, {@code →} перенесено,
     * {@code ⇄} сдвиг с выходного, {@code ≡} разовая операция, {@code ✕} пропущено, {@code Δ} «что-если».
     *
     * @param row событие прогноза
     * @return строка значков (может быть пустой)
     */
    public static String marks(ForecastRow row) {
        List<String> marks = new ArrayList<>();
        if (row.flags().amountChanged()) {
            marks.add("✎");
        }
        if (row.flags().moved()) {
            marks.add("→");
        }
        if (row.flags().shifted()) {
            marks.add("⇄");
        }
        if (row.origin() == Origin.ONE_TIME) {
            marks.add("≡");
        }
        if (row.flags().skipped()) {
            marks.add("✕");
        }
        if (row.flags().whatIf() || row.origin() == Origin.WHAT_IF) {
            marks.add("Δ");
        }
        return String.join(" ", marks);
    }

    /**
     * Словесное пояснение отметок для подсказки ячейки.
     *
     * @param row событие прогноза
     * @return пояснение или пустая строка
     */
    public static String marksExplained(ForecastRow row) {
        List<String> text = new ArrayList<>();
        if (row.flags().amountChanged()) {
            text.add("✎ сумма изменена корректировкой");
        }
        if (row.flags().moved()) {
            text.add("→ перенесено с " + DateFormats.ru(row.originalDate()));
        }
        if (row.flags().shifted()) {
            text.add("⇄ сдвинуто с выходного " + DateFormats.ru(row.originalDate()));
        }
        if (row.origin() == Origin.ONE_TIME) {
            text.add("≡ разовая операция");
        }
        if (row.flags().skipped()) {
            text.add("✕ событие пропущено");
        }
        if (row.flags().whatIf() || row.origin() == Origin.WHAT_IF) {
            text.add("Δ сумма пересчитана режимом «что-если»");
        }
        if (row.flags().past()) {
            text.add("событие уже прошло");
        }
        return String.join("\n", text);
    }
}
