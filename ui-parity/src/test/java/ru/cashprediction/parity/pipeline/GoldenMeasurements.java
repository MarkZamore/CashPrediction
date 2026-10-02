package ru.cashprediction.parity.pipeline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Отделяет отсутствующие измерения модели от требований к содержимому.
 * ModelDump не придумывает границы карточек и тулбара, а координату кнопки
 * оставляет нулевой. Эти измерения проверяются между настоящими клиентами;
 * заданные размеры окна, ненулевые координаты и порядок кнопок сохраняются.
 */
final class GoldenMeasurements {
    private GoldenMeasurements() { }

    /** Два независимых дерева для сравнения с моделью; исходные дампы не изменяются. */
    record Pair(Object expected, Object actual) { }

    /** Убирает только незаданные измерения закрытой схемы UiDump. */
    static Pair project(Object expected, Object actual) {
        return project(expected, actual, "");
    }

    /** Рекурсивно копирует карты, не исключая лишние поля или отсутствующие виджеты. */
    private static Pair project(Object expected, Object actual, String pointer) {
        if (!(expected instanceof Map<?, ?> left) || !(actual instanceof Map<?, ?> right))
            return new Pair(expected, actual);
        Map<String, Object> a = new LinkedHashMap<>(), b = new LinkedHashMap<>();
        for (var entry : left.entrySet()) {
            String key = (String) entry.getKey();
            String path = pointer + "/" + key.replace("~", "~0").replace("/", "~1");
            if (!right.containsKey(key)) { a.put(key, entry.getValue()); continue; }
            Object value = right.get(key);
            if (unspecified(path, entry.getValue(), value)) continue;
            Pair pair = project(entry.getValue(), value, path);
            a.put(key, pair.expected()); b.put(key, pair.actual());
        }
        right.forEach((key, value) -> { if (!left.containsKey(key)) b.put((String) key, value); });
        return new Pair(Collections.unmodifiableMap(a), Collections.unmodifiableMap(b));
    }

    /** Значения состояния, текст, оформление и явно заданные границы не являются допусками. */
    private static boolean unspecified(String path, Object expected, Object actual) {
        if (path.equals("/frame/regions"))
            return expected instanceof Map<?, ?> m && m.isEmpty() && actual instanceof Map<?, ?>;
        if (path.matches("/(?:toolbar/items|summary/cards|windows|popups)/[^/]+/bounds"))
            return expected == null && (actual == null || actual instanceof Map<?, ?>);
        if (path.matches("/(?:windows/[^/]+|alerts/[^/]+|screens/[^/]+|table)/"
                + "(?:buttons|placeholderButtons)/[^/]+/x"))
            return expected instanceof Number n && n.doubleValue() == 0 && actual instanceof Number;
        return false;
    }
}
