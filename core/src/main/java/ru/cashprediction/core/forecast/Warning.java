package ru.cashprediction.core.forecast;

import java.time.LocalDate;
import java.util.Objects;
import ru.cashprediction.core.diagnostics.Severity;
import ru.cashprediction.core.util.DateFormats;

/**
 * Предупреждение прогноза: «баланс уходит в минус 10.12.2026», «корректировка без события» и т. п.
 *
 * <p>Record неизменяем и потокобезопасен.</p>
 *
 * @param severity важность
 * @param date     дата, к которой относится предупреждение; {@code null}, если даты нет
 * @param type     вид предупреждения
 * @param message  текст для пользователя на русском
 */
public record Warning(Severity severity, LocalDate date, WarningType type, String message) {

    /** Проверяет обязательные поля. */
    public Warning {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(message, "message");
    }

    /**
     * Текст для списка предупреждений.
     *
     * @return например «10.12.2026: Баланс уходит в минус: -2 000,00 ₽»
     */
    public String format() {
        return date == null ? message : DateFormats.ru(date) + ": " + message;
    }
}
