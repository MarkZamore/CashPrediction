package ru.cashprediction.core.session;

/**
 * Ошибка хранилища снимков: запись не удалась или сохранённые данные повреждены.
 *
 * <p>Исключение проверяемое: сбой записи снимка — ожидаемая ситуация (реестр недоступен под
 * ограниченной учётной записью, папка только для чтения), и вызывающий код обязан решить, что
 * показать пользователю. Сообщение всегда на русском и пригодно для показа в диалоге
 * восстановления или в строке состояния.</p>
 *
 * <p>Экземпляры неизменяемы и потокобезопасны.</p>
 */
public final class SessionStoreException extends Exception {

    /**
     * Создаёт исключение с сообщением.
     *
     * @param message сообщение на русском
     */
    public SessionStoreException(String message) {
        super(message);
    }

    /**
     * Создаёт исключение с сообщением и причиной.
     *
     * @param message сообщение на русском
     * @param cause   исходная ошибка
     */
    public SessionStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
