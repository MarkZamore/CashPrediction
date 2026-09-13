package ru.cashprediction.core.session.codec;

/**
 * Текст снимка не удаётся разобрать: он повреждён, обрезан или записан более новой версией программы.
 *
 * <p>Хранилища превращают это исключение в {@code SessionStoreException} с указанием, какое именно
 * хранилище повреждено. Сообщение всегда на русском.</p>
 *
 * <p>Экземпляры неизменяемы и потокобезопасны.</p>
 */
public final class SnapshotFormatException extends Exception {

    /**
     * Создаёт исключение с сообщением.
     *
     * @param message сообщение на русском
     */
    public SnapshotFormatException(String message) {
        super(message);
    }

    /**
     * Создаёт исключение с сообщением и причиной.
     *
     * @param message сообщение на русском
     * @param cause   исходная ошибка разбора
     */
    public SnapshotFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
