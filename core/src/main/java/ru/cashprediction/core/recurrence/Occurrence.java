package ru.cashprediction.core.recurrence;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Одно событие регулярной операции: номинальная дата по правилу и фактическая дата после сдвига с выходных.
 *
 * <p>Пример: «Зарплата ежемесячно 5, выходные — раньше». Для сентября 2026 номинальная дата 05.09.2026
 * (суббота), фактическая 04.09.2026 (пятница). Корректировки привязываются к номинальной дате,
 * а в таблицу прогноза попадает фактическая.</p>
 *
 * <p>Record неизменяем и потокобезопасен.</p>
 *
 * @param nominal номинальная дата, полученная из правила повтора
 * @param actual  фактическая дата после сдвига с выходных (совпадает с {@code nominal}, если сдвига нет)
 */
public record Occurrence(LocalDate nominal, LocalDate actual) {

    /** Проверяет обязательные поля. */
    public Occurrence {
        Objects.requireNonNull(nominal, "nominal");
        Objects.requireNonNull(actual, "actual");
    }

    /**
     * Сообщает, был ли применён сдвиг с выходных.
     *
     * @return {@code true}, если фактическая дата отличается от номинальной
     */
    public boolean shifted() {
        return !nominal.equals(actual);
    }
}
