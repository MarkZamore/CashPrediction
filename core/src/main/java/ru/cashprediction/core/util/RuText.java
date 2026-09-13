package ru.cashprediction.core.util;

import java.time.DayOfWeek;
import java.time.Month;
import java.util.Locale;
import ru.cashprediction.core.format.FormatWords;
import ru.cashprediction.core.text.Texts;

/**
 * Русские тексты, которые нельзя получить из локали JVM надёжно: склонения числительных,
 * короткие названия дней недели в фиксированной форме, названия месяцев.
 *
 * <p>Почему не {@code DateTimeFormatter} с локалью ru: в урезанной jlink-сборке без {@code jdk.localedata}
 * названия молча становятся английскими, а тексты должны выглядеть одинаково везде.</p>
 *
 * <p><b>Откуда тексты (решение L13).</b> Названия дней и месяцев для интерфейса ({@link #weekdayShort},
 * {@link #weekdayFull}, {@link #monthNominative}, {@link #monthGenitive}) берутся из каталога текстов
 * ({@code dates_ru.properties} через {@link Texts}). Разбор дня недели из файла плана ({@link #parseWeekday})
 * сравнивает со словами формата ({@link FormatWords}, {@code common.weekday.*}): файл должен читаться при любом языке
 * интерфейса. Сейчас значения совпадают, но источники независимы.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class RuText {

    private RuText() {
    }

    /**
     * Выбирает форму слова по числу: 1 месяц, 2 месяца, 5 месяцев, 11 месяцев, 21 месяц.
     *
     * @param n    число
     * @param one  форма для 1, 21, 31 ...
     * @param few  форма для 2-4, 22-24 ...
     * @param many форма для 0, 5-20, 25-30 ...
     * @return подходящая форма слова
     */
    public static String plural(long n, String one, String few, String many) {
        long abs = Math.abs(n);
        long mod100 = abs % 100;
        long mod10 = abs % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return many;
        }
        if (mod10 == 1) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return few;
        }
        return many;
    }

    /**
     * Число со словом в нужной форме: {@code "12 месяцев"}.
     *
     * @param n    число
     * @param one  форма для 1
     * @param few  форма для 2-4
     * @param many форма для 5+
     * @return строка «число форма»
     */
    public static String count(long n, String one, String few, String many) {
        return n + " " + plural(n, one, few, many);
    }

    /**
     * Целое число с разделением разрядов обычным пробелом: {@code 200000 → "200 000"}.
     *
     * <p>Нужен сообщениям, в которые подставляется предел из константы кода (например, наибольшее число строк
     * прогноза): так текст в каталоге не повторяет число, а вывод остаётся прежним буква в букву.</p>
     *
     * @param n число
     * @return например «-1 234 567», «999»
     */
    public static String groupDigits(long n) {
        String digits = Long.toString(n);
        int start = n < 0 ? 1 : 0;
        StringBuilder sb = new StringBuilder(digits.length() + digits.length() / 3);
        sb.append(digits, 0, start);
        for (int i = start; i < digits.length(); i++) {
            if (i > start && (digits.length() - i) % 3 == 0) {
                sb.append(' ');
            }
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    /**
     * Короткое название дня недели для интерфейса (каталог текстов, {@code weekday.short.N}).
     *
     * @param day день недели
     * @return пн, вт, ср, чт, пт, сб, вс
     */
    public static String weekdayShort(DayOfWeek day) {
        // Ключи литералами, а не "weekday.short." + номер: проверка каталога видит каждый используемый ключ.
        return switch (day) {
            case MONDAY -> Texts.get("weekday.short.1");
            case TUESDAY -> Texts.get("weekday.short.2");
            case WEDNESDAY -> Texts.get("weekday.short.3");
            case THURSDAY -> Texts.get("weekday.short.4");
            case FRIDAY -> Texts.get("weekday.short.5");
            case SATURDAY -> Texts.get("weekday.short.6");
            case SUNDAY -> Texts.get("weekday.short.7");
        };
    }

    /**
     * Полное название дня недели в нижнем регистре для интерфейса (каталог текстов, {@code weekday.full.N}).
     *
     * @param day день недели
     * @return понедельник ... воскресенье
     */
    public static String weekdayFull(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> Texts.get("weekday.full.1");
            case TUESDAY -> Texts.get("weekday.full.2");
            case WEDNESDAY -> Texts.get("weekday.full.3");
            case THURSDAY -> Texts.get("weekday.full.4");
            case FRIDAY -> Texts.get("weekday.full.5");
            case SATURDAY -> Texts.get("weekday.full.6");
            case SUNDAY -> Texts.get("weekday.full.7");
        };
    }

    /**
     * Распознаёт день недели по короткому или полному названию (регистр и «ё/е» не важны).
     * Понимает также винительный падеж («в среду») и английские сокращения mon..sun, чтобы ручные правки файлов
     * не ломались. Слова берутся из грамматики формата ({@link FormatWords}), а не из текстов интерфейса.
     *
     * @param text название дня
     * @return день недели или {@code null}, если не распознан
     */
    public static DayOfWeek parseWeekday(String text) {
        if (text == null) {
            return null;
        }
        String t = foldYo(text.strip().toLowerCase(Locale.ROOT));
        for (DayOfWeek day : DayOfWeek.values()) {
            if (t.equals(foldYo(FormatWords.weekdayShort(day))) || t.equals(foldYo(FormatWords.weekdayFull(day)))) {
                return day;
            }
        }
        // Частые варианты: «субботу», «среду», «пятницу» (винительный падеж).
        if (t.equals(foldYo(FormatWords.get("common.weekday.accusative.3")))) {
            return DayOfWeek.WEDNESDAY;
        }
        if (t.equals(foldYo(FormatWords.get("common.weekday.accusative.5")))) {
            return DayOfWeek.FRIDAY;
        }
        if (t.equals(foldYo(FormatWords.get("common.weekday.accusative.6")))) {
            return DayOfWeek.SATURDAY;
        }
        // Английские сокращения и названия: латиница, не зависит от языка интерфейса.
        return switch (t) {
            case "mon", "monday" -> DayOfWeek.MONDAY;
            case "tue", "tuesday" -> DayOfWeek.TUESDAY;
            case "wed", "wednesday" -> DayOfWeek.WEDNESDAY;
            case "thu", "thursday" -> DayOfWeek.THURSDAY;
            case "fri", "friday" -> DayOfWeek.FRIDAY;
            case "sat", "saturday" -> DayOfWeek.SATURDAY;
            case "sun", "sunday" -> DayOfWeek.SUNDAY;
            default -> null;
        };
    }

    /**
     * Название месяца для интерфейса (каталог текстов, {@code month.nominative.N}).
     *
     * @param month месяц
     * @return название с заглавной буквы в именительном падеже: Октябрь
     */
    public static String monthNominative(Month month) {
        return switch (month) {
            case JANUARY -> Texts.get("month.nominative.1");
            case FEBRUARY -> Texts.get("month.nominative.2");
            case MARCH -> Texts.get("month.nominative.3");
            case APRIL -> Texts.get("month.nominative.4");
            case MAY -> Texts.get("month.nominative.5");
            case JUNE -> Texts.get("month.nominative.6");
            case JULY -> Texts.get("month.nominative.7");
            case AUGUST -> Texts.get("month.nominative.8");
            case SEPTEMBER -> Texts.get("month.nominative.9");
            case OCTOBER -> Texts.get("month.nominative.10");
            case NOVEMBER -> Texts.get("month.nominative.11");
            case DECEMBER -> Texts.get("month.nominative.12");
        };
    }

    /**
     * Название месяца в родительном падеже для интерфейса (каталог текстов, {@code month.genitive.N}).
     *
     * @param month месяц
     * @return например «октября»
     */
    public static String monthGenitive(Month month) {
        return switch (month) {
            case JANUARY -> Texts.get("month.genitive.1");
            case FEBRUARY -> Texts.get("month.genitive.2");
            case MARCH -> Texts.get("month.genitive.3");
            case APRIL -> Texts.get("month.genitive.4");
            case MAY -> Texts.get("month.genitive.5");
            case JUNE -> Texts.get("month.genitive.6");
            case JULY -> Texts.get("month.genitive.7");
            case AUGUST -> Texts.get("month.genitive.8");
            case SEPTEMBER -> Texts.get("month.genitive.9");
            case OCTOBER -> Texts.get("month.genitive.10");
            case NOVEMBER -> Texts.get("month.genitive.11");
            case DECEMBER -> Texts.get("month.genitive.12");
        };
    }

    /** Заменяет «ё» на «е» (буквы — из грамматики формата), чтобы ручная правка файла не зависела от этой буквы. */
    private static String foldYo(String text) {
        return text.replace(FormatWords.get("common.char.yo"), FormatWords.get("common.char.ye"));
    }
}
