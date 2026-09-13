package ru.cashprediction.core.forecast;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.model.Money;

/**
 * Прореживание ежедневной серии баланса для графика, единое для JavaFX, Swing и Web.
 *
 * <p>Зачем: 50 лет — это 18 000+ дней, а {@code LineChart} JavaFX и SVG в браузере заметно тормозят
 * уже на нескольких тысячах узлов. Простое «каждый N-й день» теряет пики и провалы — именно то,
 * ради чего смотрят график (первый минус, минимум). Поэтому используется min/max-децимация:
 * внутренние дни делятся на корзины, и из каждой берутся день с минимальным и день с максимальным балансом.</p>
 *
 * <p>Гарантии: не больше {@code maxPoints} точек; хронологический порядок; первый и последний день интервала
 * всегда присутствуют; при {@code maxPoints >= 4} сохраняются глобальный минимум и максимум интервала.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class ChartSeries {

    /** Рекомендуемое число точек графика. */
    public static final int DEFAULT_MAX_POINTS = 1500;

    private ChartSeries() {
    }

    /**
     * Точки графика для интервала дат.
     *
     * <p>Если дней в интервале не больше {@code maxPoints}, возвращается каждый день. Иначе первый и последний день
     * берутся как есть, а дни между ними делятся на {@code (maxPoints - 2) / 2} корзин, из каждой — день минимума
     * и день максимума в хронологическом порядке (один раз, если это один и тот же день).</p>
     *
     * @param forecast  прогноз
     * @param from      первый день (прижимается к началу прогноза)
     * @param to        последний день (прижимается к концу прогноза)
     * @param maxPoints максимальное число точек, не меньше 2
     * @return неизменяемый список точек; пустой, если интервал не пересекается с прогнозом
     * @throws IllegalArgumentException если {@code maxPoints < 2}
     */
    public static List<DailyPoint> sample(Forecast forecast, LocalDate from, LocalDate to, int maxPoints) {
        Objects.requireNonNull(forecast, "forecast");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (maxPoints < 2) {
            throw new IllegalArgumentException("Число точек графика должно быть не меньше 2");
        }
        LocalDate start = forecast.startDate();
        LocalDate lo = from.isBefore(start) ? start : from;
        LocalDate hi = to.isAfter(forecast.endDate()) ? forecast.endDate() : to;
        if (lo.isAfter(hi)) {
            return List.of();
        }
        int first = (int) ChronoUnit.DAYS.between(start, lo);
        int last = (int) ChronoUnit.DAYS.between(start, hi);
        int count = last - first + 1;

        List<DailyPoint> points = new ArrayList<>(Math.min(count, maxPoints));
        if (count <= maxPoints) {
            for (int i = first; i <= last; i++) {
                points.add(point(forecast, i));
            }
            return List.copyOf(points);
        }

        points.add(point(forecast, first));
        // Первый и последний день занимают две точки, остальное — по две точки на корзину.
        int buckets = (maxPoints - 2) / 2;
        long interior = count - 2L;
        for (int b = 0; b < buckets; b++) {
            int bucketStart = first + 1 + (int) (b * interior / buckets);
            int bucketEnd = first + 1 + (int) ((b + 1) * interior / buckets);
            int minIndex = bucketStart;
            int maxIndex = bucketStart;
            for (int i = bucketStart + 1; i < bucketEnd; i++) {
                long value = forecast.balanceMinorAt(i);
                if (value < forecast.balanceMinorAt(minIndex)) {
                    minIndex = i;
                }
                if (value > forecast.balanceMinorAt(maxIndex)) {
                    maxIndex = i;
                }
            }
            points.add(point(forecast, Math.min(minIndex, maxIndex)));
            if (minIndex != maxIndex) {
                points.add(point(forecast, Math.max(minIndex, maxIndex)));
            }
        }
        points.add(point(forecast, last));
        return List.copyOf(points);
    }

    private static DailyPoint point(Forecast forecast, int index) {
        return new DailyPoint(forecast.startDate().plusDays(index), new Money(forecast.balanceMinorAt(index)));
    }
}
