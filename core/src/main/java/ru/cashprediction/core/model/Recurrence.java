package ru.cashprediction.core.model;

import java.time.DayOfWeek;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import ru.cashprediction.core.format.FormatWords;
import ru.cashprediction.core.text.Texts;
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

    /** Наибольший день месяца в {@link Monthly} (наименьший — 1); подставляется в сообщение об ошибке. */
    int MAX_DAY_OF_MONTH = 31;

    /** Наибольший период в месяцах в {@link Monthly} (наименьший — 1). */
    int MAX_EVERY_MONTHS = 120;

    /** Наибольший период в неделях в {@link Weekly} (наименьший — 1). */
    int MAX_EVERY_WEEKS = 52;

    /** Наибольший период в днях в {@link EveryNDays} (наименьший — 1). */
    int MAX_EVERY_DAYS = 366;

    /**
     * Канонический русский текст правила для файла и прежних клиентов.
     *
     * <p>Слова берутся из грамматики формата ({@link FormatWords}): этот текст пишет {@code PlanMarkdownWriter},
     * поэтому он не зависит от языка интерфейса.</p>
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
            if (dayOfMonth < 1 || dayOfMonth > MAX_DAY_OF_MONTH) {
                throw new IllegalArgumentException(Texts.get("recurrence.error.dayOfMonth", MAX_DAY_OF_MONTH));
            }
            if (everyMonths < 1 || everyMonths > MAX_EVERY_MONTHS) {
                throw new IllegalArgumentException(Texts.get("recurrence.error.everyMonths", MAX_EVERY_MONTHS));
            }
        }

        @Override
        public String toRussian() {
            return everyMonths == 1
                    ? FormatWords.get("plan.recurrence.monthly") + " " + dayOfMonth
                    : FormatWords.get("plan.recurrence.every") + " " + RuText.count(everyMonths,
                            FormatWords.get("plan.unit.month.one"), FormatWords.get("plan.unit.month.few"),
                            FormatWords.get("plan.unit.month.many")) + " " + dayOfMonth;
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
            if (everyWeeks < 1 || everyWeeks > MAX_EVERY_WEEKS) {
                throw new IllegalArgumentException(Texts.get("recurrence.error.everyWeeks", MAX_EVERY_WEEKS));
            }
        }

        @Override
        public String toRussian() {
            // День недели — слово формата, а не подпись интерфейса RuText.weekdayShort: этот текст пишется в файл.
            String day = FormatWords.weekdayShort(weekday);
            return everyWeeks == 1
                    ? FormatWords.get("plan.recurrence.weekly") + " " + day
                    : FormatWords.get("plan.recurrence.every") + " " + RuText.count(everyWeeks,
                            FormatWords.get("plan.unit.week.one"), FormatWords.get("plan.unit.week.few"),
                            FormatWords.get("plan.unit.week.many")) + " " + day;
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
            if (days < 1 || days > MAX_EVERY_DAYS) {
                throw new IllegalArgumentException(Texts.get("recurrence.error.everyDays", MAX_EVERY_DAYS));
            }
        }

        @Override
        public String toRussian() {
            return days == 1
                    ? FormatWords.get("plan.recurrence.daily")
                    : FormatWords.get("plan.recurrence.every") + " " + RuText.count(days,
                            FormatWords.get("plan.unit.day.one"), FormatWords.get("plan.unit.day.few"),
                            FormatWords.get("plan.unit.day.many"));
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
            return FormatWords.get("plan.recurrence.yearly") + " " + MONTH_DAY.format(monthDay);
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
