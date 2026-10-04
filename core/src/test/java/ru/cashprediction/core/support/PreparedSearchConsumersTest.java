package ru.cashprediction.core.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppClock;
import ru.cashprediction.core.app.AppController;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
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
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.chart.ChartLayout;
import ru.cashprediction.core.ui.view.chart.ChartPrimitive;
import ru.cashprediction.core.ui.view.chart.HitRegion;
import ru.cashprediction.core.ui.view.popup.PopupBuilders;
import ru.cashprediction.core.ui.view.status.StatusBuilder;
import ru.cashprediction.core.ui.view.status.StatusModel;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;
import ru.cashprediction.core.ui.view.table.LazyTableModel;
import ru.cashprediction.core.ui.view.table.Placeholder;

/**
 * Независимые небольшие семантические примеры для настоящих table/chart/status/popup/navigation потребителей.
 * Проверяет общие модели всех трёх профилей без клиентов, таймеров производительности и изменения большой фикстуры.
 */
class PreparedSearchConsumersTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);
    private static final RuleId INCOME = new RuleId("r1");
    private static final RuleId EXPENSE = new RuleId("r2");
    @TempDir
    Path home;

    /** Реальные потребители сохраняют сортировку, заметки override и полный баланс для каждого профиля клиента. */
    @Test
    void sharedModelsUseIndependentSearchAnswersWithoutChangingFinancialData() {
        Plan plan = plan();
        Forecast forecast = ForecastEngine.forecast(plan, WhatIf.NONE, TODAY, false);
        assertEquals(109_000, forecast.balanceAt(TODAY).minor());
        assertEquals(125_000, forecast.balanceAt(TODAY.plusDays(1)).minor());
        assertEquals(135_000, forecast.endBalance().minor());
        assertEquals(37_000, forecast.summary().totalIncome().minor());
        assertEquals(2_000, forecast.summary().totalExpense().minor());
        List<Search> cases = List.of(
                new Search("", List.of("r1@2026-09-13", "r2@2026-09-13", "r1@2026-09-14", "t1",
                        "r2@2026-09-14", "r1@2026-09-15"), List.of(0, 1, 2)),
                new Search("iNcOmErEpEaT", List.of("r1@2026-09-13", "r1@2026-09-14", "r1@2026-09-15"), List.of(0, 1, 2)),
                new Search("  ЖИЛЬЕ   ДОМ ", List.of("r1@2026-09-13", "r1@2026-09-14", "r1@2026-09-15"), List.of(0, 1, 2)),
                new Search("\tеЖ\u202f note ", List.of("r1@2026-09-13", "r1@2026-09-15"), List.of(0, 2)),
                new Search("override only", List.of("r1@2026-09-14"), List.of(1)),
                new Search("gift note", List.of("t1"), List.of(1)),
                new Search("absent", List.of(), List.of()),
                new Search("IncomeRepeat жилье", List.of(), List.of()));
        for (ClientProfile profile : profiles()) {
            var baselineSummary = SummaryBuilder.build(state(profile, plan, forecast, view("")));
            List<?> baselineLine = null;
            for (Search search : cases) {
                AppState state = state(profile, plan, forecast, view(search.query()));
                var table = LazyTableModel.build(state, 1);
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < table.rowCount(); i++) ids.add(table.row(i).rowId());
                List<String> expected = new ArrayList<>();
                if (!search.eventIds().isEmpty()) expected.add("start");
                expected.addAll(search.eventIds());
                assertEquals(expected, ids, profile.kind() + " " + search.query());
                if (expected.isEmpty()) {
                    assertEquals(Placeholder.Kind.FILTERED, table.placeholder().kind());
                } else {
                    assertEquals("start", table.scrollToRowId(), "A plan starting today scrolls to its START row");
                }
                var status = StatusBuilder.build(state, LargePerformancePlanFixture.CLOCK.instant());
                assertEquals(UiText.get("status.rows", search.eventIds().size() + 1), status.find(StatusModel.ROWS).orElseThrow().text());
                assertEquals(baselineSummary, SummaryBuilder.build(state));

                var scene = ChartLayout.model(state, 1).layout(1200, 700);
                List<String> markerIds = scene.hits().stream().filter(hit -> hit.kind() == HitRegion.Kind.MARKER).map(HitRegion::id).toList();
                assertEquals(search.markerDays().stream().map(day -> "marker@" + TODAY.plusDays(day)).toList(), markerIds);
                assertEquals(List.of("bar@2026-09", "bar@2026-10"), scene.hits().stream()
                        .filter(hit -> hit.kind() == HitRegion.Kind.BAR).map(HitRegion::id).toList());
                var line = scene.primitives().stream().filter(ChartPrimitive.Polyline.class::isInstance)
                        .map(ChartPrimitive.Polyline.class::cast).findFirst().orElseThrow();
                if (baselineLine == null) baselineLine = line.points();
                assertEquals(baselineLine, line.points(), "Text search changes markers, not the balance line");
                assertEquals(scene.plot().yOf(135_000), line.points().getLast().y(), 0.000001);
                assertEquals("", scene.emptyText());
                for (int day = 0; day < 3; day++) {
                    LocalDate date = TODAY.plusDays(day);
                    int events = (int) search.eventIds().stream().filter(id -> id.endsWith("@" + date)
                            || id.equals("t1") && date.equals(TODAY.plusDays(1))).count();
                    // JavaFX: PopupWindow -> Swing: JWindow -> Web: div.
                    var card = PopupBuilders.dayCard(state, date);
                    assertEquals(events, card.lines().size());
                    assertEquals(events == 0 ? UiText.get("day.none") : "", card.noneText());
                    assertEquals("", card.moreText());
                }
            }
        }
    }

    /** Skipped-события остаются в таблице/карточке по флагу, но маркеры по прежнему контракту их не рисуют. */
    @Test
    void skippedSearchPreservesDifferentTableAndChartMarkerContracts() {
        Plan plan = plan();
        ViewState view = view("skip note").withShowSkipped(true);
        Forecast forecast = ForecastEngine.forecast(plan, WhatIf.NONE, TODAY, true);
        AppState state = state(ClientProfile.swing(), plan, forecast, view);
        var table = LazyTableModel.build(state, 1);
        assertEquals(2, table.rowCount());
        assertEquals("start", table.row(0).rowId());
        assertEquals("r2@2026-09-15", table.row(1).rowId());
        assertEquals(UiText.get("status.rows", 2), StatusBuilder.build(state, LargePerformancePlanFixture.CLOCK.instant())
                .find(StatusModel.ROWS).orElseThrow().text());
        // JavaFX: PopupWindow -> Swing: JWindow -> Web: div.
        var card = PopupBuilders.dayCard(state, TODAY.plusDays(2));
        assertEquals(1, card.lines().size());
        assertTrue(card.lines().getFirst().text().contains(UiText.get("day.skipped")));
        var scene = ChartLayout.model(state, 1).layout(1200, 700);
        assertTrue(scene.hits().stream().noneMatch(hit -> hit.kind() == HitRegion.Kind.MARKER));
        assertEquals(135_000, forecast.endBalance().minor());
    }

    /** Navigation перестраивается после query/view и нового прогноза, включая замену заметки того же occurrence. */
    @Test
    void actualNavigationDoesNotReuseStaleQueryOrOverrideAnswers() {
        for (ClientProfile profile : profiles()) {
            FakeUiPort port = new FakeUiPort(profile);
            AppController app = new AppController(port, new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                    home, home.resolve("CashMemory"), AppClock.fixedToday(TODAY)));
            try {
                app.showMain(null);
                app.document().replace(plan(), null, false, List.of());
                app.updateView(ignored -> view(""));
                Forecast before = app.document().forecast();
                app.filterText("ЕЖ note");
                app.views().showInTable(TODAY.plusDays(1));
                assertEquals("r1@2026-09-15", app.state().selectedRowId());
                assertSame(before, app.document().forecast());

                app.filterText("override only");
                app.views().showInTable(TODAY);
                assertEquals("r1@2026-09-14", app.state().selectedRowId());
                app.document().edit("change override", value -> value.withAdjustmentPut(new Adjustment(
                        new OccurrenceKey(INCOME, TODAY.plusDays(1)), new Adjustment.ChangeAmount(Money.ofMajor(120)), "Changed note")));
                Forecast edited = app.document().forecast();
                assertNotSame(before, edited);
                int reveals = port.calls("revealRow").size();
                app.views().showInTable(TODAY);
                assertEquals(reveals, port.calls("revealRow").size(), "Old override query must no longer find any event");
                app.filterText("changed note");
                app.views().showInTable(TODAY);
                assertEquals("r1@2026-09-14", app.state().selectedRowId());
                app.filterText("gift note");
                app.views().showInTable(TODAY);
                assertEquals("t1", app.state().selectedRowId());
                app.updateView(view -> view.withShowOneTime(false));
                assertSame(edited, app.document().forecast());
                reveals = port.calls("revealRow").size();
                app.views().showInTable(TODAY);
                assertEquals(reveals, port.calls("revealRow").size(), "One-time flag invalidates Navigation membership");
            } finally {
                port.manualScheduler().shutdown();
            }
        }
    }

    /** Создаёт небольшой независимый план: три дня повторов, отдельная заметка override, пропуск и разовая. */
    private static Plan plan() {
        RecurringRule income = new RecurringRule(INCOME, "IncomeRepeat", Kind.INCOME, Money.ofMajor(100), "Жильё\u00a0 Дом",
                new Recurrence.EveryNDays(1), TODAY, TODAY.plusDays(2), WeekendPolicy.NONE, true, "Ёж note");
        RecurringRule expense = new RecurringRule(EXPENSE, "ExpenseRepeat", Kind.EXPENSE, Money.ofMajor(10), "Other",
                new Recurrence.EveryNDays(1), TODAY, TODAY.plusDays(2), WeekendPolicy.NONE, true, "Plain note");
        OneTimeTransaction single = new OneTimeTransaction(new TxId("t1"), TODAY.plusDays(1), "Single", Kind.INCOME,
                Money.ofMajor(50), "BonusCategory", "Gift note");
        return new Plan("Search consumers", "", Plan.DEFAULT_CURRENCY, TODAY, Money.ofMajor(1_000), new Horizon.Months(1),
                Money.ZERO, null, List.of(income, expense), List.of(single), List.of(
                        new Adjustment(new OccurrenceKey(INCOME, TODAY.plusDays(1)), new Adjustment.ChangeAmount(Money.ofMajor(120)), "Override ONLY"),
                        new Adjustment(new OccurrenceKey(EXPENSE, TODAY.plusDays(2)), new Adjustment.Skip(), "Skip note")), List.of());
    }

    /** Фиксирует полный период, служебные итоги отдельно проверяет график; START в пустой таблице не остаётся. */
    private static ViewState view(String query) {
        return ViewState.defaults().withPeriod(PeriodChoice.ALL).withMonthTotals(false).withChartBars(true).withFilterText(query);
    }

    /** Создаёт только общий снимок, не читая файлы и не создавая клиентские окна. */
    private static AppState state(ClientProfile profile, Plan plan, Forecast forecast, ViewState view) {
        DocumentView document = new DocumentView(plan, null, false, false, "", false, "", forecast, "", List.of());
        return new AppState(1, profile, TODAY, Path.of("CashMemory"), null, document, view, "", false,
                AppSettings.defaults(), null, List.of(), null, null, "");
    }

    /** Возвращает все клиентские профили без загрузки JavaFX/Swing/браузера. */
    private static List<ClientProfile> profiles() {
        return List.of(ClientProfile.fx("25"), ClientProfile.swing(), ClientProfile.web());
    }

    /** Независимые ожидаемые id событий по порядку и номера дней с маркерами. */
    private record Search(String query, List<String> eventIds, List<Integer> markerDays) { }
}
