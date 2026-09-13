package ru.cashprediction.parity.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Слепок поддерева {@code java.util.prefs}: для каждого узла список ключей и хеш значений.
 *
 * <p>Реестр Windows хранит время изменения ключа, но {@code java.util.prefs} его не показывает, а чужие
 * утилиты стенду запрещены. Поэтому «отметкой изменения» узла служат SHA-256 всех пар ключ=значение
 * и значение {@code snapshot.time} (время последней записи снимка сеанса), если оно есть: любое изменение
 * узла меняет хеш.</p>
 *
 * @param root   путь корня слепка, например {@code ru/cashprediction/session}
 * @param exists существовал ли корень в момент слепка
 * @param nodes  узлы поддерева по полному пути (корень включён)
 */
public record RegistryTreeSnapshot(String root, boolean exists, SortedMap<String, NodeStamp> nodes) {

    /**
     * Отметка одного узла.
     *
     * @param path         полный путь узла
     * @param keys         имена ключей по алфавиту
     * @param valuesSha256 SHA-256 строк {@code ключ=значение\n} по алфавиту ключей (hex)
     * @param snapshotTime значение {@code snapshot.time} или пустая строка
     */
    public record NodeStamp(String path, List<String> keys, String valuesSha256, String snapshotTime) {

        /** Копирует список ключей. */
        public NodeStamp {
            Objects.requireNonNull(path, "path");
            keys = List.copyOf(keys);
            Objects.requireNonNull(valuesSha256, "valuesSha256");
            snapshotTime = snapshotTime == null ? "" : snapshotTime;
        }
    }

    /** Делает карту узлов неизменяемой и упорядоченной. */
    public RegistryTreeSnapshot {
        Objects.requireNonNull(root, "root");
        nodes = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(nodes));
    }

    /**
     * Перечисляет различия между этим слепком (до) и более поздним (после).
     *
     * @param after слепок того же корня, снятый позже
     * @return человекочитаемые различия; пустой список — поддерево не менялось
     */
    public List<String> differences(RegistryTreeSnapshot after) {
        List<String> result = new ArrayList<>();
        if (!root.equals(after.root)) {
            result.add("different roots: " + root + " vs " + after.root);
            return result;
        }
        if (exists != after.exists) {
            result.add(root + (after.exists ? " was created" : " was removed"));
        }
        TreeSet<String> paths = new TreeSet<>(nodes.keySet());
        paths.addAll(after.nodes.keySet());
        for (String path : paths) {
            NodeStamp was = nodes.get(path);
            NodeStamp now = after.nodes.get(path);
            if (was == null) {
                result.add("node added: " + path);
            } else if (now == null) {
                result.add("node removed: " + path);
            } else if (!was.keys().equals(now.keys())) {
                result.add("keys changed in " + path + ": " + was.keys() + " -> " + now.keys());
            } else if (!was.valuesSha256().equals(now.valuesSha256())) {
                result.add("values changed in " + path + " (snapshot.time " + was.snapshotTime() + " -> "
                        + now.snapshotTime() + ")");
            }
        }
        return result;
    }

    /**
     * Узел по пути.
     *
     * @param path полный путь
     * @return отметка или {@code null}
     */
    public NodeStamp node(String path) {
        return nodes.get(path);
    }

    /**
     * Копия карты узлов для сборки слепка.
     *
     * @param stamps отметки
     * @return карта путь → отметка
     */
    static SortedMap<String, NodeStamp> index(List<NodeStamp> stamps) {
        SortedMap<String, NodeStamp> map = new TreeMap<>();
        for (NodeStamp stamp : stamps) {
            map.put(stamp.path(), stamp);
        }
        return map;
    }

    /**
     * Количество узлов.
     *
     * @return число узлов в слепке
     */
    public int size() {
        return nodes.size();
    }

    /**
     * Все узлы как неизменяемая карта.
     *
     * @return узлы по пути
     */
    public Map<String, NodeStamp> asMap() {
        return nodes;
    }
}
