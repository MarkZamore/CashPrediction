package ru.cashprediction.core.forecast;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.LongPredicate;
import ru.cashprediction.core.model.Money;

/**
 * Внутренние операции над ежедневной серией баланса ({@code long[]} копеек на конец каждого дня).
 *
 * <p>Вынесены отдельно, потому что нужны и движку (при построении сводки, когда объекта {@link Forecast}
 * ещё нет), и самому {@link Forecast}, и {@link GoalCalculator}. Работают с массивом без копирования.</p>
 *
 * <p>Класс без состояния, потокобезопасен при условии, что массив не меняется (так и есть: его
 * создаёт движок и больше никто не пишет).</p>
 */
final class BalanceSeries {

    private BalanceSeries() {
    }

    /**
     * Баланс на конец дня с «прижатием» к границам серии.
     *
     * @param start        первый день серии
     * @param values       баланс на конец каждого дня
     * @param startBalance баланс до первого дня
     * @param date         запрошенный день
     * @return {@code startBalance} для дней до начала, последний баланс для дней после конца
     */
    static Money balanceAt(LocalDate start, long[] values, Money startBalance, LocalDate date) {
        long index = ChronoUnit.DAYS.between(start, date);
        if (index < 0) {
            return startBalance;
        }
        return new Money(values[(int) Math.min(index, values.length - 1)]);
    }

    /**
     * Первый день в интервале {@code [from, конец серии]}, баланс которого удовлетворяет условию.
     *
     * @param start     первый день серии
     * @param values    баланс на конец каждого дня
     * @param from      с какого дня искать (дни до начала серии пропускаются)
     * @param condition условие на баланс в копейках
     * @return найденный день или пусто (в том числе когда {@code from} позже конца серии)
     */
    static Optional<LocalDate> firstMatch(LocalDate start, long[] values, LocalDate from, LongPredicate condition) {
        long first = Math.max(0, ChronoUnit.DAYS.between(start, from));
        for (long i = first; i < values.length; i++) {
            if (condition.test(values[(int) i])) {
                return Optional.of(start.plusDays(i));
            }
        }
        return Optional.empty();
    }

    /**
     * Индекс дня в серии без прижатия.
     *
     * @param start первый день серии
     * @param date  день
     * @return номер дня (может быть отрицательным или выходить за длину серии)
     */
    static long indexOf(LocalDate start, LocalDate date) {
        return ChronoUnit.DAYS.between(start, date);
    }
}
