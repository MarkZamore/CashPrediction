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
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.SessionFixtures;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.SessionStoreException;

/**
 * Тесты хранилища сессии web-сервера: вынос несохранённого плана в отдельный файл, сохранение
 * снимка при смене маркера, повреждённый файл.
 */
class MarkdownSessionStoreTest {

    @TempDir
    Path dir;

    private MarkdownSessionStore store() {
        return MarkdownSessionStore.inCashMemory(dir);
    }

    @Test
    void dirtyPlanGoesToSeparateFile() throws SessionStoreException, IOException {
        MarkdownSessionStore store = store();
        SessionSnapshot snapshot = SessionFixtures.tricky("web");
        store.save(snapshot);
        assertEquals(SessionFixtures.TRICKY_PLAN, Files.readString(store.planFile(), StandardCharsets.UTF_8));
        String session = Files.readString(store.sessionFile(), StandardCharsets.UTF_8);
        assertTrue(session.startsWith("# Сессия CashPrediction (web)\n"), session);
        assertTrue(session.contains("- Несохранённые изменения: да (web-session.plan.md)\n"), session);
        assertFalse(session.contains("## Несохранённый план"), "текст плана не дублируется в файле сессии");
        assertEquals(Optional.of(snapshot), store.load());
        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(List.of("web-session.md", "web-session.plan.md"),
                    files.map(p -> p.getFileName().toString()).sorted().toList());
        }
        assertEquals("server", store.id());
        assertEquals("Сервер", store.title());
    }

    @Test
    void cleanPlanDeletesPlanFile() throws SessionStoreException {
        MarkdownSessionStore store = store();
        store.save(SessionFixtures.tricky("web"));
        SessionSnapshot clean = SessionSnapshot.of(SessionFixtures.SAVED, "web", MainWindowState.empty(), PlanState.CLEAN, List.of());
        store.save(clean);
        assertFalse(Files.exists(store.planFile()));
        assertEquals(Optional.of(clean), store.load());
    }

    @Test
    void markDirtyPreservesSnapshotIncludingPlan() throws SessionStoreException, IOException {
        MarkdownSessionStore store = store();
        SessionSnapshot snapshot = SessionFixtures.tricky("web");
        store.save(snapshot);
        SessionMarker marker = SessionMarker.running(20440, SessionFixtures.STARTED, "web");
        store.markDirty(marker);
        assertEquals(Optional.of(marker), store.readMarker());
        assertEquals(Optional.of(snapshot), store.load());
        assertTrue(Files.readString(store.sessionFile()).contains("- PID сервера: 20440\n"));

        MarkdownSessionStore reopened = store();
        reopened.markClean();
        assertEquals(Optional.of(marker.closed()), store.readMarker());
        assertEquals(Optional.of(snapshot), store.load());
    }

    @Test
    void corruptFileThrowsAndSaveRecovers() throws IOException, SessionStoreException {
        MarkdownSessionStore store = store();
        Files.writeString(store.sessionFile(), "# Не тот файл\n", StandardCharsets.UTF_8);
        SessionStoreException e = assertThrows(SessionStoreException.class, store::load);
        assertTrue(e.getMessage().startsWith("Файл сессии повреждён"), e.getMessage());
        assertEquals(Optional.empty(), store.readMarker());
        SessionSnapshot snapshot = SessionFixtures.simple("web");
        store.save(snapshot);
        assertEquals(Optional.of(snapshot), store.load());
    }

    @Test
    void missingPlanFileIsReported() throws SessionStoreException, IOException {
        MarkdownSessionStore store = store();
        store.save(SessionFixtures.simple("web"));
        Files.delete(store.planFile());
        SessionStoreException e = assertThrows(SessionStoreException.class, store::load);
        assertEquals("Файл несохранённого плана «web-session.plan.md» не найден", e.getMessage());
    }

    @Test
    void clearDeletesBothFiles() throws SessionStoreException {
        MarkdownSessionStore store = store();
        store.markDirty(SessionMarker.running(1, SessionFixtures.STARTED, "web"));
        store.save(SessionFixtures.tricky("web"));
        store.clear();
        assertFalse(Files.exists(store.sessionFile()));
        assertFalse(Files.exists(store.planFile()));
        assertEquals(Optional.empty(), store.load());
    }
}
