package ru.cashprediction.core.session;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.session.store.*;

/** Реальные stores проверяют контракт службы чтения без окон, сети или OS registry. */
class RecoverySnapshotsContractTest {
    @TempDir Path memory;

    /** Испорченный registry не мешает независимому XML, повтор перечитывает свежие данные без fallback. */
    @Test void independentReadRetainsRawWindowPayloadAndIsFresh() throws Exception {
        var backend = new InMemoryRegistryBackend();
        var registry = new RegistrySessionStore(backend, "fx");
        var xml = XmlSessionStore.inCashMemory(memory, "fx");
        WindowState window = new WindowState("draft", WindowType.ONE_TIME_EDITOR, true, "owner",
                new WindowBounds(11, 22, 333, 444), Map.of("mode", "create", "source", "context"),
                Map.of("amount", "1,234", "date", "31.02.2026"));
        var snapshot = SessionSnapshot.of(Instant.EPOCH, "fx", MainWindowState.empty(), PlanState.CLEAN, List.of(window));
        registry.save(snapshot);
        xml.save(snapshot);
        backend.put(RegistrySessionStore.KEY_SNAPSHOT_CRC, "00000000");
        byte[] xmlBefore = Files.readAllBytes(xml.file());
        var registryBefore = backend.contents();
        RecoverySnapshots service = new LocalRecoverySnapshots(List.of(registry, xml));
        var failed = service.read("registry");
        assertEquals(SessionStoreException.Code.CORRUPT, failed.problem().orElseThrow().code());
        assertTrue(failed.snapshot().isEmpty());
        assertEquals(snapshot, service.read("xml").snapshot().orElseThrow());
        assertArrayEquals(xmlBefore, Files.readAllBytes(xml.file()));
        assertEquals(registryBefore, backend.contents());
        xml.save(snapshot.withSavedAt(Instant.ofEpochSecond(1)));
        assertEquals(Instant.ofEpochSecond(1), service.read("xml").snapshot().orElseThrow().savedAt());
        assertEquals(SessionStoreException.Code.UNKNOWN_STORE, service.read("missing").problem().orElseThrow().code());
    }

    /** Отсутствие снимка отличается от недоступности, причина не зависит от текста; cause остаётся локально. */
    @Test void absentUnavailableAndExceptionCompatibilityStayDistinct() throws Exception {
        var backend = new InMemoryRegistryBackend();
        var service = new LocalRecoverySnapshots(List.of(new RegistrySessionStore(backend, "fx")));
        assertTrue(service.read("registry").snapshot().isEmpty());
        assertTrue(service.read("registry").problem().isEmpty());
        backend.failWith("policy");
        assertEquals(SessionStoreException.Code.UNAVAILABLE, service.read("registry").problem().orElseThrow().code());
        var cause = new java.io.IOException("cause");
        var failure = new SessionStoreException(SessionStoreException.Code.IO_ERROR, "detail", cause);
        assertSame(cause, failure.getCause());
        assertEquals("detail", failure.getMessage());
        assertEquals(SessionStoreException.Code.UNSPECIFIED, new SessionStoreException("legacy").code());
        assertThrows(IllegalArgumentException.class, () -> new RecoverySnapshots.Result(
                Optional.of(SessionSnapshot.of(Instant.EPOCH, "fx", MainWindowState.empty(), PlanState.CLEAN, List.of())),
                Optional.of(new RecoverySnapshots.Problem(SessionStoreException.Code.CORRUPT, "broken"))));
    }

    /** Категория настоящего файлового отказа не становится ошибкой схемы. */
    @Test void directoryInsteadOfXmlReportsIoFailure() throws Exception {
        Path file = Files.createDirectory(memory.resolve("session-fx.xml"));
        var result = new LocalRecoverySnapshots(List.of(new XmlSessionStore(file, "fx"))).read("xml");
        assertEquals(SessionStoreException.Code.IO_ERROR, result.problem().orElseThrow().code());
        assertTrue(Files.isDirectory(file));
    }
}
