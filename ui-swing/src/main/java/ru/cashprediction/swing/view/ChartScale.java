package ru.cashprediction.swing.view;

import java.util.ArrayList;
import java.util.List;

/**
 * «Красивые» деления оси графика: шаг 1, 2 или 5 × 10ⁿ, чтобы подписи оси баланса были круглыми
 * («0», «50 000», «100 000»), а не «37 412,5».
 *
 * <p>Класс без состояния.</p>
 */
public final class ChartScale {

    private ChartScale() {
    }

    /**
     * Круглый шаг делений.
     *
     * @param range       размах значений (больше нуля)
     * @param targetTicks желаемое число делений
     * @return шаг вида 1, 2 или 5 × 10ⁿ
     */
    public static double niceStep(double range, int targetTicks) {
        if (!(range > 0) || targetTicks < 1) {
            return 1;
        }
        double rough = range / targetTicks;
        double magnitude = Math.pow(10, Math.floor(Math.log10(rough)));
        double fraction = rough / magnitude;
        double nice = fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10;
        return nice * magnitude;
    }

    /**
     * Деления, покрывающие диапазон.
     *
     * @param min         минимум
     * @param max         максимум
     * @param targetTicks желаемое число делений
     * @return значения делений по возрастанию (первое ≤ min, последнее ≥ max)
     */
    public static List<Double> ticks(double min, double max, int targetTicks) {
        if (max <= min) {
            max = min + 1;
        }
        double step = niceStep(max - min, targetTicks);
        double start = Math.floor(min / step) * step;
        double end = Math.ceil(max / step) * step;
        List<Double> result = new ArrayList<>();
        // Защита от бесконечного цикла при вырожденных числах.
        for (double v = start; v <= end + step / 2 && result.size() < 100; v += step) {
            result.add(Math.abs(v) < step / 1e6 ? 0.0 : v);
        }
        return result;
    }
}
