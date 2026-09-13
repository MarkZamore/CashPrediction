package ru.cashprediction.core.ui.menu;

import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Выделение текста кнопки тулбара (спецификация v2, §4).
 *
 * <p>Перечисление неизменяемо и потокобезопасно.</p>
 */
public enum Emphasis {

    /** Обычный текст. */
    NONE(null),
    /** Жирный текст цвета {@code accent}: «Сохранить» при несохранённых изменениях. */
    ACCENT(ColorToken.ACCENT),
    /** Жирный текст цвета {@code whatif}: «Что-если: включено». */
    WHATIF(ColorToken.WHATIF);

    private final ColorToken color;

    Emphasis(ColorToken color) {
        this.color = color;
    }

    /** @return цвет жирного текста или {@code null} для {@link #NONE} */
    public ColorToken color() {
        return color;
    }
}
