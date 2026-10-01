package ru.cashprediction.core.ui.forms.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.Problem;

/** Проверяет смысл дат и точный текст предупреждения редактора правила по §6.3, независимо от goldens. */
class RuleEditorSpecFidelityTest {

    private static final RuleId RULE = new RuleId("r1");

    @ParameterizedTest
    @CsvSource({
            "PREVIOUS_BUSINESS_DAY, 'пт, 04.12.2026  ⇄ с сб, 05.12.2026  ✎ корректировка'",
            "NEXT_BUSINESS_DAY, 'пн, 07.12.2026  ⇄ с сб, 05.12.2026  ✎ корректировка'"
    })
    void previewShowsActualDateThenNominalAndKeepsNominalChildContext(WeekendPolicy policy, String expected) {
        Plan plan = plan(policy);
        LocalDate nominal = LocalDate.of(2026, 12, 5);
        plan = plan.withAdjustmentPut(adjustment(RULE, nominal));
        RuleEditorForm form = new RuleEditorForm();
        FormContext context = context(plan);
        // Декабрьская суббота проверяет оба направления переноса, не полагаясь на форматтер реализации.
        FormState state = new FormState(0, form.defaults(context), 2);
        assertEquals(expected, form.evaluate(state, context).preview().get(2).text());
        FormOutcome.OpenChild child = assertInstanceOf(FormOutcome.OpenChild.class,
                form.onPreview(2, true, state, context));
        assertEquals("2026-12-05", child.child().contextValue(WindowType.CONTEXT_ORIGINAL_DATE));
    }

    @Test
    void unshiftedPreviewHasOnlyAdjustmentSuffixWithTwoSpaces() {
        Plan plan = plan(WeekendPolicy.NONE).withAdjustmentPut(adjustment(RULE, LocalDate.of(2026, 10, 5)));
        RuleEditorForm form = new RuleEditorForm();
        FormContext context = context(plan);
        var preview = form.evaluate(new FormState(0, form.defaults(context)), context).preview();
        assertEquals("пн, 05.10.2026  ✎ корректировка", preview.getFirst().text());
        assertEquals("чт, 05.11.2026", preview.get(1).text());
    }

    @ParameterizedTest
    @CsvSource({
            "1, '1 корректировка перестанет совпадать с датами правила'",
            "2, '2 корректировки перестанут совпадать с датами правила'",
            "5, '5 корректировок перестанут совпадать с датами правила'",
            "11, '11 корректировок перестанут совпадать с датами правила'",
            "21, '21 корректировка перестанет совпадать с датами правила'"
    })
    void proposedRecurrenceCountsOnlyItsNonmatchingNominalDatesIncludingOutsideHorizon(int count, String expected) {
        Plan plan = plan(WeekendPolicy.PREVIOUS_BUSINESS_DAY);
        for (int index = 0; index < count; index++) {
            // Часть дат позже конца плана: привязка корректировки не ограничена горизонтом предпросмотра.
            plan = plan.withAdjustmentPut(adjustment(RULE, LocalDate.of(2026, 10, 5).plusMonths(index)));
        }
        plan = plan.withAdjustmentPut(adjustment(RULE, LocalDate.of(2026, 10, 6)))
                .withAdjustmentPut(adjustment(new RuleId("r2"), LocalDate.of(2026, 10, 5)));
        RuleEditorForm form = new RuleEditorForm();
        FormContext context = context(plan);
        Map<String, String> fields = new LinkedHashMap<>(form.defaults(context));
        fields.put("dayOfMonth", "6");
        var view = form.evaluate(new FormState(0, fields), context);
        assertEquals(Problem.Severity.WARNING, view.problem().severity());
        assertEquals(count, Integer.parseInt(view.problem().text().split(" ", 2)[0]));
        assertTrue(view.buttons().get("ok").enabled());
        assertEquals(count + 2, context.app().document().plan().adjustments().size());
        assertEquals(expected, view.problem().text());
    }

    @Test
    void changingWeekendPolicyDoesNotOrphanAnAdjustmentKeyedByNominalDate() {
        Plan plan = plan(WeekendPolicy.NONE).withAdjustmentPut(adjustment(RULE, LocalDate.of(2026, 12, 5)));
        RuleEditorForm form = new RuleEditorForm();
        FormContext context = context(plan);
        Map<String, String> fields = new LinkedHashMap<>(form.defaults(context));
        fields.put("weekendPolicy", "PREVIOUS_BUSINESS_DAY");
        assertEquals(Problem.NONE, form.evaluate(new FormState(0, fields), context).problem());
    }

    @Test
    void proposedUntilCountsLostDatesEvenWhenTheyAreNotInTheSixDatePreview() {
        Plan plan = plan(WeekendPolicy.NONE)
                .withAdjustmentPut(adjustment(RULE, LocalDate.of(2026, 10, 5)))
                .withAdjustmentPut(adjustment(RULE, LocalDate.of(2027, 7, 5)));
        RuleEditorForm form = new RuleEditorForm();
        FormContext context = context(plan);
        Map<String, String> fields = new LinkedHashMap<>(form.defaults(context));
        fields.put("untilEnabled", "true");
        fields.put("until", "30.06.2027");
        assertEquals("1 корректировка перестанет совпадать с датами правила",
                form.evaluate(new FormState(0, fields), context).problem().text());
    }

    /** Создаёт корректировку, привязанную к исходной дате события. */
    private static Adjustment adjustment(RuleId ruleId, LocalDate nominal) {
        return new Adjustment(new OccurrenceKey(ruleId, nominal), new Adjustment.Skip(), "");
    }

    /** Создаёт план с ежемесячным правилом пятого числа и заданной политикой выходных. */
    private static Plan plan(WeekendPolicy policy) {
        RecurringRule rule = new RecurringRule(RULE, "Доход", Kind.INCOME, Money.ofMajor(100), "",
                new Recurrence.Monthly(5, 1), null, null, policy, true, "");
        return new Plan("План", "", "₽", LocalDate.of(2026, 9, 1), Money.ZERO, new Horizon.Months(12),
                Money.ZERO, null, List.of(rule), List.of(), List.of(), List.of());
    }

    /** Создаёт окружение редактирования существующего правила без запуска контроллера. */
    private static FormContext context(Plan plan) {
        return new FormContext("w1", "main", Map.of(WindowType.CONTEXT_MODE, WindowType.MODE_EDIT,
                WindowType.CONTEXT_RULE_ID, RULE.value()),
                FakeStates.withPlan(ClientProfile.fx("25"), plan, Path.of("CashMemory")));
    }
}
