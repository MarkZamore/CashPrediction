package ru.cashprediction.fx.ui;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.alert.*;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет реальные границы Alert в отдельном процессе без CashMemory и реестра. */
@EnabledIfSystemProperty(named = "fx.alertGeometryProof", matches = "true")
public class FxAlertsGeometryTest {
    private static FxUiPort port;
    private static FxAlerts alert;
    private static AlertSession session;
    private static final AtomicInteger registrations = new AtomicInteger();
    private static final AtomicInteger answers = new AtomicInteger();

    /** Отдельный процесс изолирует toolkit от остальных тестов JavaFX. */
    @Test void realAlertRestoresBoundsBeforeRegistrationAndKeepsLiveGeometry() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-XX:-UsePerfData", "-cp", classpath, FxAlertsGeometryTest.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "проверка настоящего сообщения должна завершиться");
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("alert-geometry-ok"), output);
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    /** Показывает восстановленное и новое сообщения только при явном запуске этого стенда. */
    public static void main(String[] args) {
        try {
            Platform.startup(() -> Platform.setImplicitExit(false));
            var restored = new WindowBounds(120, 140, 640, 480);
            onFx(() -> {
                port = new FxUiPort(new Stage());
                open(restored);
            });
            // Отложенный show из конструктора проходит до следующей задачи очереди JavaFX.
            onFx(() -> {
                assertTrue(alert.showing());
                assertEquals(1, registrations.get());
                assertBounds(restored, alert.bounds());
                alert.updateAlert(session.spec());
                assertBounds(restored, alert.bounds());
                var moved = new WindowBounds(180, 200, 680, 500);
                FxFormDialog.applyBounds(alert.alert.getDialogPane().getScene().getWindow(), moved);
                assertBounds(moved, session.captureState().bounds());
                assertEquals(restored, session.restoredBounds());
                alert.close(); session.closed();
                open(null);
            });
            onFx(() -> {
                assertTrue(alert.showing());
                assertEquals(2, registrations.get());
                assertNull(session.restoredBounds());
                assertNotNull(alert.bounds());
                alert.close(); session.closed();
                assertEquals(0, answers.get());
                port.scheduler.shutdown(); port.stage.close();
            });
            Platform.exit();
            System.out.println("alert-geometry-ok");
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static void open(WindowBounds restored) {
        var spec = AlertCatalog.deleteRule("r1", "rent", Money.ofMajor(1), "RUB", "monthly", 0);
        session = new AlertSession("alert-probe", "main", spec, new AlertSession.Host() {
            /** Проверяет границы именно в момент регистрации, до продолжения восстановления. */
            @Override public void registered(AlertSession value) {
                registrations.incrementAndGet();
                if (restored != null) assertBounds(restored, value.captureState().bounds());
            }
            /** Изолированному стенду не нужен регистратор снимков. */
            @Override public void unregistered(AlertSession value) { }
        });
        if (restored != null)
            session.applyState(new WindowState(session.windowId(), WindowType.ALERT, true, "main", restored, Map.of(), Map.of()));
        session.whenShown(value -> {
            if (restored != null) assertBounds(restored, value.captureState().bounds());
        });
        // JavaFX: Alert → Swing: SwingAlerts → Web: dialog.alert
        session.attach(port.showAlert(spec, session, id -> answers.incrementAndGet()));
        alert = (FxAlerts) port.windows.get(session.windowId());
    }

    private static void assertBounds(WindowBounds expected, WindowBounds actual) {
        assertNotNull(actual);
        assertEquals(expected.x(), actual.x(), 2);
        assertEquals(expected.y(), actual.y(), 2);
        assertEquals(expected.width(), actual.width(), 2);
        assertEquals(expected.height(), actual.height(), 2);
    }

    private static void onFx(Runnable action) throws Exception {
        var task = new FutureTask<Void>(() -> { action.run(); return null; });
        Platform.runLater(task); task.get(10, TimeUnit.SECONDS);
    }
}
