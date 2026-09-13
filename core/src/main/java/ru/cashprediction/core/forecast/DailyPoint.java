package ru.cashprediction.core.forecast;

import java.time.LocalDate;
import java.util.Objects;
import ru.cashprediction.core.model.Money;

/**
 * Точка графика баланса: день и баланс на его конец.
 *
 * <p>Record неизменяем и потокобезопасен.</p>
 *
 * @param date    день
 * @param balance баланс на конец дня
 */
public record DailyPoint(LocalDate date, Money balance) {

    /** Проверяет обязательные поля. */
    public DailyPoint {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(balance, "balance");
    }
}
