package ru.cashprediction.core.app;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Часы приложения: «сегодня» для прогноза и текущий момент для статусов и снимков.
 *
 * <p>Аргумент {@code --today 2026-09-13} (свойство {@code cashprediction.today}) фиксирует только дату: прогноз,
 * таблица и сценарии самотеста становятся детерминированными, а время в строке состояния остаётся настоящим
 * (дампы заменяют его на {@code <time>}, см. {@code DumpNormalizer}). Все компоненты ядра берут дату только
 * отсюда, а не из {@link LocalDate#now()}.</p>
 *
 * <p>Класс неизменяем и потокобезопасен.</p>
 */
public final class AppClock {

    private final Clock clock;
    private final LocalDate fixedToday;

    private AppClock(Clock clock, LocalDate fixedToday) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.fixedToday = fixedToday;
    }

    /**
     * Системные часы в часовом поясе по умолчанию.
     *
     * @return часы без фиксированной даты
     */
    public static AppClock system() {
        return new AppClock(Clock.systemDefaultZone(), null);
    }

    /**
     * Системные часы с зафиксированной датой «сегодня».
     *
     * @param today дата, которую программа считает сегодняшней
     * @return часы
     */
    public static AppClock fixedToday(LocalDate today) {
        return new AppClock(Clock.systemDefaultZone(), Objects.requireNonNull(today, "today"));
    }

    /**
     * Произвольные часы (для тестов с виртуальным временем).
     *
     * @param clock            источник времени
     * @param fixedTodayOrNull зафиксированная дата или {@code null}, чтобы брать дату из {@code clock}
     * @return часы
     */
    public static AppClock of(Clock clock, LocalDate fixedTodayOrNull) {
        return new AppClock(clock, fixedTodayOrNull);
    }

    /** @return сегодняшняя дата: зафиксированная или по часам */
    public LocalDate today() {
        return fixedToday != null ? fixedToday : LocalDate.now(clock);
    }

    /** @return текущий момент по часам (дата не подменяется) */
    public Instant now() {
        return clock.instant();
    }

    /** @return текущие местные дата и время по часам */
    public LocalDateTime localNow() {
        return LocalDateTime.now(clock);
    }

    /** @return часовой пояс часов */
    public ZoneId zone() {
        return clock.getZone();
    }

    /** @return зафиксирована ли дата «сегодня» */
    public boolean isTodayFixed() {
        return fixedToday != null;
    }

    /** @return исходные часы {@link Clock} (для {@code SessionRecorder} и планировщиков) */
    public Clock clock() {
        return clock;
    }
}
