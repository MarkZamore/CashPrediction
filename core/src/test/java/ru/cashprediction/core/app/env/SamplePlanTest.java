package ru.cashprediction.core.app.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.SamplePlan;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.WeekendPolicy;

/**
 * План «Пример» — единственный для трёх клиентов (спецификация v2, §6.24).
 */
class SamplePlanTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);

    @Test
    void parametersMatchSpec() {
        Plan plan = SamplePlan.create(TODAY);
        assertEquals("Пример", plan.name());
        assertEquals("Пример", SamplePlan.name());
        assertEquals("", plan.note());
        assertEquals("₽", plan.currency());
        assertEquals(LocalDate.of(2026, 9, 1), plan.startDate(), "1-е число текущего месяца");
        assertEquals(Money.ofMajor(150_000), plan.startBalance());
        assertEquals(new Horizon.Months(12), plan.horizon());
        assertEquals(Money.ofMajor(50_000), plan.cushion());
        assertEquals("Отпуск", plan.goal().title());
        assertEquals(Money.ofMajor(300_000), plan.goal().target());
        assertNull(plan.goal().wishDate());
        assertEquals(List.of(), plan.adjustments());
    }

    @Test
    void rulesMatchSpec() {
        List<RecurringRule> rules = SamplePlan.create(TODAY).rules();
        assertEquals(List.of("r1", "r2", "r3", "r4", "r5"), rules.stream().map(r -> r.id().value()).toList());
        assertRule(rules.get(0), "Зарплата", Kind.INCOME, Money.ofMajor(80_000), new Recurrence.Monthly(5, 1),
                WeekendPolicy.PREVIOUS_BUSINESS_DAY);
        assertRule(rules.get(1), "Аванс", Kind.INCOME, Money.ofMajor(40_000), new Recurrence.Monthly(20, 1),
                WeekendPolicy.PREVIOUS_BUSINESS_DAY);
        assertRule(rules.get(2), "Аренда", Kind.EXPENSE, Money.ofMajor(45_000), new Recurrence.Monthly(1, 1),
                WeekendPolicy.NONE);
        assertRule(rules.get(3), "Продукты", Kind.EXPENSE, Money.ofMajor(4_000),
                new Recurrence.Weekly(DayOfWeek.SATURDAY, 1), WeekendPolicy.NONE);
        assertRule(rules.get(4), "Кредит", Kind.EXPENSE, Money.parse("12 345,67"), new Recurrence.Monthly(31, 1),
                WeekendPolicy.NONE);
    }

    @Test
    void oneTimeBonusThreeMonthsLater() {
        List<OneTimeTransaction> oneTimes = SamplePlan.create(TODAY).oneTimes();
        assertEquals(1, oneTimes.size());
        OneTimeTransaction bonus = oneTimes.getFirst();
        assertEquals("t1", bonus.id().value());
        assertEquals("Премия", bonus.title());
        assertEquals(Kind.INCOME, bonus.kind());
        assertEquals(Money.ofMajor(60_000), bonus.amount());
        assertEquals(LocalDate.of(2026, 12, 20), bonus.date());
    }

    @Test
    void sampleDependsOnlyOnTodayAndForecastsWithoutErrors() {
        assertEquals(SamplePlan.create(TODAY), SamplePlan.create(LocalDate.of(2026, 9, 30)), "тот же месяц - тот же план");
        assertEquals(LocalDate.of(2027, 1, 1), SamplePlan.create(LocalDate.of(2027, 1, 31)).startDate());

        Plan plan = SamplePlan.create(TODAY);
        ViewState view = ViewState.defaults();
        Forecast forecast = ForecastEngine.forecast(plan, view.whatIf(), TODAY, view.showSkipped());
        List<String> rowIds = forecast.rowsBetween(plan.startDate(), plan.endDate()).stream().map(ForecastRow::rowId).toList();
        assertTrue(rowIds.stream().filter(id -> id.startsWith("r1@")).count() >= 12, rowIds.toString());
        assertTrue(rowIds.stream().anyMatch(id -> id.startsWith("t1")), rowIds.toString());
        assertTrue(rowIds.stream().filter(id -> id.startsWith("r4@")).count() >= 52, "каждую субботу");
    }

    private static void assertRule(RecurringRule rule, String title, Kind kind, Money amount, Recurrence recurrence,
                                   WeekendPolicy policy) {
        assertEquals(title, rule.title());
        assertEquals(kind, rule.kind());
        assertEquals(amount, rule.amount());
        assertEquals(recurrence, rule.recurrence());
        assertEquals(policy, rule.weekendPolicy());
        assertTrue(rule.enabled());
        assertNull(rule.from());
        assertNull(rule.until());
        assertEquals("", rule.category());
        assertEquals("", rule.note());
    }
}
