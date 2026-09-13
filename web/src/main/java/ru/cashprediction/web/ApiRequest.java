package ru.cashprediction.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonException;
import ru.cashprediction.core.json.JsonParser;

/**
 * Разобранный запрос к API: метод, путь, параметры строки запроса, параметры пути и тело.
 *
 * <p>Тело читается {@link ApiHandler} заранее (потоком запроса нельзя пользоваться дважды), а JSON из него
 * разбирается лениво при первом обращении к {@link #json()}: многим маршрутам тело не нужно.</p>
 *
 * <p>Объект живёт в пределах одного запроса и одного потока; не потокобезопасен.</p>
 */
public final class ApiRequest {

    private final String method;
    private final String path;
    private final Map<String, String> query;
    private final Map<String, String> pathParams;
    private final String body;
    private Map<String, Object> json;

    /**
     * Создаёт запрос.
     *
     * @param method     HTTP-метод в верхнем регистре
     * @param path       путь без строки запроса, например {@code /api/rules/r3}
     * @param query      параметры строки запроса
     * @param pathParams параметры шаблона пути ({@code {id}} → {@code r3})
     * @param body       тело запроса (UTF-8), пустая строка — тела нет
     */
    public ApiRequest(String method, String path, Map<String, String> query, Map<String, String> pathParams, String body) {
        this.method = Objects.requireNonNull(method, "method");
        this.path = Objects.requireNonNull(path, "path");
        this.query = Map.copyOf(Objects.requireNonNullElse(query, Map.of()));
        this.pathParams = Map.copyOf(Objects.requireNonNullElse(pathParams, Map.of()));
        this.body = Objects.requireNonNullElse(body, "");
    }

    /** @return HTTP-метод */
    public String method() {
        return method;
    }

    /** @return путь запроса */
    public String path() {
        return path;
    }

    /** @return тело запроса как текст */
    public String body() {
        return body;
    }

    /**
     * Параметр пути.
     *
     * @param name имя из шаблона, например {@code id}
     * @return значение (URL-декодированное)
     */
    public String pathParam(String name) {
        return pathParams.getOrDefault(name, "");
    }

    /**
     * Параметр строки запроса.
     *
     * @param name         имя
     * @param defaultValue значение, если параметра нет
     * @return значение
     */
    public String query(String name, String defaultValue) {
        return query.getOrDefault(name, defaultValue);
    }

    /**
     * Все параметры строки запроса (например, поля формы, переданные в GET-запросе предпросмотра).
     *
     * @return неизменяемая карта параметров
     */
    public Map<String, String> queryMap() {
        return query;
    }

    /**
     * Есть ли параметр строки запроса (даже пустой).
     *
     * @param name имя
     * @return {@code true}, если параметр передан
     */
    public boolean hasQuery(String name) {
        return query.containsKey(name);
    }

    /**
     * Тело запроса как JSON-объект.
     *
     * @return объект; пустой изменяемый объект, если тела нет
     * @throws ApiException 400, если тело не является JSON-объектом
     */
    public Map<String, Object> json() {
        if (json == null) {
            if (body.isBlank()) {
                json = new LinkedHashMap<>();
            } else {
                try {
                    json = Json.asObject(JsonParser.parse(body), "тело запроса");
                } catch (JsonException e) {
                    throw ApiException.badRequest(e.getMessage());
                }
            }
        }
        return json;
    }

    /**
     * Строковое поле тела; числа и логические значения приводятся к строке (удобно для полей форм).
     *
     * @param key          имя поля
     * @param defaultValue значение, если поля нет или оно {@code null}
     * @return строка
     */
    public String string(String key, String defaultValue) {
        Object value = json().get(key);
        return value == null ? defaultValue : value.toString();
    }

    /**
     * Логическое поле тела; принимает также строки {@code "true"/"false"}.
     *
     * @param key          имя поля
     * @param defaultValue значение, если поля нет
     * @return значение
     * @throws ApiException 400, если значение не логическое
     */
    public boolean bool(String key, boolean defaultValue) {
        Object value = json().get(key);
        return switch (value) {
            case null -> defaultValue;
            case Boolean b -> b;
            case String s when "true".equalsIgnoreCase(s.strip()) -> true;
            case String s when "false".equalsIgnoreCase(s.strip()) -> false;
            default -> throw ApiException.badRequest("Поле «" + key + "» должно быть true или false");
        };
    }

    /**
     * Вложенный объект тела или всё тело, если вложенного нет (браузер может прислать
     * {@code {"fields": {...}}} или сами поля).
     *
     * @param key имя вложенного объекта
     * @return объект
     */
    public Map<String, Object> objectOrSelf(String key) {
        try {
            return Json.optionalObject(json(), key).orElse(json());
        } catch (JsonException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    /**
     * Приводит JSON-объект к карте строк (значения полей форм и контекста окна).
     *
     * @param map объект; {@code null} даёт пустую карту
     * @return карта строк в том же порядке; {@code null}-значения становятся пустыми строками
     * @throws ApiException 400, если значение — объект или массив
     */
    public static Map<String, String> stringMap(Map<String, Object> map) {
        Map<String, String> result = new LinkedHashMap<>();
        if (map == null) {
            return result;
        }
        map.forEach((key, value) -> {
            if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
                throw ApiException.badRequest("Поле «" + key + "» должно быть строкой");
            }
            result.put(key, value == null ? "" : value.toString());
        });
        return result;
    }
}
