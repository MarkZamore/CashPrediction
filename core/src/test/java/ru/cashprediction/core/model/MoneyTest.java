package ru.cashprediction.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Тесты денежного типа: разбор ручного ввода, форматирование, арифметика.
 */
class MoneyTest {

    /** Разные варианты записи одной и той же суммы, которые может ввести человек или Блокнот. */
    @ParameterizedTest(name = "«{0}» = {1} коп.")
    @CsvSource(delimiter = '|', value = {
        "80 000,00      | 8000000",
        "80000          | 8000000",
        "80000.5        | 8000050",
        "80 000,50 | 8000050",
        "80 000,50 | 8000050",
        "-1 200         | -120000",
        "−1 200,10 | -120010",
        "+15            | 1500",
        "1.234,56       | 123456",
        "1,234.56       | 123456",
        "1,234,567      | 123456700",
        "0,005          | 1",
        "0,004          | 0",
        "45 000,00 ₽    | 4500000",
        ",5             | 50"
    })
    void parsesHumanInput(String text, long expectedMinor) {
        assertEquals(expectedMinor, Money.parse(text).minor());
    }

    @ParameterizedTest(name = "«{0}» отклоняется")
    @CsvSource(delimiter = '|', value = {"''", "abc", "12a3", "-", "1,2,3.4.5x", "."})
    void rejectsGarbage(String text) {
        assertThrows(IllegalArgumentException.class, () -> Money.parse(text));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> Money.parse(null));
    }

    @Test
    void formatsWithGroupingAndComma() {
        assertEquals("80 000,00", Money.ofMajor(80_000).format());
        assertEquals("1 234 567,89", Money.ofMinor(123_456_789).format());
        assertEquals("-1 200,50", Money.ofMinor(-120_050).format());
        assertEquals("0,05", Money.ofMinor(5).format());
        assertEquals("999,00", Money.ofMajor(999).format());
        assertEquals("80 000,00 ₽", Money.ofMajor(80_000).format("₽"));
        assertEquals("+80 000,00", Money.ofMajor(80_000).formatSigned());
        assertEquals("95000,00", Money.ofMajor(95_000).formatPlain());
        assertEquals("-0,07", Money.ofMinor(-7).formatPlain());
    }

    @Test
    void formatAndParseRoundTrip() {
        for (long minor : new long[] {0, 1, 99, 100, 123_456, -987_654_321, 100_000_000_000L}) {
            Money m = Money.ofMinor(minor);
            assertEquals(m, Money.parse(m.format()), "format " + minor);
            assertEquals(m, Money.parse(m.formatPlain()), "plain " + minor);
        }
    }

    @Test
    void arithmetic() {
        Money a = Money.ofMajor(100);
        Money b = Money.ofMinor(2_550);
        assertEquals(Money.ofMinor(12_550), a.plus(b));
        assertEquals(Money.ofMinor(7_450), a.minus(b));
        assertEquals(Money.ofMinor(-10_000), a.negate());
        assertEquals(a, a.negate().abs());
        assertEquals(Money.ofMinor(11_000), a.times(new BigDecimal("1.10")));
        assertEquals(Money.ofMinor(3), Money.ofMinor(5).times(new BigDecimal("0.5")), "HALF_UP: 2,5 коп. -> 3 коп.");
        assertTrue(Money.ofMinor(-1).isNegative());
        assertFalse(Money.ZERO.isNegative());
        assertTrue(b.isLessThan(a));
    }

    @Test
    void divideCeilToMajorRoundsUp() {
        assertEquals(Money.ofMajor(3_334), Money.ofMajor(10_000).divideCeilToMajor(3));
        assertEquals(Money.ofMajor(5_000), Money.ofMajor(10_000).divideCeilToMajor(2));
    }

    @Test
    void needsRoundingDetectsExtraDigits() {
        assertTrue(Money.needsRounding("10,005"));
        assertFalse(Money.needsRounding("10,05"));
        assertFalse(Money.needsRounding("10"));
    }

    @Test
    void rejectsLongMinValue() {
        assertThrows(IllegalArgumentException.class, () -> new Money(Long.MIN_VALUE));
    }
}
