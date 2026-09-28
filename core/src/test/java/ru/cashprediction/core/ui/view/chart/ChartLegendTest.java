package ru.cashprediction.core.ui.view.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.SamplePlan;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Легенда графика (спецификация v2, §5.3 «Легенда»): элементы появляются только при своём условии, тексты, образцы и
 * подсказки совпадают со спецификацией.
 */
class ChartLegendTest {

    private static List<LegendItem> legend(AppState state) {
        return ChartFixtures.model(state).layout(1200, 700).legend();
    }

    private static List<String> ids(List<LegendItem> legend) {
        return legend.stream().map(LegendItem::id).toList();
    }

    @Test
    void samplePlanShowsEveryConditionalItemExceptBars() {
        AppState state = ChartFixtures.state(SamplePlan.create(ChartFixtures.TODAY), ViewState.defaults(),
                ChartFixtures.TODAY);
        LocalDate reach = state.document().forecast().summary().goalReachDate().orElseThrow();
        List<LegendItem> legend = legend(state);
        assertEquals(List.of(
                new LegendItem("balance", "Баланс, 01.09.2026 - 31.08.2027", LegendItem.Swatch.LINE, ColorToken.ACCENT,
                        "Баланс на конец каждого дня"),
                new LegendItem("zero", "Ноль", LegendItem.Swatch.LINE, ColorToken.LINE_ZERO,
                        "Нулевой баланс: ниже этой линии денег не хватает"),
                new LegendItem("cushion", "Подушка", LegendItem.Swatch.DASH, ColorToken.LINE_CUSHION,
                        "Подушка безопасности: 50 000,00 ₽"),
                new LegendItem("goal", "Цель", LegendItem.Swatch.DASH, ColorToken.LINE_GOAL,
                        "Цель «Отпуск»: 300 000,00 ₽, достигается " + UiFormats.date(reach)),
                new LegendItem("today", "Сегодня", LegendItem.Swatch.DASH, ColorToken.LINE_TODAY,
                        "Сегодня, 13.09.2026: левее - прошедшие дни"),
                new LegendItem("income", "Доход", LegendItem.Swatch.DOT, ColorToken.INCOME, "Дни с доходами"),
                new LegendItem("expense", "Расход", LegendItem.Swatch.DOT, ColorToken.EXPENSE, "Дни с расходами"),
                new LegendItem("mixed", "Доход и расход", LegendItem.Swatch.DOT, ColorToken.MARKER_MIXED,
                        "Дни с доходами и расходами")), legend);
    }

    @Test
    void barsItemAppearsLastWhenBarsAreShown() {
        AppState state = ChartFixtures.state(SamplePlan.create(ChartFixtures.TODAY),
                ViewState.defaults().withChartBars(true), ChartFixtures.TODAY);
        assertEquals(new LegendItem("bars", "Итог месяца", LegendItem.Swatch.BOX, ColorToken.INCOME,
                "Доходы минус расходы за месяц"), legend(state).getLast());
    }

    @Test
    void unreachableGoalSaysNotReached() {
        Plan plan = SamplePlan.create(ChartFixtures.TODAY).withGoal(new Goal("Дом", Money.ofMajor(100_000_000), null));
        AppState state = ChartFixtures.state(plan, ViewState.defaults(), ChartFixtures.TODAY);
        LegendItem goal = legend(state).stream().filter(item -> item.id().equals("goal")).findFirst().orElseThrow();
        assertEquals("Цель «Дом»: 100 000 000,00 ₽, не достигается", goal.tooltip());
    }

    @Test
    void itemsWithoutConditionAreAbsent() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        Plan plan = ChartFixtures.plan(start, new Horizon.Months(3), 1_000,
                List.of(ChartFixtures.monthly("r1", "Зарплата", Kind.INCOME, 100, 5)), List.of(), List.of());
        assertEquals(List.of("balance", "zero", "income", "expense", "mixed"),
                ids(legend(ChartFixtures.state(plan, ViewState.defaults(), ChartFixtures.TODAY))),
                "без подушки, цели и сегодня вне диапазона");
        assertEquals(List.of("balance", "zero"),
                ids(legend(ChartFixtures.state(plan, ViewState.defaults().withChartMarkers(false), ChartFixtures.TODAY))),
                "маркеры выключены");
        assertEquals(List.of("balance", "zero", "today", "income", "expense", "mixed"),
                ids(legend(ChartFixtures.state(plan, ViewState.defaults().withPeriod(PeriodChoice.ALL), start))),
                "сегодня в диапазоне");
    }

    @Test
    void markerItemsNeedAtLeastOneMarker() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        Plan empty = ChartFixtures.plan(start, new Horizon.Months(3), 1_000, List.of(), List.of(), List.of());
        assertEquals(List.of("balance", "zero", "today"),
                ids(legend(ChartFixtures.state(empty, ViewState.defaults(), start))));
    }

    @Test
    void failedForecastHasNoLegend() {
        assertEquals(List.of(), legend(ChartFixtures.failed(SamplePlan.create(ChartFixtures.TODAY), "x")));
    }
}
