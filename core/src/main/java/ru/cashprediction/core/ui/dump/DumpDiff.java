package ru.cashprediction.core.ui.dump;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.math.BigDecimal;

/**
 * Сравнение двух деревьев JSON дампов с адресами расхождений в виде JSON Pointer (архитектура §6.1).
 *
 * <p>Списки сравниваются по индексу, объекты — по ключам (лишний и отсутствующий ключ — расхождения); числа границ
 * сравниваются с допуском, если он задан; результат упорядочен по указателю. В деревьях
 * {@link DumpNormalizer#comparisonTree(UiDump)} список {@code $order} проверяет относительный порядок общих id:
 * вставки и удаления отражаются отдельными именованными узлами, перестановки остаются расхождениями.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class DumpDiff {

    /**
     * Одно расхождение.
     *
     * @param pointer  JSON Pointer, например {@code /menuBar/file/children/file.exit/text}
     * @param expected ожидаемое значение (дерево JSON) или {@code null}, если ключа нет
     * @param actual   фактическое значение или {@code null}, если ключа нет
     */
    public record Difference(String pointer, Object expected, Object actual) {
    }

    private DumpDiff() {
    }

    /**
     * Расхождения двух деревьев.
     *
     * @param expected    эталон
     * @param actual      фактический дамп
     * @param boxTolerance допуск для полей {@code x}, {@code y}, {@code width}, {@code height} в пикселях (0 — точно)
     * @return расхождения по возрастанию указателя
     */
    public static List<Difference> diff(Object expected, Object actual, double boxTolerance) {
        if (boxTolerance < 0 || !Double.isFinite(boxTolerance)) {
            throw new IllegalArgumentException("boxTolerance");
        }
        List<Difference> differences = new ArrayList<>();
        compare("", expected, actual, boxTolerance, differences);
        differences.sort(java.util.Comparator.comparing(Difference::pointer));
        return List.copyOf(differences);
    }

    /** Рекурсивно сравнивает JSON-дерево и записывает адреса по RFC 6901. */
    private static void compare(String pointer, Object expected, Object actual, double tolerance,
                                List<Difference> differences) {
        if (expected instanceof Map<?, ?> expectedMap && actual instanceof Map<?, ?> actualMap) {
            TreeSet<String> keys = new TreeSet<>();
            expectedMap.keySet().forEach(key -> keys.add(String.valueOf(key)));
            actualMap.keySet().forEach(key -> keys.add(String.valueOf(key)));
            for (String key : keys) {
                boolean hasExpected = expectedMap.containsKey(key);
                boolean hasActual = actualMap.containsKey(key);
                String child = pointer + "/" + escape(key);
                if (!hasExpected || !hasActual) {
                    differences.add(new Difference(child, hasExpected ? expectedMap.get(key) : null,
                            hasActual ? actualMap.get(key) : null));
                } else {
                    if (key.equals("$order") && expectedMap.get(key) instanceof List<?> left
                            && actualMap.get(key) instanceof List<?> right) {
                        // Вставки и удаления уже представлены именованными узлами; проверяется порядок общих узлов.
                        List<?> commonLeft = left.stream().filter(right::contains).toList();
                        List<?> commonRight = right.stream().filter(left::contains).toList();
                        compare(child, commonLeft, commonRight, tolerance, differences);
                    } else {
                        compare(child, expectedMap.get(key), actualMap.get(key), tolerance, differences);
                    }
                }
            }
            return;
        }
        if (expected instanceof List<?> expectedList && actual instanceof List<?> actualList) {
            int common = Math.min(expectedList.size(), actualList.size());
            for (int index = 0; index < common; index++) {
                compare(pointer + "/" + index, expectedList.get(index), actualList.get(index), tolerance, differences);
            }
            for (int index = common; index < expectedList.size(); index++) {
                differences.add(new Difference(pointer + "/" + index, expectedList.get(index), null));
            }
            for (int index = common; index < actualList.size(); index++) {
                differences.add(new Difference(pointer + "/" + index, null, actualList.get(index)));
            }
            return;
        }
        if (expected instanceof Number expectedNumber && actual instanceof Number actualNumber
                && isBoxCoordinate(pointer) && decimal(expectedNumber).subtract(decimal(actualNumber)).abs()
                    .compareTo(BigDecimal.valueOf(tolerance)) <= 0) {
            return;
        }
        if (!sameValue(expected, actual)) {
            differences.add(new Difference(pointer, expected, actual));
        }
    }

    /** Числа разных Java-типов сравниваются по значению, а не по типу парсера JSON. */
    private static boolean sameValue(Object expected, Object actual) {
        if (expected instanceof Number left && actual instanceof Number right) {
            return decimal(left).compareTo(decimal(right)) == 0;
        }
        return Objects.equals(expected, actual);
    }

    /** Сохраняет точность целых счётчиков и десятичных значений без промежуточного double. */
    private static BigDecimal decimal(Number value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    /** Допуск применяется к геометрии виджетов, но не к одноимённым полям данных. */
    private static boolean isBoxCoordinate(String pointer) {
        int slash = pointer.lastIndexOf('/');
        String field = slash < 0 ? pointer : pointer.substring(slash + 1);
        boolean coordinate = field.equals("x") || field.equals("y") || field.equals("width") || field.equals("height");
        String parent = slash < 0 ? "" : pointer.substring(0, slash);
        return coordinate && (parent.endsWith("/bounds") || parent.matches("/frame/regions/[^/]+"))
                || field.equals("x") && (parent.matches(".*/buttons/[^/]+")
                        || parent.matches("/table/placeholderButtons/[^/]+"));
    }

    /** Экранирует сегмент JSON Pointer. */
    private static String escape(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }
}
