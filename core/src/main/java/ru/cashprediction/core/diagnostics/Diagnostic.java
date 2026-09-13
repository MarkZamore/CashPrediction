package ru.cashprediction.core.diagnostics;

import java.util.Objects;

/**
 * Одно диагностическое сообщение: что не так, где и насколько серьёзно.
 *
 * <p>Пример: {@code Diagnostic(ERROR, 27, "Некорректная сумма: «8о 000»")} — пользователь вписал
 * букву «о» вместо нуля в строке 27 файла плана. Строка не попала в прогноз, но сохранилась
 * в заметке, а интерфейс показывает список таких сообщений.</p>
 *
 * @param severity важность
 * @param line     номер строки файла, начиная с 1; 0 — сообщение не относится к конкретной строке
 * @param message  понятный человеку текст на русском
 */
public record Diagnostic(Severity severity, int line, String message) {

    /** Проверяет обязательные поля. */
    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        if (line < 0) {
            throw new IllegalArgumentException("Номер строки не может быть отрицательным");
        }
    }

    /** @return справочное сообщение без привязки к строке */
    public static Diagnostic info(String message) {
        return new Diagnostic(Severity.INFO, 0, message);
    }

    /** @return предупреждение без привязки к строке */
    public static Diagnostic warning(String message) {
        return new Diagnostic(Severity.WARNING, 0, message);
    }

    /** @return ошибка без привязки к строке */
    public static Diagnostic error(String message) {
        return new Diagnostic(Severity.ERROR, 0, message);
    }

    /** @return справочное сообщение для строки файла */
    public static Diagnostic info(int line, String message) {
        return new Diagnostic(Severity.INFO, line, message);
    }

    /** @return предупреждение для строки файла */
    public static Diagnostic warning(int line, String message) {
        return new Diagnostic(Severity.WARNING, line, message);
    }

    /** @return ошибка для строки файла */
    public static Diagnostic error(int line, String message) {
        return new Diagnostic(Severity.ERROR, line, message);
    }

    /**
     * Текст для списка в диалоге диагностики.
     *
     * @return например «Ошибка, строка 27: Некорректная сумма: «8о 000»»
     */
    public String format() {
        return severity.title() + (line > 0 ? ", строка " + line : "") + ": " + message;
    }

    /** @return то же, что {@link #format()} */
    @Override
    public String toString() {
        return format();
    }
}
