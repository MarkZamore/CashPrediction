package ru.cashprediction.swing.view;

import java.time.YearMonth;
import java.util.Objects;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.MonthTotals;

/**
 * Одна строка таблицы прогноза Swing-клиента: событие прогноза, итог месяца или заголовок свёрнутой группы
 * «Прошедшие события».
 *
 * <p>Таблица показывает не только строки {@code PlanDocument.visibleRows()}, но и служебные строки: итоги месяцев
 * (флажок «Итоги по месяцам») и заголовок группы прошедших событий (события раньше сегодняшнего дня по умолчанию
 * свёрнуты, раздел 2 плана «Семантика сегодня»). Record неизменяем.</p>
 *
 * @param type      вид строки
 * @param row       событие прогноза (только для {@link Type#ROW})
 * @param month     месяц итога (только для {@link Type#MONTH_TOTAL})
 * @param totals    итоги месяца (только для {@link Type#MONTH_TOTAL})
 * @param pastCount число прошедших событий (только для {@link Type#PAST_HEADER})
 * @param expanded  развёрнута ли группа прошедших событий (только для {@link Type#PAST_HEADER})
 */
public record TableItem(Type type, ForecastRow row, YearMonth month, MonthTotals totals, int pastCount, boolean expanded) {

    /** Вид строки таблицы. */
    public enum Type {
        /** Событие прогноза. */
        ROW,
        /** Итог месяца. */
        MONTH_TOTAL,
        /** Заголовок группы «Прошедшие события». */
        PAST_HEADER
    }

    /** Идентификатор заголовка группы прошедших событий. */
    public static final String PAST_HEADER_ID = "past";

    /** Проверяет, что для вида строки заданы нужные данные. */
    public TableItem {
        Objects.requireNonNull(type, "type");
        switch (type) {
            case ROW -> Objects.requireNonNull(row, "row");
            case MONTH_TOTAL -> {
                Objects.requireNonNull(month, "month");
                Objects.requireNonNull(totals, "totals");
            }
            case PAST_HEADER -> {
                // Данных, кроме счётчика, не нужно.
            }
        }
    }

    /**
     * Строка события.
     *
     * @param row событие прогноза
     * @return строка таблицы
     */
    public static TableItem of(ForecastRow row) {
        return new TableItem(Type.ROW, row, null, null, 0, false);
    }

    /**
     * Строка итога месяца.
     *
     * @param month  месяц
     * @param totals итоги
     * @return строка таблицы
     */
    public static TableItem monthTotal(YearMonth month, MonthTotals totals) {
        return new TableItem(Type.MONTH_TOTAL, null, month, totals, 0, false);
    }

    /**
     * Заголовок группы прошедших событий.
     *
     * @param count    сколько событий в группе
     * @param expanded развёрнута ли группа
     * @return строка таблицы
     */
    public static TableItem pastHeader(int count, boolean expanded) {
        return new TableItem(Type.PAST_HEADER, null, null, null, count, expanded);
    }

    /**
     * Идентификатор строки: как у события прогноза ({@code start}, {@code r2@2026-10-01}, {@code t1}), для итога —
     * {@code month@2026-10}, для заголовка группы — {@code past}.
     *
     * @return идентификатор
     */
    public String rowId() {
        return switch (type) {
            case ROW -> row.rowId();
            case MONTH_TOTAL -> "month@" + month;
            case PAST_HEADER -> PAST_HEADER_ID;
        };
    }

    /**
     * Является ли строка событием прогноза (к ней применимы команды «Изменить», «Удалить» и т. п.).
     *
     * @return {@code true} для {@link Type#ROW}
     */
    public boolean isRow() {
        return type == Type.ROW;
    }
}
