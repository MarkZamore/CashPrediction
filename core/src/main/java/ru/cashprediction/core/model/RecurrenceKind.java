package ru.cashprediction.core.model;

import ru.cashprediction.core.text.Texts;

/**
 * Вид повтора без параметров: для выпадающего списка редактора правила и для канонического
 * значения поля {@code recurrenceKind} в снимке сессии.
 *
 * <p>Подпись берётся из каталога текстов лениво, при вызове {@link #title()}: в снимок сессии пишется имя константы,
 * а не подпись, поэтому смена языка интерфейса снимков не затрагивает.</p>
 */
public enum RecurrenceKind {
    /** Раз в N месяцев в заданный день месяца. */
    MONTHLY,
    /** Раз в N недель в заданный день недели. */
    WEEKLY,
    /** Каждые N дней от даты начала правила. */
    EVERY_N_DAYS,
    /** Раз в год в заданный день. */
    YEARLY;

    /** @return подпись для интерфейса */
    public String title() {
        return switch (this) {
            case MONTHLY -> Texts.get("recurrence.kind.monthly");
            case WEEKLY -> Texts.get("recurrence.kind.weekly");
            case EVERY_N_DAYS -> Texts.get("recurrence.kind.everyNDays");
            case YEARLY -> Texts.get("recurrence.kind.yearly");
        };
    }

    /** @return подпись для интерфейса (нужна, когда enum напрямую кладут в список выбора) */
    @Override
    public String toString() {
        return title();
    }
}
