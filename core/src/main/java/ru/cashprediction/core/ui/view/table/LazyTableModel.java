package ru.cashprediction.core.ui.view.table;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.MonthTotals;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.recurrence.OccurrenceGenerator;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Реализация {@link TableModel} над прогнозом (спецификация v2, §5.2; архитектура §3.4).
 *
 * <p><b>Какие строки видны.</b> Строки прогноза от начала плана до {@code view.periodEnd(plan, anchor)}, прошедшие
 * фильтры вида ({@code ViewState.accepts}). Первая строка - START (всегда, прошедшей не бывает). Перед первой
 * прошедшей строкой события (дата раньше {@code state.today()}) стоит PAST_HEADER с числом прошедших строк событий;
 * при свёрнутой группе ({@code state.pastExpanded() == false}, так по умолчанию) прошедшие строки не показываются.
 * При флажке «Итоги по месяцам» после последней видимой строки события каждого месяца стоит MONTH_TOTAL; START в
 * итоги не входит; итог, чья последняя строка скрыта в свёрнутой группе, не показывается, а при раскрытой группе он
 * стоит внутри неё и оформлен как прошедший.</p>
 *
 * <p><b>Пустые состояния</b> (при них {@link #rowCount()} = 0, по порядку проверки): прогноз не рассчитан -
 * {@code table.empty.error}; в плане нет ни одного правила и ни одной разовой операции - {@code table.empty.newPlan}
 * с кнопками «Добавить доход…», «Добавить расход…», «Открыть пример»; после фильтров, периода и флажков не осталось
 * ни одной строки события (считая скрытые в свёрнутой группе) - {@code table.empty.filtered} и кнопка «Очистить
 * фильтр», если фильтр не пуст.</p>
 *
 * <p><b>Идентификаторы строк.</b> Строки событий - {@link ForecastRow#rowId()} ({@code start}, {@code r1@2026-10-05},
 * {@code t1}, {@code whatif@2026-10-31}); итог месяца - {@code total@2026-10} ({@link #totalRowId(YearMonth)});
 * группа прошедших - {@value #PAST_HEADER_ROW_ID}. Оба служебных id не совпадают ни с одной строкой прогноза:
 * id правил и разовых операций не содержат «@», а суффикс строки правила - полная дата.</p>
 *
 * <p><b>Оформление</b> ({@link TableRowView}): фон без учёта выделения - баланс &lt; 0 → {@code negative.bg}, ниже
 * подушки → {@code cushion.bg}, иначе {@code total.bg} у итога, {@code bg.alt} у группы; текст прошедшей строки -
 * {@code text.past} без цветов сумм; «Доход» {@code income}, «Расход» {@code expense}, «Баланс» {@code expense} при
 * минусе; у пропущенного события зачёркнуты название и сумма («пропущено»); START, WHAT_IF и группа - курсив, итог - жирный.
 * Итоговое оформление ячейки: цвет {@code cellStyle.text}, иначе {@code rowStyle.text}; жирность и курсив - из
 * {@code cellStyle}, если он есть, иначе из строки; жирность дополнительно включает {@link ColumnSpec#bold()}.
 * Отметки через пробел в порядке ✎ → ⇄ ≡ ✕ Δ.</p>
 *
 * <p><b>Устройство.</b> При построении - только массив кодов видимых строк (индекс строки прогноза, группа или итог
 * месяца) и обратный массив «строка прогноза → видимый индекс»; тексты не создаются. Готовые {@link TableRowView}
 * живут в LRU-кэше на {@value #CACHE_SIZE} строк. Бюджет: индекс 200 000 строк строится быстрее 300 мс, строка - в
 * среднем быстрее 1 мс.</p>
 *
 * <p>Класс неизменяем снаружи (кэш синхронизирован), потокобезопасен.</p>
 */
public final class LazyTableModel implements TableModel {

    /** Колонка «Дата». */
    public static final String COLUMN_DATE = "date";
    /** Колонка «День». */
    public static final String COLUMN_DAY = "day";
    /** Колонка «Операция». */
    public static final String COLUMN_TITLE = "title";
    /** Колонка «Категория». */
    public static final String COLUMN_CATEGORY = "category";
    /** Колонка «Доход». */
    public static final String COLUMN_INCOME = "income";
    /** Колонка «Расход». */
    public static final String COLUMN_EXPENSE = "expense";
    /** Колонка «Баланс». */
    public static final String COLUMN_BALANCE = "balance";
    /** Колонка «Отметки». */
    public static final String COLUMN_MARKS = "marks";

    /** Идентификатор строки группы «Прошедшие события». */
    public static final String PAST_HEADER_ROW_ID = "past@group";

    /** Начало идентификатора итога месяца: {@code total@2026-10}. */
    public static final String TOTAL_ROW_ID_PREFIX = "total@";

    /** Сколько первых колонок объединяет строка группы «Прошедшие события» («Дата…Категория»). */
    public static final int PAST_HEADER_SPAN = 4;

    /** Ёмкость LRU-кэша готовых строк. */
    public static final int CACHE_SIZE = 2000;

    /** Код строки группы «Прошедшие события» в массиве видимых строк. */
    private static final int CODE_PAST_HEADER = -1;
    /** Код итога месяца: {@code CODE_TOTAL_BASE - ключ месяца}. */
    private static final int CODE_TOTAL_BASE = -2;
    /** Длина суффикса месяца в id итога: «2026-10». */
    private static final int MONTH_SUFFIX_LENGTH = 7;

    private final long revision;
    private final List<ColumnSpec> columns;
    private final String selectedRowId;
    private final Placeholder placeholder;
    private final Plan plan;
    private final List<ForecastRow> forecastRows;
    private final Map<YearMonth, MonthTotals> monthTotals;
    private final TableRows rows;
    private final boolean pastExpanded;
    private final int pastCount;
    private final int[] codes;
    private final int[] visibleOf;
    private final int pastHeaderIndex;
    private final Map<Integer, Integer> totalIndexByMonth;
    private final String scrollToRowId;

    /** LRU-кэш готовых строк по видимому индексу; доступ только под его монитором. */
    private final Map<Integer, TableRowView> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, TableRowView> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /**
     * Модель пустого состояния.
     *
     * @param revision      ревизия
     * @param columns       колонки
     * @param selectedRowId выделенная строка
     * @param placeholder   пустое состояние
     */
    private LazyTableModel(long revision, List<ColumnSpec> columns, String selectedRowId, Placeholder placeholder) {
        this.revision = revision;
        this.columns = columns;
        this.selectedRowId = selectedRowId;
        this.placeholder = placeholder;
        this.plan = null;
        this.forecastRows = List.of();
        this.monthTotals = Map.of();
        this.rows = null;
        this.pastExpanded = false;
        this.pastCount = 0;
        this.codes = new int[0];
        this.visibleOf = new int[0];
        this.pastHeaderIndex = -1;
        this.totalIndexByMonth = Map.of();
        this.scrollToRowId = "";
    }

    /**
     * Модель со строками.
     *
     * @param revision      ревизия
     * @param columns       колонки
     * @param state         состояние
     * @param forecast      прогноз
     * @param layout        раскладка видимых строк
     */
    private LazyTableModel(long revision, List<ColumnSpec> columns, AppState state, Forecast forecast, Layout layout) {
        this.revision = revision;
        this.columns = columns;
        this.selectedRowId = state.selectedRowId();
        this.placeholder = null;
        this.plan = forecast.plan();
        this.forecastRows = forecast.rows();
        this.monthTotals = forecast.summary().byMonth();
        this.rows = new TableRows(forecast.plan(), state.today());
        this.pastExpanded = state.pastExpanded();
        this.pastCount = layout.pastCount();
        this.codes = layout.codes();
        this.visibleOf = layout.visibleOf();
        this.pastHeaderIndex = layout.pastHeaderIndex();
        this.totalIndexByMonth = layout.totalIndexByMonth();
        this.scrollToRowId = layout.scrollToRowId();
    }

    /**
     * Строит модель для текущего состояния.
     *
     * @param state    состояние приложения (план, прогноз, вид, выделение, группа прошедших, сегодня)
     * @param revision номер ревизии модели
     * @return модель таблицы
     */
    public static TableModel build(AppState state, long revision) {
        Objects.requireNonNull(state, "state");
        DocumentView document = state.document();
        List<ColumnSpec> columns = TableRows.columns();
        Forecast forecast = document.forecast();
        if (forecast == null) {
            return new LazyTableModel(revision, columns, state.selectedRowId(), new Placeholder(
                    Placeholder.Kind.FORECAST_ERROR, UiText.get("table.empty.error", document.forecastError()),
                    ColorToken.EXPENSE, List.of()));
        }
        Plan plan = document.plan();
        if (plan.rules().isEmpty() && plan.oneTimes().isEmpty()) {
            return new LazyTableModel(revision, columns, state.selectedRowId(), new Placeholder(
                    Placeholder.Kind.NEW_PLAN, UiText.get("table.empty.newPlan", plan.name()), ColorToken.TEXT_MUTED,
                    List.of(new Placeholder.Button("empty.addIncome", CommandId.EDIT_ADD_INCOME,
                                    UiText.get("table.empty.addIncome")),
                            new Placeholder.Button("empty.addExpense", CommandId.EDIT_ADD_EXPENSE,
                                    UiText.get("table.empty.addExpense")),
                            new Placeholder.Button("empty.sample", CommandId.FILE_SAMPLE,
                                    UiText.get("table.empty.sample")))));
        }
        Layout layout = layout(state, forecast);
        if (layout == null) {
            List<Placeholder.Button> buttons = state.view().filterText().isBlank() ? List.of()
                    : List.of(new Placeholder.Button("empty.clearFilter", CommandId.FILTER_CLEAR,
                            UiText.get("table.empty.clearFilter")));
            return new LazyTableModel(revision, columns, state.selectedRowId(), new Placeholder(
                    Placeholder.Kind.FILTERED, UiText.get("table.empty.filtered"), ColorToken.TEXT_MUTED, buttons));
        }
        return new LazyTableModel(revision, columns, state, forecast, layout);
    }

    /**
     * Идентификатор строки итога месяца.
     *
     * @param month месяц
     * @return {@code total@2026-10}
     */
    public static String totalRowId(YearMonth month) {
        return TOTAL_ROW_ID_PREFIX + month;
    }

    @Override
    public long revision() {
        return revision;
    }

    @Override
    public List<ColumnSpec> columns() {
        return columns;
    }

    @Override
    public int rowCount() {
        return codes.length;
    }

    @Override
    public TableRowView row(int index) {
        Objects.checkIndex(index, codes.length);
        synchronized (cache) {
            TableRowView cached = cache.get(index);
            if (cached != null) {
                return cached;
            }
        }
        // Строка собирается вне монитора: два потока в худшем случае соберут одну строку дважды, а равные записи
        // взаимозаменяемы.
        TableRowView built = buildRow(index);
        synchronized (cache) {
            cache.put(index, built);
        }
        return built;
    }

    @Override
    public String tooltip(int index, String columnId) {
        Objects.checkIndex(index, codes.length);
        int code = codes[index];
        if (code >= 0) {
            return rows.eventTooltip(forecastRows.get(code), columnId);
        }
        if (code == CODE_PAST_HEADER) {
            return TableRows.pastHeaderTooltip(pastExpanded);
        }
        YearMonth month = monthOf(CODE_TOTAL_BASE - code);
        return rows.totalTooltip(month, totalsOf(month));
    }

    @Override
    public int indexOf(String rowId) {
        if (rowId == null || rowId.isEmpty() || codes.length == 0) {
            return -1;
        }
        if (rowId.equals(PAST_HEADER_ROW_ID)) {
            return pastHeaderIndex;
        }
        if (rowId.startsWith(TOTAL_ROW_ID_PREFIX)
                && rowId.length() == TOTAL_ROW_ID_PREFIX.length() + MONTH_SUFFIX_LENGTH) {
            try {
                YearMonth month = YearMonth.parse(rowId.substring(TOTAL_ROW_ID_PREFIX.length()));
                return totalIndexByMonth.getOrDefault(monthKey(month.getYear(), month.getMonthValue()), -1);
            } catch (DateTimeParseException e) {
                // Не месяц: это может быть событие правила с id «total», ищем дальше как обычную строку.
            }
        }
        int forecastIndex = forecastRowIndex(rowId);
        return forecastIndex < 0 ? -1 : visibleOf[forecastIndex];
    }

    @Override
    public String selectedRowId() {
        return selectedRowId;
    }

    @Override
    public String scrollToRowId() {
        return scrollToRowId;
    }

    @Override
    public Placeholder placeholder() {
        return placeholder;
    }

    // ------------------------------------------------------------------ построение индекса

    /**
     * Раскладка видимых строк.
     *
     * @param codes             коды видимых строк: индекс строки прогноза, группа или итог месяца
     * @param visibleOf         видимый индекс каждой строки прогноза или -1
     * @param pastCount         число прошедших строк событий
     * @param pastHeaderIndex   индекс строки группы или -1
     * @param totalIndexByMonth видимый индекс итога по ключу месяца
     * @param scrollToRowId     первая строка с датой не раньше сегодня или пустая строка
     */
    private record Layout(int[] codes, int[] visibleOf, int pastCount, int pastHeaderIndex,
                          Map<Integer, Integer> totalIndexByMonth, String scrollToRowId) {
    }

    /** @return раскладка или {@code null}, если не видно ни одной строки события */
    private static Layout layout(AppState state, Forecast forecast) {
        ViewState view = state.view();
        LocalDate today = state.today();
        List<ForecastRow> all = forecast.rows();
        LocalDate periodEnd = view.periodEnd(forecast.plan(), forecast.anchor());
        int n = all.size();

        // Проход 1: строки событий, прошедшие фильтры и период. Строки прогноза отсортированы по дате, поэтому
        // прошедшие образуют начало списка, а после конца периода можно остановиться.
        int[] accepted = new int[n];
        int acceptedCount = 0;
        int pastCount = 0;
        for (int i = 1; i < n; i++) {
            ForecastRow row = all.get(i);
            if (row.date().isAfter(periodEnd)) {
                break;
            }
            if (row.origin() == Origin.START || !view.accepts(row)) {
                continue;
            }
            accepted[acceptedCount++] = i;
            if (row.date().isBefore(today)) {
                pastCount++;
            }
        }
        if (acceptedCount == 0) {
            return null;
        }

        // Проход 2: коды видимых строк. Итог месяца идёт сразу за последней строкой события своего месяца и виден,
        // только если видна она сама.
        boolean expanded = state.pastExpanded();
        boolean totals = view.monthTotals();
        int[] codes = new int[2 + acceptedCount * 2];
        int[] visibleOf = new int[n];
        Arrays.fill(visibleOf, -1);
        Map<Integer, Integer> totalIndex = new HashMap<>();
        int size = 0;
        visibleOf[0] = size;
        codes[size++] = 0;
        int pastHeaderIndex = -1;
        String scrollTo = forecast.dailyStart().isBefore(today) ? "" : ForecastRow.START_ROW_ID;
        int nextMonth = monthKey(all.get(accepted[0]).date());
        for (int p = 0; p < acceptedCount; p++) {
            int index = accepted[p];
            int month = nextMonth;
            nextMonth = p + 1 < acceptedCount ? monthKey(all.get(accepted[p + 1]).date()) : Integer.MIN_VALUE;
            boolean past = p < pastCount;
            if (p == 0 && pastCount > 0) {
                pastHeaderIndex = size;
                codes[size++] = CODE_PAST_HEADER;
            }
            boolean shown = !past || expanded;
            if (!shown) {
                continue;
            }
            visibleOf[index] = size;
            codes[size++] = index;
            if (!past && scrollTo.isEmpty()) {
                scrollTo = all.get(index).rowId();
            }
            if (totals && nextMonth != month) {
                totalIndex.put(month, size);
                codes[size++] = CODE_TOTAL_BASE - month;
            }
        }
        return new Layout(Arrays.copyOf(codes, size), visibleOf, pastCount, pastHeaderIndex, totalIndex, scrollTo);
    }

    // ------------------------------------------------------------------ строки

    /** @return готовая строка по видимому индексу */
    private TableRowView buildRow(int index) {
        int code = codes[index];
        if (code >= 0) {
            return rows.event(forecastRows.get(code));
        }
        if (code == CODE_PAST_HEADER) {
            return TableRows.pastHeader(pastCount, pastExpanded);
        }
        YearMonth month = monthOf(CODE_TOTAL_BASE - code);
        // Итог стоит сразу за последней строкой своего месяца: прошедшая она - итог внутри раскрытой группы.
        int previous = codes[index - 1];
        boolean past = previous > 0 && rows.isPast(forecastRows.get(previous));
        return rows.total(month, totalsOf(month), past);
    }

    /** @return итоги месяца из сводки прогноза (нулевые, если месяца там нет) */
    private MonthTotals totalsOf(YearMonth month) {
        MonthTotals totals = monthTotals.get(month);
        return totals != null ? totals : new MonthTotals(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO);
    }

    // ------------------------------------------------------------------ поиск строки прогноза

    /**
     * Индекс строки прогноза по id без полного словаря: дата берётся из id (событие правила - номинальная дата ±
     * сдвиг с выходных или новая дата корректировки; «что-если» - дата из id; разовая операция - дата из плана), а
     * строка ищется двоичным поиском по дате.
     *
     * @return индекс строки прогноза или -1
     */
    private int forecastRowIndex(String rowId) {
        if (rowId.equals(ForecastRow.START_ROW_ID)) {
            return 0;
        }
        int at = rowId.lastIndexOf('@');
        if (at < 0) {
            for (OneTimeTransaction tx : plan.oneTimes()) {
                if (tx.id().value().equals(rowId)) {
                    return findOnDate(rowId, tx.date());
                }
            }
            return -1;
        }
        LocalDate nominal;
        try {
            nominal = LocalDate.parse(rowId.substring(at + 1));
        } catch (DateTimeParseException e) {
            return -1;
        }
        for (int shift = -OccurrenceGenerator.MAX_WEEKEND_SHIFT_DAYS; shift <= OccurrenceGenerator.MAX_WEEKEND_SHIFT_DAYS;
             shift++) {
            int found = findOnDate(rowId, nominal.plusDays(shift));
            if (found >= 0) {
                return found;
            }
        }
        Optional<LocalDate> moved = movedDate(rowId.substring(0, at), nominal);
        return moved.map(date -> findOnDate(rowId, date)).orElse(-1);
    }

    /** @return новая дата события правила из корректировки плана, если она есть */
    private Optional<LocalDate> movedDate(String ruleId, LocalDate nominal) {
        if (ruleId.isBlank() || ruleId.contains("|") || ruleId.contains("@")) {
            return Optional.empty();
        }
        return plan.findAdjustment(new OccurrenceKey(new RuleId(ruleId), nominal))
                .flatMap(adjustment -> adjustment.action().newDate());
    }

    /** @return индекс строки прогноза с этим id на эту дату или -1 */
    private int findOnDate(String rowId, LocalDate date) {
        int lo = 0;
        int hi = forecastRows.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (forecastRows.get(mid).date().isBefore(date)) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        for (int i = lo; i < forecastRows.size() && forecastRows.get(i).date().equals(date); i++) {
            if (forecastRows.get(i).rowId().equals(rowId)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ ключ месяца

    /** @return ключ месяца даты: год × 12 + номер месяца − 1 */
    private static int monthKey(LocalDate date) {
        return monthKey(date.getYear(), date.getMonthValue());
    }

    /** @return ключ месяца */
    private static int monthKey(int year, int month) {
        return year * 12 + month - 1;
    }

    /** @return месяц по ключу */
    private static YearMonth monthOf(int key) {
        return YearMonth.of(Math.floorDiv(key, 12), Math.floorMod(key, 12) + 1);
    }
}
