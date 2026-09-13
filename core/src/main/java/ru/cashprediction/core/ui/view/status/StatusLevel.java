package ru.cashprediction.core.ui.view.status;

import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Уровень статусного сообщения и его цвет (спецификация v2, §5.4 п. 5, §8.2).
 *
 * <p>Перечисление неизменяемо и потокобезопасно.</p>
 */
public enum StatusLevel {

    /** Сведения: {@code text.primary}. */
    INFO(ColorToken.TEXT_PRIMARY),
    /** Успех: {@code income}. */
    SUCCESS(ColorToken.INCOME),
    /** Предупреждение: {@code warn}. */
    WARN(ColorToken.WARN),
    /** Ошибка: {@code expense}. */
    ERROR(ColorToken.EXPENSE);

    private final ColorToken color;

    StatusLevel(ColorToken color) {
        this.color = color;
    }

    /** @return цвет текста сегмента «Сообщение» */
    public ColorToken color() {
        return color;
    }
}
