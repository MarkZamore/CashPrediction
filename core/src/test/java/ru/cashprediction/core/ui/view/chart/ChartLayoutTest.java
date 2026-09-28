package ru.cashprediction.core.ui.view.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.SamplePlan;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.view.popup.PopupBuilders;

/**
 * Раскладка сцены графика (спецификация v2, §5.3): порядок примитивов, поля 80/24/28/32 при 1200×700 и 300×200,
 * сетка и подписи осей, шаг подписей месяцев при 12, 24, 48 и 120 месяцах, ступенчатая линия, линии нуля, подушки,
 * цели и сегодня, пустые сцены, наведение и карточка дня.
 */
class ChartLayoutTest {

    private static final double W = 1200;
    private static final double H = 700;

    private static AppState sample(ViewState view) {
        return ChartFixtures.state(SamplePlan.create(ChartFixtures.TODAY), view, ChartFixtures.TODAY);
    }

    private static Forecast forecast(AppState state) {
        return state.document().forecast();
    }

    /** Группа примитива в порядке рисования §5.3. */
    private static int rank(ChartPrimitive primitive) {
        return switch (primitive) {
            case ChartPrimitive.Line line when line.stroke().color() == ColorToken.GRID -> 0;
            case ChartPrimitive.Label label when label.text().equals("итог мес.") -> 1;
            case ChartPrimitive.Label label when label.color() == ColorToken.TEXT_MUTED -> 0;
            case ChartPrimitive.Line line when line.stroke().color() == ColorToken.BORDER -> 1;
            case ChartPrimitive.Box _ -> 1;
            case ChartPrimitive.Area _ -> 2;
            case ChartPrimitive.Polyline _ -> 3;
            case ChartPrimitive.Line line when line.stroke().color() == ColorToken.LINE_ZERO -> 4;
            case ChartPrimitive.Label label when label.color() == ColorToken.LINE_ZERO -> 4;
            case ChartPrimitive.Line line when line.stroke().color() == ColorToken.LINE_CUSHION -> 5;
            case ChartPrimitive.Label label when label.color() == ColorToken.LINE_CUSHION -> 5;
            case ChartPrimitive.Line line when line.stroke().color() == ColorToken.LINE_GOAL -> 6;
            case ChartPrimitive.Label label when label.color() == ColorToken.LINE_GOAL -> 6;
            case ChartPrimitive.Line line when line.stroke().color() == ColorToken.LINE_TODAY -> 7;
            case ChartPrimitive.Label label when label.color() == ColorToken.LINE_TODAY -> 7;
            case ChartPrimitive.Circle _ -> 8;
            default -> throw new AssertionError("неожиданный примитив " + primitive);
        };
    }

    private static void assertDrawingOrder(ChartScene scene) {
        List<Integer> ranks = scene.primitives().stream().map(ChartLayoutTest::rank).toList();
        List<Integer> sorted = new ArrayList<>(ranks);
        sorted.sort(Integer::compare);
        assertEquals(sorted, ranks, "порядок рисования §5.3");
        assertEquals(new TreeSet<>(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8)), new TreeSet<>(ranks), "все группы на месте");
    }

    @Test
    void primitivesFollowSpecDrawingOrderAt1200x700() {
        ChartScene scene = ChartFixtures.model(sample(ViewState.defaults().withChartBars(true))).layout(W, H);
        assertDrawingOrder(scene);
        assertEquals("", scene.emptyText());
        assertEquals(W, scene.width());
        assertEquals(H, scene.height());
    }

    @Test
    void primitivesFollowSpecDrawingOrderAt300x200() {
        ChartScene scene = ChartFixtures.model(sample(ViewState.defaults().withChartBars(true))).layout(300, 200);
        assertDrawingOrder(scene);
    }

    @Test
    void plotAreaHasSpecMarginsAndRange() {
        AppState state = sample(ViewState.defaults());
        Plan plan = state.document().plan();
        ChartModel model = ChartFixtures.model(state);
        ChartScene big = model.layout(W, H);
        PlotTransform plot = big.plot();
        assertEquals(List.of(80.0, 28.0, 1096.0, 640.0),
                List.of(plot.plotX(), plot.plotY(), plot.plotWidth(), plot.plotHeight()));
        assertEquals(LocalDate.of(2026, 9, 1), plot.from());
        // Период M12 от 13.09.2026 шире горизонта плана: диапазон заканчивается концом плана.
        assertEquals(plan.endDate(), plot.from().plusDays(plot.dayCount() - 1L));

        PlotTransform small = model.layout(300, 200).plot();
        assertEquals(List.of(80.0, 28.0, 196.0, 140.0),
                List.of(small.plotX(), small.plotY(), small.plotWidth(), small.plotHeight()));
        assertEquals(plot.minMinor(), small.minMinor());
        assertEquals(plot.maxMinor(), small.maxMinor());
    }

    @Test
    void periodShorterThanHorizonEndsAtPeriodEnd() {
        AppState state = sample(ViewState.defaults().withPeriod(PeriodChoice.M3));
        PlotTransform plot = ChartFixtures.model(state).layout(W, H).plot();
        assertEquals(LocalDate.of(2026, 9, 1), plot.from(), "диапазон от начала прогноза");
        assertEquals(LocalDate.of(2026, 12, 12), plot.from().plusDays(plot.dayCount() - 1L), "до view.periodEnd");
    }

    @Test
    void yGridAndLabelsMatchTicks() {
        AppState state = sample(ViewState.defaults());
        ChartScene scene = ChartFixtures.model(state).layout(W, H);
        PlotTransform plot = scene.plot();
        Forecast forecast = forecast(state);
        long max = 0;
        for (int i = 0; i < forecast.dayCount(); i++) {
            max = Math.max(max, forecast.balanceMinorAt(i));
        }
        ChartScale.Ticks ticks = ChartScale.yTicks(0, Math.max(max, 30_000_000));
        assertEquals(ticks.minMinor(), plot.minMinor());
        assertEquals(ticks.maxMinor(), plot.maxMinor());

        List<ChartPrimitive.Line> horizontal = ChartFixtures.ofType(scene, ChartPrimitive.Line.class).stream()
                .filter(line -> line.stroke().color() == ColorToken.GRID && line.y1() == line.y2()).toList();
        assertEquals(ticks.values().size(), horizontal.size());
        assertEquals(668, horizontal.getFirst().y1(), 1e-9, "нижнее деление на нижнем краю области");
        assertEquals(28, horizontal.getLast().y1(), 1e-9, "верхнее деление на верхнем краю области");
        for (ChartPrimitive.Line line : horizontal) {
            assertEquals(80, line.x1(), 1e-9);
            assertEquals(1176, line.x2(), 1e-9);
            assertEquals(1, line.stroke().width());
        }
        List<ChartPrimitive.Label> yLabels = ChartFixtures.ofType(scene, ChartPrimitive.Label.class).stream()
                .filter(label -> label.anchor() == TextAnchor.END && label.color() == ColorToken.TEXT_MUTED).toList();
        assertEquals(ticks.labels(), yLabels.stream().map(ChartPrimitive.Label::text).toList());
        for (int i = 0; i < yLabels.size(); i++) {
            assertEquals(74, yLabels.get(i).x(), 1e-9, "справа налево до оси");
            assertEquals(plot.yOf(ticks.values().get(i)) + 4, yLabels.get(i).y(), 1e-9);
        }
    }

    @Test
    void xGridOnFirstDayOfEveryMonth() {
        ChartScene scene = ChartFixtures.model(sample(ViewState.defaults())).layout(W, H);
        PlotTransform plot = scene.plot();
        List<Double> vertical = ChartFixtures.ofType(scene, ChartPrimitive.Line.class).stream()
                .filter(line -> line.stroke().color() == ColorToken.GRID && line.x1() == line.x2())
                .map(ChartPrimitive.Line::x1).toList();
        List<Double> expected = new ArrayList<>();
        for (LocalDate month = LocalDate.of(2026, 9, 1); !month.isAfter(LocalDate.of(2027, 8, 31)); month = month.plusMonths(1)) {
            expected.add(plot.xOf(month));
        }
        assertEquals(expected, vertical);
    }

    private static List<ChartPrimitive.Label> xLabels(ChartScene scene) {
        double baseline = scene.plot().plotY() + scene.plot().plotHeight() + 18;
        return ChartFixtures.ofType(scene, ChartPrimitive.Label.class).stream()
                .filter(label -> label.y() == baseline).toList();
    }

    private static ChartScene monthsPlan(int months) {
        LocalDate start = LocalDate.of(2026, 1, 1);
        Plan plan = ChartFixtures.plan(start, new Horizon.Months(months), 1_000,
                List.of(ChartFixtures.monthly("r1", "Зарплата", Kind.INCOME, 100, 5)), List.of(), List.of());
        return ChartFixtures.model(ChartFixtures.state(plan, ViewState.defaults().withPeriod(PeriodChoice.ALL), start))
                .layout(W, H);
    }

    @Test
    void xLabelCadenceAt12Months() {
        ChartScene scene = monthsPlan(12);
        List<ChartPrimitive.Label> labels = xLabels(scene);
        assertEquals(List.of("янв 2026", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек"),
                labels.stream().map(ChartPrimitive.Label::text).toList());
        for (int i = 0; i < labels.size(); i++) {
            assertEquals(scene.plot().xOf(LocalDate.of(2026, 1 + i, 1)) + 4, labels.get(i).x(), 1e-9);
            assertEquals(TextAnchor.START, labels.get(i).anchor());
            assertEquals(ColorToken.TEXT_MUTED, labels.get(i).color());
        }
    }

    @Test
    void xLabelCadenceAt24Months() {
        assertEquals(List.of("янв 2026", "мар", "май", "июл", "сен", "ноя", "янв 2027", "мар", "май", "июл", "сен", "ноя"),
                xLabels(monthsPlan(24)).stream().map(ChartPrimitive.Label::text).toList());
    }

    @Test
    void xLabelCadenceAt48Months() {
        assertEquals(List.of("янв 2026", "июл", "янв 2027", "июл", "янв 2028", "июл", "янв 2029", "июл"),
                xLabels(monthsPlan(48)).stream().map(ChartPrimitive.Label::text).toList());
    }

    @Test
    void xLabelCadenceAt120Months() {
        List<String> labels = xLabels(monthsPlan(120)).stream().map(ChartPrimitive.Label::text).toList();
        assertEquals(10, labels.size());
        assertEquals("янв 2026", labels.getFirst());
        assertEquals("янв 2035", labels.getLast());
    }

    @Test
    void balanceIsStepLineHorizontalThenVertical() {
        AppState state = sample(ViewState.defaults());
        ChartScene scene = ChartFixtures.model(state).layout(W, H);
        PlotTransform plot = scene.plot();
        Forecast forecast = forecast(state);
        ChartPrimitive.Polyline line = ChartFixtures.ofType(scene, ChartPrimitive.Polyline.class).getFirst();
        assertEquals(ColorToken.ACCENT, line.stroke().color());
        assertEquals(2, line.stroke().width());
        assertEquals(List.of(), line.stroke().dash());
        List<ChartPoint> points = line.points();
        assertEquals(80, points.getFirst().x(), 1e-9);
        assertEquals(plot.yOf(forecast.balanceAt(plot.from()).minor()), points.getFirst().y(), 1e-9);
        assertEquals(1176, points.getLast().x(), 1e-9, "последняя горизонталь до правого края");
        double dayWidth = ChartFixtures.dayWidth(scene);
        for (int k = 0; k + 1 < points.size(); k++) {
            ChartPoint a = points.get(k);
            ChartPoint b = points.get(k + 1);
            if (k % 2 == 0) {
                assertEquals(a.y(), b.y(), 1e-9, "сначала горизонталь, вершина " + k);
                assertTrue(b.x() >= a.x());
            } else {
                assertEquals(a.x(), b.x(), 1e-9, "затем вертикаль, вершина " + k);
                LocalDate day = plot.dateAt(b.x() + dayWidth / 2);
                assertEquals(plot.yOf(forecast.balanceAt(day).minor()), b.y(), 1e-6, "вертикаль к балансу дня " + day);
            }
        }
        ChartPrimitive.Area fill = ChartFixtures.ofType(scene, ChartPrimitive.Area.class).getFirst();
        assertEquals(points, fill.points());
        assertEquals(plot.yOf(0), fill.baselineY(), 1e-9);
        assertEquals(ColorToken.ACCENT, fill.fill());
        assertEquals(0.10, fill.opacity(), 1e-12);
    }

    @Test
    void longPlanIsSampledTo1500Points() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        Plan plan = ChartFixtures.plan(start, new Horizon.Years(50), 1_000, List.of(
                ChartFixtures.monthly("r1", "Зарплата", Kind.INCOME, 50_000, 5),
                new RecurringRule(new RuleId("r2"), "Продукты", Kind.EXPENSE, Money.ofMajor(4_000), "",
                        new Recurrence.Weekly(java.time.DayOfWeek.SATURDAY, 1), null, null, WeekendPolicy.NONE, true, "")),
                List.of(), List.of());
        AppState state = ChartFixtures.state(plan, ViewState.defaults().withPeriod(PeriodChoice.ALL), start);
        ChartScene scene = ChartFixtures.model(state).layout(W, H);
        assertTrue(scene.plot().dayCount() > 18_000);
        int vertices = ChartFixtures.ofType(scene, ChartPrimitive.Polyline.class).getFirst().points().size();
        assertTrue(vertices <= 2 * 1500 + 1, "вершин " + vertices);
        assertTrue(scene.primitives().stream().allMatch(ChartLayoutTest::finite), "все координаты конечны");
    }

    private static boolean finite(ChartPrimitive primitive) {
        return switch (primitive) {
            case ChartPrimitive.Line l -> Double.isFinite(l.x1() + l.y1() + l.x2() + l.y2());
            case ChartPrimitive.Box b -> Double.isFinite(b.x() + b.y() + b.width() + b.height());
            case ChartPrimitive.Circle c -> Double.isFinite(c.cx() + c.cy());
            case ChartPrimitive.Label l -> Double.isFinite(l.x() + l.y());
            case ChartPrimitive.Polyline p -> p.points().stream().allMatch(pt -> Double.isFinite(pt.x() + pt.y()));
            case ChartPrimitive.Area a -> a.points().stream().allMatch(pt -> Double.isFinite(pt.x() + pt.y()));
        };
    }

    @Test
    void zeroCushionGoalAndTodayLinesWithLabels() {
        AppState state = sample(ViewState.defaults());
        ChartScene scene = ChartFixtures.model(state).layout(W, H);
        PlotTransform plot = scene.plot();
        List<ChartPrimitive.Line> lines = ChartFixtures.ofType(scene, ChartPrimitive.Line.class);
        List<ChartPrimitive.Label> labels = ChartFixtures.ofType(scene, ChartPrimitive.Label.class);

        ChartPrimitive.Line zero = single(lines, ColorToken.LINE_ZERO);
        assertEquals(List.of(80.0, plot.yOf(0), 1176.0, plot.yOf(0)), List.of(zero.x1(), zero.y1(), zero.x2(), zero.y2()));
        assertEquals(Stroke.solid(ColorToken.LINE_ZERO, 1), zero.stroke());
        ChartPrimitive.Label zeroLabel = singleLabel(labels, ColorToken.LINE_ZERO);
        assertEquals(new ChartPrimitive.Label(1172, plot.yOf(0) - 4, "0", TextAnchor.END, ColorToken.LINE_ZERO, null),
                zeroLabel, "«0» справа над линией");

        ChartPrimitive.Line cushion = single(lines, ColorToken.LINE_CUSHION);
        assertEquals(plot.yOf(5_000_000), cushion.y1(), 1e-9);
        assertEquals(new Stroke(ColorToken.LINE_CUSHION, 1.5, List.of(6.0, 4.0), 1), cushion.stroke());
        assertEquals("подушка 50 000", singleLabel(labels, ColorToken.LINE_CUSHION).text());

        ChartPrimitive.Line goal = single(lines, ColorToken.LINE_GOAL);
        assertEquals(plot.yOf(30_000_000), goal.y1(), 1e-9);
        assertEquals(new Stroke(ColorToken.LINE_GOAL, 1.5, List.of(8.0, 4.0), 1), goal.stroke());
        assertEquals("цель «Отпуск» 300 000", singleLabel(labels, ColorToken.LINE_GOAL).text());

        ChartPrimitive.Line today = single(lines, ColorToken.LINE_TODAY);
        double todayX = plot.xOf(ChartFixtures.TODAY) + ChartFixtures.dayWidth(scene) / 2;
        assertEquals(List.of(todayX, 28.0, todayX, 668.0), List.of(today.x1(), today.y1(), today.x2(), today.y2()));
        assertEquals(new Stroke(ColorToken.LINE_TODAY, 1.5, List.of(4.0, 4.0), 1), today.stroke());
        assertEquals(ChartFixtures.TODAY, plot.dateAt(todayX));
        ChartPrimitive.Label todayLabel = singleLabel(labels, ColorToken.LINE_TODAY);
        assertEquals("сегодня", todayLabel.text());
        assertTrue(todayLabel.y() > 28 && todayLabel.y() < 668, "подпись сверху внутри области");
    }

    private static ChartPrimitive.Line single(List<ChartPrimitive.Line> lines, ColorToken color) {
        List<ChartPrimitive.Line> found = lines.stream().filter(line -> line.stroke().color() == color).toList();
        assertEquals(1, found.size(), color.id());
        return found.getFirst();
    }

    private static ChartPrimitive.Label singleLabel(List<ChartPrimitive.Label> labels, ColorToken color) {
        List<ChartPrimitive.Label> found = labels.stream().filter(label -> label.color() == color).toList();
        assertEquals(1, found.size(), color.id());
        return found.getFirst();
    }

    @Test
    void noCushionNoGoalAndTodayOutsideRangeDrawNoSuchLines() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        Plan plan = ChartFixtures.plan(start, new Horizon.Months(3), 1_000,
                List.of(ChartFixtures.monthly("r1", "Зарплата", Kind.INCOME, 100, 5)), List.of(), List.of());
        ChartScene scene = ChartFixtures.model(ChartFixtures.state(plan, ViewState.defaults(), ChartFixtures.TODAY))
                .layout(W, H);
        List<ColorToken> colors = ChartFixtures.ofType(scene, ChartPrimitive.Line.class).stream()
                .map(line -> line.stroke().color()).toList();
        assertTrue(colors.contains(ColorToken.LINE_ZERO));
        assertFalse(colors.contains(ColorToken.LINE_CUSHION));
        assertFalse(colors.contains(ColorToken.LINE_GOAL));
        assertFalse(colors.contains(ColorToken.LINE_TODAY), "сегодня (13.09.2026) после конца плана");
        assertEquals(LocalDate.of(2026, 3, 31), scene.plot().from().plusDays(scene.plot().dayCount() - 1L));
    }

    @Test
    void failedForecastGivesEmptySceneWithErrorText() {
        AppState state = ChartFixtures.failed(SamplePlan.create(ChartFixtures.TODAY), "boom");
        ChartModel model = ChartFixtures.model(state);
        ChartScene scene = model.layout(W, H);
        assertEquals("Прогноз не рассчитан: boom", scene.emptyText());
        assertEquals(ColorToken.EXPENSE, scene.emptyColor());
        assertEquals(List.of(), scene.primitives());
        assertEquals(List.of(), scene.legend());
        assertEquals(List.of(), scene.hits());
        assertEquals(List.of(80.0, 28.0, 1096.0, 640.0), List.of(scene.plot().plotX(), scene.plot().plotY(),
                scene.plot().plotWidth(), scene.plot().plotHeight()));
        assertEquals(Optional.empty(), model.hover(600, 300, W, H));
    }

    @Test
    void singleDayGivesNoDataText() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        Plan plan = ChartFixtures.plan(start, new Horizon.Until(start), 1_000, List.of(), List.of(), List.of());
        ChartModel model = ChartFixtures.model(ChartFixtures.state(plan, ViewState.defaults(), start));
        ChartScene scene = model.layout(W, H);
        assertEquals("Недостаточно данных для графика", scene.emptyText());
        assertEquals(ColorToken.TEXT_MUTED, scene.emptyColor());
        assertEquals(List.of(), scene.primitives());
        assertEquals(Optional.empty(), model.hover(600, 300, W, H));
    }

    @Test
    void hoverInsidePlotSnapsToDayAndPlacesCard() {
        AppState state = sample(ViewState.defaults());
        ChartModel model = ChartFixtures.model(state);
        ChartScene scene = model.layout(W, H);
        PlotTransform plot = scene.plot();
        LocalDate day = LocalDate.of(2026, 10, 5);
        double dayWidth = ChartFixtures.dayWidth(scene);
        double x = plot.xOf(day) + dayWidth * 0.8;
        ChartHover hover = model.hover(x, 300, W, H).orElseThrow();
        assertEquals(day, hover.date());
        assertEquals(plot.xOf(day) + dayWidth / 2, hover.lineX(), 1e-9);
        assertEquals(new ChartPoint(hover.lineX(), plot.yOf(forecast(state).balanceAt(day).minor())), hover.dot());
        assertEquals("card 2026-10-05", hover.card().header());
        assertEquals(x + 16, hover.cardX(), 1e-9);
        assertEquals(316, hover.cardY(), 1e-9);
    }

    @Test
    void hoverOutsidePlotIsHidden() {
        ChartModel model = ChartFixtures.model(sample(ViewState.defaults()));
        assertEquals(Optional.empty(), model.hover(79, 300, W, H));
        assertEquals(Optional.empty(), model.hover(1177, 300, W, H));
        assertEquals(Optional.empty(), model.hover(600, 27, W, H), "легенда");
        assertEquals(Optional.empty(), model.hover(600, 669, W, H), "подписи оси X");
        assertEquals(Optional.empty(), model.hover(Double.NaN, 300, W, H));
        assertEquals(LocalDate.of(2026, 9, 1), model.hover(80, 28, W, H).orElseThrow().date(), "левый край - первый день");
        assertEquals(LocalDate.of(2027, 8, 31), model.hover(1176, 668, W, H).orElseThrow().date(), "правый край - последний");
    }

    @Test
    void dayCardComesFromBuilder() {
        AppState state = sample(ViewState.defaults());
        assertEquals("card 2026-10-05", ChartFixtures.model(state).dayCard(LocalDate.of(2026, 10, 5)).header());
    }

    @Test
    void publicModelUsesSharedDayCardBuilder() {
        // Сравниваются исходы: карточку строит PopupBuilders (задача core-views), у графика своей копии нет.
        AppState state = sample(ViewState.defaults());
        LocalDate day = LocalDate.of(2026, 10, 5);
        assertEquals(outcome(() -> PopupBuilders.dayCard(state, day)),
                outcome(() -> ChartLayout.model(state, 1).dayCard(day)));
    }

    private static Object outcome(Supplier<Object> action) {
        try {
            return action.get();
        } catch (RuntimeException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    @Test
    void layoutIsDeterministicAndKeepsRevision() {
        AppState state = sample(ViewState.defaults().withChartBars(true));
        ChartModel first = ChartFixtures.model(state);
        ChartModel second = ChartFixtures.model(state);
        assertEquals(7, first.revision());
        assertEquals(first.layout(W, H), second.layout(W, H));
        assertEquals(first.layout(W, H), first.layout(W, H));
        assertEquals(first.hover(500, 300, W, H), second.hover(500, 300, W, H));
    }

    @Test
    void degenerateSizesDoNotThrow() {
        ChartModel model = ChartFixtures.model(sample(ViewState.defaults().withChartBars(true)));
        for (double[] size : new double[][] {{0, 0}, {50, 40}, {-10, Double.NaN}, {Double.POSITIVE_INFINITY, 700}}) {
            ChartScene scene = model.layout(size[0], size[1]);
            assertTrue(scene.plot().plotWidth() >= 1, "ширина области не меньше 1");
            assertTrue(scene.plot().plotHeight() >= 1, "высота области не меньше 1");
            assertTrue(scene.primitives().stream().allMatch(ChartLayoutTest::finite), "координаты конечны");
        }
    }
}
