package ru.cashprediction.core.ui.selftest.paint;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.CRC32;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;

/**
 * Строгий UTF-8 JSON companion-схемы S5 поверх существующего JSON ядра.
 * Имена компонентов записей задают точные ключи; лишние, отсутствующие и повторные ключи запрещены
 * на всех уровнях. Nullable-поля всё равно обязательны. Нет загрузки классов по внешним именам.
 * Массивы Transform и Point имеют фиксированный порядок, PNG-байты пишутся каноническим base64.
 * startNanos/endNanos/deadlineNanos пишутся строками; счётчики - точными числами, безопасными для JS.
 * Сообщения исключений предназначены только разработчику selftest.
 */
public final class PaintObservationCodec {
    /** Предельный размер JSON наблюдения в UTF-8. */
    public static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    /** Предельный размер оригинального снимка PNG. */
    public static final int MAX_PNG_BYTES = 4 * 1024 * 1024;
    static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final Set<Class<?>> ROOTS = Set.of(PaintObservation.class, PaintCaptureRequest.class,
            WidgetCapture.Commit.class, WidgetCapture.FileDigest.class);

    private PaintObservationCodec() { }

    /** Записывает наблюдение схемы 1, без эталонных данных. */
    public static byte[] write(PaintObservation observation) { return encode(Objects.requireNonNull(observation)); }

    /** Разбирает ограниченный UTF-8 документ и проверяет все вложенные поля. */
    public static PaintObservation read(byte[] utf8) { return parse(utf8, PaintObservation.class); }

    /** Записывает намерение опыта, отдельно от actual-наблюдения. */
    public static byte[] writeRequest(PaintCaptureRequest request) { return encode(Objects.requireNonNull(request)); }

    /** Разбирает намерение с точным набором ключей. */
    public static PaintCaptureRequest readRequest(byte[] utf8) { return parse(utf8, PaintCaptureRequest.class); }

    /** Записывает commit-манифест, который интегратор сохраняет последним. */
    public static byte[] writeCommit(WidgetCapture.Commit commit) { return encode(Objects.requireNonNull(commit)); }

    /** Разбирает commit-манифест, не подтверждая существование файлов на диске. */
    public static WidgetCapture.Commit readCommit(byte[] utf8) { return parse(utf8, WidgetCapture.Commit.class); }

    /** Вычисляет SHA-256 точных байтов, без перекодирования или декодирования изображения. */
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static byte[] encode(Object value) {
        byte[] bytes = JsonWriter.write(tree(value, "")).getBytes(StandardCharsets.UTF_8);
        PaintObservation.require(bytes.length <= MAX_JSON_BYTES, "paint JSON limit");
        return bytes;
    }

    private static <T> T parse(byte[] bytes, Class<T> type) {
        try {
            Object root = JsonParser.parse(utf8(bytes));
            PaintObservation.require(root != null, "required paint document");
            return type.cast(convert(root, type, ""));
        }
        catch (IllegalArgumentException error) { throw error; }
        catch (RuntimeException error) { throw new IllegalArgumentException("invalid paint JSON", error); }
    }

    static String utf8(byte[] bytes) {
        Objects.requireNonNull(bytes); PaintObservation.require(bytes.length <= MAX_JSON_BYTES, "paint JSON limit");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) { throw new IllegalArgumentException("UTF-8", error); }
    }

    private static Object tree(Object value, String field) {
        if (value == null || value instanceof Boolean) return value;
        if (value instanceof String string) { unicode(string); return string; }
        if (value instanceof UUID uuid) return uuid.toString();
        if (value instanceof PaintCaptureRequest.CardState state) return state.name();
        if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
        if (value instanceof Number number) {
            if (nanos(field)) return number.toString();
            if (value instanceof Long counter) PaintObservation.require(counter >= 0 && counter <= MAX_SAFE_INTEGER, "counter precision");
            if (value instanceof Double decimal) PaintObservation.finite(decimal);
            return number;
        }
        if (value instanceof PaintObservation.Point p) return List.of(p.x(), p.y());
        if (value instanceof PaintObservation.Transform t) return List.of(t.a(), t.b(), t.c(), t.d(), t.tx(), t.ty());
        if (value instanceof List<?> list) return list.stream().map(element -> tree(element, "")).toList();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, element) -> { unicode((String) key); result.put((String) key, tree(element, "")); });
            return result;
        }
        Class<?> type = value.getClass();
        PaintObservation.require(contract(type) && type.isRecord(), "paint record type");
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            for (var component : type.getRecordComponents())
                result.put(component.getName(), tree(component.getAccessor().invoke(value), component.getName()));
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        return result;
    }

    private static Object convert(Object value, Type type, String field) {
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (parameterized.getRawType() == List.class) {
                PaintObservation.require(value instanceof List<?>, "array type");
                List<?> list = (List<?>) value; PaintObservation.require(list.size() <= 512, "array limit");
                List<Object> result = new ArrayList<>();
                for (Object element : list) { Objects.requireNonNull(element); result.add(convert(element, arguments[0], "")); }
                return List.copyOf(result);
            }
            PaintObservation.require(parameterized.getRawType() == Map.class && value instanceof Map<?, ?>, "map type");
            Map<?, ?> map = (Map<?, ?>) value; PaintObservation.require(map.size() <= 128, "map limit");
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, element) -> {
                PaintObservation.text((String) key); unicode((String) key); Objects.requireNonNull(element);
                result.put((String) key, convert(element, arguments[1], ""));
            });
            return Map.copyOf(result);
        }
        Class<?> target = (Class<?>) type;
        if (value == null) return null;
        if (target == String.class) {
            PaintObservation.require(value instanceof String, "string type"); unicode((String) value); return value;
        }
        if (target == UUID.class) {
            PaintObservation.require(value instanceof String, "UUID type");
            UUID uuid = UUID.fromString((String) value); PaintObservation.require(uuid.toString().equals(value), "canonical UUID"); return uuid;
        }
        if (target == PaintCaptureRequest.CardState.class) {
            PaintObservation.require(value instanceof String, "state type"); return PaintCaptureRequest.CardState.valueOf((String) value);
        }
        if (target == byte[].class) {
            PaintObservation.require(value instanceof String, "base64 type"); String encoded = (String) value;
            PaintObservation.require(encoded.length() <= ((PaintObservation.MAX_ASSET_BYTES + 2) / 3) * 4, "base64 limit");
            byte[] decoded = Base64.getDecoder().decode(encoded);
            PaintObservation.require(Base64.getEncoder().encodeToString(decoded).equals(encoded), "canonical base64"); return decoded;
        }
        if (target == boolean.class || target == Boolean.class) {
            PaintObservation.require(value instanceof Boolean, "boolean type"); return value;
        }
        if (target == long.class && nanos(field)) {
            PaintObservation.require(value instanceof String && ((String) value).matches("0|-?[1-9][0-9]{0,18}"), "nanoTime decimal string");
            return Long.parseLong((String) value);
        }
        if (target == int.class || target == Integer.class || target == long.class || target == double.class || target == Double.class) {
            PaintObservation.require(value instanceof Number, "number type");
            BigDecimal decimal = new BigDecimal(value.toString());
            if (target == int.class || target == Integer.class) return decimal.intValueExact();
            if (target == long.class) {
                long counter = decimal.longValueExact(); PaintObservation.require(counter >= 0 && counter <= MAX_SAFE_INTEGER, "counter precision"); return counter;
            }
            double number = decimal.doubleValue(); PaintObservation.finite(number); return number;
        }
        if (target == PaintObservation.Point.class || target == PaintObservation.Transform.class) {
            PaintObservation.require(value instanceof List<?>, "coordinate array"); List<?> list = (List<?>) value;
            int length = target == PaintObservation.Point.class ? 2 : 6; PaintObservation.require(list.size() == length, "coordinate length");
            double[] numbers = new double[length];
            for (int i = 0; i < length; i++) { Objects.requireNonNull(list.get(i)); numbers[i] = (double) convert(list.get(i), double.class, ""); }
            return length == 2 ? new PaintObservation.Point(numbers[0], numbers[1])
                    : new PaintObservation.Transform(numbers[0], numbers[1], numbers[2], numbers[3], numbers[4], numbers[5]);
        }
        PaintObservation.require(contract(target) && target.isRecord() && value instanceof Map<?, ?>, "record type");
        Map<?, ?> object = (Map<?, ?>) value; var components = target.getRecordComponents();
        Set<String> keys = new java.util.HashSet<>(); for (var component : components) keys.add(component.getName());
        PaintObservation.require(object.keySet().equals(keys), "exact keys: " + target.getSimpleName());
        Object[] values = new Object[components.length]; Class<?>[] signature = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            var component = components[i]; Object element = object.get(component.getName());
            PaintObservation.require(element != null || nullable(target, component.getName()), "required: " + component.getName());
            values[i] = convert(element, component.getGenericType(), component.getName()); signature[i] = component.getType();
        }
        try { return target.getConstructor(signature).newInstance(values); }
        catch (InvocationTargetException error) { throw new IllegalArgumentException("invalid " + target.getSimpleName(), error.getCause()); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static boolean contract(Class<?> type) { return ROOTS.contains(type) || type.getEnclosingClass() == PaintObservation.class; }
    private static boolean nanos(String field) { return Set.of("startNanos", "endNanos", "deadlineNanos").contains(field); }
    private static boolean nullable(Class<?> type, String field) {
        return type == PaintObservation.Viewport.class && Set.of("screenContentOrigin", "browserDpr").contains(field)
                || type == PaintObservation.Interaction.class && Set.of("pointer", "focusOwner").contains(field)
                || type == PaintObservation.Surface.class && field.equals("screenOrigin")
                || type == PaintObservation.Icon.class && Set.of("assetId", "semanticKey", "variantToken", "rawArgb", "sourceViewport").contains(field)
                || type == PaintObservation.BorderLayer.class && field.equals("pathRadii")
                || type == PaintObservation.Card.class && Set.of("focusVisible", "focusOwner", "physicalHit").contains(field);
    }
    private static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                PaintObservation.require(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)), "Unicode surrogate"); i++;
            } else PaintObservation.require(!Character.isLowSurrogate(c), "Unicode surrogate");
        }
    }

    /** Проверяет структуру PNG и CRC без графических библиотек; полноценное декодирование остаётся у клиента. */
    static void pngDimensions(byte[] bytes, int width, int height) {
        PaintObservation.dimension(width); PaintObservation.dimension(height);
        PaintObservation.require(bytes.length >= 45 && Arrays.equals(Arrays.copyOf(bytes, 8),
                new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}), "PNG signature");
        ByteBuffer buffer = ByteBuffer.wrap(bytes); int offset = 8; boolean header = false, data = false, end = false;
        while (offset < bytes.length) {
            PaintObservation.require(bytes.length - offset >= 12, "PNG chunk"); int length = buffer.getInt(offset);
            PaintObservation.require(length >= 0 && length <= bytes.length - offset - 12, "PNG chunk length");
            String type = new String(bytes, offset + 4, 4, StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32(); crc.update(bytes, offset + 4, length + 4);
            PaintObservation.require((int) crc.getValue() == buffer.getInt(offset + 8 + length), "PNG CRC");
            if (!header) {
                PaintObservation.require(type.equals("IHDR") && length == 13, "PNG IHDR"); header = true;
                PaintObservation.require(buffer.getInt(offset + 8) == width && buffer.getInt(offset + 12) == height, "PNG dimensions");
                int depth = Byte.toUnsignedInt(bytes[offset + 16]), color = Byte.toUnsignedInt(bytes[offset + 17]);
                boolean format = switch (color) {
                    case 0 -> Set.of(1, 2, 4, 8, 16).contains(depth);
                    case 2, 4, 6 -> depth == 8 || depth == 16;
                    case 3 -> Set.of(1, 2, 4, 8).contains(depth);
                    default -> false;
                };
                PaintObservation.require(format && bytes[offset + 18] == 0 && bytes[offset + 19] == 0
                        && (bytes[offset + 20] == 0 || bytes[offset + 20] == 1), "PNG format");
            } else PaintObservation.require(!type.equals("IHDR"), "duplicate PNG IHDR");
            if (type.equals("IDAT")) data = true;
            offset += length + 12;
            if (type.equals("IEND")) { PaintObservation.require(length == 0 && data && offset == bytes.length, "PNG IEND"); end = true; }
        }
        PaintObservation.require(header && data && end, "incomplete PNG");
    }
}
