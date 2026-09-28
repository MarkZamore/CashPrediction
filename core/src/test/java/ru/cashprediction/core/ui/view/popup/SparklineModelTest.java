package ru.cashprediction.core.ui.view.popup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.cashprediction.core.ui.view.table.ViewStates.TODAY;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.SamplePlan;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.view.chart.ChartPoint;
import ru.cashprediction.core.ui.view.summary.CardModel;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;
import ru.cashprediction.core.ui.view.table.ViewStates;

/**
 * Всплывающее окно карточки сводки (спецификация v2, §5.1): тексты как у карточки, диапазон по карточке, точки в
 * 0..1, линия нуля при пересечении, точка на дате карточки, «мин.»/«макс.», меньше двух точек - «нет данных».
 */
class SparklineModelTest {

    private static final double EPS = 1e-9;

    @Test
    void monthCardRangeAndNormalization() {
        AppState state = ViewStates.sample();
        SparklineModel model = PopupBuilders.sparkline(state, "m1");
        CardModel card = SummaryBuilder.card(state, "m1").orElseThrow();
        assertEquals("m1", model.cardId());
        assertEquals("Через 1 месяц: 223 654,33 ₽", model.header());
        assertEquals(card.explanation(), model.explanation());

        // 13.09.2026 … 13.10.2026 - 31 день, по точке на день.
        assertEquals(31, model.points().size());
        assertEquals(0.0, model.points().getFirst().x(), EPS);
        assertEquals(1.0, model.points().getLast().x(), EPS);
        for (ChartPoint point : model.points()) {
            assertTrue(point.x() >= 0 && point.x() <= 1 && point.y() >= 0 && point.y() <= 1, point.toString());
        }
        // Максимум 227 654,33 (05-09.10) - y = 0, минимум 147 654,33 (03.10) - y = 1; сегодня 177 000.
        assertEquals((227_654.33 - 177_000) / 80_000, model.points().getFirst().y(), 1e-6);
        assertEquals("мин. 147 654,33 ₽", model.minText());
        assertEquals("макс. 227 654,33 ₽", model.maxText());
        assertNull(model.zeroY(), "баланс не пересекает ноль");
        assertEquals(new ChartPoint(1.0, 0.05), round(model.marker()));
        assertEquals("", model.noDataText());
    }

    @Test
    void beyondHorizonCardRunsToPlanEndWithoutMarker() {
        SparklineModel model = PopupBuilders.sparkline(ViewStates.sample(), "m12");
        assertEquals("Через 12 месяцев: за горизонтом", model.header());
        assertTrue(model.points().size() >= 2 && model.points().size() <= PopupBuilders.SPARKLINE_MAX_POINTS,
                "точек: " + model.points().size());
        assertEquals(1.0, model.points().getLast().x(), EPS);
        assertNull(model.marker());
    }

    @Test
    void zeroLineWhenBalanceCrossesZero() {
        Plan plan = ViewStates.plan(TODAY, 10_000, new Horizon.Months(12), 0,
                List.of(ViewStates.monthly("r1", "Кредит", Kind.EXPENSE, 20_000, 15)), List.of());
        SparklineModel model = PopupBuilders.sparkline(ViewStates.of(plan, ViewState.defaults()), "now");
        // «Сейчас»: 13.09 … 13.12.2026, баланс от 10 000 до -50 000.
        assertNotNull(model.zeroY());
        assertEquals(10_000.0 / 60_000, model.zeroY(), 1e-9);
        assertEquals(new ChartPoint(0.0, 0.0), round(model.marker()));
        assertEquals("мин. -50 000,00 ₽", model.minText());
        assertEquals("макс. 10 000,00 ₽", model.maxText());
    }

    @Test
    void fewerThanTwoPointsGiveNoData() {
        Plan oneDay = Plan.empty("Один день", TODAY).withHorizon(new Horizon.Until(TODAY));
        SparklineModel model = PopupBuilders.sparkline(ViewStates.of(oneDay, ViewState.defaults()), "now");
        assertEquals("нет данных", model.noDataText());
        assertTrue(model.points().isEmpty());
        assertEquals("Сейчас: 0,00 ₽", model.header());
        assertEquals("", model.minText());
        assertEquals("", model.maxText());

        SparklineModel failed = PopupBuilders.sparkline(ViewStates.failed(SamplePlan.create(TODAY), "ошибка",
                ViewState.defaults()), "goal");
        assertEquals(new SparklineModel("goal", "", "", List.of(), null, null, "", "", "нет данных"), failed);
    }

    @Test
    void unknownCardIsDeveloperError() {
        assertThrows(IllegalArgumentException.class, () -> PopupBuilders.sparkline(ViewStates.sample(), "nope"));
    }

    /** @return точка с координатами, округлёнными до 6 знаков */
    private static ChartPoint round(ChartPoint point) {
        assertNotNull(point);
        return new ChartPoint(Math.round(point.x() * 1e6) / 1e6, Math.round(point.y() * 1e6) / 1e6);
    }
}
