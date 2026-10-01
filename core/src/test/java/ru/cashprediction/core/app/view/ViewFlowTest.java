package ru.cashprediction.core.app.view;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.FocusTarget;
import ru.cashprediction.core.app.RevealMode;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.*;
import ru.cashprediction.core.model.*;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.form.Presentation;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.status.StatusLevel;
import ru.cashprediction.core.ui.view.table.LazyTableModel;

/** Проверяет требования S2 к виду, фильтрам, навигации и изменению горизонта. */
class ViewFlowTest {
    @TempDir Path home;

    private ViewHelpHarness harness() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        Plan plan = Plan.empty("Plan", start).withHorizon(new Horizon.Months(36))
                .withOneTimes(List.of(tx("past", start.plusDays(5), Kind.INCOME, "Past"),
                        tx("today-a", ViewHelpHarness.TODAY, Kind.INCOME, "Доход"),
                        tx("today-b", ViewHelpHarness.TODAY, Kind.EXPENSE, "Расход"),
                        tx("next", ViewHelpHarness.TODAY.plusDays(2), Kind.INCOME, "Ёж"),
                        tx("far", ViewHelpHarness.TODAY.plusMonths(26), Kind.INCOME, "Far")));
        return new ViewHelpHarness(plan, home);
    }

    private static OneTimeTransaction tx(String id, LocalDate date, Kind kind, String title) {
        return new OneTimeTransaction(new TxId(id), date, title, kind, new Money(10000), "Category", "Note");
    }

    @Test void everyFlagTogglesOnlyItsOwnSettingAndNeverDirtiesThePlan() {
        Map<CommandId, Function<ViewState, ViewState>> flags = Map.of(
                CommandId.VIEW_FLAG_SHOW_INCOME, v -> v.withShowIncome(!v.showIncome()),
                CommandId.VIEW_FLAG_SHOW_EXPENSE, v -> v.withShowExpense(!v.showExpense()),
                CommandId.VIEW_FLAG_SHOW_ONE_TIME, v -> v.withShowOneTime(!v.showOneTime()),
                CommandId.VIEW_FLAG_SHOW_SKIPPED, v -> v.withShowSkipped(!v.showSkipped()),
                CommandId.VIEW_FLAG_MONTH_TOTALS, v -> v.withMonthTotals(!v.monthTotals()),
                CommandId.VIEW_FLAG_CHART_MARKERS, v -> v.withChartMarkers(!v.chartMarkers()),
                CommandId.VIEW_FLAG_CHART_BARS, v -> v.withChartBars(!v.chartBars()),
                CommandId.VIEW_FLAG_SUMMARY_PANEL, v -> v.withSummaryPanel(!v.summaryPanel()));
        flags.forEach((command, change) -> {
            ViewHelpHarness h = harness();
            Plan original = h.document.plan();
            ViewState before = h.document.viewState();
            h.views.toggleFlag(command);
            assertEquals(change.apply(before), h.document.viewState(), command.name());
            assertEquals(change.apply(before).applyTo(ru.cashprediction.core.document.AppSettings.defaults()), h.settings);
            h.views.toggleFlag(command);
            assertEquals(before, h.document.viewState());
            assertSame(original, h.document.plan());
            assertFalse(h.document.isDirty());
            assertFalse(h.document.canUndo());
        });
    }

    @Test void everyPeriodUpdatesSettingsAndScrollsWithoutSelectingOrEditing() {
        for (PeriodChoice period : PeriodChoice.values()) {
            ViewHelpHarness h = harness();
            h.document.setViewState(h.document.viewState().withPeriod(period == PeriodChoice.ALL
                    ? PeriodChoice.M3 : PeriodChoice.ALL));
            h.selection = "past";
            h.views.setPeriod(period);
            assertEquals(period, h.document.viewState().period());
            assertEquals(period, h.settings.period());
            assertEquals("past", h.selection);
            assertFalse(h.pastExpanded);
            assertEquals(List.of("today-a", RevealMode.SCROLL_TO_TOP), h.port.calls("revealRow").getLast().args());
            int updates = h.viewUpdates;
            h.views.setPeriod(period);
            assertEquals(updates, h.viewUpdates);
            assertFalse(h.document.isDirty());
            assertFalse(h.document.canUndo());
        }
    }

    @Test void modesCloseQuickEditAndFocusTheChosenAreaOnlyOnTransition() {
        ViewHelpHarness h = harness();
        h.installQuickEdit();
        h.views.setMode(ViewMode.TABLE);
        assertEquals(0, h.formClosures);
        h.views.setMode(ViewMode.CHART);
        assertEquals(1, h.formClosures);
        assertEquals(FocusTarget.CHART, h.port.calls("focus").getLast().args().getFirst());
        h.views.setMode(ViewMode.CHART);
        assertEquals(1, h.formClosures);
        h.views.setMode(ViewMode.TABLE);
        assertEquals(FocusTarget.TABLE, h.port.calls("focus").getLast().args().getFirst());
        assertFalse(h.document.isDirty());
    }

    @Test void filterFocusClearAndPastAreViewOperations() {
        ViewHelpHarness h = harness();
        h.views.filterText("  ЕЖ  ");
        assertEquals("  ЕЖ  ", h.document.viewState().filterText());
        h.views.showInTable(ViewHelpHarness.TODAY);
        assertEquals("next", h.selection);
        h.views.focusFilter();
        h.views.focusTable();
        assertEquals(List.of(FocusTarget.FILTER),
                h.port.calls("focus").get(h.port.calls("focus").size() - 2).args());
        assertEquals(List.of(FocusTarget.TABLE), h.port.calls("focus").getLast().args());
        h.views.clearFilter();
        assertEquals("", h.document.viewState().filterText());
        h.views.filterText(null);
        h.views.togglePast();
        assertTrue(h.pastExpanded);
        h.views.togglePast();
        assertFalse(h.pastExpanded);
        assertFalse(h.document.isDirty());
        assertFalse(h.document.canUndo());
    }

    @Test void showInTableSelectsTheFirstSameDayEventAndExpandsPast() {
        ViewHelpHarness h = harness();
        h.views.setMode(ViewMode.CHART);
        h.views.showInTable(LocalDate.of(2026, 9, 1));
        assertEquals(ViewMode.TABLE, h.document.viewState().mode());
        assertEquals("past", h.selection);
        assertTrue(h.pastExpanded);
        assertEquals(List.of("past", RevealMode.SELECT_AND_SCROLL), h.port.calls("revealRow").getLast().args());
        h.views.showInTable(ViewHelpHarness.TODAY);
        assertEquals("today-a", h.selection);
        assertFalse(h.document.isDirty());
    }

    @Test void navigationHonorsIncomeExpenseOneTimeTextAndPeriodFilters() {
        ViewHelpHarness h = harness();
        h.views.toggleFlag(CommandId.VIEW_FLAG_SHOW_INCOME);
        h.views.showInTable(ViewHelpHarness.TODAY);
        assertEquals("today-b", h.selection);
        h.views.selectRow("past");
        assertEquals("today-b", h.selection);
        assertFalse(h.pastExpanded);
        h.views.toggleFlag(CommandId.VIEW_FLAG_SHOW_EXPENSE);
        h.views.showInTable(ViewHelpHarness.TODAY);
        assertEquals("status.msg.noRowAfter", h.statuses.getLast().get(1));
        h.views.toggleFlag(CommandId.VIEW_FLAG_SHOW_INCOME);
        h.views.toggleFlag(CommandId.VIEW_FLAG_SHOW_ONE_TIME);
        h.views.selectRow("today-a");
        assertEquals("today-b", h.selection);
        h.views.toggleFlag(CommandId.VIEW_FLAG_SHOW_ONE_TIME);
        h.views.selectRow("far");
        assertEquals("today-b", h.selection);
        h.views.setPeriod(PeriodChoice.ALL);
        h.views.selectRow("far");
        assertEquals("far", h.selection);
        h.views.filterText("missing");
        h.views.selectRow("past");
        assertEquals("far", h.selection);
        assertFalse(h.pastExpanded);
    }

    @Test void everyFinitePeriodIncludesItsLastDayAndExcludesTheNextDay() {
        for (PeriodChoice period : PeriodChoice.values()) {
            if (period == PeriodChoice.ALL) continue;
            ViewHelpHarness h = harness();
            LocalDate last = ViewHelpHarness.TODAY.plusMonths(period.months()).minusDays(1);
            Plan plan = h.document.plan().withOneTimes(List.of(tx("inside", last, Kind.INCOME, "Inside"),
                    tx("outside", last.plusDays(1), Kind.INCOME, "Outside")));
            h.document.replace(plan, null, false, List.of());
            h.views.setPeriod(period);
            h.views.showInTable(last);
            assertEquals("inside", h.selection);
            h.views.selectRow("outside");
            assertEquals("inside", h.selection);
            h.views.showInTable(last.plusDays(1));
            assertEquals("status.msg.noRowAfter", h.statuses.getLast().get(1));
        }
    }

    @Test void skippedAndMovedRuleEventsUseFiltersAndActualDates() {
        ViewHelpHarness h = harness();
        Forecast base = h.document.forecast();
        LocalDate original = ViewHelpHarness.TODAY.plusDays(30);
        LocalDate moved = ViewHelpHarness.TODAY.minusDays(1);
        ForecastRow movedRow = new ForecastRow(moved, original, "Moved", Kind.INCOME, "", new Money(100),
                Money.ZERO, Origin.RULE, new RuleId("moved"), null,
                new Flags(false, false, true, false, true, false), "");
        ForecastRow skipped = new ForecastRow(ViewHelpHarness.TODAY, ViewHelpHarness.TODAY, "Skipped",
                Kind.INCOME, "", new Money(100), Money.ZERO, Origin.RULE, new RuleId("skipped"), null,
                new Flags(false, false, false, true, false, false), "");
        h.overrideForecast = new Forecast(base.plan(), base.whatIf(), base.today(), base.anchor(),
                List.of(base.rows().getFirst(), movedRow, skipped), base.dailyBalance(), base.dailyStart(),
                base.summary(), List.of());
        h.views.selectRow(skipped.rowId());
        assertEquals("", h.selection);
        h.views.showInTable(moved);
        assertEquals(movedRow.rowId(), h.selection);
        assertTrue(h.pastExpanded);
        h.views.toggleFlag(CommandId.VIEW_FLAG_SHOW_SKIPPED);
        h.views.showInTable(ViewHelpHarness.TODAY);
        assertEquals(skipped.rowId(), h.selection);
        h.views.toggleFlag(CommandId.VIEW_FLAG_SHOW_ONE_TIME);
        h.views.selectRow(movedRow.rowId());
        assertEquals(movedRow.rowId(), h.selection);
    }

    @Test void missingDateWarnsWithFormattedDateAndNoFalseSelectionOrReveal() {
        ViewHelpHarness h = harness();
        h.selection = "today-a";
        LocalDate date = ViewHelpHarness.TODAY.plusYears(8);
        h.views.showInTable(date);
        assertEquals("today-a", h.selection);
        assertEquals(List.of(StatusLevel.WARN, "status.msg.noRowAfter", List.of(UiFormats.date(date))),
                h.statuses.getLast());
        assertTrue(h.port.calls("revealRow").isEmpty());
        h.views.showInTable(null);
        assertEquals(1, h.statuses.size());
    }

    @Test void rowSelectionExpandsPastButDoesNotScrollAndHonorsServiceRowVisibility() {
        ViewHelpHarness h = harness();
        h.views.selectRow("past");
        assertEquals("past", h.selection);
        assertTrue(h.pastExpanded);
        assertTrue(h.port.calls("revealRow").isEmpty());
        String total = LazyTableModel.totalRowId(YearMonth.of(2026, 9));
        h.views.selectRow(total);
        assertEquals(total, h.selection);
        h.views.togglePast();
        h.views.selectRow("today-a");
        h.views.selectRow(total);
        assertEquals("today-a", h.selection);
        h.views.selectRow(LazyTableModel.PAST_HEADER_ROW_ID);
        assertEquals(LazyTableModel.PAST_HEADER_ROW_ID, h.selection);
        h.views.selectRow("unknown");
        assertEquals(LazyTableModel.PAST_HEADER_ROW_ID, h.selection);
        h.views.selectRow(null);
        assertEquals("", h.selection);
    }

    @Test void failedForecastAndEmptyPlanDoNotInventRows() {
        ViewHelpHarness h = harness();
        h.failedForecast = true;
        h.views.showInTable(ViewHelpHarness.TODAY);
        h.views.selectRow("start");
        h.views.scrollToToday();
        assertEquals("", h.selection);
        assertTrue(h.port.calls("revealRow").isEmpty());
        h = new ViewHelpHarness(Plan.empty("Empty", ViewHelpHarness.TODAY), home);
        h.views.showInTable(ViewHelpHarness.TODAY);
        h.views.selectRow("start");
        assertEquals("", h.selection);
        assertTrue(h.port.calls("revealRow").isEmpty());
    }

    @Test void sliderIsOneUndoableEditAndClampedPositionDoesNotCutLongHorizons() {
        ViewHelpHarness h = harness();
        h.views.horizonSliderCommit(36);
        assertFalse(h.document.canUndo());
        h.views.horizonSliderCommit(24);
        assertEquals(new Horizon.Months(24), h.document.plan().horizon());
        assertTrue(h.document.isDirty());
        assertEquals(UiText.get("undo.horizon", UiFormats.horizonLabel(new Horizon.Months(24),
                h.document.plan().startDate())), h.document.undoDescription().orElseThrow());
        h.document.undo();
        assertEquals(new Horizon.Months(36), h.document.plan().horizon());
        assertFalse(h.document.canUndo());
        assertFalse(h.document.isDirty());
        for (Horizon horizon : List.of(new Horizon.Months(600), new Horizon.Years(50),
                new Horizon.Until(h.document.plan().startDate().plusYears(40)))) {
            h.document.replace(h.document.plan().withHorizon(horizon), null, false, List.of());
            h.views.horizonSliderCommit(120);
            assertEquals(horizon, h.document.plan().horizon());
            assertFalse(h.document.canUndo());
            h.views.horizonSliderCommit(119);
            assertEquals(new Horizon.Months(119), h.document.plan().horizon());
        }
        assertThrows(IllegalArgumentException.class, () -> h.views.horizonSliderCommit(0));
        assertThrows(IllegalArgumentException.class, () -> h.views.horizonSliderCommit(121));
    }

    @Test void yearsAndUntilUseEquivalentSliderMonthsAndCustomMonthsDefaults() {
        for (Horizon horizon : List.of(new Horizon.Years(2),
                new Horizon.Until(LocalDate.of(2028, 8, 31)),
                new Horizon.Until(LocalDate.of(2026, 9, 1)))) {
            ViewHelpHarness h = harness();
            h.document.replace(h.document.plan().withHorizon(horizon), null, false, List.of());
            int months = (int) horizon.approximateMonths(h.document.plan().startDate());
            h.views.horizonSliderCommit(months);
            assertEquals(horizon, h.document.plan().horizon());
            assertFalse(h.document.canUndo());
            h.views.customMonths();
            assertEquals(Integer.toString(months), h.form.state().value("value"));
            h.form.fieldChanged("value", "1", true, 1);
            h.form.buttonPressed("apply");
            assertEquals(new Horizon.Months(1), h.document.plan().horizon());
        }
    }

    @Test void scrollForAFuturePlanStartsAtTheBalanceWithoutSelectingIt() {
        LocalDate start = ViewHelpHarness.TODAY.plusDays(20);
        ViewHelpHarness h = new ViewHelpHarness(Plan.empty("Future", start)
                .withOneTimes(List.of(tx("future", start.plusDays(10), Kind.INCOME, "Future"))), home);
        h.views.scrollToToday();
        assertEquals(List.of("start", RevealMode.SCROLL_TO_TOP), h.port.calls("revealRow").getLast().args());
        assertEquals("", h.selection);
        h.views.showInTable(start);
        assertEquals("future", h.selection);
    }

    @Test void customMonthsShowsCurrentHorizonValidatesAndAppliesThroughUndo() {
        ViewHelpHarness h = harness();
        h.views.customMonths();
        assertEquals(WindowType.TEXT_INPUT, h.request.type());
        assertEquals("customMonths", h.request.context().get("purpose"));
        assertEquals(Presentation.TEXT_INPUT, h.form.spec().presentation());
        assertTrue(h.request.modal());
        assertEquals("36", h.form.state().value("value"));
        assertEquals(UiText.get("s2.viewHelp.customMonths.header",
                UiFormats.horizonLabel(h.document.plan().horizon(), h.document.plan().startDate()),
                UiFormats.date(h.document.plan().endDate())), h.form.view().header());
        for (String invalid : List.of("", "0", "601", "2.5", "abc", "99999999999999999999999999999")) {
            h.form.fieldChanged("value", invalid, true, 1);
            assertEquals(UiText.get("s2.viewHelp.customMonths.error"), h.form.view().problem().text());
            h.form.buttonPressed("apply");
            assertFalse(h.document.canUndo(), invalid);
            assertEquals(0, h.formClosures, invalid);
        }
        h.form.fieldChanged("value", "600", true, 2);
        h.form.buttonPressed("apply");
        assertEquals(new Horizon.Months(600), h.document.plan().horizon());
        assertEquals(1, h.formClosures);
        h.document.undo();
        assertEquals(new Horizon.Months(36), h.document.plan().horizon());
        h.views.customMonths();
        h.form.buttonPressed("cancel");
        assertFalse(h.document.canUndo());
    }

    @Test void indexedNavigationOn200000RowsDoesNotScanOnRepeatedDateOrIdLookup() throws Exception {
        ViewHelpHarness h = harness();
        h.views.setPeriod(PeriodChoice.ALL);
        Forecast base = h.document.forecast();
        List<ForecastRow> rows = new ArrayList<>();
        rows.add(base.rows().getFirst());
        for (int i = 0; i < 200000; i++) {
            LocalDate date = base.startDate().plusDays(i / 200);
            rows.add(new ForecastRow(date, date, "Event", Kind.INCOME, "", new Money(100), Money.ZERO,
                    Origin.ONE_TIME, null, new TxId("t" + i), Flags.NONE, ""));
        }
        h.overrideForecast = new Forecast(base.plan(), base.whatIf(), base.today(), base.anchor(), rows,
                base.dailyBalance(), base.dailyStart(), base.summary(), List.of());
        LocalDate date = base.startDate().plusDays(999);
        h.views.showInTable(date);
        assertEquals("t199800", h.selection);
        Field cache = h.views.getClass().getDeclaredField("navigation");
        cache.setAccessible(true);
        Object index = cache.get(h.views);
        Field events = index.getClass().getDeclaredField("events");
        events.setAccessible(true);
        @SuppressWarnings("unchecked")
        CountingRows counted = new CountingRows((List<ForecastRow>) events.get(index));
        events.set(index, counted);
        h.views.showInTable(date);
        assertTrue(counted.reads <= 20, "Date search must use a binary lower bound");
        counted.reads = 0;
        h.views.selectRow("t199801");
        h.views.setMode(ViewMode.CHART);
        h.views.setMode(ViewMode.TABLE);
        h.views.selectRow("t199802");
        assertSame(index, cache.get(h.views));
        assertEquals(0, counted.reads, "Row id lookup must use the cached index");
    }

    /** Считает чтения событий после построения индекса, проверяя алгоритм без нестабильного таймера. */
    private static final class CountingRows extends AbstractList<ForecastRow> {
        private final List<ForecastRow> rows;
        int reads;
        CountingRows(List<ForecastRow> rows) { this.rows = rows; }
        /** {@inheritDoc} */
        @Override public ForecastRow get(int index) { reads++; return rows.get(index); }
        /** {@inheritDoc} */
        @Override public int size() { return rows.size(); }
    }
}
