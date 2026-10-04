package ru.cashprediction.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.forecast.Flags;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.model.WeekendPolicy;

/** Семантика prepared-предиката по независимым строкам, смене вида и настоящим исправлениям плана. */
class PreparedRowFilterTest {
    private static final LocalDate DATE = LocalDate.of(2026, 9, 13);
    private static final Flags SKIPPED = new Flags(false, false, false, true, false, false);
    private static final List<ForecastRow> ROWS = List.of(
            row(Origin.START, Kind.INCOME, "Start", "", "", SKIPPED),
            row(Origin.RULE, Kind.INCOME, "Ёж Weekly", "Other", "Memo", Flags.NONE),
            row(Origin.RULE, Kind.INCOME, "Salary", "ЖильЁ  Дом", "Memo", Flags.NONE),
            row(Origin.RULE, Kind.EXPENSE, "Rent", "Other", "До\u202fПереезда", Flags.NONE),
            row(Origin.ONE_TIME, Kind.INCOME, "Bonus", "Other", "Memo", Flags.NONE),
            row(Origin.WHAT_IF, Kind.INCOME, "Extra", "Other", "Ёж", Flags.NONE),
            row(Origin.RULE, Kind.EXPENSE, "Ёж skipped", "Other", "Memo", SKIPPED));

    /** Сопоставляет оба API с явными ответами, не используя старый matcher в качестве оракула. */
    @Test
    void singleAndPreparedFiltersMatchIndependentCases() {
        ViewState base = ViewState.defaults();
        List<Case> cases = List.of(
                new Case(base, List.of(0, 1, 2, 3, 4, 5)),
                new Case(base.withFilterText("  ЕЖ   WEEKLY "), List.of(0, 1)),
                new Case(base.withFilterText("жилье\u00a0дом"), List.of(0, 2)),
                new Case(base.withFilterText("  ДО   переезда "), List.of(0, 3)),
                new Case(base.withFilterText("еЖ"), List.of(0, 1, 5)),
                new Case(base.withFilterText("absent"), List.of(0)),
                new Case(base.withFilterText("weekly other"), List.of(0)),
                new Case(base.withFilterText("\u00a0\u202f\t"), List.of(0, 1, 2, 3, 4, 5)),
                new Case(base.withShowIncome(false), List.of(0, 3)),
                new Case(base.withShowExpense(false), List.of(0, 1, 2, 4, 5)),
                new Case(base.withShowOneTime(false), List.of(0, 1, 2, 3, 5)),
                new Case(base.withShowSkipped(true), List.of(0, 1, 2, 3, 4, 5, 6)),
                new Case(base.withShowSkipped(true).withFilterText("ЁЖ"), List.of(0, 1, 5, 6)),
                new Case(base.withShowIncome(false).withShowExpense(false).withShowOneTime(false)
                        .withFilterText("absent"), List.of(0)));
        for (Case test : cases) {
            Predicate<ForecastRow> prepared = test.view().rowFilter();
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < ROWS.size(); i++) {
                    boolean expected = test.acceptedIndexes().contains(i);
                    assertEquals(expected, prepared.test(ROWS.get(i)), "prepared row " + i);
                    assertEquals(expected, test.view().accepts(ROWS.get(i)), "single row " + i);
                }
            }
        }
        assertThrows(NullPointerException.class, () -> base.accepts(null));
        assertThrows(NullPointerException.class, () -> base.rowFilter().test(null));
    }

    /** Предикат фиксирует старый вид, но не привязан к id/идентичности строки или прежним текстам правила. */
    @Test
    void oldPredicateKeepsItsViewAndReadsEachRowsActualFields() {
        ViewState before = ViewState.defaults().withFilterText("  ЕЖ weekly ");
        Predicate<ForecastRow> old = before.rowFilter();
        assertTrue(old.test(ROWS.get(1)));
        ViewState after = before.withFilterText("ЖИЛЬЕ дом").withShowOneTime(false);
        Predicate<ForecastRow> next = after.rowFilter();
        assertFalse(next.test(ROWS.get(1)));
        assertTrue(next.test(ROWS.get(2)));
        assertTrue(old.test(ROWS.get(1)));
        assertFalse(old.test(ROWS.get(2)));
        ForecastRow changed = row(Origin.RULE, Kind.INCOME, "Different", "Other", "Memo", Flags.NONE);
        assertEquals(ROWS.get(1).rowId(), changed.rowId());
        assertFalse(old.test(changed));
        assertTrue(old.test(row(Origin.RULE, Kind.INCOME, "Different", "ЁЖ weekly", "", Flags.NONE)));
        assertTrue(old.test(row(Origin.RULE, Kind.INCOME, "Different", "", "ЁЖ weekly", Flags.NONE)));
        assertFalse(old.test(row(Origin.RULE, Kind.INCOME, "ЁЖ weekly", "", "", SKIPPED)));
    }

    /** Настоящий прогноз после override, замены правила, смены view и undo не наследует результаты по ruleId. */
    @Test
    void planEditsAndAdjustmentNotesDoNotReuseStaleRuleAnswers() {
        RecurringRule original = rule("Original title", "Original category", "Ёж old note");
        Plan plan = Plan.empty("Search", DATE).withHorizon(new Horizon.Months(1)).withRuleAdded(original);
        PlanDocument document = new PlanDocument(plan, null, () -> DATE);
        document.setViewState(ViewState.defaults().withFilterText("ЕЖ OLD NOTE"));
        Predicate<ForecastRow> retained = document.viewState().rowFilter();
        var initial = document.forecast();
        ForecastRow first = occurrence(initial.rows(), DATE);
        assertTrue(retained.test(first));

        document.edit("override", value -> value.withAdjustmentPut(new Adjustment(new OccurrenceKey(original.id(), DATE),
                new Adjustment.ChangeAmount(Money.ofMajor(120)), "Override ONLY")));
        var adjusted = document.forecast();
        assertNotSame(initial, adjusted);
        ForecastRow overridden = occurrence(adjusted.rows(), DATE);
        assertEquals("Override ONLY", overridden.note());
        assertFalse(retained.test(overridden));
        assertTrue(retained.test(occurrence(adjusted.rows(), DATE.plusDays(1))));
        Predicate<ForecastRow> override = document.viewState().withFilterText("override only").rowFilter();
        assertTrue(override.test(overridden));
        assertFalse(override.test(occurrence(adjusted.rows(), DATE.plusDays(1))));

        document.setViewState(document.viewState().withFilterText("override only"));
        assertSame(adjusted, document.forecast(), "Changing search leaves the financial forecast cached");
        document.edit("rule texts", value -> value.withRuleReplaced(rule("Changed title", "New category", "New note")));
        var changed = document.forecast();
        assertFalse(retained.test(occurrence(changed.rows(), DATE.plusDays(1))));
        assertTrue(document.viewState().rowFilter().test(occurrence(changed.rows(), DATE)));
        assertTrue(document.viewState().withFilterText("NEW NOTE").rowFilter().test(occurrence(changed.rows(), DATE.plusDays(1))));
        document.undo();
        assertTrue(retained.test(occurrence(document.forecast().rows(), DATE.plusDays(1))));
        assertFalse(retained.test(occurrence(document.forecast().rows(), DATE)));
        document.undo();
        assertTrue(retained.test(occurrence(document.forecast().rows(), DATE)));
    }

    /** Создаёт независимую строку; несколько строк намеренно имеют один и тот же ruleId/date. */
    private static ForecastRow row(Origin origin, Kind kind, String title, String category, String note, Flags flags) {
        return new ForecastRow(DATE, DATE, title, kind, category, kind.signed(Money.ofMajor(100)), Money.ofMajor(1_000),
                origin, origin == Origin.RULE ? new RuleId("r1") : null, origin == Origin.ONE_TIME ? new TxId("t1") : null,
                flags, note);
    }

    /** Создаёт правило с неизменным id для проверки замены фактических текстов. */
    private static RecurringRule rule(String title, String category, String note) {
        return new RecurringRule(new RuleId("r1"), title, Kind.INCOME, Money.ofMajor(100), category,
                new Recurrence.EveryNDays(1), DATE, DATE.plusDays(2), WeekendPolicy.NONE, true, note);
    }

    /** Находит проверяемую строку прогноза, не вычисляя из неё ожидаемые совпадения. */
    private static ForecastRow occurrence(List<ForecastRow> rows, LocalDate date) {
        return rows.stream().filter(row -> row.origin() == Origin.RULE && row.date().equals(date)).findFirst().orElseThrow();
    }

    /** Вид и независимо заданные индексы принимаемых строк. */
    private record Case(ViewState view, List<Integer> acceptedIndexes) { }
}
