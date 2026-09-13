package ru.cashprediction.core.ui.view.chart;

import java.util.List;
import java.util.Objects;

/**
 * Шкалы графика (спецификация v2, §5.3 «Ось Y», «Ось X»).
 *
 * <p><b>Ось Y:</b> шаги 1/2/5×10ⁿ, около 6 делений; диапазон включает 0, подушку (если &gt; 0) и цель; подписи
 * целыми с группировкой пробелом («150 000»), выровнены вправо до оси, {@code text.muted} 11 px. <b>Ось X:</b>
 * вертикальная сетка на первом числе каждого месяца; подписи «янв», «фев», …, в январе и на первой подписи
 * «янв 2026»; шаг подписей: каждый месяц при ≤ 12 месяцах, каждый 2-й при ≤ 24, 6-й при ≤ 48, 12-й иначе.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class ChartScale {

    /**
     * Деления оси Y.
     *
     * @param minMinor  нижняя граница шкалы (кратна шагу)
     * @param maxMinor  верхняя граница шкалы (кратна шагу)
     * @param stepMinor шаг
     * @param values    значения делений снизу вверх
     * @param labels    подписи делений («150 000»)
     */
    public record Ticks(long minMinor, long maxMinor, long stepMinor, List<Long> values, List<String> labels) {
        /** Копирует списки. */
        public Ticks {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
            labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
        }
    }

    private ChartScale() {
    }

    /**
     * Деления оси Y для диапазона данных.
     *
     * @param dataMinMinor минимум данных (с учётом 0, подушки и цели)
     * @param dataMaxMinor максимум данных
     * @return деления
     */
    public static Ticks yTicks(long dataMinMinor, long dataMaxMinor) {
        throw new UnsupportedOperationException("S1: core-chart — ChartScale.yTicks");
    }

    /**
     * Шаг подписей оси X в месяцах.
     *
     * @param months число месяцев диапазона
     * @return 1, 2, 6 или 12
     */
    public static int monthLabelStep(int months) {
        throw new UnsupportedOperationException("S1: core-chart — ChartScale.monthLabelStep");
    }
}
