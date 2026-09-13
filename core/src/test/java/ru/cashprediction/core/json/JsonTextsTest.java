package ru.cashprediction.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.model.RecurrenceKind;
import ru.cashprediction.core.text.Texts;

/**
 * Этап S0.5: сообщения разбора JSON (ответы web-API и строки отчёта восстановления) берутся из каталога
 * ({@code json_ru.properties}) и совпадают с прежними русскими строками кода буква в букву. Прежний web-клиент
 * узнаёт ошибки полей по началу «Поле », поэтому начало сообщений тоже проверяется.
 */
class JsonTextsTest {

    @Test
    void parserMessagesKeepPositionAndWording() {
        assertEquals("Некорректный JSON: текст отсутствует (null)", message(() -> JsonParser.parse(null)));
        assertEquals("Некорректный JSON, строка 1, столбец 1 (позиция 0): пустой текст, ожидалось значение",
                message(() -> JsonParser.parse("")));
        assertEquals("Некорректный JSON, строка 1, столбец 6 (позиция 5): ожидалось «:» после имени поля",
                message(() -> JsonParser.parse("{\"a\" 1}")));
        assertEquals("Некорректный JSON, строка 1, столбец 4 (позиция 3): неожиданный символ «]», ожидалось значение",
                message(() -> JsonParser.parse("[1,]")));
        assertEquals("Некорректный JSON, строка 1, столбец 3 (позиция 2): неполная escape-последовательность \\u",
                message(() -> JsonParser.parse("\"\\u12\"")));
        assertEquals("Некорректный JSON, строка 1, столбец 3 (позиция 2): недопустимая escape-последовательность \\x",
                message(() -> JsonParser.parse("\"\\x\"")));
        assertEquals("Некорректный JSON, строка 2, столбец 2 (позиция 9): повторяющееся имя поля «a»",
                message(() -> JsonParser.parse("{\"a\":1,\n \"a\":2}")), "повтор ключа отмечается позицией второго ключа");
        assertTrue(message(() -> JsonParser.parse("[".repeat(JsonParser.MAX_DEPTH + 1)))
                .endsWith(": слишком глубокая вложенность (больше 512)"));
        assertEquals("Значение «документ» должно быть JSON-объектом, получено: массив",
                message(() -> JsonParser.parseObject("[]")));
    }

    @Test
    void accessorMessages() {
        assertEquals("Поле «pid» должно быть целым числом, получено: строка",
                message(() -> Json.requireLong(Map.of("pid", "x"), "pid")));
        assertEquals("Поле «flag» должно быть логическим значением (true/false), получено: число",
                message(() -> Json.bool(Map.of("flag", 1L), "flag", false)));
        assertEquals("Отсутствует обязательное поле «name»", message(() -> Json.requireString(Map.of(), "name")));
        assertEquals("Значение «снимок» должно быть JSON-объектом, получено: логическое значение",
                message(() -> Json.asObject(true, "снимок")));
        assertEquals(List.of("строка", "логическое значение", "число", "объект", "массив", "null"),
                Arrays.asList(Json.typeName("s"), Json.typeName(true), Json.typeName(1L), Json.typeName(Map.of()),
                        Json.typeName(List.of()), Json.typeName(null)));
        assertEquals(Texts.get("json.type.array"), Json.typeName(List.of()), "название типа — из каталога");
    }

    @Test
    void planMessagesKeepFieldPrefix() {
        assertEquals("Поле «kind»: неизвестный вид горизонта «DAYS» (ожидается MONTHS, YEARS или UNTIL)",
                message(() -> PlanJson.horizonFrom(map("kind", "days"))));
        assertEquals("Отсутствует обязательное поле «horizon»",
                message(() -> PlanJson.planFrom(map("name", "x", "startDate", "2026-09-01"))));
        assertEquals("Элемент rules[0]: Значение «rules[0]» должно быть JSON-объектом, получено: строка",
                message(() -> PlanJson.planFrom(map("name", "x", "startDate", "2026-09-01",
                        "horizon", map("kind", "MONTHS", "count", 12L), "rules", List.of("r1")))));
        assertEquals("Элемент recentPlans[0] должен быть строкой, получено: число",
                message(() -> PlanJson.settingsFrom(map("recentPlans", List.of(1L)))));
        assertEquals("Элемент lines[1] должен быть строкой, получено: логическое значение",
                message(() -> PlanJson.rawBlockFrom(map("lines", List.of("a", true)))));
        assertEquals("Поле «view»: неизвестное значение «xyz»", message(() -> PlanJson.settingsFrom(map("view", "xyz"))));
        assertEquals("Поле «dayOfMonth» должно быть целым числом, получено: «abc»",
                message(() -> PlanJson.recurrenceFrom(map("kind", "MONTHLY", "dayOfMonth", "abc"))));
        assertEquals("Поле «dayOfMonth»: число вне допустимого диапазона",
                message(() -> PlanJson.recurrenceFrom(map("kind", "MONTHLY", "dayOfMonth", 99_999_999_999L))));
        assertEquals("Поле «kind»: неизвестное значение «FOO» (ожидается одно из: "
                        + String.join(", ", Arrays.stream(RecurrenceKind.values()).map(Enum::name).toList()) + ")",
                message(() -> PlanJson.recurrenceFrom(map("kind", "FOO"))));
        assertEquals("Поле «weekday»: неизвестный день недели «xyz»",
                message(() -> PlanJson.recurrenceFrom(map("kind", "WEEKLY", "weekday", "xyz"))));
        assertEquals("Поле «monthDay»: некорректный день года «13-40» (ожидается ММ-ДД)",
                message(() -> PlanJson.recurrenceFrom(map("kind", "YEARLY", "monthDay", "13-40"))));
        assertEquals("Поле «incomeFactor»: некорректное число «abc»",
                message(() -> PlanJson.whatIfFrom(map("incomeFactor", "abc"))));
        assertEquals("Поле «incomeFactor» должно быть числом, получено: логическое значение",
                message(() -> PlanJson.whatIfFrom(map("incomeFactor", true))));
        Map<String, Object> tx = map("id", "t1", "date", "2026-09-01", "kind", "INCOME", "amount", true);
        assertEquals("Поле «amount» должно быть суммой (строкой), получено: логическое значение",
                message(() -> PlanJson.oneTimeFrom(tx)));
        tx.put("amount", "abc");
        assertTrue(message(() -> PlanJson.oneTimeFrom(tx)).startsWith("Поле «amount»: "));
        assertTrue(message(() -> PlanJson.recurrenceFrom(map("kind", "MONTHLY", "dayOfMonth", 40L))).startsWith("Повтор: "));
    }

    /** @return сообщение {@link JsonException}, брошенного действием */
    private static String message(Runnable action) {
        return assertThrows(JsonException.class, action::run).getMessage();
    }

    /** @return изменяемый объект JSON из пар «ключ, значение» */
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }
}
