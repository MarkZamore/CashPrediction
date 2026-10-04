package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.session.StoreStatus;

/** Барьер начального снимка не считает маркер или незавершённую запись сохранённым снимком. */
class SwingStartupBarrierTest {
    @Test void onlyStartupQuestionsReleaseRecorderInstallationWait() {
        assertTrue(SwingUiDriver.startupDecision("alreadyRunning"));
        assertTrue(SwingUiDriver.startupDecision("crashRecovery"));
        assertTrue(SwingUiDriver.startupDecision("restoreReport"));
        assertTrue(SwingUiDriver.startupDecision("recorderNotStarted"));
        assertTrue(SwingUiDriver.startupDecision("loadDiagnostics"));
        assertFalse(SwingUiDriver.startupDecision("clearSnapshots"));
        assertFalse(SwingUiDriver.startupDecision("about"));
    }
    @Test void pendingRestoreAndDisabledInstanceDoNotWaitForImpossibleSnapshot() {
        assertTrue(SwingUiDriver.snapshotWaitNotRequired(ru.cashprediction.core.app.RecorderStatus.PENDING_RESTORE, true));
        assertFalse(SwingUiDriver.snapshotWaitNotRequired(ru.cashprediction.core.app.RecorderStatus.PENDING_RESTORE, false));
        assertTrue(SwingUiDriver.snapshotWaitNotRequired(ru.cashprediction.core.app.RecorderStatus.DISABLED_SECOND_INSTANCE, false));
        assertFalse(SwingUiDriver.snapshotWaitNotRequired(ru.cashprediction.core.app.RecorderStatus.NOT_STARTED, true));
        assertFalse(SwingUiDriver.snapshotWaitNotRequired(ru.cashprediction.core.app.RecorderStatus.RECORDING, true));
    }
    @Test void waitsForAllActualSnapshotResults() {
        var saved = new StoreStatus("registry", true, Instant.EPOCH, "");
        assertFalse(SwingUiDriver.startupStoresReady(2, List.of()));
        assertFalse(SwingUiDriver.startupStoresReady(2, List.of(saved)));
        assertFalse(SwingUiDriver.startupStoresReady(2, List.of(saved, new StoreStatus("xml", true, null, ""))));
        assertTrue(SwingUiDriver.startupStoresReady(2, List.of(saved, new StoreStatus("xml", true, Instant.EPOCH, ""))));
    }
    @Test void actualFailureSettlesButRemainsFailure() {
        var failure = new StoreStatus("xml", false, null, "write failed");
        assertTrue(SwingUiDriver.startupStoresReady(1, List.of(failure)));
        assertFalse(failure.ok()); assertNull(failure.savedAt());
    }
}
