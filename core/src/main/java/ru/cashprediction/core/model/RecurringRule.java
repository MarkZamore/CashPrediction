package ru.cashprediction.core.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Регулярная операция плана: «Зарплата, доход 80 000, ежемесячно 5-го».
 *
 * <p>Инварианты записи минимальны (только обязательные поля), а бизнес-проверки вроде «сумма больше нуля»
 * или «дата окончания не раньше даты начала» выполняет {@code diagnostics.PlanValidator}. Причина:
 * файл плана могли испортить вручную, и такой план всё равно нужно открыть, показать и подсветить ошибку,
 * а не отказаться его читать.</p>
 *
 * @param id            идентификатор в пределах плана
 * @param title         название операции
 * @param kind          доход или расход
 * @param amount        сумма одного события (положительная; знак задаёт {@code kind})
 * @param category      категория для группировки, может быть пустой
 * @param recurrence    правило повтора
 * @param from          первый допустимый день правила; {@code null} означает «с начала плана»
 * @param until         последний допустимый день правила; {@code null} означает «до конца горизонта»
 * @param weekendPolicy сдвиг событий, выпавших на выходные
 * @param enabled       включено ли правило; выключенное остаётся в плане, но не участвует в прогнозе
 * @param note          заметка, может быть пустой
 */
public record RecurringRule(
        RuleId id,
        String title,
        Kind kind,
        Money amount,
        String category,
        Recurrence recurrence,
        LocalDate from,
        LocalDate until,
        WeekendPolicy weekendPolicy,
        boolean enabled,
        String note) {

    /** Проверяет обязательные поля и заменяет {@code null}-строки пустыми. */
    public RecurringRule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(recurrence, "recurrence");
        title = title == null ? "" : title.strip();
        category = category == null ? "" : category.strip();
        weekendPolicy = weekendPolicy == null ? WeekendPolicy.NONE : weekendPolicy;
        note = note == null ? "" : note.strip();
    }

    /** @return копия с другим идентификатором */
    public RecurringRule withId(RuleId newId) {
        return new RecurringRule(newId, title, kind, amount, category, recurrence, from, until, weekendPolicy, enabled, note);
    }

    /** @return копия с другим признаком «включено» */
    public RecurringRule withEnabled(boolean value) {
        return new RecurringRule(id, title, kind, amount, category, recurrence, from, until, weekendPolicy, value, note);
    }

    /** @return копия с другой суммой */
    public RecurringRule withAmount(Money value) {
        return new RecurringRule(id, title, kind, value, category, recurrence, from, until, weekendPolicy, enabled, note);
    }

    /** @return копия с другой датой окончания */
    public RecurringRule withUntil(LocalDate value) {
        return new RecurringRule(id, title, kind, amount, category, recurrence, from, value, weekendPolicy, enabled, note);
    }
}
