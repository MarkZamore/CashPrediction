package ru.cashprediction.core.session.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Locale;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.session.SessionFixtures;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStoreException;

/**
 * Интеграционный тест реестрового хранилища на настоящем реестре Windows (HKCU).
 *
 * <p>Выполняется только на Windows. Пишет в уникальный временный узел
 * {@code ru/cashprediction/test/<nanoTime>} и удаляет его в {@code finally}, не трогая узлы
 * настоящих сеансов {@code ru/cashprediction/session/*}.</p>
 */
class RegistrySessionStoreIT {

    @Test
    void roundTripThroughRealRegistry() throws SessionStoreException, BackingStoreException {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"),
                "реестр Windows доступен только на Windows");
        String path = "ru/cashprediction/test/" + System.nanoTime();
        PreferencesRegistryBackend backend = new PreferencesRegistryBackend(path);
        assumeTrue(backend.isAvailable(), "реестр недоступен: " + backend.unavailableReason());
        try {
            RegistrySessionStore store = new RegistrySessionStore(backend, "fx");
            SessionMarker marker = SessionFixtures.running("fx");
            store.markDirty(marker);
            assertTrue(store.lastError().isEmpty(), store.lastError().orElse(""));

            // Снимок с кириллицей и спецсимволами: в реестре они кодируются /uXXXX, а длина значения растёт.
            SessionSnapshot snapshot = SessionFixtures.tricky("fx");
            store.save(snapshot);
            assertEquals(Optional.of(snapshot), store.load());
            assertEquals(Optional.of(marker), store.readMarker());

            // Новый объект поверх того же узла видит те же данные (данные действительно в реестре, а не в кэше).
            RegistrySessionStore reopened = new RegistrySessionStore(new PreferencesRegistryBackend(path), "fx");
            assertEquals(Optional.of(snapshot), reopened.load());
            assertEquals("RULE_EDITOR", reopened.backend().get("window.1.type"));

            store.markClean();
            assertEquals(Optional.of(marker.closed()), reopened.readMarker());
            store.clear();
            assertEquals(Optional.empty(), reopened.load());
        } finally {
            Preferences node = Preferences.userRoot().node(path);
            node.removeNode();
            removeIfEmpty("ru/cashprediction/test");
            removeIfEmpty("ru/cashprediction");
            removeIfEmpty("ru");
            Preferences.userRoot().flush();
        }
    }

    /** Удаляет вспомогательный узел, только если тест создал его пустым (реальные данные программы не трогаются). */
    private static void removeIfEmpty(String path) throws BackingStoreException {
        if (!Preferences.userRoot().nodeExists(path)) {
            return;
        }
        Preferences node = Preferences.userRoot().node(path);
        if (node.childrenNames().length == 0 && node.keys().length == 0) {
            node.removeNode();
        }
    }
}
