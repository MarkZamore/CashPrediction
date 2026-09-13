package ru.cashprediction.core.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

/**
 * Тесты проверки плана: суммы, окна правил, горизонт, имя плана, оценка числа строк.
 */
class PlanValidatorTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    /** Корректный план из примера формата не даёт ошибок и предупреждений. */
    @Test
    void validPlanHasNoErrorsOrWarnings() {
        Plan p = base(List.of(
                rule("r1", "Зарплата", Money.ofMajor(80_000), new Recurrence.Monthly(5, 1), START, null),
                rule("r4", "Продукты", Money.ofMajor(4_000), new Recurrence.Weekly(DayOfWeek.SATURDAY, 1), null, null),
                rule("r5", "Страховка авто", Money.ofMajor(30_000), new Recurrence.Yearly(MonthDay.of(3, 15)), null, null),
                rule("r6", "Абонемент", Money.ofMajor(3_500), new Recurrence.Monthly(10, 2), LocalDate.of(2026, 10, 1), null),
                rule("r8", "Кредит", PlanValidator.MAX_AMOUNT, new Recurrence.Monthly(31, 1), null, LocalDate.of(2027, 3, 31))))
                .withGoal(new Goal("Отпуск", Money.ofMajor(300_000), LocalDate.of(2027, 6, 1)))
                .withCushion(Money.ofMajor(50_000));
        List<Diagnostic> result = PlanValidator.validate(p);
        assertTrue(result.stream().allMatch(x -> x.severity() == Severity.INFO), result.toString());
        assertTrue(result.isEmpty(), result.toString());
    }

    /** Суммы операций: больше нуля и не больше максимума. */
    @Test
    void amountsMustBePositiveAndBounded() {
        Plan p = base(List.of(
                rule("r1", "Ноль", Money.ZERO, new Recurrence.Monthly(5, 1), null, null),
                rule("r2", "Минус", Money.ofMajor(-5), new Recurrence.Monthly(5, 1), null, null),
                rule("r3", "Слишком много", Money.ofMinor(100_000_000_000_000L), new Recurrence.Monthly(5, 1), null, null)))
                .withOneTimeAdded(new OneTimeTransaction(new TxId("t1"), START, "Разовая", Kind.INCOME, Money.ZERO, "", ""));
        List<Diagnostic> errors = only(PlanValidator.validate(p), Severity.ERROR);
        assertEquals(4, errors.size(), errors.toString());
        assertTrue(errors.get(0).message().contains("r1"));
        assertTrue(errors.get(2).message().contains("999 999 999 999,99"));
    }

    /** «По» раньше «С» — ошибка; окно вне горизонта — предупреждение. */
    @Test
    void ruleWindows() {
        Plan p = base(List.of(
                rule("r1", "Наоборот", Money.ofMajor(1), new Recurrence.Monthly(5, 1), LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 1)),
                rule("r2", "Потом", Money.ofMajor(1), new Recurrence.Monthly(5, 1), LocalDate.of(2030, 1, 1), null),
                rule("r3", "Выкл", Money.ofMajor(1), new Recurrence.Monthly(5, 1), LocalDate.of(2030, 1, 1), null).withEnabled(false)));
        List<Diagnostic> result = PlanValidator.validate(p);
        assertEquals(1, only(result, Severity.ERROR).size(), result.toString());
        assertTrue(only(result, Severity.ERROR).get(0).message().contains("r1"));
        List<Diagnostic> warnings = only(result, Severity.WARNING);
        assertEquals(1, warnings.size(), result.toString());
        assertTrue(warnings.get(0).message().contains("r2"));
    }

    /** «Каждые N» без «С» — сведения; пустое название — предупреждение. */
    @Test
    void anchorInfoAndEmptyTitle() {
        Plan p = base(List.of(
                rule("r1", "Кофе", Money.ofMajor(300), new Recurrence.EveryNDays(3), null, null),
                rule("r2", "", Money.ofMajor(1), new Recurrence.Monthly(5, 1), null, null)));
        List<Diagnostic> result = PlanValidator.validate(p);
        List<Diagnostic> infos = only(result, Severity.INFO);
        assertEquals(1, infos.size(), result.toString());
        assertTrue(infos.get(0).message().contains("отсчёт от даты начала плана"));
        List<Diagnostic> warnings = only(result, Severity.WARNING);
        assertEquals(1, warnings.size(), result.toString());
        assertTrue(warnings.get(0).message().contains("название"));
    }

    /** Корректировки «изменить»/«заменить» с неположительной суммой — ошибка. */
    @Test
    void adjustmentAmounts() {
        RecurringRule r1 = rule("r1", "Зарплата", Money.ofMajor(1), new Recurrence.Monthly(5, 1), null, null);
        OccurrenceKey key = new OccurrenceKey(r1.id(), LocalDate.of(2026, 10, 5));
        OccurrenceKey key2 = new OccurrenceKey(r1.id(), LocalDate.of(2026, 11, 5));
        OccurrenceKey key3 = new OccurrenceKey(r1.id(), LocalDate.of(2026, 12, 5));
        Plan p = base(List.of(r1)).withAdjustments(List.of(
                new Adjustment(key, new Adjustment.ChangeAmount(Money.ZERO), ""),
                new Adjustment(key2, new Adjustment.Replace(Money.ofMajor(-1), LocalDate.of(2026, 11, 7)), ""),
                new Adjustment(key3, new Adjustment.MoveDate(LocalDate.of(2026, 12, 7)), "")));
        List<Diagnostic> errors = only(PlanValidator.validate(p), Severity.ERROR);
        assertEquals(2, errors.size(), errors.toString());
        assertTrue(errors.get(0).message().contains("05.10.2026"));
    }

    /** Цель с неположительной суммой — предупреждение. */
    @Test
    void goalTarget() {
        List<Diagnostic> result = PlanValidator.validate(base(List.of()).withGoal(new Goal("Ничего", Money.ZERO, null)));
        assertEquals(1, only(result, Severity.WARNING).size(), result.toString());
    }

    /** Горизонт: длиннее 240 месяцев — предупреждение, «до даты» раньше начала или длиннее 50 лет — ошибка. */
    @Test
    void horizonChecks() {
        assertTrue(PlanValidator.validate(base(List.of()).withHorizon(new Horizon.Months(240))).isEmpty());
        List<Diagnostic> long300 = PlanValidator.validate(base(List.of()).withHorizon(new Horizon.Months(300)));
        assertEquals(1, only(long300, Severity.WARNING).size(), long300.toString());
        assertEquals(1, only(PlanValidator.validate(base(List.of()).withHorizon(new Horizon.Until(LocalDate.of(2026, 1, 1)))), Severity.ERROR).size());
        assertEquals(1, only(PlanValidator.validate(base(List.of()).withHorizon(new Horizon.Until(LocalDate.of(2090, 1, 1)))), Severity.ERROR).size());
    }

    /** Оценка больше 200 000 строк — ошибка. */
    @Test
    void tooManyRows() {
        Plan p = base(List.of(rule("r1", "Каждый день", Money.ofMajor(1), new Recurrence.EveryNDays(1), START, null)))
                .withHorizon(new Horizon.Until(START.plusYears(600)));
        assertTrue(PlanValidator.estimateRowCount(p) > PlanValidator.MAX_ROWS);
        List<Diagnostic> errors = only(PlanValidator.validate(p), Severity.ERROR);
        assertTrue(errors.stream().anyMatch(e -> e.message().contains("200 000")), errors.toString());
    }

    /** Оценка числа строк не меньше фактического и близка к нему. */
    @Test
    void estimateRowCountIsUpperBound() {
        Plan p = base(List.of(
                rule("r1", "Зарплата", Money.ofMajor(1), new Recurrence.Monthly(5, 1), null, null),
                rule("r2", "Продукты", Money.ofMajor(1), new Recurrence.Weekly(DayOfWeek.SATURDAY, 1), null, null),
                rule("r3", "Кофе", Money.ofMajor(1), new Recurrence.EveryNDays(3), START, null),
                rule("r4", "Страховка", Money.ofMajor(1), new Recurrence.Yearly(MonthDay.of(3, 15)), null, null),
                rule("r5", "Выкл", Money.ofMajor(1), new Recurrence.EveryNDays(1), START, null).withEnabled(false)))
                .withOneTimeAdded(new OneTimeTransaction(new TxId("t1"), START, "Разовая", Kind.INCOME, Money.ofMajor(1), "", ""));
        long actual = ForecastEngine.forecast(p, WhatIf.NONE, START, true).rows().size();
        long estimate = PlanValidator.estimateRowCount(p);
        assertTrue(estimate >= actual, estimate + " < " + actual);
        assertTrue(estimate <= actual + 4, estimate + " слишком далеко от " + actual);
    }

    /** Допустимые имена плана. */
    @ParameterizedTest
    @ValueSource(strings = {"Семейный бюджет 2026", "a", "План (черновик) №2", "web-session-копия"})
    void acceptsNames(String name) {
        assertTrue(PlanValidator.checkPlanName(name).isEmpty(), name);
        assertTrue(PlanValidator.checkPlanName("я".repeat(80)).isEmpty());
    }

    /** Недопустимые имена плана. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "a/b", "a\\b", "a:b", "что?", "a*b", "\"q\"", "<x>", "a|b", "settings", "SETTINGS",
        "Web-Session.Plan", "session-fx", "session-swing", "web-session", "con", "plan."})
    void rejectsNames(String name) {
        assertTrue(PlanValidator.checkPlanName(name).isPresent(), name);
        assertFalse(PlanValidator.checkPlanName("я".repeat(81)).isEmpty());
        assertTrue(PlanValidator.checkPlanName(null).isPresent());
    }

    /** Зарезервированные имена ровно те, что заняты служебными файлами. */
    @Test
    void reservedNames() {
        assertEquals(5, PlanValidator.RESERVED_NAMES.size());
        assertTrue(PlanValidator.RESERVED_NAMES.contains("web-session.plan"));
    }

    private static Plan base(List<RecurringRule> rules) {
        return new Plan("Семейный бюджет 2026", "", "₽", START, Money.ofMajor(150_000), new Horizon.Months(12), Money.ZERO, null,
                rules, List.of(), List.of(), List.of());
    }

    private static RecurringRule rule(String id, String title, Money amount, Recurrence recurrence, LocalDate from, LocalDate until) {
        return new RecurringRule(new RuleId(id), title, Kind.EXPENSE, amount, "", recurrence, from, until, WeekendPolicy.NONE, true, "");
    }

    private static List<Diagnostic> only(List<Diagnostic> list, Severity severity) {
        return list.stream().filter(x -> x.severity() == severity).toList();
    }
}
