package ru.cashprediction.web;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import ru.cashprediction.core.json.JsonWriter;

/**
 * Ответ обработчика API: статус, тип содержимого, тело и (для скачивания) имя файла.
 *
 * <p>Обработчики маршрутов возвращают этот объект, а не пишут в {@code HttpExchange} сами: так вся
 * работа с потоком ответа, заголовками кэширования и безопасности сосредоточена в {@link ApiHandler},
 * а маршруты остаются короткими.</p>
 *
 * <p>Record неизменяем; массив тела не копируется ради скорости и не должен меняться после создания.</p>
 *
 * @param status      HTTP-статус
 * @param contentType значение заголовка {@code Content-Type}
 * @param body        тело ответа
 * @param fileName    имя файла для {@code Content-Disposition: attachment}; {@code null} — не скачивание
 */
public record ApiResponse(int status, String contentType, byte[] body, String fileName) {

    /** Тип JSON-ответа. */
    public static final String JSON = "application/json; charset=utf-8";

    /** Проверяет поля. */
    public ApiResponse {
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(body, "body");
    }

    /**
     * JSON-ответ 200.
     *
     * @param value карта, список или простое значение, поддерживаемое {@link JsonWriter}
     * @return ответ
     */
    public static ApiResponse json(Object value) {
        return json(200, value);
    }

    /**
     * JSON-ответ с заданным статусом.
     *
     * @param status статус
     * @param value  значение
     * @return ответ
     */
    public static ApiResponse json(int status, Object value) {
        return new ApiResponse(status, JSON, JsonWriter.write(value).getBytes(StandardCharsets.UTF_8), null);
    }

    /**
     * Файл для скачивания — web-аналог сохранения через {@code FileChooser}.
     *
     * @param contentType тип содержимого
     * @param text        текст файла (UTF-8)
     * @param fileName    предлагаемое имя файла
     * @return ответ 200 с {@code Content-Disposition: attachment}
     */
    public static ApiResponse download(String contentType, String text, String fileName) {
        return new ApiResponse(200, contentType, text.getBytes(StandardCharsets.UTF_8), fileName);
    }
}
