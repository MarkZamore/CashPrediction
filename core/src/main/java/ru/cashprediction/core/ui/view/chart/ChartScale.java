package ru.cashprediction.core.ui.view.chart;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.util.RuText;

/**
 * Шкалы графика (спецификация v2, §5.3 «Ось Y», «Ось X»).
 *
 * <p><b>Ось Y:</b> шаги 1/2/5×10ⁿ, около 6 делений; диапазон включает 0, подушку (если &gt; 0) и цель; подписи
 * целыми с группировкой пробелом («150 000»), выровнены вправо до оси, {@code text.muted} 11 px. <b>Ось X:</b>
 * вертикальная сетка на первом числе каждого месяца; подписи «янв», «фев», …, в январе и на первой подписи
 * «янв 2026»; шаг подписей: каждый месяц при ≤ 12 месяцах, каждый 2-й при ≤ 24, 6-й при ≤ 48, 12-й иначе.</p>
 *
 * <p><b>Выбор шага оси Y</b> (целочисленный, одинаковый на любой JVM): перебираются шаги 1, 2, 5, 10, 20, 50 …
 * единиц валюты (не меньше 1, потому что подписи целые); для каждого число делений - от {@code floor(min/шаг)} до
 * {@code ceil(max/шаг)}; берётся шаг с числом делений, ближайшим к 6, при равенстве - больший шаг. Если все данные
 * равны нулю, шкала строится для размаха 1 000 единиц.</p>
 *
 * <p><b>Выравнивание подписей оси X по календарю:</b> подпись получает первое число месяца, номер которого (январь -
 * 0) кратен шагу: при шаге 12 - январи, при шаге 6 - январи и июли, при шаге 2 - нечётные месяцы. Так в каждом
 * наборе подписей есть январь с годом. Число месяцев диапазона - полные месяцы от первого дня до дня после
 * последнего ({@code ChronoUnit.MONTHS}): период «12 месяцев», начатый в середине месяца, остаётся 12 месяцами.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class ChartScale {

    /** Желаемое число делений оси Y («около 6», §5.3). */
    static final int TARGET_Y_DIVISIONS = 6;

    /** Наименьший шаг оси Y в копейках: одна единица валюты, потому что подписи целые. */
    static final long MIN_STEP_MINOR = 100;

    /** Размах шкалы в копейках, если все данные равны нулю (план с нулевым балансом): 1 000 единиц валюты. */
    static final long DEGENERATE_SPAN_MINOR = 100_000;

    /** Множители шага внутри одного порядка. */
    private static final long[] STEP_FACTORS = {1, 2, 5};

    /**
     * Деления оси Y.
     *
     * @param minMinor  нижняя граница шкалы (кратна шагу)
     * @param maxMinor  верхняя граница шкалы (кратна шагу)
     * @param stepMinor шаг
     * @param values    значения делений снизу вверх
     * @param labels    подписи делений («150 000»)
     */
    public record Ticks(long minMinor, long maxMinor, long stepMinor, List<Long> values, List<String> labels) {
        /** Копирует списки. */
        public Ticks {
            values = List.copyOf(Objects.requireNonNull(values, "values"));
            labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
        }
    }

    /**
     * Вертикальная линия сетки оси X на первом числе месяца.
     *
     * @param date  первое число месяца
     * @param label подпись («окт», «янв 2027») или пустая строка, если месяц без подписи по шагу
     */
    record MonthTick(LocalDate date, String label) {
        /** Проверяет поля. */
        MonthTick {
            Objects.requireNonNull(date, "date");
            label = Objects.requireNonNullElse(label, "");
        }

        /** @return есть ли у линии подпись */
        boolean labeled() {
            return !label.isEmpty();
        }
    }

    private ChartScale() {
    }

    /**
     * Деления оси Y для диапазона данных.
     *
     * @param dataMinMinor минимум данных (с учётом 0, подушки и цели)
     * @param dataMaxMinor максимум данных
     * @return деления
     */
    public static Ticks yTicks(long dataMinMinor, long dataMaxMinor) {
        // Ноль входит в шкалу всегда (§5.3), даже если вызывающий код забыл его учесть; порядок аргументов не важен.
        long lo = Math.min(Math.min(dataMinMinor, dataMaxMinor), 0);
        long hi = Math.max(Math.max(dataMinMinor, dataMaxMinor), 0);
        if (hi == lo) {
            hi = lo + DEGENERATE_SPAN_MINOR;
        }
        long span = hi - lo;
        long bestStep = MIN_STEP_MINOR;
        long bestDiff = Long.MAX_VALUE;
        boolean done = false;
        // Перебор по возрастанию: при равной близости к 6 побеждает больший шаг (условие <=).
        for (long magnitude = MIN_STEP_MINOR; !done && magnitude <= Long.MAX_VALUE / 50; magnitude *= 10) {
            for (long factor : STEP_FACTORS) {
                long step = magnitude * factor;
                long divisions = Math.ceilDiv(hi, step) - Math.floorDiv(lo, step);
                long diff = Math.abs(divisions - TARGET_Y_DIVISIONS);
                if (diff <= bestDiff) {
                    bestStep = step;
                    bestDiff = diff;
                }
                // Шаг больше размаха даёт 1-2 деления, и дальше они не приближаются к 6.
                if (step > span) {
                    done = true;
                    break;
                }
            }
        }
        long first = Math.floorDiv(lo, bestStep);
        long last = Math.ceilDiv(hi, bestStep);
        List<Long> values = new ArrayList<>((int) (last - first + 1));
        List<String> labels = new ArrayList<>((int) (last - first + 1));
        for (long k = first; k <= last; k++) {
            long value = k * bestStep;
            values.add(value);
            // Шаг кратен 100 копейкам, поэтому деление точное: подпись - целые единицы валюты.
            labels.add(RuText.groupDigits(value / 100));
        }
        return new Ticks(first * bestStep, last * bestStep, bestStep, values, labels);
    }

    /**
     * Шаг подписей оси X в месяцах.
     *
     * @param months число месяцев диапазона
     * @return 1, 2, 6 или 12
     */
    public static int monthLabelStep(int months) {
        if (months <= 12) {
            return 1;
        }
        if (months <= 24) {
            return 2;
        }
        if (months <= 48) {
            return 6;
        }
        return 12;
    }

    /**
     * Число полных месяцев диапазона дат включительно.
     *
     * @param from первый день
     * @param to   последний день
     * @return {@code ChronoUnit.MONTHS.between(from, to + 1 день)}; 0, если {@code to < from}
     */
    static int monthCount(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) {
            return 0;
        }
        return (int) ChronoUnit.MONTHS.between(from, to.plusDays(1));
    }

    /**
     * Линии сетки оси X с подписями.
     *
     * @param from первый день диапазона
     * @param to   последний день диапазона
     * @return первое число каждого месяца внутри {@code [from, to]} по порядку; подпись - по шагу
     *         {@link #monthLabelStep(int)}, с годом в январе и у первой подписи
     */
    static List<MonthTick> monthTicks(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        int step = monthLabelStep(monthCount(from, to));
        LocalDate first = from.getDayOfMonth() == 1 ? from : from.withDayOfMonth(1).plusMonths(1);
        List<MonthTick> ticks = new ArrayList<>();
        boolean labeledBefore = false;
        for (LocalDate date = first; !date.isAfter(to); date = date.plusMonths(1)) {
            String label = "";
            if ((date.getMonthValue() - 1) % step == 0) {
                label = monthLabel(YearMonth.from(date), date.getMonth() == Month.JANUARY || !labeledBefore);
                labeledBefore = true;
            }
            ticks.add(new MonthTick(date, label));
        }
        return List.copyOf(ticks);
    }

    /**
     * Подпись месяца оси X.
     *
     * @param month    месяц
     * @param withYear добавить ли год («янв 2026»)
     * @return «янв» или «янв 2026»
     */
    static String monthLabel(YearMonth month, boolean withYear) {
        // Ключи литералами в ветках, а не сборкой строки: проверка каталога видит каждый используемый ключ.
        String name = switch (month.getMonth()) {
            case JANUARY -> UiText.get("chart.month.1");
            case FEBRUARY -> UiText.get("chart.month.2");
            case MARCH -> UiText.get("chart.month.3");
            case APRIL -> UiText.get("chart.month.4");
            case MAY -> UiText.get("chart.month.5");
            case JUNE -> UiText.get("chart.month.6");
            case JULY -> UiText.get("chart.month.7");
            case AUGUST -> UiText.get("chart.month.8");
            case SEPTEMBER -> UiText.get("chart.month.9");
            case OCTOBER -> UiText.get("chart.month.10");
            case NOVEMBER -> UiText.get("chart.month.11");
            case DECEMBER -> UiText.get("chart.month.12");
        };
        return withYear ? UiText.get("chart.monthYear", name, String.valueOf(month.getYear())) : name;
    }
}
