package ru.cashprediction.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.io.CashMemoryLayout;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;

/**
 * Настоящий web-сервер CashPrediction для тестов: {@link ServerState} над временной папкой CashMemory,
 * {@link WebServer} на свободном порту и {@link HttpClient}, который ходит в JSON API как браузер.
 *
 * <p>Тесты проверяют сервер «снаружи», через HTTP, — так проверяются и маршруты, и токен, и превращение исключений
 * в JSON-ответы. Имя класса не оканчивается на {@code Test}, чтобы surefire не принимал его за тест.</p>
 *
 * <p>Не потокобезопасен: один экземпляр — один тест.</p>
 */
final class ApiTestClient {

    /**
     * Ответ сервера.
     *
     * @param status      HTTP-статус
     * @param body        тело как текст
     * @param contentType заголовок {@code Content-Type} или пустая строка
     * @param disposition заголовок {@code Content-Disposition} или пустая строка
     */
    record Response(int status, String body, String contentType, String disposition) {

        /** @return тело как JSON-объект */
        Map<String, Object> json() {
            return JsonParser.parseObject(body);
        }
    }

    private final Path dir;
    private final ServerState state;
    private final WebServer server;
    private final HttpClient client;
    private boolean finished;

    private ApiTestClient(Path dir, ServerState state, WebServer server) {
        this.dir = dir;
        this.state = state;
        this.server = server;
        // HTTP/1.1 явно: встроенный сервер JDK не умеет HTTP/2, а попытка апгрейда только замедляет запросы.
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Запускает сервер над папкой CashMemory (папка создаётся при необходимости).
     *
     * @param dir папка CashMemory
     * @return клиент запущенного сервера
     * @throws IOException если папку создать или порт занять не удалось
     */
    static ApiTestClient start(Path dir) throws IOException {
        Files.createDirectories(dir);
        ServerState state = new ServerState(new CashMemoryLayout(dir), Clock.systemDefaultZone(), new ServerLog(false));
        state.start();
        // Порт 0: операционная система выдаёт свободный, тесты не мешают запущенному вручную серверу на 8765.
        WebServer server = WebServer.start(state, state.log(), 0, true);
        return new ApiTestClient(dir, state, server);
    }

    /** @return папка CashMemory */
    Path dir() {
        return dir;
    }

    /** @return серверное состояние */
    ServerState state() {
        return state;
    }

    /** @return токен сеанса */
    String token() {
        return server.token();
    }

    /**
     * Отправляет запрос.
     *
     * @param method метод
     * @param path   путь со строкой запроса (значения уже закодированы)
     * @param body   тело (карта или список для JSON) или {@code null}
     * @param token  токен для заголовка {@code X-Token} или {@code null} — без заголовка
     * @return ответ
     */
    Response send(String method, String path, Object body, String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(30));
        if (token != null) {
            builder.header(ApiHandler.TOKEN_HEADER, token);
        }
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JsonWriter.write(body), StandardCharsets.UTF_8);
        if (body != null) {
            builder.header("Content-Type", "application/json; charset=utf-8");
        }
        builder.method(method, publisher);
        try {
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body(),
                    response.headers().firstValue("Content-Type").orElse(""),
                    response.headers().firstValue("Content-Disposition").orElse(""));
        } catch (IOException e) {
            throw new IllegalStateException("Запрос " + method + " " + path + " не выполнен: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Запрос прерван", e);
        }
    }

    /**
     * Запрос с токеном сеанса.
     *
     * @param method метод
     * @param path   путь
     * @param body   тело или {@code null}
     * @return ответ
     */
    Response call(String method, String path, Object body) {
        return send(method, path, body, token());
    }

    /**
     * Запрос с токеном, который обязан завершиться статусом 200.
     *
     * @param method метод
     * @param path   путь
     * @param body   тело или {@code null}
     * @return JSON ответа
     */
    Map<String, Object> ok(String method, String path, Object body) {
        Response response = call(method, path, body);
        assertEquals(200, response.status(), () -> method + " " + path + " → " + response.body());
        return response.json();
    }

    /**
     * Запрос, который обязан завершиться заданным статусом с JSON-ошибкой.
     *
     * @param status ожидаемый статус
     * @param method метод
     * @param path   путь
     * @param body   тело или {@code null}
     * @return JSON ответа (с полем {@code error})
     */
    Map<String, Object> fail(int status, String method, String path, Object body) {
        Response response = call(method, path, body);
        assertEquals(status, response.status(), () -> method + " " + path + " → " + response.body());
        return response.json();
    }

    /**
     * Корректная остановка: снимок, настройки, маркер {@code closed}. Повторный вызов ничего не делает.
     */
    void stop() {
        if (finished) {
            return;
        }
        finished = true;
        server.stop();
        client.close();
    }

    /**
     * Имитация аварийного завершения: сервер бросается без снимка и без маркера {@code closed}.
     */
    void abandon() {
        if (finished) {
            return;
        }
        finished = true;
        server.abandon();
        client.close();
    }

    // ------------------------------------------------------------------ разбор JSON в тестах

    /**
     * Вложенный объект.
     *
     * @param map объект
     * @param key ключ
     * @return вложенный объект (ошибка теста, если его нет)
     */
    static Map<String, Object> obj(Map<String, ?> map, String key) {
        return Json.asObject(map.get(key), key);
    }

    /**
     * Элемент массива как объект.
     *
     * @param list массив
     * @param index индекс
     * @return объект
     */
    static Map<String, Object> obj(List<Object> list, int index) {
        return Json.asObject(list.get(index), "элемент " + index);
    }

    /**
     * Вложенный массив.
     *
     * @param map объект
     * @param key ключ
     * @return массив (пустой, если ключа нет)
     */
    static List<Object> list(Map<String, ?> map, String key) {
        return Json.list(map, key);
    }

    /**
     * Строковое поле.
     *
     * @param map объект
     * @param key ключ
     * @return значение или {@code null}
     */
    static String str(Map<String, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * Кодирует значение для строки запроса.
     *
     * @param value значение
     * @return закодированное значение
     */
    static String q(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
