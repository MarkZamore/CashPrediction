package ru.cashprediction.core.model;

/**
 * Вид повтора без параметров: для выпадающего списка редактора правила и для канонического
 * значения поля {@code recurrenceKind} в снимке сессии.
 */
public enum RecurrenceKind {
    /** Раз в N месяцев в заданный день месяца. */
    MONTHLY("Ежемесячно / каждые N месяцев"),
    /** Раз в N недель в заданный день недели. */
    WEEKLY("Еженедельно / каждые N недель"),
    /** Каждые N дней от даты начала правила. */
    EVERY_N_DAYS("Каждые N дней"),
    /** Раз в год в заданный день. */
    YEARLY("Ежегодно");

    private final String title;

    RecurrenceKind(String title) {
        this.title = title;
    }

    /** @return подпись для интерфейса */
    public String title() {
        return title;
    }

    /** @return подпись для интерфейса (нужна, когда enum напрямую кладут в список выбора) */
    @Override
    public String toString() {
        return title;
    }
}
