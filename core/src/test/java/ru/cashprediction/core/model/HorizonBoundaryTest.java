package ru.cashprediction.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/** Проверяет полные месяцы включительного горизонта на границах диапазона дат. */
class HorizonBoundaryTest {

    /** Проверяет точное число месяцев всего диапазона, в том числе неполный первый месяц. */
    @Test
    void entireDateRangeKeepsExactMonthCount() {
        Horizon horizon = new Horizon.Until(LocalDate.MAX);
        // От января -999999999 до января 1000000000: 1999999999 полных лет.
        assertEquals(23_999_999_988L, horizon.approximateMonths(LocalDate.MIN));
        assertEquals(23_999_999_987L, horizon.approximateMonths(LocalDate.MIN.plusDays(1)));
        assertEquals(23_999_999_987L, horizon.approximateMonths(LocalDate.MIN.plusDays(30)));
        assertEquals(23_999_999_987L, horizon.approximateMonths(LocalDate.MIN.plusMonths(1)));
        assertEquals(23_999_999_976L, horizon.approximateMonths(LocalDate.MIN.plusYears(1)));
    }

    /** Проверяет нижнюю границу результата и прежнее поведение даты конца до начала. */
    @Test
    void singletonAndPastEndRemainOneMonthAtBothBoundaries() {
        for (LocalDate start : new LocalDate[] {LocalDate.MIN, LocalDate.MAX}) {
            assertEquals(1, new Horizon.Until(start).approximateMonths(start));
        }
        Horizon pastEnd = new Horizon.Until(LocalDate.MIN);
        assertEquals(LocalDate.MAX, pastEnd.endDate(LocalDate.MAX));
        assertEquals(1, pastEnd.approximateMonths(LocalDate.MAX));
    }

    /** Сверяет верхнюю границу с независимым расчётом после сдвига на цикл григорианского календаря. */
    @Test
    void finalDayMatchesReferenceShiftedByFourHundredYears() {
        LocalDate referenceEnd = LocalDate.MAX.minusYears(400);
        Horizon horizon = new Horizon.Until(LocalDate.MAX);
        LocalDate firstStart = LocalDate.MAX.minusYears(4);
        for (LocalDate start = firstStart; ; start = start.plusDays(1)) {
            long expected = referenceMonths(start.minusYears(400), referenceEnd);
            assertEquals(expected, horizon.approximateMonths(start), start::toString);
            if (start.equals(LocalDate.MAX)) {
                break;
            }
        }
    }

    /** Сверяет обычные периоды, високосные дни и концы месяцев с прежней формулой. */
    @Test
    void ordinaryInclusiveSpansMatchIndependentReference() {
        int[] lengths = {0, 1, 27, 28, 29, 30, 31, 59, 365, 366, 730};
        LocalDate limit = LocalDate.of(2005, 1, 1);
        for (LocalDate start = LocalDate.of(1999, 1, 1); start.isBefore(limit); start = start.plusDays(1)) {
            for (int days : lengths) {
                LocalDate end = start.plusDays(days);
                Horizon horizon = new Horizon.Until(end);
                assertEquals(referenceMonths(start, end), horizon.approximateMonths(start),
                        start + " / " + end);
            }
        }
        LocalDate lowerEnd = LocalDate.MIN.plusYears(4);
        for (LocalDate start = LocalDate.MIN; start.isBefore(lowerEnd); start = start.plusDays(1)) {
            assertEquals(referenceMonths(start, lowerEnd),
                    new Horizon.Until(lowerEnd).approximateMonths(start), start::toString);
        }
    }

    /** Проверяет, что календарное усечение месяцев и лет сохраняет прежнюю точность. */
    @Test
    void monthAndYearHorizonsKeepCalendarTruncation() {
        Horizon[] horizons = {new Horizon.Months(1), new Horizon.Months(2), new Horizon.Months(12),
                new Horizon.Months(600), new Horizon.Years(1), new Horizon.Years(4), new Horizon.Years(50)};
        LocalDate[] starts = {LocalDate.MIN, LocalDate.of(1999, 1, 31), LocalDate.of(2000, 2, 29),
                LocalDate.of(2000, 3, 31), LocalDate.of(2001, 4, 30), LocalDate.of(2026, 9, 1)};
        for (LocalDate start : starts) {
            for (Horizon horizon : horizons) {
                assertEquals(referenceMonths(start, horizon.endDate(start)), horizon.approximateMonths(start),
                        start + " / " + horizon);
            }
        }
        assertEquals(12, new Horizon.Months(13).approximateMonths(LocalDate.of(1999, 1, 31)));
        assertEquals(11, new Horizon.Months(12).approximateMonths(LocalDate.of(2000, 2, 29)));
        assertEquals(11, new Horizon.Years(1).approximateMonths(LocalDate.of(2000, 2, 29)));
    }

    /** Проверяет, что невозможный конец месячного или годового горизонта не маскируется. */
    @Test
    void unrepresentableMonthAndYearEndsStillFail() {
        assertThrows(DateTimeException.class, () -> new Horizon.Months(1).approximateMonths(LocalDate.MAX));
        assertThrows(DateTimeException.class, () -> new Horizon.Years(1).approximateMonths(LocalDate.MAX));
    }

    /** Считает эталон через календарный API только там, где следующий день представим. */
    private static long referenceMonths(LocalDate start, LocalDate inclusiveEnd) {
        return Math.max(1, ChronoUnit.MONTHS.between(start, inclusiveEnd.plusDays(1)));
    }
}
