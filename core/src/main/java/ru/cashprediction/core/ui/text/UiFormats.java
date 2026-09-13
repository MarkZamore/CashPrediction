package ru.cashprediction.core.ui.text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Форматы чисел и дат интерфейса (спецификация v2, раздел «Форматы»), общие для трёх клиентов.
 *
 * <p>Клиенты сами ничего не форматируют: ядро отдаёт готовые строки. Суммы с копейками — {@link Money#format()}
 * («80 000,00»), здесь — целые и сокращённые суммы, горизонт, даты и время. ISO-дата в интерфейсе запрещена,
 * поэтому все даты выводятся как «05.10.2026».</p>
 *
 * <p>Округление до целых — HALF_UP по модулю (0,50 → 1, −0,50 → −1). Отрицательный ноль не выводится:
 * −0,40 даёт «0».</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class UiFormats {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final BigDecimal TEN_THOUSAND = BigDecimal.valueOf(10_000);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm");
    private static final DateTimeFormatter DATE_TIME_SECONDS = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss");

    private UiFormats() {
    }

    /**
     * Целая сумма с валютой: «177 000 ₽».
     *
     * @param money    сумма
     * @param currency обозначение валюты; пустое или {@code null} — без валюты
     * @return целые единицы с группировкой пробелом
     */
    public static String whole(Money money, String currency) {
        String digits = groupedWhole(money);
        return currency == null || currency.isBlank() ? digits : digits + " " + currency.strip();
    }

    /**
     * Целая сумма со знаком: «+46 654», «-1 200»; ноль без знака.
     *
     * @param money сумма
     * @return текст со знаком
     */
    public static String wholeSigned(Money money) {
        String digits = groupedWhole(money);
        return digits.startsWith("-") || digits.equals("0") ? digits : "+" + digits;
    }

    /**
     * Сокращённая сумма для подписей карточек: ≥ 1 000 000 → «1,5 млн» (одна цифра после запятой, нулевая дробь
     * опускается: «2 млн»); ≥ 10 000 → «896 тыс»; иначе целая сумма «950».
     *
     * <p>Порог проверяется по уже округлённому значению: 999 600 даёт «1 млн», а не «1 000 тыс».</p>
     *
     * @param money сумма
     * @return сокращённый текст без валюты; отрицательная сумма — со знаком «-»
     */
    public static String compact(Money money) {
        BigDecimal major = BigDecimal.valueOf(Math.abs(money.minor()), 2);
        BigDecimal thousands = major.divide(THOUSAND, 0, RoundingMode.HALF_UP);
        String magnitude;
        if (major.compareTo(MILLION) >= 0 || thousands.compareTo(THOUSAND) >= 0) {
            BigDecimal millions = major.divide(MILLION, 1, RoundingMode.HALF_UP);
            long whole = millions.longValue();
            int tenth = millions.subtract(BigDecimal.valueOf(whole)).movePointRight(1).intValue();
            String number = group(whole) + (tenth == 0 ? "" : "," + tenth);
            magnitude = UiText.get("format.million", number);
        } else if (major.compareTo(TEN_THOUSAND) >= 0) {
            magnitude = UiText.get("format.thousand", group(thousands.longValue()));
        } else {
            magnitude = group(major.setScale(0, RoundingMode.HALF_UP).longValue());
        }
        return money.isNegative() && !magnitude.equals("0") ? "-" + magnitude : magnitude;
    }

    /**
     * Подпись горизонта: «12 месяцев», «2 года», «до 31.08.2027».
     *
     * @param horizon горизонт плана
     * @param start   дата начала плана; для горизонта «до даты» даёт фактический конец (не раньше начала),
     *                {@code null} — выводится записанная дата
     * @return подпись
     */
    public static String horizonLabel(Horizon horizon, LocalDate start) {
        Objects.requireNonNull(horizon, "horizon");
        return switch (horizon) {
            case Horizon.Months months -> Plurals.count(Plurals.MONTH, months.count());
            case Horizon.Years years -> Plurals.count(Plurals.YEAR, years.count());
            case Horizon.Until until -> UiText.get("format.until",
                    date(start == null ? until.end() : until.endDate(start)));
        };
    }

    /**
     * Число со словом в нужной форме: {@code count(5, "месяц", "месяца", "месяцев")} = «5 месяцев».
     *
     * @param n    число
     * @param one  форма для 1
     * @param few  форма для 2–4
     * @param many форма для 5–20
     * @return «число форма»
     */
    public static String count(long n, String one, String few, String many) {
        return RuText.count(n, one, few, many);
    }

    /**
     * Дата интерфейса: «05.10.2026».
     *
     * @param date дата или {@code null}
     * @return текст; для {@code null} — пустая строка
     */
    public static String date(LocalDate date) {
        return DateFormats.ru(date);
    }

    /**
     * Короткий день недели: «пн».
     *
     * @param date дата
     * @return два символа
     */
    public static String weekdayShort(LocalDate date) {
        return RuText.weekdayShort(date.getDayOfWeek());
    }

    /**
     * Полный день недели: «понедельник».
     *
     * @param date дата
     * @return название в нижнем регистре
     */
    public static String weekdayFull(LocalDate date) {
        return RuText.weekdayFull(date.getDayOfWeek());
    }

    /**
     * Дата с коротким днём недели впереди, как в предпросмотре правила: «пн, 05.10.2026».
     *
     * @param date дата
     * @return текст
     */
    public static String weekdayDate(LocalDate date) {
        return weekdayShort(date) + ", " + date(date);
    }

    /**
     * Дата с полным днём недели после неё, как в карточке дня: «05.10.2026, понедельник».
     *
     * @param date дата
     * @return текст
     */
    public static String dateWeekday(LocalDate date) {
        return date(date) + ", " + weekdayFull(date);
    }

    /**
     * Месяц и год: «Октябрь 2026».
     *
     * @param month месяц
     * @return текст
     */
    public static String monthTitle(YearMonth month) {
        return DateFormats.monthTitle(month);
    }

    /**
     * Время строки состояния: «10:15:30».
     *
     * @param time время
     * @return текст
     */
    public static String time(LocalTime time) {
        return TIME.format(time);
    }

    /**
     * Дата и время до минут: «13.09.2026 10:15».
     *
     * @param dateTime момент
     * @return текст
     */
    public static String dateTime(LocalDateTime dateTime) {
        return DATE_TIME.format(dateTime);
    }

    /**
     * Дата и время до секунд: «13.09.2026 10:15:30».
     *
     * @param dateTime момент
     * @return текст
     */
    public static String dateTimeSeconds(LocalDateTime dateTime) {
        return DATE_TIME_SECONDS.format(dateTime);
    }

    /** @return целые единицы суммы с группировкой и знаком «-» для отрицательных (без «-0») */
    private static String groupedWhole(Money money) {
        long whole = BigDecimal.valueOf(money.minor(), 2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return (whole < 0 ? "-" : "") + group(Math.abs(whole));
    }

    /** @return неотрицательное целое с пробелом перед каждой тройкой цифр справа */
    private static String group(long value) {
        String digits = Long.toString(value);
        StringBuilder sb = new StringBuilder(digits.length() + digits.length() / 3);
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }
}
