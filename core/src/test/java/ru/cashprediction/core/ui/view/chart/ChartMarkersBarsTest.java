package ru.cashprediction.core.ui.view.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Маркеры дней и столбцы итогов месяцев (спецификация v2, §5.3): агрегация по дню (доход, расход, оба) с учётом
 * фильтров вида, пропущенные события и START без маркеров, предел 400 маркеров с уведомлением, полоса столбцов
 * высотой min(110, 22 %), зоны подсказок и их тексты.
 */
class ChartMarkersBarsTest {

    private static final double W = 1200;
    private static final double H = 700;
    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    /** Доход и расход 5-го, расход 10-го, разовый доход 20.01; горизонт январь-март 2026. */
    private static Plan plan(List<OneTimeTransaction> extraOneTimes, List<Adjustment> adjustments) {
        List<OneTimeTransaction> oneTimes = new java.util.ArrayList<>(
                List.of(ChartFixtures.oneTime("t1", LocalDate.of(2026, 1, 20), "Премия", Kind.INCOME, 500)));
        oneTimes.addAll(extraOneTimes);
        return ChartFixtures.plan(START, new Horizon.Months(3), 10_000, List.of(
                        ChartFixtures.monthly("r1", "Зарплата", Kind.INCOME, 1_000, 5),
                        ChartFixtures.monthly("r2", "Аренда", Kind.EXPENSE, 300, 5),
                        ChartFixtures.monthly("r3", "Кафе", Kind.EXPENSE, 200, 10)),
                oneTimes, adjustments);
    }

    private static ViewState all() {
        return ViewState.defaults().withPeriod(PeriodChoice.ALL);
    }

    private static ChartScene scene(Plan plan, ViewState view) {
        return ChartFixtures.model(ChartFixtures.state(plan, view, START)).layout(W, H);
    }

    private static List<LocalDate> markerDates(ChartScene scene) {
        return scene.hits().stream().filter(hit -> hit.kind() == HitRegion.Kind.MARKER).map(HitRegion::date).toList();
    }

    private static List<ColorToken> markerColors(ChartScene scene) {
        return ChartFixtures.ofType(scene, ChartPrimitive.Circle.class).stream().map(ChartPrimitive.Circle::fill).toList();
    }

    @Test
    void oneMarkerPerDayColouredByKinds() {
        AppState state = ChartFixtures.state(plan(List.of(), List.of()), all(), START);
        ChartScene scene = ChartFixtures.model(state).layout(W, H);
        assertEquals(List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 10), LocalDate.of(2026, 1, 20),
                LocalDate.of(2026, 2, 5), LocalDate.of(2026, 2, 10), LocalDate.of(2026, 3, 5),
                LocalDate.of(2026, 3, 10)), markerDates(scene), "START 01.01 без маркера");
        assertEquals(List.of(ColorToken.MARKER_MIXED, ColorToken.EXPENSE, ColorToken.INCOME, ColorToken.MARKER_MIXED,
                ColorToken.EXPENSE, ColorToken.MARKER_MIXED, ColorToken.EXPENSE), markerColors(scene));

        PlotTransform plot = scene.plot();
        double dayWidth = ChartFixtures.dayWidth(scene);
        ChartPrimitive.Circle first = ChartFixtures.ofType(scene, ChartPrimitive.Circle.class).getFirst();
        LocalDate day = LocalDate.of(2026, 1, 5);
        assertEquals(new ChartPrimitive.Circle(plot.xOf(day) + dayWidth / 2,
                plot.yOf(state.document().forecast().balanceAt(day).minor()), 3.5, ColorToken.MARKER_MIXED,
                ColorToken.BG_SURFACE, 1), first, "центр на балансе конца дня, белая обводка 1 px");
        assertEquals(1_070_000, state.document().forecast().balanceAt(day).minor());

        HitRegion hit = scene.hits().getFirst();
        assertEquals("marker@2026-01-05", hit.id());
        assertEquals(12, hit.width(), 1e-9);
        assertEquals(12, hit.height(), 1e-9);
        assertTrue(hit.contains(first.cx(), first.cy()));
        assertEquals("05.01.2026\n+1 000,00 ₽ - Зарплата\n-300,00 ₽ - Аренда\nБаланс: 10 700,00 ₽", hit.tooltip());
    }

    @Test
    void markersRespectIncomeExpenseAndOneTimeFilters() {
        Plan plan = plan(List.of(), List.of());
        ChartScene noExpense = scene(plan, all().withShowExpense(false));
        assertEquals(List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 20), LocalDate.of(2026, 2, 5),
                LocalDate.of(2026, 3, 5)), markerDates(noExpense));
        assertTrue(markerColors(noExpense).stream().allMatch(color -> color == ColorToken.INCOME));
        assertEquals("05.01.2026\n+1 000,00 ₽ - Зарплата\nБаланс: 10 700,00 ₽", noExpense.hits().getFirst().tooltip(),
                "подсказка без скрытых событий, баланс настоящий");

        ChartScene noIncome = scene(plan, all().withShowIncome(false));
        assertEquals(List.of(ColorToken.EXPENSE, ColorToken.EXPENSE, ColorToken.EXPENSE, ColorToken.EXPENSE,
                ColorToken.EXPENSE, ColorToken.EXPENSE), markerColors(noIncome));

        ChartScene noOneTime = scene(plan, all().withShowOneTime(false));
        assertFalse(markerDates(noOneTime).contains(LocalDate.of(2026, 1, 20)));
        assertEquals(6, markerDates(noOneTime).size());

        ChartScene filtered = scene(plan, all().withFilterText("АРЕНДА"));
        assertEquals(List.of(LocalDate.of(2026, 1, 5), LocalDate.of(2026, 2, 5), LocalDate.of(2026, 3, 5)),
                markerDates(filtered));
        assertTrue(markerColors(filtered).stream().allMatch(color -> color == ColorToken.EXPENSE));
    }

    @Test
    void skippedEventsHaveNoMarkerEvenWhenShown() {
        Adjustment skip = new Adjustment(new OccurrenceKey(new RuleId("r3"), LocalDate.of(2026, 1, 10)),
                new Adjustment.Skip(), "");
        Plan plan = plan(List.of(), List.of(skip));
        for (ViewState view : List.of(all(), all().withShowSkipped(true))) {
            ChartScene scene = scene(plan, view);
            assertFalse(markerDates(scene).contains(LocalDate.of(2026, 1, 10)), "showSkipped=" + view.showSkipped());
            assertTrue(markerDates(scene).contains(LocalDate.of(2026, 2, 10)));
        }
    }

    @Test
    void markersOffDrawNothing() {
        ChartScene scene = scene(plan(List.of(), List.of()), all().withChartMarkers(false));
        assertEquals(List.of(), ChartFixtures.ofType(scene, ChartPrimitive.Circle.class));
        assertEquals(List.of(), markerDates(scene));
        assertFalse(scene.legend().stream().anyMatch(item -> item.id().equals("income") || item.id().equals("notice")));
    }

    private static Plan dailyPlan(int days) {
        return ChartFixtures.plan(START, new Horizon.Months(24), 10_000,
                List.of(ChartFixtures.daily("r1", Kind.EXPENSE, START, START.plusDays(days - 1L))), List.of(), List.of());
    }

    @Test
    void exactly400MarkerDaysAreDrawn() {
        ChartScene scene = scene(dailyPlan(400), all());
        assertEquals(400, ChartFixtures.ofType(scene, ChartPrimitive.Circle.class).size());
        assertEquals(400, markerDates(scene).size());
        assertFalse(scene.legend().stream().anyMatch(item -> item.id().equals("notice")));
    }

    @Test
    void over400MarkerDaysDrawNoMarkersAndShowNotice() {
        ChartScene scene = scene(dailyPlan(401), all());
        assertEquals(List.of(), ChartFixtures.ofType(scene, ChartPrimitive.Circle.class));
        assertEquals(List.of(), markerDates(scene));
        List<LegendItem> notices = scene.legend().stream().filter(item -> item.id().equals("notice")).toList();
        assertEquals(1, notices.size());
        assertEquals("Маркеры: слишком много событий - уменьшите период", notices.getFirst().text());
        assertEquals(LegendItem.Swatch.NONE, notices.getFirst().swatch());
        assertFalse(scene.legend().stream().anyMatch(item -> List.of("income", "expense", "mixed").contains(item.id())));

        ChartScene shorter = scene(dailyPlan(401), all().withPeriod(PeriodChoice.M3));
        assertEquals(90, ChartFixtures.ofType(shorter, ChartPrimitive.Circle.class).size(), "меньший период - маркеры есть");
    }

    @Test
    void barsBandHeightIsMin110Or22Percent() {
        assertEquals(110, PlanChartModel.bandHeight(700, 640), 1e-9);
        assertEquals(44, PlanChartModel.bandHeight(200, 140), 1e-9);
        assertEquals(110, PlanChartModel.bandHeight(1000, 940), 1e-9);
        assertEquals(20, PlanChartModel.bandHeight(100, 20), 1e-9, "не больше области построения");
        assertEquals(0, PlanChartModel.bandHeight(Double.NaN, 1), 1e-9, "NaN не просачивается в координаты");

        Plan plan = plan(List.of(), List.of());
        for (double[] size : new double[][] {{W, H, 110}, {300, 200, 44}}) {
            ChartScene scene = ChartFixtures.model(ChartFixtures.state(plan, all().withChartBars(true), START))
                    .layout(size[0], size[1]);
            double bottom = scene.plot().plotY() + scene.plot().plotHeight();
            HitRegion bar = scene.hits().stream().filter(hit -> hit.kind() == HitRegion.Kind.BAR).findFirst().orElseThrow();
            assertEquals(size[2], bar.height(), 1e-9);
            assertEquals(bottom - size[2], bar.y(), 1e-9);
            ChartPrimitive.Line axis = ChartFixtures.ofType(scene, ChartPrimitive.Line.class).stream()
                    .filter(line -> line.stroke().color() == ColorToken.BORDER).findFirst().orElseThrow();
            assertEquals(bottom - size[2] / 2, axis.y1(), 1e-9, "ось полосы посередине");
        }
    }

    @Test
    void barsAreProportionalAndColouredBySign() {
        AppState state = ChartFixtures.state(plan(List.of(), List.of()), all().withChartBars(true), START);
        ChartScene scene = ChartFixtures.model(state).layout(W, H);
        PlotTransform plot = scene.plot();
        double axis = 668 - 55;
        List<ChartPrimitive.Box> boxes = ChartFixtures.ofType(scene, ChartPrimitive.Box.class);
        assertEquals(3, boxes.size());
        double januaryWidth = plot.xOf(LocalDate.of(2026, 2, 1)) - plot.xOf(START);
        assertEquals(round(new ChartPrimitive.Box(plot.xOf(START) + januaryWidth * 0.15, axis - 55, januaryWidth * 0.7,
                55, ColorToken.INCOME, 0.45)), round(boxes.get(0)), "январь: итог +1 000 - максимум, половина полосы");
        assertEquals(27.5, boxes.get(1).height(), 1e-9, "февраль: +500");
        assertEquals(axis - 27.5, boxes.get(1).y(), 1e-9);

        ChartPrimitive.Label axisLabel = ChartFixtures.ofType(scene, ChartPrimitive.Label.class).stream()
                .filter(label -> label.text().equals("итог мес.")).findFirst().orElseThrow();
        assertEquals(84, axisLabel.x(), 1e-9);

        List<HitRegion> barHits = scene.hits().stream().filter(hit -> hit.kind() == HitRegion.Kind.BAR).toList();
        assertEquals(List.of("bar@2026-01", "bar@2026-02", "bar@2026-03"), barHits.stream().map(HitRegion::id).toList());
        assertEquals("Январь 2026\nИтог: +1 000,00 ₽\nДоходы: 1 500,00 ₽\nРасходы: 500,00 ₽\nБаланс на конец: 11 000,00 ₽",
                barHits.getFirst().tooltip());
        assertEquals(START, barHits.getFirst().date());
        assertEquals(HitRegion.Kind.MARKER, scene.hits().getFirst().kind(), "маркеры раньше столбцов: они сверху");

        ChartScene negative = scene(plan(List.of(ChartFixtures.oneTime("t2", LocalDate.of(2026, 2, 15), "Ремонт",
                Kind.EXPENSE, 2_000)), List.of()), all().withChartBars(true));
        List<ChartPrimitive.Box> negativeBoxes = ChartFixtures.ofType(negative, ChartPrimitive.Box.class);
        assertEquals(ColorToken.EXPENSE, negativeBoxes.get(1).fill());
        assertEquals(axis, negativeBoxes.get(1).y(), 1e-9, "отрицательный итог вниз от оси");
        assertEquals(55, negativeBoxes.get(1).height(), 1e-9, "февраль −1 500 - максимум по модулю");
        assertEquals(55 * 1000.0 / 1500, negativeBoxes.get(0).height(), 1e-9);
    }

    private static ChartPrimitive.Box round(ChartPrimitive.Box box) {
        return new ChartPrimitive.Box(r(box.x()), r(box.y()), r(box.width()), r(box.height()), box.fill(), box.opacity());
    }

    private static double r(double value) {
        return Math.round(value * 1e6) / 1e6;
    }

    @Test
    void barsOffDrawNoBand() {
        ChartScene scene = scene(plan(List.of(), List.of()), all());
        assertEquals(List.of(), ChartFixtures.ofType(scene, ChartPrimitive.Box.class));
        assertFalse(ChartFixtures.ofType(scene, ChartPrimitive.Line.class).stream()
                .anyMatch(line -> line.stroke().color() == ColorToken.BORDER));
        assertFalse(scene.hits().stream().anyMatch(hit -> hit.kind() == HitRegion.Kind.BAR));
        assertFalse(ChartFixtures.ofType(scene, ChartPrimitive.Label.class).stream()
                .anyMatch(label -> label.text().equals("итог мес.")));
    }

    @Test
    void barsOfPartialMonthsAreClippedToRange() {
        AppState state = ChartFixtures.state(plan(List.of(), List.of()), all().withChartBars(true)
                .withPeriod(PeriodChoice.M3), LocalDate.of(2026, 2, 15));
        ChartScene scene = ChartFixtures.model(state).layout(W, H);
        PlotTransform plot = scene.plot();
        assertEquals(LocalDate.of(2026, 3, 31), plot.from().plusDays(plot.dayCount() - 1L));
        HitRegion march = scene.hits().stream().filter(hit -> hit.id().equals("bar@2026-03")).findFirst().orElseThrow();
        double marchWidth = plot.xOf(LocalDate.of(2026, 4, 1)) - plot.xOf(LocalDate.of(2026, 3, 1));
        assertEquals(marchWidth * 0.7, march.width(), 1e-9);
        assertEquals(1176, march.x() + march.width() + marchWidth * 0.15, 1e-6, "март заканчивается у правого края");
    }
}
