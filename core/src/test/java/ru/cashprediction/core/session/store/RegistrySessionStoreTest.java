package ru.cashprediction.core.session.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.SessionFixtures;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStoreException;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.session.codec.JsonSnapshotCodec;

/**
 * Тесты реестрового хранилища на {@link InMemoryRegistryBackend}: куски, маркер фиксации, CRC,
 * удаление хвостов, сохранение снимка при смене маркера и читаемые дубли.
 */
class RegistrySessionStoreTest {

    private final InMemoryRegistryBackend backend = new InMemoryRegistryBackend();
    private final RegistrySessionStore store = new RegistrySessionStore(backend, "fx");

    /** Строит снимок, JSON которого ровно заданной длины. */
    private static SessionSnapshot snapshotWithJsonLength(int length) {
        SessionSnapshot base = withPad(SessionFixtures.simple("fx"), "");
        int baseLength = new JsonSnapshotCodec().encode(base).length();
        SessionSnapshot padded = withPad(base, "x".repeat(length - baseLength));
        assertEquals(length, new JsonSnapshotCodec().encode(padded).length());
        return padded;
    }

    private static SessionSnapshot withPad(SessionSnapshot snapshot, String pad) {
        WindowState window = snapshot.windows().get(0);
        Map<String, String> fields = new LinkedHashMap<>(window.fields());
        fields.put("note", pad);
        return new SessionSnapshot(1, snapshot.savedAt(), snapshot.client(), snapshot.main(), snapshot.plan(),
                List.of(window.withFields(fields)));
    }

    @Test
    void jsonOfExactlyOneChunkIsStoredInOneKey() throws SessionStoreException {
        SessionSnapshot snapshot = snapshotWithJsonLength(RegistrySessionStore.CHUNK_SIZE);
        store.save(snapshot);
        Map<String, String> keys = backend.contents();
        assertEquals("1", keys.get("snapshot.count"));
        assertEquals("4096", keys.get("snapshot.length"));
        assertEquals(4096, keys.get("snapshot.0").length());
        assertFalse(keys.containsKey("snapshot.1"));
        assertEquals(Optional.of(snapshot), store.load());
    }

    @Test
    void jsonOneCharOverChunkUsesTwoKeys() throws SessionStoreException {
        SessionSnapshot snapshot = snapshotWithJsonLength(RegistrySessionStore.CHUNK_SIZE + 1);
        store.save(snapshot);
        Map<String, String> keys = backend.contents();
        assertEquals("2", keys.get("snapshot.count"));
        assertEquals(4096, keys.get("snapshot.0").length());
        assertEquals(1, keys.get("snapshot.1").length());
        assertEquals(Optional.of(snapshot), store.load());
    }

    @Test
    void smallerSaveRemovesStaleChunksAndWindowDuplicates() throws SessionStoreException {
        SessionSnapshot big = snapshotWithJsonLength(3 * RegistrySessionStore.CHUNK_SIZE - 10);
        SessionSnapshot threeWindows = new SessionSnapshot(1, big.savedAt(), "fx", big.main(), big.plan(),
                List.of(big.windows().get(0), big.windows().get(0).withIds("w2", "w1"), big.windows().get(0).withIds("w3", "w2")));
        store.save(threeWindows);
        int bigLength = new JsonSnapshotCodec().encode(threeWindows).length();
        int bigCount = (bigLength + RegistrySessionStore.CHUNK_SIZE - 1) / RegistrySessionStore.CHUNK_SIZE;
        assertTrue(bigCount >= 3, "исходный снимок должен занимать несколько кусков");
        assertEquals(String.valueOf(bigCount), backend.contents().get("snapshot.count"));
        assertTrue(backend.contents().containsKey("snapshot." + (bigCount - 1)));
        assertTrue(backend.contents().containsKey("window.2.type"));

        SessionSnapshot small = SessionFixtures.simple("fx").withSavedAt(Instant.parse("2026-09-13T10:20:00Z"));
        store.save(small);
        Map<String, String> keys = backend.contents();
        assertEquals("1", keys.get("snapshot.count"));
        for (int i = 1; i < bigCount; i++) {
            assertFalse(keys.containsKey("snapshot." + i), "устаревший кусок snapshot." + i + " удалён");
        }
        assertEquals("1", keys.get("windows.count"));
        assertFalse(keys.containsKey("window.1.type"));
        assertFalse(keys.containsKey("window.2.type"));
        assertEquals(Optional.of(small), store.load());
    }

    @Test
    void crcMismatchIsReportedAsCorruption() throws SessionStoreException {
        store.save(SessionFixtures.tricky("fx"));
        String chunk = backend.contents().get("snapshot.0");
        // Та же длина, другое содержимое: поймать может только CRC.
        String tampered = (chunk.charAt(10) == 'a' ? 'b' : 'a') + "";
        backend.put("snapshot.0", chunk.substring(0, 10) + tampered + chunk.substring(11));
        SessionStoreException e = assertThrows(SessionStoreException.class, store::load);
        assertEquals("Снимок в реестре повреждён: контрольная сумма не совпадает", e.getMessage());
    }

    @Test
    void lengthCountAndMissingChunkMismatchesAreCorruption() throws SessionStoreException {
        SessionSnapshot snapshot = snapshotWithJsonLength(RegistrySessionStore.CHUNK_SIZE + 100);
        store.save(snapshot);

        backend.put("snapshot.length", "4000");
        assertCorrupted("число кусков 2 не соответствует длине 4000");
        backend.put("snapshot.length", "4197");
        assertCorrupted("длина 4196 вместо 4197");
        backend.put("snapshot.length", "4196");
        backend.remove("snapshot.1");
        assertCorrupted("нет куска snapshot.1");
        backend.remove("snapshot.count");
        assertCorrupted("нет ключа snapshot.count");
        backend.put("snapshot.count", "два");
        assertCorrupted("некорректное значение snapshot.count=два");
    }

    private void assertCorrupted(String detail) {
        SessionStoreException e = assertThrows(SessionStoreException.class, store::load);
        assertEquals("Снимок в реестре повреждён: " + detail, e.getMessage());
    }

    @Test
    void missingCommitMarkerMeansNoSnapshot() throws SessionStoreException {
        store.save(SessionFixtures.simple("fx"));
        backend.remove("snapshot.time");
        assertEquals(Optional.empty(), store.load());
        assertEquals(Optional.empty(), store.lastSavedAt());
        assertEquals(Optional.empty(), new RegistrySessionStore(new InMemoryRegistryBackend(), "fx").load());
    }

    @Test
    void commitMarkerIsWrittenLastAndRemovedFirst() throws SessionStoreException {
        List<String> log = new ArrayList<>();
        RegistryBackend recording = new RegistryBackend() {
            @Override
            public String get(String key) {
                return backend.get(key);
            }

            @Override
            public void put(String key, String value) {
                log.add("put " + key);
                backend.put(key, value);
            }

            @Override
            public void remove(String key) {
                log.add("remove " + key);
                backend.remove(key);
            }

            @Override
            public List<String> keys() {
                return backend.keys();
            }

            @Override
            public void flush() throws SessionStoreException {
                log.add("flush");
                backend.flush();
            }

            @Override
            public boolean isAvailable() {
                return backend.isAvailable();
            }

            @Override
            public String unavailableReason() {
                return backend.unavailableReason();
            }
        };
        new RegistrySessionStore(recording, "fx").save(SessionFixtures.simple("fx"));
        assertEquals("remove snapshot.time", log.get(0));
        assertEquals("put snapshot.time", log.get(log.size() - 2));
        assertEquals("flush", log.get(log.size() - 1));
    }

    @Test
    void markDirtyPreservesSnapshotAndMarkCleanClosesMarker() throws SessionStoreException {
        SessionSnapshot snapshot = SessionFixtures.tricky("fx");
        store.save(snapshot);
        SessionMarker marker = SessionFixtures.running("fx");
        store.markDirty(marker);
        assertEquals(Optional.of(marker), store.readMarker());
        assertEquals(Optional.of(snapshot), store.load());
        assertEquals(Optional.of(SessionFixtures.SAVED), store.lastSavedAt());

        store.markClean();
        assertEquals(Optional.of(marker.closed()), store.readMarker());
        assertEquals(Optional.of(snapshot), store.load());
        assertTrue(store.lastError().isEmpty());
    }

    @Test
    void readableDuplicatesAndLowercaseKeys() throws SessionStoreException {
        store.markDirty(SessionFixtures.running("fx"));
        store.save(SessionFixtures.tricky("fx"));
        Map<String, String> keys = backend.contents();
        assertEquals("1", keys.get("schema"));
        assertEquals("running", keys.get("state"));
        assertEquals("12345", keys.get("pid"));
        assertEquals("2026-09-13T10:00:00Z", keys.get("started.at"));
        assertEquals("fx", keys.get("client"));
        assertEquals("Папка с пробелом/Семейный бюджет «2026».md", keys.get("plan.path"));
        assertEquals("CHART", keys.get("view"));
        assertEquals("5", keys.get("windows.count"));
        assertEquals("GOAL_CALCULATOR", keys.get("window.0.type"));
        assertEquals("RULE_EDITOR", keys.get("window.1.type"));
        assertEquals("", keys.get("window.3.type"));
        assertEquals("2026-09-13T10:15:30.123Z", keys.get("snapshot.time"));
        assertTrue(keys.get("snapshot.crc32").matches("[0-9a-f]{8}"));
        for (String key : keys.keySet()) {
            assertTrue(key.matches("[a-z0-9.]+"), "ключ реестра только строчными латинскими: " + key);
        }
    }

    @Test
    void markerFromCorruptKeysIsIgnored() {
        backend.put("state", "running");
        backend.put("pid", "не число");
        assertEquals(Optional.empty(), store.readMarker());
        store.markClean();
        assertNull(backend.get("started.at"));
    }

    @Test
    void unavailableBackendFailsLoudlyOnSaveAndQuietlyOnMarkers() {
        backend.failWith("доступ запрещён политикой");
        assertFalse(store.isAvailable());
        assertEquals("доступ запрещён политикой", store.unavailableReason());
        SessionStoreException e = assertThrows(SessionStoreException.class, () -> store.save(SessionFixtures.simple("fx")));
        assertTrue(e.getMessage().startsWith("Реестр Windows недоступен"), e.getMessage());
        assertThrows(SessionStoreException.class, store::load);
        store.markDirty(SessionFixtures.running("fx"));
        assertTrue(store.lastError().orElseThrow().contains("недоступен"));
        assertEquals(Optional.empty(), store.readMarker());
    }

    @Test
    void clearRemovesEverything() throws SessionStoreException {
        store.markDirty(SessionFixtures.running("fx"));
        store.save(SessionFixtures.tricky("fx"));
        store.clear();
        assertTrue(backend.contents().isEmpty());
        assertEquals(Optional.empty(), store.readMarker());
        assertEquals(Optional.empty(), store.load());
    }

    @Test
    void helpersAndIdentity() {
        assertEquals("registry", store.id());
        assertEquals("Реестр Windows", store.title());
        assertEquals(List.of(), RegistrySessionStore.split(""));
        assertEquals(List.of("a".repeat(4096), "b"), RegistrySessionStore.split("a".repeat(4096) + "b"));
        assertEquals("cbf43926", RegistrySessionStore.crc32("123456789"));
        SessionSnapshot unchanged = SessionSnapshot.of(SessionFixtures.SAVED, "fx", null, PlanState.CLEAN,
                List.of(new WindowState("w1", WindowType.CSV_EXPORT, true, "main", null, null, null)));
        assertEquals("fx", unchanged.client());
    }
}
