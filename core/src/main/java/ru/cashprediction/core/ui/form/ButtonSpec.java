package ru.cashprediction.core.ui.form;

import java.util.Objects;

/**
 * Кнопка панели кнопок формы (архитектура §3.5).
 *
 * @param id      id кнопки ({@code ok}, {@code cancel}, {@code sample}, {@code back}, {@code next}, {@code finish}, …)
 * @param text    текст из каталога ({@code button.*})
 * @param role    роль
 * @param tooltip подсказка или пустая строка
 */
public record ButtonSpec(String id, String text, ButtonRole role, String tooltip) {

    /** Проверяет поля. */
    public ButtonSpec {
        Objects.requireNonNull(id, "id");
        text = Objects.requireNonNullElse(text, "");
        Objects.requireNonNull(role, "role");
        tooltip = Objects.requireNonNullElse(tooltip, "");
    }
}
