package ru.cashprediction.core.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Разовая операция: «20.12.2026 премия +60 000», «10.01.2027 ноутбук -90 000».
 *
 * @param id       идентификатор в пределах плана
 * @param date     дата операции
 * @param title    название
 * @param kind     доход или расход
 * @param amount   сумма (положительная; знак задаёт {@code kind})
 * @param category категория, может быть пустой
 * @param note     заметка, может быть пустой
 */
public record OneTimeTransaction(
        TxId id,
        LocalDate date,
        String title,
        Kind kind,
        Money amount,
        String category,
        String note) {

    /** Проверяет обязательные поля и заменяет {@code null}-строки пустыми. */
    public OneTimeTransaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        title = title == null ? "" : title.strip();
        category = category == null ? "" : category.strip();
        note = note == null ? "" : note.strip();
    }

    /** @return копия с другим идентификатором */
    public OneTimeTransaction withId(TxId newId) {
        return new OneTimeTransaction(newId, date, title, kind, amount, category, note);
    }
}
