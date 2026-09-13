package ru.cashprediction.parity.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.store.RegistrySessionStore;

/**
 * {@link RegistryNodeCleaner} на настоящем реестре (стадия S0): тестовый узел создаётся, в него пишет
 * хранилище ядра с явным префиксом, узел удаляется, а слепок поддерева {@code ru/cashprediction/session}
 * до и после совпадает.
 */
class RegistryNodeCleanerIT {

    /** Удаляет пустой корень тестовых узлов после всех тестов класса (решение L12). */
    @AfterAll
    static void removeEmptySelftestRoot() {
        RegistryNodeCleaner.deleteSelftestRootIfEmpty();
    }

    @Test
    void createsAndDeletesSelftestNodeWithoutTouchingRealSessionNodes(@TempDir Path tmp) throws Exception {
        RegistryTreeSnapshot before = RegistryNodeCleaner.snapshotRealSessionNodes();
        String node = RegistryNodeCleaner.newSelftestNode();
        try {
            assertFalse(RegistryNodeCleaner.exists(node));
            RegistryNodeCleaner.create(node);
            assertTrue(RegistryNodeCleaner.exists(node));

            // Хранилище ядра с префиксом --registry-node пишет в тестовый узел, а не в session/.
            RegistrySessionStore store = RegistrySessionStore.forClient("fx", tmp.resolve("CashMemory"), node);
            assertEquals(node + "/fx", store.nodePath());
            assertTrue(store.isAvailable(), store.unavailableReason());
            store.markDirty(SessionMarker.running(ProcessHandle.current().pid(), Instant.now(), "fx"));
            assertEquals(SessionMarker.RUNNING, store.readMarker().orElseThrow().state());

            RegistryTreeSnapshot testTree = RegistryNodeCleaner.snapshot(node);
            assertNotNull(testTree.node(node + "/fx"), "store wrote under the selftest node: " + testTree.asMap().keySet());
            assertFalse(testTree.node(node + "/fx").keys().isEmpty());
        } finally {
            assertTrue(RegistryNodeCleaner.delete(node), "selftest node existed and was deleted");
        }

        assertFalse(RegistryNodeCleaner.exists(node));
        assertFalse(RegistryNodeCleaner.delete(node), "deleting an absent node is a no-op");
        assertEquals(List.of(), before.differences(RegistryNodeCleaner.snapshotRealSessionNodes()),
                "real session nodes changed (was a CashPrediction client running during the test?)");
    }

    @Test
    void snapshotStampsDetectChangesInsideASubtree() throws Exception {
        // Настоящие узлы менять нельзя, поэтому чувствительность слепка проверяется на тестовом узле.
        String node = RegistryNodeCleaner.newSelftestNode();
        try {
            Preferences prefs = RegistryNodeCleaner.create(node);
            prefs.put("snapshot.time", "2026-09-13T10:00:00Z");
            prefs.put("state", "running");
            prefs.flush();
            RegistryTreeSnapshot first = RegistryNodeCleaner.snapshot(node);
            assertEquals(List.of(), first.differences(RegistryNodeCleaner.snapshot(node)), "reading must not change");

            prefs.put("snapshot.time", "2026-09-13T10:00:05Z");
            prefs.node("fx-3fa92c1d").put("pid", "1");
            prefs.flush();
            List<String> diff = first.differences(RegistryNodeCleaner.snapshot(node));

            assertEquals(2, diff.size(), diff.toString());
            assertTrue(diff.get(0).startsWith("values changed in " + node), diff.toString());
            assertEquals("node added: " + node + "/fx-3fa92c1d", diff.get(1));
            assertEquals("2026-09-13T10:00:00Z", first.node(node).snapshotTime());
        } finally {
            RegistryNodeCleaner.delete(node);
        }
        assertFalse(RegistryNodeCleaner.exists(node));
    }
}
