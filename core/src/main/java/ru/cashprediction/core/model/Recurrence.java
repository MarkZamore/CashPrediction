package ru.cashprediction.core.model;

import java.time.DayOfWeek;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import ru.cashprediction.core.util.RuText;

/**
 * Правило повтора регулярной операции.
 *
 * <p>Здесь только описание правила и его канонический текст для файла плана («ежемесячно 5»).
 * Генерация дат — в {@code recurrence.OccurrenceGenerator}, разбор текста — в {@code markdown.RuFormats}.</p>
 *
 * <p>Про «фазу»: правило «каждые 2 месяца» без даты начала неоднозначно (чётные или нечётные месяцы?).
 * Для таких правил {@link #needsAnchor()} возвращает {@code true}, и отсчёт идёт от поля «С» правила
 * или, если оно пусто, от даты начала плана.</p>
 */
public sealed interface Recurrence
        permits Recurrence.Monthly, Recurrence.Weekly, Recurrence.EveryNDays, Recurrence.Yearly {

    /** Формат дня года в файле: 03-15. */
    DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    /**
     * Канонический русский текст правила для файла и интерфейса.
     *
     * @return например «ежемесячно 5», «каждые 2 месяца 10», «еженедельно сб», «каждые 3 дня», «ежегодно 03-15»
     */
    String toRussian();

    /** @return вид повтора без параметров */
    RecurrenceKind kind();

    /**
     * Нужна ли правилу опорная дата, от которой отсчитывается фаза повтора.
     *
     * @return {@code true} для «каждые N месяцев/недель» при N &gt; 1 и для «каждые N дней»
     */
    boolean needsAnchor();

    /**
     * Раз в {@code everyMonths} месяцев в день {@code dayOfMonth}.
     * День 29-31 в коротких месяцах прижимается к последнему дню месяца: «31» означает «последний день».
     *
     * @param dayOfMonth  день месяца, 1..31
     * @param everyMonths период в месяцах, 1..120
     */
    record Monthly(int dayOfMonth, int everyMonths) implements Recurrence {
        /** Проверяет диапазоны. */
        public Monthly {
            if (dayOfMonth < 1 || dayOfMonth > 31) {
                throw new IllegalArgumentException("День месяца должен быть от 1 до 31");
            }
            if (everyMonths < 1 || everyMonths > 120) {
                throw new IllegalArgumentException("Период должен быть от 1 до 120 месяцев");
            }
        }

        @Override
        public String toRussian() {
            return everyMonths == 1
                    ? "ежемесячно " + dayOfMonth
                    : "каждые " + RuText.count(everyMonths, "месяц", "месяца", "месяцев") + " " + dayOfMonth;
        }

        @Override
        public RecurrenceKind kind() {
            return RecurrenceKind.MONTHLY;
        }

        @Override
        public boolean needsAnchor() {
            return everyMonths > 1;
        }
    }

    /**
     * Раз в {@code everyWeeks} недель в день недели {@code weekday}.
     *
     * @param weekday    день недели
     * @param everyWeeks период в неделях, 1..52
     */
    record Weekly(DayOfWeek weekday, int everyWeeks) implements Recurrence {
        /** Проверяет поля. */
        public Weekly {
            Objects.requireNonNull(weekday, "weekday");
            if (everyWeeks < 1 || everyWeeks > 52) {
                throw new IllegalArgumentException("Период должен быть от 1 до 52 недель");
            }
        }

        @Override
        public String toRussian() {
            return everyWeeks == 1
                    ? "еженедельно " + RuText.weekdayShort(weekday)
                    : "каждые " + RuText.count(everyWeeks, "неделю", "недели", "недель") + " " + RuText.weekdayShort(weekday);
        }

        @Override
        public RecurrenceKind kind() {
            return RecurrenceKind.WEEKLY;
        }

        @Override
        public boolean needsAnchor() {
            return everyWeeks > 1;
        }
    }

    /**
     * Каждые {@code days} дней, начиная с даты «С» правила (или даты начала плана).
     *
     * @param days период в днях, 1..366
     */
    record EveryNDays(int days) implements Recurrence {
        /** Проверяет диапазон. */
        public EveryNDays {
            if (days < 1 || days > 366) {
                throw new IllegalArgumentException("Период должен быть от 1 до 366 дней");
            }
        }

        @Override
        public String toRussian() {
            return days == 1 ? "ежедневно" : "каждые " + RuText.count(days, "день", "дня", "дней");
        }

        @Override
        public RecurrenceKind kind() {
            return RecurrenceKind.EVERY_N_DAYS;
        }

        @Override
        public boolean needsAnchor() {
            return days > 1;
        }
    }

    /**
     * Раз в год в день {@code monthDay}. 29 февраля в невисокосный год превращается в 28 февраля.
     *
     * @param monthDay месяц и день
     */
    record Yearly(MonthDay monthDay) implements Recurrence {
        /** Проверяет обязательное поле. */
        public Yearly {
            Objects.requireNonNull(monthDay, "monthDay");
        }

        @Override
        public String toRussian() {
            return "ежегодно " + MONTH_DAY.format(monthDay);
        }

        @Override
        public RecurrenceKind kind() {
            return RecurrenceKind.YEARLY;
        }

        @Override
        public boolean needsAnchor() {
            return false;
        }
    }
}
