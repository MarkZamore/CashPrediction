package ru.cashprediction.core.ui.dump;

import java.nio.file.Path;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Collections;
import java.lang.reflect.ParameterizedType;

/**
 * Нормализация дампа перед сравнением (архитектура §6.1): время {@code HH:mm:ss} → {@code <time>}, путь CashMemory →
 * {@code <CashMemory>}, узел реестра → {@code <node>}, ширины и координаты округляются до 2 px.
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class DumpNormalizer {

    private static final Pattern CLOCK = Pattern.compile("(?<![0-9])(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?![0-9])");
    /** Версия среды меняется между локальным JDK и CI, но нормализуется только в служебной строке about. */
    private static final Pattern ABOUT_JAVA = Pattern.compile("(?m)(, Java )[0-9][A-Za-z0-9.+_-]*(\\.$)");

    private DumpNormalizer() {
    }

    /**
     * Нормализует дамп.
     *
     * @param dump         дамп клиента или модели
     * @param cashMemory   папка CashMemory запуска
     * @param registryNode узел реестра запуска (например {@code ru/cashprediction/selftest/<uuid>/fx}) или пустая строка
     * @return нормализованный дамп
     */
    public static UiDump normalize(UiDump dump, Path cashMemory, String registryNode) {
        Objects.requireNonNull(dump, "dump");
        Objects.requireNonNull(cashMemory, "cashMemory");
        return (UiDump) normalizeValue(dump, cashMemory, registryNode);
    }

    /**
     * Создаёт отдельное дерево сравнения из нормализованного дампа, не меняя JSON-протокол.
     * Именованные списки становятся объектами с ключами id, purpose, rowId, target или kind.
     * Служебный список {@code $order} сохраняет исходный порядок; повторные и пустые ключи запрещены.
     * Метаданные client и classCensus остаются: потребитель явно исключает их при проверке паритета.
     *
     * @param dump уже нормализованный дамп
     * @return дерево Map/List с неизменяемыми коллекциями и исходной точностью чисел
     */
    public static Map<String, Object> comparisonTree(UiDump dump) {
        Objects.requireNonNull(dump, "dump");
        return recordTree(dump);
    }

    /**
     * Нормализует дамп и создаёт дерево сравнения; сериализация файлов продолжает использовать UiJson.
     *
     * @param dump исходный дамп
     * @param cashMemory папка запуска
     * @param registryNode узел реестра
     * @return каноническое дерево сравнения
     */
    public static Map<String, Object> comparisonTree(UiDump dump, Path cashMemory, String registryNode) {
        return comparisonTree(normalize(dump, cashMemory, registryNode));
    }

    /** Преобразует только записи замороженной схемы, сохраняя null и числовые типы. */
    private static Map<String, Object> recordTree(Object record) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                Object raw = component.getAccessor().invoke(record);
                if (raw instanceof List<?> list && component.getGenericType() instanceof ParameterizedType type
                        && type.getActualTypeArguments()[0] instanceof Class<?> elementType
                        && identityField(elementType) != null) {
                    result.put(component.getName(), keyedList(list, elementType));
                } else {
                    result.put(component.getName(), treeValue(raw));
                }
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("UiDump schema", exception);
        }
        return Collections.unmodifiableMap(result);
    }

    /** Выбирает стабильный ключ по типу, а не по случайному наличию поля в JSON. */
    private static String identityField(Class<?> type) {
        if (type == UiDump.Window.class || type == UiDump.Alert.class) return "purpose";
        if (type == UiDump.Row.class) return "rowId";
        if (type == UiDump.ContextMenu.class) return "target";
        if (type == UiDump.Popup.class || type == UiDump.Screen.class) return "kind";
        if (type == UiDump.MenuItem.class || type == UiDump.ToolbarItem.class || type == UiDump.Card.class
                || type == UiDump.Segment.class || type == UiDump.Field.class || type == UiDump.Button.class) return "id";
        return null;
    }

    /** Хранит порядок отдельно от ключей: сортировка объектов не скрывает перестановки виджетов. */
    private static Map<String, Object> keyedList(List<?> list, Class<?> elementType) throws ReflectiveOperationException {
        Map<String, Object> result = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (Object element : list) {
            String key = (String) elementType.getMethod(identityField(elementType)).invoke(element);
            if ((key == null || key.isBlank()) && (elementType == UiDump.Window.class || elementType == UiDump.Alert.class)) {
                key = (String) elementType.getMethod("id").invoke(element);
            }
            if (key == null || key.isBlank() || key.equals("$order") || result.containsKey(key)) {
                throw new IllegalArgumentException("duplicate or empty dump identity: " + key);
            }
            result.put(key, recordTree(element));
            order.add(key);
        }
        result.put("$order", List.copyOf(order));
        return Collections.unmodifiableMap(result);
    }

    /** Копирует неименованные списки и карты без потери порядка, null и значащих данных. */
    private static Object treeValue(Object value) {
        if (value == null) return null;
        if (value.getClass() == UiDump.class || value.getClass().getEnclosingClass() == UiDump.class) {
            return recordTree(value);
        }
        if (value instanceof List<?> list) return list.stream().map(DumpNormalizer::treeValue).toList();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, element) -> result.put((String) key, treeValue(element)));
            return Collections.unmodifiableMap(result);
        }
        return value;
    }

    /**
     * Нормализует один текст по тем же правилам.
     *
     * @param text         текст
     * @param cashMemory   папка CashMemory
     * @param registryNode узел реестра или пустая строка
     * @return нормализованный текст
     */
    public static String normalizeText(String text, Path cashMemory, String registryNode) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(cashMemory, "cashMemory");
        String result = replacePath(text, cashMemory.toAbsolutePath().normalize().toString(), "<CashMemory>");
        if (registryNode != null && !registryNode.isBlank()) {
            result = replacePath(result, registryNode, "<node>");
        }
        return CLOCK.matcher(result).replaceAll("<time>");
    }

    /** Меняет только заданный путь и его дочерние пути, не похожие имена соседних каталогов. */
    private static String replacePath(String text, String path, String replacement) {
        String portable = path.replace('\\', '/');
        String[] variants = {path, portable, portable.replace('/', '\\')};
        String result = text;
        for (String variant : variants) {
            Pattern pattern = Pattern.compile(Pattern.quote(variant) + "(?=$|[\\\\/\\s\\\"'<>),;:])");
            result = pattern.matcher(result).replaceAll(Matcher.quoteReplacement(replacement));
        }
        return result;
    }

    /**
     * Копирует закрытую схему UiDump, сохраняя порядок списков и ключи идентификаторов.
     * Обход компонентов записи автоматически охватывает новые вложенные тексты схемы;
     * сторонние записи и произвольные объекты намеренно не преобразуются.
     */
    private static Object normalizeValue(Object value, Path cashMemory, String registryNode) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return normalizeText(text, cashMemory, registryNode);
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object element : list) {
                result.add(normalizeValue(element, cashMemory, registryNode));
            }
            return List.copyOf(result);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            map.forEach((key, element) -> result.put(key, normalizeValue(element, cashMemory, registryNode)));
            return Map.copyOf(result);
        }
        Class<?> type = value.getClass();
        if (!type.isRecord() || type != UiDump.class && type.getEnclosingClass() != UiDump.class) {
            return value;
        }
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] values = new Object[components.length];
        try {
            for (int index = 0; index < components.length; index++) {
                RecordComponent component = components[index];
                parameterTypes[index] = component.getType();
                Object raw = component.getAccessor().invoke(value);
                if (geometry(type, component.getName())) {
                    double coordinate = ((Number) raw).doubleValue();
                    if (!Double.isFinite(coordinate)) {
                        throw new IllegalArgumentException("non-finite geometry");
                    }
                    values[index] = Math.round(coordinate / 2.0) * 2.0;
                } else {
                    values[index] = normalizeValue(raw, cashMemory, registryNode);
                    if (value instanceof UiDump.Alert alert && "about".equals(alert.purpose())
                            && component.getName().equals("content")) {
                        values[index] = ABOUT_JAVA.matcher((String) values[index]).replaceAll("$1<java>$2");
                    }
                }
            }
            return type.getDeclaredConstructor(parameterTypes).newInstance(values);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("UiDump schema", exception);
        }
    }

    /** Только геометрические компоненты округляются; суммы, индексы и счётчики остаются точными. */
    private static boolean geometry(Class<?> type, String component) {
        return type == UiDump.Box.class || type == UiDump.Size.class
                || type == UiDump.Button.class && component.equals("x")
                || type == UiDump.Frame.class && (component.equals("contentWidth") || component.equals("contentHeight"));
    }
}
