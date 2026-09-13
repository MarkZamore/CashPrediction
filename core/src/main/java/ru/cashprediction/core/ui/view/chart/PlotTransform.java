package ru.cashprediction.core.ui.view.chart;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Преобразование координат области построения графика (архитектура §3.4; поля 80/24/28/32 из §5.3).
 *
 * <p><b>x → дата</b> (формула одинакова в Java и JS): {@code from + floor((x - plotX) * dayCount / plotWidth)} дней,
 * результат ограничен диапазоном {@code [from, from + dayCount - 1]}. <b>дата → x</b>: левый край дня
 * {@code plotX + dayIndex * plotWidth / dayCount}. <b>сумма → y</b>: {@code plotY + plotHeight * (maxMinor - minor) /
 * (maxMinor - minMinor)} (y растёт вниз).</p>
 *
 * @param plotX      левый край области построения
 * @param plotY      верхний край области построения
 * @param plotWidth  ширина области построения
 * @param plotHeight высота области построения
 * @param from       первая дата диапазона
 * @param dayCount   число дней диапазона (≥ 1)
 * @param minMinor   нижняя граница шкалы Y в копейках
 * @param maxMinor   верхняя граница шкалы Y в копейках
 */
public record PlotTransform(double plotX, double plotY, double plotWidth, double plotHeight, LocalDate from,
                            int dayCount, long minMinor, long maxMinor) {

    /** Проверяет поля. */
    public PlotTransform {
        Objects.requireNonNull(from, "from");
    }

    /**
     * Дата под координатой x.
     *
     * @param x координата
     * @return дата, ограниченная диапазоном
     */
    public LocalDate dateAt(double x) {
        throw new UnsupportedOperationException("S1: core-chart — PlotTransform.dateAt");
    }

    /**
     * Координата x левого края дня.
     *
     * @param date дата
     * @return координата
     */
    public double xOf(LocalDate date) {
        throw new UnsupportedOperationException("S1: core-chart — PlotTransform.xOf");
    }

    /**
     * Координата y суммы.
     *
     * @param minor сумма в копейках
     * @return координата
     */
    public double yOf(long minor) {
        throw new UnsupportedOperationException("S1: core-chart — PlotTransform.yOf");
    }

    /**
     * Внутри ли точка области построения (для контекстного меню и наведения).
     *
     * @param x координата x
     * @param y координата y
     * @return {@code true}, если внутри или на границе
     */
    public boolean contains(double x, double y) {
        throw new UnsupportedOperationException("S1: core-chart — PlotTransform.contains");
    }
}
