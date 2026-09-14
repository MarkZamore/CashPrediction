package ru.cashprediction.core.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.cashprediction.core.forecast.TestPlans.START;
import static ru.cashprediction.core.forecast.TestPlans.d;
import static ru.cashprediction.core.forecast.TestPlans.monthly;
import static ru.cashprediction.core.forecast.TestPlans.plan;
import static ru.cashprediction.core.forecast.TestPlans.run;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.WeekendPolicy;

/**
 * Тесты калькулятора цели, в том числе согласованность с дополнительной экономией «что-если».
 */
class GoalCalculatorTest {

    private static final Plan SALARY = plan(0, monthly("r1", "Доход", Kind.INCOME, 1000, 5));

    /** Дата достижения цели. */
    @Test
    void reachDate() {
        Forecast f = run(SALARY);
        assertEquals(Optional.of(d("2026-11-05")), GoalCalculator.reachDate(f, Money.ofMajor(2500)));
        assertEquals(Optional.of(START), GoalCalculator.reachDate(f, Money.ZERO), "уже достигнута - anchor");
        assertTrue(GoalCalculator.reachDate(f, Money.ofMajor(1_000_000)).isEmpty());

        Forecast later = ForecastEngine.forecast(SALARY, WhatIf.NONE, d("2026-12-20"), false);
        assertEquals(Optional.of(d("2026-12-20")), GoalCalculator.reachDate(later, Money.ofMajor(2500)));
        assertEquals(Optional.of(d("2027-01-05")), GoalCalculator.reachDate(later, Money.ofMajor(5000)));

        Forecast afterEnd = ForecastEngine.forecast(SALARY, WhatIf.NONE, d("2028-01-01"), false);
        assertEquals(Optional.of(d("2028-01-01")), GoalCalculator.reachDate(afterEnd, Money.ofMajor(2500)));
        assertTrue(GoalCalculator.reachDate(afterEnd, Money.ofMajor(20_000)).isEmpty());
    }

    /** Граничные случаи «сколько откладывать». */
    @Test
    void requiredExtraMonthlyEdgeCases() {
        Forecast f = run(SALARY);
        assertEquals(Optional.of(Money.ZERO), GoalCalculator.requiredExtraMonthly(f, Money.ofMajor(2000), d("2026-11-30")));
        assertTrue(GoalCalculator.requiredExtraMonthly(f, Money.ofMajor(1_000_000), d("2027-12-31")).isEmpty(), "после горизонта");
        Forecast empty = run(plan(0));
        assertTrue(GoalCalculator.requiredExtraMonthly(empty, Money.ofMajor(1000), d("2026-09-20")).isEmpty(), "нет концов месяцев");
        assertEquals(Optional.of(Money.ofMajor(2500)), GoalCalculator.requiredExtraMonthly(empty, Money.ofMajor(10_000), d("2026-12-31")));
        assertEquals(Optional.of(Money.ofMajor(2501)),
                GoalCalculator.requiredExtraMonthly(empty, Money.parse("10000,50"), d("2026-12-31")), "округление вверх до рубля");
        assertEquals(Money.ofMajor(3000), GoalCalculator.balanceAt(f, d("2026-11-30")));
    }

    /** Экономия, найденная калькулятором, действительно доводит баланс до цели (и на рубль меньше — нет). */
    @Test
    void requiredExtraMonthlyIsConsistentWithWhatIf() {
        Plan p = plan(1000,
                TestPlans.rule("r1", "Зарплата", Kind.INCOME, Money.ofMajor(30_000), new Recurrence.Monthly(5, 1), null, null,
                        WeekendPolicy.PREVIOUS_BUSINESS_DAY),
                TestPlans.rule("r2", "Расходы", Kind.EXPENSE, Money.parse("27 777,77"), new Recurrence.Monthly(20, 1), null, null,
                        WeekendPolicy.NONE));
        LocalDate today = d("2026-10-15");
        LocalDate byDate = d("2027-03-10");
        Money target = Money.ofMajor(100_000);
        for (WhatIf base : new WhatIf[] {WhatIf.NONE, new WhatIf(new BigDecimal("1.1"), new BigDecimal("0.95"), Money.ofMajor(700))}) {
            Forecast f = ForecastEngine.forecast(p, base, today, false);
            Money extra = GoalCalculator.requiredExtraMonthly(f, target, byDate).orElseThrow();
            assertTrue(extra.isPositive());
            Money total = base.extraMonthlySaving().plus(extra);
            Forecast withSaving = ForecastEngine.forecast(p, base.withExtraMonthlySaving(total), today, false);
            assertTrue(withSaving.balanceAt(byDate).compareTo(target) >= 0, "баланс " + withSaving.balanceAt(byDate));
            Forecast oneLess = ForecastEngine.forecast(p, base.withExtraMonthlySaving(total.minus(Money.ofMajor(1))), today, false);
            assertTrue(oneLess.balanceAt(byDate).compareTo(target) < 0, "округление до рубля не завышено");
            assertEquals(Optional.of(Money.ZERO), GoalCalculator.requiredExtraMonthly(withSaving, target, byDate));
        }
    }

    /** Подсчёт последних дней месяца в интервале. */
    @Test
    void monthEndsBetween() {
        assertEquals(0, GoalCalculator.monthEndsBetween(d("2026-09-01"), d("2026-09-20")));
        assertEquals(1, GoalCalculator.monthEndsBetween(d("2026-09-30"), d("2026-09-30")));
        assertEquals(4, GoalCalculator.monthEndsBetween(d("2026-09-01"), d("2026-12-31")));
        assertEquals(4, GoalCalculator.monthEndsBetween(d("2026-09-01"), d("2027-01-15")));
        assertEquals(2, GoalCalculator.monthEndsBetween(d("2028-02-29"), d("2028-03-31")));
        assertEquals(0, GoalCalculator.monthEndsBetween(d("2026-10-01"), d("2026-09-01")));
    }
}
