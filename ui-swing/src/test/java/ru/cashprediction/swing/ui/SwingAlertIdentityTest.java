package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.alert.*;

/** Проверяет происхождение id сообщений от живого реестра окон ядра и восстановленного сеанса. */
class SwingAlertIdentityTest {
    @TempDir Path directory;

    @Test void alertsUseCoreWindowIdentityForBothSessionKinds() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var environment = AppEnvironment.from(LaunchOptions.parse(new String[]{"--home", directory.toString(), "--registry", "memory", "--selftest", "s08-alerts"}));
            var port = new SwingUiPort(environment); var app = new AppController(port, environment); port.bind(app);
            try {
                // JavaFX: Alert → Swing: SwingAlerts → Web: dialog.alert
                var info = app.showAlert(AlertCatalog.info("validateOk"), null);
                String infoId = app.state().windows().windows().getLast().windowId();
                assertSame(info, port.alerts.get(infoId));
                info.close();
                var confirm = app.showAlert(AlertCatalog.clearSnapshots(), null);
                String confirmId = app.state().windows().windows().getLast().windowId();
                assertSame(confirm, port.alerts.get(confirmId));
                assertEquals(confirmId, port.alerts.get(confirmId).session.windowId());
                var restored = new AlertSession("restored7", "main", AlertCatalog.clearSnapshots(), new AlertSession.Host() {
                    /** Тесту не нужна запись восстановленного сеанса. */
                    @Override public void registered(AlertSession session) { }
                    /** Тесту не нужна запись закрытия сеанса. */
                    @Override public void unregistered(AlertSession session) { }
                });
                assertEquals("restored7", port.alertId(restored));
            } finally { port.exit(ExitKind.CLEAN, 0); }
        });
    }
}
