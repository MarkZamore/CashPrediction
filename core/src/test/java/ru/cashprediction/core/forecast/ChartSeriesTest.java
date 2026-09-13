package ru.cashprediction.core.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.cashprediction.core.forecast.TestPlans.START;
import static ru.cashprediction.core.forecast.TestPlans.d;
import static ru.cashprediction.core.forecast.TestPlans.monthly;
import static ru.cashprediction.core.forecast.TestPlans.oneTime;
import static ru.cashprediction.core.forecast.TestPlans.plan;
import static ru.cashprediction.core.forecast.TestPlans.rule;
import static ru.cashprediction.core.forecast.TestPlans.run;

import java.time.DayOfWeek;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.WeekendPolicy;

/**
 * Тесты прореживания серии для графика.
 */
class ChartSeriesTest {

    /** Длинная «пила» с одним глубоким провалом и одним высоким пиком посреди 50 лет. */
    private static final Forecast LONG = run(plan(100_000, new Horizon.Years(50),
            List.of(monthly("r1", "Зарплата", Kind.INCOME, 100_000, 1),
                    rule("r2", "Продукты", Kind.EXPENSE, Money.ofMajor(24_000), new Recurrence.Weekly(DayOfWeek.FRIDAY, 1),
                            null, null, WeekendPolicy.NONE)),
            List.of(oneTime("t1", "2041-03-17", "Провал", Kind.EXPENSE, 50_000_000),
                    oneTime("t2", "2041-03-18", "Возврат", Kind.INCOME, 50_000_000),
                    oneTime("t3", "2058-07-07", "Пик", Kind.INCOME, 90_000_000),
                    oneTime("t4", "2058-07-08", "Трата пика", Kind.EXPENSE, 90_000_000)),
            List.of()));

    /** Короткий интервал возвращается целиком. */
    @Test
    void shortRangeReturnsEveryDay() {
        Forecast f = run(plan(0, new Horizon.Months(1), List.of(monthly("r1", "Доход", Kind.INCOME, 10, 5)), List.of(), List.of()));
        List<DailyPoint> points = ChartSeries.sample(f, START, f.endDate(), ChartSeries.DEFAULT_MAX_POINTS);
        assertEquals(30, points.size());
        for (int i = 0; i < points.size(); i++) {
            assertEquals(START.plusDays(i), points.get(i).date());
            assertEquals(f.balanceAt(points.get(i).date()), points.get(i).balance());
        }
    }

    /** Размер, порядок, первая и последняя точка, глобальные минимум и максимум. */
    @ParameterizedTest(name = "maxPoints = {0}")
    @ValueSource(ints = {1500, 101, 100, 5, 4})
    void decimationKeepsInvariants(int maxPoints) {
        List<DailyPoint> points = ChartSeries.sample(LONG, LONG.startDate(), LONG.endDate(), maxPoints);
        assertTrue(points.size() <= maxPoints, "точек: " + points.size());
        assertEquals(LONG.startDate(), points.get(0).date());
        assertEquals(LONG.endDate(), points.get(points.size() - 1).date());
        for (int i = 1; i < points.size(); i++) {
            assertTrue(points.get(i).date().isAfter(points.get(i - 1).date()), "хронологический порядок");
        }
        long[] daily = LONG.dailyBalance();
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (long v : daily) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        long globalMin = min;
        long globalMax = max;
        assertTrue(points.stream().anyMatch(p -> p.balance().minor() == globalMin), "минимум сохранён");
        assertTrue(points.stream().anyMatch(p -> p.balance().minor() == globalMax), "максимум сохранён");
        for (DailyPoint p : points) {
            assertEquals(LONG.balanceAt(p.date()), p.balance());
        }
    }

    /** Интервал прижимается к прогнозу; два значения точек — только края. */
    @Test
    void clampingAndMinimalMaxPoints() {
        List<DailyPoint> clamped = ChartSeries.sample(LONG, d("2000-01-01"), d("2100-01-01"), 300);
        assertEquals(LONG.startDate(), clamped.get(0).date());
        assertEquals(LONG.endDate(), clamped.get(clamped.size() - 1).date());
        assertTrue(ChartSeries.sample(LONG, d("2100-01-01"), d("2101-01-01"), 300).isEmpty());
        List<DailyPoint> two = ChartSeries.sample(LONG, LONG.startDate(), LONG.endDate(), 2);
        assertEquals(List.of(LONG.startDate(), LONG.endDate()), two.stream().map(DailyPoint::date).toList());
        assertThrows(IllegalArgumentException.class, () -> ChartSeries.sample(LONG, LONG.startDate(), LONG.endDate(), 1));
    }

    /** Подынтервал: края подынтервала, а не прогноза. */
    @Test
    void subRange() {
        List<DailyPoint> points = ChartSeries.sample(LONG, d("2041-01-01"), d("2041-12-31"), 50);
        assertTrue(points.size() <= 50);
        assertEquals(d("2041-01-01"), points.get(0).date());
        assertEquals(d("2041-12-31"), points.get(points.size() - 1).date());
        assertTrue(points.stream().anyMatch(p -> p.date().equals(d("2041-03-17"))), "провал 17.03.2041 виден на графике года");
    }
}
