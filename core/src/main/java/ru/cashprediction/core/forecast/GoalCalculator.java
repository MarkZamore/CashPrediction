package ru.cashprediction.core.forecast;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.model.Money;

/**
 * Калькулятор цели накопления: «когда я накоплю 300 000?» и «сколько откладывать, чтобы успеть к 01.06.2027?».
 *
 * <p>Согласован с режимом «что-если» ({@link WhatIf#extraMonthlySaving()}): дополнительная экономия
 * добавляется в последний день каждого месяца, начиная с {@code anchor}. Поэтому, если построить прогноз
 * с экономией {@code forecast.whatIf().extraMonthlySaving() + requiredExtraMonthly(...)}, баланс на
 * {@code byDate} будет не меньше цели.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class GoalCalculator {

    private GoalCalculator() {
    }

    /**
     * Дата, когда баланс впервые достигает цели.
     *
     * @param forecast прогноз
     * @param target   нужная сумма
     * @return первый день не раньше {@code anchor}, на конец которого баланс не меньше цели
     *         ({@code anchor}, если цель уже достигнута); пусто, если в горизонте цель не достигается
     */
    public static Optional<LocalDate> reachDate(Forecast forecast, Money target) {
        Objects.requireNonNull(forecast, "forecast");
        Objects.requireNonNull(target, "target");
        if (forecast.anchor().isAfter(forecast.endDate())) {
            // «Сейчас» уже после конца горизонта: баланс считается равным конечному (как в Forecast.balanceAt).
            return forecast.endBalance().compareTo(target) >= 0 ? Optional.of(forecast.anchor()) : Optional.empty();
        }
        return forecast.firstDateAtLeast(forecast.anchor(), target);
    }

    /**
     * Реализация {@link #reachDate(Forecast, Money)} над массивом; нужна движку до создания объекта прогноза.
     */
    static Optional<LocalDate> reachDate(LocalDate start, long[] daily, LocalDate anchor, Money target) {
        long t = target.minor();
        long anchorIndex = BalanceSeries.indexOf(start, anchor);
        if (anchorIndex >= daily.length) {
            // «Сейчас» уже после конца горизонта: баланс считается равным конечному (как в Forecast.balanceAt).
            return daily[daily.length - 1] >= t ? Optional.of(anchor) : Optional.empty();
        }
        return BalanceSeries.firstMatch(start, daily, anchor, v -> v >= t);
    }

    /**
     * Сколько дополнительно откладывать в месяц, чтобы к дате баланс достиг цели.
     *
     * @param forecast прогноз (результат — добавка к уже заданной в нём экономии «что-если»)
     * @param target   нужная сумма
     * @param byDate   к какой дате
     * @return {@link Money#ZERO}, если цель на эту дату и так достигается; пусто, если дата позже конца горизонта
     *         или между {@code anchor} и {@code byDate} нет ни одного последнего дня месяца; иначе недостающая сумма,
     *         делённая на число последних дней месяцев в {@code [anchor, byDate]} с округлением вверх до рубля
     */
    public static Optional<Money> requiredExtraMonthly(Forecast forecast, Money target, LocalDate byDate) {
        Objects.requireNonNull(forecast, "forecast");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(byDate, "byDate");
        Money balance = forecast.balanceAt(byDate);
        if (balance.compareTo(target) >= 0) {
            return Optional.of(Money.ZERO);
        }
        if (byDate.isAfter(forecast.endDate())) {
            return Optional.empty();
        }
        long monthEnds = monthEndsBetween(forecast.anchor(), byDate);
        if (monthEnds == 0) {
            return Optional.empty();
        }
        return Optional.of(target.minus(balance).divideCeilToMajor(monthEnds));
    }

    /**
     * Баланс на конец дня; то же, что {@link Forecast#balanceAt(LocalDate)}.
     *
     * @param forecast прогноз
     * @param date     день
     * @return баланс
     */
    public static Money balanceAt(Forecast forecast, LocalDate date) {
        return forecast.balanceAt(date);
    }

    /**
     * Число последних дней месяца в интервале дат включительно — столько раз сработает дополнительная экономия.
     *
     * @param from первый день
     * @param to   последний день
     * @return число дат вида «последний день месяца» в {@code [from, to]}; 0, если {@code from > to}
     */
    public static long monthEndsBetween(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            return 0;
        }
        YearMonth last = YearMonth.from(to);
        long months = ChronoUnit.MONTHS.between(YearMonth.from(from), last) + 1;
        // Конец первого месяца всегда не раньше from; конец последнего входит, только если to — последний день.
        return to.equals(last.atEndOfMonth()) ? months : months - 1;
    }
}
