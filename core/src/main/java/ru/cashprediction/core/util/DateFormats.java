package ru.cashprediction.core.util;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Форматы дат приложения.
 *
 * <ul>
 *   <li>В файлах CashMemory и в снимках сессии всегда ISO: {@code 2026-10-05}. Такой текст однозначен,
 *       сортируется как строка и не зависит от локали.</li>
 *   <li>В интерфейсе: {@code 05.10.2026}.</li>
 *   <li>При чтении принимаются оба варианта, чтобы ручная правка файла в привычном формате не ломала разбор.</li>
 * </ul>
 *
 * <p>Класс без состояния, потокобезопасен ({@link DateTimeFormatter} неизменяем).</p>
 */
public final class DateFormats {

    /** Формат для файлов и снимков: 2026-10-05. */
    public static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Формат для интерфейса: 05.10.2026. */
    public static final DateTimeFormatter RU = DateTimeFormatter.ofPattern("dd.MM.uuuu");

    private DateFormats() {
    }

    /** @return дата в ISO-форме для файлов */
    public static String iso(LocalDate date) {
        return date == null ? "" : ISO.format(date);
    }

    /** @return дата в русской форме для интерфейса */
    public static String ru(LocalDate date) {
        return date == null ? "" : RU.format(date);
    }

    /**
     * Разбирает дату в форме {@code ГГГГ-ММ-ДД} или {@code ДД.ММ.ГГГГ}.
     *
     * @param text исходный текст
     * @return дата
     * @throws IllegalArgumentException если текст не является датой ни в одном из форматов
     */
    public static LocalDate parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Дата не указана");
        }
        String t = text.strip();
        try {
            return t.contains(".") ? LocalDate.parse(t, RU) : LocalDate.parse(t, ISO);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Некорректная дата: «" + t + "» (ожидается ГГГГ-ММ-ДД или ДД.ММ.ГГГГ)", e);
        }
    }

    /**
     * Название месяца и год для заголовков групп таблицы: «Октябрь 2026».
     *
     * @param month месяц года
     * @return подпись
     */
    public static String monthTitle(YearMonth month) {
        return RuText.monthNominative(month.getMonth()) + " " + month.getYear();
    }
}
