package ru.cashprediction.core.model;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Цель накопления: «Отпуск, 300 000 к 01.06.2027».
 *
 * <p>Прогноз показывает дату, когда баланс впервые достигнет цели, а калькулятор цели считает,
 * сколько откладывать дополнительно, чтобы успеть к желаемой дате.</p>
 *
 * @param title    название цели, может быть пустым
 * @param target   нужная сумма на счёте
 * @param wishDate желаемая дата; {@code null}, если срок не важен
 */
public record Goal(String title, Money target, LocalDate wishDate) {

    /** Проверяет обязательное поле и заменяет {@code null}-название пустым. */
    public Goal {
        Objects.requireNonNull(target, "target");
        title = title == null ? "" : title.strip();
    }
}
