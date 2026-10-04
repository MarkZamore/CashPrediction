package ru.cashprediction.core.support;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.Flags;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;
import ru.cashprediction.core.ui.view.summary.SummaryModel;
import ru.cashprediction.core.ui.view.table.LazyTableModel;
import ru.cashprediction.core.ui.view.table.Placeholder;
import ru.cashprediction.core.ui.view.table.TableModel;
import ru.cashprediction.core.ui.view.table.ViewStates;

/**
 * Проверяет большой доменный план через репозиторий, движок и общие модели представления.
 * Оракул использует календарь и условия сценария, не генератор событий и не поиск приложения.
 * Проверки не измеряют задержку интерфейса и не являются доказательством производительности.
 */
class LargeWeeklyPlanFixtureTest {
    @TempDir
    Path temporary;

    private Plan plan;
    private Path file;

    /** Создаёт изолированный файл и читает его настоящим репозиторием перед каждым тестом. */
    @BeforeEach
    void loadRealMarkdown() throws Exception {
        Path cashMemory = temporary.resolve("CashMemory");
        file = LargeWeeklyPlanFixture.write(cashMemory);
        var read = new PlanRepository(cashMemory).load(file, LargeWeeklyPlanFixture.today());
        assertTrue(read.diagnostics().isEmpty(), read.diagnostics().toString());
        plan = read.plan();
    }

    /** Проверяет равенство домена и байтов после повторной канонической записи. */
    @Test
    void canonicalRepositoryRoundTrip() throws Exception {
        assertEquals(LargeWeeklyPlanFixture.create(), plan);
        assertEquals(new Horizon.Months(600), plan.horizon());
        assertEquals(LocalDate.of(2076, 1, 4), plan.endDate());
        assertEquals(10, plan.rules().size());
        byte[] first = Files.readAllBytes(file);
        assertEquals(PlanMarkdownWriter.write(plan), Files.readString(file));
        PlanRepository repository = new PlanRepository(file.getParent());
        repository.save(plan, file);
        assertArrayEquals(first, Files.readAllBytes(file));
        assertEquals(plan, repository.load(file, LargeWeeklyPlanFixture.today()).plan());
        assertThrows(java.io.IOException.class, () -> LargeWeeklyPlanFixture.write(file.getParent()));
    }

    /** Проверяет каждую строку и каждый день, включая обычное скрытие пропуска. */
    @Test
    void completeForecastMatchesCalendarOracle() {
        List<Event> expected = calendar();
        assertEquals(26_091, expected.size());
        Forecast shown = calculate(true);
        assertEquals(26_092, shown.rows().size());
        assertEquals(18_262, shown.dayCount());
        assertComplete(shown, expected);
        Forecast hidden = calculate(false);
        assertEquals(26_091, hidden.rows().size());
        assertComplete(hidden, expected.stream().filter(e -> !e.skipped()).toList());
        assertEquals(shown.summary(), hidden.summary());
        assertArrayEquals(shown.dailyBalance(), hidden.dailyBalance());
        ForecastRow changed = shown.findRow("r1@2026-01-19").orElseThrow();
        assertEquals(new Money(1_750_000), changed.amount());
        assertEquals("Замена DELTA", changed.note());
        assertTrue(changed.flags().amountChanged());
        ForecastRow skipped = shown.findRow("r6@2026-01-16").orElseThrow();
        assertEquals(new Money(-1_500_000), skipped.amount());
        assertTrue(skipped.flags().skipped());
        assertEquals(plan.endDate(), shown.rows().getLast().date());
        assertEquals("t1", shown.rows().getLast().rowId());
    }

    /** Проверяет независимые суммы доходов/расходов и конечный финансовый инвариант. */
    @Test
    void totalsAndFinalBalanceMatchIndependentEvents() {
        List<Event> events = calendar();
        long income = events.stream().filter(e -> !e.skipped() && e.amount() > 0)
                .mapToLong(Event::amount).sum();
        long expense = -events.stream().filter(e -> !e.skipped() && e.amount() < 0)
                .mapToLong(Event::amount).sum();
        Forecast forecast = calculate(true);
        assertEquals(new Money(income), forecast.summary().totalIncome());
        assertEquals(new Money(expense), forecast.summary().totalExpense());
        assertEquals(new Money(2_500_000 + income - expense), forecast.endBalance());
        assertEquals(forecast.endBalance(), forecast.summary().endBalance());
        assertEquals(601, forecast.summary().byMonth().size());
        assertEquals(income, forecast.summary().byMonth().values().stream()
                .mapToLong(month -> month.income().minor()).sum());
        assertEquals(expense, forecast.summary().byMonth().values().stream()
                .mapToLong(month -> month.expense().minor()).sum());
    }

    /** Проверяет поиск в трёх полях; ожидаемые наборы заданы семантикой данных, без normalize/accepts. */
    @Test
    void searchHasIndependentPositiveNegativeCaseAndYoExpectations() {
        Predicate<Event> income = e -> e.kind() == Kind.INCOME;
        Predicate<Event> expense = e -> e.id().startsWith("r") && e.kind() == Kind.EXPENSE;
        List<SearchCase> cases = List.of(
                new SearchCase("", e -> true),
                new SearchCase("  WEEKLY   INCOME  0 ", e -> e.id().startsWith("r1@")),
                new SearchCase("доход елка", income),
                new SearchCase("ДОХОД ЁЛКА", income),
                new SearchCase(" премия  alpha ", e -> income.test(e) && !e.changed()),
                new SearchCase("ПОКУПКА beta", e -> expense.test(e) && !e.skipped()),
                new SearchCase("РАСХОД быт", expense),
                new SearchCase("еЖ", e -> e.id().equals("t1")),
                new SearchCase("ЁЖ", e -> e.id().equals("t1")),
                new SearchCase("гамма", e -> e.id().equals("t1")),
                new SearchCase("delta", Event::changed),
                new SearchCase("sigma", Event::skipped),
                new SearchCase("не найдено", e -> false));
        AppState baseline = state(all());
        for (SearchCase search : cases) {
            ViewState view = all().withFilterText(search.query());
            List<Event> expected = calendar().stream().filter(search.expected()).toList();
            assertView(baseline, view, expected, false);
            assertView(baseline, view.withMonthTotals(true), expected, true);
        }
    }

    /** Проверяет флаги типов, разовых и пропущенных, а также границу периода. */
    @Test
    void visibilityFiltersPreserveLedgerAndSummary() {
        AppState baseline = state(all());
        List<Event> events = calendar();
        assertView(baseline, all().withShowIncome(false),
                events.stream().filter(e -> e.kind() == Kind.EXPENSE).toList(), false);
        assertView(baseline, all().withShowExpense(false),
                events.stream().filter(e -> e.kind() == Kind.INCOME).toList(), false);
        assertView(baseline, all().withShowOneTime(false),
                events.stream().filter(e -> !e.id().equals("t1")).toList(), false);
        assertView(baseline, all().withShowSkipped(false),
                events.stream().filter(e -> !e.skipped()).toList(), false);
        assertView(baseline, all().withFilterText("sigma").withShowSkipped(false), List.of(), false);
        assertView(baseline, all().withShowIncome(false).withShowExpense(false), List.of(), false);
        LocalDate periodEnd = LocalDate.of(2026, 4, 4);
        assertView(baseline, all().withPeriod(PeriodChoice.M3),
                events.stream().filter(e -> !e.date().isAfter(periodEnd)).toList(), false);
    }

    /** @return вид всего горизонта без групповых строк, с пропусками */
    private static ViewState all() {
        return ViewState.defaults().withPeriod(PeriodChoice.ALL).withMonthTotals(false).withShowSkipped(true);
    }

    /** @return настоящее состояние с прогнозом, заново рассчитанным существующим тестовым помощником */
    private AppState state(ViewState view) {
        return ViewStates.of(plan, LargeWeeklyPlanFixture.today(), view, true, "");
    }

    /** @return прогноз без синтетических событий «что-если» */
    private Forecast calculate(boolean skipped) {
        return ForecastEngine.forecast(plan, WhatIf.NONE, LargeWeeklyPlanFixture.today(), skipped);
    }

    /** Проверяет полный порядок идентификаторов, поля, семантику пропуска и все ежедневные балансы. */
    private void assertComplete(Forecast forecast, List<Event> expected) {
        List<String> ids = new ArrayList<>();
        ids.add("start");
        expected.forEach(e -> ids.add(e.id()));
        assertEquals(ids, forecast.rows().stream().map(ForecastRow::rowId).toList());
        assertEquals(ids.size(), new HashSet<>(ids).size());
        assertEquals(plan.startDate(), forecast.anchor());
        ForecastRow start = forecast.rows().getFirst();
        assertEquals(Origin.START, start.origin());
        assertEquals(Money.ZERO, start.amount());
        assertEquals(new Money(2_500_000), start.balanceAfter());
        long balance = 2_500_000;
        for (int i = 0; i < expected.size(); i++) {
            Event e = expected.get(i);
            ForecastRow row = forecast.rows().get(i + 1);
            if (!e.skipped()) balance += e.amount();
            assertEquals(e.date(), row.date(), e.id());
            assertEquals(e.date(), row.originalDate(), e.id());
            assertEquals(e.title(), row.title(), e.id());
            assertEquals(e.category(), row.category(), e.id());
            assertEquals(e.note(), row.note(), e.id());
            assertEquals(e.kind(), row.kind(), e.id());
            assertEquals(new Money(e.amount()), row.amount(), e.id());
            assertEquals(new Money(balance), row.balanceAfter(), e.id());
            assertEquals(e.id().equals("t1") ? Origin.ONE_TIME : Origin.RULE, row.origin(), e.id());
            assertEquals(new Flags(false, e.changed(), false, e.skipped(), false, false), row.flags(), e.id());
            assertEquals(!e.skipped(), row.affectsBalance(), e.id());
            if (row.origin() == Origin.RULE) {
                assertEquals(e.id(), row.occurrenceKey().orElseThrow().asRowId());
            } else {
                assertTrue(row.occurrenceKey().isEmpty());
            }
        }
        Map<LocalDate, Long> daily = dailyLedger(expected);
        assertEquals(daily.size(), forecast.dayCount());
        assertEquals(plan.endDate(), forecast.endDate());
        int day = 0;
        for (var entry : daily.entrySet()) {
            assertEquals(entry.getValue().longValue(), forecast.balanceMinorAt(day++), entry.getKey().toString());
            assertEquals(new Money(entry.getValue()), forecast.balanceAt(entry.getKey()));
        }
    }

    /** Проверяет все видимые id и обратный поиск, полные балансы строк и неизменность общей сводки. */
    private void assertView(AppState baseline, ViewState view, List<Event> expected, boolean totals) {
        AppState filtered = state(view);
        Forecast original = baseline.document().forecast();
        Forecast forecast = filtered.document().forecast();
        assertArrayEquals(original.dailyBalance(), forecast.dailyBalance());
        assertEquals(original.summary(), forecast.summary());
        SummaryModel summary = SummaryBuilder.build(baseline);
        assertEquals(summary, SummaryBuilder.build(filtered));
        TableModel table = LazyTableModel.build(filtered, filtered.revision());
        List<String> ids = new ArrayList<>();
        if (!expected.isEmpty()) {
            ids.add("start");
            for (int i = 0; i < expected.size(); i++) {
                Event event = expected.get(i);
                ids.add(event.id());
                YearMonth month = YearMonth.from(event.date());
                if (totals && (i + 1 == expected.size()
                        || !month.equals(YearMonth.from(expected.get(i + 1).date())))) {
                    ids.add("total@" + month);
                }
            }
            assertNull(table.placeholder());
        } else {
            assertEquals(Placeholder.Kind.FILTERED, table.placeholder().kind());
        }
        assertEquals(ids.size(), table.rowCount(), view.filterText());
        Map<String, Long> fullBalances = eventBalances(calendar());
        TableModel unfilteredTotals = LazyTableModel.build(state(all().withMonthTotals(true)), 1);
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            var row = table.row(i);
            assertEquals(id, row.rowId(), view.filterText());
            assertEquals(i, table.indexOf(id));
            if (id.startsWith("total@")) {
                // Итоги показывают полный месяц, даже когда видна только часть его событий.
                assertEquals(unfilteredTotals.row(unfilteredTotals.indexOf(id)).cells(), row.cells());
            } else {
                assertEquals(new Money(fullBalances.get(id)).format(), row.cells().get(6), id);
            }
        }
        HashSet<String> visible = new HashSet<>(ids);
        for (Event event : calendar()) {
            if (!visible.contains(event.id())) assertEquals(-1, table.indexOf(event.id()), event.id());
        }
    }

    /**
     * Независимые события: проход по дням и явным десяти условиям сценария.
     * Не читает правила плана и не вызывает генератор, ForecastRow или фильтры приложения.
     */
    private static List<Event> calendar() {
        List<Event> result = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 5);
        LocalDate end = LocalDate.of(2076, 1, 4);
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.MONDAY) {
                for (int i = 0; i < 5; i++) {
                    boolean changed = i == 0 && date.equals(LocalDate.of(2026, 1, 19));
                    result.add(new Event("r" + (i + 1) + "@" + date, date, Kind.INCOME,
                            (changed ? 17_500L : 12_000L + i * 100L) * 100,
                            false, changed, "Weekly income " + i, "Доход Ёлка",
                            changed ? "Замена DELTA" : "Премия   ALPHA"));
                }
            }
            if (date.getDayOfWeek() == DayOfWeek.FRIDAY) {
                for (int i = 0; i < 5; i++) {
                    boolean skipped = i == 0 && date.equals(LocalDate.of(2026, 1, 16));
                    result.add(new Event("r" + (i + 6) + "@" + date, date, Kind.EXPENSE,
                            -(15_000L + i * 100L) * 100, skipped, false,
                            "Weekly expense " + i, "Расход Быт", skipped ? "Отмена SIGMA" : "покупка beta"));
                }
            }
            if (date.equals(end)) {
                result.add(new Event("t1", date, Kind.EXPENSE, -34_500, false, false,
                        "Equipment", "Резерв ёж", "Разовая ГАММА"));
            }
        }
        return List.copyOf(result);
    }

    /** @return независимый баланс после каждого события, включая начальный баланс */
    private static Map<String, Long> eventBalances(List<Event> events) {
        Map<String, Long> result = new LinkedHashMap<>();
        long balance = 2_500_000;
        result.put("start", balance);
        for (Event event : events) {
            if (!event.skipped()) balance += event.amount();
            result.put(event.id(), balance);
        }
        return result;
    }

    /** @return полный независимый баланс на конец каждого дня, включая дни без операций */
    private static Map<LocalDate, Long> dailyLedger(List<Event> events) {
        Map<LocalDate, Long> result = new LinkedHashMap<>();
        long balance = 2_500_000;
        int eventIndex = 0;
        LocalDate start = LocalDate.of(2026, 1, 5);
        int days = Math.toIntExact(ChronoUnit.DAYS.between(start, LocalDate.of(2076, 1, 4)) + 1);
        for (int i = 0; i < days; i++) {
            LocalDate date = start.plusDays(i);
            while (eventIndex < events.size() && events.get(eventIndex).date().equals(date)) {
                Event event = events.get(eventIndex++);
                if (!event.skipped()) balance += event.amount();
            }
            result.put(date, balance);
        }
        return result;
    }

    /** Независимое ожидаемое событие, не строка прогноза приложения. */
    private record Event(String id, LocalDate date, Kind kind, long amount, boolean skipped,
                         boolean changed, String title, String category, String note) {
    }

    /** Поисковый запрос и явное ожидаемое условие сценария без повторения алгоритма поиска. */
    private record SearchCase(String query, Predicate<Event> expected) {
    }
}
