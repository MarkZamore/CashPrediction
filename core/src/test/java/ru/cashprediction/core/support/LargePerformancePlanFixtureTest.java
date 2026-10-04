package ru.cashprediction.core.support;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.cashprediction.core.support.LargePerformancePlanFixture.END;
import static ru.cashprediction.core.support.LargePerformancePlanFixture.START;
import static ru.cashprediction.core.support.LargePerformancePlanFixture.TODAY;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.document.AppSettings;
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
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.support.LargePerformancePlanFixture.ExpectedEvent;
import ru.cashprediction.core.support.LargePerformancePlanFixture.ExpectedLedger;
import ru.cashprediction.core.support.LargePerformancePlanFixture.ExpectedView;
import ru.cashprediction.core.support.LargePerformancePlanFixture.SearchCase;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;
import ru.cashprediction.core.ui.view.table.LazyTableModel;
import ru.cashprediction.core.ui.view.table.Placeholder;
import ru.cashprediction.core.ui.view.table.RowKind;
import ru.cashprediction.core.ui.view.table.TableModel;
import ru.cashprediction.core.ui.view.table.TableRowView;

/**
 * Проверяет реальный сохранённый/прочитанный план S5 по независимому календарю и явным поисковым наборам.
 * Движок, matcher и таблица здесь являются проверяемым результатом, никогда источником ожидаемых ответов.
 * Подготовка не рассчитывает production forecast; расчёт вызывается только внутри проверяющих тестов.
 * Эти проверки не запускают клиентов, не измеряют latency и не закрывают бюджет S5.
 */
class LargePerformancePlanFixtureTest {
    @TempDir
    static Path temporary;

    private static Path file;
    private static Plan plan;
    private static ExpectedLedger expected;

    /** Готовит независимые ответы и читает обычный Markdown; production forecast ещё не создаётся. */
    @BeforeAll
    static void prepareAndReadRealFile() throws Exception {
        var prepared = LargePerformancePlanFixture.prepare(temporary.resolve("CashMemory"));
        file = prepared.file();
        expected = prepared.expected();
        var read = new PlanRepository(file.getParent()).load(file, TODAY);
        assertTrue(read.diagnostics().isEmpty(), read.diagnostics().toString());
        plan = read.plan();
    }

    /** Проверяет производственную грамматику, все 77 правил и канонический цикл сохранения/чтения. */
    @Test
    void realMarkdownRoundTripRetainsFullPerformancePlan() throws Exception {
        assertEquals(LargePerformancePlanFixture.create(), plan);
        assertEquals(TODAY, LocalDate.now(LargePerformancePlanFixture.CLOCK));
        assertEquals(TODAY, LargePerformancePlanFixture.today());
        assertEquals(LocalDate.of(2026, 3, 13), plan.startDate());
        assertEquals(LocalDate.of(2076, 3, 12), plan.endDate());
        assertEquals(new Horizon.Months(600), plan.horizon());
        assertEquals(100_000_000L, plan.startBalance().minor());
        assertEquals(77, plan.rules().size());
        assertTrue(plan.oneTimes().isEmpty());
        assertTrue(plan.adjustments().isEmpty());
        assertTrue(plan.rawBlocks().isEmpty());
        for (int i = 0; i < 77; i++) {
            var rule = plan.rules().get(i);
            assertEquals("r" + (i + 1), rule.id().value());
            assertTrue(rule.enabled());
            assertEquals(new Recurrence.Weekly(DayOfWeek.of(i % 7 + 1), 1), rule.recurrence());
            assertEquals(i % 2 == 0 ? Kind.INCOME : Kind.EXPENSE, rule.kind());
            assertEquals((100L + i) * 100, rule.amount().minor());
            assertEquals(START, rule.from());
            assertEquals(END, rule.until());
            assertEquals(WeekendPolicy.NONE, rule.weekendPolicy());
        }
        byte[] bytes = Files.readAllBytes(file);
        String markdown = Files.readString(file);
        assertEquals(PlanMarkdownWriter.write(plan), markdown);
        assertTrue(file.isAbsolute());
        assertEquals("S5-performance-weekly-600.md", file.getFileName().toString());
        assertTrue(markdown.endsWith("\n"));
        assertFalse(markdown.startsWith(Character.toString(0xFEFF)));
        assertFalse(markdown.contains(Character.toString(0x2013)));
        assertFalse(markdown.contains(Character.toString(0x2014)));
        assertFalse(markdown.contains(Character.toString(0x2212)));
        PlanRepository repository = new PlanRepository(file.getParent());
        repository.save(plan, file);
        assertArrayEquals(bytes, Files.readAllBytes(file));
        assertEquals(plan, repository.load(file, TODAY).plan());
        assertThrows(FileAlreadyExistsException.class, () -> LargePerformancePlanFixture.write(file.getParent()));
        assertArrayEquals(bytes, Files.readAllBytes(file));
        try (var files = Files.list(file.getParent())) {
            assertEquals(List.of(file), files.toList());
        }
    }

    /** Фиксирует точные независимые числа, границы, поисковые наборы и счётчики служебных строк ALL. */
    @Test
    void calendarOraclePinsRealEventsBalancesSearchSetsAndAllLayout() {
        assertEquals(18_263, ChronoUnit.DAYS.between(START, END) + 1);
        assertEquals(18_263, expected.dailyBalances().size());
        assertEquals(200_893, expected.events().size());
        assertEquals(LargePerformancePlanFixture.EXPECTED_EVENT_COUNT, expected.events().size());
        assertTrue(expected.events().size() >= 190_000 && expected.events().size() <= 210_000);
        assertEquals(2_024, expected.events().stream().filter(event -> event.date().isBefore(TODAY)).count());
        assertEquals(LargePerformancePlanFixture.EXPECTED_PAST_EVENT_COUNT,
                expected.events().stream().filter(event -> event.date().isBefore(TODAY)).count());
        assertEquals(1_404_163_800L, expected.totalIncomeMinor());
        assertEquals(1_368_159_600L, expected.totalExpenseMinor());
        assertEquals(136_004_200L, expected.endBalanceMinor());
        assertEquals(100_000_000L + expected.totalIncomeMinor() - expected.totalExpenseMinor(),
                expected.endBalanceMinor());
        assertEquals(601, expected.months().size());
        assertEquals(YearMonth.of(2026, 3), expected.months().keySet().iterator().next());
        assertEquals(YearMonth.of(2076, 3), expected.months().keySet().stream().reduce((a, b) -> b).orElseThrow());
        assertEquals(START, expected.events().getFirst().date());
        assertEquals(END, expected.events().getLast().date());
        assertEquals("r5@2026-03-13", expected.events().getFirst().rowId());
        assertEquals(10_400L, expected.events().getFirst().amountMinor());
        assertEquals(100_010_400L, expected.events().getFirst().balanceAfterMinor());
        assertEquals("r74@2076-03-12", expected.events().getLast().rowId());
        int[] perRule = new int[77];
        expected.events().forEach(event -> perRule[event.ruleIndex()]++);
        for (int count : perRule) assertEquals(2_609, count);
        for (SearchCase search : LargePerformancePlanFixture.searchCases()) {
            ExpectedView expanded = LargePerformancePlanFixture.expectedAllView(expected, search, true, true);
            int matches = switch (search.name()) {
                case "clear", "title-large" -> 200_893;
                case "title-mixed-case-spaces" -> 2_609;
                case "no-match" -> 0;
                default -> 28_699;
            };
            assertEquals(matches, expanded.matchedEventCount(), search.name());
            assertEquals(matches, expanded.visibleEventCount(), search.name());
            assertEquals(search.name().equals("no-match") ? 0 : 601, expanded.monthTotalCount(), search.name());
        }
        SearchCase clear = search("clear");
        ExpectedView expanded = LargePerformancePlanFixture.expectedAllView(expected, clear, true, true);
        ExpectedView collapsed = LargePerformancePlanFixture.expectedAllView(expected, clear, false, true);
        assertEquals(201_496, expanded.rowIds().size());
        assertEquals(199_466, collapsed.rowIds().size());
        assertEquals(198_869, collapsed.visibleEventCount());
        assertEquals(2_024, collapsed.pastEventCount());
        assertEquals(595, collapsed.monthTotalCount());
        assertEquals(List.of("start", "past@group", "r7@2026-09-13"), collapsed.rowIds().subList(0, 3));
        assertEquals("r7@2026-09-13", collapsed.scrollToRowId());
        assertEquals(collapsed.scrollToRowId(), expanded.scrollToRowId());
        assertFalse(collapsed.rowIds().contains("total@2026-03"));
        assertTrue(collapsed.rowIds().contains("total@2026-09"));
        assertEquals(200_895, LargePerformancePlanFixture.expectedAllView(expected, clear, true, false).rowIds().size());
        assertEquals(198_871, LargePerformancePlanFixture.expectedAllView(expected, clear, false, false).rowIds().size());
        assertEquals("r1@2026-09-14",
                LargePerformancePlanFixture.expectedAllView(expected, search("category-only"), false, true).scrollToRowId());
        assertEquals("r9@2026-09-15",
                LargePerformancePlanFixture.expectedAllView(expected, search("note-only"), false, true).scrollToRowId());
        assertThrows(UnsupportedOperationException.class, () -> expected.events().clear());
        assertThrows(UnsupportedOperationException.class, () -> expected.dailyBalances().clear());
        assertThrows(UnsupportedOperationException.class, () -> expected.months().clear());
        assertThrows(UnsupportedOperationException.class, () -> clear.matchingRuleIndexes().clear());
        assertThrows(UnsupportedOperationException.class, () -> expanded.rowIds().clear());
    }

    /** Сравнивает каждое реальное событие, каждый ежедневный баланс и полную финансовую сводку. */
    @Test
    void savedPlanForecastMatchesEveryIndependentCalendarAnswer() {
        Forecast forecast = ForecastEngine.forecast(plan, WhatIf.NONE, TODAY, false);
        assertEquals(200_894, forecast.rows().size());
        assertEquals(200_893, forecast.rows().stream().filter(row -> row.origin() == Origin.RULE).count());
        assertEquals(18_263, forecast.dayCount());
        assertEquals(START, forecast.dailyStart());
        assertEquals(TODAY, forecast.anchor());
        assertEquals(END, forecast.endDate());
        assertEquals(END, forecast.rows().getLast().date());
        ForecastRow start = forecast.rows().getFirst();
        assertEquals("start", start.rowId());
        assertEquals(Origin.START, start.origin());
        assertEquals(START, start.date());
        assertEquals(0, start.amount().minor());
        assertEquals(100_000_000L, start.balanceAfter().minor());
        assertFalse(start.affectsBalance());
        for (int i = 0; i < expected.events().size(); i++) {
            ExpectedEvent event = expected.events().get(i);
            ForecastRow row = forecast.rows().get(i + 1);
            assertEquals(event.rowId(), row.rowId());
            assertEquals(event.date(), row.date(), event.rowId());
            assertEquals(event.date(), row.originalDate(), event.rowId());
            assertEquals(event.title(), row.title(), event.rowId());
            assertEquals(event.category(), row.category(), event.rowId());
            assertEquals(event.note(), row.note(), event.rowId());
            assertEquals(event.amountMinor() > 0 ? Kind.INCOME : Kind.EXPENSE, row.kind(), event.rowId());
            assertEquals(event.amountMinor(), row.amount().minor(), event.rowId());
            assertEquals(event.balanceAfterMinor(), row.balanceAfter().minor(), event.rowId());
            assertEquals(Origin.RULE, row.origin(), event.rowId());
            assertEquals(new Flags(false, false, false, false, event.date().isBefore(TODAY), false), row.flags(), event.rowId());
            assertEquals(event.rowId(), row.occurrenceKey().orElseThrow().asRowId());
            assertTrue(row.affectsBalance(), event.rowId());
        }
        int day = 0;
        long min = Long.MAX_VALUE;
        LocalDate minDate = null;
        for (var entry : expected.dailyBalances().entrySet()) {
            long balance = entry.getValue();
            assertEquals(balance, forecast.balanceMinorAt(day++), entry.getKey().toString());
            assertEquals(balance, forecast.balanceAt(entry.getKey()).minor(), entry.getKey().toString());
            if (!entry.getKey().isBefore(TODAY) && balance < min) {
                min = balance;
                minDate = entry.getKey();
            }
        }
        assertEquals(expected.totalIncomeMinor(), forecast.summary().totalIncome().minor());
        assertEquals(expected.totalExpenseMinor(), forecast.summary().totalExpense().minor());
        assertEquals(expected.endBalanceMinor(), forecast.endBalance().minor());
        assertEquals(expected.endBalanceMinor(), forecast.summary().endBalance().minor());
        assertEquals(min, forecast.summary().minBalance().minor());
        assertEquals(minDate, forecast.summary().minBalanceDate());
        assertTrue(forecast.summary().firstNegativeDate().isEmpty());
        assertTrue(forecast.summary().firstBelowCushionDate().isEmpty());
        assertTrue(forecast.summary().goalReachDate().isEmpty());
        long average = BigDecimal.valueOf(expected.endBalanceMinor() - 100_000_000L)
                .multiply(BigDecimal.valueOf(487)).divide(BigDecimal.valueOf(16L * 18_263), 0, RoundingMode.HALF_UP)
                .longValueExact();
        assertEquals(average, forecast.summary().averageMonthlyNet().minor());
        for (int months : List.of(1, 3, 6, 12, 24)) {
            assertEquals(expected.dailyBalances().get(TODAY.plusMonths(months)).longValue(),
                    forecast.summary().balanceAfterMonths().get(months).minor());
        }
        assertEquals(expected.months().keySet(), forecast.summary().byMonth().keySet());
        expected.months().forEach((month, totals) -> {
            var actual = forecast.summary().byMonth().get(month);
            assertEquals(totals.incomeMinor(), actual.income().minor(), month.toString());
            assertEquals(totals.expenseMinor(), actual.expense().minor(), month.toString());
            assertEquals(totals.netMinor(), actual.net().minor(), month.toString());
            assertEquals(totals.closingBalanceMinor(), actual.closingBalance().minor(), month.toString());
        });
    }

    /** Проверяет точный поиск по каждому событию и ALL в обоих состояниях прошедших, с итогами и без. */
    @Test
    void independentSearchSetsAndPastCollapsePreserveFullBalances() {
        Forecast forecast = ForecastEngine.forecast(plan, WhatIf.NONE, TODAY, false);
        var baselineSummary = SummaryBuilder.build(state(forecast, all("", true), false));
        long[] dailyBefore = forecast.dailyBalance();
        for (SearchCase search : LargePerformancePlanFixture.searchCases()) {
            ViewState view = all(search.query(), true);
            // accepts здесь проверяется по явному набору, а не создаёт ожидаемые ответы.
            assertTrue(view.accepts(forecast.rows().getFirst()), search.name());
            for (int i = 0; i < expected.events().size(); i++) {
                ExpectedEvent event = expected.events().get(i);
                assertEquals(search.matchingRuleIndexes().contains(event.ruleIndex()),
                        view.accepts(forecast.rows().get(i + 1)), event.rowId());
            }
            for (boolean expanded : List.of(false, true)) {
                for (boolean totals : List.of(false, true)) {
                    ExpectedView layout = LargePerformancePlanFixture.expectedAllView(expected, search, expanded, totals);
                    AppState state = state(forecast, all(search.query(), totals), expanded);
                    TableModel table = LazyTableModel.build(state, state.revision());
                    assertTable(table, layout, expanded);
                    assertEquals(baselineSummary, SummaryBuilder.build(state), search.name());
                }
            }
        }
        assertArrayEquals(dailyBefore, forecast.dailyBalance());
        assertEquals(136_004_200L, forecast.endBalance().minor());
        // Поля с маркерами действительно независимы: category-only и note-only не проходят по title.
        for (var rule : plan.rules()) {
            assertFalse(rule.title().contains("CategoryNeedle"));
            assertFalse(rule.note().contains("CategoryNeedle"));
            assertFalse(rule.title().contains("NoteNeedle"));
            assertFalse(rule.category().contains("NoteNeedle"));
        }
    }

    /** Показывает, почему обычный M12 не заменяет нагрузку ALL, хотя forecast остаётся полным. */
    @Test
    void defaultM12DoesNotExerciseTheWholeSixHundredMonthTable() {
        Forecast forecast = ForecastEngine.forecast(plan, WhatIf.NONE, TODAY, false);
        assertEquals(PeriodChoice.M12, ViewState.defaults().period());
        LocalDate cutoff = LocalDate.of(2027, 9, 12);
        long future = expected.events().stream()
                .filter(event -> !event.date().isBefore(TODAY) && !event.date().isAfter(cutoff)).count();
        assertEquals(4_015, future);
        TableModel defaultTable = LazyTableModel.build(state(forecast, ViewState.defaults(), false), 1);
        assertEquals(future + 2 + 13, defaultTable.rowCount());
        assertEquals(-1, defaultTable.indexOf("total@2076-03"));
        TableModel allTable = LazyTableModel.build(state(forecast, all("", true), false), 2);
        assertEquals(199_466, allTable.rowCount());
        assertEquals(allTable.rowCount() - 1, allTable.indexOf("total@2076-03"));
        assertEquals("r7@2026-09-13", defaultTable.scrollToRowId());
        assertEquals(defaultTable.scrollToRowId(), allTable.scrollToRowId());
        assertEquals(200_894, forecast.rows().size());
        assertEquals(END, forecast.endDate());
    }

    /** Проверяет число/id строк и содержимое ограниченной выборки начала, сегодня, середины и конца. */
    private static void assertTable(TableModel table, ExpectedView layout, boolean expanded) {
        assertEquals(layout.rowIds().size(), table.rowCount());
        assertEquals(layout.scrollToRowId(), table.scrollToRowId());
        if (layout.rowIds().isEmpty()) {
            assertEquals(Placeholder.Kind.FILTERED, table.placeholder().kind());
            assertEquals(UiText.get("table.empty.filtered"), table.placeholder().text());
            assertEquals(1, table.placeholder().buttons().size());
            assertEquals("empty.clearFilter", table.placeholder().buttons().getFirst().id());
            assertEquals(-1, table.indexOf("start"));
            assertEquals(-1, table.indexOf("past@group"));
            return;
        }
        assertNull(table.placeholder());
        Set<Integer> samples = new LinkedHashSet<>();
        for (int index : List.of(0, 1, 2, 3, table.rowCount() / 4, table.rowCount() / 2,
                table.rowCount() * 3 / 4, table.rowCount() - 2, table.rowCount() - 1)) {
            if (index >= 0 && index < table.rowCount()) samples.add(index);
        }
        int today = layout.rowIds().indexOf(layout.scrollToRowId());
        for (int i = Math.max(0, today - 1); i <= today + 2 && i < table.rowCount(); i++) samples.add(i);
        for (int index : samples) {
            String id = layout.rowIds().get(index);
            assertEquals(index, table.indexOf(id), id);
            TableRowView row = table.row(index);
            assertEquals(id, row.rowId());
            if (id.equals("start")) {
                assertEquals(RowKind.START, row.kind());
                assertEquals("1 000 000,00", row.cells().get(6));
                assertEquals("", row.cells().get(4));
                assertEquals("", row.cells().get(5));
            } else if (id.equals("past@group")) {
                assertEquals(RowKind.PAST_HEADER, row.kind());
                assertEquals(UiText.get(expanded ? "table.past.expanded" : "table.past.collapsed",
                        layout.pastEventCount()), row.cells().getFirst());
            } else if (id.startsWith("total@")) {
                var month = expected.months().get(YearMonth.parse(id.substring(6)));
                assertEquals(RowKind.MONTH_TOTAL, row.kind());
                assertEquals(formatMinor(month.incomeMinor()), row.cells().get(4));
                assertEquals(formatMinor(month.expenseMinor()), row.cells().get(5));
                assertEquals(formatMinor(month.closingBalanceMinor()), row.cells().get(6));
            } else {
                ExpectedEvent event = expectedEvent(id);
                assertEquals(RowKind.RULE, row.kind());
                assertEquals(event.date().format(DateTimeFormatter.ofPattern("dd.MM.uuuu")), row.cells().getFirst());
                assertEquals(event.title(), row.cells().get(2));
                assertEquals(event.category(), row.cells().get(3));
                assertEquals(event.amountMinor() > 0 ? formatMinor(event.amountMinor()) : "", row.cells().get(4));
                assertEquals(event.amountMinor() < 0 ? formatMinor(-event.amountMinor()) : "", row.cells().get(5));
                assertEquals(formatMinor(event.balanceAfterMinor()), row.cells().get(6));
                assertEquals("", row.cells().get(7));
            }
        }
        assertEquals(0, table.indexOf("start"));
        assertEquals(1, table.indexOf("past@group"));
        if (!expanded) {
            assertEquals(-1, table.indexOf("r5@2026-03-13"));
            assertEquals(-1, table.indexOf("total@2026-03"));
        }
    }

    /** @return независимое событие по id из одиннадцати событий его календарного дня */
    private static ExpectedEvent expectedEvent(String id) {
        LocalDate date = LocalDate.parse(id.substring(id.indexOf('@') + 1));
        int first = Math.toIntExact(ChronoUnit.DAYS.between(START, date)) * 11;
        return expected.events().subList(first, first + 11).stream()
                .filter(event -> event.rowId().equals(id)).findFirst().orElseThrow();
    }

    /** @return известный поисковый случай по стабильному имени, без сопоставления текста */
    private static SearchCase search(String name) {
        return LargePerformancePlanFixture.searchCases().stream()
                .filter(search -> search.name().equals(name)).findFirst().orElseThrow();
    }

    /** @return вид полного горизонта для нагрузки; состояние прошедших задаётся отдельно */
    private static ViewState all(String query, boolean totals) {
        return ViewState.defaults().withPeriod(PeriodChoice.ALL).withFilterText(query).withMonthTotals(totals);
    }

    /** Создаёт только проверяемое состояние с уже рассчитанным в теле теста прогнозом. */
    private static AppState state(Forecast forecast, ViewState view, boolean expanded) {
        DocumentView document = new DocumentView(plan, file, false, false, "", false, "", forecast, "", List.of());
        return new AppState(1, ClientProfile.swing(), TODAY, file.getParent(), null, document, view, "", expanded,
                AppSettings.defaults(), null, List.of(), null, null, "");
    }

    /** @return формат копеек из спецификации, без Money.format и других производственных форматтеров */
    private static String formatMinor(long value) {
        long absolute = Math.abs(value);
        return (value < 0 ? "-" : "") + String.format(Locale.ROOT, "%,d", absolute / 100).replace(',', ' ')
                + String.format(Locale.ROOT, ",%02d", absolute % 100);
    }
}
