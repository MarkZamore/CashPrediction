package ru.cashprediction.core.session.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.session.SessionFixtures;
import ru.cashprediction.core.session.SessionStoreException;

/**
 * Узел реестра хранилища снимков (решение L2): узел установки по умолчанию, явный префикс, отказ от префикса
 * вне {@code ru/cashprediction/}, прежний {@code forClient(client)}.
 *
 * <p><b>Безопасность реестра (решение L12).</b> Тест никогда не открывает настоящие узлы
 * {@code ru/cashprediction/session/*}: узел по умолчанию только вычисляется, а всё, что действительно создаётся
 * в реестре, лежит под {@code ru/cashprediction/selftest/<uuid>} и удаляется после теста.</p>
 */
class RegistrySessionStoreNodeTest {

    private String savedProperty;
    private String selftestPrefix;

    @BeforeEach
    void rememberProperty() {
        savedProperty = System.getProperty(RegistrySessionStore.PROPERTY_NODE);
        System.clearProperty(RegistrySessionStore.PROPERTY_NODE);
        selftestPrefix = "ru/cashprediction/selftest/" + UUID.randomUUID();
    }

    @AfterEach
    void restorePropertyAndDeleteSelftestNode() throws BackingStoreException {
        if (savedProperty == null) {
            System.clearProperty(RegistrySessionStore.PROPERTY_NODE);
        } else {
            System.setProperty(RegistrySessionStore.PROPERTY_NODE, savedProperty);
        }
        if (isWindows() && Preferences.userRoot().nodeExists(selftestPrefix)) {
            Preferences.userRoot().node(selftestPrefix).removeNode();
            // Пустой родитель selftest тоже убираем, чтобы после теста в реестре не оставалось следов.
            String parent = "ru/cashprediction/selftest";
            if (Preferences.userRoot().nodeExists(parent)) {
                Preferences node = Preferences.userRoot().node(parent);
                if (node.childrenNames().length == 0 && node.keys().length == 0) {
                    node.removeNode();
                }
            }
            Preferences.userRoot().flush();
        }
    }

    @Test
    void defaultNodeIsPerInstallation(@TempDir Path home) throws Exception {
        Path cashMemory = home.resolve("CashMemory");
        String expectedHash = sha256Prefix(cashMemory.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT));
        assertEquals("ru/cashprediction/session/fx-" + expectedHash, RegistrySessionStore.installationNodePath("fx", cashMemory));
        assertEquals(RegistrySessionStore.installationNodePath("swing", cashMemory),
                RegistrySessionStore.resolveNodePath("swing", cashMemory, null));
        assertTrue(expectedHash.matches("[0-9a-f]{8}"), expectedHash);
    }

    @Test
    void twoCopiesGetDifferentNodesAndCaseDoesNotMatter(@TempDir Path home) {
        Path first = home.resolve("A").resolve("CashMemory");
        Path second = home.resolve("B").resolve("CashMemory");
        assertNotEquals(RegistrySessionStore.installationNodePath("fx", first),
                RegistrySessionStore.installationNodePath("fx", second));
        // Пути Windows нечувствительны к регистру: один и тот же каталог даёт один узел.
        Path upper = Path.of(first.toAbsolutePath().toString().toUpperCase(Locale.ROOT));
        assertEquals(RegistrySessionStore.installationHash(first), RegistrySessionStore.installationHash(upper));
        // Нормализация «..» не даёт второго узла для той же папки.
        Path roundabout = home.resolve("A").resolve("x").resolve("..").resolve("CashMemory");
        assertEquals(RegistrySessionStore.installationHash(first), RegistrySessionStore.installationHash(roundabout));
    }

    @Test
    void explicitPrefixAndPropertyAreHonoured(@TempDir Path home) {
        Path cashMemory = home.resolve("CashMemory");
        assertEquals(selftestPrefix + "/fx", RegistrySessionStore.resolveNodePath("fx", cashMemory, selftestPrefix));
        assertEquals(selftestPrefix + "/web", RegistrySessionStore.resolveNodePath("web", cashMemory, selftestPrefix + "/"));
        System.setProperty(RegistrySessionStore.PROPERTY_NODE, selftestPrefix);
        assertEquals(selftestPrefix + "/swing", RegistrySessionStore.resolveNodePath("swing", cashMemory, null));
        // Аргумент командной строки сильнее свойства.
        assertEquals("ru/cashprediction/other/swing", RegistrySessionStore.resolveNodePath("swing", cashMemory, "ru/cashprediction/other"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ru/cashprediction", "ru/cashprediction/", "ru/other/session", "software/ru/cashprediction/x",
        "ru/cashprediction//x", "ru/cashprediction/../x", "ru/cashprediction/a\\b", "RU/cashprediction/x"})
    void invalidPrefixIsRejected(String prefix, @TempDir Path home) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RegistrySessionStore.resolveNodePath("fx", home, prefix));
        assertTrue(e.getMessage().contains("узел реестра") || e.getMessage().contains("Узел реестра"), e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ru/cashprediction/session", "ru/cashprediction/session/", "ru/cashprediction/session/fx",
        "ru/cashprediction/session/fx-3fa92c1d", " ru/cashprediction/session/x/y "})
    void prefixInsideSessionAreaIsRejected(String prefix, @TempDir Path home) {
        // Решение L12: явный узел не может указывать на общий узел прежних клиентов или внутрь узла установки.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> RegistrySessionStore.resolveNodePath("fx", home, prefix));
        assertTrue(e.getMessage().contains("ru/cashprediction/session"), e.getMessage());
        System.setProperty(RegistrySessionStore.PROPERTY_NODE, prefix);
        assertThrows(IllegalArgumentException.class, () -> RegistrySessionStore.resolveNodePath("fx", home, null));
    }

    @Test
    void similarlyNamedBranchOutsideSessionAreaIsAllowed(@TempDir Path home) {
        assertEquals("ru/cashprediction/sessions-test/fx",
                RegistrySessionStore.resolveNodePath("fx", home, "ru/cashprediction/sessions-test"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void blankExplicitPrefixFallsBackToPropertyNotToInstallationNode(String blank, @TempDir Path home) {
        System.setProperty(RegistrySessionStore.PROPERTY_NODE, selftestPrefix);
        assertEquals(selftestPrefix + "/fx", RegistrySessionStore.resolveNodePath("fx", home, blank));
        System.clearProperty(RegistrySessionStore.PROPERTY_NODE);
        assertEquals(RegistrySessionStore.installationNodePath("fx", home),
                RegistrySessionStore.resolveNodePath("fx", home, blank));
    }

    @Test
    void invalidPropertyIsRejectedByOldFactoryToo() {
        System.setProperty(RegistrySessionStore.PROPERTY_NODE, "software/other");
        assertThrows(IllegalArgumentException.class, () -> forClientDeprecated("fx"));
    }

    @Test
    void legacyNodeIsUnchanged() {
        assertEquals("ru/cashprediction/session/fx", RegistrySessionStore.legacyNodePath("fx"));
        assertEquals("ru/cashprediction/session/swing", RegistrySessionStore.legacyNodePath("swing"));
    }

    @Test
    void oldForClientStillWorksAndHonoursProperty() throws SessionStoreException {
        assumeTrue(isWindows(), "реестр Windows доступен только на Windows");
        // Без свойства старая фабрика открыла бы настоящий общий узел, поэтому в тесте свойство направляет её в selftest.
        System.setProperty(RegistrySessionStore.PROPERTY_NODE, selftestPrefix);
        RegistrySessionStore store = forClientDeprecated("fx");
        assumeTrue(store.isAvailable(), "реестр недоступен: " + store.unavailableReason());
        assertEquals(selftestPrefix + "/fx", store.nodePath());
        store.save(SessionFixtures.simple("fx"));
        assertEquals(Optional.of(SessionFixtures.simple("fx")), store.load());
        assertTrue(store.cashMemoryPath().isEmpty(), "старая фабрика не знает папку CashMemory");
    }

    @Test
    void newFactoryWritesCashMemoryPath(@TempDir Path home) throws SessionStoreException {
        assumeTrue(isWindows(), "реестр Windows доступен только на Windows");
        Path cashMemory = home.resolve("CashMemory");
        RegistrySessionStore store = RegistrySessionStore.forClient("swing", cashMemory, selftestPrefix);
        assumeTrue(store.isAvailable(), "реестр недоступен: " + store.unavailableReason());
        assertEquals(selftestPrefix + "/swing", store.nodePath());
        store.markDirty(SessionFixtures.running("swing"));
        assertEquals(cashMemory.toAbsolutePath().normalize().toString(),
                store.backend().get(RegistrySessionStore.KEY_CASHMEMORY_PATH));
    }

    @Test
    void inMemoryStoreNeverTouchesRegistry(@TempDir Path home) throws SessionStoreException {
        RegistrySessionStore store = RegistrySessionStore.inMemory("fx", home.resolve("CashMemory"));
        assertTrue(store.backend() instanceof InMemoryRegistryBackend);
        assertEquals("", store.nodePath());
        store.save(SessionFixtures.simple("fx"));
        assertEquals(Optional.of(SessionFixtures.simple("fx")), store.load());
        assertEquals(home.resolve("CashMemory").toAbsolutePath().normalize().toString(),
                store.backend().get(RegistrySessionStore.KEY_CASHMEMORY_PATH));
    }

    /** Вызов устаревшей фабрики в одном месте, чтобы предупреждение компилятора было подавлено точечно. */
    @SuppressWarnings("deprecation")
    private static RegistrySessionStore forClientDeprecated(String client) {
        return RegistrySessionStore.forClient(client);
    }

    private static String sha256Prefix(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest).substring(0, 8);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
