package ru.cashprediction.core.ui.alert;

import java.util.Objects;
import ru.cashprediction.core.ui.form.ButtonRole;

/**
 * Кнопка сообщения в визуальном порядке (JavaFX {@code ButtonType} → Swing {@code JButton} → Web {@code button}).
 *
 * @param id      id кнопки (ответ {@code onButton}), например {@code save}, {@code dontSave}, {@code cancel}
 * @param text    текст из каталога ({@code button.*})
 * @param role    роль (CANCEL — крестик и Esc)
 * @param enabled доступна ли (кнопка хранилища без снимка в диалоге восстановления — нет)
 * @param tooltip подсказка или пустая строка
 */
public record AlertButton(String id, String text, ButtonRole role, boolean enabled, String tooltip) {

    /** Проверяет поля. */
    public AlertButton {
        Objects.requireNonNull(id, "id");
        text = Objects.requireNonNullElse(text, "");
        Objects.requireNonNull(role, "role");
        tooltip = Objects.requireNonNullElse(tooltip, "");
    }
}
