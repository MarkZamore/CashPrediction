package ru.cashprediction.core.app.flow;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.store.*;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.update.install.InstallCoordinator;
import ru.cashprediction.core.update.lifecycle.*;
import ru.cashprediction.core.update.model.UpdateProblem;

/** Сквозные headless-сценарии production запуска и восстановления, без клиентских toolkit. */
@org.junit.jupiter.api.Timeout(15)
class StartupDataBoundaryIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);
    private static final Instant NOW = Instant.parse("2026-10-01T10:20:30Z");
    @TempDir Path temporary;

    /** Реальная ошибка update lock проходит клиентский gate и настоящий controller.start/StartupFlow. */
    @Test void realUpdaterBarrierFailureStillStartsControllerAndRecorder() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("update-copy")).toRealPath();
        Path memory = Files.createDirectory(home.resolve("CashMemory"));
        Path blocker = memory.resolve("Updates");
        Files.writeString(blocker, "lock-blocker-sentinel");
        var coordinator = new InstallCoordinator(home, memory, "fx", new String[0], 1);
        var constructor = UpdateLifecycle.class.getDeclaredConstructor(Path.class, InstallCoordinator.class, URI.class);
        constructor.setAccessible(true);
        var lifecycle = constructor.newInstance(home, coordinator, null);
        var port = new FakeUiPort(ClientProfile.fx("25"));
        var controller = new AppController(port, environment(home, null));
        try {
            // Порядок клиентского launcher: updater gate, core startup, затем main-ready.
            if (lifecycle.beforeUi()) controller.start();
            assertEquals(UpdateProblem.Code.BARRIER_FAILED, lifecycle.beforeUiResult().problem().orElseThrow().code());
            assertTrue(lifecycle.beforeUiResult().allowed());
            assertEquals(1, port.calls("showMain").size());
            assertTrue(port.exitKind().isEmpty(), "updater failure must not exit application");
            assertTrue(port.alerts().isEmpty(), "silent updater must not cause startup error alert");
            assertNotNull(controller.recorder());
            assertTrue(controller.recorder().isStarted());
            assertEquals(RecorderStatus.RECORDING, controller.state().recorder());
            lifecycle.afterUiReady();
            controller.recorder().saveNow();
            var xml = XmlSessionStore.inCashMemory(memory, "fx");
            assertFalse(xml.load().isEmpty(), "real recorder must persist real startup state");
            assertEquals("lock-blocker-sentinel", Files.readString(blocker));
            assertBoundary();
        } finally {
            if (controller.recorder() != null) controller.recorder().shutdownClean();
            port.scheduler().shutdown();
            lifecycle.close();
        }
        assertEquals(UpdateSessionLifecycle.Stage.CLOSED, lifecycle.status().stage());
    }

    /** Выбранный XML проходит data-only границу, реальные формы и повторную запись при corrupt registry. */
    @Test void selectedXmlRestoresNestedRealFormsAndPersistsRawInvalidFields() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("restore-copy"));
        var environment = environment(home, LaunchOptions.RecoveryAnswer.XML);
        Path memory = Files.createDirectories(environment.cashMemory());
        var backend = new InMemoryRegistryBackend();
        var registry = new RegistrySessionStore(backend, "fx");
        var xml = XmlSessionStore.inCashMemory(memory, "fx");
        var parent = draft("source-parent", "main", false, "1,234", "31.02.2026", 11);
        var child = draft("source-child", "source-parent", true, "invalid money", "invalid date", 22);
        var snapshot = SessionSnapshot.of(NOW, "fx", MainWindowState.empty(), PlanState.CLEAN, List.of(parent, child));
        registry.save(snapshot);
        xml.save(snapshot);
        backend.put(RegistrySessionStore.KEY_SNAPSHOT_CRC, "00000000");
        var corruptBefore = backend.contents();
        byte[] originalXml = Files.readAllBytes(xml.file());
        var local = new LocalRecoverySnapshots(List.of(registry, xml));
        List<String> reads = new ArrayList<>();
        RecoverySnapshots boundary = id -> {
            reads.add(id);
            return local.read(id);
        };
        var port = new FakeUiPort(ClientProfile.fx("25"));
        var controller = new AppController(port, environment);
        var bridge = new SessionBridge(controller);
        var startup = new StartupFlow(controller, controller.planStorage(), boundary, List.of(xml),
                bridge, bridge, new CoreWindowFactory(controller),
                () -> new CrashDetector.Detection(CrashDetector.Status.CRASHED, Map.of(), null));
        try {
            startup.start();
            assertEquals(List.of("xml"), reads, "auto-selected recovery must not read/fallback to registry");
            assertEquals(1, port.calls("showMain").size());
            assertTrue(controller.recorder().isStarted(), "recorder starts before modal shown callbacks");
            assertEquals(1, port.forms().size(), "child waits for parent shown callback");
            FormSession restoredParent = port.forms().getFirst().session();
            assertDraft(parent, restoredParent);
            assertNotEquals(parent.id(), restoredParent.windowId());
            assertEquals("main", restoredParent.ownerId());
            restoredParent.shown();
            assertEquals(2, port.forms().size(), "real RestoreCoordinator opens child after shown");
            FormSession restoredChild = port.forms().getLast().session();
            assertDraft(child, restoredChild);
            assertNotEquals(child.id(), restoredChild.windowId());
            assertEquals(restoredParent.windowId(), restoredChild.ownerId(), "owner is remapped to restored parent");
            restoredChild.shown();
            assertEquals(RecorderStatus.RECORDING, controller.state().recorder());
            assertTrue(port.alerts().isEmpty(), "no restore warning or startup error is expected");
            assertEquals(corruptBefore, backend.contents(), "quarantine must survive recorder.start");
            controller.recorder().saveNow();
            var persisted = xml.load().orElseThrow();
            assertEquals(2, persisted.windows().size());
            assertEquals(restoredParent.captureState(), persisted.windows().getFirst());
            assertEquals(restoredChild.captureState(), persisted.windows().getLast());
            assertEquals(restoredParent.windowId(), persisted.windows().getLast().ownerId());
            assertFalse(Arrays.equals(originalXml, Files.readAllBytes(xml.file())), "real recorder wrote new window ids");
            assertEquals(corruptBefore, backend.contents(), "quarantine must survive recorder.saveNow");
            assertTrue(local.read("registry").problem().isPresent(), "corrupt registry must remain an explicit read failure");
            assertBoundary();
        } finally {
            if (controller.recorder() != null) controller.recorder().shutdownClean();
            port.scheduler().shutdown();
        }
        assertEquals(corruptBefore, backend.contents(), "quarantine must survive clean shutdown");
    }

    /** Окружение разрешает только registry-memory и собственный CashMemory, часы фиксированы. */
    private static AppEnvironment environment(Path home, LaunchOptions.RecoveryAnswer answer) {
        var options = new LaunchOptions(home, null, true, TODAY, null, null, answer, false, true, true, List.of());
        return new AppEnvironment(options, home, home.resolve("CashMemory"),
                AppClock.of(Clock.fixed(NOW, ZoneOffset.UTC), TODAY));
    }

    /** Создаёт исходные необработанные данные формы с контекстом и геометрией. */
    private static WindowState draft(String id, String owner, boolean modal, String money, String date, int x) {
        return new WindowState(id, WindowType.ONE_TIME_EDITOR, modal, owner, new WindowBounds(x, 33, 560, 440),
                Map.of("mode", "create", "source", "context-" + id),
                Map.of("title", id, "amount", money, "date", date, "kind", "EXPENSE", "note", "raw-note-" + id));
    }

    /** Проверяет реальные validation/view/capture, а не только исходный snapshot payload. */
    private static void assertDraft(WindowState original, FormSession form) {
        assertEquals(WindowType.ONE_TIME_EDITOR, form.windowType());
        assertEquals(original.modal(), form.modal());
        assertEquals(original.bounds(), form.captureState().bounds());
        assertEquals(original.context(), form.captureState().context());
        original.fields().forEach((key, value) -> assertEquals(value, form.captureState().fields().get(key), key));
        assertEquals(original.fields().get("amount"), form.view().fields().get("amount").value());
        assertEquals(original.fields().get("date"), form.view().fields().get("date").value());
        assertEquals(Problem.Severity.ERROR, form.view().problem().severity());
        assertFalse(form.view().buttons().get("ok").enabled(), "invalid draft must not become a valid submitted transaction");
    }

    /** DTO границы не раскрывают toolkit; наличие desktop в JVM Surefire не является зависимостью core. */
    private static void assertBoundary() {
        for (Class<?> dto : List.of(RecoverySnapshots.Result.class, RecoverySnapshots.Problem.class,
                UpdateSessionLifecycle.StartupResult.class, UpdateSessionLifecycle.Status.class, UpdateProblem.class)) {
            assertTrue(dto.isRecord(), dto.getName());
            for (var component : dto.getRecordComponents()) {
                String type = component.getGenericType().getTypeName();
                assertFalse(type.contains("javafx.") || type.contains("javax.swing.") || type.contains("java.awt."), type);
                assertFalse(Throwable.class.isAssignableFrom(component.getType()), type);
            }
        }
        // Компилированный module-info проверяет ServiceBoundaryContractTest независимо от harness JUnit.
    }
}
