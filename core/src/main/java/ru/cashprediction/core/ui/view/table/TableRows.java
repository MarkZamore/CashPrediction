package ru.cashprediction.core.ui.view.table;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import ru.cashprediction.core.forecast.Flags;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.MonthTotals;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Тексты, оформление и подсказки отдельных строк таблицы прогноза (спецификация v2, §5.2) для
 * {@link LazyTableModel}: модель решает, какие строки видны, а этот класс - как выглядит каждая из них.
 *
 * <p>Класс неизменяем и потокобезопасен: все поля задаются в конструкторе.</p>
 */
final class TableRows {

    /** Отметка «сумма изменена корректировкой». */
    static final String MARK_AMOUNT_CHANGED = "✎";
    /** Отметка «событие перенесено». */
    static final String MARK_MOVED = "→";
    /** Отметка «сдвиг с выходного». */
    static final String MARK_SHIFTED = "⇄";
    /** Отметка разовой операции. */
    static final String MARK_ONE_TIME = "≡";
    /** Отметка пропущенного события. */
    static final String MARK_SKIPPED = "✕";
    /** Отметка «что-если». */
    static final String MARK_WHAT_IF = "Δ";

    /** Число колонок таблицы. */
    static final int COLUMN_COUNT = 8;

    private final Plan plan;
    private final String currency;
    private final Money cushion;
    private final LocalDate today;

    /**
     * Создаёт построитель строк.
     *
     * @param plan  план, по которому построен прогноз (валюта, подушка, правила для подсказок)
     * @param today «сегодня»: строки раньше него - прошедшие
     */
    TableRows(Plan plan, LocalDate today) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.currency = plan.currency();
        this.cushion = plan.cushion();
        this.today = Objects.requireNonNull(today, "today");
    }

    /**
     * Колонки таблицы в порядке §5.2: id, заголовок, ширина, растяжение, выравнивание, жирность, подсказка заголовка.
     *
     * @return восемь колонок
     */
    static List<ColumnSpec> columns() {
        return List.of(
                new ColumnSpec(LazyTableModel.COLUMN_DATE, UiText.get("table.column.date"), 92, false,
                        ColumnSpec.Align.LEFT, false, UiText.get("table.column.date.tip")),
                new ColumnSpec(LazyTableModel.COLUMN_DAY, UiText.get("table.column.day"), 44, false,
                        ColumnSpec.Align.CENTER, false, UiText.get("table.column.day.tip")),
                new ColumnSpec(LazyTableModel.COLUMN_TITLE, UiText.get("table.column.title"), 220, true,
                        ColumnSpec.Align.LEFT, false, UiText.get("table.column.title.tip")),
                new ColumnSpec(LazyTableModel.COLUMN_CATEGORY, UiText.get("table.column.category"), 130, false,
                        ColumnSpec.Align.LEFT, false, UiText.get("table.column.category.tip")),
                new ColumnSpec(LazyTableModel.COLUMN_INCOME, UiText.get("table.column.income"), 120, false,
                        ColumnSpec.Align.RIGHT, false, UiText.get("table.column.income.tip")),
                new ColumnSpec(LazyTableModel.COLUMN_EXPENSE, UiText.get("table.column.expense"), 120, false,
                        ColumnSpec.Align.RIGHT, false, UiText.get("table.column.expense.tip")),
                new ColumnSpec(LazyTableModel.COLUMN_BALANCE, UiText.get("table.column.balance"), 130, false,
                        ColumnSpec.Align.RIGHT, true, UiText.get("table.column.balance.tip")),
                new ColumnSpec(LazyTableModel.COLUMN_MARKS, UiText.get("table.column.marks"), 90, false,
                        ColumnSpec.Align.CENTER, false, UiText.get("table.column.marks.tip")));
    }

    /**
     * Прошедшая ли строка события: дата раньше «сегодня»; начальный баланс прошедшим не бывает (§5.2).
     *
     * @param row строка прогноза
     * @return {@code true} для прошедшей строки
     */
    boolean isPast(ForecastRow row) {
        return row.origin() != Origin.START && row.date().isBefore(today);
    }

    /**
     * Строка события: START, RULE, ONE_TIME или WHAT_IF.
     *
     * @param row строка прогноза
     * @return готовая строка
     */
    TableRowView event(ForecastRow row) {
        boolean past = isPast(row);
        Flags flags = row.flags();
        RowKind kind = kindOf(row.origin());
        boolean italic = kind == RowKind.START || kind == RowKind.WHAT_IF;

        String amount = flags.skipped() ? UiText.get("table.skippedAmount") : row.amount().abs().format();
        String income = row.isIncome() ? amount : "";
        String expense = row.isExpense() ? amount : "";
        List<String> cells = List.of(
                UiFormats.date(row.date()),
                UiFormats.weekdayShort(row.date()),
                titleOf(row),
                row.origin() == Origin.START ? "" : row.category(),
                income,
                expense,
                row.balanceAfter().format(),
                marks(row));

        // Прошедшая строка целиком серая: цвета сумм и баланса у неё не показываются (§5.2 «Раскраска»).
        RowStyle rowStyle = new RowStyle(background(row.balanceAfter(), null),
                past ? ColorToken.TEXT_PAST : ColorToken.TEXT_PRIMARY, false, italic);
        Map<String, CellStyle> cellStyles = new HashMap<>();
        ColorToken incomeColor = past ? null : ColorToken.INCOME;
        ColorToken expenseColor = past ? null : ColorToken.EXPENSE;
        if (flags.skipped()) {
            cellStyles.put(LazyTableModel.COLUMN_TITLE, new CellStyle(null, false, italic, true));
        }
        if (!income.isEmpty()) {
            cellStyles.put(LazyTableModel.COLUMN_INCOME, new CellStyle(incomeColor, false, italic, flags.skipped()));
        }
        if (!expense.isEmpty()) {
            cellStyles.put(LazyTableModel.COLUMN_EXPENSE, new CellStyle(expenseColor, false, italic, flags.skipped()));
        }
        if (!past && row.balanceAfter().isNegative()) {
            cellStyles.put(LazyTableModel.COLUMN_BALANCE, new CellStyle(ColorToken.EXPENSE, false, italic, false));
        }
        // Пустые записи оформления не нужны: ячейка без записи рисуется как строка.
        cellStyles.values().removeIf(style -> style.text() == null && !style.strike() && style.italic() == italic
                && !style.bold());
        boolean quickEditable = kind == RowKind.RULE && !flags.skipped();
        return new TableRowView(row.rowId(), kind, cells, rowStyle, cellStyles, 1, quickEditable);
    }

    /**
     * Строка итога месяца.
     *
     * @param month  месяц
     * @param totals итоги месяца из сводки прогноза
     * @param past   стоит ли итог внутри группы «Прошедшие события» (все его видимые строки прошедшие)
     * @return готовая строка
     */
    TableRowView total(YearMonth month, MonthTotals totals, boolean past) {
        List<String> cells = List.of(
                "",
                "",
                UiText.get("table.total", UiFormats.monthTitle(month)),
                UiText.get("table.totalCategory", totals.net().formatSigned()),
                totals.income().format(),
                totals.expense().format(),
                totals.closingBalance().format(),
                "");
        RowStyle rowStyle = new RowStyle(background(totals.closingBalance(), ColorToken.TOTAL_BG),
                past ? ColorToken.TEXT_PAST : ColorToken.TEXT_PRIMARY, true, false);
        Map<String, CellStyle> cellStyles = new HashMap<>();
        if (!past) {
            cellStyles.put(LazyTableModel.COLUMN_INCOME, new CellStyle(ColorToken.INCOME, true, false, false));
            cellStyles.put(LazyTableModel.COLUMN_EXPENSE, new CellStyle(ColorToken.EXPENSE, true, false, false));
            if (totals.closingBalance().isNegative()) {
                cellStyles.put(LazyTableModel.COLUMN_BALANCE, new CellStyle(ColorToken.EXPENSE, true, false, false));
            }
        }
        return new TableRowView(LazyTableModel.totalRowId(month), RowKind.MONTH_TOTAL, cells, rowStyle, cellStyles, 1,
                false);
    }

    /**
     * Строка группы «Прошедшие события»: текст в объединённой ячейке «Дата…Категория».
     *
     * @param count    число прошедших строк событий
     * @param expanded раскрыта ли группа
     * @return готовая строка
     */
    static TableRowView pastHeader(int count, boolean expanded) {
        List<String> cells = new ArrayList<>(COLUMN_COUNT);
        cells.add(expanded ? UiText.get("table.past.expanded", count) : UiText.get("table.past.collapsed", count));
        while (cells.size() < COLUMN_COUNT) {
            cells.add("");
        }
        RowStyle style = new RowStyle(ColorToken.BG_ALT, ColorToken.TEXT_MUTED, false, true);
        return new TableRowView(LazyTableModel.PAST_HEADER_ROW_ID, RowKind.PAST_HEADER, cells, style, Map.of(),
                LazyTableModel.PAST_HEADER_SPAN, false);
    }

    /**
     * Подсказка строки события (§5.2, строки 1-13): одна на строку, строка 13 - только в колонке суммы строки RULE,
     * которую можно быстро править.
     *
     * @param row      строка прогноза
     * @param columnId колонка под указателем
     * @return текст с переводами строк
     */
    String eventTooltip(ForecastRow row, String columnId) {
        return decoratedEventTooltip(row, columnId).text();
    }

    /** Собирает прежний текст и отмечает только добавляемые ядром служебные строки. */
    DecoratedTooltip decoratedEventTooltip(ForecastRow row, String columnId) {
        Flags flags = row.flags();
        StringJoiner text = new StringJoiner("\n");
        List<DecoratedTooltip.IconPosition> icons = new ArrayList<>();
        text.add(titleOf(row));
        switch (row.origin()) {
            case START -> text.add(UiText.get("table.tip.start"));
            case RULE -> text.add(UiText.get("table.tip.rule", recurrenceText(row)));
            case ONE_TIME -> text.add(UiText.get("table.tip.oneTime"));
            case WHAT_IF -> text.add(UiText.get("table.tip.whatIf"));
        }
        if (row.origin() == Origin.RULE && !row.originalDate().equals(row.date())) {
            text.add(UiText.get("table.tip.dates", UiFormats.date(row.originalDate()), UiFormats.date(row.date())));
        }
        if (flags.amountChanged()) {
            addServiceLine(text, icons, "table.tip.amountChanged", MARK_AMOUNT_CHANGED);
        }
        if (flags.moved()) {
            addServiceLine(text, icons, "table.tip.moved", MARK_MOVED);
        }
        if (flags.shifted()) {
            addServiceLine(text, icons, "table.tip.shifted", MARK_SHIFTED);
        }
        if (flags.skipped()) {
            addServiceLine(text, icons, "table.tip.skipped", MARK_SKIPPED);
        }
        // У строки WHAT_IF сумма не «изменена», а целиком задана режимом: это уже сказано в строке 2.
        if (flags.whatIf() && row.origin() != Origin.WHAT_IF) {
            addServiceLine(text, icons, "table.tip.whatIfAmount", MARK_WHAT_IF);
        }
        if (isPast(row)) {
            text.add(UiText.get("table.tip.past"));
        }
        if (row.origin() != Origin.START) {
            text.add(UiText.get("table.tip.amount", signedWithCurrency(row.amount())));
        }
        text.add(balanceLine(row.balanceAfter()));
        if (!row.note().isBlank()) {
            text.add(UiText.get("table.tip.note", row.note()));
        }
        String result = text.toString();
        if (row.origin() == Origin.RULE && !flags.skipped() && isAmountColumn(row, columnId)) {
            result = result + "\n\n" + UiText.get("table.tip.quickEdit");
        }
        return new DecoratedTooltip(result, icons);
    }

    /** Фиксирует позицию значка в момент добавления известной локализованной строки. */
    private static void addServiceLine(StringJoiner text, List<DecoratedTooltip.IconPosition> icons,
                                       String textKey, String iconKey) {
        String line = UiText.get(textKey);
        icons.add(new DecoratedTooltip.IconPosition(text.length() + 1, iconKey));
        text.add(line);
    }

    /**
     * Подсказка итога месяца (§5.2): месяц, доходы, расходы, итог, баланс на конец.
     *
     * @param month  месяц
     * @param totals итоги месяца
     * @return текст с переводами строк
     */
    String totalTooltip(YearMonth month, MonthTotals totals) {
        return String.join("\n",
                UiFormats.monthTitle(month),
                UiText.get("table.tip.total.income", totals.income().format(currency)),
                UiText.get("table.tip.total.expense", totals.expense().format(currency)),
                UiText.get("table.tip.total.net", signedWithCurrency(totals.net())),
                UiText.get("table.tip.total.closing", totals.closingBalance().format(currency)));
    }

    /**
     * Подсказка строки группы «Прошедшие события».
     *
     * @param expanded раскрыта ли группа
     * @return текст
     */
    static String pastHeaderTooltip(boolean expanded) {
        return expanded ? UiText.get("table.past.tip.expanded") : UiText.get("table.past.tip.collapsed");
    }

    /**
     * Текст колонки «Отметки» через пробел в порядке ✎ → ⇄ ≡ ✕ Δ (§5.2).
     *
     * @param row строка прогноза
     * @return отметки или пустая строка
     */
    static String marks(ForecastRow row) {
        Flags flags = row.flags();
        StringJoiner marks = new StringJoiner(" ");
        if (flags.amountChanged()) {
            marks.add(MARK_AMOUNT_CHANGED);
        }
        if (flags.moved()) {
            marks.add(MARK_MOVED);
        }
        if (flags.shifted()) {
            marks.add(MARK_SHIFTED);
        }
        if (row.origin() == Origin.ONE_TIME) {
            marks.add(MARK_ONE_TIME);
        }
        if (flags.skipped()) {
            marks.add(MARK_SKIPPED);
        }
        if (flags.whatIf()) {
            marks.add(MARK_WHAT_IF);
        }
        return marks.toString();
    }

    /** @return вид строки таблицы для источника строки прогноза */
    private static RowKind kindOf(Origin origin) {
        return switch (origin) {
            case START -> RowKind.START;
            case RULE -> RowKind.RULE;
            case ONE_TIME -> RowKind.ONE_TIME;
            case WHAT_IF -> RowKind.WHAT_IF;
        };
    }

    /** @return текст колонки «Операция»: у START и WHAT_IF - тексты каталога, иначе название операции */
    private static String titleOf(ForecastRow row) {
        return switch (row.origin()) {
            case START -> UiText.get("table.start");
            case WHAT_IF -> UiText.get("table.whatIfTitle");
            case RULE, ONE_TIME -> row.title();
        };
    }

    /** @return правило повтора строки RULE текстом «ежемесячно 5» или пустая строка, если правила уже нет */
    private String recurrenceText(ForecastRow row) {
        return row.ruleId() == null ? "" : plan.findRule(row.ruleId()).map(rule -> rule.recurrence().toRussian())
                .orElse("");
    }

    /** @return строка 11 подсказки: баланс после события и, если нужно, «ниже нуля» или «ниже подушки» */
    private String balanceLine(Money balance) {
        String value = balance.format(currency);
        if (balance.isNegative()) {
            return UiText.get("table.tip.balance.negative", value);
        }
        if (cushion.isPositive() && balance.isLessThan(cushion)) {
            return UiText.get("table.tip.balance.cushion", value, cushion.format(currency));
        }
        return UiText.get("table.tip.balance", value);
    }

    /** @return сумма со знаком и валютой: «+80 000,00 ₽» */
    private String signedWithCurrency(Money money) {
        return currency.isBlank() ? money.formatSigned() : money.formatSigned() + " " + currency;
    }

    /**
     * Фон строки без учёта выделения: баланс &lt; 0 → {@code negative.bg}; подушка &gt; 0 и баланс ниже неё →
     * {@code cushion.bg}; иначе фон вида строки ({@code total.bg} у итога) или фон таблицы.
     */
    private ColorToken background(Money balance, ColorToken kindBackground) {
        if (balance.isNegative()) {
            return ColorToken.NEGATIVE_BG;
        }
        if (cushion.isPositive() && balance.isLessThan(cushion)) {
            return ColorToken.CUSHION_BG;
        }
        return kindBackground;
    }

    /** @return лежит ли сумма строки в колонке {@code columnId} */
    private static boolean isAmountColumn(ForecastRow row, String columnId) {
        return row.isIncome() ? LazyTableModel.COLUMN_INCOME.equals(columnId)
                : row.isExpense() && LazyTableModel.COLUMN_EXPENSE.equals(columnId);
    }
}
