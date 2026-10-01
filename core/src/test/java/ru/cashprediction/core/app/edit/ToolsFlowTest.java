package ru.cashprediction.core.app.edit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.model.*;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cashprediction.core.app.edit.EditHarness.*;

/** Проверяет перенос сценария в план, задержку ввода, цель, валюту и диагностику без запуска клиентов. */
class ToolsFlowTest {
    @Test void togglesAndDebouncedExtraDoNotEditPlan() {
        EditHarness h = new EditHarness(plan());
        h.tools.toggleWhatIfIncome();
        h.tools.toggleWhatIfExpense();
        assertEquals(new BigDecimal("0.90"), h.document.viewState().whatIf().incomeFactor());
        assertEquals(new BigDecimal("1.10"), h.document.viewState().whatIf().expenseFactor());
        h.tools.toggleWhatIfIncome();
        h.tools.toggleWhatIfExpense();
        assertTrue(h.document.viewState().whatIf().isNone());
        h.tools.setWhatIfExtra(100);
        h.advance(599);
        assertEquals(Money.ZERO, h.document.viewState().whatIf().extraMonthlySaving());
        h.tools.setWhatIfExtra(200);
        h.advance(599);
        assertEquals(Money.ZERO, h.document.viewState().whatIf().extraMonthlySaving());
        h.advance(1);
        assertEquals(Money.ofMajor(200), h.document.viewState().whatIf().extraMonthlySaving());
        assertFalse(h.document.canUndo());
        assertFalse(h.document.isDirty());
        h.tools.setWhatIfExtra(300);
        h.tools.resetWhatIf();
        h.advance(1000);
        assertTrue(h.document.viewState().whatIf().isNone());
        assertEquals("«Что-если» сброшено", h.statusText);
        assertThrows(IllegalArgumentException.class, () -> h.tools.setWhatIfExtra(-1));
        assertThrows(IllegalArgumentException.class, () -> h.tools.setWhatIfExtra(10_000_001));
    }

    @Test void applyScalesAdjustmentsAndExtraWithIdenticalDailyBalances() {
        OccurrenceKey key = new OccurrenceKey(rule().id(), TODAY);
        RecurringRule expense = new RecurringRule(new RuleId("r2"), "Расход", Kind.EXPENSE,
                Money.ofMajor(20), "", new Recurrence.Monthly(10, 1), null, null, WeekendPolicy.NONE, true, "");
        Plan original = plan().withRuleAdded(rule()).withRuleAdded(expense)
                .withOneTimeAdded(new OneTimeTransaction(new TxId("t1"), TODAY, "Разовая", Kind.EXPENSE, Money.ofMajor(10), "", ""))
                .withAdjustmentPut(new Adjustment(key, new Adjustment.Replace(Money.ofMajor(200), TODAY.plusDays(2)), "заметка"));
        EditHarness h = new EditHarness(original);
        h.tools.toggleWhatIfIncome();
        h.tools.toggleWhatIfExpense();
        h.tools.setWhatIfExtra(50);
        h.advance(600);
        long[] simulated = h.document.forecast().dailyBalance();
        h.tools.applyWhatIf();
        assertTrue(h.alert().restorable());
        assertEquals("applyWhatIf", h.alert().purpose());
        assertTrue(h.alert().content().startsWith("Доходы × 0,90; Расходы × 1,10; доп. экономия 50,00 ₽ в месяц."));
        assertTrue(h.alert().content().contains("Режим «что-если» выключится."));
        h.answer("cancel");
        assertEquals(original, h.document.plan());
        assertFalse(h.document.canUndo());
        h.tools.applyWhatIf();
        h.answer("apply");
        assertTrue(h.document.viewState().whatIf().isNone());
        assertArrayEquals(simulated, h.document.forecast().dailyBalance());
        assertEquals(Money.ofMajor(90), h.document.plan().findRule(rule().id()).orElseThrow().amount());
        assertEquals(new Adjustment.Replace(Money.ofMajor(180), TODAY.plusDays(2)),
                h.document.plan().findAdjustment(key).orElseThrow().action());
        assertEquals("заметка", h.document.plan().findAdjustment(key).orElseThrow().note());
        assertEquals(3, h.document.plan().rules().size());
        assertEquals("Применение «что-если» к плану", h.document.undoDescription().orElseThrow());
        assertEquals("«Что-если» применено к плану", h.statusText);
        h.edits.undo();
        assertEquals(original, h.document.plan());
        assertFalse(h.document.canUndo());
        assertTrue(h.document.viewState().whatIf().isNone());
        h.edits.redo();
        assertArrayEquals(simulated, h.document.forecast().dailyBalance());
    }

    @Test void applyEmptyPlanResetsModeWithoutCreatingHistory() {
        EditHarness h = new EditHarness(plan());
        h.tools.toggleWhatIfIncome();
        h.tools.applyWhatIf();
        assertFalse(h.alert().content().contains("Расходы ×"));
        h.answer("apply");
        assertTrue(h.document.viewState().whatIf().isNone());
        assertFalse(h.document.canUndo());
        assertEquals("status.msg.whatIfApplied", h.statusKey);
    }

    @Test void failedApplyKeepsPlanAndScenario() {
        RecurringRule tiny = rule().withAmount(Money.ofMinor(1));
        OccurrenceKey key = new OccurrenceKey(tiny.id(), TODAY);
        EditHarness h = new EditHarness(plan().withRuleAdded(tiny).withAdjustmentPut(
                new Adjustment(key, new Adjustment.ChangeAmount(Money.ofMajor(10)), "")));
        WhatIf scenario = new WhatIf(new BigDecimal("0.1"), BigDecimal.ONE, Money.ZERO);
        h.document.setViewState(h.document.viewState().withWhatIf(scenario));
        Plan original = h.document.plan();
        h.tools.applyWhatIf();
        h.answer("apply");
        assertEquals(original, h.document.plan());
        assertEquals(scenario, h.document.viewState().whatIf());
        assertFalse(h.document.canUndo());
        assertEquals("err.editFailed", h.alert().purpose());
        assertEquals("", h.statusKey);
    }

    @Test void goalHasOneWindowAndApplyLeavesItOpen() {
        EditHarness h = new EditHarness(plan().withRuleAdded(rule()));
        h.tools.goalCalculator();
        FormSession form = h.form();
        assertFalse(form.modal());
        h.tools.goalCalculator();
        assertEquals(1, h.forms.size());
        assertEquals(1, h.fronts);
        Goal goal = new Goal("Отпуск", Money.ofMajor(3000), TODAY.plusMonths(2));
        form.apply(new FormOutcome.Apply(new GoalCalculatorForm.SaveGoal(goal), java.util.Map.of()));
        assertEquals(goal, h.document.plan().goal());
        assertFalse(form.isClosed());
        assertEquals("Цель «Отпуск»", h.document.undoDescription().orElseThrow());
        assertEquals("Цель плана обновлена", h.statusText);
        form.apply(new FormOutcome.Apply(new GoalCalculatorForm.AddWhatIfExtra(Money.ofMajor(50)), java.util.Map.of("extraSaving", "")));
        form.apply(new FormOutcome.Apply(new GoalCalculatorForm.AddWhatIfExtra(Money.ofMajor(25)), java.util.Map.of("extraSaving", "")));
        assertEquals(Money.ofMajor(75), h.document.viewState().whatIf().extraMonthlySaving());
        assertEquals("Цель «Отпуск»", h.document.undoDescription().orElseThrow());
        assertFalse(form.isClosed());
    }

    @Test void goalSurvivesFailingForecastAndValidationShowsFailure() {
        EditHarness h = new EditHarness(plan().withHorizon(new Horizon.Until(TODAY.plusYears(1000))));
        assertFalse(h.state().document().forecastAvailable());
        h.tools.goalCalculator();
        h.form().fieldChanged("target", "100", true, 1);
        assertFalse(h.form().isClosed());
        assertEquals(Problem.Severity.ERROR, h.form().view().problem().severity());
        h.tools.validate();
        assertTrue(h.alert().detailsExpanded());
        assertTrue(h.alert().details().contains("Прогноз не рассчитан: "));
    }

    @Test void currencyAndCustomCurrencyOnlyChangeSymbolAndCanCancel() {
        EditHarness h = new EditHarness(plan().withRuleAdded(rule()));
        h.tools.currency();
        assertEquals(WindowType.CHOICE, h.form().windowType());
        h.form().apply(new FormOutcome.Close("$"));
        assertEquals("$", h.document.plan().currency());
        assertEquals(Money.ofMajor(100), h.document.plan().rules().getFirst().amount());
        assertEquals("Валюта: $", h.document.undoDescription().orElseThrow());
        h.tools.currency();
        h.form().apply(new FormOutcome.Close("custom"));
        assertEquals(WindowType.TEXT_INPUT, h.form().windowType());
        assertEquals("customCurrency", h.form().context().contextValue(WindowType.CONTEXT_PURPOSE));
        assertEquals(ru.cashprediction.core.session.WindowState.MAIN_OWNER, h.placement.ownerId());
        h.form().closeRequested();
        assertEquals("$", h.document.plan().currency());
        h.tools.currency();
        h.form().apply(new FormOutcome.Close("custom"));
        h.form().apply(new FormOutcome.Close("CNY"));
        assertEquals("CNY", h.document.plan().currency());
        assertEquals("Валюта: CNY", h.document.undoDescription().orElseThrow());
    }

    @Test void cleanupRemovesOnlyOrphansAndIsOneUndoStep() {
        OccurrenceKey orphanKey = new OccurrenceKey(new RuleId("r9"), TODAY);
        OccurrenceKey validKey = new OccurrenceKey(rule().id(), TODAY);
        OccurrenceKey outsideKey = new OccurrenceKey(new RuleId("r8"), TODAY.plusYears(1));
        Adjustment orphan = new Adjustment(orphanKey, new Adjustment.Skip(), "");
        Adjustment valid = new Adjustment(validKey, new Adjustment.ChangeAmount(Money.ofMajor(110)), "");
        Adjustment outside = new Adjustment(outsideKey, new Adjustment.Skip(), "");
        Plan original = plan().withRuleAdded(rule()).withAdjustments(List.of(orphan, valid, outside));
        EditHarness h = new EditHarness(original);
        h.tools.cleanup();
        assertEquals(List.of(valid, outside), h.document.plan().adjustments());
        assertEquals("Удаление неиспользуемых корректировок: 1", h.document.undoDescription().orElseThrow());
        assertEquals(ru.cashprediction.core.ui.alert.AlertKind.INFORMATION, h.alert().kind());
        assertEquals("cleanup", h.alert().purpose());
        assertEquals("Удалено: 1 корректировка", h.alert().windowTitle());
        assertEquals("Удалено: 1 корректировка", h.alert().header());
        assertEquals("Действие можно отменить: Правка → Отменить (Ctrl+Z).", h.alert().content());
        h.edits.undo();
        assertEquals(original, h.document.plan());
        h.edits.redo();
        h.tools.cleanup();
        assertEquals("Неиспользуемых корректировок нет", h.alert().header());
    }

    @Test void validationIncludesPlanFileAndForecastAndCopyUsesCardText() {
        EditHarness h = new EditHarness(plan().withCushion(Money.ofMajor(2000)));
        h.document.replace(h.document.plan(), null, false, List.of(ru.cashprediction.core.diagnostics.Diagnostic.warning("Замечание файла")));
        h.tools.validate();
        assertTrue(h.alert().details().contains("Файл: "));
        assertTrue(h.alert().details().contains("Прогноз: "));
        h.tools.copyCardValue("now");
        var card = SummaryBuilder.build(h.state()).cards().getFirst();
        assertEquals(card.title() + ": " + card.value(), h.clipboard);
        assertEquals("Значение скопировано", h.statusText);
        h.tools.copyCardValue("m12");
        assertTrue(h.clipboard.endsWith(": за горизонтом"));
        EditHarness clean = new EditHarness(plan());
        clean.tools.validate();
        assertEquals("Замечаний нет", clean.alert().header());
        assertFalse(clean.document.canUndo());
    }
}
