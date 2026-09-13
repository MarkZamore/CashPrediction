package ru.cashprediction.core.model;

import java.util.Objects;

/**
 * Идентификатор регулярной операции в пределах одного плана: {@code r1}, {@code r2} ...
 *
 * <p>Отдельный тип вместо строки не даёт перепутать идентификатор правила с идентификатором
 * разовой операции ({@link TxId}) при вызове методов плана.</p>
 *
 * @param value текст идентификатора, как он записан в файле плана
 */
public record RuleId(String value) {

    /** Проверяет, что идентификатор не пустой и не содержит символов, ломающих таблицу Markdown. */
    public RuleId {
        Objects.requireNonNull(value, "value");
        value = value.strip();
        if (value.isEmpty() || value.contains("|") || value.contains("@")) {
            throw new IllegalArgumentException("Некорректный идентификатор правила: «" + value + "»");
        }
    }

    /** @return текст идентификатора */
    @Override
    public String toString() {
        return value;
    }
}
