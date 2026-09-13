package ru.cashprediction.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Тесты контракта доменной модели: тексты правил, горизонт, сдвиг с выходных и редактирование плана.
 */
class ModelContractTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    @Test
    void recurrenceCanonicalRussianText() {
        assertEquals("ежемесячно 5", new Recurrence.Monthly(5, 1).toRussian());
        assertEquals("каждые 2 месяца 10", new Recurrence.Monthly(10, 2).toRussian());
        assertEquals("каждые 5 месяцев 1", new Recurrence.Monthly(1, 5).toRussian());
        assertEquals("еженедельно сб", new Recurrence.Weekly(DayOfWeek.SATURDAY, 1).toRussian());
        assertEquals("каждые 2 недели пт", new Recurrence.Weekly(DayOfWeek.FRIDAY, 2).toRussian());
        assertEquals("каждые 3 дня", new Recurrence.EveryNDays(3).toRussian());
        assertEquals("ежедневно", new Recurrence.EveryNDays(1).toRussian());
        assertEquals("ежегодно 03-15", new Recurrence.Yearly(MonthDay.of(3, 15)).toRussian());
        assertThrows(IllegalArgumentException.class, () -> new Recurrence.Monthly(32, 1));
        assertThrows(IllegalArgumentException.class, () -> new Recurrence.Monthly(5, 0));
    }

    @Test
    void horizonEndDateAndLabel() {
        assertEquals(LocalDate.of(2027, 8, 31), new Horizon.Months(12).endDate(START));
        assertEquals(LocalDate.of(2028, 8, 31), new Horizon.Years(2).endDate(START));
        assertEquals(LocalDate.of(2027, 1, 1), new Horizon.Until(LocalDate.of(2027, 1, 1)).endDate(START));
        assertEquals(START, new Horizon.Until(LocalDate.of(2020, 1, 1)).endDate(START), "дата до начала -> пустой горизонт в 1 день");
        assertEquals("12 месяцев", new Horizon.Months(12).label());
        assertEquals("1 месяц", new Horizon.Months(1).label());
        assertEquals("2 года", new Horizon.Years(2).label());
        assertEquals("до 2027-01-01", new Horizon.Until(LocalDate.of(2027, 1, 1)).label());
        assertEquals(12, new Horizon.Months(12).approximateMonths(START));
    }

    @Test
    void weekendPolicyShiftsOnlyWeekends() {
        LocalDate saturday = LocalDate.of(2026, 9, 5);
        LocalDate sunday = LocalDate.of(2026, 9, 6);
        LocalDate monday = LocalDate.of(2026, 9, 7);
        assertEquals(LocalDate.of(2026, 9, 4), WeekendPolicy.PREVIOUS_BUSINESS_DAY.apply(saturday));
        assertEquals(LocalDate.of(2026, 9, 4), WeekendPolicy.PREVIOUS_BUSINESS_DAY.apply(sunday));
        assertEquals(monday, WeekendPolicy.NEXT_BUSINESS_DAY.apply(saturday));
        assertEquals(monday, WeekendPolicy.NEXT_BUSINESS_DAY.apply(sunday));
        assertEquals(saturday, WeekendPolicy.NONE.apply(saturday));
        assertEquals(monday, WeekendPolicy.PREVIOUS_BUSINESS_DAY.apply(monday));
    }

    @Test
    void russianPlurals() {
        assertEquals("месяц", RuText.plural(21, "месяц", "месяца", "месяцев"));
        assertEquals("месяца", RuText.plural(3, "месяц", "месяца", "месяцев"));
        assertEquals("месяцев", RuText.plural(11, "месяц", "месяца", "месяцев"));
        assertEquals("месяцев", RuText.plural(112, "месяц", "месяца", "месяцев"));
        assertEquals(DayOfWeek.SATURDAY, RuText.parseWeekday("Суббота"));
        assertEquals(DayOfWeek.WEDNESDAY, RuText.parseWeekday("ср"));
    }

    @Test
    void dateFormatsAcceptBothForms() {
        assertEquals(LocalDate.of(2026, 10, 5), DateFormats.parse("2026-10-05"));
        assertEquals(LocalDate.of(2026, 10, 5), DateFormats.parse(" 05.10.2026 "));
        assertEquals("05.10.2026", DateFormats.ru(LocalDate.of(2026, 10, 5)));
        assertThrows(IllegalArgumentException.class, () -> DateFormats.parse("31.02.2026x"));
    }

    @Test
    void occurrenceKeyRowIdRoundTrip() {
        OccurrenceKey key = new OccurrenceKey(new RuleId("r2"), LocalDate.of(2026, 10, 1));
        assertEquals("r2@2026-10-01", key.asRowId());
        assertEquals(key, OccurrenceKey.parseRowId("r2@2026-10-01"));
    }

    @Test
    void planEditingIsImmutableAndKeepsOrder() {
        Plan plan = Plan.empty("Тест", START);
        RecurringRule salary = rule("r1", "Зарплата", Kind.INCOME, 80_000);
        RecurringRule rent = rule("r2", "Аренда", Kind.EXPENSE, 45_000);
        Plan p2 = plan.withRuleAdded(salary).withRuleAdded(rent);
        assertTrue(plan.rules().isEmpty(), "исходный план не меняется");
        assertEquals(List.of("r1", "r2"), p2.rules().stream().map(r -> r.id().value()).toList());
        assertEquals(new RuleId("r3"), p2.nextRuleId());

        Plan p3 = p2.withRuleReplaced(salary.withAmount(Money.ofMajor(90_000)));
        assertEquals(Money.ofMajor(90_000), p3.rules().get(0).amount(), "замена на месте");

        OccurrenceKey dec = new OccurrenceKey(salary.id(), LocalDate.of(2026, 12, 5));
        Plan p4 = p3.withAdjustmentPut(new Adjustment(dec, new Adjustment.ChangeAmount(Money.ofMajor(95_000)), ""))
                .withAdjustmentPut(new Adjustment(dec, new Adjustment.Skip(), "передумали"));
        assertEquals(1, p4.adjustments().size(), "повторная корректировка события заменяет прежнюю");
        assertTrue(p4.findAdjustment(dec).orElseThrow().action() instanceof Adjustment.Skip);

        Plan p5 = p4.withRuleRemoved(salary.id());
        assertTrue(p5.adjustments().isEmpty(), "удаление правила удаляет его корректировки");
        assertEquals(1, p5.rules().size());
    }

    @Test
    void nextIdsIgnoreManualIds() {
        Plan plan = Plan.empty("Тест", START)
                .withRuleAdded(rule("r7", "A", Kind.INCOME, 1))
                .withRuleAdded(rule("аренда", "B", Kind.EXPENSE, 1));
        assertEquals(new RuleId("r8"), plan.nextRuleId());
        assertEquals(new TxId("t1"), plan.nextTxId());
    }

    private static RecurringRule rule(String id, String title, Kind kind, long major) {
        return new RecurringRule(new RuleId(id), title, kind, Money.ofMajor(major), "", new Recurrence.Monthly(5, 1),
                null, null, WeekendPolicy.NONE, true, "");
    }
}
