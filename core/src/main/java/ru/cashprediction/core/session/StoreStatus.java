package ru.cashprediction.core.session;

import java.time.Instant;
import java.util.Objects;

/**
 * Результат очередной операции рекордера с одним хранилищем — для строки состояния
 * «Реестр ✓ 10:15:30 | XML ✓ 10:15:31».
 *
 * <p>Запись неизменяема и потокобезопасна.</p>
 *
 * @param storeId идентификатор хранилища ({@link SessionStore#id()})
 * @param ok      успешна ли операция
 * @param savedAt момент последнего успешно сохранённого в это хранилище снимка; {@code null}, если ещё не было
 * @param message пояснение на русском: пусто при успехе, причина при ошибке
 */
public record StoreStatus(String storeId, boolean ok, Instant savedAt, String message) {

    /** Проверяет идентификатор и заменяет {@code null}-сообщение пустой строкой. */
    public StoreStatus {
        Objects.requireNonNull(storeId, "storeId");
        message = Objects.requireNonNullElse(message, "");
    }
}
