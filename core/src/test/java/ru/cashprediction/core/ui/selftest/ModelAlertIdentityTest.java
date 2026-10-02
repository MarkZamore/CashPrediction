package ru.cashprediction.core.ui.selftest;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import static org.junit.jupiter.api.Assertions.*;

/** Эталон сообщений использует реальные идентификаторы ядра, а не собственный счётчик окон. */
final class ModelAlertIdentityTest {
    @TempDir Path root;

    /** Свежие сообщения обоих видов сохраняют идентификатор, выделенный общим контроллером. */
    @Test void restorableAndTransientAlertsUseControllerIds() {
        var environment = AppEnvironment.from(LaunchOptions.parse("--home", root.toString(), "--registry", "memory", "--today", "2026-09-13"));
        var port = new RecordingUiPort(ClientProfile.fx("25"));
        var controller = new AppController(port, environment);
        port.alertIdentity = () -> controller.state().windows().windows().getLast().windowId();
        try {
            controller.start();
            controller.showAlert(AlertCatalog.actualize(java.time.LocalDate.of(2026, 9, 13),
                    ru.cashprediction.core.model.Money.ZERO, "RUB"), ignored -> { });
            var saved = port.top();
            assertNotNull(saved.alertSession);
            assertEquals(saved.alertSession.windowId(), saved.id);
            assertEquals(controller.state().windows().windows().getLast().windowId(), saved.id);
            controller.showAlert(AlertCatalog.info("recordingOff"), ignored -> { });
            var transientAlert = port.top();
            assertNull(transientAlert.alertSession);
            assertEquals(controller.state().windows().windows().getLast().windowId(), transientAlert.id);
            assertNotEquals(saved.id, transientAlert.id);
        } finally { port.scheduler.shutdown(); }
    }
}
