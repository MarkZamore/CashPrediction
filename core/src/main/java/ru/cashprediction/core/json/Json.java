package ru.cashprediction.core.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.cashprediction.core.text.Texts;

/**
 * Типизированные аксессоры для объектов, полученных из {@link JsonParser}.
 *
 * <p>Зачем: разобранный JSON — это {@code Map<String, Object>}, и каждое чтение поля требует
 * проверки типа. Без этих методов код web-API и кодека снимка превратился бы в лес
 * {@code instanceof} с разными сообщениями об ошибках. Здесь правило одно: отсутствующее поле или
 * {@code null} → значение по умолчанию; поле неверного типа → {@link JsonException} с сообщением из каталога
 * текстов вида «Поле «pid» должно быть целым числом» (ключи {@code json.error.*}, область {@code json}).</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class Json {

    private Json() {
    }

    /**
     * Проверяет, что значение является JSON-объектом.
     *
     * @param value значение из разборщика
     * @param what  что это за значение (для сообщения), например «снимок»
     * @return то же значение, приведённое к карте
     * @throws JsonException если значение не объект
     */
    @SuppressWarnings("unchecked") // JsonParser создаёт объекты только с ключами-строками
    public static Map<String, Object> asObject(Object value, String what) {
        if (value instanceof Map<?, ?> map) {
            for (Object key : map.keySet()) {
                if (!(key instanceof String)) {
                    throw new JsonException(Texts.get("json.error.nonStringKey", what));
                }
            }
            return (Map<String, Object>) map;
        }
        throw new JsonException(Texts.get("json.error.notObject", what, typeName(value)));
    }

    /**
     * Читает строковое поле.
     *
     * @param map          объект
     * @param key          имя поля
     * @param defaultValue значение, если поле отсутствует или равно {@code null}
     * @return строка
     * @throws JsonException если поле есть, но не строка
     */
    public static String string(Map<String, ?> map, String key, String defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String s) {
            return s;
        }
        throw wrongType(key, "json.expected.string", value);
    }

    /**
     * Читает обязательное строковое поле.
     *
     * @param map объект
     * @param key имя поля
     * @return строка
     * @throws JsonException если поле отсутствует, равно {@code null} или не строка
     */
    public static String requireString(Map<String, ?> map, String key) {
        String value = string(map, key, null);
        if (value == null) {
            throw missing(key);
        }
        return value;
    }

    /**
     * Читает логическое поле.
     *
     * @param map          объект
     * @param key          имя поля
     * @param defaultValue значение, если поле отсутствует или равно {@code null}
     * @return логическое значение
     * @throws JsonException если поле есть, но не {@code true}/{@code false}
     */
    public static boolean bool(Map<String, ?> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        throw wrongType(key, "json.expected.boolean", value);
    }

    /**
     * Читает целочисленное поле.
     *
     * <p>Принимаются {@link Long}, {@link Integer} и {@link BigDecimal} с нулевой дробной частью,
     * помещающийся в {@code long} (например, {@code 5.0} или {@code 1e3}).</p>
     *
     * @param map          объект
     * @param key          имя поля
     * @param defaultValue значение, если поле отсутствует или равно {@code null}
     * @return целое число
     * @throws JsonException если поле не число или не целое
     */
    public static long longValue(Map<String, ?> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        switch (value) {
            case Long l -> {
                return l;
            }
            case Integer i -> {
                return i;
            }
            case BigDecimal bd -> {
                try {
                    return bd.longValueExact();
                } catch (ArithmeticException e) {
                    throw wrongType(key, "json.expected.integer", value);
                }
            }
            default -> throw wrongType(key, "json.expected.integer", value);
        }
    }

    /**
     * Читает обязательное целочисленное поле.
     *
     * @param map объект
     * @param key имя поля
     * @return целое число
     * @throws JsonException если поле отсутствует или не целое число
     */
    public static long requireLong(Map<String, ?> map, String key) {
        if (map.get(key) == null) {
            throw missing(key);
        }
        return longValue(map, key, 0);
    }

    /**
     * Читает числовое поле как {@code double}.
     *
     * @param map          объект
     * @param key          имя поля
     * @param defaultValue значение, если поле отсутствует или равно {@code null}
     * @return число
     * @throws JsonException если поле есть, но не число
     */
    public static double doubleValue(Map<String, ?> map, String key, double defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        throw wrongType(key, "json.expected.number", value);
    }

    /**
     * Читает поле-массив.
     *
     * @param map объект
     * @param key имя поля
     * @return элементы массива (неизменяемая копия); пустой список, если поле отсутствует или {@code null}
     * @throws JsonException если поле есть, но не массив
     */
    public static List<Object> list(Map<String, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> items) {
            // List.copyOf не принимает null-элементы, а JSON-массив может их содержать.
            return Collections.unmodifiableList(new ArrayList<Object>(items));
        }
        throw wrongType(key, "json.expected.array", value);
    }

    /**
     * Читает поле-объект.
     *
     * @param map объект
     * @param key имя поля
     * @return вложенный объект; пустой объект, если поле отсутствует или {@code null}
     * @throws JsonException если поле есть, но не объект
     */
    public static Map<String, Object> object(Map<String, ?> map, String key) {
        return optionalObject(map, key).orElseGet(LinkedHashMap::new);
    }

    /**
     * Читает необязательное поле-объект, отличая «нет поля» от «пустой объект».
     *
     * @param map объект
     * @param key имя поля
     * @return вложенный объект или пусто, если поле отсутствует или {@code null}
     * @throws JsonException если поле есть, но не объект
     */
    public static Optional<Map<String, Object>> optionalObject(Map<String, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Map<?, ?>) {
            return Optional.of(asObject(value, key));
        }
        throw wrongType(key, "json.expected.object", value);
    }

    /**
     * Ошибка «поле неверного типа».
     *
     * @param key         имя поля
     * @param expectedKey ключ каталога с ожидаемым типом в творительном падеже ({@code json.expected.*})
     * @param actual      фактическое значение
     * @return исключение с готовым сообщением
     */
    static JsonException wrongType(String key, String expectedKey, Object actual) {
        return new JsonException(Texts.get("json.error.wrongType", key, Texts.get(expectedKey), typeName(actual)));
    }

    /**
     * Ошибка «нет обязательного поля».
     *
     * @param key имя поля
     * @return исключение с готовым сообщением
     */
    static JsonException missing(String key) {
        return new JsonException(Texts.get("json.error.missingField", key));
    }

    /**
     * Название JSON-типа значения на языке интерфейса, для сообщений об ошибках.
     *
     * @param value значение из разборщика
     * @return например «строка», «число», «массив»; для {@code null} — {@code null}, для чужого класса — его имя
     */
    public static String typeName(Object value) {
        return switch (value) {
            case null -> "null";
            case String _ -> Texts.get("json.type.string");
            case Boolean _ -> Texts.get("json.type.boolean");
            case Number _ -> Texts.get("json.type.number");
            case Map<?, ?> _ -> Texts.get("json.type.object");
            case List<?> _ -> Texts.get("json.type.array");
            default -> value.getClass().getSimpleName();
        };
    }
}
