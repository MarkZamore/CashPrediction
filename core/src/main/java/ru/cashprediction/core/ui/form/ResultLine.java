package ru.cashprediction.core.ui.form;

import java.util.Objects;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Строка результата формы (калькулятор цели, §6.6 «Результат»).
 *
 * @param text  текст
 * @param color цвет текста
 */
public record ResultLine(String text, ColorToken color) {

    /** Подставляет значения по умолчанию. */
    public ResultLine {
        text = Objects.requireNonNullElse(text, "");
        color = color == null ? ColorToken.TEXT_PRIMARY : color;
    }
}
