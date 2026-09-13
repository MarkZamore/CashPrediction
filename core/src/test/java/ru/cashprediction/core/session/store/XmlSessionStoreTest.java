package ru.cashprediction.core.session.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.session.SessionFixtures;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStoreException;

/**
 * Тесты XML-хранилища во временной папке: атомарная запись, повреждённый файл, сохранение снимка
 * при смене маркера и чтение маркера.
 */
class XmlSessionStoreTest {

    @TempDir
    Path dir;

    private XmlSessionStore store() {
        return XmlSessionStore.inCashMemory(dir, "fx");
    }

    @Test
    void savesAtomicallyAndLoads() throws SessionStoreException, IOException {
        XmlSessionStore store = store();
        assertEquals(dir.resolve("session-fx.xml"), store.file());
        assertEquals(Optional.empty(), store.load());
        SessionSnapshot first = SessionFixtures.simple("fx");
        store.save(first);
        SessionSnapshot second = SessionFixtures.tricky("fx");
        store.save(second);
        assertEquals(Optional.of(second), store.load());
        assertEquals(Optional.of(SessionFixtures.SAVED), store.lastSavedAt());
        String text = Files.readString(store.file(), StandardCharsets.UTF_8);
        assertTrue(text.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<session schema=\"1\" client=\"fx\""), text);
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("session-fx.xml"), files.map(p -> p.getFileName().toString()).toList(),
                    "после атомарной записи временных файлов не остаётся");
        }
        assertEquals("xml", store.id());
        assertEquals("XML-файл", store.title());
        assertTrue(store.isAvailable());
    }

    @Test
    void corruptFileThrowsAndNextSaveRecovers() throws IOException, SessionStoreException {
        XmlSessionStore store = store();
        Files.writeString(store.file(), "<session schema=\"1\" client=\"fx\"><main", StandardCharsets.UTF_8);
        SessionStoreException e = assertThrows(SessionStoreException.class, store::load);
        assertTrue(e.getMessage().startsWith("XML-файл сессии повреждён"), e.getMessage());
        assertEquals(Optional.empty(), store.readMarker());
        assertEquals(Optional.empty(), store.lastSavedAt());

        SessionSnapshot snapshot = SessionFixtures.tricky("fx");
        store.save(snapshot);
        assertEquals(Optional.of(snapshot), store.load());
    }

    @Test
    void markDirtyPreservesSnapshotAndSaveKeepsMarker() throws SessionStoreException, IOException {
        XmlSessionStore store = store();
        SessionSnapshot snapshot = SessionFixtures.tricky("fx");
        store.save(snapshot);
        SessionMarker marker = SessionFixtures.running("fx");
        store.markDirty(marker);
        assertEquals(Optional.of(marker), store.readMarker());
        assertEquals(Optional.of(snapshot), store.load());
        assertTrue(Files.readString(store.file()).contains("state=\"running\" pid=\"12345\""));

        // Новый экземпляр хранилища (перезапуск) при записи снимка берёт маркер из файла.
        XmlSessionStore reopened = store();
        SessionSnapshot newer = SessionFixtures.simple("fx");
        reopened.save(newer);
        assertEquals(Optional.of(marker), reopened.readMarker());
        assertEquals(Optional.of(newer), reopened.load());

        reopened.markClean();
        assertEquals(Optional.of(marker.closed()), store.readMarker());
        assertEquals(Optional.of(newer), store.load());
        assertTrue(Files.readString(store.file()).contains("state=\"closed\""));
    }

    @Test
    void markerWithoutSnapshot() throws SessionStoreException {
        XmlSessionStore store = store();
        store.markClean();
        assertFalse(Files.exists(store.file()), "без маркера закрывать нечего");
        SessionMarker marker = SessionFixtures.running("fx");
        store.markDirty(marker);
        assertEquals(Optional.of(marker), store().readMarker());
        assertEquals(Optional.empty(), store().load());
        assertTrue(store.lastError().isEmpty());
    }

    @Test
    void markDirtyOverCorruptFileWritesFreshMarker() throws IOException, SessionStoreException {
        XmlSessionStore store = store();
        Files.writeString(store.file(), "мусор", StandardCharsets.UTF_8);
        SessionMarker marker = SessionFixtures.running("fx");
        store.markDirty(marker);
        assertEquals(Optional.of(marker), store.readMarker());
        assertEquals(Optional.empty(), store.load());
    }

    @Test
    void rejectsExternalEntities() throws IOException {
        XmlSessionStore store = store();
        Files.writeString(store.file(), """
                <?xml version="1.0"?>
                <!DOCTYPE session [<!ENTITY xxe SYSTEM "file:///C:/Windows/win.ini">]>
                <session schema="1" client="fx" savedAt="2026-09-13T10:15:30Z"><main view="&xxe;"/></session>
                """, StandardCharsets.UTF_8);
        assertThrows(SessionStoreException.class, store::load);
    }

    @Test
    void clearDeletesFile() throws SessionStoreException {
        XmlSessionStore store = store();
        store.markDirty(SessionFixtures.running("fx"));
        store.save(SessionFixtures.simple("fx"));
        store.clear();
        assertFalse(Files.exists(store.file()));
        assertEquals(Optional.empty(), store.readMarker());
        assertEquals(Optional.empty(), store.load());
        // После очистки новая запись не должна воскрешать старый маркер.
        store.save(SessionFixtures.simple("fx"));
        assertEquals(Optional.empty(), store.readMarker());
    }
}
