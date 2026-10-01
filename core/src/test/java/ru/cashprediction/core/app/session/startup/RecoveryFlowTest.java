package ru.cashprediction.core.app.flow;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import ru.cashprediction.core.session.store.XmlSessionStore;
import ru.cashprediction.core.session.store.MarkdownSessionStore;

/** Проверяет меню восстановления и аварийные действия без остановки настоящего процесса. */
class RecoveryFlowTest {
    @TempDir Path home;

    @Test void snapshotNowSavesBothStoresAndDisabledRecordingExplains() {
        FlowHarness h = new FlowHarness(home);
        RecoveryFlow flow = new RecoveryFlow(h.context);
        flow.snapshotNow();
        assertEquals("info.recordingOff", h.alerts.getLast().purpose());
        FlowHarness.Store registry = new FlowHarness.Store("registry", h.events);
        FlowHarness.Store xml = new FlowHarness.Store("xml", h.events);
        h.recording(List.of(registry, xml));
        flow.snapshotNow();
        assertNotNull(registry.snapshot);
        assertEquals(registry.snapshot, xml.snapshot);
        assertTrue(h.events.indexOf("save:xml") < h.events.indexOf("message:status.msg.snapshot"));
        h.recorder.setEnabled(false);
        flow.snapshotNow();
        assertEquals("info.recordingOff", h.alerts.getLast().purpose());
    }

    @Test void clearCancelPreservesAndConfirmRecreatesRunningMarker() {
        FlowHarness h = new FlowHarness(home);
        FlowHarness.Store store = new FlowHarness.Store("registry", h.events);
        h.recording(List.of(store));
        h.recorder.saveNow();
        RecoveryFlow flow = new RecoveryFlow(h.context);
        flow.clear();
        assertTrue(h.alerts.getLast().restorable());
        h.answer("cancel");
        assertNotNull(store.snapshot);
        flow.clear();
        h.answer("clear");
        assertNull(store.snapshot);
        assertTrue(store.marker.isRunning());
        assertTrue(h.recorder.isStarted());
        assertTrue(h.events.contains("message:status.msg.snapshotsCleared"));
    }

    @Test void showLastOrdersXmlFirstAndIncludesActualRegistryNodeAndRawXml() throws Exception {
        FlowHarness h = new FlowHarness(home);
        Files.createDirectories(h.environment.cashMemory());
        RegistrySessionStore registry = RegistrySessionStore.inMemory("fx", h.environment.cashMemory());
        XmlSessionStore xml = h.environment.xmlStore("fx");
        registry.save(FlowHarness.snapshot("fx", false));
        xml.save(FlowHarness.snapshot("fx", false));
        h.recording(List.of(registry, xml));
        RecoveryFlow flow = new RecoveryFlow(h.context);
        flow.setDefaultStore(RecoveryStoreKind.XML);
        flow.showLast();
        String details = h.alerts.getLast().details();
        assertTrue(details.indexOf(xml.file().toString()) < details.indexOf("(JSON)"));
        assertTrue(details.contains(Files.readString(xml.file())));
        assertTrue(details.contains(registry.nodePath().replace('/', '\\')));
        assertTrue(h.alerts.getLast().content().contains("01.10.2026 10:20:30"));
        assertTrue(h.alerts.getLast().detailsExpanded());
    }

    @Test void showLastUnreadableSnapshotIsVisible() {
        FlowHarness h = new FlowHarness(home);
        FlowHarness.Store store = new FlowHarness.Store("registry", h.events);
        store.fail = true;
        h.recording(List.of(store));
        new RecoveryFlow(h.context).showLast();
        assertTrue(h.alerts.getLast().details().contains("unreadable"));
    }

    @Test void webShowLastIncludesOnlyServerFileAndNoDefaultStoreLine() throws Exception {
        FlowHarness h = new FlowHarness(home);
        h.profile = ClientProfile.web();
        Files.createDirectories(h.environment.cashMemory());
        MarkdownSessionStore server = h.environment.webStore();
        h.recording(List.of(server));
        h.recorder.saveNow();
        new RecoveryFlow(h.context).showLast();
        assertTrue(h.alerts.getLast().details().contains(server.sessionFile().toString()));
        assertTrue(h.alerts.getLast().details().contains(Files.readString(server.sessionFile())));
        assertEquals(1, h.alerts.getLast().content().lines().count());
    }

    @Test void simulateHaltCancelAndDesktopOrWebConfirmDoNotSave() {
        for (ClientProfile profile : List.of(ClientProfile.fx("25"), ClientProfile.web())) {
            FlowHarness h = new FlowHarness(home);
            h.profile = profile;
            FlowHarness.Store store = new FlowHarness.Store(profile.snapshotClient().equals("web") ? "server" : "registry", h.events);
            h.recording(List.of(store));
            RecoveryFlow flow = new RecoveryFlow(h.context);
            flow.simulateHalt();
            h.answer("cancel");
            assertNull(h.exit);
            flow.simulateHalt();
            h.answer("halt");
            assertEquals(profile.snapshotClient().equals("web") ? "WEB_CRASHED:3" : "HALT:3", h.exit);
            assertNull(store.snapshot);
            assertTrue(store.marker.isRunning());
        }
    }

    @Test void simulatedExceptionIsDeliveredByUiExecutor() {
        FlowHarness h = new FlowHarness(home);
        new RecoveryFlow(h.context).simulateException();
        assertTrue(h.queued.isEmpty());
        assertEquals(1, h.scheduled.size());
        h.scheduled.getFirst().run();
        assertEquals(1, h.queued.size());
        IllegalStateException error = assertThrows(IllegalStateException.class, h.queued.getFirst()::run);
        assertFalse(error.getMessage().isBlank());
        assertTrue(h.alerts.isEmpty());
    }

    @Test void uncaughtSavesBeforeAlertRepeatedErrorOnlyStderrAndAnswerHaltsTwo() {
        FlowHarness h = new FlowHarness(home);
        FlowHarness.Store store = new FlowHarness.Store("registry", h.events);
        h.recording(List.of(store));
        RecoveryFlow flow = new RecoveryFlow(h.context);
        flow.uncaught(Thread.currentThread(), new IllegalArgumentException("first"));
        assertTrue(h.events.indexOf("save:registry") < h.events.indexOf("alert:uncaught"));
        assertEquals(List.of("closeProgram"), h.alerts.getLast().buttons().stream().map(b -> b.id()).toList());
        assertNull(h.exit);
        PrintStream original = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            flow.uncaught(Thread.currentThread(), new IllegalStateException("second"));
        } finally {
            System.setErr(original);
        }
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("second"));
        assertEquals(1, h.alerts.size());
        assertEquals(1, h.events.stream().filter(event -> event.equals("save:registry")).count());
        h.answer("closeProgram");
        assertEquals("HALT:2", h.exit);
        assertTrue(store.marker.isRunning());
        assertFalse(h.recorder.isClosed());
    }

    @Test void uncaughtWithoutDescriptionUsesWordsAndKeepsDetailsCollapsed() {
        FlowHarness h = new FlowHarness(home);
        new RecoveryFlow(h.context).uncaught(Thread.currentThread(), new IllegalStateException());
        assertEquals("IllegalStateException: без описания", h.alerts.getLast().content());
        assertFalse(h.alerts.getLast().detailsExpanded());
        assertTrue(h.alerts.getLast().details().contains("IllegalStateException"));
    }

    @Test void fatalAlertFailureStillSavesAndHaltsWithoutCleanShutdown() {
        FlowHarness h = new FlowHarness(home);
        FlowHarness.Store store = new FlowHarness.Store("registry", h.events);
        h.recording(List.of(store));
        h.failAlert = true;
        PrintStream original = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            new RecoveryFlow(h.context).uncaught(Thread.currentThread(), new IllegalArgumentException("first"));
        } finally {
            System.setErr(original);
        }
        assertNotNull(store.snapshot);
        assertTrue(store.marker.isRunning());
        assertEquals("HALT:2", h.exit);
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("alert failed"));
    }
}
