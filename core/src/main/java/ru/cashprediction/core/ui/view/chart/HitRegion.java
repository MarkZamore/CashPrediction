package ru.cashprediction.core.ui.view.chart;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Прямоугольная зона подсказки на графике (маркер дня или столбец итога месяца, §5.3).
 *
 * <p>Подсказка маркера: «dd.MM.yyyy», строки «+80 000,00 ₽ — Зарплата», «Баланс: X ₽». Подсказка столбца:
 * «Октябрь 2026», «Итог: +X ₽», «Доходы: X ₽», «Расходы: X ₽», «Баланс на конец: X ₽».</p>
 *
 * @param id      стабильный id ({@code marker@2026-10-05}, {@code bar@2026-10})
 * @param kind    вид зоны
 * @param x       левый край
 * @param y       верхний край
 * @param width   ширина
 * @param height  высота
 * @param date    день маркера или первое число месяца столбца
 * @param tooltip готовый текст подсказки с переводами строк
 */
public record HitRegion(String id, Kind kind, double x, double y, double width, double height, LocalDate date,
                        String tooltip) {

    /** Вид зоны. */
    public enum Kind {
        /** Маркер событий дня. */
        MARKER,
        /** Столбец итога месяца. */
        BAR
    }

    /** Проверяет поля. */
    public HitRegion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        tooltip = Objects.requireNonNullElse(tooltip, "");
    }

    /**
     * Попадает ли точка в зону.
     *
     * @param px координата x
     * @param py координата y
     * @return {@code true}, если точка внутри или на границе
     */
    public boolean contains(double px, double py) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }
}
