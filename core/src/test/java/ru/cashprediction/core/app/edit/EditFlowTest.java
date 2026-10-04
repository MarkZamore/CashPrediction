package ru.cashprediction.core.app.edit;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.model.*;
import ru.cashprediction.core.service.plan.PlanCommand;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.forms.ops.AdjustmentForm;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.table.LazyTableModel;
import static org.junit.jupiter.api.Assertions.*;
import static ru.cashprediction.core.app.edit.EditHarness.*;

/** Проверяет планы, отмену, статусы и жизненный цикл настоящих форм S1. */
class EditFlowTest {
    private static final String ROW = "r1@2026-10-05";

    @Test void ruleCrudAndHistory() {
        EditHarness h = new EditHarness(plan());
        h.edits.addRule(Kind.INCOME);
        assertEquals(WindowType.RULE_EDITOR, h.form().windowType());
        assertEquals("INCOME", h.form().state().value("kind"));
        h.form().apply(new FormOutcome.Close(rule()));
        assertEquals(List.of(rule()), h.document.plan().rules());
        assertEquals("Добавление операции «Доход»", h.document.undoDescription().orElseThrow());
        assertEquals("Операция добавлена", h.statusText);
        h.edits.editRow(ROW);
        h.form().apply(new FormOutcome.Close(rule().withAmount(Money.ofMajor(250))));
        assertEquals(Money.ofMajor(250), h.document.plan().rules().getFirst().amount());
        assertEquals("Изменение операции «Доход»", h.document.undoDescription().orElseThrow());
        assertEquals("Операция изменена", h.statusText);
        Plan beforeDelete = h.document.plan();
        h.edits.deleteRow(ROW);
        h.answer("cancel");
        assertEquals(beforeDelete, h.document.plan());
        h.edits.deleteRow(ROW);
        h.answer("delete");
        assertTrue(h.document.plan().rules().isEmpty());
        assertEquals("Удаление операции «Доход»", h.document.undoDescription().orElseThrow());
        assertEquals("Операция «Доход» удалена", h.statusText);
        h.edits.undo();
        assertEquals(beforeDelete, h.document.plan());
        assertEquals("Отменено: Удаление операции «Доход»", h.statusText);
        h.edits.redo();
        assertTrue(h.document.plan().rules().isEmpty());
        assertEquals("Повторено: Удаление операции «Доход»", h.statusText);
    }

    @Test void oneTimeCrudAndCancellation() {
        EditHarness h = new EditHarness(plan());
        h.edits.addOneTime(TODAY.plusDays(1));
        assertEquals("2026-10-06", h.form().state().value("date"));
        assertEquals("EXPENSE", h.form().state().value("kind"));
        h.form().closeRequested();
        assertFalse(h.document.canUndo());
        OneTimeTransaction tx = new OneTimeTransaction(new TxId("t1"), TODAY, "Покупка", Kind.EXPENSE, Money.ofMajor(30), "", "");
        h.edits.addOneTime(TODAY);
        h.form().apply(new FormOutcome.Close(tx));
        assertEquals(List.of(tx), h.document.plan().oneTimes());
        assertEquals("Добавление разовой операции «Покупка»", h.document.undoDescription().orElseThrow());
        assertEquals("Разовая операция добавлена", h.statusText);
        h.edits.editRow("t1");
        OneTimeTransaction changed = new OneTimeTransaction(tx.id(), TODAY.plusDays(2), tx.title(), tx.kind(), Money.ofMajor(40), "", "");
        h.form().apply(new FormOutcome.Close(changed));
        assertEquals(List.of(changed), h.document.plan().oneTimes());
        assertEquals("Изменение разовой операции «Покупка»", h.document.undoDescription().orElseThrow());
        assertEquals("Разовая операция изменена", h.statusText);
        h.edits.deleteRow("t1");
        h.answer("delete");
        assertTrue(h.document.plan().oneTimes().isEmpty());
        assertEquals("Удаление разовой операции «Покупка»", h.document.undoDescription().orElseThrow());
        assertEquals("Разовая операция «Покупка» удалена", h.statusText);
    }

    /** Сброс скрытого пропуска не зависит от наличия строки в рассчитанном прогнозе. */
    @Test void resetHiddenSkipRestoresEventAndIsUndoable() {
        EditHarness h = new EditHarness(plan().withRuleAdded(rule()));
        OccurrenceKey key = new OccurrenceKey(rule().id(), TODAY);
        h.edits.skip(ROW);
        assertFalse(h.document.viewState().showSkipped());
        assertTrue(h.document.forecast().findRow(ROW).isEmpty());
        h.edits.reset(ROW);
        assertTrue(h.document.plan().findAdjustment(key).isEmpty());
        assertTrue(h.document.forecast().findRow(ROW).isPresent());
        assertEquals("status.msg.adjustReset", h.statusKey);
        h.edits.undo();
        assertInstanceOf(Adjustment.Skip.class, h.document.plan().findAdjustment(key).orElseThrow().action());
        assertTrue(h.document.forecast().findRow(ROW).isEmpty());
    }

    @Test void adjustmentSkipResetAndDisable() {
        EditHarness h = new EditHarness(plan().withRuleAdded(rule()));
        OccurrenceKey key = new OccurrenceKey(rule().id(), TODAY);
        h.edits.adjust(ROW);
        Adjustment adjustment = new Adjustment(key, new Adjustment.ChangeAmount(Money.ofMajor(250)), "заметка");
        h.form().apply(new FormOutcome.Close(new AdjustmentForm.Result(key, adjustment)));
        assertEquals(adjustment, h.document.plan().findAdjustment(key).orElseThrow());
        assertEquals("Корректировка «Доход» 05.10.2026", h.document.undoDescription().orElseThrow());
        assertEquals("Корректировка сохранена", h.statusText);
        h.edits.adjust(ROW);
        h.form().apply(new FormOutcome.Close(new AdjustmentForm.Result(key, null)));
        assertTrue(h.document.plan().adjustments().isEmpty());
        assertEquals("Сброс корректировки «Доход» 05.10.2026", h.document.undoDescription().orElseThrow());
        h.edits.skip(ROW);
        assertInstanceOf(Adjustment.Skip.class, h.document.plan().findAdjustment(key).orElseThrow().action());
        assertEquals("Пропуск события 05.10.2026", h.document.undoDescription().orElseThrow());
        assertEquals("status.msg.skippedHidden", h.statusKey);
        h.document.setViewState(h.document.viewState().withShowSkipped(true));
        h.edits.reset(ROW);
        assertTrue(h.document.plan().adjustments().isEmpty());
        assertEquals("Возврат события 05.10.2026 к правилу", h.document.undoDescription().orElseThrow());
        h.edits.skip(ROW);
        assertEquals("Событие пропущено", h.statusText);
        h.edits.skip(ROW);
        assertEquals("status.hint.alreadySkipped", h.statusKey);
        h.edits.reset(ROW);
        h.edits.disableRule(ROW);
        assertFalse(h.document.plan().rules().getFirst().enabled());
        assertEquals("Отключение операции «Доход»", h.document.undoDescription().orElseThrow());
        assertEquals("Правило «Доход» отключено. Ctrl+Z - вернуть", h.statusText);
    }

    @Test void quickEditPreservesMoveAndNoteAndReplacesPreviousPopup() {
        OccurrenceKey key = new OccurrenceKey(rule().id(), TODAY);
        EditHarness h = new EditHarness(plan().withRuleAdded(rule()).withAdjustmentPut(
                new Adjustment(key, new Adjustment.MoveDate(TODAY.plusDays(3)), "заметка")));
        h.edits.quickEdit(ROW, "income");
        FormSession first = h.form();
        assertEquals(ROW, h.placement.anchor().rowId());
        assertFalse(first.modal());
        h.edits.quickEdit(ROW, "income");
        assertTrue(first.isClosed());
        h.form().fieldChanged("amount", "250", true, 1);
        h.form().buttonPressed("ok");
        Adjustment result = h.document.plan().findAdjustment(key).orElseThrow();
        assertEquals(new Adjustment.Replace(Money.ofMajor(250), TODAY.plusDays(3)), result.action());
        assertEquals("заметка", result.note());
        assertEquals("Быстрая правка суммы 05.10.2026", h.document.undoDescription().orElseThrow());
        assertEquals("Сумма события изменена. Ctrl+Z - отменить", h.statusText);
    }

    @Test void quickEqualRuleRemovesAdjustmentAndInvalidInputStays() {
        OccurrenceKey key = new OccurrenceKey(rule().id(), TODAY);
        EditHarness h = new EditHarness(plan().withRuleAdded(rule()).withAdjustmentPut(
                new Adjustment(key, new Adjustment.ChangeAmount(Money.ofMajor(200)), "")));
        h.edits.quickEdit(ROW, "income");
        h.form().fieldChanged("amount", "0", true, 1);
        h.form().buttonPressed("ok");
        assertFalse(h.form().isClosed());
        assertFalse(h.document.canUndo());
        h.form().fieldChanged("amount", "100", true, 2);
        h.form().buttonPressed("ok");
        assertTrue(h.document.plan().adjustments().isEmpty());
        assertEquals("Быстрая правка суммы 05.10.2026", h.document.undoDescription().orElseThrow());
    }

    @Test void dispatchSettingsWhatIfAndGoToRule() {
        EditHarness h = new EditHarness(plan().withRuleAdded(rule()));
        h.edits.editRow("start");
        assertEquals(WindowType.PLAN_SETTINGS, h.form().windowType());
        h.form().apply(new FormOutcome.Close(h.document.plan().withCushion(Money.ofMajor(500))));
        assertEquals(Money.ofMajor(500), h.document.plan().cushion());
        assertEquals("Изменение параметров плана", h.document.undoDescription().orElseThrow());
        assertEquals("Параметры плана изменены", h.statusText);
        h.edits.goToRule(ROW);
        assertEquals("r1", h.form().context().contextValue(WindowType.CONTEXT_RULE_ID));
        h.document.setViewState(h.document.viewState().withWhatIf(
                ru.cashprediction.core.forecast.WhatIf.NONE.withExtraMonthlySaving(Money.ofMajor(10))));
        h.edits.editRow("whatif@2026-10-31");
        assertEquals("status.hint.whatIfRow", h.statusKey);
    }

    @Test void actualizeUsesPlainOpeningBalanceAndPreservesRecurrencePhase() {
        RecurringRule phased = new RecurringRule(new RuleId("r2"), "Каждые три дня", Kind.INCOME,
                Money.ofMajor(10), "", new Recurrence.EveryNDays(3), null, null, WeekendPolicy.NONE, true, "");
        OneTimeTransaction past = new OneTimeTransaction(new TxId("t1"), TODAY.minusDays(1), "Вчера", Kind.EXPENSE, Money.ofMajor(20), "", "");
        OneTimeTransaction current = new OneTimeTransaction(new TxId("t2"), TODAY, "Сегодня", Kind.EXPENSE, Money.ofMajor(30), "", "");
        Plan original = plan().withRuleAdded(phased).withRuleAdded(rule()).withOneTimes(List.of(past, current));
        EditHarness h = new EditHarness(original);
        var plain = h.document.forecast();
        h.tools.toggleWhatIfIncome();
        h.edits.actualize();
        assertTrue(h.alert().content().contains(plain.balanceAt(TODAY.minusDays(1)).format(original.currency())));
        h.answer("actualize");
        assertEquals(TODAY, h.document.plan().startDate());
        assertEquals(plain.balanceAt(TODAY.minusDays(1)), h.document.plan().startBalance());
        assertEquals(original.startDate(), h.document.plan().findRule(phased.id()).orElseThrow().from());
        assertEquals(List.of(current), h.document.plan().oneTimes());
        assertEquals("Актуализация на 05.10.2026", h.document.undoDescription().orElseThrow());
        assertEquals("План актуализирован на сегодня", h.statusText);
        h.edits.undo();
        assertEquals(original, h.document.plan());
    }

    @Test void reconcileUsesPlainForecastAndOneHistoryStep() {
        EditHarness h = new EditHarness(plan().withRuleAdded(rule()));
        h.tools.toggleWhatIfIncome();
        h.edits.reconcile();
        h.form().apply(new FormOutcome.Close(Money.ofMajor(1200)));
        assertEquals(Money.ofMajor(100), h.document.plan().oneTimes().getFirst().amount());
        assertEquals(Kind.INCOME, h.document.plan().oneTimes().getFirst().kind());
        assertEquals("Сверка баланса", h.document.undoDescription().orElseThrow());
        assertEquals("Баланс сверен: разница +100,00 ₽", h.statusText);
        h.edits.undo();
        assertTrue(h.document.plan().oneTimes().isEmpty());
        h.edits.reconcile();
        h.form().apply(new FormOutcome.Close(Money.ofMajor(1100)));
        assertTrue(h.document.plan().oneTimes().isEmpty());
        assertFalse(h.document.canUndo());
    }

    @Test void centralEditFailureNoopAndStaleFormPreserveHistory() {
        EditHarness h = new EditHarness(plan().withRuleAdded(rule()));
        Plan original = h.document.plan();
        long revision = h.planCommands.snapshot().revision();
        assertSame(h.planCommands, h.context.planCommands());
        assertSame(h.context.planCommands(), h.context.planCommands());
        // Нулевая сумма проходит мимо формы и отклоняется предметной службой до записи истории.
        assertFalse(h.edits.edit("Правка", "status.msg.settings", new PlanCommand.ReplaceRule(rule().withAmount(Money.ZERO))));
        assertEquals("err.editFailed", h.alert().purpose());
        assertEquals(original, h.document.plan());
        assertFalse(h.document.canUndo());
        assertEquals(revision, h.planCommands.snapshot().revision());
        assertEquals("", h.statusKey);
        assertFalse(h.edits.edit("Правка", "status.msg.settings", new PlanCommand.ReplaceRule(rule())));
        assertEquals(original, h.document.plan());
        assertFalse(h.document.canUndo());
        assertEquals(revision, h.planCommands.snapshot().revision());
        assertEquals("", h.statusKey);
        h.edits.editRow(ROW);
        h.document.edit("Удаление", p -> p.withRuleRemoved(rule().id()));
        h.form().apply(new FormOutcome.Close(rule().withAmount(Money.ofMajor(300))));
        assertFalse(h.form().isClosed());
        assertEquals(Problem.Severity.ERROR, h.form().view().problem().severity());
        assertTrue(h.document.plan().rules().isEmpty());
        assertEquals("Удаление", h.document.undoDescription().orElseThrow());
    }

    @Test void copiesModelCellsAndTotalMonthAndEmptyHistoryHints() {
        EditHarness h = new EditHarness(plan().withRuleAdded(rule()));
        h.edits.copyRow(ROW);
        var table = LazyTableModel.build(h.state(), 0);
        assertEquals(String.join("\t", table.row(table.indexOf(ROW)).cells()), h.clipboard);
        assertEquals("Строка скопирована", h.statusText);
        h.document.setViewState(h.document.viewState().withMonthTotals(true));
        h.edits.copyTotal("total@2026-10");
        assertTrue(h.clipboard.startsWith("Октябрь 2026\t"));
        assertEquals(4, h.clipboard.split("\t", -1).length);
        assertEquals("Итог скопирован", h.statusText);
        h.edits.undo();
        assertEquals("status.hint.nothingToUndo", h.statusKey);
        h.edits.redo();
        assertEquals("status.hint.nothingToRedo", h.statusKey);
    }

    @Test void unavailableCommandsAndCancelledActualizeDoNotMutate() {
        EditHarness h = new EditHarness(plan().withRuleAdded(rule()));
        h.edits.actualize();
        h.answer("cancel");
        assertFalse(h.document.canUndo());
        h.edits.quickEdit("r1@2026-10-05", "expense");
        assertEquals("quickEdit", h.alert().purpose());
        assertTrue(h.forms.isEmpty());
        h.edits.adjust("start");
        assertEquals("status.hint.noRuleEvent", h.statusKey);
        h.edits.reset("r1@2026-10-05");
        assertEquals("status.hint.noAdjustment", h.statusKey);
        h.edits.editRow("");
        assertEquals("status.hint.noRow", h.statusKey);
        h.edits.reconcile();
        h.form().closeRequested();
        assertFalse(h.document.canUndo());
        EditHarness future = new EditHarness(Plan.empty("Будущий", TODAY.plusDays(1)));
        future.edits.actualize();
        assertTrue(future.alert().content().contains("06.10.2026"));
        future.edits.reconcile();
        assertTrue(future.alert().content().contains("06.10.2026"));
        assertFalse(future.document.canUndo());
    }
}
