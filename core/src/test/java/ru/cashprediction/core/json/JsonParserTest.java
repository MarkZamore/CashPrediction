package ru.cashprediction.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Тесты строгого разборщика JSON: типы значений, escape-последовательности, числа и сообщения об ошибках с позицией.
 */
class JsonParserTest {

    @Test
    void parsesObjectsPreservingKeyOrder() {
        Object value = JsonParser.parse(" {\"b\": 1, \"a\": [true, false, null], \"c\": {\"вложено\": \"да\"}} ");
        Map<String, Object> map = Json.asObject(value, "корень");
        assertEquals(List.of("b", "a", "c"), List.copyOf(map.keySet()));
        assertEquals(1L, map.get("b"));
        assertEquals(java.util.Arrays.asList(true, false, null), map.get("a"));
        assertEquals(Map.of("вложено", "да"), map.get("c"));
    }

    @Test
    void parsesStringEscapesAndCyrillic() {
        assertEquals("\"\\/\b\f\n\r\tAЖ", JsonParser.parse("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0041\\u0416\""));
        assertEquals("Семейный бюджет «2026» | <тест> & ok", JsonParser.parse("\"Семейный бюджет «2026» | <тест> & ok\""));
        // Суррогатная пара через \\u даёт один символ за пределами BMP.
        assertEquals("😀", JsonParser.parse("\"\\ud83d\\ude00\""));
        assertEquals("", JsonParser.parse("\"\""));
    }

    @Test
    void parsesNumbersIntoLongOrBigDecimal() {
        assertEquals(0L, JsonParser.parse("0"));
        assertEquals(-42L, JsonParser.parse("-42"));
        assertEquals(Long.MAX_VALUE, JsonParser.parse("9223372036854775807"));
        assertEquals(new BigDecimal("9223372036854775808"), JsonParser.parse("9223372036854775808"));
        assertEquals(new BigDecimal("1.50"), JsonParser.parse("1.50"));
        assertEquals(new BigDecimal("1e3"), JsonParser.parse("1e3"));
        assertEquals(new BigDecimal("-2.5E-3"), JsonParser.parse("-2.5E-3"));
        assertInstanceOf(BigDecimal.class, JsonParser.parse("1E+400"));
    }

    @Test
    void parsesLiteralsAndEmptyContainers() {
        assertEquals(Boolean.TRUE, JsonParser.parse("true"));
        assertEquals(Boolean.FALSE, JsonParser.parse("false"));
        assertNull(JsonParser.parse("null"));
        assertEquals(Map.of(), JsonParser.parse("{ }"));
        assertEquals(List.of(), JsonParser.parse("[\n]"));
    }

    /**
     * Случай ошибки разбора.
     *
     * @param text     текст
     * @param position ожидаемая позиция ошибки
     * @param fragment фрагмент ожидаемого сообщения
     */
    record ErrorCase(String text, int position, String fragment) {
    }

    static List<ErrorCase> errorCases() {
        return List.of(
                new ErrorCase("", 0, "пустой текст"),
                new ErrorCase("   ", 3, "пустой текст"),
                new ErrorCase("{", 1, "ожидалось имя поля"),
                new ErrorCase("[1,]", 3, "неожиданный символ «]»"),
                new ErrorCase("{\"a\" 1}", 5, "ожидалось «:»"),
                new ErrorCase("01", 1, "ведущие нули"),
                new ErrorCase("1.", 2, "после десятичной точки"),
                new ErrorCase("\"abc", 4, "не закрыта"),
                new ErrorCase("\"a\nb\"", 2, "управляющий символ U+000A"),
                new ErrorCase("[1] x", 4, "лишние символы"),
                new ErrorCase("{'a':1}", 1, "ожидалось имя поля"),
                new ErrorCase("tru", 0, "ожидалось «true»"),
                new ErrorCase("{\"a\":1,\"a\":2}", 7, "повторяющееся имя поля «a»"),
                new ErrorCase("\"\\x\"", 2, "недопустимая escape-последовательность"),
                new ErrorCase("\"\\u12G4\"", 5, "шестнадцатеричная цифра"),
                new ErrorCase("\"\\u12\"", 2, "неполная escape-последовательность"),
                new ErrorCase("-", 1, "после знака минус"),
                new ErrorCase("1e", 2, "в экспоненте"),
                new ErrorCase("\uFEFF{}", 0, "неожиданный символ"),
                new ErrorCase("{\"a\":1,}", 7, "ожидалось имя поля"),
                new ErrorCase("[1 2]", 3, "ожидалась «,» или «]»"),
                new ErrorCase("1e9999999999", 0, "не удаётся представить"));
    }

    @ParameterizedTest
    @MethodSource("errorCases")
    void reportsErrorsWithPosition(ErrorCase errorCase) {
        JsonException e = assertThrows(JsonException.class, () -> JsonParser.parse(errorCase.text()));
        assertEquals(errorCase.position(), e.position(), e.getMessage());
        assertTrue(e.getMessage().contains("(позиция " + errorCase.position() + ")"), e.getMessage());
        assertTrue(e.getMessage().contains(errorCase.fragment()), e.getMessage());
        assertTrue(e.getMessage().startsWith("Некорректный JSON, строка "), e.getMessage());
    }

    @Test
    void reportsLineAndColumnForMultilineText() {
        JsonException e = assertThrows(JsonException.class, () -> JsonParser.parse("{\n  \"a\": tru\n}"));
        assertEquals(9, e.position());
        assertTrue(e.getMessage().contains("строка 2, столбец 8 (позиция 9)"), e.getMessage());
    }

    @Test
    void limitsNestingDepth() {
        String deepest = "[".repeat(JsonParser.MAX_DEPTH) + "]".repeat(JsonParser.MAX_DEPTH);
        assertInstanceOf(List.class, JsonParser.parse(deepest));
        String tooDeep = "[".repeat(JsonParser.MAX_DEPTH + 1) + "]".repeat(JsonParser.MAX_DEPTH + 1);
        JsonException e = assertThrows(JsonException.class, () -> JsonParser.parse(tooDeep));
        assertTrue(e.getMessage().contains("вложенность"), e.getMessage());
    }

    @Test
    void parseObjectRejectsNonObjects() {
        JsonException e = assertThrows(JsonException.class, () -> JsonParser.parseObject("[1]"));
        assertTrue(e.getMessage().contains("должно быть JSON-объектом"), e.getMessage());
        assertThrows(JsonException.class, () -> JsonParser.parse(null));
    }
}
