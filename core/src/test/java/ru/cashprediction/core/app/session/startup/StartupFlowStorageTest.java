package ru.cashprediction.core.app.flow;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.markdown.SettingsMarkdown;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.service.storage.FakePlanStorage;
import ru.cashprediction.core.service.storage.FilePlanStorage;
import ru.cashprediction.core.service.storage.PlanStorage;
import ru.cashprediction.core.session.CrashDetector;

/** Доказывает использование внедрённого хранения при обычном запуске, включая версии списка и последнего плана. */
class StartupFlowStorageTest {
    @TempDir Path home;

    @Test void startupListsAndReadsFakeStorageWithoutPlanFiles() throws Exception {
        FlowHarness ui = new FlowHarness(home);
        FakePlanStorage storage = new FakePlanStorage();
        Path file = ui.environment.cashMemory().resolve("Stored.md");
        var reference = FilePlanStorage.reference(file);
        Plan plan = Plan.empty("Stored", FlowHarness.TODAY);
        var version = storage.put(reference, plan);
        storage.collection(FilePlanStorage.collection(ui.environment.cashMemory()), List.of(reference));
        startup(ui, storage).start();
        assertEquals(List.of("list", "read"), storage.calls());
        assertEquals(Optional.of(version), storage.lastReadExpected());
        assertEquals(plan, ui.document.plan());
        assertEquals(file, ui.document.file().orElseThrow());
        assertFalse(Files.exists(file));
        assertTrue(ui.events.indexOf("main") < ui.events.indexOf("dirty:registry"));
        assertTrue(ui.alerts.isEmpty());
        assertEquals(version, ui.externalChanges.expectedVersion(reference, PlanStorage.Version.ABSENT));
    }

    @Test void lastPlanUsesObservedVersionAndDoesNotListOtherPlans() throws Exception {
        FlowHarness ui = new FlowHarness(home);
        Files.createDirectories(ui.environment.cashMemory());
        SettingsMarkdown.save(ui.environment.cashMemory().resolve("settings.md"), AppSettings.defaults().withLastPlan("Stored.md"));
        FakePlanStorage storage = new FakePlanStorage();
        Path file = ui.environment.cashMemory().resolve("Stored.md");
        var version = storage.put(FilePlanStorage.reference(file), Plan.empty("Stored", FlowHarness.TODAY));
        startup(ui, storage).start();
        assertEquals(List.of("version", "read"), storage.calls());
        assertEquals(Optional.of(version), storage.lastReadExpected());
        assertEquals("Stored", ui.document.plan().name());
        assertFalse(Files.exists(file));
    }

    @Test void failedReadDoesNotInstallPlanOrStartRecorder() {
        FlowHarness ui = new FlowHarness(home);
        FakePlanStorage storage = new FakePlanStorage();
        var reference = FilePlanStorage.reference(ui.environment.cashMemory().resolve("Stored.md"));
        storage.put(reference, Plan.empty("Stored", FlowHarness.TODAY));
        storage.collection(FilePlanStorage.collection(ui.environment.cashMemory()), List.of(reference));
        storage.readProblem(new PlanStorage.Problem(PlanStorage.Code.CONFLICT, PlanStorage.Conflict.VERSION_CHANGED, "test"));
        startup(ui, storage).start();
        assertTrue(ui.document.file().isEmpty());
        assertNull(ui.recorder);
        assertFalse(ui.events.contains("main"));
        assertFalse(ui.events.contains("dirty:registry"));
        assertEquals("startupError", ui.alerts.getLast().purpose());
    }

    /** Подключает имитацию к реальному запуску, сохраняя готовые изолированные участники сеанса. */
    private StartupFlow startup(FlowHarness ui, PlanStorage storage) {
        return new StartupFlow(ui.context, storage, List.of(new FlowHarness.Store("registry", ui.events)),
                ui.source, ui.target, (state, owner, shown, failed) -> { throw new AssertionError("Unexpected window"); },
                () -> new CrashDetector.Detection(CrashDetector.Status.CLEAN_START, Map.of(), null));
    }
}
