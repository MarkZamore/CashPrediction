package ru.cashprediction.parity.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.session.store.RegistrySessionStore;

/**
 * Правила {@link RegistryNodeCleaner} и сравнение {@link RegistryTreeSnapshot} без обращения к реестру.
 *
 * <p>Недопустимый путь должен отклоняться до любого обращения к {@code java.util.prefs}: поэтому эти тесты
 * безопасно вызывают {@code create} и {@code delete} с путями настоящих узлов сеанса.</p>
 */
class RegistryNodeCleanerTest {

    @Test
    void newSelftestNodeIsAcceptedByTheCleanerAndByTheCoreStore() {
        String node = RegistryNodeCleaner.newSelftestNode();

        assertEquals(node, RegistryNodeCleaner.requireSelftestNode(node));
        assertTrue(node.startsWith("ru/cashprediction/selftest/"), node);
        assertEquals(node + "/fx", RegistrySessionStore.nodePath(node, "fx"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "ru/cashprediction/session",
            "ru/cashprediction/session/fx",
            "ru/cashprediction/session/swing",
            "ru/cashprediction/session/fx-3fa92c1d",
            "ru/cashprediction",
            "ru/cashprediction/selftest",
            "ru/cashprediction/selftest/",
            "/ru/cashprediction/selftest/0b7c2f1e-4a1d-4c55-9b0e-2f3a4b5c6d7e",
            "ru/cashprediction/selftest/0B7C2F1E-4A1D-4C55-9B0E-2F3A4B5C6D7E",
            "ru/cashprediction/selftest/0b7c2f1e-4a1d-4c55-9b0e-2f3a4b5c6d7e/fx",
            "ru/cashprediction/selftest/../session",
            "ru/cashprediction/selftest/not-a-uuid"})
    void onlySelftestUuidNodesMayBeTouched(String path) {
        assertThrows(IllegalArgumentException.class, () -> RegistryNodeCleaner.requireSelftestNode(path));
        assertThrows(IllegalArgumentException.class, () -> RegistryNodeCleaner.delete(path));
        assertThrows(IllegalArgumentException.class, () -> RegistryNodeCleaner.create(path));
    }

    @Test
    void unchangedSnapshotsHaveNoDifferences() {
        RegistryTreeSnapshot a = snapshot(stamp("ru/cashprediction/session", "", ""),
                stamp("ru/cashprediction/session/fx-3fa92c1d", "aa", "2026-09-13T10:00:00Z", "state", "pid"));
        RegistryTreeSnapshot b = snapshot(stamp("ru/cashprediction/session", "", ""),
                stamp("ru/cashprediction/session/fx-3fa92c1d", "aa", "2026-09-13T10:00:00Z", "state", "pid"));

        assertEquals(List.of(), a.differences(b));
    }

    @Test
    void differencesReportAddedRemovedAndChangedNodes() {
        RegistryTreeSnapshot before = snapshot(stamp("ru/cashprediction/session", "", ""),
                stamp("ru/cashprediction/session/fx", "aa", "t1", "state"),
                stamp("ru/cashprediction/session/swing", "bb", "", "state"));
        RegistryTreeSnapshot after = snapshot(stamp("ru/cashprediction/session", "", ""),
                stamp("ru/cashprediction/session/fx", "cc", "t2", "state"),
                stamp("ru/cashprediction/session/swing-1234abcd", "dd", "", "state"));

        List<String> diff = before.differences(after);

        assertEquals(3, diff.size(), diff.toString());
        assertTrue(diff.get(0).startsWith("values changed in ru/cashprediction/session/fx"), diff.toString());
        assertTrue(diff.get(0).contains("t1 -> t2"), diff.toString());
        assertEquals("node removed: ru/cashprediction/session/swing", diff.get(1));
        assertEquals("node added: ru/cashprediction/session/swing-1234abcd", diff.get(2));
    }

    @Test
    void creatingTheRootIsADifference() {
        RegistryTreeSnapshot absent = new RegistryTreeSnapshot("ru/cashprediction/session", false,
                RegistryTreeSnapshot.index(List.of()));
        RegistryTreeSnapshot present = snapshot(stamp("ru/cashprediction/session", "", ""));

        assertEquals(List.of("ru/cashprediction/session was created", "node added: ru/cashprediction/session"),
                absent.differences(present));
    }

    private static RegistryTreeSnapshot snapshot(RegistryTreeSnapshot.NodeStamp... stamps) {
        return new RegistryTreeSnapshot("ru/cashprediction/session", true, RegistryTreeSnapshot.index(Arrays.asList(stamps)));
    }

    private static RegistryTreeSnapshot.NodeStamp stamp(String path, String hash, String time, String... keys) {
        return new RegistryTreeSnapshot.NodeStamp(path, new ArrayList<>(List.of(keys)), hash, time);
    }
}
