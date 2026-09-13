package ru.cashprediction.core.recurrence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.WeekendPolicy;

/**
 * Тесты генератора дат: прижатие дня к концу месяца, фазы «каждые N», окно «С/По», сдвиг с выходных, лимиты.
 */
class OccurrenceGeneratorTest {

    private static final LocalDate PLAN_START = d("2026-09-01");
    private static final LocalDate PLAN_END = d("2027-08-31");

    /** 31-е число прижимается к 30/28/29 (включая високосный 2028 год). */
    @Test
    void day31ClampsToLastDayOfMonth() {
        List<LocalDate> dates = OccurrenceGenerator.nominalDates(rule(new Recurrence.Monthly(31, 1), null, null),
                d("2027-01-01"), d("2028-12-31"));
        assertEquals(24, dates.size());
        assertEquals(d("2027-01-31"), dates.get(0));
        assertEquals(d("2027-02-28"), dates.get(1));
        assertEquals(d("2027-04-30"), dates.get(3));
        assertEquals(d("2028-02-29"), dates.get(13), "високосный год");
        assertEquals(d("2028-12-31"), dates.get(23));
    }

    /** 29 и 30 число в феврале. */
    @Test
    void days29And30InFebruary() {
        assertEquals(dates("2027-01-29", "2027-02-28", "2027-03-29"),
                OccurrenceGenerator.nominalDates(rule(new Recurrence.Monthly(29, 1), null, null), d("2027-01-01"), d("2027-03-31")));
        assertEquals(dates("2028-02-29", "2028-03-30"),
                OccurrenceGenerator.nominalDates(rule(new Recurrence.Monthly(30, 1), null, null), d("2028-02-01"), d("2028-03-31")));
        assertEquals(dates("2028-02-29"),
                OccurrenceGenerator.nominalDates(rule(new Recurrence.Monthly(29, 1), null, null), d("2028-02-01"), d("2028-02-29")));
    }

    /** «Каждые 2 месяца» отсчитываются от «С» правила, а без него — от начала плана. */
    @Test
    void everyTwoMonthsPhaseAnchoredOnFromOrPlanStart() {
        Recurrence every2 = new Recurrence.Monthly(10, 2);
        assertEquals(dates("2026-10-10", "2026-12-10", "2027-02-10", "2027-04-10", "2027-06-10", "2027-08-10"),
                OccurrenceGenerator.nominalDates(rule(every2, d("2026-10-01"), null), PLAN_START, PLAN_END));
        assertEquals(dates("2026-09-10", "2026-11-10", "2027-01-10", "2027-03-10", "2027-05-10", "2027-07-10"),
                OccurrenceGenerator.nominalDates(rule(every2, null, null), PLAN_START, PLAN_END));
        // Начало плана перенесли вперёд, но «С» правила осталось: фаза (чётные месяцы) не меняется.
        assertEquals(dates("2026-12-10", "2027-02-10", "2027-04-10"),
                OccurrenceGenerator.nominalDates(rule(every2, d("2026-10-01"), null), d("2026-11-15"), d("2027-04-30")));
    }

    /** Опорная дата в далёком прошлом: фаза «каждые 3 месяца» сохраняется и перебор быстрый. */
    @Test
    void farAnchorKeepsPhase() {
        assertEquals(dates("2026-10-31"),
                OccurrenceGenerator.nominalDates(rule(new Recurrence.Monthly(31, 3), d("2000-01-31"), null),
                        PLAN_START, d("2026-12-31")));
    }

    /** «Ежемесячно 5» с «С 10.09.2026» начинается с 05.10.2026. */
    @Test
    void monthlyDay5FromMidMonthStartsNextMonth() {
        assertEquals(dates("2026-10-05", "2026-11-05", "2026-12-05"),
                OccurrenceGenerator.nominalDates(rule(new Recurrence.Monthly(5, 1), d("2026-09-10"), null), PLAN_START, d("2026-12-31")));
    }

    /** «Каждые 2 недели пт»: первая пятница на или после опорной даты, далее шаг 14 дней. */
    @Test
    void weeklyEveryTwoWeeksPhase() {
        Recurrence fridays = new Recurrence.Weekly(DayOfWeek.FRIDAY, 2);
        LocalDate end = d("2026-10-10");
        assertEquals(dates("2026-09-04", "2026-09-18", "2026-10-02"),
                OccurrenceGenerator.nominalDates(rule(fridays, null, null), PLAN_START, end));
        assertEquals(dates("2026-09-11", "2026-09-25", "2026-10-09"),
                OccurrenceGenerator.nominalDates(rule(fridays, d("2026-09-10"), null), PLAN_START, end));
        // «С» раньше начала плана: фаза считается от «С», а не от начала плана.
        assertEquals(dates("2026-09-18", "2026-10-02"),
                OccurrenceGenerator.nominalDates(rule(fridays, d("2026-09-01"), null), d("2026-09-10"), end));
        assertEquals(dates("2026-09-05", "2026-09-12", "2026-09-19", "2026-09-26"),
                OccurrenceGenerator.nominalDates(rule(new Recurrence.Weekly(DayOfWeek.SATURDAY, 1), null, null),
                        PLAN_START, d("2026-09-30")));
    }

    /** «Каждые N дней» от опорной даты, в том числе когда опора раньше начала плана. */
    @Test
    void everyNDaysFromAnchor() {
        Recurrence every3 = new Recurrence.EveryNDays(3);
        LocalDate end = d("2026-09-12");
        assertEquals(dates("2026-09-02", "2026-09-05", "2026-09-08", "2026-09-11"),
                OccurrenceGenerator.nominalDates(rule(every3, d("2026-09-02"), null), PLAN_START, end));
        assertEquals(dates("2026-09-03", "2026-09-06", "2026-09-09", "2026-09-12"),
                OccurrenceGenerator.nominalDates(rule(every3, d("2026-08-31"), null), PLAN_START, end));
        assertEquals(dates("2026-09-01", "2026-09-04", "2026-09-07", "2026-09-10"),
                OccurrenceGenerator.nominalDates(rule(every3, null, null), PLAN_START, end));
    }

    /** «Ежегодно 02-29» даёт 28.02 в невисокосные годы. */
    @Test
    void yearlyFebruary29() {
        assertEquals(dates("2027-02-28", "2028-02-29", "2029-02-28"),
                OccurrenceGenerator.nominalDates(rule(new Recurrence.Yearly(MonthDay.of(2, 29)), null, null),
                        d("2027-01-01"), d("2029-12-31")));
        assertEquals(dates("2027-03-15"),
                OccurrenceGenerator.nominalDates(rule(new Recurrence.Yearly(MonthDay.of(3, 15)), null, null), PLAN_START, PLAN_END));
    }

    /** Окно «С/По» ограничивает даты внутри горизонта. */
    @Test
    void fromUntilWindow() {
        assertEquals(dates("2026-12-01", "2027-01-01", "2027-02-01"),
                OccurrenceGenerator.nominalDates(rule(new Recurrence.Monthly(1, 1), d("2026-11-15"), d("2027-02-01")), PLAN_START, PLAN_END));
    }

    /** Окно правила вне горизонта — дат нет. */
    @Test
    void windowOutsideHorizonIsEmpty() {
        Recurrence monthly = new Recurrence.Monthly(5, 1);
        assertTrue(OccurrenceGenerator.nominalDates(rule(monthly, d("2028-01-01"), null), PLAN_START, PLAN_END).isEmpty());
        assertTrue(OccurrenceGenerator.nominalDates(rule(monthly, null, d("2026-08-01")), PLAN_START, PLAN_END).isEmpty());
        assertTrue(OccurrenceGenerator.nominalDates(rule(monthly, d("2027-01-01"), d("2026-12-01")), PLAN_START, PLAN_END).isEmpty(),
                "«По» раньше «С»");
    }

    /** Предпросмотр «Ближайшие даты» применяет сдвиг с выходных и не зависит от горизонта. */
    @Test
    void upcomingWithWeekendShift() {
        RecurringRule salary = new RecurringRule(new RuleId("r1"), "Зарплата", Kind.INCOME, Money.ofMajor(80_000), "",
                new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.PREVIOUS_BUSINESS_DAY, true, "");
        List<Occurrence> next = OccurrenceGenerator.upcoming(salary, PLAN_START, PLAN_START, 4);
        assertEquals(List.of(
                new Occurrence(d("2026-09-05"), d("2026-09-04")),
                new Occurrence(d("2026-10-05"), d("2026-10-05")),
                new Occurrence(d("2026-11-05"), d("2026-11-05")),
                new Occurrence(d("2026-12-05"), d("2026-12-04"))), next);
        assertTrue(next.get(0).shifted());
        assertFalse(next.get(1).shifted());

        assertEquals(30, OccurrenceGenerator.upcoming(salary, PLAN_START, PLAN_START, 30).size(), "горизонт не ограничивает");
        assertEquals(240, OccurrenceGenerator.upcoming(salary, PLAN_START, PLAN_START, 1000).size(), "поиск ограничен 20 годами");
        assertEquals(3, OccurrenceGenerator.upcoming(salary.withUntil(d("2026-11-30")), PLAN_START, PLAN_START, 10).size());
        assertTrue(OccurrenceGenerator.upcoming(salary, PLAN_START, PLAN_START, 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> OccurrenceGenerator.upcoming(salary, PLAN_START, PLAN_START, -1));

        RecurringRule farFuture = rule(new Recurrence.Yearly(MonthDay.of(1, 1)), d("2050-01-01"), null);
        assertTrue(OccurrenceGenerator.upcoming(farFuture, PLAN_START, PLAN_START, 5).isEmpty(), "дальше 20 лет не ищем");
        // Опорная дата «С» учитывается и в предпросмотре.
        assertEquals(d("2026-10-05"), OccurrenceGenerator.upcoming(
                rule(new Recurrence.Monthly(5, 1), d("2026-09-10"), null), PLAN_START, PLAN_START, 1).get(0).nominal());
    }

    /** Сдвиг, выводящий за горизонт, не применяется. */
    @Test
    void shiftOutsideHorizonKeepsNominal() {
        RecurringRule next = new RecurringRule(new RuleId("r1"), "A", Kind.INCOME, Money.ofMajor(1), "",
                new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.NEXT_BUSINESS_DAY, true, "");
        assertEquals(List.of(new Occurrence(d("2026-09-05"), d("2026-09-05"))),
                OccurrenceGenerator.occurrences(next, PLAN_START, d("2026-09-06")));
        assertEquals(List.of(new Occurrence(d("2026-09-05"), d("2026-09-07"))),
                OccurrenceGenerator.occurrences(next, PLAN_START, d("2026-09-30")));
        RecurringRule previous = new RecurringRule(new RuleId("r2"), "B", Kind.INCOME, Money.ofMajor(1), "",
                new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.PREVIOUS_BUSINESS_DAY, true, "");
        assertEquals(d("2026-09-05"), OccurrenceGenerator.shiftWithinHorizon(previous, d("2026-09-05"), d("2026-09-05"), d("2026-09-30")));
    }

    /** Больше 200 000 дат на правило — ошибка с русским сообщением. */
    @Test
    void tooManyDatesRejected() {
        RecurringRule daily = rule(new Recurrence.EveryNDays(1), null, null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> OccurrenceGenerator.nominalDates(daily, d("2000-01-01"), d("2600-01-01")));
        // Этап S0.5: текст из каталога (occurrence.error.tooManyDates) совпадает с прежним буква в букву.
        assertEquals("Правило r1 «Тест» даёт больше 200 000 дат; сократите горизонт плана или увеличьте период повтора",
                e.getMessage());
        // Ровно на границе лимита ещё допустимо.
        LocalDate start = d("2000-01-01");
        assertEquals(OccurrenceGenerator.MAX_DATES_PER_RULE,
                OccurrenceGenerator.nominalDates(daily, start, start.plusDays(OccurrenceGenerator.MAX_DATES_PER_RULE - 1)).size());
    }

    private static RecurringRule rule(Recurrence recurrence, LocalDate from, LocalDate until) {
        return new RecurringRule(new RuleId("r1"), "Тест", Kind.EXPENSE, Money.ofMajor(100), "", recurrence,
                from, until, WeekendPolicy.NONE, true, "");
    }

    private static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    private static List<LocalDate> dates(String... iso) {
        return Stream.of(iso).map(LocalDate::parse).toList();
    }
}
