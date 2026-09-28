package ru.cashprediction.core.ui.view.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Шкалы графика (спецификация v2, §5.3 «Ось Y», «Ось X»): шаги 1/2/5×10ⁿ около 6 делений, подписи целыми с
 * группировкой, ноль в диапазоне, шаг и выравнивание подписей месяцев.
 */
class ChartScaleTest {

    private static long major(long rubles) {
        return rubles * 100;
    }

    @Test
    void positiveRangeUsesRoundStepWithGroupedLabels() {
        ChartScale.Ticks ticks = ChartScale.yTicks(0, major(300_000));
        assertEquals(major(50_000), ticks.stepMinor());
        assertEquals(0, ticks.minMinor());
        assertEquals(major(300_000), ticks.maxMinor());
        assertEquals(List.of("0", "50 000", "100 000", "150 000", "200 000", "250 000", "300 000"), ticks.labels());
        assertEquals(List.of(0L, major(50_000), major(100_000), major(150_000), major(200_000), major(250_000),
                major(300_000)), ticks.values());
    }

    @Test
    void symmetricNegativeRangeLabels150000OnBothSides() {
        ChartScale.Ticks ticks = ChartScale.yTicks(major(-150_000), major(150_000));
        assertEquals(major(50_000), ticks.stepMinor());
        assertEquals(List.of("-150 000", "-100 000", "-50 000", "0", "50 000", "100 000", "150 000"), ticks.labels());
    }

    @Test
    void mixedRangeCoversDataWithFiveDivisions() {
        ChartScale.Ticks ticks = ChartScale.yTicks(major(-30_000), major(147_000));
        assertEquals(major(50_000), ticks.stepMinor());
        assertEquals(List.of("-50 000", "0", "50 000", "100 000", "150 000"), ticks.labels());
    }

    @Test
    void negativeRangeRoundsOutward() {
        ChartScale.Ticks ticks = ChartScale.yTicks(major(-120_000), major(80_000));
        assertEquals(major(50_000), ticks.stepMinor());
        assertEquals(List.of("-150 000", "-100 000", "-50 000", "0", "50 000", "100 000"), ticks.labels());
        assertEquals(major(-150_000), ticks.minMinor());
        assertEquals(major(100_000), ticks.maxMinor());
    }

    @Test
    void allNegativeDataStillIncludesZero() {
        ChartScale.Ticks ticks = ChartScale.yTicks(major(-95_000), major(-10_000));
        assertEquals(major(20_000), ticks.stepMinor());
        assertEquals(List.of("-100 000", "-80 000", "-60 000", "-40 000", "-20 000", "0"), ticks.labels());
    }

    @Test
    void largeRangeUsesMillionsStep() {
        ChartScale.Ticks ticks = ChartScale.yTicks(0, 1_234_567_800L);
        assertEquals(major(2_000_000), ticks.stepMinor());
        assertEquals("14 000 000", ticks.labels().getLast());
        assertEquals(8, ticks.values().size());
    }

    @Test
    void smallAndDegenerateRangesKeepWholeLabels() {
        assertEquals(List.of("0", "200", "400", "600", "800", "1 000"), ChartScale.yTicks(0, major(950)).labels());
        assertEquals(List.of("0", "200", "400", "600", "800", "1 000"), ChartScale.yTicks(0, 0).labels(),
                "все данные нулевые - размах 1 000");
        assertEquals(ChartScale.yTicks(0, major(950)), ChartScale.yTicks(major(950), 0), "порядок аргументов не важен");
        assertEquals(List.of("0", "1"), ChartScale.yTicks(0, 37).labels(), "шаг не меньше одной единицы валюты");
    }

    @Test
    void divisionsStayNearSixForManyRanges() {
        Set<Long> factors = Set.of(1L, 2L, 5L);
        long seed = 12345;
        for (int i = 0; i < 3000; i++) {
            // Детерминированный линейный генератор: одинаковые диапазоны при каждом запуске.
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            long a = (seed >>> 20) % 50_000_000_000L - 10_000_000_000L;
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            long b = (seed >>> 20) % 50_000_000_000L - 10_000_000_000L;
            long lo = Math.min(Math.min(a, b), 0);
            long hi = Math.max(Math.max(a, b), 0);
            if (hi - lo < major(1_000)) {
                continue;
            }
            ChartScale.Ticks ticks = ChartScale.yTicks(a, b);
            int divisions = ticks.values().size() - 1;
            String range = a + ".." + b;
            assertTrue(divisions >= 3 && divisions <= 9, range + " → " + divisions);
            assertTrue(ticks.minMinor() <= lo && ticks.maxMinor() >= hi, range);
            assertTrue(ticks.values().contains(0L), range);
            long step = ticks.stepMinor() / 100;
            while (step % 10 == 0) {
                step /= 10;
            }
            assertTrue(factors.contains(step), range + " шаг " + ticks.stepMinor());
        }
    }

    @Test
    void monthLabelStepFollowsSpec() {
        assertEquals(1, ChartScale.monthLabelStep(0));
        assertEquals(1, ChartScale.monthLabelStep(12));
        assertEquals(2, ChartScale.monthLabelStep(13));
        assertEquals(2, ChartScale.monthLabelStep(24));
        assertEquals(6, ChartScale.monthLabelStep(25));
        assertEquals(6, ChartScale.monthLabelStep(48));
        assertEquals(12, ChartScale.monthLabelStep(49));
        assertEquals(12, ChartScale.monthLabelStep(120));
        assertEquals(12, ChartScale.monthLabelStep(600));
    }

    @Test
    void monthCountUsesWholeMonths() {
        assertEquals(12, ChartScale.monthCount(LocalDate.of(2026, 9, 1), LocalDate.of(2027, 8, 31)));
        assertEquals(12, ChartScale.monthCount(LocalDate.of(2026, 9, 13), LocalDate.of(2027, 9, 12)));
        assertEquals(12, ChartScale.monthCount(LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 12)));
        assertEquals(13, ChartScale.monthCount(LocalDate.of(2026, 9, 1), LocalDate.of(2027, 9, 30)));
        assertEquals(0, ChartScale.monthCount(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 8, 31)));
    }

    @Test
    void twelveMonthsLabelEveryMonthWithYearOnFirstAndJanuary() {
        List<ChartScale.MonthTick> ticks = ChartScale.monthTicks(LocalDate.of(2026, 9, 13), LocalDate.of(2027, 9, 12));
        assertEquals(LocalDate.of(2026, 10, 1), ticks.getFirst().date(), "сетка только на первых числах");
        assertEquals(List.of("окт 2026", "ноя", "дек", "янв 2027", "фев", "мар", "апр", "май", "июн", "июл", "авг", "сен"),
                ticks.stream().map(ChartScale.MonthTick::label).toList());
    }

    @Test
    void rangeStartingOnFirstDayHasGridLineAtStart() {
        List<ChartScale.MonthTick> ticks = ChartScale.monthTicks(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        assertEquals(List.of("янв 2026", "фев", "мар"), ticks.stream().map(ChartScale.MonthTick::label).toList());
    }

    @Test
    void twentyFourMonthsLabelEverySecondMonthAlignedToJanuary() {
        List<ChartScale.MonthTick> ticks = ChartScale.monthTicks(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31));
        assertEquals(24, ticks.size());
        assertEquals(List.of("янв 2026", "мар", "май", "июл", "сен", "ноя", "янв 2027", "мар", "май", "июл", "сен", "ноя"),
                ticks.stream().filter(ChartScale.MonthTick::labeled).map(ChartScale.MonthTick::label).toList());
        assertEquals("", ticks.get(1).label(), "февраль без подписи");
    }

    @Test
    void fortyEightMonthsLabelJanuaryAndJuly() {
        List<ChartScale.MonthTick> ticks = ChartScale.monthTicks(LocalDate.of(2026, 10, 1), LocalDate.of(2030, 9, 30));
        assertEquals(List.of("янв 2027", "июл", "янв 2028", "июл", "янв 2029", "июл", "янв 2030", "июл"),
                ticks.stream().filter(ChartScale.MonthTick::labeled).map(ChartScale.MonthTick::label).toList());
    }

    @Test
    void firstLabelGetsYearEvenWhenNotJanuary() {
        List<ChartScale.MonthTick> ticks = ChartScale.monthTicks(LocalDate.of(2026, 3, 1), LocalDate.of(2029, 2, 28));
        assertEquals(List.of("июл 2026", "янв 2027", "июл"),
                ticks.stream().filter(ChartScale.MonthTick::labeled).map(ChartScale.MonthTick::label).limit(3).toList());
    }

    @Test
    void hundredTwentyMonthsLabelEveryJanuary() {
        List<ChartScale.MonthTick> ticks = ChartScale.monthTicks(LocalDate.of(2026, 9, 1), LocalDate.of(2036, 8, 31));
        assertEquals(120, ticks.size());
        assertEquals(List.of("янв 2027", "янв 2028", "янв 2029", "янв 2030", "янв 2031", "янв 2032", "янв 2033",
                        "янв 2034", "янв 2035", "янв 2036"),
                ticks.stream().filter(ChartScale.MonthTick::labeled).map(ChartScale.MonthTick::label).toList());
    }
}
