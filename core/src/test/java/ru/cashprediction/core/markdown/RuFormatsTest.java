package ru.cashprediction.core.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.WeekendPolicy;

/**
 * Исчерпывающие тесты грамматики значений файла плана.
 */
class RuFormatsTest {

    static Stream<Arguments> recurrenceCases() {
        return Stream.of(
                Arguments.of("ежемесячно 5", new Recurrence.Monthly(5, 1)),
                Arguments.of("Ежемесячно 31", new Recurrence.Monthly(31, 1)),
                Arguments.of("ежемесячно 5-го", new Recurrence.Monthly(5, 1)),
                Arguments.of("ежемесячно 7 числа", new Recurrence.Monthly(7, 1)),
                Arguments.of("каждые 2 месяца 10", new Recurrence.Monthly(10, 2)),
                Arguments.of("каждые 5 месяцев 1", new Recurrence.Monthly(1, 5)),
                Arguments.of("каждые 21 месяц 3", new Recurrence.Monthly(3, 21)),
                Arguments.of("каждые 3 мес 15", new Recurrence.Monthly(15, 3)),
                Arguments.of("каждые 3 мес. 15", new Recurrence.Monthly(15, 3)),
                Arguments.of("каждый месяц 7", new Recurrence.Monthly(7, 1)),
                Arguments.of("  КАЖДЫЕ 2 МЕСЯЦА   10 ", new Recurrence.Monthly(10, 2)),
                Arguments.of("еженедельно сб", new Recurrence.Weekly(DayOfWeek.SATURDAY, 1)),
                Arguments.of("еженедельно вс", new Recurrence.Weekly(DayOfWeek.SUNDAY, 1)),
                Arguments.of("еженедельно в среду", new Recurrence.Weekly(DayOfWeek.WEDNESDAY, 1)),
                Arguments.of("каждые 5 нед. чт", new Recurrence.Weekly(DayOfWeek.THURSDAY, 5)),
                Arguments.of("каждые 2 нед пн", new Recurrence.Weekly(DayOfWeek.MONDAY, 2)),
                Arguments.of("каждые 7 дн.", new Recurrence.EveryNDays(7)),
                Arguments.of("каждые 4 дн", new Recurrence.EveryNDays(4)),
                Arguments.of("еженедельно суббота", new Recurrence.Weekly(DayOfWeek.SATURDAY, 1)),
                Arguments.of("Еженедельно  ПН", new Recurrence.Weekly(DayOfWeek.MONDAY, 1)),
                Arguments.of("еженедельно по пятницам", null),
                Arguments.of("каждые 2 недели пт", new Recurrence.Weekly(DayOfWeek.FRIDAY, 2)),
                Arguments.of("каждые 3 недели воскресенье", new Recurrence.Weekly(DayOfWeek.SUNDAY, 3)),
                Arguments.of("каждые 5 недель вт", new Recurrence.Weekly(DayOfWeek.TUESDAY, 5)),
                Arguments.of("каждые 21 неделю чт", new Recurrence.Weekly(DayOfWeek.THURSDAY, 21)),
                Arguments.of("каждую неделю ср", new Recurrence.Weekly(DayOfWeek.WEDNESDAY, 1)),
                Arguments.of("ежедневно", new Recurrence.EveryNDays(1)),
                Arguments.of("каждый день", new Recurrence.EveryNDays(1)),
                Arguments.of("каждые 3 дня", new Recurrence.EveryNDays(3)),
                Arguments.of("каждые 10 дней", new Recurrence.EveryNDays(10)),
                Arguments.of("каждые 21 день", new Recurrence.EveryNDays(21)),
                Arguments.of("каждые 3дня", new Recurrence.EveryNDays(3)),
                Arguments.of("ежегодно 03-15", new Recurrence.Yearly(MonthDay.of(3, 15))),
                Arguments.of("ежегодно 15.03", new Recurrence.Yearly(MonthDay.of(3, 15))),
                Arguments.of("ёжегодно 02-29", new Recurrence.Yearly(MonthDay.of(2, 29))),
                Arguments.of("ЕЖЕГОДНО 31.12", new Recurrence.Yearly(MonthDay.of(12, 31))));
    }

    @ParameterizedTest(name = "«{0}»")
    @MethodSource("recurrenceCases")
    void parsesRecurrence(String text, Recurrence expected) {
        if (expected == null) {
            // «по пятницам» — не поддерживаемое склонение: сообщение должно быть понятным.
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RuFormats.parseRecurrence(text));
            assertTrue(e.getMessage().contains("пятницам"), e.getMessage());
            return;
        }
        assertEquals(expected, RuFormats.parseRecurrence(text));
    }

    @ParameterizedTest(name = "«{0}» отклоняется")
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "раз в месяц", "ежемесячно", "ежемесячно 32", "ежемесячно 0", "каждые 0 дней",
        "каждые 367 дней", "каждые 2 недели xx", "каждые 53 недели пн", "ежегодно 02-30", "ежегодно 13-01",
        "каждые 121 месяц 5", "каждые 2 месяца", "ежегодно 2026-03-15", "ежемесячно пятого"})
    void rejectsBadRecurrence(String text) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> RuFormats.parseRecurrence(text));
        assertTrue(e.getMessage().chars().anyMatch(c -> c >= 'а' && c <= 'я'), "сообщение по-русски: " + e.getMessage());
    }

    @Test
    void everyRecurrenceRoundTripsThroughCanonicalText() {
        List<Recurrence> all = new ArrayList<>();
        for (int day : new int[] {1, 2, 15, 28, 29, 30, 31}) {
            for (int every : new int[] {1, 2, 3, 4, 5, 11, 12, 14, 21, 22, 25, 101, 120}) {
                all.add(new Recurrence.Monthly(day, every));
            }
        }
        for (DayOfWeek weekday : DayOfWeek.values()) {
            for (int every : new int[] {1, 2, 3, 4, 5, 11, 21, 22, 52}) {
                all.add(new Recurrence.Weekly(weekday, every));
            }
        }
        for (int days : new int[] {1, 2, 3, 4, 5, 11, 12, 21, 22, 100, 101, 111, 365, 366}) {
            all.add(new Recurrence.EveryNDays(days));
        }
        for (MonthDay md : new MonthDay[] {MonthDay.of(1, 1), MonthDay.of(2, 29), MonthDay.of(3, 15), MonthDay.of(12, 31)}) {
            all.add(new Recurrence.Yearly(md));
        }
        for (Recurrence r : all) {
            String text = RuFormats.formatRecurrence(r);
            assertEquals(r, RuFormats.parseRecurrence(text), text);
            assertEquals(r, RuFormats.parseRecurrence(text.toUpperCase()), "верхний регистр: " + text);
        }
    }

    @ParameterizedTest(name = "«{0}» = {1}")
    @CsvSource({"доход, INCOME", "ДОХОД, INCOME", "+, INCOME", "income, INCOME", "расход, EXPENSE", "-, EXPENSE",
        "−, EXPENSE", "Expense, EXPENSE", "' Расход ', EXPENSE"})
    void parsesKind(String text, Kind expected) {
        assertEquals(expected, RuFormats.parseKind(text));
    }

    @Test
    void kindErrorsAndFormat() {
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseKind("прибыль"));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseKind(""));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseKind(null));
        assertEquals("доход", RuFormats.formatKind(Kind.INCOME));
        assertEquals("расход", RuFormats.formatKind(Kind.EXPENSE));
    }

    @Test
    void weekendPolicy() {
        assertEquals(WeekendPolicy.NONE, RuFormats.parseWeekendPolicy("нет"));
        assertEquals(WeekendPolicy.NONE, RuFormats.parseWeekendPolicy(""));
        assertEquals(WeekendPolicy.NONE, RuFormats.parseWeekendPolicy("-"));
        assertEquals(WeekendPolicy.NONE, RuFormats.parseWeekendPolicy(null));
        assertEquals(WeekendPolicy.PREVIOUS_BUSINESS_DAY, RuFormats.parseWeekendPolicy("Раньше"));
        assertEquals(WeekendPolicy.NEXT_BUSINESS_DAY, RuFormats.parseWeekendPolicy(" ПОЗЖЕ "));
        // Синонимы из грамматики формата, которые пишут вручную (plan.weekend.*.alias).
        assertEquals(WeekendPolicy.NONE, RuFormats.parseWeekendPolicy("Не сдвигать"));
        assertEquals(WeekendPolicy.PREVIOUS_BUSINESS_DAY, RuFormats.parseWeekendPolicy("на пятницу"));
        assertEquals(WeekendPolicy.NEXT_BUSINESS_DAY, RuFormats.parseWeekendPolicy("НА  ПОНЕДЕЛЬНИК"));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseWeekendPolicy("вчера"));
        for (WeekendPolicy p : WeekendPolicy.values()) {
            assertEquals(p, RuFormats.parseWeekendPolicy(RuFormats.formatWeekendPolicy(p)));
        }
    }

    @ParameterizedTest(name = "«{0}» = {1}")
    @CsvSource({"да, true", "ДА, true", "yes, true", "True, true", "1, true", "нет, false", "Нет, false", "no, false",
        "FALSE, false", "0, false"})
    void parsesBoolean(String text, boolean expected) {
        assertEquals(expected, RuFormats.parseBoolean(text));
    }

    @Test
    void booleanErrorsAndFormat() {
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseBoolean("может быть"));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseBoolean(""));
        assertEquals("да", RuFormats.formatBoolean(true));
        assertEquals("нет", RuFormats.formatBoolean(false));
    }

    @Test
    void adjustmentActions() {
        assertEquals(RuFormats.ActionType.CHANGE_AMOUNT, RuFormats.parseActionType("изменить"));
        assertEquals(RuFormats.ActionType.CHANGE_AMOUNT, RuFormats.parseActionType("Изменение"));
        assertEquals(RuFormats.ActionType.MOVE_DATE, RuFormats.parseActionType("перенести"));
        assertEquals(RuFormats.ActionType.MOVE_DATE, RuFormats.parseActionType("ПЕРЕНОС"));
        assertEquals(RuFormats.ActionType.SKIP, RuFormats.parseActionType("пропустить"));
        assertEquals(RuFormats.ActionType.SKIP, RuFormats.parseActionType("пропуск"));
        assertEquals(RuFormats.ActionType.REPLACE, RuFormats.parseActionType("заменить"));
        assertEquals(RuFormats.ActionType.REPLACE, RuFormats.parseActionType("замена"));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseActionType("удалить"));

        Money m = Money.ofMajor(5);
        LocalDate d = LocalDate.of(2026, 10, 1);
        assertEquals(new Adjustment.Skip(), RuFormats.buildAction(RuFormats.ActionType.SKIP, m, d));
        assertEquals(new Adjustment.ChangeAmount(m), RuFormats.buildAction(RuFormats.ActionType.CHANGE_AMOUNT, m, null));
        assertEquals(new Adjustment.MoveDate(d), RuFormats.buildAction(RuFormats.ActionType.MOVE_DATE, null, d));
        assertEquals(new Adjustment.Replace(m, d), RuFormats.buildAction(RuFormats.ActionType.REPLACE, m, d));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.buildAction(RuFormats.ActionType.CHANGE_AMOUNT, null, d));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.buildAction(RuFormats.ActionType.MOVE_DATE, m, null));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.buildAction(RuFormats.ActionType.REPLACE, m, null));

        for (Adjustment.Action action : List.of(new Adjustment.Skip(), new Adjustment.ChangeAmount(m),
                new Adjustment.MoveDate(d), new Adjustment.Replace(m, d))) {
            RuFormats.ActionType type = RuFormats.parseActionType(RuFormats.formatAction(action));
            assertEquals(RuFormats.actionTypeOf(action), type);
            assertEquals(action, RuFormats.buildAction(type, action.newAmount().orElse(null), action.newDate().orElse(null)));
        }
    }

    static Stream<Arguments> horizonCases() {
        return Stream.of(
                Arguments.of("12 месяцев", new Horizon.Months(12)),
                Arguments.of("1 месяц", new Horizon.Months(1)),
                Arguments.of("3 месяца", new Horizon.Months(3)),
                Arguments.of("6 мес", new Horizon.Months(6)),
                Arguments.of("6мес.", new Horizon.Months(6)),
                Arguments.of("600 месяцев", new Horizon.Months(600)),
                Arguments.of("1 год", new Horizon.Years(1)),
                Arguments.of("2 года", new Horizon.Years(2)),
                Arguments.of("50 лет", new Horizon.Years(50)),
                Arguments.of("до 2027-12-31", new Horizon.Until(LocalDate.of(2027, 12, 31))),
                Arguments.of("До 31.12.2027", new Horizon.Until(LocalDate.of(2027, 12, 31))));
    }

    @ParameterizedTest(name = "«{0}»")
    @MethodSource("horizonCases")
    void parsesHorizon(String text, Horizon expected) {
        assertEquals(expected, RuFormats.parseHorizon(text));
    }

    @ParameterizedTest(name = "«{0}» отклоняется")
    @NullAndEmptySource
    @ValueSource(strings = {"0 месяцев", "601 месяц", "51 год", "навсегда", "12", "до завтра", "до 2027-02-30"})
    void rejectsBadHorizon(String text) {
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseHorizon(text));
    }

    @Test
    void horizonRoundTrip() {
        for (int m = 1; m <= Horizon.MAX_MONTHS; m++) {
            Horizon h = new Horizon.Months(m);
            assertEquals(h, RuFormats.parseHorizon(RuFormats.formatHorizon(h)));
        }
        for (int y = 1; y <= 50; y++) {
            Horizon h = new Horizon.Years(y);
            assertEquals(h, RuFormats.parseHorizon(RuFormats.formatHorizon(h)));
        }
        Horizon until = new Horizon.Until(LocalDate.of(2030, 1, 1));
        assertEquals(until, RuFormats.parseHorizon(RuFormats.formatHorizon(until)));
    }

    @Test
    void dates() {
        LocalDate expected = LocalDate.of(2026, 9, 1);
        assertEquals(expected, RuFormats.parseDate("2026-09-01"));
        assertEquals(expected, RuFormats.parseDate("01.09.2026"));
        assertEquals(expected, RuFormats.parseDate("1.9.2026"));
        assertEquals(expected, RuFormats.parseDate(" 2026-9-1 "));
        assertEquals(expected, RuFormats.parseDate("01.09.2026 "));
        assertEquals(LocalDate.of(2028, 2, 29), RuFormats.parseDate("29.02.2028"));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseDate("31.02.2026"));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseDate("2026-02-30"));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseDate("2026/09/01"));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseDate(""));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseDate("-"));
        assertNull(RuFormats.parseOptionalDate("-"));
        assertNull(RuFormats.parseOptionalDate(""));
        assertNull(RuFormats.parseOptionalDate(null));
        assertEquals(expected, RuFormats.parseOptionalDate("2026-09-01"));
        assertEquals("2026-09-01", RuFormats.formatDate(expected));
        assertEquals("", RuFormats.formatDate(null));
    }

    @Test
    void money() {
        assertEquals(Money.ofMajor(80_000), RuFormats.parseMoney("80 000,00"));
        assertEquals(Money.ofMajor(80_000), RuFormats.parseMoney("80 000"));
        assertEquals(Money.ofMinor(-150), RuFormats.parseMoney("-1,5"));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseMoney(""));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseMoney("-"));
        assertThrows(IllegalArgumentException.class, () -> RuFormats.parseMoney("8о 000"));
        assertTrue(RuFormats.hasExplicitSign(" -5"));
        assertTrue(RuFormats.hasExplicitSign("+5"));
        assertTrue(RuFormats.hasExplicitSign("−5"));
        assertFalse(RuFormats.hasExplicitSign("5"));
        assertEquals("12 345,67", RuFormats.formatMoney(Money.ofMinor(1_234_567)));
    }

    @Test
    void normalizeAndEmptyValues() {
        assertEquals("елка еж", RuFormats.normalize("  ЁЛКА \t Ёж  "));
        assertEquals("", RuFormats.normalize(null));
        for (String empty : new String[] {null, "", "  ", "-", " - "}) {
            assertTrue(RuFormats.isEmptyValue(empty), "«" + empty + "»");
        }
        assertFalse(RuFormats.isEmptyValue("0"));
        assertFalse(RuFormats.isEmptyValue("--"));
        // Решение 2026-09-14: пустое значение пишется только дефисом-минусом, длинное и среднее тире особым случаем не являются.
        assertFalse(RuFormats.isEmptyValue(String.valueOf(ru.cashprediction.core.format.DashFreeOutput.EM_DASH)));
        assertFalse(RuFormats.isEmptyValue(String.valueOf(ru.cashprediction.core.format.DashFreeOutput.EN_DASH)));
    }
}
