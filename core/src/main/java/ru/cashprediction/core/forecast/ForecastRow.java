package ru.cashprediction.core.forecast;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;

/**
 * Одна строка таблицы прогноза: событие и баланс сразу после него.
 *
 * <p>Record неизменяем и потокобезопасен.</p>
 *
 * @param date         фактическая дата события (после сдвига с выходных и переносов)
 * @param originalDate номинальная дата: для событий правил — дата по правилу (ключ корректировок),
 *                     для остальных строк совпадает с {@code date}
 * @param title        название операции
 * @param kind         доход или расход; у строки начального баланса — {@link Kind#INCOME} с нулевой суммой
 * @param category     категория, может быть пустой
 * @param amount       изменение баланса со знаком: доход положительный, расход отрицательный;
 *                     у пропущенной строки — сумма, которая была бы без пропуска
 * @param balanceAfter баланс после строки; у пропущенной строки равен балансу предыдущей
 * @param origin       источник строки
 * @param ruleId       правило для строк {@link Origin#RULE}, иначе {@code null}
 * @param txId         разовая операция для строк {@link Origin#ONE_TIME}, иначе {@code null}
 * @param flags        отметки
 * @param note         заметка: заметка корректировки, если она не пуста, иначе заметка операции
 */
public record ForecastRow(
        LocalDate date,
        LocalDate originalDate,
        String title,
        Kind kind,
        String category,
        Money amount,
        Money balanceAfter,
        Origin origin,
        RuleId ruleId,
        TxId txId,
        Flags flags,
        String note) {

    /** Идентификатор строки начального баланса. */
    public static final String START_ROW_ID = "start";

    /** Проверяет обязательные поля и подставляет значения по умолчанию для необязательных. */
    public ForecastRow {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(balanceAfter, "balanceAfter");
        Objects.requireNonNull(origin, "origin");
        originalDate = originalDate == null ? date : originalDate;
        title = title == null ? "" : title;
        category = category == null ? "" : category;
        flags = flags == null ? Flags.NONE : flags;
        note = note == null ? "" : note;
    }

    /**
     * Ключ события правила, по которому ищется и создаётся корректировка.
     *
     * @return ключ для строк правил; пусто для остальных строк
     */
    public Optional<OccurrenceKey> occurrenceKey() {
        return ruleId == null ? Optional.empty() : Optional.of(new OccurrenceKey(ruleId, originalDate));
    }

    /**
     * Устойчивый текстовый идентификатор строки для выделения в таблице, снимков сессии и web-API.
     *
     * @return {@code "start"}, {@code "r2@2026-10-01"}, {@code "t1"} или {@code "whatif@2026-10-31"}
     */
    public String rowId() {
        return switch (origin) {
            case START -> START_ROW_ID;
            case RULE -> ruleId == null ? "rule@" + originalDate : new OccurrenceKey(ruleId, originalDate).asRowId();
            case ONE_TIME -> txId == null ? "tx@" + date : txId.value();
            case WHAT_IF -> "whatif@" + date;
        };
    }

    /** @return {@code true} для строки дохода (строка начального баланса доходом не считается) */
    public boolean isIncome() {
        return kind == Kind.INCOME && origin != Origin.START;
    }

    /** @return {@code true} для строки расхода */
    public boolean isExpense() {
        return kind == Kind.EXPENSE;
    }

    /** @return {@code true}, если строка меняет баланс (не начальный баланс и не пропущенное событие) */
    public boolean affectsBalance() {
        return origin != Origin.START && !flags.skipped();
    }
}
