package ru.cashprediction.core.util;

import java.time.DayOfWeek;
import java.time.Month;
import java.util.Locale;

/**
 * Русские тексты, которые нельзя получить из локали JVM надёжно: склонения числительных,
 * короткие названия дней недели в фиксированной форме, названия месяцев.
 *
 * <p>Почему не {@code DateTimeFormatter} с локалью ru: в урезанной jlink-сборке без {@code jdk.localedata}
 * названия молча становятся английскими, а файлы CashMemory должны выглядеть одинаково везде.
 * Поэтому тексты, попадающие в файлы, заданы здесь явно.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class RuText {

    private static final String[] WEEKDAY_SHORT = {"пн", "вт", "ср", "чт", "пт", "сб", "вс"};
    private static final String[] WEEKDAY_FULL = {
        "понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"
    };
    private static final String[] MONTH_NOMINATIVE = {
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь"
    };
    private static final String[] MONTH_GENITIVE = {
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря"
    };

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

    /** @return короткое название дня недели: пн, вт, ср, чт, пт, сб, вс */
    public static String weekdayShort(DayOfWeek day) {
        return WEEKDAY_SHORT[day.getValue() - 1];
    }

    /** @return полное название дня недели в нижнем регистре: понедельник ... */
    public static String weekdayFull(DayOfWeek day) {
        return WEEKDAY_FULL[day.getValue() - 1];
    }

    /**
     * Распознаёт день недели по короткому или полному названию (регистр не важен).
     * Понимает также английские сокращения mon..sun, чтобы ручные правки файлов не ломались.
     *
     * @param text название дня
     * @return день недели или {@code null}, если не распознан
     */
    public static DayOfWeek parseWeekday(String text) {
        if (text == null) {
            return null;
        }
        String t = text.strip().toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (int i = 0; i < 7; i++) {
            if (t.equals(WEEKDAY_SHORT[i]) || t.equals(WEEKDAY_FULL[i].replace('ё', 'е'))) {
                return DayOfWeek.of(i + 1);
            }
        }
        // Частые варианты: «субботу», «среду», «пятницу» (винительный падеж) и английские сокращения.
        return switch (t) {
            case "среду" -> DayOfWeek.WEDNESDAY;
            case "пятницу" -> DayOfWeek.FRIDAY;
            case "субботу" -> DayOfWeek.SATURDAY;
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

    /** @return название месяца с заглавной буквы в именительном падеже: Октябрь */
    public static String monthNominative(Month month) {
        return MONTH_NOMINATIVE[month.getValue() - 1];
    }

    /** @return название месяца в родительном падеже: октября */
    public static String monthGenitive(Month month) {
        return MONTH_GENITIVE[month.getValue() - 1];
    }
}
