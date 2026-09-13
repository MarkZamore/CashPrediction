package ru.cashprediction.core.ui.view.table;

import java.util.Objects;

/**
 * Колонка таблицы прогноза (спецификация v2, §5.2): не сортируется и не переставляется.
 *
 * @param id            идентификатор: {@code date}, {@code day}, {@code title}, {@code category}, {@code income},
 *                      {@code expense}, {@code balance}, {@code marks}
 * @param title         заголовок
 * @param widthPx       ширина
 * @param grows         растягивается ли колонка (только «Операция»)
 * @param align         выравнивание
 * @param bold          жирный ли текст ячеек («Баланс»)
 * @param headerTooltip подсказка заголовка
 */
public record ColumnSpec(String id, String title, int widthPx, boolean grows, Align align, boolean bold,
                         String headerTooltip) {

    /** Выравнивание текста ячейки. */
    public enum Align {
        /** По левому краю. */
        LEFT,
        /** По центру. */
        CENTER,
        /** По правому краю. */
        RIGHT
    }

    /** Проверяет обязательные поля. */
    public ColumnSpec {
        Objects.requireNonNull(id, "id");
        title = Objects.requireNonNullElse(title, "");
        align = align == null ? Align.LEFT : align;
        headerTooltip = Objects.requireNonNullElse(headerTooltip, "");
    }
}
