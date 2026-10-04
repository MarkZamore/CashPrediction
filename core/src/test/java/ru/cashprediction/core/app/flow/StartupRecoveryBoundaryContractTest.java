package ru.cashprediction.core.app.flow;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.alert.AlertCatalog;

/** Альтернативная data-only служба проходит настоящий StartupFlow и локальный RestoreCoordinator. */
class StartupRecoveryBoundaryContractTest {
    @TempDir Path home;

    /** Выбранные данные приходят через внедрённую службу, а не прямое load локального store. */
    @Test void alternateServiceFeedsRealRestoreWithoutClientRewrite() throws Exception {
        FlowHarness ui = new FlowHarness(home, LaunchOptions.RecoveryAnswer.XML);
        Files.createDirectories(ui.environment.cashMemory());
        FlowHarness.Store store = new FlowHarness.Store("xml", ui.events);
        store.fail = true;
        List<String> calls = new ArrayList<>();
        SessionSnapshot snapshot = FlowHarness.snapshot("fx", false);
        RecoverySnapshots service = id -> {
            calls.add(id);
            return new RecoverySnapshots.Result(Optional.of(snapshot), Optional.empty());
        };
        StartupFlow startup = new StartupFlow(ui.context, ui.externalChanges.storage(), service, List.of(store),
                ui.source, ui.target, (state, owner, shown, failed) -> fail("unexpected window"),
                () -> new CrashDetector.Detection(CrashDetector.Status.CRASHED, Map.of(), null));
        startup.start();
        assertEquals(List.of("xml"), calls);
        assertEquals(snapshot.main(), ui.restored);
        assertTrue(ui.events.contains("load"));
        assertTrue(ui.events.contains("main"));
        assertEquals(RecorderStatus.RECORDING, ui.recorderStatus);
        assertTrue(ui.alerts.isEmpty());
        ui.recorder.shutdownClean();
    }

    /** Отказ data-only службы не открывает главное окно или рекордер до ответа пользователя. */
    @Test void machineFailureUsesExistingErrorUiAndDoesNotApplySnapshot() throws Exception {
        FlowHarness ui = new FlowHarness(home, LaunchOptions.RecoveryAnswer.XML);
        Files.createDirectories(ui.environment.cashMemory());
        var problem = new RecoverySnapshots.Problem(SessionStoreException.Code.CORRUPT, "remote detail");
        RecoverySnapshots service = id -> new RecoverySnapshots.Result(Optional.empty(), Optional.of(problem));
        StartupFlow startup = new StartupFlow(ui.context, ui.externalChanges.storage(), service,
                List.of(new FlowHarness.Store("xml", ui.events)), ui.source, ui.target,
                (state, owner, shown, failed) -> fail("unexpected window"),
                () -> new CrashDetector.Detection(CrashDetector.Status.CRASHED, Map.of(), null));
        startup.start();
        assertNull(ui.recorder);
        assertNull(ui.restored);
        assertFalse(ui.events.contains("main"));
        assertEquals("err.readSnapshot", ui.alerts.getLast().purpose());
        assertTrue(ui.alerts.getLast().content().contains("remote detail"));
    }
}
