package ru.cashprediction.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Ошибка обработки запроса API с HTTP-статусом и русским сообщением для пользователя.
 *
 * <p>{@link ApiHandler} превращает её в ответ {@code {"error": "<сообщение>", ...extra}}. Дополнительные поля
 * нужны браузеру, чтобы отличать, например, «файл изменён снаружи» (предложить «перезаписать / перечитать»)
 * от прочих конфликтов.</p>
 *
 * <p>Объект неизменяем после создания.</p>
 */
public final class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** HTTP-статус ответа. */
    private final int status;

    /** Дополнительные поля JSON-ответа (неизменяемая копия). */
    private final transient Map<String, Object> extra;

    /**
     * Создаёт ошибку.
     *
     * @param status  HTTP-статус (400, 403, 404, 405, 409, 500)
     * @param message сообщение на русском
     * @param extra   дополнительные поля ответа или {@code null}
     */
    public ApiException(int status, String message, Map<String, Object> extra) {
        super(Objects.requireNonNull(message, "message"));
        this.status = status;
        this.extra = extra == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(extra));
    }

    /**
     * Неверный запрос (400).
     *
     * @param message сообщение
     * @return ошибка
     */
    public static ApiException badRequest(String message) {
        return new ApiException(400, message, null);
    }

    /**
     * Не найдено (404).
     *
     * @param message сообщение
     * @return ошибка
     */
    public static ApiException notFound(String message) {
        return new ApiException(404, message, null);
    }

    /**
     * Доступ запрещён (403): неверный токен или чужой заголовок Host.
     *
     * @param message сообщение
     * @return ошибка
     */
    public static ApiException forbidden(String message) {
        return new ApiException(403, message, null);
    }

    /** @return HTTP-статус */
    public int status() {
        return status;
    }

    /** @return дополнительные поля JSON-ответа */
    public Map<String, Object> extra() {
        return extra;
    }
}
