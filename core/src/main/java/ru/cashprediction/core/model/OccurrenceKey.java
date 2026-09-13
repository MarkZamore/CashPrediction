package ru.cashprediction.core.model;

import java.time.LocalDate;
import java.util.Objects;
import ru.cashprediction.core.text.Texts;

/**
 * Ключ конкретного события регулярной операции: правило плюс его НОМИНАЛЬНАЯ дата.
 *
 * <p>Номинальная дата — та, что получается из правила повтора до сдвига с выходных и до переносов.
 * Корректировка («в декабре зарплата 95 000») привязывается именно к ней: если пользователь
 * перенесёт событие на другой день, ключ не изменится и корректировка не потеряется.</p>
 *
 * @param ruleId       правило
 * @param originalDate номинальная дата события
 */
public record OccurrenceKey(RuleId ruleId, LocalDate originalDate) {

    /** Проверяет обязательные поля. */
    public OccurrenceKey {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(originalDate, "originalDate");
    }

    /**
     * Текстовый идентификатор строки для снимков сессии и web-API: {@code r2@2026-10-01}.
     *
     * @return идентификатор строки
     */
    public String asRowId() {
        return ruleId.value() + "@" + originalDate;
    }

    /**
     * Разбирает идентификатор строки, созданный {@link #asRowId()}.
     *
     * @param rowId текст вида {@code r2@2026-10-01}
     * @return ключ события
     * @throws IllegalArgumentException если текст имеет другой вид
     */
    public static OccurrenceKey parseRowId(String rowId) {
        int at = rowId == null ? -1 : rowId.lastIndexOf('@');
        if (at <= 0 || at == rowId.length() - 1) {
            throw new IllegalArgumentException(Texts.get("id.error.occurrence", rowId));
        }
        return new OccurrenceKey(new RuleId(rowId.substring(0, at)), LocalDate.parse(rowId.substring(at + 1)));
    }
}
