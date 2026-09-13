package ru.cashprediction.core.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.diagnostics.Severity;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.forecast.WhatIf;
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

/**
 * Тесты открытого документа: правка, отмена/повтор, признак «изменён», события, кэш прогноза,
 * видимые строки и команды «Актуализировать», «Сверить баланс», «Применить что-если», «Очистить корректировки».
 */
class PlanDocumentTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);

    /** Управляемые «часы» документа. */
    private final AtomicReference<LocalDate> clock = new AtomicReference<>(TODAY);
    private final List<DocumentEvent> events = new ArrayList<>();
    private PlanDocument doc;

    private static RecurringRule rule(String id, String title, Kind kind, long major, String category, Recurrence recurrence) {
        return new RecurringRule(new RuleId(id), title, kind, Money.ofMajor(major), category, recurrence, null, null,
                WeekendPolicy.NONE, true, "");
    }

    private static OneTimeTransaction oneTime(String id, String date, String title, Kind kind, long major) {
        return new OneTimeTransaction(new TxId(id), LocalDate.parse(date), title, kind, Money.ofMajor(major), "", "");
    }

    private static Adjustment adjust(String ruleId, String date, Adjustment.Action action) {
        return new Adjustment(new OccurrenceKey(new RuleId(ruleId), LocalDate.parse(date)), action, "");
    }

    /** План на 12 месяцев: зарплата 5-го, аренда 1-го, кофе каждые 3 дня (фаза от начала плана), две разовые. */
    private static Plan basePlan() {
        return new Plan("Тест", "", "₽", START, Money.ofMajor(100_000), new Horizon.Months(12), Money.ZERO, null,
                List.of(rule("r1", "Зарплата", Kind.INCOME, 80_000, "Работа", new Recurrence.Monthly(5, 1)),
                        rule("r2", "Аренда", Kind.EXPENSE, 45_000, "Жильё", new Recurrence.Monthly(1, 1)),
                        rule("r3", "Кофе", Kind.EXPENSE, 300, "Еда", new Recurrence.EveryNDays(3))),
                List.of(oneTime("t1", "2026-09-10", "Подарок", Kind.EXPENSE, 5_000),
                        oneTime("t2", "2026-10-10", "Премия", Kind.INCOME, 20_000)),
                List.of(), List.of());
    }

    @BeforeEach
    void setUp() {
        doc = new PlanDocument(basePlan(), null, clock::get);
        doc.addListener(events::add);
    }

    private static Set<EventKind> kinds(DocumentEvent event) {
        return event.kinds();
    }

    // ------------------------------------------------------------------ правка и история

    @Test
    void newDocumentIsCleanWithoutHistory() {
        assertEquals(basePlan(), doc.plan());
        assertFalse(doc.isDirty());
        assertEquals(Optional.empty(), doc.file());
        assertEquals(List.of(), doc.loadDiagnostics());
        assertFalse(doc.canUndo());
        assertFalse(doc.canRedo());
        assertEquals(Optional.empty(), doc.undoDescription());
        assertEquals(Optional.empty(), doc.redoDescription());
        assertEquals(ViewState.defaults(), doc.viewState());
        assertEquals(TODAY, doc.today());
        // Отмена и повтор без истории ничего не делают.
        doc.undo();
        doc.redo();
        assertTrue(events.isEmpty());
    }

    @Test
    void editUndoRedo() {
        Plan original = doc.plan();
        doc.edit("Изменение заметки", p -> p.withNote("первая"));
        Plan first = doc.plan();
        assertEquals("первая", first.note());
        assertTrue(doc.isDirty());
        assertTrue(doc.canUndo());
        assertFalse(doc.canRedo());
        assertEquals(Optional.of("Изменение заметки"), doc.undoDescription());
        assertEquals(Set.of(EventKind.PLAN, EventKind.DIRTY), kinds(events.get(0)));

        doc.edit("Изменение валюты", p -> p.withCurrency("$"));
        assertEquals(Set.of(EventKind.PLAN), kinds(events.get(1)), "признак «изменён» уже стоял");
        assertEquals(Optional.of("Изменение валюты"), doc.undoDescription());

        doc.undo();
        assertEquals(first, doc.plan());
        assertEquals(Optional.of("Изменение валюты"), doc.redoDescription());
        assertEquals(Optional.of("Изменение заметки"), doc.undoDescription());
        assertTrue(doc.isDirty());

        doc.undo();
        assertEquals(original, doc.plan());
        assertFalse(doc.isDirty(), "вернулись ровно к сохранённому состоянию");
        assertFalse(doc.canUndo());
        assertEquals(Set.of(EventKind.PLAN, EventKind.DIRTY), kinds(events.get(3)));

        doc.redo();
        assertEquals(first, doc.plan());
        assertTrue(doc.isDirty());
        doc.redo();
        assertEquals("$", doc.plan().currency());
        assertFalse(doc.canRedo());

        doc.undo();
        doc.edit("Новая ветка", p -> p.withName("Другое имя"));
        assertFalse(doc.canRedo(), "новая правка очищает стек повтора");
        assertEquals("Другое имя", doc.plan().name());
        assertEquals("первая", doc.plan().note());
    }

    @Test
    void noOpEditDoesNothing() {
        doc.edit("Ничего", p -> p);
        doc.edit("Та же заметка", p -> p.withNote(""));
        assertFalse(doc.isDirty());
        assertFalse(doc.canUndo());
        assertTrue(events.isEmpty());
    }

    @Test
    void failedEditLeavesDocumentUnchanged() {
        assertThrows(IllegalStateException.class, () -> doc.edit("Ошибка", p -> {
            throw new IllegalStateException("сбой");
        }));
        assertThrows(NullPointerException.class, () -> doc.edit("null", p -> null));
        assertEquals(basePlan(), doc.plan());
        assertFalse(doc.canUndo());
        assertTrue(events.isEmpty());
    }

    @Test
    void undoHistoryIsLimitedToHundredSteps() {
        for (int i = 0; i < 105; i++) {
            String note = "n" + i;
            doc.edit("Шаг " + i, p -> p.withNote(note));
        }
        int undone = 0;
        while (doc.canUndo()) {
            doc.undo();
            undone++;
        }
        assertEquals(PlanDocument.UNDO_LIMIT, undone);
        assertEquals("n4", doc.plan().note(), "самые старые шаги отброшены");
        assertTrue(doc.isDirty());
    }

    @Test
    void replaceClearsHistoryAndFiresAllKinds() {
        doc.edit("Правка", p -> p.withNote("x"));
        events.clear();
        Path file = Path.of("CashMemory", "Восстановленный.md");
        Plan restored = basePlan().withName("Восстановленный");
        List<Diagnostic> diagnostics = List.of(Diagnostic.warning(3, "Неизвестный ключ"));
        doc.replace(restored, file, true, diagnostics);

        assertEquals(restored, doc.plan());
        assertEquals(Optional.of(file), doc.file());
        assertTrue(doc.isDirty());
        assertEquals(diagnostics, doc.loadDiagnostics());
        assertFalse(doc.canUndo());
        assertFalse(doc.canRedo());
        assertEquals(1, events.size());
        assertEquals(Set.of(EventKind.PLAN, EventKind.FILE, EventKind.DIRTY), kinds(events.get(0)));

        // У восстановленного «грязного» плана нет сохранённой версии: отмена правки не делает его чистым.
        doc.edit("Правка", p -> p.withNote("y"));
        doc.undo();
        assertTrue(doc.isDirty());

        events.clear();
        Path saved = Path.of("CashMemory", "Сохранённый.md");
        doc.markSaved(saved);
        assertFalse(doc.isDirty());
        assertEquals(Optional.of(saved), doc.file());
        assertEquals(List.of(DocumentEvent.of(EventKind.FILE, EventKind.DIRTY)), events);
        assertTrue(doc.canRedo(), "сохранение не очищает историю");

        doc.edit("После сохранения", p -> p.withNote("z"));
        assertTrue(doc.isDirty());
        doc.undo();
        assertFalse(doc.isDirty(), "отмена вернула сохранённое состояние");

        doc.replace(basePlan(), null, false, null);
        assertFalse(doc.isDirty());
        assertEquals(Optional.empty(), doc.file());
        assertEquals(List.of(), doc.loadDiagnostics());
    }

    @Test
    void listenersCanBeRemovedAndFailuresDoNotStopOthers() {
        List<DocumentEvent> second = new ArrayList<>();
        Consumer<DocumentEvent> failing = e -> {
            throw new IllegalStateException("слушатель упал");
        };
        doc.addListener(failing);
        doc.addListener(second::add);
        assertThrows(IllegalStateException.class, () -> doc.edit("Правка", p -> p.withNote("a")));
        assertEquals("a", doc.plan().note(), "изменение применено до рассылки");
        assertEquals(1, events.size());
        assertEquals(1, second.size());

        doc.removeListener(failing);
        doc.removeListener(events::add); // другой экземпляр ссылки на метод — не подписан, игнорируется
        doc.edit("Правка", p -> p.withNote("b"));
        assertEquals(2, second.size());
        assertThrows(IllegalArgumentException.class, () -> new DocumentEvent(Set.of()));
        assertTrue(DocumentEvent.of(EventKind.VIEW).has(EventKind.VIEW));
        assertFalse(DocumentEvent.of(EventKind.VIEW).has(EventKind.PLAN));
    }

    // ------------------------------------------------------------------ вид и прогноз

    @Test
    void forecastIsCachedAndInvalidatedOnlyWhenNeeded() {
        Forecast first = doc.forecast();
        assertSame(first, doc.forecast());
        assertEquals(TODAY, first.today());

        doc.setViewState(doc.viewState().withFilterText("аренда").withPeriod(PeriodChoice.M3).withMode(ViewMode.CHART));
        assertSame(first, doc.forecast(), "фильтры, период и режим не пересчитывают прогноз");
        assertEquals(Set.of(EventKind.VIEW), kinds(events.get(events.size() - 1)));

        int before = events.size();
        doc.setViewState(doc.viewState());
        assertEquals(before, events.size(), "тот же вид не рассылает событие");

        doc.setViewState(doc.viewState().withShowSkipped(true));
        Forecast skipped = doc.forecast();
        assertNotSame(first, skipped);

        WhatIf whatIf = WhatIf.ofPercent(10, 0, Money.ZERO);
        doc.setViewState(doc.viewState().withWhatIf(whatIf));
        Forecast scaled = doc.forecast();
        assertNotSame(skipped, scaled);
        assertEquals(whatIf, scaled.whatIf());

        doc.edit("Правка", p -> p.withCushion(Money.ofMajor(1)));
        Forecast edited = doc.forecast();
        assertNotSame(scaled, edited);
        assertEquals(Money.ofMajor(1), edited.plan().cushion());

        doc.undo();
        assertNotSame(edited, doc.forecast());
        assertEquals(Money.ZERO, doc.forecast().plan().cushion());

        Forecast beforeMidnight = doc.forecast();
        clock.set(TODAY.plusDays(1));
        Forecast afterMidnight = doc.forecast();
        assertNotSame(beforeMidnight, afterMidnight);
        assertEquals(TODAY.plusDays(1), afterMidnight.today());
    }

    @Test
    void visibleRowsRespectPeriodAndFilters() {
        Forecast f = doc.forecast();
        assertEquals(f.rows(), doc.visibleRows(), "12 месяцев от сегодня покрывают весь горизонт");

        doc.setViewState(doc.viewState().withPeriod(PeriodChoice.M3));
        LocalDate end = LocalDate.of(2026, 12, 12);
        List<ForecastRow> m3 = doc.visibleRows();
        assertEquals(f.rows().stream().filter(r -> !r.date().isAfter(end)).toList(), m3);
        assertEquals(Origin.START, m3.get(0).origin());
        assertTrue(m3.stream().anyMatch(r -> r.date().isBefore(TODAY)), "прошедшие строки периода видны");

        doc.setViewState(doc.viewState().withShowIncome(false));
        assertTrue(doc.visibleRows().stream().noneMatch(ForecastRow::isIncome));
        doc.setViewState(doc.viewState().withShowIncome(true).withShowOneTime(false));
        assertTrue(doc.visibleRows().stream().noneMatch(r -> r.origin() == Origin.ONE_TIME));

        doc.setViewState(doc.viewState().withShowOneTime(true).withFilterText("АРЕНДА"));
        List<ForecastRow> rent = doc.visibleRows();
        assertEquals(5, rent.size(), "начальный баланс и аренда 1-го сентября, октября, ноября, декабря");
        assertTrue(rent.stream().skip(1).allMatch(r -> r.title().equals("Аренда")));
        doc.setViewState(doc.viewState().withFilterText("жилье"));
        assertEquals(rent, doc.visibleRows());

        doc.setViewState(doc.viewState().withFilterText(""));
        doc.edit("Пропуск", p -> p.withAdjustmentPut(adjust("r2", "2026-10-01", new Adjustment.Skip())));
        assertTrue(doc.visibleRows().stream().noneMatch(r -> r.rowId().equals("r2@2026-10-01")));
        doc.setViewState(doc.viewState().withShowSkipped(true));
        ForecastRow skipped = doc.visibleRows().stream().filter(r -> r.rowId().equals("r2@2026-10-01")).findFirst().orElseThrow();
        assertTrue(skipped.flags().skipped());

        doc.setViewState(doc.viewState().withPeriod(PeriodChoice.ALL));
        assertEquals(doc.forecast().rows(), doc.visibleRows());
    }

    // ------------------------------------------------------------------ команды

    @Test
    void actualizeKeepsForecastFromTodayOnward() {
        Forecast before = doc.forecast();
        events.clear();
        doc.actualize(TODAY, null);

        Plan p = doc.plan();
        assertEquals(TODAY, p.startDate());
        assertEquals(before.balanceAt(TODAY.minusDays(1)), p.startBalance(), "баланс на начало дня актуализации");
        assertEquals(List.of(new TxId("t2")), p.oneTimes().stream().map(OneTimeTransaction::id).toList());
        assertEquals(START, p.findRule(new RuleId("r3")).orElseThrow().from(), "фаза «каждые 3 дня» закреплена");
        assertEquals(null, p.findRule(new RuleId("r1")).orElseThrow().from(), "ежемесячному правилу фаза не нужна");
        assertTrue(doc.isDirty());
        assertEquals(Optional.of("Актуализация на 13.09.2026"), doc.undoDescription());
        assertEquals(Set.of(EventKind.PLAN, EventKind.DIRTY), kinds(events.get(0)));

        Forecast after = doc.forecast();
        for (LocalDate d = TODAY; !d.isAfter(before.endDate()); d = d.plusDays(1)) {
            assertEquals(before.balanceAt(d), after.balanceAt(d), "баланс на " + d);
        }

        doc.undo();
        assertEquals(basePlan(), doc.plan());
        assertFalse(doc.canUndo());
    }

    @Test
    void actualizeWithActualBalance() {
        doc.actualize(LocalDate.of(2026, 10, 2), Money.parse("12 345,67"));
        assertEquals(LocalDate.of(2026, 10, 2), doc.plan().startDate());
        assertEquals(Money.parse("12345,67"), doc.plan().startBalance());
        assertEquals(1, doc.plan().oneTimes().size());

        // Тот же день и тот же баланс — план не меняется, шаг истории не добавляется.
        doc.actualize(LocalDate.of(2026, 10, 2), Money.parse("12345,67"));
        assertEquals(1, events.stream().filter(e -> e.has(EventKind.PLAN)).count());
    }

    @Test
    void reconcileAddsDifferenceAsOneTimeTransaction() {
        Money expected = doc.forecast().balanceAt(TODAY);
        Money actual = expected.plus(Money.parse("1234,56"));
        doc.reconcile(TODAY, actual);

        OneTimeTransaction tx = doc.plan().oneTimes().get(doc.plan().oneTimes().size() - 1);
        assertEquals(new TxId("t3"), tx.id());
        assertEquals(PlanDocument.RECONCILE_TITLE, tx.title());
        assertEquals(Kind.INCOME, tx.kind());
        assertEquals(Money.parse("1234,56"), tx.amount());
        assertEquals(TODAY, tx.date());
        assertEquals(actual, doc.forecast().balanceAt(TODAY));
        assertEquals(Optional.of("Сверка баланса"), doc.undoDescription());

        Money lower = doc.forecast().balanceAt(TODAY).minus(Money.ofMajor(700));
        doc.reconcile(TODAY, lower);
        OneTimeTransaction expense = doc.plan().oneTimes().get(doc.plan().oneTimes().size() - 1);
        assertEquals(Kind.EXPENSE, expense.kind());
        assertEquals(Money.ofMajor(700), expense.amount());
        assertEquals(new TxId("t4"), expense.id());
        assertEquals(lower, doc.forecast().balanceAt(TODAY));
    }

    @Test
    void reconcileWithZeroDifferenceDoesNothing() {
        doc.reconcile(TODAY, doc.forecast().balanceAt(TODAY));
        assertFalse(doc.canUndo());
        assertFalse(doc.isDirty());
        assertTrue(events.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> doc.reconcile(LocalDate.of(2030, 1, 1), Money.ZERO));
        assertThrows(IllegalArgumentException.class, () -> doc.reconcile(START.minusDays(1), Money.ZERO));
    }

    @Test
    void reconcileIgnoresWhatIf() {
        doc.setViewState(doc.viewState().withWhatIf(WhatIf.ofPercent(10, -10, Money.ofMajor(1_000))));
        Money plain = ForecastEngine.forecast(doc.plan(), WhatIf.NONE, TODAY, false).balanceAt(TODAY);
        doc.reconcile(TODAY, plain.plus(Money.ofMajor(100)));
        OneTimeTransaction tx = doc.plan().oneTimes().get(doc.plan().oneTimes().size() - 1);
        assertEquals(Money.ofMajor(100), tx.amount());
        assertEquals(Kind.INCOME, tx.kind());
    }

    @Test
    void applyWhatIfToPlanReproducesWhatIfForecast() {
        doc.replace(basePlan().withAdjustments(List.of(
                adjust("r1", "2026-12-05", new Adjustment.ChangeAmount(Money.ofMajor(95_000))),
                adjust("r2", "2027-01-01", new Adjustment.Replace(Money.ofMajor(50_000), LocalDate.of(2027, 1, 9))),
                adjust("r1", "2026-10-05", new Adjustment.Skip()))), null, false, List.of());
        Plan original = doc.plan();
        WhatIf whatIf = WhatIf.ofPercent(10, -10, Money.ofMajor(5_000));
        doc.setViewState(doc.viewState().withWhatIf(whatIf));
        long[] expected = doc.forecast().dailyBalance();
        events.clear();

        doc.applyWhatIfToPlan();

        assertEquals(WhatIf.NONE, doc.viewState().whatIf());
        assertEquals(List.of(Set.of(EventKind.PLAN, EventKind.DIRTY), Set.of(EventKind.VIEW)),
                events.stream().map(DocumentEvent::kinds).toList());
        Plan p = doc.plan();
        assertEquals(Money.ofMajor(88_000), p.findRule(new RuleId("r1")).orElseThrow().amount());
        assertEquals(Money.ofMajor(40_500), p.findRule(new RuleId("r2")).orElseThrow().amount());
        assertEquals(Money.ofMajor(270), p.findRule(new RuleId("r3")).orElseThrow().amount());
        assertEquals(Money.ofMajor(4_500), p.findOneTime(new TxId("t1")).orElseThrow().amount());
        assertEquals(Money.ofMajor(22_000), p.findOneTime(new TxId("t2")).orElseThrow().amount());
        assertEquals(new Adjustment.ChangeAmount(Money.ofMajor(104_500)), p.adjustments().get(0).action());
        assertEquals(new Adjustment.Replace(Money.ofMajor(45_000), LocalDate.of(2027, 1, 9)), p.adjustments().get(1).action());
        assertEquals(new Adjustment.Skip(), p.adjustments().get(2).action());

        RecurringRule saving = p.findRule(new RuleId("r4")).orElseThrow();
        assertEquals(PlanDocument.EXTRA_SAVING_TITLE, saving.title());
        assertEquals(Kind.INCOME, saving.kind());
        assertEquals(Money.ofMajor(5_000), saving.amount());
        assertEquals(new Recurrence.Monthly(31, 1), saving.recurrence());
        assertEquals(TODAY, saving.from());

        assertArrayEquals(expected, doc.forecast().dailyBalance(), "прогноз плана совпадает с прогнозом «что-если» по дням");
        assertEquals(Optional.of("Применение «что-если» к плану"), doc.undoDescription());
        doc.undo();
        assertEquals(original, doc.plan());
    }

    @Test
    void applyWhatIfWithoutWhatIfDoesNothing() {
        doc.applyWhatIfToPlan();
        assertFalse(doc.canUndo());
        assertTrue(events.isEmpty());
    }

    @Test
    void removeOrphanAdjustments() {
        RecurringRule disabled = rule("r4", "Спортзал", Kind.EXPENSE, 2_000, "", new Recurrence.Monthly(7, 1)).withEnabled(false);
        Adjustment valid = adjust("r1", "2026-10-05", new Adjustment.Skip());
        Adjustment missingRule = adjust("r9", "2026-10-05", new Adjustment.Skip());
        Adjustment wrongDate = adjust("r1", "2026-10-06", new Adjustment.ChangeAmount(Money.ofMajor(1_000)));
        Adjustment outsideHorizon = adjust("r9", "2025-01-01", new Adjustment.Skip());
        Adjustment disabledRule = adjust("r4", "2026-10-08", new Adjustment.Skip());
        Adjustment wrongRentDate = adjust("r2", "2026-11-02", new Adjustment.MoveDate(LocalDate.of(2026, 11, 3)));
        Adjustment duplicateMissing = adjust("r9", "2026-10-05", new Adjustment.ChangeAmount(Money.ofMajor(500)));
        doc.replace(basePlan().withRuleAdded(disabled).withAdjustments(List.of(valid, missingRule, wrongDate, outsideHorizon,
                disabledRule, wrongRentDate, duplicateMissing)), null, false, List.of());

        assertEquals(4, doc.removeOrphanAdjustments());
        assertEquals(List.of(valid, outsideHorizon, disabledRule), doc.plan().adjustments());
        assertEquals(Optional.of("Удаление неиспользуемых корректировок: 4"), doc.undoDescription());
        assertTrue(doc.isDirty());

        assertEquals(0, doc.removeOrphanAdjustments());
        doc.undo();
        assertFalse(doc.canUndo(), "повторный вызов не добавил шаг истории");
        assertEquals(7, doc.plan().adjustments().size());
    }

    // ------------------------------------------------------------------ регрессии команд

    /** План на 12 месяцев с началом {@link #START}, балансом 1 000 и единственным правилом дохода. */
    private static Plan salaryPlan(RecurringRule salary, List<Adjustment> adjustments) {
        return new Plan("Тест", "", "₽", START, Money.ofMajor(1_000), new Horizon.Months(12), Money.ZERO, null,
                List.of(salary), List.of(), adjustments, List.of());
    }

    /** Проверяет, что после актуализации баланс каждого дня от {@code from} до конца прежнего горизонта не изменился. */
    private static void assertSameBalancesFrom(Forecast before, Forecast after, LocalDate from) {
        for (LocalDate d = from; !d.isAfter(before.endDate()); d = d.plusDays(1)) {
            assertEquals(before.balanceAt(d), after.balanceAt(d), "баланс на " + d);
        }
    }

    /** Регрессия: зарплата от субботы 12.09, выплачиваемая «позже» в понедельник-сегодня, не пропадает. */
    @Test
    void actualizeKeepsRuleEventShiftedFromWeekendOntoToday() {
        LocalDate monday = LocalDate.of(2026, 9, 14);
        doc.replace(salaryPlan(new RecurringRule(new RuleId("r1"), "Зарплата", Kind.INCOME, Money.ofMajor(80_000), "",
                new Recurrence.Monthly(12, 1), null, null, WeekendPolicy.NEXT_BUSINESS_DAY, true, ""), List.of()), null, false, List.of());
        Forecast before = doc.forecast();
        assertEquals(Money.ofMajor(81_000), before.balanceAt(monday));

        doc.actualize(monday, null);

        assertEquals(Money.ofMajor(1_000), doc.plan().startBalance());
        assertSameBalancesFrom(before, doc.forecast(), monday);
    }

    /** Регрессия: событие от 05.09, перенесённое корректировкой на 20.09, после актуализации на 13.09 не пропадает. */
    @Test
    void actualizeKeepsRuleEventMovedFromPastToFuture() {
        doc.replace(salaryPlan(rule("r1", "Зарплата", Kind.INCOME, 80_000, "", new Recurrence.Monthly(5, 1)),
                List.of(adjust("r1", "2026-09-05", new Adjustment.MoveDate(LocalDate.of(2026, 9, 20))))), null, false, List.of());
        Forecast before = doc.forecast();
        assertEquals(Money.ofMajor(81_000), before.balanceAt(LocalDate.of(2026, 9, 25)));

        doc.actualize(TODAY, null);

        Forecast after = doc.forecast();
        assertSameBalancesFrom(before, after, TODAY);
        assertTrue(after.warnings().isEmpty(), after.warnings().toString());
    }

    /** Зеркальный случай: событие от субботы-сегодня, выплаченное «раньше» в пятницу, не учитывается дважды. */
    @Test
    void actualizeDoesNotDoubleCountRuleEventShiftedBeforeToday() {
        LocalDate saturday = LocalDate.of(2026, 9, 12);
        doc.replace(salaryPlan(new RecurringRule(new RuleId("r1"), "Зарплата", Kind.INCOME, Money.ofMajor(80_000), "",
                new Recurrence.Monthly(12, 1), null, null, WeekendPolicy.PREVIOUS_BUSINESS_DAY, true, ""), List.of()), null, false, List.of());
        Forecast before = doc.forecast();

        doc.actualize(saturday, null);

        assertEquals(Money.ofMajor(81_000), doc.plan().startBalance());
        assertSameBalancesFrom(before, doc.forecast(), saturday);
    }

    /** Регрессия: правило «Доп. экономия» не получает ID, уже занятый корректировкой-«сиротой». */
    @Test
    void applyWhatIfSavingRuleAvoidsIdOfOrphanAdjustment() {
        doc.replace(basePlan().withAdjustments(List.of(adjust("r4", "2026-10-31", new Adjustment.Skip()))), null, false, List.of());
        doc.setViewState(doc.viewState().withWhatIf(WhatIf.ofPercent(0, 0, Money.ofMajor(1_000))));
        long[] expected = doc.forecast().dailyBalance();

        doc.applyWhatIfToPlan();

        assertTrue(doc.plan().findRule(new RuleId("r4")).isEmpty(), "r4 занят корректировкой");
        assertEquals(PlanDocument.EXTRA_SAVING_TITLE, doc.plan().findRule(new RuleId("r5")).orElseThrow().title());
        assertArrayEquals(expected, doc.forecast().dailyBalance());
    }

    /** Регрессия: коэффициент 0 не записывает в план суммы 0,00; прогноз совпадает, план проходит проверку. */
    @Test
    void applyWhatIfWithZeroFactorKeepsPlanValid() {
        Adjustment bonus = adjust("r1", "2026-12-05", new Adjustment.ChangeAmount(Money.ofMajor(95_000)));
        Adjustment rent = adjust("r2", "2027-01-01", new Adjustment.Replace(Money.ofMajor(1), LocalDate.of(2027, 1, 9)));
        doc.replace(basePlan().withAdjustments(List.of(bonus, rent)), null, false, List.of());
        // Доходы −100 %, расходы −99,9 % через коэффициент: 1,00 ₽ × 0,001 округляется до нуля.
        doc.setViewState(doc.viewState().withWhatIf(new WhatIf(java.math.BigDecimal.ZERO, new java.math.BigDecimal("0.001"), Money.ZERO)));
        long[] expected = doc.forecast().dailyBalance();

        doc.applyWhatIfToPlan();

        Plan p = doc.plan();
        RecurringRule salary = p.findRule(new RuleId("r1")).orElseThrow();
        assertFalse(salary.enabled(), "правило с обнулившейся суммой выключено");
        assertEquals(Money.ofMajor(80_000), salary.amount(), "прежняя сумма сохранена");
        assertEquals(bonus, p.adjustments().get(0), "корректировки выключенного правила не тронуты");
        assertEquals(new Adjustment.Skip(), p.adjustments().get(1).action(), "обнулившаяся «заменить» стала «пропустить»");
        assertEquals(Money.ofMajor(45), p.findRule(new RuleId("r2")).orElseThrow().amount());
        assertEquals(List.of(new TxId("t1")), p.oneTimes().stream().map(OneTimeTransaction::id).toList(), "доход t2 удалён");
        assertEquals(Money.ofMajor(5), p.oneTimes().get(0).amount());
        assertTrue(PlanValidator.validate(p).stream().noneMatch(d -> d.severity() == Severity.ERROR),
                PlanValidator.validate(p).toString());
        assertArrayEquals(expected, doc.forecast().dailyBalance());
    }

    /** Нельзя выключить правило, если суммы его корректировок после умножения остаются: команда отклоняется целиком. */
    @Test
    void applyWhatIfRejectsRuleRoundedToZeroWithNonZeroAdjustment() {
        RecurringRule tiny = new RecurringRule(new RuleId("r1"), "Копейка", Kind.INCOME, Money.ofMinor(1), "",
                new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.NONE, true, "");
        doc.replace(salaryPlan(tiny, List.of(adjust("r1", "2026-10-05", new Adjustment.ChangeAmount(Money.ofMajor(100))))),
                null, false, List.of());
        WhatIf whatIf = WhatIf.ofPercent(-51, 0, Money.ZERO);
        doc.setViewState(doc.viewState().withWhatIf(whatIf));
        Plan original = doc.plan();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, doc::applyWhatIfToPlan);
        assertTrue(e.getMessage().contains("округляется до нуля"), e.getMessage());
        assertEquals(original, doc.plan());
        assertFalse(doc.canUndo());
        assertEquals(whatIf, doc.viewState().whatIf(), "режим «что-если» не выключен");
    }
}
