package ru.cashprediction.core.service.plan;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.forecast.service.EngineForecastService;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RawBlock;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.text.Texts;
import static org.junit.jupiter.api.Assertions.*;

/** Контракты предметной границы без формы, файлов, реестра, клиентов и сетевого транспорта. */
class LocalPlanCommandsTest {
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate TODAY = START.plusDays(4);

    /** Неверные суммы всех денежных операций отклоняются, включая выключенное правило и отрицательный баланс. */
    @Test void invalidAmountsNeverChangePlanHistoryRevisionOrRedo() {
        PlanDocument document = document(plan());
        LocalPlanCommands commands = new LocalPlanCommands(document);
        apply(commands, new PlanCommand.AddRule(rule()));
        apply(commands, new PlanCommand.SetCurrency("USD"));
        apply(commands, new PlanCommand.Undo());
        PlanCommandSnapshot before = commands.snapshot();
        boolean dirty = document.isDirty();
        List<PlanCommand> invalid = List.of(
                new PlanCommand.AddRule(rule().withId(new RuleId("r2")).withAmount(Money.ZERO).withEnabled(false)),
                new PlanCommand.ReplaceRule(rule().withAmount(Money.ofMajor(-1))),
                new PlanCommand.AddOneTime(transaction("t1", Money.ZERO)),
                new PlanCommand.AddOneTime(transaction("t1", PlanValidator.MAX_AMOUNT.plus(Money.ofMinor(1)))),
                new PlanCommand.PutAdjustment(new Adjustment(key(), new Adjustment.ChangeAmount(Money.ZERO), "")),
                new PlanCommand.PutAdjustment(new Adjustment(key(), new Adjustment.Replace(Money.ofMajor(-2), TODAY), "")),
                new PlanCommand.SetGoal(new Goal("goal", Money.ZERO, null)),
                new PlanCommand.Reconcile(TODAY, PlanValidator.MAX_AMOUNT.negate().minus(Money.ofMinor(1))));
        for (PlanCommand command : invalid) {
            PlanCommandResult result = apply(commands, command);
            assertFalse(result.accepted(), command.toString());
            assertEquals(PlanCommandError.INVALID_AMOUNT, result.problems().getFirst().code());
            assertEquals(before, commands.snapshot());
            assertEquals(dirty, document.isDirty());
        }
        assertTrue(apply(commands, new PlanCommand.Redo()).changed());
        assertEquals("USD", document.plan().currency());
    }

    /** Добавление не подменяет уже существующий объект или сиротскую корректировку нового правила. */
    @Test void duplicateIdsAndMissingReplacementsAreRejected() {
        Plan initial = plan().withRuleAdded(rule()).withOneTimeAdded(transaction("t1", Money.ofMajor(10)))
                .withAdjustmentPut(new Adjustment(new OccurrenceKey(new RuleId("r9"), TODAY), new Adjustment.Skip(), ""));
        LocalPlanCommands commands = new LocalPlanCommands(document(initial));
        assertRejectedUnchanged(commands, new PlanCommand.AddRule(rule()), PlanCommandError.DUPLICATE_ID);
        assertRejectedUnchanged(commands, new PlanCommand.AddOneTime(transaction("t1", Money.ofMajor(20))), PlanCommandError.DUPLICATE_ID);
        assertRejectedUnchanged(commands, new PlanCommand.AddRule(rule().withId(new RuleId("r9"))), PlanCommandError.DUPLICATE_ID);
        assertRejectedUnchanged(commands, new PlanCommand.ReplaceRule(rule().withId(new RuleId("r7"))), PlanCommandError.NOT_FOUND);
        assertRejectedUnchanged(commands, new PlanCommand.ReplaceOneTime(transaction("t7", Money.ofMajor(20))), PlanCommandError.NOT_FOUND);
        assertRejectedUnchanged(commands, new PlanCommand.RemoveRule(new RuleId("r7")), PlanCommandError.NOT_FOUND);
        assertRejectedUnchanged(commands, new PlanCommand.RemoveOneTime(new TxId("t7")), PlanCommandError.NOT_FOUND);
    }

    /** Проверяются интервал правила, принадлежность корректировки номинальной дате и обязательные значения. */
    @Test void invalidRecurrenceAndAdjustmentAreRejectedOutsideForms() {
        LocalPlanCommands commands = new LocalPlanCommands(document(plan().withRuleAdded(rule())));
        RecurringRule invalid = new RecurringRule(new RuleId("r2"), "range", Kind.INCOME, Money.ofMajor(10), "",
                new Recurrence.EveryNDays(3), TODAY, START, WeekendPolicy.NONE, true, "");
        PlanCommandResult range = assertRejectedUnchanged(commands, new PlanCommand.AddRule(invalid), PlanCommandError.INVALID_RECURRENCE);
        assertEquals(invalid.id(), range.problems().getFirst().ruleId());
        OccurrenceKey wrongDate = new OccurrenceKey(rule().id(), TODAY.plusDays(1));
        PlanCommandResult adjustment = assertRejectedUnchanged(commands,
                new PlanCommand.PutAdjustment(new Adjustment(wrongDate, new Adjustment.Skip(), "")), PlanCommandError.INVALID_ADJUSTMENT);
        assertEquals(wrongDate, adjustment.problems().getFirst().occurrenceKey());
        assertRejectedUnchanged(commands, new PlanCommand.AddRule(null), PlanCommandError.INVALID_COMMAND);
        assertRejectedUnchanged(commands, new PlanCommand.Actualize(null, null), PlanCommandError.INVALID_COMMAND);
        // Структурно неверные интервалы повтора уже запрещены существующими типами; модель не ужесточена службой.
        assertThrows(IllegalArgumentException.class, () -> new Recurrence.Monthly(32, 1));
        assertThrows(IllegalArgumentException.class, () -> new Recurrence.EveryNDays(0));
        assertEquals(0, commands.snapshot().revision());
    }

    /** Обычные CRUD-команды создают по одному шагу истории и сохраняют позиции объектов. */
    @Test void ruleTransactionAndAdjustmentCrudOwnOneStepEach() {
        PlanDocument document = document(plan());
        LocalPlanCommands commands = new LocalPlanCommands(document);
        assertTrue(apply(commands, new PlanCommand.AddRule(rule())).changed());
        assertTrue(apply(commands, new PlanCommand.ReplaceRule(rule().withAmount(Money.ofMajor(250)))).changed());
        OneTimeTransaction transaction = transaction("t1", Money.ofMajor(30));
        apply(commands, new PlanCommand.AddOneTime(transaction));
        apply(commands, new PlanCommand.ReplaceOneTime(transaction("t1", Money.ofMajor(40))));
        Adjustment adjustment = new Adjustment(key(), new Adjustment.MoveDate(TODAY.plusDays(2)), "note");
        apply(commands, new PlanCommand.PutAdjustment(adjustment));
        apply(commands, new PlanCommand.PutAdjustment(new Adjustment(key(), new Adjustment.Replace(Money.ofMajor(300), TODAY.plusDays(2)), "note")));
        apply(commands, new PlanCommand.RemoveAdjustment(key()));
        apply(commands, new PlanCommand.RemoveOneTime(transaction.id()));
        apply(commands, new PlanCommand.RemoveRule(rule().id()));
        assertEquals(9, commands.snapshot().revision());
        for (int i = 0; i < 9; i++) assertTrue(apply(commands, new PlanCommand.Undo()).changed());
        assertEquals(plan(), document.plan());
        assertFalse(document.canUndo());
        assertFalse(document.isDirty());
        assertEquals(PlanCommandResult.Status.UNCHANGED, apply(commands, new PlanCommand.Undo()).status());
        for (int i = 0; i < 9; i++) assertTrue(apply(commands, new PlanCommand.Redo()).changed());
        assertEquals(plan(), document.plan());
        assertFalse(document.canRedo());
    }

    /** Удаление правила атомарно удаляет корректировки; отмена восстанавливает оба объекта. */
    @Test void removeRuleIncludesAdjustmentsAndUndoRestoresThem() {
        Plan initial = plan().withRuleAdded(rule()).withAdjustmentPut(new Adjustment(key(), new Adjustment.Skip(), "note"));
        PlanDocument document = document(initial);
        LocalPlanCommands commands = new LocalPlanCommands(document);
        apply(commands, new PlanCommand.RemoveRule(rule().id()));
        assertTrue(document.plan().rules().isEmpty());
        assertTrue(document.plan().adjustments().isEmpty());
        apply(commands, new PlanCommand.Undo());
        assertEquals(initial, document.plan());
        assertFalse(document.canUndo());
    }

    /** Равные операции, включая корректировку не на последней позиции, сохраняют redo и ревизию. */
    @Test void equalCommandNeverCreatesUndoOrReordersAdjustment() {
        Adjustment first = new Adjustment(key(), new Adjustment.Skip(), "first");
        Adjustment second = new Adjustment(new OccurrenceKey(rule().id(), TODAY.plusMonths(1)), new Adjustment.Skip(), "second");
        PlanDocument document = document(plan().withRuleAdded(rule()).withAdjustments(List.of(first, second)));
        LocalPlanCommands commands = new LocalPlanCommands(document);
        apply(commands, new PlanCommand.SetCurrency("USD"));
        apply(commands, new PlanCommand.Undo());
        PlanCommandSnapshot before = commands.snapshot();
        for (PlanCommand command : List.of(new PlanCommand.ReplaceRule(rule()), new PlanCommand.PutAdjustment(first),
                new PlanCommand.SetCurrency(before.plan().currency()), new PlanCommand.SetGoal(null))) {
            assertEquals(PlanCommandResult.Status.UNCHANGED, apply(commands, command).status());
            assertEquals(before, commands.snapshot());
        }
        assertTrue(document.canRedo());
    }

    /** Просроченная ревизия проверяется до вычисления и не меняет историю. */
    @Test void staleRevisionRejectsEvenEqualCommandAndPreview() {
        LocalPlanCommands commands = new LocalPlanCommands(document(plan()));
        PlanCommandRequest stale = request(commands, new PlanCommand.SetCurrency("USD"));
        apply(commands, new PlanCommand.AddRule(rule()));
        PlanCommandSnapshot before = commands.snapshot();
        PlanCommandResult result = commands.execute(stale);
        assertEquals(PlanCommandError.STALE_REVISION, result.problems().getFirst().code());
        assertEquals(Map.of("expectedRevision", "0", "actualRevision", "1"), result.problems().getFirst().arguments());
        assertFalse(commands.preview(stale).accepted());
        assertEquals(before, commands.snapshot());
        PlanCommandResult equal = commands.execute(new PlanCommandRequest(UUID.randomUUID(), 0, "test",
                new PlanCommand.ReplaceRule(rule())));
        assertEquals(PlanCommandError.STALE_REVISION, equal.problems().getFirst().code());
        assertEquals(before, commands.snapshot());
    }

    /** Повтор и коллизия requestId не выполняют вторую операцию, даже после последующих изменений и отмены. */
    @Test void identicalRetryReturnsOriginalImmutableResultAndIdCollisionRejects() {
        LocalPlanCommands commands = new LocalPlanCommands(document(plan()));
        PlanCommandRequest request = request(commands, new PlanCommand.AddRule(rule()));
        PlanCommandResult original = commands.execute(request);
        apply(commands, new PlanCommand.SetCurrency("USD"));
        apply(commands, new PlanCommand.Undo());
        PlanCommandSnapshot before = commands.snapshot();
        assertSame(original, commands.execute(request));
        assertEquals(before, commands.snapshot());
        PlanCommandResult collision = commands.execute(new PlanCommandRequest(request.requestId(), before.revision(), "test",
                new PlanCommand.SetCurrency("EUR")));
        assertEquals(PlanCommandError.REQUEST_ID_REUSED, collision.problems().getFirst().code());
        assertEquals(before, commands.snapshot());
        assertEquals(1, original.snapshot().revision());
        assertTrue(original.snapshot().redoDescription().isEmpty());
    }

    /** Дедупликация отказов не превращает повтор ошибочного запроса в удачный запрос. */
    @Test void rejectedRequestIsRememberedUntilReplacement() {
        LocalPlanCommands commands = new LocalPlanCommands(document(plan()));
        PlanCommandRequest request = request(commands, new PlanCommand.AddOneTime(transaction("t1", Money.ZERO)));
        PlanCommandResult refused = commands.execute(request);
        apply(commands, new PlanCommand.AddRule(rule()));
        assertSame(refused, commands.execute(request));
        assertEquals(1, commands.snapshot().revision());
        assertTrue(commands.snapshot().plan().oneTimes().isEmpty());
    }

    /** После вытеснения результата старая ревизия защищает от повторной записи. */
    @Test void boundedRetryWindowFallsBackToRevisionCheck() {
        LocalPlanCommands commands = new LocalPlanCommands(document(plan()));
        PlanCommandRequest old = request(commands, new PlanCommand.AddRule(rule()));
        commands.execute(old);
        for (int i = 0; i < LocalPlanCommands.RETRY_WINDOW; i++) apply(commands, new PlanCommand.SetCurrency("USD"));
        PlanCommandSnapshot before = commands.snapshot();
        assertEquals(PlanCommandError.STALE_REVISION, commands.execute(old).problems().getFirst().code());
        assertEquals(before, commands.snapshot());
    }

    /** Предпросмотр не меняет dirty, историю и ревизию и не занимает идентификатор запроса. */
    @Test void actualizePreviewAndCommitPreserveOpeningBalanceAndRecurrencePhase() {
        RecurringRule phased = new RecurringRule(new RuleId("r2"), "phase", Kind.INCOME, Money.ofMajor(10), "",
                new Recurrence.EveryNDays(3), null, null, WeekendPolicy.NONE, true, "");
        OneTimeTransaction past = new OneTimeTransaction(new TxId("t1"), TODAY.minusDays(1), "past", Kind.EXPENSE, Money.ofMajor(20), "", "");
        OneTimeTransaction current = transaction("t2", Money.ofMajor(30));
        Plan initial = plan().withRuleAdded(rule()).withRuleAdded(phased).withOneTimes(List.of(past, current));
        PlanDocument document = document(initial);
        document.setViewState(ViewState.defaults().withWhatIf(WhatIf.ofPercent(-10, 10, Money.ZERO)));
        LocalPlanCommands commands = new LocalPlanCommands(document);
        PlanCommandSnapshot before = commands.snapshot();
        PlanCommandRequest request = request(commands, new PlanCommand.Actualize(TODAY, null));
        PlanCommandResult preview = commands.preview(request);
        assertEquals(PlanCommandResult.Status.PREVIEW, preview.status());
        assertEquals(before, commands.snapshot());
        assertFalse(document.isDirty());
        assertEquals(Money.ofMajor(1000), preview.snapshot().plan().startBalance());
        PlanCommandResult committed = commands.execute(request);
        assertEquals(preview.snapshot().plan(), committed.snapshot().plan());
        assertEquals(START, document.plan().findRule(phased.id()).orElseThrow().from());
        assertEquals(List.of(current), document.plan().oneTimes());
        assertEquals(Money.ofMajor(1170), document(initial).forecast().balanceAt(TODAY));
        assertEquals(TODAY, document.plan().startDate());
        apply(commands, new PlanCommand.Undo());
        assertEquals(initial, document.plan());
        assertFalse(document.canUndo());
    }

    /** Сверка использует обычный прогноз и знаковую разницу, нулевая разница не создаёт историю. */
    @Test void reconcilePreservesPlainForecastAndDifference() {
        PlanDocument document = document(plan().withRuleAdded(rule()));
        document.setViewState(ViewState.defaults().withWhatIf(WhatIf.ofPercent(-10, 0, Money.ZERO)));
        LocalPlanCommands commands = new LocalPlanCommands(document);
        PlanCommandResult result = apply(commands, new PlanCommand.Reconcile(TODAY, Money.ofMajor(1150)));
        assertEquals(Money.ofMajor(-50), result.effect().reconciliationDifference());
        assertEquals(Kind.EXPENSE, document.plan().oneTimes().getFirst().kind());
        assertEquals(Money.ofMajor(50), document.plan().oneTimes().getFirst().amount());
        assertEquals(PlanDocument.RECONCILE_TITLE, document.undoDescription().orElseThrow());
        apply(commands, new PlanCommand.Undo());
        PlanCommandSnapshot before = commands.snapshot();
        assertEquals(PlanCommandResult.Status.UNCHANGED, apply(commands, new PlanCommand.Reconcile(TODAY, Money.ofMajor(1200))).status());
        assertEquals(before, commands.snapshot());
        assertRejectedUnchanged(commands, new PlanCommand.Reconcile(START.minusDays(1), Money.ZERO), PlanCommandError.CALCULATION_FAILED);
    }

    /** Сценарий сохраняет ежедневный прогноз, округление, перенос и заметку корректировки. */
    @Test void applyWhatIfPreservesEveryDailyBalanceAndUsesFreeRuleId() {
        Adjustment moved = new Adjustment(key(), new Adjustment.Replace(Money.ofMajor(300), TODAY.plusDays(2)), "note");
        Adjustment orphan = new Adjustment(new OccurrenceKey(new RuleId("r2"), TODAY), new Adjustment.Skip(), "orphan");
        Plan initial = plan().withRuleAdded(rule()).withOneTimeAdded(transaction("t1", Money.ofMajor(10)))
                .withAdjustments(List.of(moved, orphan));
        PlanDocument document = document(initial);
        WhatIf scenario = WhatIf.ofPercent(-10, 10, Money.ofMajor(50));
        document.setViewState(ViewState.defaults().withWhatIf(scenario));
        long[] simulated = document.forecast().dailyBalance();
        LocalPlanCommands commands = new LocalPlanCommands(document);
        assertTrue(apply(commands, new PlanCommand.ApplyWhatIf(scenario, TODAY)).changed());
        document.setViewState(document.viewState().withWhatIf(WhatIf.NONE));
        assertArrayEquals(simulated, document.forecast().dailyBalance());
        assertTrue(document.plan().findRule(new RuleId("r2")).isEmpty());
        assertTrue(document.plan().findRule(new RuleId("r3")).isPresent());
        assertEquals(new Adjustment.Replace(Money.ofMajor(270), TODAY.plusDays(2)), document.plan().findAdjustment(key()).orElseThrow().action());
        assertEquals("note", document.plan().findAdjustment(key()).orElseThrow().note());
        apply(commands, new PlanCommand.Undo());
        assertEquals(initial, document.plan());
        assertFalse(document.canUndo());
        assertTrue(document.viewState().whatIf().isNone());
    }

    /** Округление в ноль не пишет нулевые суммы, неоднозначное обнуление не меняет ничего. */
    @Test void zeroRoundingAndFailedWhatIfPreserveExistingSemantics() {
        RecurringRule tiny = rule().withAmount(Money.ofMinor(1));
        WhatIf scenario = new WhatIf(new BigDecimal("0.1"), BigDecimal.ONE, Money.ZERO);
        Plan invalidCombination = plan().withRuleAdded(tiny).withAdjustmentPut(new Adjustment(key(),
                new Adjustment.ChangeAmount(Money.ofMajor(10)), ""));
        PlanDocument failed = document(invalidCombination);
        failed.setViewState(ViewState.defaults().withWhatIf(scenario));
        LocalPlanCommands commands = new LocalPlanCommands(failed);
        assertRejectedUnchanged(commands, new PlanCommand.ApplyWhatIf(scenario, TODAY), PlanCommandError.CALCULATION_FAILED);
        assertEquals(scenario, failed.viewState().whatIf());
        LocalPlanCommands rounded = new LocalPlanCommands(document(plan().withRuleAdded(tiny)
                .withOneTimeAdded(new OneTimeTransaction(new TxId("t1"), TODAY, "tiny", Kind.INCOME, Money.ofMinor(1), "", ""))));
        assertTrue(apply(rounded, new PlanCommand.ApplyWhatIf(scenario, TODAY)).changed());
        assertFalse(rounded.snapshot().plan().rules().getFirst().enabled());
        assertEquals(Money.ofMinor(1), rounded.snapshot().plan().rules().getFirst().amount());
        assertTrue(rounded.snapshot().plan().oneTimes().isEmpty());
    }

    /** Обнулённая корректировка становится пропуском, не меняя ежедневные балансы сценария. */
    @Test void roundedAdjustmentTurnsIntoSkipWithSameBalances() {
        Plan initial = plan().withRuleAdded(rule()).withAdjustmentPut(new Adjustment(key(),
                new Adjustment.ChangeAmount(Money.ofMinor(1)), "note"));
        PlanDocument document = document(initial);
        WhatIf scenario = new WhatIf(new BigDecimal("0.1"), BigDecimal.ONE, Money.ZERO);
        document.setViewState(ViewState.defaults().withWhatIf(scenario));
        long[] simulated = document.forecast().dailyBalance();
        LocalPlanCommands commands = new LocalPlanCommands(document);
        assertTrue(apply(commands, new PlanCommand.ApplyWhatIf(scenario, TODAY)).changed());
        assertInstanceOf(Adjustment.Skip.class, document.plan().findAdjustment(key()).orElseThrow().action());
        assertEquals("note", document.plan().findAdjustment(key()).orElseThrow().note());
        document.setViewState(document.viewState().withWhatIf(WhatIf.NONE));
        assertArrayEquals(simulated, document.forecast().dailyBalance());
    }

    /** Очистка удаляет только сирот горизонта, сохраняя выключенные правила и внешние даты. */
    @Test void cleanupRetainsDisabledAndOutsideHorizonAdjustments() {
        RecurringRule disabled = rule().withId(new RuleId("r2")).withEnabled(false);
        Adjustment missing = new Adjustment(new OccurrenceKey(new RuleId("r9"), TODAY), new Adjustment.Skip(), "");
        Adjustment wrongDate = new Adjustment(new OccurrenceKey(rule().id(), TODAY.plusDays(1)), new Adjustment.Skip(), "");
        Adjustment valid = new Adjustment(key(), new Adjustment.Skip(), "");
        Adjustment inactive = new Adjustment(new OccurrenceKey(disabled.id(), TODAY.plusDays(1)), new Adjustment.Skip(), "");
        Adjustment outside = new Adjustment(new OccurrenceKey(new RuleId("r8"), TODAY.plusYears(2)), new Adjustment.Skip(), "");
        Plan initial = plan().withRules(List.of(rule(), disabled)).withAdjustments(List.of(missing, wrongDate, valid, inactive, outside));
        PlanDocument document = document(initial);
        LocalPlanCommands commands = new LocalPlanCommands(document);
        PlanCommandResult result = apply(commands, new PlanCommand.Cleanup(TODAY, WhatIf.NONE, false));
        assertEquals(2, result.effect().removedAdjustments());
        assertEquals(List.of(valid, inactive, outside), document.plan().adjustments());
        assertEquals(Texts.get("document.edit.removeUnusedAdjustments", 2), document.undoDescription().orElseThrow());
        apply(commands, new PlanCommand.Undo());
        assertEquals(initial, document.plan());
        assertFalse(document.canUndo());
    }

    /** Внедрённый расчёт используется и специальными командами; ошибки не затрагивают живой документ. */
    @Test void draftUsesSameForecastServiceAndCalculationFailureIsAtomic() {
        int[] calls = {0};
        PlanDocument document = new PlanDocument(plan().withRuleAdded(rule()), null, () -> TODAY, request -> {
            calls[0]++;
            return EngineForecastService.DEFAULT.calculate(request);
        });
        LocalPlanCommands commands = new LocalPlanCommands(document);
        apply(commands, new PlanCommand.Reconcile(TODAY, Money.ofMajor(1250)));
        assertEquals(1, calls[0]);
        PlanDocument failing = new PlanDocument(plan(), null, () -> TODAY, request -> { throw new ArithmeticException("overflow"); });
        assertRejectedUnchanged(new LocalPlanCommands(failing), new PlanCommand.Actualize(TODAY, null), PlanCommandError.CALCULATION_FAILED);
        assertFalse(failing.isDirty());
    }

    /** Терпимое восстановление принимает повреждённый план, очищает историю и область повторов, повышая ревизию. */
    @Test void tolerantReplacementIsSeparateAndInvalidDataCanBeRepairedAndUndone() {
        PlanDocument document = document(plan());
        LocalPlanCommands commands = new LocalPlanCommands(document);
        PlanCommandRequest previous = request(commands, new PlanCommand.AddRule(rule()));
        commands.execute(previous);
        RecurringRule bad = rule().withAmount(Money.ZERO);
        Plan damaged = plan().withRuleAdded(bad).withRawBlocks(List.of(new RawBlock("", List.of("unparsed"))));
        document.replace(damaged, null, true, List.of(Diagnostic.warning("load")));
        assertEquals(2, commands.snapshot().revision());
        assertEquals(damaged, commands.snapshot().plan());
        assertFalse(document.canUndo());
        assertEquals(1, document.loadDiagnostics().size());
        assertEquals(PlanCommandError.STALE_REVISION, commands.execute(previous).problems().getFirst().code());
        apply(commands, new PlanCommand.ReplaceRule(rule()));
        assertEquals(damaged.rawBlocks(), document.plan().rawBlocks());
        apply(commands, new PlanCommand.Undo());
        assertEquals(damaged, document.plan());
        assertTrue(document.isDirty());
    }

    /** Изменение вида и сохранение не инвалидируют команду, а равное открытие другого документа инвалидирует. */
    @Test void savingAndViewDoNotChangeRevisionButEqualReplacementDoes() {
        PlanDocument document = document(plan());
        LocalPlanCommands commands = new LocalPlanCommands(document);
        PlanCommandRequest request = request(commands, new PlanCommand.SetCurrency("USD"));
        document.setViewState(ViewState.defaults().withWhatIf(WhatIf.ofPercent(-10, 0, Money.ZERO)));
        document.markSaved(Path.of("CashMemory", "test.md"));
        assertEquals(0, commands.snapshot().revision());
        assertTrue(commands.execute(request).changed());
        document.replace(document.plan(), null, false, List.of());
        assertEquals(2, commands.snapshot().revision());
        assertEquals(PlanCommandError.STALE_REVISION, commands.execute(request).problems().getFirst().code());
    }

    /** Ошибка подписчика после фиксации не маскируется отказом и не приводит к повторной записи команды. */
    @Test void observerFailureAndReentrantCommandHaveExplicitResults() {
        PlanDocument document = document(plan());
        LocalPlanCommands commands = new LocalPlanCommands(document);
        List<PlanCommandResult> nested = new ArrayList<>();
        document.addListener(event -> {
            nested.add(apply(commands, new PlanCommand.SetCurrency("EUR")));
            throw new IllegalStateException("listener");
        });
        PlanCommandRequest request = request(commands, new PlanCommand.AddRule(rule()));
        PlanCommandResult result = commands.execute(request);
        assertTrue(result.changed());
        assertEquals(PlanCommandError.NOTIFICATION_FAILED, result.problems().getFirst().code());
        assertEquals(PlanCommandError.COMMAND_IN_PROGRESS, nested.getFirst().problems().getFirst().code());
        assertEquals(1, commands.snapshot().revision());
        assertSame(result, commands.execute(request));
        assertEquals(1, nested.size());
    }

    /** Параметры меняются без подмены операций и rawBlocks, а публичные результаты защищают коллекции. */
    @Test void settingsAndResultCollectionsAreImmutable() {
        Plan initial = plan().withRuleAdded(rule()).withRawBlocks(List.of(new RawBlock("", List.of("unparsed"))));
        LocalPlanCommands commands = new LocalPlanCommands(document(initial));
        Plan changed = initial.withNote("note").withCurrency("USD").withStart(START, Money.ofMajor(-10));
        PlanCommandResult result = apply(commands, new PlanCommand.UpdateSettings(PlanCommand.Settings.from(changed)));
        assertEquals(changed, result.snapshot().plan());
        assertThrows(UnsupportedOperationException.class, () -> result.snapshot().plan().rules().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.problems().add(null));
        Map<String, String> input = new HashMap<>(Map.of("field", "amount"));
        PlanCommandProblem problem = new PlanCommandProblem(PlanCommandError.INVALID_AMOUNT, null, null, null, input, "message");
        input.clear();
        assertEquals(Map.of("field", "amount"), problem.arguments());
        assertThrows(UnsupportedOperationException.class, () -> problem.arguments().clear());
        assertRejectedUnchanged(commands, new PlanCommand.RenamePlan(""), PlanCommandError.INVALID_PLAN);
    }

    /** Создаёт небольшой детерминированный план. */
    private static Plan plan() { return Plan.empty("test", START).withStart(START, Money.ofMajor(1000)); }

    /** Правило дохода на пятый день каждого месяца. */
    private static RecurringRule rule() {
        return new RecurringRule(new RuleId("r1"), "income", Kind.INCOME, Money.ofMajor(200), "",
                new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.NONE, true, "");
    }

    /** Номинальный ключ первого события правила. */
    private static OccurrenceKey key() { return new OccurrenceKey(rule().id(), TODAY); }

    /** Создаёт разовую операцию с указанной суммой. */
    private static OneTimeTransaction transaction(String id, Money amount) {
        return new OneTimeTransaction(new TxId(id), TODAY, "expense", Kind.EXPENSE, amount, "", "");
    }

    /** Использует совместимый прежний конструктор документа. */
    private static PlanDocument document(Plan plan) { return new PlanDocument(plan, null, () -> TODAY); }

    /** Запрос на текущую предметную ревизию. */
    private static PlanCommandRequest request(PlanCommands commands, PlanCommand command) {
        return new PlanCommandRequest(UUID.randomUUID(), commands.snapshot().revision(), "test", command);
    }

    /** Выполняет новую команду без посредничества формы. */
    private static PlanCommandResult apply(PlanCommands commands, PlanCommand command) { return commands.execute(request(commands, command)); }

    /** Проверяет атомарность отказа, включая доступные вершины обеих историй. */
    private static PlanCommandResult assertRejectedUnchanged(PlanCommands commands, PlanCommand command, PlanCommandError error) {
        PlanCommandSnapshot before = commands.snapshot();
        PlanCommandResult result = apply(commands, command);
        assertFalse(result.accepted());
        assertEquals(error, result.problems().getFirst().code());
        assertEquals(before, commands.snapshot());
        assertEquals(before, result.snapshot());
        return result;
    }
}
