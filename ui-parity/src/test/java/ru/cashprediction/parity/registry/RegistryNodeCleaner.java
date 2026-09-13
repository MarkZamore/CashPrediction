package ru.cashprediction.parity.registry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;

/**
 * Тестовые узлы реестра {@code ru/cashprediction/selftest/<uuid>} и слепки настоящих узлов сеанса (решение L12).
 *
 * <p>Автоматические запуски пишут только в свой узел {@code selftest/<uuid>} и удаляют его в {@code finally}.
 * Удалять можно только узел ровно такого вида: опечатка в тесте не сотрёт настоящие снимки
 * {@code ru/cashprediction/session/<клиент>-<хеш>} (решение L2) или прежние {@code session/fx} и
 * {@code session/swing}. Слепок всего поддерева {@code ru/cashprediction/session} до и после теста доказывает,
 * что настоящие узлы не менялись.</p>
 *
 * <p>Узлы {@code java.util.prefs} в Windows лежат в {@code HKCU\Software\JavaSoft\Prefs}.</p>
 */
public final class RegistryNodeCleaner {

    /** Корень всех узлов программы. */
    public static final String ROOT = "ru/cashprediction";
    /** Корень тестовых узлов. */
    public static final String SELFTEST_ROOT = ROOT + "/selftest";
    /** Корень настоящих узлов сеанса (узлы установок и прежние узлы клиентов). */
    public static final String SESSION_ROOT = ROOT + "/session";
    /** Ключ времени последней записи снимка сеанса ({@code RegistrySessionStore.KEY_SNAPSHOT_TIME}). */
    static final String SNAPSHOT_TIME_KEY = "snapshot.time";

    /** Единственный вид узла, который разрешено создавать и удалять: selftest и строчный UUID. */
    private static final Pattern SELFTEST_NODE = Pattern.compile(
            "ru/cashprediction/selftest/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private RegistryNodeCleaner() {
    }

    /**
     * Новый уникальный путь тестового узла (узел не создаётся).
     *
     * @return {@code ru/cashprediction/selftest/<uuid>}
     */
    public static String newSelftestNode() {
        return SELFTEST_ROOT + "/" + UUID.randomUUID();
    }

    /**
     * Проверяет, что путь — тестовый узел, который стенду разрешено трогать.
     *
     * @param path путь узла
     * @return тот же путь
     * @throws IllegalArgumentException если путь не {@code ru/cashprediction/selftest/<строчный uuid>}
     */
    public static String requireSelftestNode(String path) {
        if (path == null || !SELFTEST_NODE.matcher(path).matches()) {
            throw new IllegalArgumentException("Refusing to touch registry node '" + path
                    + "': only " + SELFTEST_ROOT + "/<lower-case uuid> nodes are allowed");
        }
        return path;
    }

    /**
     * Создаёт тестовый узел и сбрасывает его в реестр.
     *
     * @param path путь {@code ru/cashprediction/selftest/<uuid>}
     * @return узел
     * @throws IllegalArgumentException если путь не тестовый
     * @throws IllegalStateException    если реестр недоступен
     */
    public static Preferences create(String path) {
        Preferences node = Preferences.userRoot().node(requireSelftestNode(path));
        flush(node);
        return node;
    }

    /**
     * Существует ли узел. Не создаёт ни узел, ни его родителей.
     *
     * @param path абсолютный путь без ведущей косой черты
     * @return {@code true}, если узел есть
     * @throws IllegalStateException если реестр недоступен
     */
    public static boolean exists(String path) {
        try {
            return Preferences.userRoot().nodeExists(path);
        } catch (BackingStoreException e) {
            throw new IllegalStateException("Registry is not available: " + e.getMessage(), e);
        }
    }

    /**
     * Удаляет тестовый узел со всеми подузлами.
     *
     * @param path путь {@code ru/cashprediction/selftest/<uuid>}
     * @return {@code true}, если узел был и удалён
     * @throws IllegalArgumentException если путь не тестовый
     * @throws IllegalStateException    если реестр недоступен
     */
    public static boolean delete(String path) {
        requireSelftestNode(path);
        if (!exists(path)) {
            return false;
        }
        try {
            Preferences node = Preferences.userRoot().node(path);
            Preferences parent = node.parent();
            node.removeNode();
            flush(parent);
            return true;
        } catch (BackingStoreException e) {
            throw new IllegalStateException("Cannot delete registry node " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Удаляет пустой корень {@code ru/cashprediction/selftest}, если в нём не осталось ни узлов, ни ключей.
     *
     * <p>Отдельный метод, а не часть {@link #delete}: удаление родителя удалило бы и узлы параллельно
     * работающих тестов. Вызывается в конце класса тестов.</p>
     *
     * @return {@code true}, если корень удалён
     */
    public static boolean deleteSelftestRootIfEmpty() {
        if (!exists(SELFTEST_ROOT)) {
            return false;
        }
        try {
            Preferences node = Preferences.userRoot().node(SELFTEST_ROOT);
            if (node.childrenNames().length > 0 || node.keys().length > 0) {
                return false;
            }
            Preferences parent = node.parent();
            node.removeNode();
            flush(parent);
            return true;
        } catch (BackingStoreException e) {
            throw new IllegalStateException("Cannot delete " + SELFTEST_ROOT + ": " + e.getMessage(), e);
        }
    }

    /**
     * Слепок настоящих узлов сеанса: всё поддерево {@code ru/cashprediction/session}.
     *
     * @return слепок (с {@code exists=false}, если узла нет)
     */
    public static RegistryTreeSnapshot snapshotRealSessionNodes() {
        return snapshot(SESSION_ROOT);
    }

    /**
     * Слепок поддерева: ключи и хеш значений каждого узла. Отсутствующие узлы не создаются.
     *
     * @param rootPath путь корня
     * @return слепок
     * @throws IllegalStateException если реестр недоступен
     */
    public static RegistryTreeSnapshot snapshot(String rootPath) {
        if (!exists(rootPath)) {
            return new RegistryTreeSnapshot(rootPath, false, RegistryTreeSnapshot.index(List.of()));
        }
        List<RegistryTreeSnapshot.NodeStamp> stamps = new ArrayList<>();
        try {
            // Узел уже существует, поэтому node() его только открывает, а не создаёт.
            collect(Preferences.userRoot().node(rootPath), stamps);
        } catch (BackingStoreException e) {
            throw new IllegalStateException("Cannot read registry subtree " + rootPath + ": " + e.getMessage(), e);
        }
        return new RegistryTreeSnapshot(rootPath, true, RegistryTreeSnapshot.index(stamps));
    }

    private static void collect(Preferences node, List<RegistryTreeSnapshot.NodeStamp> stamps)
            throws BackingStoreException {
        String[] keys = node.keys();
        Arrays.sort(keys);
        MessageDigest digest = sha256();
        for (String key : keys) {
            digest.update((key + "=" + node.get(key, "") + "\n").getBytes(StandardCharsets.UTF_8));
        }
        String path = node.absolutePath().substring(1);
        stamps.add(new RegistryTreeSnapshot.NodeStamp(path, List.of(keys), HexFormat.of().formatHex(digest.digest()),
                node.get(SNAPSHOT_TIME_KEY, "")));
        String[] children = node.childrenNames();
        Arrays.sort(children);
        for (String child : children) {
            collect(node.node(child), stamps);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 обязателен в любой реализации Java SE.
            throw new IllegalStateException(e);
        }
    }

    private static void flush(Preferences node) {
        try {
            node.flush();
        } catch (BackingStoreException e) {
            throw new IllegalStateException("Registry flush failed for " + node.absolutePath() + ": " + e.getMessage(), e);
        }
    }
}
