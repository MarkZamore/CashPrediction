package ru.cashprediction.core.ui.form;

import java.util.Objects;

/**
 * Вариант выбора поля {@code CHOICE}, {@code EDITABLE_CHOICE}, {@code RADIO} или {@code LIST}.
 *
 * @param value  каноническое значение (попадает в {@code FormState} и снимок), например {@code MONTHLY}
 * @param text   показываемый текст, например «Ежемесячно / каждые N месяцев»
 * @param detail вторая колонка списка или пустая строка (например «изменён 13.09.2026 10:15» в окне «Выбор файла»)
 * @param bold   жирный ли текст (папки в окне «Выбор файла»)
 */
public record Option(String value, String text, String detail, boolean bold) {

    /** Заменяет {@code null} пустыми строками. */
    public Option {
        value = Objects.requireNonNullElse(value, "");
        text = Objects.requireNonNullElse(text, "");
        detail = Objects.requireNonNullElse(detail, "");
    }

    /**
     * Простой вариант без второй колонки.
     *
     * @param value значение
     * @param text  текст
     * @return вариант
     */
    public static Option of(String value, String text) {
        return new Option(value, text, "", false);
    }
}
