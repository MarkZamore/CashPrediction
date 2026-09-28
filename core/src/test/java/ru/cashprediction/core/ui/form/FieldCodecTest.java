package ru.cashprediction.core.ui.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.MonthDay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.session.WindowType;

/**
 * Канонические и показываемые формы значений полей (архитектура §3.5, спецификация v2 §6.0) и решение L1b.
 */
class FieldCodecTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "80000|80000,00",
            "80 000,5|80000,50",
            "80000.50|80000,50",
            "-1 200|-1200,00",
            "1.234,56|1234,56",
            "1,234.56|1234,56",
            ",5|0,50",
            "45 000,00 ₽|45000,00",
            "  95 000,00  |95000,00",
            "−300|-300,00"
    })
    void moneyIsCanonicalized(String raw, String canonical) {
        assertEquals(canonical, FieldCodec.canonical(FieldKind.MONEY, raw));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            // Решение L1b: больше двух цифр после одиночной запятой или точки - некорректный ввод.
            "1,234", "12.345", "0,005", "1234,567", "1.234,567",
            // Решение L1: неверные группы разрядов.
            "12 3", "1 23", "12,3,4",
            "abc", "80 000,5x"
    })
    void invalidMoneyIsKeptVerbatim(String raw) {
        assertEquals(raw, FieldCodec.canonical(FieldKind.MONEY, raw));
        assertEquals(raw, FieldCodec.display(FieldKind.MONEY, raw), "некорректный текст показывается дословно");
        assertTrue(FieldCodec.parseMoney(raw).isEmpty());
    }

    @Test
    void emptyValuesStayEmpty() {
        for (FieldKind kind : FieldKind.values()) {
            assertEquals("", FieldCodec.canonical(kind, null), kind.name());
            assertEquals("", FieldCodec.display(kind, null), kind.name());
        }
        assertEquals("", FieldCodec.canonical(FieldKind.MONEY, "   "));
        assertEquals("", FieldCodec.canonical(FieldKind.DATE, ""));
        assertEquals("", FieldCodec.canonical(FieldKind.MONTH_DAY, " "));
        assertEquals("", FieldCodec.canonical(FieldKind.SPINNER, ""));
    }

    @Test
    void moneyDisplayReformats() {
        assertEquals("95 000,00", FieldCodec.display(FieldKind.MONEY, "95000,00"));
        assertEquals("-1 200,00", FieldCodec.display(FieldKind.MONEY, "-1200,00"));
        assertEquals("80 000,00", FieldCodec.display(FieldKind.MONEY, FieldCodec.canonical(FieldKind.MONEY, "80000")));
        assertEquals("0,50", FieldCodec.display(FieldKind.MONEY, "0,50"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "2026-10-05|2026-10-05",
            "05.10.2026|2026-10-05",
            "5.10.2026|2026-10-05",
            " 29.02.2028 |2028-02-29"
    })
    void datesAcceptIsoAndRussianForm(String raw, String canonical) {
        assertEquals(canonical, FieldCodec.canonical(FieldKind.DATE, raw));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"30.02.2026", "2026-02-30", "05.10.26", "5 октября", "2026-10-5", "32.01.2026"})
    void invalidDatesAreKept(String raw) {
        assertEquals(raw, FieldCodec.canonical(FieldKind.DATE, raw));
        assertEquals(raw, FieldCodec.display(FieldKind.DATE, raw));
    }

    @Test
    void dateDisplayIsRussian() {
        assertEquals("05.10.2026", FieldCodec.display(FieldKind.DATE, "2026-10-05"));
        assertEquals(LocalDate.of(2026, 10, 5), FieldCodec.parseDate("05.10.2026").orElseThrow());
        assertEquals("2026-10-05", FieldCodec.date(LocalDate.of(2026, 10, 5)));
        assertEquals("", FieldCodec.date(null));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"15.03|03-15", "03-15|03-15", "5.3|03-05", "3-5|03-05", "29.02|02-29", "--12-31|12-31"})
    void monthDayAcceptsDayMonthAndMonthDay(String raw, String canonical) {
        assertEquals(canonical, FieldCodec.canonical(FieldKind.MONTH_DAY, raw));
    }

    @Test
    void monthDayInvalidAndDisplay() {
        assertEquals("31.02", FieldCodec.canonical(FieldKind.MONTH_DAY, "31.02"));
        assertEquals("13-01", FieldCodec.canonical(FieldKind.MONTH_DAY, "13-01"));
        assertEquals("15.03", FieldCodec.display(FieldKind.MONTH_DAY, "03-15"));
        assertEquals("03-15", FieldCodec.monthDay(MonthDay.of(3, 15)));
        assertEquals("ерунда", FieldCodec.display(FieldKind.MONTH_DAY, "ерунда"));
    }

    @Test
    void spinnerCheckAndTextForms() {
        assertEquals("12", FieldCodec.canonical(FieldKind.SPINNER, " 12 "));
        assertEquals("5", FieldCodec.canonical(FieldKind.SPINNER, "+5"));
        assertEquals("7", FieldCodec.canonical(FieldKind.SPINNER, "007"));
        assertEquals("-3", FieldCodec.canonical(FieldKind.SPINNER, "-3"));
        assertEquals("1 2", FieldCodec.canonical(FieldKind.SPINNER, "1 2"));
        assertEquals("true", FieldCodec.canonical(FieldKind.CHECK, "TRUE"));
        assertEquals("false", FieldCodec.canonical(FieldKind.CHECK, " false "));
        assertEquals("maybe", FieldCodec.canonical(FieldKind.CHECK, "maybe"));
        assertEquals("  Семейный бюджет ", FieldCodec.canonical(FieldKind.TEXT, "  Семейный бюджет "));
        assertEquals("строка 1\nстрока 2", FieldCodec.canonical(FieldKind.MULTILINE, "строка 1\nстрока 2"));
        assertEquals("MONTHLY", FieldCodec.canonical(FieldKind.CHOICE, "MONTHLY"));
        assertTrue(FieldCodec.parseBoolean(" True "));
        assertFalse(FieldCodec.parseBoolean("yes"));
        assertEquals("true", FieldCodec.bool(true));
    }

    @Test
    void canonicalIsIdempotentAndDisplayRoundTrips() {
        for (String raw : new String[] {"80000", "80 000,5", "-1 200", "0,01", "999 999 999 999,99"}) {
            String canonical = FieldCodec.canonical(FieldKind.MONEY, raw);
            assertEquals(canonical, FieldCodec.canonical(FieldKind.MONEY, canonical), raw);
            assertEquals(canonical, FieldCodec.canonical(FieldKind.MONEY, FieldCodec.display(FieldKind.MONEY, canonical)), raw);
        }
        String date = FieldCodec.canonical(FieldKind.DATE, "05.10.2026");
        assertEquals(date, FieldCodec.canonical(FieldKind.DATE, FieldCodec.display(FieldKind.DATE, date)));
        String monthDay = FieldCodec.canonical(FieldKind.MONTH_DAY, "15.03");
        assertEquals(monthDay, FieldCodec.canonical(FieldKind.MONTH_DAY, FieldCodec.display(FieldKind.MONTH_DAY, monthDay)));
    }

    @Test
    void parseHelpersAndCanonicalMoney() {
        assertEquals(Money.ofMinor(8_000_050), FieldCodec.parseMoney("80 000,5").orElseThrow());
        assertEquals("95000,00", FieldCodec.money(Money.ofMajor(95_000)));
        assertEquals("", FieldCodec.money(null));
        assertEquals(42, FieldCodec.parseLong(" 42 ").orElseThrow());
        assertTrue(FieldCodec.parseLong("4.2").isEmpty());
        assertTrue(FieldCodec.parseMonthDay("").isEmpty());
    }

    @Test
    void ambiguousAmountAnalysisSuggestsThousands() {
        assertEquals(FieldCodec.MoneyStatus.FRACTION, FieldCodec.analyzeMoney("1,234").status());
        assertEquals("1 234", FieldCodec.analyzeMoney("1,234").suggestion());
        assertEquals("12 345", FieldCodec.analyzeMoney("12.345").suggestion());
        assertEquals("-1 234 567", FieldCodec.analyzeMoney("-1,234567").suggestion());
        assertEquals("", FieldCodec.analyzeMoney("0,005").suggestion());
        assertEquals("", FieldCodec.analyzeMoney("1234,567").suggestion());
        assertEquals(FieldCodec.MoneyStatus.OK, FieldCodec.analyzeMoney("1,234,567").status(), "повторённая запятая - разряды");
        assertEquals(FieldCodec.MoneyStatus.TOO_BIG, FieldCodec.analyzeMoney("999999999999999999999").status());
        assertEquals(FieldCodec.MoneyStatus.INVALID, FieldCodec.analyzeMoney("12 3").status());
        assertEquals(FieldCodec.MoneyStatus.EMPTY, FieldCodec.analyzeMoney(" ").status());
    }

    @Test
    void legacyValuesBecomeCanonical() {
        assertEquals("95000,00", FieldCodec.acceptLegacy(WindowType.RULE_EDITOR, "amount", "95 000,00"));
        assertEquals("2026-10-05", FieldCodec.acceptLegacy(WindowType.ONE_TIME_EDITOR, "date", "05.10.2026"));
        assertEquals("03-15", FieldCodec.acceptLegacy(WindowType.RULE_EDITOR, "monthDay", "15.03"));
        assertEquals("true", FieldCodec.acceptLegacy(WindowType.RULE_EDITOR, "enabled", "TRUE"));
        assertEquals("12", FieldCodec.acceptLegacy(WindowType.NEW_PLAN_WIZARD, "horizonValue", " 12"));
        assertEquals("MONTHS", FieldCodec.acceptLegacy(WindowType.PLAN_SETTINGS, "horizonKind", "months"));
        assertEquals("MOVE", FieldCodec.acceptLegacy(WindowType.ADJUSTMENT_EDITOR, "action", "MOVE_DATE"));
        assertEquals("REPLACE", FieldCodec.acceptLegacy(WindowType.ADJUSTMENT_EDITOR, "action", "REPLACE"));
        assertEquals("INCOME", FieldCodec.acceptLegacy(WindowType.RULE_EDITOR, "kind", "Доход"));
        assertEquals("EXPENSE", FieldCodec.acceptLegacy(WindowType.ONE_TIME_EDITOR, "kind", "expense"));
        assertEquals("MONTHLY", FieldCodec.acceptLegacy(WindowType.RULE_EDITOR, "recurrenceKind", "Ежемесячно / каждые N месяцев"));
        assertEquals("NONE", FieldCodec.acceptLegacy(WindowType.RULE_EDITOR, "weekendPolicy", "Не сдвигать"));
        assertEquals("WEDNESDAY", FieldCodec.acceptLegacy(WindowType.RULE_EDITOR, "weekday", "3"));
        assertEquals("MONDAY", FieldCodec.acceptLegacy(WindowType.RULE_EDITOR, "weekday", "пн"));
        assertEquals("SATURDAY", FieldCodec.acceptLegacy(WindowType.RULE_EDITOR, "weekday", "saturday"));
        assertEquals("TAB", FieldCodec.acceptLegacy(WindowType.CSV_EXPORT, "separator", "\t"));
        assertEquals(";", FieldCodec.acceptLegacy(WindowType.CSV_EXPORT, "separator", ";"));
        assertEquals("ALL", FieldCodec.acceptLegacy(WindowType.CSV_EXPORT, "range", "all"));
        assertEquals("1,234", FieldCodec.acceptLegacy(WindowType.TEXT_INPUT, "value", "1,234"), "значение назначения - как есть");
        assertEquals("12 3", FieldCodec.acceptLegacy(WindowType.QUICK_EDIT_POPUP, "amount", "12 3"), "некорректное - как есть");
        assertEquals("x", FieldCodec.acceptLegacy(WindowType.RULE_EDITOR, "unknownField", "x"));
        assertEquals("", FieldCodec.acceptLegacy(WindowType.RULE_EDITOR, "title", null));
        assertEquals("Зарплата ", FieldCodec.acceptLegacy(WindowType.RULE_EDITOR, "title", "Зарплата "));
    }
}
