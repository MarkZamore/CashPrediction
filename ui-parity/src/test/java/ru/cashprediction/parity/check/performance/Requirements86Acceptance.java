package ru.cashprediction.parity.check.performance;

import java.nio.file.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import ru.cashprediction.core.json.*;
import static ru.cashprediction.parity.check.performance.DomainLatencyGate.*;

/** Адаптирует JSON к существующему gate, не запускает UI. */
public final class Requirements86Acceptance {
    private Requirements86Acceptance() { }

    /** Проверяет supplied contract, а не происхождение paint. */
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[0].equals("verify")) throw new IllegalArgumentException("USAGE verify CONTRACT RUN");
        Contract contract = decode(Files.readString(Path.of(args[1])), Contract.class);
        Run run = decode(Files.readString(Path.of(args[2])), Run.class);
        DomainLatencyGate.verify(contract, run, Instant.now());
        System.out.println("LATENCY_CONTRACT_VALIDATED; actual input/paint provenance and UX signoff still required");
    }

    /** Делает JSON-compatible копию record модели для сохранения или механических тестов адаптера. */
    static Object encode(Object value) throws Exception {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Enum<?> || value instanceof Instant || value instanceof Duration) return value.toString();
        if (value instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            for (var entry : map.entrySet()) result.put(entry.getKey().toString(), encode(entry.getValue()));
            return result;
        }
        if (value instanceof Collection<?> list) {
            var result = new ArrayList<Object>();
            for (var item : list) result.add(encode(item));
            return result;
        }
        if (!value.getClass().isRecord()) throw new IllegalArgumentException("UNSUPPORTED_JSON_TYPE");
        var result = new LinkedHashMap<String, Object>();
        for (var component : value.getClass().getRecordComponents()) result.put(component.getName(), encode(component.getAccessor().invoke(value)));
        return result;
    }

    /** Читает все компоненты record, отвергает неизвестные и отсутствующие поля JSON. */
    static <T> T decode(String json, Class<T> type) throws Exception {
        return type.cast(convert(JsonParser.parse(json), type));
    }

    /** Преобразует вложенные typed контейнеры, не вводя второй latency checker. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convert(Object value, Type type) throws Exception {
        if (type instanceof ParameterizedType p) {
            if (p.getRawType() == List.class) {
                var result = new ArrayList<Object>();
                for (Object item : (List<?>) value) result.add(convert(item, p.getActualTypeArguments()[0]));
                return result;
            }
            if (p.getRawType() == Map.class) {
                var result = new LinkedHashMap<Object, Object>();
                for (var entry : ((Map<?, ?>) value).entrySet()) result.put(
                        convert(entry.getKey(), p.getActualTypeArguments()[0]), convert(entry.getValue(), p.getActualTypeArguments()[1]));
                return result;
            }
            throw new IllegalArgumentException("JSON_GENERIC_TYPE");
        }
        Class<?> c = (Class<?>) type;
        if (value == null) return null;
        if (c == String.class) return (String) value;
        if (c == long.class) return new BigDecimal(value.toString()).longValueExact();
        if (c == Instant.class) return Instant.parse((String) value);
        if (c == Duration.class) return Duration.parse((String) value);
        if (c.isEnum()) return Enum.valueOf((Class) c, (String) value);
        if (!c.isRecord()) throw new IllegalArgumentException("JSON_RECORD_TYPE");
        Map<?, ?> map = (Map<?, ?>) value;
        var components = c.getRecordComponents();
        Set<String> names = new HashSet<>();
        for (var component : components) names.add(component.getName());
        if (!names.equals(map.keySet())) throw new IllegalArgumentException("JSON_RECORD_FIELDS");
        var signature = new Class<?>[components.length]; var args = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            signature[i] = components[i].getType();
            args[i] = convert(map.get(components[i].getName()), components[i].getGenericType());
        }
        return c.getDeclaredConstructor(signature).newInstance(args);
    }


}
