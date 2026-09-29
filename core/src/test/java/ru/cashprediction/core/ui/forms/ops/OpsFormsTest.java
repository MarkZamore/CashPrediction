package ru.cashprediction.core.ui.forms.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.model.Adjustment;
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
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.Problem;

/** Проверяет редакторы регулярных, разовых и скорректированных событий (§5.6.1, §6.3-§6.5). */
class OpsFormsTest {

    private static final Path MEMORY = Path.of("CashMemory");

    @Test
    void ruleDefaultsAndVisibilityFollowRecurrenceKind() {
        RuleEditorForm form = new RuleEditorForm();
        FormContext context = context(Map.of(WindowType.CONTEXT_MODE, WindowType.MODE_CREATE));
        Map<String, String> defaults = form.defaults(context);
        assertEquals("MONTHLY", defaults.get("recurrenceKind"));
        assertEquals("13", defaults.get("dayOfMonth"));
        FormState yearly = new FormState(0, replace(defaults, "recurrenceKind", "YEARLY"));
        var view = form.evaluate(yearly, context);
        assertFalse(view.fields().get("dayOfMonth").visible());
        assertFalse(view.fields().get("everyN").visible());
        assertTrue(view.fields().get("monthDay").visible());
        assertEquals(Problem.Severity.ERROR, view.problem().severity());
    }

    @Test
    void ruleCreatesPreviewAndChildAdjustmentForEditedRule() {
        Plan plan = plan();
        RuleEditorForm form = new RuleEditorForm();
        FormContext context = context(plan, Map.of(WindowType.CONTEXT_MODE, WindowType.MODE_EDIT, WindowType.CONTEXT_RULE_ID, "r1"));
        FormState state = new FormState(0, form.defaults(context), 0);
        var view = form.evaluate(state, context);
        assertEquals(6, view.preview().size());
        assertTrue(view.buttons().get("adjustSelected").enabled());
        FormOutcome result = form.onPreview(0, true, state, context);
        FormOutcome.OpenChild child = assertInstanceOf(FormOutcome.OpenChild.class, result);
        assertEquals(WindowType.ADJUSTMENT_EDITOR, child.child().type());
        assertEquals("r1", child.child().contextValue(WindowType.CONTEXT_RULE_ID));
        assertEquals("2026-10-05", child.child().contextValue(WindowType.CONTEXT_ORIGINAL_DATE));
    }

    @Test
    void ruleRejectsIncompleteRangeAndSavesValidRule() {
        RuleEditorForm form = new RuleEditorForm();
        FormContext context = context(Map.of(WindowType.CONTEXT_MODE, WindowType.MODE_CREATE));
        Map<String, String> values = replace(form.defaults(context), "title", "Зарплата", "amount", "80000", "fromEnabled", "true", "from", "");
        assertFalse(form.evaluate(new FormState(0, values), context).buttons().get("ok").enabled());
        values = replace(values, "from", "2026-09-13");
        FormOutcome.Close close = assertInstanceOf(FormOutcome.Close.class, form.onButton("ok", new FormState(0, values), context));
        RecurringRule saved = assertInstanceOf(RecurringRule.class, close.result());
        assertEquals("r2", saved.id().value());
        assertEquals(Money.parse("80000"), saved.amount());
    }

    @Test
    void oneTimeUsesContextDateAndWarnsOutsideHorizon() {
        OneTimeForm form = new OneTimeForm();
        FormContext context = context(Map.of("date", "2028-01-01", WindowType.CONTEXT_MODE, WindowType.MODE_CREATE));
        assertEquals("2028-01-01", form.defaults(context).get("date"));
        Map<String, String> values = replace(form.defaults(context), "title", "Ноутбук", "amount", "90000");
        var view = form.evaluate(new FormState(0, values), context);
        assertEquals(Problem.Severity.WARNING, view.problem().severity());
        FormOutcome.Close close = assertInstanceOf(FormOutcome.Close.class, form.onButton("ok", new FormState(0, values), context));
        OneTimeTransaction tx = assertInstanceOf(OneTimeTransaction.class, close.result());
        assertEquals(new TxId("t1"), tx.id());
        assertEquals(Kind.EXPENSE, tx.kind());
    }

    @Test
    void adjustmentEnablesOnlyFieldsNeededForActionAndCanReset() {
        AdjustmentForm form = new AdjustmentForm();
        FormContext context = context(plan(), Map.of(WindowType.CONTEXT_RULE_ID, "r1", WindowType.CONTEXT_ORIGINAL_DATE, "2026-10-05"));
        Map<String, String> values = replace(form.defaults(context), "action", "MOVE");
        var view = form.evaluate(new FormState(0, values), context);
        assertFalse(view.fields().get("amount").enabled());
        assertTrue(view.fields().get("date").enabled());
        FormOutcome.Close reset = assertInstanceOf(FormOutcome.Close.class, form.onButton("reset", new FormState(0, values), context));
        AdjustmentForm.Result result = assertInstanceOf(AdjustmentForm.Result.class, reset.result());
        assertNull(result.adjustment());
    }

    @Test
    void adjustmentMissingRuleIsReadOnlyAndOnlyCloses() {
        AdjustmentForm form = new AdjustmentForm();
        FormContext context = context(Map.of(WindowType.CONTEXT_RULE_ID, "r404", WindowType.CONTEXT_ORIGINAL_DATE, "2026-10-05"));
        var view = form.evaluate(new FormState(0, form.defaults(context)), context);
        assertFalse(view.fields().get("action").enabled());
        assertTrue(view.buttons().get("close").visible());
        assertNull(assertInstanceOf(FormOutcome.Close.class, form.onButton("close", new FormState(0, form.defaults(context)), context)).result());
    }

    @Test
    void quickEditPreservesMoveAndRemovesPlainEqualAmountAdjustment() {
        Plan base = plan();
        OccurrenceKey key = new OccurrenceKey(new RuleId("r1"), LocalDate.of(2026, 10, 5));
        Plan moved = base.withAdjustmentPut(new Adjustment(key, new Adjustment.MoveDate(LocalDate.of(2026, 10, 7)), "праздник"));
        QuickEditForm form = new QuickEditForm();
        FormContext context = context(moved, Map.of(WindowType.CONTEXT_RULE_ID, "r1", WindowType.CONTEXT_ORIGINAL_DATE, "2026-10-05"));
        FormOutcome.Close close = assertInstanceOf(FormOutcome.Close.class, form.onButton("ok", new FormState(0, replace(form.defaults(context), "amount", "90000")), context));
        Adjustment updated = assertInstanceOf(Adjustment.class, assertInstanceOf(AdjustmentForm.Result.class, close.result()).adjustment());
        assertInstanceOf(Adjustment.Replace.class, updated.action());

        FormContext plain = context(base, Map.of(WindowType.CONTEXT_RULE_ID, "r1", WindowType.CONTEXT_ORIGINAL_DATE, "2026-10-05"));
        FormOutcome.Close remove = assertInstanceOf(FormOutcome.Close.class, form.onButton("ok", new FormState(0, form.defaults(plain)), plain));
        assertNull(assertInstanceOf(AdjustmentForm.Result.class, remove.result()).adjustment());
    }

    private static FormContext context(Map<String, String> window) { return context(plan(), window); }

    private static FormContext context(Plan plan, Map<String, String> window) {
        return new FormContext("w1", "main", window, FakeStates.withPlan(ClientProfile.fx("25"), plan, MEMORY));
    }

    private static Plan plan() {
        RecurringRule rule = new RecurringRule(new RuleId("r1"), "Зарплата", Kind.INCOME, Money.parse("80000"), "Работа",
                new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.NONE, true, "");
        return new Plan("План", "", "₽", LocalDate.of(2026, 9, 1), Money.ZERO, new Horizon.Months(12), Money.ZERO, null,
                List.of(rule), List.of(), List.of(), List.of());
    }

    private static Map<String, String> replace(Map<String, String> source, String... entries) {
        var result = new java.util.LinkedHashMap<>(source);
        for (int index = 0; index < entries.length; index += 2) result.put(entries[index], entries[index + 1]);
        return Map.copyOf(result);
    }
}
