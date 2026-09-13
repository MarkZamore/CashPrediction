package ru.cashprediction.core.session;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Состояние главного окна в снимке: геометрия, открытый план, вид, период, фильтры и выделение.
 *
 * <p>Строковые значения хранятся в канонических формах, общих для трёх клиентов: вид — ключ
 * ({@code TABLE}/{@code CHART}), период — ключ ({@code 12m} и т. п.), выделенная строка —
 * {@code OccurrenceKey.asRowId()} вида {@code r2@2026-10-01}. Отсутствующее значение — пустая
 * строка, а не {@code null}: так снимок одинаково записывается в JSON, XML и Markdown.</p>
 *
 * <p>Запись неизменяема и потокобезопасна: карта фильтров копируется в неизменяемую
 * {@link LinkedHashMap}, порядок фильтров сохраняется (он же порядок пунктов меню).</p>
 *
 * @param bounds        положение и размер окна; {@code null}, если неизвестно (web-клиент)
 * @param maximized     развёрнуто ли окно на весь экран
 * @param view          вид: ключ {@code TABLE} или {@code CHART}; пустая строка — по умолчанию
 * @param planPath      путь к файлу плана относительно CashMemory; пустая строка — план не открыт
 * @param period        ключ периода отображения; пустая строка — по умолчанию
 * @param filters       флажки фильтров таблицы, например {@code showIncome → true}
 * @param filterText    текст строки поиска
 * @param selectedRowId идентификатор выделенной строки прогноза; пустая строка — ничего не выделено
 */
public record MainWindowState(WindowBounds bounds, boolean maximized, String view, String planPath, String period,
                              Map<String, Boolean> filters, String filterText, String selectedRowId) {

    /** Копирует карту фильтров и заменяет {@code null}-строки пустыми. */
    public MainWindowState {
        view = Objects.requireNonNullElse(view, "");
        planPath = Objects.requireNonNullElse(planPath, "");
        period = Objects.requireNonNullElse(period, "");
        filterText = Objects.requireNonNullElse(filterText, "");
        selectedRowId = Objects.requireNonNullElse(selectedRowId, "");
        filters = copyFilters(filters);
    }

    /**
     * Главное окно без сохранённых настроек: всё по умолчанию.
     *
     * @return пустое состояние
     */
    public static MainWindowState empty() {
        return new MainWindowState(null, false, "", "", "", Map.of(), "", "");
    }

    /**
     * Возвращает копию с другим выделением строки.
     *
     * @param rowId идентификатор строки прогноза или пустая строка
     * @return новое состояние
     */
    public MainWindowState withSelectedRowId(String rowId) {
        return new MainWindowState(bounds, maximized, view, planPath, period, filters, filterText, rowId);
    }

    /**
     * Возвращает копию с другим планом.
     *
     * @param path путь к файлу плана относительно CashMemory
     * @return новое состояние
     */
    public MainWindowState withPlanPath(String path) {
        return new MainWindowState(bounds, maximized, view, path, period, filters, filterText, selectedRowId);
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
