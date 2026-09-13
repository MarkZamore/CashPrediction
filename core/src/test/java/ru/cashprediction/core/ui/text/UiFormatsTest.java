package ru.cashprediction.core.ui.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Money;

/**
 * Каждый пример раздела «Форматы» спецификации интерфейса v2 и граничные случаи округления.
 */
class UiFormatsTest {

    @Test
    void moneyFormatsOfSpec() {
        // Money.format/format(cur)/formatSigned — часть того же раздела спецификации.
        assertEquals("80 000,00", Money.ofMajor(80_000).format());
        assertEquals("80 000,00 ₽", Money.ofMajor(80_000).format("₽"));
        assertEquals("+80 000,00", Money.ofMajor(80_000).formatSigned());
        assertEquals("-45 000,00", Money.ofMajor(-45_000).formatSigned());
        assertEquals("0,00", Money.ZERO.formatSigned());
    }

    @Test
    void wholeAndWholeSigned() {
        assertEquals("177 000 ₽", UiFormats.whole(Money.ofMajor(177_000), "₽"));
        assertEquals("177 000", UiFormats.whole(Money.ofMajor(177_000), ""));
        assertEquals("-1 200 ₽", UiFormats.whole(Money.ofMajor(-1_200), "₽"));
        assertEquals("+46 654", UiFormats.wholeSigned(Money.ofMajor(46_654)));
        assertEquals("-46 654", UiFormats.wholeSigned(Money.ofMajor(-46_654)));
        assertEquals("0", UiFormats.wholeSigned(Money.ZERO));
    }

    @ParameterizedTest(name = "{0} коп. → «{1}»")
    @CsvSource(delimiter = '|', value = {
        "12345650 | 123 457",
        "12345649 | 123 456",
        "-12345650 | -123 457",
        "-40      | 0",
        "50       | 1"
    })
    void wholeRoundsHalfUpByMagnitude(long minor, String expected) {
        assertEquals(expected, UiFormats.whole(Money.ofMinor(minor), null));
    }

    @ParameterizedTest(name = "{0} ₽ → «{1}»")
    @CsvSource(delimiter = '|', value = {
        "1500000    | 1,5 млн",
        "2000000    | 2 млн",
        "896000     | 896 тыс",
        "950        | 950",
        "896432     | 896 тыс",
        "999600     | 1 млн",
        "10000      | 10 тыс",
        "9999       | 9 999",
        "12345678   | 12,3 млн",
        "1234567890 | 1 234,6 млн",
        "-1500000   | -1,5 млн",
        "0          | 0"
    })
    void compact(long major, String expected) {
        assertEquals(expected, UiFormats.compact(Money.ofMajor(major)));
    }

    @Test
    void compactDoesNotPrintNegativeZero() {
        assertEquals("0", UiFormats.compact(Money.ofMinor(-40)));
    }

    @Test
    void horizonLabels() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        assertEquals("12 месяцев", UiFormats.horizonLabel(new Horizon.Months(12), start));
        assertEquals("1 месяц", UiFormats.horizonLabel(new Horizon.Months(1), start));
        assertEquals("24 месяца", UiFormats.horizonLabel(new Horizon.Months(24), start));
        assertEquals("2 года", UiFormats.horizonLabel(new Horizon.Years(2), start));
        assertEquals("5 лет", UiFormats.horizonLabel(new Horizon.Years(5), start));
        assertEquals("до 31.08.2027", UiFormats.horizonLabel(new Horizon.Until(LocalDate.of(2027, 8, 31)), start));
        // Горизонт «до даты» раньше начала плана фактически заканчивается в день начала.
        assertEquals("до 01.09.2026", UiFormats.horizonLabel(new Horizon.Until(LocalDate.of(2026, 1, 1)), start));
        assertEquals("до 31.08.2027", UiFormats.horizonLabel(new Horizon.Until(LocalDate.of(2027, 8, 31)), null));
    }

    @Test
    void datesAndTimes() {
        LocalDate date = LocalDate.of(2026, 10, 5);
        assertEquals("05.10.2026", UiFormats.date(date));
        assertEquals("Октябрь 2026", UiFormats.monthTitle(YearMonth.of(2026, 10)));
        assertEquals("пн", UiFormats.weekdayShort(date));
        assertEquals("понедельник", UiFormats.weekdayFull(date));
        assertEquals("пн, 05.10.2026", UiFormats.weekdayDate(date));
        assertEquals("05.10.2026, понедельник", UiFormats.dateWeekday(date));
        assertEquals("10:15:30", UiFormats.time(LocalTime.of(10, 15, 30)));
        assertEquals("13.09.2026 10:15", UiFormats.dateTime(LocalDateTime.of(2026, 9, 13, 10, 15, 30)));
        assertEquals("13.09.2026 10:15:30", UiFormats.dateTimeSeconds(LocalDateTime.of(2026, 9, 13, 10, 15, 30)));
        assertEquals("", UiFormats.date(null));
    }

    @Test
    void countUsesRussianPlurals() {
        assertEquals("1 корректировка", UiFormats.count(1, "корректировка", "корректировки", "корректировок"));
        assertEquals("2 корректировки", UiFormats.count(2, "корректировка", "корректировки", "корректировок"));
        assertEquals("12 корректировок", UiFormats.count(12, "корректировка", "корректировки", "корректировок"));
    }
}
