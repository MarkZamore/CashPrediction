package ru.cashprediction.core.ui.form;

import java.util.Objects;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Строка проблем формы (спецификация v2, §6.0 п. 4): место резервируется всегда; первая ошибка «✖ {текст}» цвета
 * {@code expense}, если ошибок нет — первое предупреждение «⚠ {текст}» цвета {@code warn}.
 *
 * @param severity серьёзность
 * @param text     текст без значка (значок добавляет {@link #display()})
 */
public record Problem(Severity severity, String text) {

    /** Проблем нет. */
    public static final Problem NONE = new Problem(Severity.NONE, "");

    /** Серьёзность проблемы. */
    public enum Severity {
        /** Проблем нет: строка пуста. */
        NONE,
        /** Предупреждение: не блокирует OK. */
        WARNING,
        /** Ошибка: OK/FINISH отключены. */
        ERROR
    }

    /** Проверяет поля. */
    public Problem {
        Objects.requireNonNull(severity, "severity");
        text = Objects.requireNonNullElse(text, "");
    }

    /**
     * Ошибка.
     *
     * @param text текст из каталога
     * @return проблема
     */
    public static Problem error(String text) {
        return new Problem(Severity.ERROR, text);
    }

    /**
     * Предупреждение.
     *
     * @param text текст из каталога
     * @return проблема
     */
    public static Problem warning(String text) {
        return new Problem(Severity.WARNING, text);
    }

    /** @return текст строки проблем со значком: «✖ …», «⚠ …» или пустая строка */
    public String display() {
        return switch (severity) {
            case NONE -> "";
            case WARNING -> "⚠ " + text;
            case ERROR -> "✖ " + text;
        };
    }

    /** @return цвет строки: {@code expense} для ошибки, {@code warn} для предупреждения, {@code text.muted} иначе */
    public ColorToken color() {
        return switch (severity) {
            case NONE -> ColorToken.TEXT_MUTED;
            case WARNING -> ColorToken.WARN;
            case ERROR -> ColorToken.EXPENSE;
        };
    }
}
