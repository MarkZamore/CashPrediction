package ru.cashprediction.parity.pipeline;

import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.ui.dump.DumpNormalizer;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.json.UiJson;

/** Читает строгую схему дампа и использует нормализатор ядра без собственной политики различий. */
public final class DumpTrees {
    private DumpTrees() { }

    /** Читает дамп схемы 1, отклоняя неизвестные или отсутствующие поля. */
    public static UiDump read(String json) {
        UiDump dump = (UiDump) decode(JsonParser.parse(json), UiDump.class);
        if (dump.schema() != UiDump.SCHEMA) throw new IllegalArgumentException("Unsupported dump schema");
        return dump;
    }

    /** Нормализует дамп; метаданные происхождения не являются содержимым интерфейса. */
    public static Object normalized(UiDump dump, Path cashMemory, String node) {
        // Адресацию по id и сохранение порядка реализует только ядро, а не стенд.
        Map<String, Object> tree = new LinkedHashMap<>(DumpNormalizer.comparisonTree(dump, cashMemory, node));
        tree.remove("client");
        // Перепись классов проверяется отдельно только у FX, содержимое виджетов не исключается.
        tree.remove("classCensus");
        return tree;
    }

    /** Восстанавливает только типы схемы UiDump, без загрузки произвольных имён классов из JSON. */
    private static Object decode(Object value, Type type) {
        if (value == null) return null;
        if (type instanceof ParameterizedType p) {
            if (p.getRawType() == List.class) return ((List<?>) value).stream()
                    .map(v -> decode(v, p.getActualTypeArguments()[0])).toList();
            if (p.getRawType() == Map.class) {
                Map<String, Object> result = new LinkedHashMap<>();
                ((Map<?, ?>) value).forEach((k, v) -> result.put((String) k, decode(v, p.getActualTypeArguments()[1])));
                return result;
            }
        }
        if (type == Object.class) return value;
        Class<?> c = (Class<?>) type;
        if (c == int.class || c == Integer.class) return new java.math.BigDecimal(value.toString()).intValueExact();
        if (c == long.class || c == Long.class) return new java.math.BigDecimal(value.toString()).longValueExact();
        if (c == double.class) return ((Number) value).doubleValue();
        if (c == boolean.class || c == Boolean.class) return (Boolean) value;
        if (c == String.class) return (String) value;
        if (!c.isRecord()) throw new IllegalArgumentException("Unsupported dump type: " + type);
        Map<?, ?> fields = (Map<?, ?>) value;
        RecordComponent[] components = c.getRecordComponents();
        if (fields.size() != components.length) throw new IllegalArgumentException("Invalid fields: " + c.getSimpleName());
        Object[] args = new Object[components.length];
        Class<?>[] signature = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            if (!fields.containsKey(component.getName())) throw new IllegalArgumentException("Missing " + component.getName());
            args[i] = decode(fields.get(component.getName()), component.getGenericType());
            signature[i] = component.getType();
        }
        try { return c.getDeclaredConstructor(signature).newInstance(args); }
        catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Invalid dump: " + c.getSimpleName(), e); }
    }
}
