package ru.cashprediction.core.ui.view.chart;

import java.util.Objects;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Элемент легенды графика (спецификация v2, §5.3 «Легенда»): «Баланс, dd.MM.yyyy — dd.MM.yyyy», «Ноль», «Подушка»,
 * «Цель», «Сегодня», «Доход», «Расход», «Доход и расход», «Итог месяца» — каждый только когда выполнено условие;
 * а также уведомление {@code chart.tooManyMarkers} без образца.
 *
 * @param id      стабильный id ({@code balance}, {@code zero}, {@code cushion}, {@code goal}, {@code today},
 *                {@code income}, {@code expense}, {@code mixed}, {@code bars}, {@code notice})
 * @param text    текст
 * @param swatch  вид образца
 * @param color   цвет образца
 * @param tooltip подсказка
 */
public record LegendItem(String id, String text, Swatch swatch, ColorToken color, String tooltip) {

    /** Вид образца цвета. */
    public enum Swatch {
        /** Сплошная линия (баланс, ноль). */
        LINE,
        /** Пунктир (подушка, цель, сегодня). */
        DASH,
        /** Круг (маркеры). */
        DOT,
        /** Прямоугольник (итог месяца). */
        BOX,
        /** Без образца (уведомление). */
        NONE
    }

    /** Проверяет поля. */
    public LegendItem {
        Objects.requireNonNull(id, "id");
        text = Objects.requireNonNullElse(text, "");
        swatch = swatch == null ? Swatch.NONE : swatch;
        color = color == null ? ColorToken.TEXT_MUTED : color;
        tooltip = Objects.requireNonNullElse(tooltip, "");
    }
}
