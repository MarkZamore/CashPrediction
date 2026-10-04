package ru.cashprediction.core.forecast.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.model.WeekendPolicy;

/** Проверяет реальный адаптер: неизменяемый вход, прежнюю семантику и типизированные ошибки. */
class EngineForecastServiceTest {
    private static final LocalDate START = LocalDate.of(2024, 1, 1);
    private final ForecastService service = new EngineForecastService();

    /** Запрос не владеет изменяемыми списками и не добавляет бизнес-валидацию повреждённых планов. */
    @Test
    void requestIsImmutableAndAcceptsDamagedBusinessData() {
        List<RecurringRule> rules = new ArrayList<>();
        RecurringRule damaged = rule("r1", Kind.EXPENSE, -1, new Recurrence.Monthly(31, 1), WeekendPolicy.NONE);
        rules.add(damaged);
        rules.add(damaged);
        Plan plan = Plan.empty("damaged", START).withRules(rules);
        ForecastRequest request = new ForecastRequest(plan, null, START.plusDays(9), true);
        rules.clear();

        assertSame(plan, request.plan());
        assertSame(WhatIf.NONE, request.whatIf());
        assertEquals(START.plusDays(9), request.today());
        assertTrue(request.includeSkipped());
        assertEquals(2, request.plan().rules().size());
        assertThrows(UnsupportedOperationException.class, () -> request.plan().rules().clear());
        assertThrows(NullPointerException.class, () -> new ForecastRequest(null, WhatIf.NONE, START, false));
        assertThrows(NullPointerException.class, () -> new ForecastRequest(plan, WhatIf.NONE, null, false));
    }

    /** Все повторы, сдвиги, корректировки, строки и сводка побитово совпадают с прежним движком. */
    @Test
    void adapterPreservesRecurrencesAdjustmentsOrderingAndSummary() {
        Plan plan = fixture();
        List<WhatIf> parameters = List.of(WhatIf.NONE,
                new WhatIf(new BigDecimal("1.005"), new BigDecimal("0.975"), Money.ofMinor(199)));
        for (WhatIf whatIf : parameters) {
            for (boolean includeSkipped : List.of(false, true)) {
                for (LocalDate today : List.of(START.minusDays(2), START.plusDays(45), plan.endDate().plusDays(1))) {
                    ForecastRequest request = new ForecastRequest(plan, whatIf, today, includeSkipped);
                    Forecast expected = ForecastEngine.forecast(plan, whatIf, today, includeSkipped);
                    assertEquals(expected, service.calculate(request), request.toString());
                }
            }
        }
        assertEquals(1, service.algorithmVersion());
        Forecast shown = service.calculate(new ForecastRequest(plan, WhatIf.NONE, START, true));
        assertTrue(shown.findRow("r2@2024-01-07").orElseThrow().flags().skipped());
        assertEquals(LocalDate.of(2024, 2, 29), shown.findRow("r4@2024-02-29").orElseThrow().date());
        assertFalse(service.calculate(new ForecastRequest(plan, WhatIf.NONE, START, false))
                .findRow("r2@2024-01-07").isPresent());
    }

    /** Округление половины копейки остаётся HALF_UP, а итог хранится целым числом minor units. */
    @Test
    void halfMinorUnitRoundingIsUnchanged() {
        Plan plan = Plan.empty("precision", START).withHorizon(new Horizon.Until(START))
                .withOneTimes(List.of(transaction("t1", Kind.INCOME, 1), transaction("t2", Kind.EXPENSE, 3)));
        WhatIf whatIf = new WhatIf(new BigDecimal("1.5"), new BigDecimal("0.5"), Money.ZERO);
        Forecast forecast = service.calculate(new ForecastRequest(plan, whatIf, START, false));

        assertEquals(Money.ofMinor(2), forecast.findRow("t1").orElseThrow().amount());
        assertEquals(Money.ofMinor(-2), forecast.findRow("t2").orElseThrow().amount());
        assertEquals(Money.ZERO, forecast.endBalance());
        assertEquals(Money.ofMinor(2), forecast.summary().totalIncome());
        assertEquals(Money.ofMinor(2), forecast.summary().totalExpense());
    }

    /** Защитный предел горизонта сохраняет исходное локализованное сообщение и семейство исключения. */
    @Test
    void horizonLimitHasTypedFailureWithOriginalCause() {
        Plan plan = Plan.empty("limit", START).withHorizon(new Horizon.Until(START.plusDays(ForecastEngine.MAX_DAYS)));
        IllegalStateException original = assertThrows(IllegalStateException.class,
                () -> ForecastEngine.forecast(plan, WhatIf.NONE, START, false));
        ForecastFailure.LimitExceeded error = assertThrows(ForecastFailure.LimitExceeded.class,
                () -> service.calculate(new ForecastRequest(plan, WhatIf.NONE, START, false)));

        assertEquals(ForecastFailure.Kind.LIMIT_EXCEEDED, error.kind());
        assertInstanceOf(IllegalStateException.class, error);
        assertEquals(IllegalStateException.class, error.getCause().getClass());
        assertEquals(original.getMessage(), error.getMessage());
        assertEquals(error.getCause().getMessage(), error.getMessage());
    }

    /** Переполнение точного сложения не превращается в успешный прогноз или другую арифметику. */
    @Test
    void amountOverflowIsTypedWithoutChangingItsMessage() {
        Plan plan = Plan.empty("overflow", START).withStart(START, Money.ofMinor(Long.MAX_VALUE))
                .withHorizon(new Horizon.Until(START)).withOneTimes(List.of(transaction("t1", Kind.INCOME, 1)));
        ArithmeticException original = assertThrows(ArithmeticException.class,
                () -> ForecastEngine.forecast(plan, WhatIf.NONE, START, false));
        ForecastFailure.AmountOverflow error = assertThrows(ForecastFailure.AmountOverflow.class,
                () -> service.calculate(new ForecastRequest(plan, WhatIf.NONE, START, false)));

        assertEquals(ForecastFailure.Kind.AMOUNT_OVERFLOW, error.kind());
        assertEquals(ArithmeticException.class, error.getCause().getClass());
        assertEquals(original.getMessage(), error.getMessage());
    }

    /** Long.MIN_VALUE арифметически помещается в long, но остаётся запрещённым результатом Money. */
    @Test
    void reservedMoneyBoundaryHasSeparateTypedFailure() {
        Plan plan = Plan.empty("minimum", START).withStart(START, Money.ofMinor(-Long.MAX_VALUE))
                .withHorizon(new Horizon.Until(START)).withOneTimes(List.of(transaction("t1", Kind.EXPENSE, 1)));
        IllegalArgumentException original = assertThrows(IllegalArgumentException.class,
                () -> ForecastEngine.forecast(plan, WhatIf.NONE, START, false));
        ForecastFailure.AmountOutOfRange error = assertThrows(ForecastFailure.AmountOutOfRange.class,
                () -> service.calculate(new ForecastRequest(plan, WhatIf.NONE, START, false)));

        assertEquals(ForecastFailure.Kind.AMOUNT_OUT_OF_RANGE, error.kind());
        assertEquals(IllegalArgumentException.class, error.getCause().getClass());
        assertEquals(original.getMessage(), error.getMessage());
    }

    /** Календарное переполнение остаётся DateTimeException; ошибочный null не классифицируется как доменная ошибка. */
    @Test
    void dateRangeAndProgrammingErrorsRemainDistinct() {
        Plan plan = Plan.empty("date", LocalDate.MAX);
        DateTimeException original = assertThrows(DateTimeException.class,
                () -> ForecastEngine.forecast(plan, WhatIf.NONE, LocalDate.MAX, false));
        ForecastFailure.DateRangeExceeded error = assertThrows(ForecastFailure.DateRangeExceeded.class,
                () -> service.calculate(new ForecastRequest(plan, WhatIf.NONE, LocalDate.MAX, false)));

        assertEquals(ForecastFailure.Kind.DATE_RANGE_EXCEEDED, error.kind());
        assertInstanceOf(DateTimeException.class, error.getCause());
        assertEquals(original.getMessage(), error.getMessage());
        assertThrows(NullPointerException.class, () -> service.calculate(null));
    }

    /** Создаёт небольшой, но содержательный план со всеми семействами повторов и действий корректировки. */
    private static Plan fixture() {
        return Plan.empty("recurrences", START).withHorizon(new Horizon.Months(3))
                .withStart(START, Money.ofMinor(12345)).withCushion(Money.ofMinor(12000))
                .withGoal(new Goal("goal", Money.ofMinor(15000), START.plusDays(59)))
                .withRules(List.of(
                        rule("r1", Kind.INCOME, 101, new Recurrence.Monthly(31, 2), WeekendPolicy.NEXT_BUSINESS_DAY),
                        rule("r2", Kind.EXPENSE, 203, new Recurrence.Weekly(DayOfWeek.SUNDAY, 2), WeekendPolicy.PREVIOUS_BUSINESS_DAY),
                        rule("r3", Kind.EXPENSE, 7, new Recurrence.EveryNDays(3), WeekendPolicy.NONE),
                        rule("r4", Kind.INCOME, 301, new Recurrence.Yearly(MonthDay.of(2, 29)), WeekendPolicy.NONE)))
                .withOneTimes(List.of(transaction("t1", Kind.INCOME, 11), transaction("t2", Kind.EXPENSE, 13)))
                .withAdjustments(List.of(
                        adjustment("r2", LocalDate.of(2024, 1, 7), new Adjustment.Skip()),
                        adjustment("r1", LocalDate.of(2024, 1, 31), new Adjustment.ChangeAmount(Money.ofMinor(103))),
                        adjustment("r3", START.plusDays(3), new Adjustment.MoveDate(START.plusDays(5))),
                        adjustment("r4", LocalDate.of(2024, 2, 29), new Adjustment.Replace(Money.ofMinor(303), LocalDate.of(2024, 2, 29)))));
    }

    /** Создаёт правило с явной опорной датой, чтобы зафиксировать фазу повторов. */
    private static RecurringRule rule(String id, Kind kind, long minor, Recurrence recurrence, WeekendPolicy policy) {
        return new RecurringRule(new RuleId(id), id, kind, Money.ofMinor(minor), "category", recurrence,
                START, null, policy, true, "note");
    }

    /** Создаёт разовую операцию на первый день для проверки порядка и точных сумм. */
    private static OneTimeTransaction transaction(String id, Kind kind, long minor) {
        return new OneTimeTransaction(new TxId(id), START, id, kind, Money.ofMinor(minor), "", "");
    }

    /** Создаёт действие для события с устойчивым номинальным ключом. */
    private static Adjustment adjustment(String id, LocalDate date, Adjustment.Action action) {
        return new Adjustment(new OccurrenceKey(new RuleId(id), date), action, "");
    }
}
