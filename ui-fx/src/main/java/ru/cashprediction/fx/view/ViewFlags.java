package ru.cashprediction.fx.view;

import ru.cashprediction.core.document.ViewState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Булевы флаги вида по их каноническим ключам снимка сессии: {@code showIncome}, {@code showExpense},
 * {@code showOneTime}, {@code showSkipped}, {@code monthTotals}, {@code chartMarkers}, {@code chartBars},
 * {@code summaryPanel}.
 *
 * <p>Одно место соответствия «ключ → компонент {@link ViewState}»: им пользуются меню «Вид», снимок главного окна,
 * восстановление и команда самотеста {@code filter}. Класс без состояния, потокобезопасен.</p>
 */
public final class ViewFlags {

    /** Ключ: показывать доходы. */
    public static final String SHOW_INCOME = "showIncome";
    /** Ключ: показывать расходы. */
    public static final String SHOW_EXPENSE = "showExpense";
    /** Ключ: показывать разовые операции. */
    public static final String SHOW_ONE_TIME = "showOneTime";
    /** Ключ: показывать пропущенные события. */
    public static final String SHOW_SKIPPED = "showSkipped";
    /** Ключ: строки итогов по месяцам. */
    public static final String MONTH_TOTALS = "monthTotals";
    /** Ключ: маркеры событий на графике. */
    public static final String CHART_MARKERS = "chartMarkers";
    /** Ключ: столбцы итогов месяцев на графике. */
    public static final String CHART_BARS = "chartBars";
    /** Ключ: панель сводки. */
    public static final String SUMMARY_PANEL = "summaryPanel";

    /** Все ключи в порядке меню «Вид». */
    public static final List<String> KEYS = List.of(SHOW_INCOME, SHOW_EXPENSE, SHOW_ONE_TIME, SHOW_SKIPPED,
            MONTH_TOTALS, CHART_MARKERS, CHART_BARS, SUMMARY_PANEL);

    private ViewFlags() {
    }

    /**
     * Значение флага.
     *
     * @param view вид
     * @param key  ключ
     * @return значение
     * @throws IllegalArgumentException для неизвестного ключа
     */
    public static boolean get(ViewState view, String key) {
        return switch (key) {
            case SHOW_INCOME -> view.showIncome();
            case SHOW_EXPENSE -> view.showExpense();
            case SHOW_ONE_TIME -> view.showOneTime();
            case SHOW_SKIPPED -> view.showSkipped();
            case MONTH_TOTALS -> view.monthTotals();
            case CHART_MARKERS -> view.chartMarkers();
            case CHART_BARS -> view.chartBars();
            case SUMMARY_PANEL -> view.summaryPanel();
            default -> throw new IllegalArgumentException("Неизвестный флаг вида: " + key);
        };
    }

    /**
     * Вид с изменённым флагом.
     *
     * @param view  вид
     * @param key   ключ
     * @param value новое значение
     * @return новый вид
     * @throws IllegalArgumentException для неизвестного ключа
     */
    public static ViewState with(ViewState view, String key, boolean value) {
        return switch (key) {
            case SHOW_INCOME -> view.withShowIncome(value);
            case SHOW_EXPENSE -> view.withShowExpense(value);
            case SHOW_ONE_TIME -> view.withShowOneTime(value);
            case SHOW_SKIPPED -> view.withShowSkipped(value);
            case MONTH_TOTALS -> view.withMonthTotals(value);
            case CHART_MARKERS -> view.withChartMarkers(value);
            case CHART_BARS -> view.withChartBars(value);
            case SUMMARY_PANEL -> view.withSummaryPanel(value);
            default -> throw new IllegalArgumentException("Неизвестный флаг вида: " + key);
        };
    }

    /**
     * Все флаги вида картой для снимка главного окна.
     *
     * @param view вид
     * @return карта «ключ → значение» в порядке {@link #KEYS}
     */
    public static Map<String, Boolean> toMap(ViewState view) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (String key : KEYS) {
            map.put(key, get(view, key));
        }
        return map;
    }

    /**
     * Переносит флаги из карты снимка в вид; неизвестные ключи пропускаются.
     *
     * @param view вид
     * @param map  флаги из снимка
     * @return новый вид
     */
    public static ViewState apply(ViewState view, Map<String, Boolean> map) {
        ViewState result = view;
        for (String key : KEYS) {
            Boolean value = map.get(key);
            if (value != null) {
                result = with(result, key, value);
            }
        }
        return result;
    }
}
