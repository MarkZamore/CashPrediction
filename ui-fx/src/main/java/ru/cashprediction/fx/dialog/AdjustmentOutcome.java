package ru.cashprediction.fx.dialog;

import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.OccurrenceKey;

import java.util.Objects;
import java.util.Optional;

/**
 * Результат диалога «Корректировка события»: новая корректировка или её сброс.
 *
 * <p>Запись неизменяема.</p>
 *
 * @param key        событие правила (правило + номинальная дата)
 * @param adjustment новая корректировка; {@code null} — кнопка «Сбросить» (событие снова «как по правилу»)
 */
public record AdjustmentOutcome(OccurrenceKey key, Adjustment adjustment) {

    /** Проверяет ключ. */
    public AdjustmentOutcome {
        Objects.requireNonNull(key, "key");
    }

    /**
     * Корректировка как {@link Optional}.
     *
     * @return корректировка или пусто, если её нужно удалить
     */
    public Optional<Adjustment> adjustmentOptional() {
        return Optional.ofNullable(adjustment);
    }
}
