package ru.cashprediction.core.app.flow;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.RecorderStatus;
import ru.cashprediction.core.app.LaunchOptions.RecoveryAnswer;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.markdown.SettingsMarkdown;
import ru.cashprediction.core.markdown.RuFormats;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.ui.alert.AlertCatalog;

/** Проверяет ветви запуска и сохранение единственной копии неоткрытого плана. */
class StartupFlowTest {
    @TempDir Path home;

    @Test void lastPlanWinsOverFirstAndSettingsApplyBeforeMain() throws Exception {
        FlowHarness h = new FlowHarness(home);
        Path memory = Files.createDirectories(h.environment.cashMemory());
        PlanRepository repository = new PlanRepository(memory);
        repository.save(Plan.empty("A", FlowHarness.TODAY), memory.resolve("A.md"));
        repository.save(Plan.empty("Z", FlowHarness.TODAY), memory.resolve("Z.md"));
        SettingsMarkdown.save(memory.resolve("settings.md"), AppSettings.defaults().withLastPlan("Z.md"));
        FlowHarness.Store store = new FlowHarness.Store("registry", h.events);
        h.startup(List.of(store), CrashDetector.Status.CLEAN_START).start();
        assertEquals("Z", h.document.plan().name());
        assertEquals(memory.resolve("Z.md"), h.document.file().orElseThrow());
        assertTrue(h.events.indexOf("main") < h.events.indexOf("dirty:registry"));
        assertEquals(RecorderStatus.RECORDING, h.recorderStatus);
        assertTrue(h.alerts.isEmpty());
    }

    @Test void missingLastUsesFirstPlanAndExcludesServiceFiles() throws Exception {
        FlowHarness h = prepared();
        Path memory = h.environment.cashMemory();
        new PlanRepository(memory).save(Plan.empty("A", FlowHarness.TODAY), memory.resolve("A.md"));
        SettingsMarkdown.save(memory.resolve("settings.md"), AppSettings.defaults().withLastPlan("gone.md"));
        h.startup(List.of(new FlowHarness.Store("registry", h.events)), CrashDetector.Status.CLEAN_START).start();
        assertEquals("A", h.document.plan().name());
        assertFalse(h.events.contains("wizard"));
    }

    @Test void firstStartShowsMainThenStartsRecordingThenWizard() {
        FlowHarness h = new FlowHarness(home);
        h.startup(List.of(new FlowHarness.Store("registry", h.events)), CrashDetector.Status.CLEAN_START).start();
        assertTrue(h.events.contains("wizard"), h.events.toString());
        assertTrue(h.events.indexOf("main") < h.events.indexOf("dirty:registry"));
        assertTrue(h.events.indexOf("dirty:registry") < h.events.indexOf("wizard"));
        assertTrue(h.document.file().isEmpty());
        assertFalse(h.document.isDirty());
        assertTrue(h.alerts.isEmpty());
    }

    @Test void warningLoadProducesDiagnosticsAfterMain() throws Exception {
        FlowHarness h = prepared();
        Path file = h.environment.cashMemory().resolve("fallback.md");
        Files.writeString(file, PlanMarkdownWriter.write(Plan.empty("fallback", FlowHarness.TODAY))
                .replace(RuFormats.formatDate(FlowHarness.TODAY), "invalid-date"));
        h.startup(List.of(new FlowHarness.Store("registry", h.events)), CrashDetector.Status.CLEAN_START).start();
        assertEquals("loadDiagnostics", h.alerts.getLast().purpose());
        assertFalse(h.document.loadDiagnostics().isEmpty());
        assertTrue(h.events.indexOf("main") < h.events.indexOf("alert:loadDiagnostics"));
    }

    @Test void registryAndXmlSelectionsRestoreAndCleanReportIsSilent() throws Exception {
        for (String selected : List.of("registry", "xml")) {
            FlowHarness h = prepared();
            FlowHarness.Store registry = new FlowHarness.Store("registry", h.events);
            FlowHarness.Store xml = new FlowHarness.Store("xml", h.events);
            registry.snapshot = FlowHarness.snapshot("fx", false);
            xml.snapshot = FlowHarness.snapshot("fx", false);
            h.startup(List.of(registry, xml), CrashDetector.Status.CRASHED).start();
            assertEquals(List.of("restoreRegistry", "restoreXml", "noRestore"),
                    h.alerts.getLast().buttons().stream().map(button -> button.id()).toList());
            assertFalse(h.events.contains("main"));
            assertNull(h.recorder);
            h.answer(selected.equals("registry") ? AlertCatalog.BUTTON_RESTORE_REGISTRY : AlertCatalog.BUTTON_RESTORE_XML);
            assertEquals(List.of("load", "apply", "main"), h.events.stream()
                    .filter(event -> List.of("load", "apply", "main").contains(event)).toList());
            assertTrue(h.events.indexOf("main") < h.events.indexOf("dirty:registry"));
            assertEquals(1, h.alerts.size());
            assertTrue(h.recorder.isStarted());
        }
    }

    @Test void webOffersServerAndRestoresIt() throws Exception {
        FlowHarness h = prepared();
        h.profile = ClientProfile.web();
        FlowHarness.Store server = new FlowHarness.Store("server", h.events);
        server.snapshot = FlowHarness.snapshot("web", false);
        h.startup(List.of(server), CrashDetector.Status.CRASHED).start();
        assertEquals(List.of("restoreServer", "noRestore"), h.alerts.getLast().buttons().stream().map(b -> b.id()).toList());
        assertEquals("restoreServer", h.alerts.getLast().defaultButtonId());
        h.answer("restoreServer");
        assertEquals("web", h.recorder.client());
        assertTrue(h.recorder.isStarted());
    }

    @Test void defaultXmlIsDefaultButtonAndUnavailableRegistryDoesNotBlockIt() throws Exception {
        FlowHarness h = prepared();
        SettingsMarkdown.save(h.environment.cashMemory().resolve("settings.md"),
                AppSettings.defaults().withRecoveryStore(RecoveryStoreKind.XML));
        FlowHarness.Store registry = new FlowHarness.Store("registry", h.events);
        registry.available = false;
        FlowHarness.Store xml = new FlowHarness.Store("xml", h.events);
        xml.snapshot = FlowHarness.snapshot("fx", false);
        h.startup(List.of(registry, xml), CrashDetector.Status.CRASHED).start();
        assertEquals("restoreXml", h.alerts.getLast().defaultButtonId());
        assertFalse(h.alerts.getLast().buttons().getFirst().enabled());
    }

    @Test void noRestoreClearsEveryStoreBeforeNewMarker() throws Exception {
        FlowHarness h = prepared();
        FlowHarness.Store registry = new FlowHarness.Store("registry", h.events);
        FlowHarness.Store xml = new FlowHarness.Store("xml", h.events);
        registry.snapshot = FlowHarness.snapshot("fx", false);
        xml.snapshot = registry.snapshot;
        h.startup(List.of(registry, xml), CrashDetector.Status.CRASHED).start();
        h.answer("noRestore");
        assertNull(registry.snapshot);
        assertNull(xml.snapshot);
        assertTrue(h.events.indexOf("clear:xml") < h.events.indexOf("dirty:registry"));
    }

    @Test void unreadableAndEmptySelectionsExplainThenStartNormally() throws Exception {
        for (boolean unreadable : List.of(false, true)) {
            FlowHarness h = prepared();
            FlowHarness.Store store = new FlowHarness.Store("registry", h.events);
            store.snapshot = FlowHarness.snapshot("fx", false);
            h.startup(List.of(store), CrashDetector.Status.CRASHED).start();
            store.snapshot = null;
            store.fail = unreadable;
            h.answer("restoreRegistry");
            assertEquals(unreadable ? "err.readSnapshot" : "info.snapshotEmpty", h.alerts.getLast().purpose());
            assertFalse(h.events.contains("main"));
            h.answer("ok");
            assertTrue(h.events.contains("main"));
            assertTrue(h.recorder.isStarted());
        }
    }

    @Test void alreadyRunningExitAndOpenNeverTouchStores() throws Exception {
        for (String answer : List.of("exit", "openWithoutRestore")) {
            FlowHarness h = prepared();
            FlowHarness.Store store = new FlowHarness.Store("registry", h.events);
            SessionSnapshot original = FlowHarness.snapshot("fx", true);
            store.snapshot = original;
            h.startup(List.of(store), CrashDetector.Status.ALREADY_RUNNING).start();
            h.answer(answer);
            assertNull(h.recorder);
            assertSame(original, store.snapshot);
            assertFalse(h.events.stream().anyMatch(event -> event.startsWith("dirty:") || event.startsWith("clear:")));
            if (answer.equals("exit")) {
                assertEquals("CLEAN:0", h.exit);
                assertFalse(h.events.contains("main"));
            } else {
                assertEquals(RecorderStatus.DISABLED_SECOND_INSTANCE, h.recorderStatus);
                assertTrue(h.events.contains("main"));
            }
        }
    }

    @Test void recorderNotStartedCancelAndWriteFailureLoopPreserveOriginalUntilSave() throws Exception {
        FlowHarness h = prepared();
        h.failPlan = true;
        FlowHarness.Store store = new FlowHarness.Store("registry", h.events);
        SessionSnapshot original = FlowHarness.snapshot("fx", true);
        store.snapshot = original;
        h.startup(List.of(store), CrashDetector.Status.CRASHED).start();
        h.answer("restoreRegistry");
        assertEquals("restoreReport", h.alerts.getLast().purpose());
        h.answer("ok");
        assertEquals("recorderNotStarted", h.alerts.getLast().purpose());
        assertEquals(original.plan().markdown(), h.alerts.getLast().details());
        assertFalse(h.recorder.isStarted());
        h.answer("saveSnapshotPlan");
        h.chosen.accept(Optional.empty());
        assertEquals("recorderNotStarted", h.alerts.getLast().purpose());
        assertSame(original, store.snapshot);
        assertFalse(h.recorder.isStarted());
        h.answer("saveSnapshotPlan");
        Path invalid = h.environment.cashMemory().resolve("directory.md");
        Files.createDirectory(invalid);
        h.chosen.accept(Optional.of(invalid));
        assertEquals("err.snapshotPlanSave", h.alerts.getLast().purpose());
        assertFalse(h.recorder.isStarted());
        h.answer("ok");
        assertEquals("recorderNotStarted", h.alerts.getLast().purpose());
        assertSame(original, store.snapshot);
        h.answer("saveSnapshotPlan");
        Path saved = h.environment.cashMemory().resolve("recovered.md");
        h.chosen.accept(Optional.of(saved));
        assertEquals(original.plan().markdown(), Files.readString(saved));
        assertTrue(h.recorder.isStarted());
        assertEquals(RecorderStatus.RECORDING, h.recorderStatus);
        assertTrue(h.events.contains("message:status.msg.snapshotPlanSaved"));
    }

    @Test void recorderNotStartedSkipExplicitlyStartsRecording() throws Exception {
        FlowHarness h = prepared();
        h.failPlan = true;
        FlowHarness.Store store = new FlowHarness.Store("registry", h.events);
        store.snapshot = FlowHarness.snapshot("fx", true);
        h.startup(List.of(store), CrashDetector.Status.CRASHED).start();
        h.answer("restoreRegistry");
        h.answer("ok");
        h.answer("skip");
        assertTrue(h.recorder.isStarted());
        assertFalse(h.events.contains("message:status.msg.snapshotPlanSaved"));
    }

    @Test void startupDirectoryFailureShowsErrorAndExitsOnlyAfterAnswer() throws Exception {
        FlowHarness h = new FlowHarness(home);
        Files.writeString(h.environment.cashMemory(), "blocked");
        h.startup(List.of(), CrashDetector.Status.CLEAN_START).start();
        assertEquals("startupError", h.alerts.getLast().purpose());
        assertNull(h.exit);
        h.answer("ok");
        assertEquals("CLEAN:2", h.exit);
        assertNull(h.recorder);
    }

    @Test void automaticRecoveryAnswersUseTheSameRestoreOrClearPaths() throws Exception {
        for (RecoveryAnswer answer : List.of(RecoveryAnswer.REGISTRY, RecoveryAnswer.XML, RecoveryAnswer.NONE)) {
            FlowHarness h = new FlowHarness(home, answer);
            Path memory = Files.createDirectories(h.environment.cashMemory());
            new PlanRepository(memory).save(Plan.empty("fallback", FlowHarness.TODAY), memory.resolve("fallback.md"));
            FlowHarness.Store registry = new FlowHarness.Store("registry", h.events);
            FlowHarness.Store xml = new FlowHarness.Store("xml", h.events);
            registry.snapshot = FlowHarness.snapshot("fx", false);
            xml.snapshot = registry.snapshot;
            h.startup(List.of(registry, xml), CrashDetector.Status.CRASHED).start();
            assertTrue(h.alerts.isEmpty());
            assertTrue(h.recorder.isStarted());
            assertEquals(answer == RecoveryAnswer.NONE, h.events.contains("clear:registry"));
            assertEquals(answer != RecoveryAnswer.NONE, h.events.contains("load"));
        }
    }

    @Test void automaticAlreadyOkStartsWithoutRecorder() throws Exception {
        FlowHarness h = new FlowHarness(home, RecoveryAnswer.ALREADY_OK);
        Path memory = Files.createDirectories(h.environment.cashMemory());
        new PlanRepository(memory).save(Plan.empty("fallback", FlowHarness.TODAY), memory.resolve("fallback.md"));
        h.startup(List.of(new FlowHarness.Store("registry", h.events)), CrashDetector.Status.ALREADY_RUNNING).start();
        assertTrue(h.alerts.isEmpty());
        assertNull(h.recorder);
        assertEquals(RecorderStatus.DISABLED_SECOND_INSTANCE, h.recorderStatus);
    }

    @Test void defaultXmlWinsWhenBothStoresAreAvailable() throws Exception {
        FlowHarness h = prepared();
        SettingsMarkdown.save(h.environment.cashMemory().resolve("settings.md"),
                AppSettings.defaults().withRecoveryStore(RecoveryStoreKind.XML));
        FlowHarness.Store registry = new FlowHarness.Store("registry", h.events);
        FlowHarness.Store xml = new FlowHarness.Store("xml", h.events);
        registry.snapshot = FlowHarness.snapshot("fx", false);
        xml.snapshot = registry.snapshot;
        h.startup(List.of(registry, xml), CrashDetector.Status.CRASHED).start();
        assertEquals("restoreXml", h.alerts.getLast().defaultButtonId());
        h.answer(h.alerts.getLast().defaultButtonId());
        assertTrue(h.events.contains("load"));
        assertFalse(h.events.contains("clear:registry"));
    }

    @Test void webAutomaticRecoverySelectsItsOnlyStore() throws Exception {
        FlowHarness h = new FlowHarness(home, RecoveryAnswer.REGISTRY);
        h.profile = ClientProfile.web();
        FlowHarness.Store server = new FlowHarness.Store("server", h.events);
        server.snapshot = FlowHarness.snapshot("web", false);
        h.startup(List.of(server), CrashDetector.Status.CRASHED).start();
        assertTrue(h.alerts.isEmpty());
        assertTrue(h.events.contains("load"));
        assertTrue(h.recorder.isStarted());
        assertFalse(h.events.contains("clear:server"));
    }

    /** Подготавливает обычный план, чтобы fallback не зависел от результата мастера. */
    private FlowHarness prepared() throws Exception {
        FlowHarness h = new FlowHarness(home);
        Path memory = Files.createDirectories(h.environment.cashMemory());
        new PlanRepository(memory).save(Plan.empty("fallback", FlowHarness.TODAY), memory.resolve("fallback.md"));
        return h;
    }
}
