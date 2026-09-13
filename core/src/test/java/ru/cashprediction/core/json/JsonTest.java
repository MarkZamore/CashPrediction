package ru.cashprediction.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Тесты типизированных аксессоров {@link Json}: значения по умолчанию и русские сообщения о неверных типах.
 */
class JsonTest {

    private final Map<String, Object> object = JsonParser.parseObject("""
            {"name": "План", "pid": 12345, "exact": 5.0, "fraction": 2.5, "flag": true, "nothing": null,
             "items": [1, "два"], "child": {"x": 1}}
            """);

    @Test
    void readsTypedValuesAndDefaults() {
        assertEquals("План", Json.string(object, "name", ""));
        assertEquals("по умолчанию", Json.string(object, "absent", "по умолчанию"));
        assertEquals("по умолчанию", Json.string(object, "nothing", "по умолчанию"));
        assertEquals(12345L, Json.longValue(object, "pid", 0));
        assertEquals(5L, Json.longValue(object, "exact", 0));
        assertEquals(2.5, Json.doubleValue(object, "fraction", 0));
        assertTrue(Json.bool(object, "flag", false));
        assertFalse(Json.bool(object, "absent", false));
        assertEquals(List.of(1L, "два"), Json.list(object, "items"));
        assertEquals(List.of(), Json.list(object, "absent"));
        assertEquals(Map.of("x", 1L), Json.object(object, "child"));
        assertTrue(Json.object(object, "absent").isEmpty());
        assertTrue(Json.optionalObject(object, "absent").isEmpty());
        assertEquals("План", Json.requireString(object, "name"));
        assertEquals(12345L, Json.requireLong(object, "pid"));
    }

    @Test
    void wrongTypesProduceRussianMessages() {
        JsonException string = assertThrows(JsonException.class, () -> Json.string(object, "pid", ""));
        assertEquals("Поле «pid» должно быть строкой, получено: число", string.getMessage());
        JsonException integer = assertThrows(JsonException.class, () -> Json.longValue(object, "fraction", 0));
        assertEquals("Поле «fraction» должно быть целым числом, получено: число", integer.getMessage());
        JsonException bool = assertThrows(JsonException.class, () -> Json.bool(object, "name", false));
        assertEquals("Поле «name» должно быть логическим значением (true/false), получено: строка", bool.getMessage());
        JsonException list = assertThrows(JsonException.class, () -> Json.list(object, "child"));
        assertEquals("Поле «child» должно быть массивом, получено: объект", list.getMessage());
        JsonException child = assertThrows(JsonException.class, () -> Json.object(object, "items"));
        assertEquals("Поле «items» должно быть объектом, получено: массив", child.getMessage());
        JsonException missing = assertThrows(JsonException.class, () -> Json.requireString(object, "absent"));
        assertEquals("Отсутствует обязательное поле «absent»", missing.getMessage());
        assertThrows(JsonException.class, () -> Json.doubleValue(object, "flag", 0));
        assertEquals(-1, missing.position());
    }
}
