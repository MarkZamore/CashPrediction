package ru.cashprediction.core.json;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.Map;

/**
 * Запись значений в JSON-текст: компактно ({@link #write}) или с отступами ({@link #writePretty}).
 *
 * <p>Поддерживаемые типы: {@code null}, {@link CharSequence}, {@link Character}, {@link Boolean},
 * целые ({@link Long}, {@link Integer}, {@link Short}, {@link Byte}, {@link BigInteger}),
 * {@link BigDecimal}, конечные {@link Double}/{@link Float}, {@link Enum} (пишется имя константы),
 * {@link Map} с ключами-строками и {@link Iterable}. Всё прочее — {@link JsonException}: молча
 * писать {@code toString()} неизвестного объекта значило бы получить JSON, который потом
 * не прочитается обратно.</p>
 *
 * <p>Экранируются кавычка, обратная косая черта и управляющие символы U+0000–U+001F (короткие формы
 * {@code \n \r \t \b \f}, остальные — {@code \}{@code u00XX}). Кириллица и прочие не-ASCII символы
 * пишутся как есть: весь обмен идёт в UTF-8, а читаемость снимка в отладчике и в regedit важнее
 * пары сэкономленных байт.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class JsonWriter {

    /** Отступ одного уровня в режиме {@link #writePretty}. */
    private static final String INDENT = "  ";

    private JsonWriter() {
    }

    /**
     * Записывает значение компактно, без пробелов и переводов строк.
     *
     * @param value значение поддерживаемого типа
     * @return JSON-текст
     * @throws JsonException если встретился неподдерживаемый тип, нестроковый ключ или NaN/бесконечность
     */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, false, 0);
        return sb.toString();
    }

    /**
     * Записывает значение с отступом в два пробела и переводами строк LF.
     * Пустые объекты и массивы пишутся как {@code {}} и {@code []}.
     *
     * @param value значение поддерживаемого типа
     * @return JSON-текст с отступами (без завершающего перевода строки)
     * @throws JsonException если встретился неподдерживаемый тип, нестроковый ключ или NaN/бесконечность
     */
    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, true, 0);
        return sb.toString();
    }

    /**
     * Экранирует строку и заключает её в кавычки.
     *
     * @param text строка
     * @return JSON-литерал строки, например {@code "a\"b"}
     */
    public static String quote(String text) {
        StringBuilder sb = new StringBuilder(text.length() + 2);
        writeString(sb, text);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value, boolean pretty, int level) {
        switch (value) {
            case null -> sb.append("null");
            case CharSequence s -> writeString(sb, s.toString());
            case Character c -> writeString(sb, String.valueOf(c));
            case Boolean b -> sb.append(b.booleanValue());
            case Long l -> sb.append(l.longValue());
            case Integer i -> sb.append(i.intValue());
            case Short s -> sb.append(s.shortValue());
            case Byte b -> sb.append(b.byteValue());
            case BigInteger bi -> sb.append(bi);
            // toString() может дать экспоненту вида 1E+3 — это корректный JSON и точное значение.
            case BigDecimal bd -> sb.append(bd);
            case Double d -> writeFloating(sb, d);
            case Float f -> writeFloating(sb, f.doubleValue());
            case Enum<?> e -> writeString(sb, e.name());
            case Map<?, ?> map -> writeObject(sb, map, pretty, level);
            case Iterable<?> iterable -> writeArray(sb, iterable, pretty, level);
            default -> throw new JsonException("Тип " + value.getClass().getName() + " нельзя записать в JSON");
        }
    }

    private static void writeFloating(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new JsonException("Значение " + d + " нельзя записать в JSON: допустимы только конечные числа");
        }
        // Double.toString даёт формы вроде 1.0E10, которые грамматика JSON допускает.
        sb.append(d);
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, boolean pretty, int level) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new JsonException("Имя поля JSON должно быть строкой, получено: " + entry.getKey());
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            newline(sb, pretty, level + 1);
            writeString(sb, key);
            sb.append(pretty ? ": " : ":");
            writeValue(sb, entry.getValue(), pretty, level + 1);
        }
        newline(sb, pretty, level);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> iterable, boolean pretty, int level) {
        Iterator<?> it = iterable.iterator();
        if (!it.hasNext()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        while (it.hasNext()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            newline(sb, pretty, level + 1);
            writeValue(sb, it.next(), pretty, level + 1);
        }
        newline(sb, pretty, level);
        sb.append(']');
    }

    private static void newline(StringBuilder sb, boolean pretty, int level) {
        if (pretty) {
            sb.append('\n');
            sb.append(INDENT.repeat(level));
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
