package ru.cashprediction.core.session.store;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.session.*;
import com.sun.nio.file.ExtendedOpenOption;

/** Actual stores и Windows file lock; fault backend меняет только выбранный момент отказа. */
class StoreCommitFaultTest {
    @TempDir Path dir;

    /** До commit читается прежний полный снимок, после commit новый, даже если save бросил. */
    @ParameterizedTest @ValueSource(strings={"before-chunk", "after-chunk", "dropped-chunk", "after-commit", "commit-flush"})
    void registryFailureNeverReturnsTornOrEmptyPriorState(String fault) throws Exception {
        var backend = new FaultBackend();
        var store = new RegistrySessionStore(backend, "fx");
        var old = snapshot("fx", "old-".repeat(2500), 0);
        var next = snapshot("fx", "new-".repeat(2500), 1);
        store.save(old);
        backend.fault = fault;
        backend.committed = false;
        assertThrows(SessionStoreException.class, () -> store.save(next));
        backend.fault = "";
        var loaded = new RegistrySessionStore(backend, "fx").load().orElseThrow();
        assertEquals(fault.equals("after-commit") || fault.equals("commit-flush") ? next : old, loaded);
        assertEquals(loaded.savedAt(), new RegistrySessionStore(backend, "fx").lastSavedAt().orElseThrow());
        // После precommit отказа разрешён повтор, а durable backup сохраняется до нового commit.
        store.save(next);
        assertEquals(next, new RegistrySessionStore(backend, "fx").load().orElseThrow());
        assertFalse(backend.keys().stream().anyMatch(k -> k.startsWith("backup.") || k.equals("transaction.pending")));
    }

    /** Настоящий Windows deny-delete вызывает отказ session replace ПОСЛЕ sidecar write. */
    @Test void markdownSessionReplaceFailureRecoversPriorPlanAndTypedWindows() throws Exception {
        assertTrue(System.getProperty("os.name").startsWith("Windows"));
        var store = MarkdownSessionStore.inCashMemory(dir);
        var old = snapshot("web", "old-plan", 0);
        var next = snapshot("web", "new-plan", 1);
        store.save(old);
        try (var held = FileChannel.open(store.sessionFile(), StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
            assertThrows(SessionStoreException.class, () -> store.save(next));
            // Подтверждает реально достигнутую partial-write границу, а не ранний отказ preflight.
            assertEquals("new-plan", Files.readString(store.planFile()));
            assertEquals(old, MarkdownSessionStore.inCashMemory(dir).load().orElseThrow());
        }
        store.save(next);
        assertEquals(next, MarkdownSessionStore.inCashMemory(dir).load().orElseThrow());
        assertFalse(Files.exists(dir.resolve("web-session.md.transaction.md")));
    }

    /** Поздний отказ удаления sidecar не возвращает старый снимок поверх нового commit. */
    @Test void markdownCommittedCleanSnapshotSurvivesLateDeleteFailure() throws Exception {
        var store = MarkdownSessionStore.inCashMemory(dir);
        store.save(snapshot("web", "old-plan", 0));
        var clean = SessionSnapshot.of(Instant.parse("2031-01-01T00:00:01Z"), "web", MainWindowState.empty(), PlanState.CLEAN, List.of());
        try (var held = FileChannel.open(store.planFile(), StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE)) {
            assertThrows(SessionStoreException.class, () -> store.save(clean));
            assertEquals(clean, MarkdownSessionStore.inCashMemory(dir).load().orElseThrow());
        }
        store.save(clean);
        assertFalse(Files.exists(store.planFile()));
        assertFalse(Files.exists(dir.resolve("web-session.md.transaction.md")));
    }

    /** Полный снимок с невалидными typed значениями и недефолтным контекстом. */
    static SessionSnapshot snapshot(String client, String markdown, int epoch) {
        var fields = new LinkedHashMap<String,String>();
        fields.put("date", "31.02.2031"); fields.put("title", "typed draft"); fields.put("kind", "INCOME");
        fields.put("amount", "1,234"); fields.put("category", ""); fields.put("note", "line1\nline2\t<&>");
        var window = new WindowState("original-editor", WindowType.ONE_TIME_EDITOR, true, "main",
                new WindowBounds(23, 41, 420, 330), Map.of("mode", "create"), fields);
        return SessionSnapshot.of(Instant.parse("2031-01-01T00:00:00Z").plusSeconds(epoch), client,
                MainWindowState.empty(), PlanState.dirty(markdown), List.of(window));
    }

    /** Делегирует все данные actual backend; ошибка ставится до или после точной реальной мутации. */
    private static final class FaultBackend implements RegistryBackend {
        private final InMemoryRegistryBackend actual = new InMemoryRegistryBackend();
        String fault = "";
        boolean committed;
        /** Читает actual данные. */
        @Override public String get(String key) { return actual.get(key); }
        /** Вставляет ошибку вокруг записи первого primary chunk или commit marker. */
        @Override public void put(String key, String value) {
            if (key.equals("snapshot.0") && fault.equals("before-chunk")) throw new IllegalStateException("injected before chunk");
            if (key.equals("snapshot.0") && fault.equals("dropped-chunk")) return;
            actual.put(key, value);
            if (key.equals("snapshot.0") && fault.equals("after-chunk")) throw new IllegalStateException("injected after chunk");
            if (key.equals(RegistrySessionStore.KEY_SNAPSHOT_TIME)) {
                committed = true;
                if (fault.equals("after-commit")) throw new IllegalStateException("injected after commit");
            }
        }
        /** Удаляет actual ключ и обновляет наблюдение commit. */
        @Override public void remove(String key) { actual.remove(key); if (key.equals(RegistrySessionStore.KEY_SNAPSHOT_TIME)) committed = false; }
        /** Возвращает actual опись, без hand-model снимков. */
        @Override public List<String> keys() { return actual.keys(); }
        /** Отказ после actual flush фиксирует неоднозначный возврат save при уже committed данных. */
        @Override public void flush() throws SessionStoreException {
            actual.flush();
            if (fault.equals("commit-flush") && committed) {
                throw new SessionStoreException("injected after commit flush");
            }
        }
        /** Доступность не подменяется ошибкой записи. */
        @Override public boolean isAvailable() { return actual.isAvailable(); }
        /** Возвращает actual причину недоступности. */
        @Override public String unavailableReason() { return actual.unavailableReason(); }
    }
}
