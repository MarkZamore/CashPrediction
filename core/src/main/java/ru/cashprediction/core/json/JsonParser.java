package ru.cashprediction.core.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.text.Texts;

/**
 * Строгий разборщик JSON по RFC 8259 без внешних библиотек.
 *
 * <p>Соответствие типов:</p>
 * <ul>
 *   <li>объект → {@link LinkedHashMap}{@code <String, Object>} (порядок ключей как в тексте);</li>
 *   <li>массив → {@link ArrayList}{@code <Object>};</li>
 *   <li>строка → {@link String};</li>
 *   <li>целое число, помещающееся в {@code long} → {@link Long}; любое другое число
 *       (дробь, экспонента, слишком большое целое) → {@link BigDecimal};</li>
 *   <li>{@code true}/{@code false} → {@link Boolean}; {@code null} → {@code null}.</li>
 * </ul>
 *
 * <p>Строгость важна, потому что JSON приходит из двух ненадёжных источников: из браузера (web-API)
 * и из реестра, где снимок мог быть обрезан сбоем. Поэтому отклоняются: хвостовые запятые,
 * одинарные кавычки, комментарии, ведущие нули, неэкранированные управляющие символы в строках,
 * повторяющиеся ключи (они почти всегда означают склейку двух документов), мусор после значения
 * и BOM. Глубина вложенности ограничена {@value #MAX_DEPTH}, чтобы злонамеренный запрос вида
 * {@code [[[[...} не переполнил стек сервера.</p>
 *
 * <p>Сообщения об ошибках доходят до пользователя (ответ web-API, отчёт восстановления), поэтому берутся из
 * каталога текстов: общий вид {@code json.parse.position} и причина {@code json.parse.*}.</p>
 *
 * <p>Класс без состояния (каждый вызов создаёт свой внутренний курсор), потокобезопасен.</p>
 */
public final class JsonParser {

    /** Максимальная глубина вложенности массивов и объектов. */
    public static final int MAX_DEPTH = 512;

    /** Разбираемый текст. */
    private final String text;

    /** Текущая позиция курсора. */
    private int pos;

    /** Текущая глубина вложенности. */
    private int depth;

    private JsonParser(String text) {
        this.text = text;
    }

    /**
     * Разбирает JSON-текст целиком.
     *
     * @param text JSON-текст; пробельные символы по краям допускаются
     * @return значение верхнего уровня (см. таблицу типов в описании класса)
     * @throws JsonException если текст не является корректным JSON; сообщение содержит позицию
     */
    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException(Texts.get("json.parse.null"));
        }
        JsonParser parser = new JsonParser(text);
        parser.skipWhitespace();
        if (parser.pos >= text.length()) {
            throw parser.error(Texts.get("json.parse.empty"));
        }
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.pos < text.length()) {
            throw parser.error(Texts.get("json.parse.trailing"));
        }
        return value;
    }

    /**
     * Разбирает текст, который обязан быть JSON-объектом.
     *
     * @param text JSON-текст
     * @return объект верхнего уровня
     * @throws JsonException если текст некорректен или значение верхнего уровня не объект
     */
    public static Map<String, Object> parseObject(String text) {
        return Json.asObject(parse(text), Texts.get("json.what.document"));
    }

    private Object readValue() {
        skipWhitespace();
        if (pos >= text.length()) {
            throw error(Texts.get("json.parse.unexpectedEnd"));
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield readNumber();
                }
                throw error(Texts.get("json.parse.unexpectedChar", describe(c)));
            }
        };
    }

    private Map<String, Object> readObject() {
        enter();
        pos++; // '{'
        Map<String, Object> result = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            depth--;
            return result;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error(Texts.get("json.parse.expectedKey"));
            }
            int keyStart = pos;
            String key = readString();
            skipWhitespace();
            if (peek() != ':') {
                throw error(Texts.get("json.parse.expectedColon"));
            }
            pos++;
            Object value = readValue();
            if (result.containsKey(key)) {
                throw errorAt(keyStart, Texts.get("json.parse.duplicateKey", key));
            }
            result.put(key, value);
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                depth--;
                return result;
            } else {
                throw error(Texts.get("json.parse.expectedCommaOrBrace"));
            }
        }
    }

    private List<Object> readArray() {
        enter();
        pos++; // '['
        List<Object> result = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            depth--;
            return result;
        }
        while (true) {
            result.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                depth--;
                return result;
            } else {
                throw error(Texts.get("json.parse.expectedCommaOrBracket"));
            }
        }
    }

    private String readString() {
        pos++; // открывающая кавычка
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) {
                throw error(Texts.get("json.parse.unclosedString"));
            }
            char c = text.charAt(pos);
            if (c == '"') {
                pos++;
                return sb.toString();
            }
            if (c == '\\') {
                pos++;
                if (pos >= text.length()) {
                    throw error(Texts.get("json.parse.unclosedString"));
                }
                char e = text.charAt(pos);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        // Нужны четыре символа после 'u': позиции pos+1..pos+4.
                        if (pos + 4 >= text.length()) {
                            throw error(Texts.get("json.parse.incompleteUnicodeEscape"));
                        }
                        int code = 0;
                        for (int i = 1; i <= 4; i++) {
                            int digit = Character.digit(text.charAt(pos + i), 16);
                            if (digit < 0) {
                                throw errorAt(pos + i, Texts.get("json.parse.badHexDigit"));
                            }
                            code = code * 16 + digit;
                        }
                        // Одиночные суррогаты грамматикой RFC 8259 разрешены, поэтому просто добавляем символ.
                        sb.append((char) code);
                        pos += 4;
                    }
                    default -> throw error(Texts.get("json.parse.badEscape", e));
                }
                pos++;
                continue;
            }
            if (c < 0x20) {
                throw error(Texts.get("json.parse.controlChar", describe(c)));
            }
            sb.append(c);
            pos++;
        }
    }

    private Object readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (pos >= text.length() || !isDigit(text.charAt(pos))) {
            throw error(Texts.get("json.parse.digitAfterMinus"));
        }
        if (text.charAt(pos) == '0') {
            pos++;
            if (pos < text.length() && isDigit(text.charAt(pos))) {
                throw error(Texts.get("json.parse.leadingZeros"));
            }
        } else {
            while (pos < text.length() && isDigit(text.charAt(pos))) {
                pos++;
            }
        }
        boolean integer = true;
        if (pos < text.length() && text.charAt(pos) == '.') {
            integer = false;
            pos++;
            if (pos >= text.length() || !isDigit(text.charAt(pos))) {
                throw error(Texts.get("json.parse.digitAfterPoint"));
            }
            while (pos < text.length() && isDigit(text.charAt(pos))) {
                pos++;
            }
        }
        if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
            integer = false;
            pos++;
            if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                pos++;
            }
            if (pos >= text.length() || !isDigit(text.charAt(pos))) {
                throw error(Texts.get("json.parse.digitInExponent"));
            }
            while (pos < text.length() && isDigit(text.charAt(pos))) {
                pos++;
            }
        }
        String literal = text.substring(start, pos);
        if (integer) {
            // Не больше 18 цифр гарантированно помещаются в long; длиннее проверяем через BigDecimal.
            try {
                return Long.parseLong(literal);
            } catch (NumberFormatException overflow) {
                return new BigDecimal(literal);
            }
        }
        try {
            return new BigDecimal(literal);
        } catch (NumberFormatException | ArithmeticException e) {
            // Например, экспонента за пределами int: грамматически верно, но непредставимо.
            throw errorAt(start, Texts.get("json.parse.numberUnrepresentable", literal));
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, pos)) {
            throw error(Texts.get("json.parse.unknownWord", literal));
        }
        pos += literal.length();
        return value;
    }

    private void enter() {
        depth++;
        if (depth > MAX_DEPTH) {
            throw error(Texts.get("json.parse.tooDeep", MAX_DEPTH));
        }
    }

    /** @return текущий символ или {@code \0}, если текст закончился (такого символа в JSON вне строк не бывает) */
    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private void skipWhitespace() {
        // RFC 8259 разрешает только четыре пробельных символа; неразрывный пробел или BOM — ошибка.
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private JsonException error(String detail) {
        return errorAt(pos, detail);
    }

    private JsonException errorAt(int position, String detail) {
        int at = Math.min(position, text.length());
        int line = 1;
        int column = 1;
        for (int i = 0; i < at; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new JsonException(Texts.get("json.parse.position", line, column, at, detail), at);
    }

    private static String describe(char c) {
        if (c < 0x20 || c == 0x7f) {
            return String.format("U+%04X", (int) c);
        }
        return "«" + c + "»";
    }
}
