package ru.cashprediction.core.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Состояние главного окна в снимке: геометрия, открытый план, вид, период, фильтры, выделение и «что-если».
 *
 * <p>Строковые значения хранятся в канонических формах, общих для трёх клиентов: вид — ключ
 * ({@code TABLE}/{@code CHART}), период — ключ ({@code 12m} и т. п.), выделенная строка —
 * {@code OccurrenceKey.asRowId()} вида {@code r2@2026-10-01}. Отсутствующее значение — пустая
 * строка, а не {@code null}: так снимок одинаково записывается в JSON, XML и Markdown.</p>
 *
 * <p><b>Дополнительные ключи карты фильтров</b> (спецификация интерфейса v2, изменение 6): кроме флажков меню
 * «Вид» карта хранит {@link #FILTER_PAST_EXPANDED} (раскрыта ли группа «Прошедшие события»),
 * {@link #FILTER_WHAT_IF_INCOME} («Доходы −10 %») и {@link #FILTER_WHAT_IF_EXPENSE} («Расходы +10 %»).
 * Старые снимки без этих ключей читаются как «ключ не задан» — значение по умолчанию.</p>
 *
 * <p><b>Поле {@link #whatIfExtra()}</b> добавлено к схеме 1 необязательно: снимки, записанные до его появления,
 * читаются с пустой строкой, а кодеки пишут его только когда оно не пусто, поэтому старые файлы
 * и эталонные образцы не меняются ни на байт.</p>
 *
 * <p>Запись неизменяема и потокобезопасна: карта фильтров копируется в неизменяемую
 * {@link LinkedHashMap}, порядок фильтров сохраняется (он же порядок пунктов меню).</p>
 *
 * @param bounds        положение и размер окна; {@code null}, если неизвестно (web-клиент)
 * @param maximized     развёрнуто ли окно на весь экран
 * @param view          вид: ключ {@code TABLE} или {@code CHART}; пустая строка — по умолчанию
 * @param planPath      путь к файлу плана относительно CashMemory; пустая строка — план не открыт
 * @param period        ключ периода отображения; пустая строка — по умолчанию
 * @param filters       флажки фильтров таблицы и дополнительные ключи, например {@code showIncome → true}
 * @param filterText    текст строки поиска
 * @param selectedRowId идентификатор выделенной строки прогноза; пустая строка — ничего не выделено
 * @param whatIfExtra   дополнительная экономия «что-если» в месяц в канонической форме суммы
 *                      ({@code Money.formatPlain()}, например {@code 5000,00}); пустая строка — не задана
 */
public record MainWindowState(WindowBounds bounds, boolean maximized, String view, String planPath, String period,
                              Map<String, Boolean> filters, String filterText, String selectedRowId,
                              String whatIfExtra) {

    /** Ключ карты фильтров: раскрыта ли группа «Прошедшие события» таблицы (спецификация §5.2). */
    public static final String FILTER_PAST_EXPANDED = "pastExpanded";
    /** Ключ карты фильтров: включён ли флажок «что-если» «Доходы −10 %» (спецификация §3.4). */
    public static final String FILTER_WHAT_IF_INCOME = "whatIfIncome";
    /** Ключ карты фильтров: включён ли флажок «что-если» «Расходы +10 %» (спецификация §3.4). */
    public static final String FILTER_WHAT_IF_EXPENSE = "whatIfExpense";

    /** Копирует карту фильтров и заменяет {@code null}-строки пустыми. */
    public MainWindowState {
        view = Objects.requireNonNullElse(view, "");
        planPath = Objects.requireNonNullElse(planPath, "");
        period = Objects.requireNonNullElse(period, "");
        filterText = Objects.requireNonNullElse(filterText, "");
        selectedRowId = Objects.requireNonNullElse(selectedRowId, "");
        whatIfExtra = Objects.requireNonNullElse(whatIfExtra, "");
        filters = copyFilters(filters);
    }

    /**
     * Прежний конструктор из восьми полей (до появления {@code whatIfExtra}); сохранён для совместимости
     * с клиентами, которые ещё не знают о «что-если» в снимке.
     *
     * @param bounds        положение и размер окна или {@code null}
     * @param maximized     развёрнуто ли окно
     * @param view          ключ вида
     * @param planPath      путь к плану относительно CashMemory
     * @param period        ключ периода
     * @param filters       флажки фильтров
     * @param filterText    текст строки поиска
     * @param selectedRowId идентификатор выделенной строки
     */
    public MainWindowState(WindowBounds bounds, boolean maximized, String view, String planPath, String period,
                           Map<String, Boolean> filters, String filterText, String selectedRowId) {
        this(bounds, maximized, view, planPath, period, filters, filterText, selectedRowId, "");
    }

    /**
     * Главное окно без сохранённых настроек: всё по умолчанию.
     *
     * @return пустое состояние
     */
    public static MainWindowState empty() {
        return new MainWindowState(null, false, "", "", "", Map.of(), "", "", "");
    }

    /**
     * Возвращает копию с другим выделением строки.
     *
     * @param rowId идентификатор строки прогноза или пустая строка
     * @return новое состояние
     */
    public MainWindowState withSelectedRowId(String rowId) {
        return new MainWindowState(bounds, maximized, view, planPath, period, filters, filterText, rowId, whatIfExtra);
    }

    /**
     * Возвращает копию с другим планом.
     *
     * @param path путь к файлу плана относительно CashMemory
     * @return новое состояние
     */
    public MainWindowState withPlanPath(String path) {
        return new MainWindowState(bounds, maximized, view, path, period, filters, filterText, selectedRowId, whatIfExtra);
    }

    /**
     * Возвращает копию с другой дополнительной экономией «что-если».
     *
     * @param extra каноническая сумма ({@code 5000,00}) или пустая строка
     * @return новое состояние
     */
    public MainWindowState withWhatIfExtra(String extra) {
        return new MainWindowState(bounds, maximized, view, planPath, period, filters, filterText, selectedRowId, extra);
    }

    /**
     * Значение флажка карты фильтров с запасным значением для старых снимков, где ключа нет.
     *
     * @param key          ключ, например {@link #FILTER_PAST_EXPANDED}
     * @param defaultValue значение, если ключ не записан
     * @return записанное значение или {@code defaultValue}
     */
    public boolean filter(String key, boolean defaultValue) {
        return filters.getOrDefault(key, defaultValue);
    }

    private static Map<String, Boolean> copyFilters(Map<String, Boolean> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Boolean> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "ключ фильтра"),
                Objects.requireNonNull(value, "значение фильтра")));
        return Collections.unmodifiableMap(copy);
    }
}
