package ru.cashprediction.core.forecast.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RawBlock;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.model.WeekendPolicy;

/** Проверяет внедрение службы в живой документ вместе с прежними правилами кэша и истории. */
class PlanDocumentForecastServiceTest {
    private static final LocalDate START = LocalDate.of(2024, 1, 1);

    /** Документ ленив и возвращает именно объект подставной службы, а не собственный расчёт. */
    @Test
    void injectedServiceOwnsCalculationAndDocumentOwnsCache() {
        Plan plan = plan();
        Forecast expected = EngineForecastService.DEFAULT.calculate(new ForecastRequest(plan, WhatIf.NONE, START, false));
        FakeForecastService fake = new FakeForecastService(request -> expected);
        PlanDocument document = new PlanDocument(plan, null, () -> START, fake);

        assertSame(fake, document.forecastService());
        assertTrue(fake.requests().isEmpty());
        assertSame(expected, document.forecast());
        assertSame(expected, document.forecast());
        assertEquals(List.of(new ForecastRequest(plan, WhatIf.NONE, START, false)), fake.requests());
        assertFalse(document.isDirty());
        assertFalse(document.canUndo());
    }

    /** Фильтры не сбрасывают кэш, а дата, пропуски и «что-если» передаются в новый явный запрос. */
    @Test
    void onlyForecastInputsInvalidateCache() {
        Plan plan = plan();
        AtomicReference<LocalDate> today = new AtomicReference<>(START);
        FakeForecastService fake = new FakeForecastService();
        PlanDocument document = new PlanDocument(plan, null, today::get, fake);
        Forecast initial = document.forecast();
        document.setViewState(document.viewState().withFilterText("filter").withShowIncome(false));
        assertSame(initial, document.forecast());
        assertEquals(1, fake.requests().size());

        document.setViewState(document.viewState().withShowSkipped(true));
        document.forecast();
        WhatIf whatIf = new WhatIf(new BigDecimal("1.005"), new BigDecimal("0.995"), Money.ofMinor(125));
        document.setViewState(document.viewState().withWhatIf(whatIf));
        document.forecast();
        today.set(START.plusDays(1));
        Forecast nextDay = document.forecast();
        document.setViewState(document.viewState());
        assertSame(nextDay, document.forecast());

        assertEquals(List.of(
                new ForecastRequest(plan, WhatIf.NONE, START, false),
                new ForecastRequest(plan, WhatIf.NONE, START, true),
                new ForecastRequest(plan, whatIf, START, true),
                new ForecastRequest(plan, whatIf, START.plusDays(1), true)), fake.requests());
        assertFalse(document.isDirty());
        assertFalse(document.canUndo());
    }

    /** Правка, сохранение, отмена, повтор и открытие сохраняют прежнее владение историей и кэшем. */
    @Test
    void editSaveUndoRedoAndReplaceKeepTheirOriginalSemantics() {
        Plan original = plan();
        FakeForecastService fake = new FakeForecastService();
        PlanDocument document = new PlanDocument(original, null, () -> START, fake);
        Forecast initial = document.forecast();
        document.edit("same", current -> current);
        assertThrows(IllegalStateException.class, () -> document.edit("failed", current -> {
            throw new IllegalStateException("test failure");
        }));
        assertSame(initial, document.forecast());
        assertEquals(1, fake.requests().size());
        assertFalse(document.canUndo());

        document.edit("note", current -> current.withNote("changed"));
        Plan changed = document.plan();
        Forecast edited = document.forecast();
        assertTrue(document.isDirty());
        assertEquals("note", document.undoDescription().orElseThrow());
        document.markSaved(Path.of("CashMemory", "test.md"));
        assertFalse(document.isDirty());
        assertTrue(document.canUndo());
        assertSame(edited, document.forecast());

        document.undo();
        assertSame(original, document.plan());
        assertTrue(document.isDirty());
        assertTrue(document.canRedo());
        document.forecast();
        document.redo();
        assertSame(changed, document.plan());
        assertFalse(document.isDirty());
        document.forecast();

        Plan replacement = original.withName("replacement");
        document.replace(replacement, null, true, List.of());
        assertTrue(document.isDirty());
        assertFalse(document.canUndo());
        assertFalse(document.canRedo());
        assertSame(fake, document.forecastService());
        assertEquals(4, fake.requests().size());
        document.forecast();
        assertEquals(List.of(original, changed, original, changed, replacement),
                fake.requests().stream().map(ForecastRequest::plan).toList());
    }

    /** Полная глубина отмены не переносится в службу; новая правка после отмены по-прежнему убирает повтор. */
    @Test
    void historyLimitAndBranchingRemainInDocument() {
        FakeForecastService fake = new FakeForecastService();
        PlanDocument document = new PlanDocument(plan(), null, () -> START, fake);
        document.forecast();
        for (int i = 0; i < PlanDocument.UNDO_LIMIT + 5; i++) {
            String note = "note-" + i;
            document.edit(note, current -> current.withNote(note));
        }
        assertEquals(1, fake.requests().size());
        document.forecast();
        for (int i = 0; i < PlanDocument.UNDO_LIMIT; i++) {
            document.undo();
        }
        assertEquals("note-4", document.plan().note());
        assertFalse(document.canUndo());
        assertTrue(document.canRedo());
        document.forecast();
        document.edit("branch", current -> current.withNote("branch"));
        assertFalse(document.canRedo());
        assertEquals(3, fake.requests().size());
    }

    /** Денежная команда рассчитывает обычный план той же службой, не применяя гипотетическую экономию. */
    @Test
    void reconciliationUsesInjectedPlainForecastAndPreservesUndo() {
        FakeForecastService fake = new FakeForecastService();
        PlanDocument document = new PlanDocument(plan(), null, () -> START, fake);
        WhatIf whatIf = new WhatIf(new BigDecimal("1.1"), new BigDecimal("0.9"), Money.ofMinor(100));
        document.setViewState(document.viewState().withWhatIf(whatIf).withShowSkipped(true));
        LocalDate reconciliationDate = document.plan().endDate();
        document.reconcile(reconciliationDate, Money.ofMinor(500));

        assertEquals(List.of(
                new ForecastRequest(plan(), whatIf, START, true),
                new ForecastRequest(plan(), WhatIf.NONE, START, false)), fake.requests());
        OneTimeTransaction adjustment = document.plan().oneTimes().getFirst();
        assertEquals(Money.ofMinor(500), adjustment.amount());
        assertEquals(reconciliationDate, adjustment.date());
        assertEquals(Kind.INCOME, adjustment.kind());
        assertEquals(Money.ofMinor(650), document.forecast().endBalance());
        document.undo();
        assertEquals(plan(), document.plan());
        assertFalse(document.isDirty());
        assertEquals(whatIf, document.viewState().whatIf());
        document.redo();
        assertEquals(adjustment, document.plan().oneTimes().getFirst());
    }

    /** Актуализация использует обычный прогноз, сохраняет опорные фазы повторов и нераспознанные блоки. */
    @Test
    void actualizationKeepsRecurrencePhasesAndRawData() {
        Plan original = plan().withStart(START, Money.ofMinor(10000))
                .withRules(List.of(rule("r1", new Recurrence.Monthly(31, 2)), rule("r2", new Recurrence.EveryNDays(3))))
                .withOneTimes(List.of(transaction("t1", START.plusDays(2)), transaction("t2", START.plusDays(20))))
                .withRawBlocks(List.of(new RawBlock("", List.of("unknown: keep"))));
        LocalDate today = START.plusDays(10);
        Forecast plain = EngineForecastService.DEFAULT.calculate(new ForecastRequest(original, WhatIf.NONE, today, false));
        FakeForecastService fake = new FakeForecastService();
        PlanDocument document = new PlanDocument(original, null, () -> today, fake);
        WhatIf whatIf = new WhatIf(new BigDecimal("1.5"), BigDecimal.ONE, Money.ofMinor(99));
        document.setViewState(document.viewState().withWhatIf(whatIf));
        document.actualize(today, null);

        assertEquals(List.of(new ForecastRequest(original, whatIf, today, false),
                new ForecastRequest(original, WhatIf.NONE, today, false)), fake.requests());
        assertEquals(plain.balanceAt(today.minusDays(1)), document.plan().startBalance());
        assertEquals(today, document.plan().startDate());
        assertTrue(document.plan().rules().stream().allMatch(rule -> START.equals(rule.from())));
        assertEquals(List.of(original.oneTimes().get(1)), document.plan().oneTimes());
        assertEquals(original.rawBlocks(), document.plan().rawBlocks());
        document.undo();
        assertSame(original, document.plan());
        document.redo();
        assertEquals(today, document.plan().startDate());
    }

    /** Ошибка службы не кэшируется и не создаёт изменение документа; следующий вызов может восстановиться. */
    @Test
    void failedCalculationIsRetriedWithoutChangingHistory() {
        Plan plan = plan();
        Forecast expected = EngineForecastService.DEFAULT.calculate(new ForecastRequest(plan, WhatIf.NONE, START, false));
        ForecastFailure.LimitExceeded failure = new ForecastFailure.LimitExceeded(new IllegalStateException("test limit"));
        AtomicInteger attempts = new AtomicInteger();
        FakeForecastService fake = new FakeForecastService(request -> {
            if (attempts.getAndIncrement() == 0) {
                throw failure;
            }
            return expected;
        });
        PlanDocument document = new PlanDocument(plan, null, () -> START, fake);

        assertSame(failure, assertThrows(ForecastFailure.LimitExceeded.class, document::forecast));
        assertSame(expected, document.forecast());
        assertSame(expected, document.forecast());
        assertEquals(2, fake.requests().size());
        assertSame(plan, document.plan());
        assertFalse(document.isDirty());
        assertFalse(document.canUndo());
    }

    /** Конструктор и замена не рассчитывают и не отвергают повреждённый план; старый конструктор доступен. */
    @Test
    void damagedPlanOpeningAndCompatibilityConstructorRemainTolerant() {
        RecurringRule damagedRule = rule("r1", new Recurrence.EveryNDays(3)).withAmount(Money.ofMinor(-1));
        Plan damaged = plan().withRules(List.of(damagedRule, damagedRule));
        FakeForecastService fake = new FakeForecastService(request -> {
            throw new AssertionError("opening must not calculate");
        });
        PlanDocument document = new PlanDocument(damaged, null, () -> START, fake);
        document.replace(damaged.withNote("opened again"), null, false, List.of());
        assertEquals(2, document.plan().rules().size());
        assertTrue(fake.requests().isEmpty());
        assertFalse(document.isDirty());
        assertFalse(document.canUndo());

        PlanDocument compatible = new PlanDocument(plan(), null, () -> START);
        assertSame(EngineForecastService.DEFAULT, compatible.forecastService());
        assertEquals(EngineForecastService.DEFAULT.calculate(new ForecastRequest(plan(), WhatIf.NONE, START, false)),
                compatible.forecast());
        assertThrows(NullPointerException.class, () -> new PlanDocument(plan(), null, () -> START, null));
    }

    /** Создаёт пустой план с небольшим горизонтом, не затрагивая диск или реестр. */
    private static Plan plan() {
        return Plan.empty("test", START).withHorizon(new Horizon.Months(1));
    }

    /** Создаёт правило с неявной опорной датой для проверки актуализации. */
    private static RecurringRule rule(String id, Recurrence recurrence) {
        return new RecurringRule(new RuleId(id), id, Kind.INCOME, Money.ofMinor(101), "", recurrence,
                null, null, WeekendPolicy.NONE, true, "");
    }

    /** Создаёт разовую операцию с устойчивым идентификатором. */
    private static OneTimeTransaction transaction(String id, LocalDate date) {
        return new OneTimeTransaction(new TxId(id), date, id, Kind.EXPENSE, Money.ofMinor(1), "", "");
    }
}
