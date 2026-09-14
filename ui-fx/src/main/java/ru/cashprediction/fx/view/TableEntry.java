package ru.cashprediction.fx.view;

import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.MonthTotals;

import java.time.YearMonth;
import java.util.Objects;

/**
 * Строка таблицы прогноза: либо событие прогноза, либо итог месяца.
 *
 * <p>Итоги месяцев не входят в прогноз ядра — это представление для пункта «Вид → Итоги по месяцам»,
 * поэтому таблица хранит обёртку, а не сами {@link ForecastRow}.</p>
 *
 * @param row    событие прогноза или {@code null} для строки итога
 * @param month  месяц итога или {@code null} для события
 * @param totals итоги месяца или {@code null} для события
 */
public record TableEntry(ForecastRow row, YearMonth month, MonthTotals totals) {

    /** Префикс идентификатора строки итога месяца. */
    public static final String TOTAL_PREFIX = "total@";

    /** Проверяет, что задано ровно одно: событие или итог. */
    public TableEntry {
        if ((row == null) == (month == null || totals == null)) {
            throw new IllegalArgumentException("Строка таблицы - либо событие, либо итог месяца");
        }
    }

    /**
     * Строка события.
     *
     * @param row событие прогноза
     * @return строка таблицы
     */
    public static TableEntry of(ForecastRow row) {
        return new TableEntry(Objects.requireNonNull(row, "row"), null, null);
    }

    /**
     * Строка итога месяца.
     *
     * @param month  месяц
     * @param totals итоги
     * @return строка таблицы
     */
    public static TableEntry total(YearMonth month, MonthTotals totals) {
        return new TableEntry(null, Objects.requireNonNull(month, "month"), Objects.requireNonNull(totals, "totals"));
    }

    /**
     * Строка итога месяца?
     *
     * @return {@code true} для итога
     */
    public boolean isTotal() {
        return row == null;
    }

    /**
     * Идентификатор строки: {@code rowId()} события или {@code total@2026-10}.
     *
     * @return идентификатор
     */
    public String id() {
        return row != null ? row.rowId() : TOTAL_PREFIX + month;
    }
}
