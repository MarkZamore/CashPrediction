package ru.cashprediction.core.model;

import java.util.Objects;

/**
 * Идентификатор разовой операции в пределах одного плана: {@code t1}, {@code t2} ...
 *
 * @param value текст идентификатора, как он записан в файле плана
 */
public record TxId(String value) {

    /** Проверяет, что идентификатор не пустой и не содержит символов, ломающих таблицу Markdown. */
    public TxId {
        Objects.requireNonNull(value, "value");
        value = value.strip();
        if (value.isEmpty() || value.contains("|") || value.contains("@")) {
            throw new IllegalArgumentException("Некорректный идентификатор операции: «" + value + "»");
        }
    }

    /** @return текст идентификатора */
    @Override
    public String toString() {
        return value;
    }
}
