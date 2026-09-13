package ru.cashprediction.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.markdown.MarkdownFormat;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurrenceKind;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.text.CoreModuleDir;
import ru.cashprediction.core.text.JavaSourceScanner;
import ru.cashprediction.core.text.Texts;

/**
 * Тексты слоёв модели, дат и Markdown в общем каталоге (этап S0.5, области {@code model}, {@code dates},
 * {@code markdown}).
 *
 * <p>Проверяет, что каждый ключ этих областей используется литералом в основном коде ядра (для областей S0.5 проверка
 * строгая), и что публичные методы, которые вызывают прежние клиенты ({@code Kind.title()},
 * {@code RuText.monthNominative}, {@code DateFormats.monthTitle} и другие), возвращают те же тексты, что и до переноса.</p>
 */
class DomainTextsTest {

    /** Области каталога, созданные или заполненные группой A этапа S0.5. */
    private static final List<String> AREAS = List.of("model", "dates", "markdown");

    @Test
    void areasAreRegisteredAndLoaded() {
        for (String area : AREAS) {
            assertTrue(Texts.AREAS.contains(area), area);
            assertEquals(Texts.fileName(area), Texts.catalog().loadedFiles().get(area), area);
        }
    }

    @Test
    void everyKeyOfTheseAreasIsUsedByLiteral() {
        Set<String> used = new LinkedHashSet<>();
        Path coreMain = CoreModuleDir.resolve("src/main/java");
        for (JavaSourceScanner.Literal literal : JavaSourceScanner.literals(coreMain)) {
            used.add(literal.raw());
        }
        List<String> unused = Texts.catalog().keys().stream()
                .filter(key -> AREAS.contains(Texts.catalog().area(key).orElseThrow()))
                .filter(key -> !used.contains(key))
                .toList();
        assertEquals(List.of(), unused, "ключи областей model/dates/markdown, на которые нет ссылок");
    }

    @Test
    void legacyTitlesKeepTheirTexts() {
        assertEquals("Доход", Kind.INCOME.title());
        assertEquals("Расход", Kind.EXPENSE.title());
        assertEquals("доход", Kind.INCOME.label());
        assertEquals("На пятницу (раньше)", WeekendPolicy.PREVIOUS_BUSINESS_DAY.title());
        assertEquals("позже", WeekendPolicy.NEXT_BUSINESS_DAY.label());
        assertEquals("Каждые N дней", RecurrenceKind.EVERY_N_DAYS.toString());
        assertEquals("Ежемесячно / каждые N месяцев", RecurrenceKind.MONTHLY.title());
        assertEquals("Октябрь 2026", DateFormats.monthTitle(YearMonth.of(2026, 10)));
        assertEquals("марта", RuText.monthGenitive(Month.MARCH));
        assertEquals("Май", RuText.monthNominative(Month.MAY));
        assertEquals("пн", RuText.weekdayShort(DayOfWeek.MONDAY));
        assertEquals("среда", RuText.weekdayFull(DayOfWeek.WEDNESDAY));
        assertEquals("2 года", new Horizon.Years(2).label());
        assertEquals("до 2027-01-01", new Horizon.Until(LocalDate.of(2027, 1, 1)).label());
    }

    @Test
    void parseWeekdayUnderstandsFormatWordsOnly() {
        assertEquals(DayOfWeek.WEDNESDAY, RuText.parseWeekday("Среду"));
        assertEquals(DayOfWeek.FRIDAY, RuText.parseWeekday(" пятницу "));
        assertEquals(DayOfWeek.SATURDAY, RuText.parseWeekday("субботу"));
        assertEquals(DayOfWeek.SUNDAY, RuText.parseWeekday("ВС"));
        assertEquals(DayOfWeek.THURSDAY, RuText.parseWeekday("thu"));
        assertEquals(null, RuText.parseWeekday("пятницам"));
        assertEquals(null, RuText.parseWeekday(null));
    }

    @Test
    void modelMessagesKeepTheirTexts() {
        assertEquals("Сумма не указана", message(() -> Money.parse(" ")));
        assertEquals("Некорректная сумма: «12x»", message(() -> Money.parse("12x")));
        assertEquals("Слишком большая сумма: «99999999999999999999»", message(() -> Money.parse("99999999999999999999")));
        assertEquals("Сумма вне допустимого диапазона", message(() -> new Money(Long.MIN_VALUE)));
        assertEquals("Горизонт должен быть от 1 до 600 месяцев", message(() -> new Horizon.Months(601)));
        assertEquals("Горизонт должен быть от 1 до 50 лет", message(() -> new Horizon.Years(0)));
        assertEquals("Некорректный идентификатор правила: «a|b»", message(() -> new RuleId("a|b")));
        assertEquals("Некорректный идентификатор операции: «»", message(() -> new TxId(" ")));
        assertEquals("Некорректный идентификатор события: «r1»", message(() -> OccurrenceKey.parseRowId("r1")));
        assertEquals("Дата не указана", message(() -> DateFormats.parse("")));
        assertEquals("Некорректная дата: «31.13.2026» (ожидается ГГГГ-ММ-ДД или ДД.ММ.ГГГГ)",
                message(() -> DateFormats.parse("31.13.2026")));
        assertEquals(Texts.get("money.error.invalid", "12x"), message(() -> Money.parse("12x")));
    }

    /** Пределы правил повтора подставляются из констант Recurrence, а вывод прежний буква в букву. */
    @Test
    void recurrenceLimitsComeFromConstants() {
        assertEquals("День месяца должен быть от 1 до 31", message(() -> new Recurrence.Monthly(32, 1)));
        assertEquals("Период должен быть от 1 до 120 месяцев", message(() -> new Recurrence.Monthly(5, 121)));
        assertEquals("Период должен быть от 1 до 52 недель", message(() -> new Recurrence.Weekly(DayOfWeek.MONDAY, 0)));
        assertEquals("Период должен быть от 1 до 366 дней", message(() -> new Recurrence.EveryNDays(367)));
        assertEquals(Texts.get("recurrence.error.everyDays", Recurrence.MAX_EVERY_DAYS),
                message(() -> new Recurrence.EveryNDays(Recurrence.MAX_EVERY_DAYS + 1)));
        // На границе ещё допустимо.
        assertEquals(Recurrence.MAX_DAY_OF_MONTH, new Recurrence.Monthly(Recurrence.MAX_DAY_OF_MONTH, 1).dayOfMonth());
        assertEquals(Recurrence.MAX_EVERY_MONTHS, new Recurrence.Monthly(1, Recurrence.MAX_EVERY_MONTHS).everyMonths());
        assertEquals(Recurrence.MAX_EVERY_WEEKS, new Recurrence.Weekly(DayOfWeek.MONDAY, Recurrence.MAX_EVERY_WEEKS).everyWeeks());
        assertEquals(Recurrence.MAX_EVERY_DAYS, new Recurrence.EveryNDays(Recurrence.MAX_EVERY_DAYS).days());
    }

    /** Разделение разрядов для пределов в сообщениях: обычный пробел, как в прежних текстах. */
    @Test
    void groupDigitsUsesPlainSpaces() {
        assertEquals("200 000", RuText.groupDigits(200_000));
        assertEquals("0", RuText.groupDigits(0));
        assertEquals("999", RuText.groupDigits(999));
        assertEquals("1 000", RuText.groupDigits(1_000));
        assertEquals("12 345 678", RuText.groupDigits(12_345_678));
        assertEquals("-1 234 567", RuText.groupDigits(-1_234_567));
        assertEquals("-100", RuText.groupDigits(-100));
        assertEquals("-9 223 372 036 854 775 808", RuText.groupDigits(Long.MIN_VALUE));
    }

    @Test
    void developerMessagesAreLatin() {
        String text = message(() -> Money.ofMajor(10).divideCeilToMajor(0));
        assertTrue(text.chars().noneMatch(c -> c >= 0x0400 && c <= 0x04FF), text);
    }

    @Test
    void formatConstantsKeepTheirSpelling() {
        assertEquals("# План: ", MarkdownFormat.TITLE_PREFIX);
        assertEquals("(не разобрано, строка ", MarkdownFormat.UNPARSED_PREFIX);
        assertEquals("Новая сумма", MarkdownFormat.COL_NEW_AMOUNT);
    }

    private static String message(Runnable action) {
        return assertThrows(IllegalArgumentException.class, action::run).getMessage();
    }
}
