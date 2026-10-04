package ru.cashprediction.core.app.session.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.flow.CoreWindowFactory;
import ru.cashprediction.core.app.flow.SessionBridge;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.store.*;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.form.Problem;

/** Проверяет literal legacy снимки через реальные stores, recovery boundary и общую фабрику мастера. */
class LiteralWizardStoreRecoveryCompatibilityTest {
    /** Старые bytes читаются до первой новой записи; raw ввод и новые поля переживают второй recovery. */
    @ParameterizedTest @ValueSource(strings = {"json", "markdown", "xml"})
    void literalLegacyStoreThroughCoreFactoryAndNewFields(String format, @TempDir Path home) throws Exception {
        Path fixture = fixture(format);
        byte[] original = Files.readAllBytes(fixture);
        String client = format.equals("json") ? "swing" : format.equals("xml") ? "fx" : "web";
        ClientProfile profile = switch (client) {
            case "swing" -> ClientProfile.swing();
            case "fx" -> ClientProfile.fx("25");
            default -> ClientProfile.web();
        };
        Path memory = Files.createDirectories(home.resolve("CashMemory"));
        SessionStore store = seedLiteralStore(format, client, memory, original);
        SessionSnapshot legacy = readBoundary(store);
        WindowState old = legacy.windows().getFirst();
        assertEquals(WindowType.NEW_PLAN_WIZARD, old.type());
        assertFalse(old.fields().containsKey("displayPeriod"));
        assertEquals("abc", old.field("quickExpenseAmount"));
        assertEquals(format.equals("markdown") ? "12,3,4" : "12 3", old.field("startBalance"));

        CaptureContext first = new CaptureContext(home, profile);
        SessionBridge bridge = new SessionBridge(first.flow);
        first.recorder = SessionRecorder.create(client, List.of(store), UiExecutor.direct(), bridge);
        try {
            restoreThroughFactory(legacy, first, bridge);
            FormSession form = first.sessions.getFirst();
            assertEquals("M12", form.state().value("displayPeriod"));
            old.fields().forEach((key, value) -> assertEquals(value, form.state().value(key), key));
            assertEquals(Problem.Severity.ERROR, form.view().problem().severity());
            assertFalse(form.view().buttons().get("finish").enabled());
            assertEquals(old.bounds(), first.placement.bounds());
            assertEquals(old.contextValue("page"), form.captureState().contextValue("page"));
            assertEquals("main", form.ownerId());

            form.fieldChanged("displayPeriod", "not-yet", true, 1);
            first.recorder.saveNow();
            SessionSnapshot saved = readBoundary(store);
            assertEquals("not-yet", saved.windows().getFirst().field("displayPeriod"));
            old.fields().forEach((key, value) -> assertEquals(value, saved.windows().getFirst().field(key), key));
            assertEquals(saved, first.recorder.lastCaptured().orElseThrow());

            CaptureContext second = new CaptureContext(home, profile);
            SessionBridge secondBridge = new SessionBridge(second.flow);
            second.recorder = SessionRecorder.create(client, List.of(store), UiExecutor.direct(), secondBridge);
            try {
                restoreThroughFactory(saved, second, secondBridge);
                FormSession reopened = second.sessions.getFirst();
                assertEquals("not-yet", reopened.state().value("displayPeriod"));
                assertEquals(old.contextValue("page"), reopened.captureState().contextValue("page"));
                old.fields().forEach((key, value) -> assertEquals(value, reopened.state().value(key), key));
                assertFalse(reopened.view().buttons().get("finish").enabled());
                reopened.fieldChanged("displayPeriod", "ALL", true, 1);
                second.recorder.saveNow();
                SessionSnapshot again = readBoundary(store);
                assertEquals("ALL", again.windows().getFirst().field("displayPeriod"));
                old.fields().forEach((key, value) -> assertEquals(value, again.windows().getFirst().field(key), key));
                assertEquals("main", again.windows().getFirst().ownerId());
                assertEquals(1, again.schemaVersion());
                assertEquals("not-yet", saved.windows().getFirst().field("displayPeriod"),
                        "Поздняя запись не меняет owned snapshot первого цикла");
            } finally {
                second.recorder.shutdownClean();
            }
        } finally {
            first.recorder.shutdownClean();
        }
        assertArrayEquals(original, Files.readAllBytes(fixture), "Исходный fixture не меняется");
    }

    /** Выбирает существующий файл с bytes прежнего клиента, не генерируя старый snapshot encoder-ом. */
    private static Path fixture(String format) throws Exception {
        String relative = switch (format) {
            case "json" -> "/session/legacy/swing/new-plan-wizard-invalid/registry.json";
            case "xml" -> "/session/legacy/fx/new-plan-wizard-invalid/session-fx.xml";
            default -> "/session/legacy/web/new-plan-wizard-invalid/web-session.md";
        };
        return Path.of(LiteralWizardStoreRecoveryCompatibilityTest.class.getResource(relative).toURI());
    }

    /** Устанавливает literal bytes в тестовую копию persistent store, не вызывая store.save для legacy seed. */
    private static SessionStore seedLiteralStore(String format, String client, Path memory, byte[] bytes)
            throws Exception {
        if (format.equals("json")) {
            String json = new String(bytes, StandardCharsets.UTF_8);
            var backend = new InMemoryRegistryBackend();
            int count = (json.length() + RegistrySessionStore.CHUNK_SIZE - 1) / RegistrySessionStore.CHUNK_SIZE;
            for (int index = 0; index < count; index++) {
                int start = index * RegistrySessionStore.CHUNK_SIZE;
                backend.put("snapshot." + index, json.substring(start,
                        Math.min(json.length(), start + RegistrySessionStore.CHUNK_SIZE)));
            }
            CRC32 crc = new CRC32();
            crc.update(bytes);
            backend.put(RegistrySessionStore.KEY_SNAPSHOT_COUNT, Integer.toString(count));
            backend.put(RegistrySessionStore.KEY_SNAPSHOT_LENGTH, Integer.toString(json.length()));
            backend.put(RegistrySessionStore.KEY_SNAPSHOT_CRC, String.format("%08x", crc.getValue()));
            backend.put(RegistrySessionStore.KEY_SNAPSHOT_TIME, "2026-09-13T22:24:57.132815900Z");
            return new RegistrySessionStore(backend, client);
        }
        if (format.equals("xml")) {
            XmlSessionStore store = XmlSessionStore.inCashMemory(memory, client);
            Files.write(store.file(), bytes);
            return store;
        }
        MarkdownSessionStore store = MarkdownSessionStore.inCashMemory(memory);
        Files.write(store.sessionFile(), bytes);
        return store;
    }

    /** Читает настоящий store через новый интерфейс владельца recovery, без обхода ошибок границы. */
    private static SessionSnapshot readBoundary(SessionStore store) {
        RecoverySnapshots.Result result = new LocalRecoverySnapshots(List.of(store)).read(store.id());
        assertTrue(result.problem().isEmpty(), result.problem().toString());
        return result.snapshot().orElseThrow();
    }

    /** Coordinator вызывает реальную фабрику/catalog; CaptureContext подменяет только платформенные эффекты. */
    private static void restoreThroughFactory(SessionSnapshot snapshot, CaptureContext context, SessionBridge bridge) {
        var report = new AtomicReference<RestoreReport>();
        new RestoreCoordinator().restore(snapshot, bridge, new CoreWindowFactory(context.flow),
                context.recorder, report::set);
        assertNotNull(report.get());
        assertEquals(1, report.get().windowsRestored(), report.get().warnings().toString());
        assertEquals(1, context.sessions.size());
        assertEquals(1, context.registrations);
        assertEquals(1, context.shownCount);
        assertEquals(1, context.recorder.registeredWindows().size());
        assertSame(context.sessions.getFirst(), context.recorder.registeredWindows().getFirst());
        assertTrue(context.recorder.isStarted());
    }
}
