package ru.cashprediction.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Вспомогательные операции с {@link HttpExchange}: чтение тела и параметров, запись ответа, заголовки.
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class HttpUtil {

    /** Наибольший размер тела запроса: импорт плана в несколько мегабайт ещё проходит, мусор — нет. */
    public static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

    private HttpUtil() {
    }

    /**
     * Читает тело запроса как UTF-8.
     *
     * @param exchange обмен
     * @return текст тела (пустая строка, если тела нет)
     * @throws IOException  если чтение не удалось
     * @throws ApiException 400, если тело больше {@link #MAX_BODY_BYTES}
     */
    public static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (out.size() + read > MAX_BODY_BYTES) {
                    throw ApiException.badRequest("Слишком большой запрос (больше " + MAX_BODY_BYTES / (1024 * 1024) + " МБ)");
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * Разбирает строку запроса {@code a=1&b=%D0%B0}.
     *
     * @param uri адрес запроса
     * @return параметры в порядке появления; повторный параметр перекрывает прежний
     */
    public static Map<String, String> parseQuery(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            result.put(key, value);
        }
        return result;
    }

    /**
     * Декодирует компонент URL; некорректная кодировка превращается в ответ 400, а не в 500.
     *
     * @param text закодированный текст
     * @return декодированный текст
     */
    public static String decode(String text) {
        try {
            return URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Некорректная кодировка параметра запроса");
        }
    }

    /**
     * Кодирует компонент URL (пробел как {@code %20}, а не {@code +}: так понимает {@code filename*}).
     *
     * @param text текст
     * @return закодированный текст
     */
    public static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Добавляет заголовки «не кэшировать» и базовые заголовки безопасности.
     *
     * <p>{@code Referrer-Policy: no-referrer} не даёт токену из адресной строки утечь через заголовок Referer;
     * {@code nosniff} запрещает браузеру угадывать тип содержимого.</p>
     *
     * @param headers заголовки ответа
     */
    public static void addCommonHeaders(Headers headers) {
        headers.set("Cache-Control", "no-store, no-cache, must-revalidate");
        headers.set("Pragma", "no-cache");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
    }

    /**
     * Отправляет ответ целиком и закрывает поток.
     *
     * @param exchange    обмен
     * @param status      статус
     * @param contentType тип содержимого
     * @param body        тело
     * @param headOnly    {@code true} для HEAD: только заголовки
     * @throws IOException если клиент оборвал соединение
     */
    public static void send(HttpExchange exchange, int status, String contentType, byte[] body, boolean headOnly)
            throws IOException {
        Headers headers = exchange.getResponseHeaders();
        addCommonHeaders(headers);
        headers.set("Content-Type", contentType);
        if (headOnly || status == 204) {
            // -1: тела нет; для HEAD длина всё равно сообщается в Content-Length.
            if (headOnly) {
                headers.set("Content-Length", Integer.toString(body.length));
            }
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Значение {@code Content-Disposition} для скачивания файла с русским именем (RFC 6266 / RFC 5987).
     *
     * @param fileName имя файла
     * @return {@code attachment; filename="..."; filename*=UTF-8''...}
     */
    public static String attachment(String fileName) {
        // ASCII-замена для старых браузеров: всё не-ASCII и кавычки заменяются подчёркиванием.
        StringBuilder ascii = new StringBuilder();
        for (char c : fileName.toCharArray()) {
            ascii.append(c >= 0x20 && c < 0x7F && c != '"' && c != '\\' ? c : '_');
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encode(fileName);
    }
}
