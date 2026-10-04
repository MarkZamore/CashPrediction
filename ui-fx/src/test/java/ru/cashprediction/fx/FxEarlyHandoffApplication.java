package ru.cashprediction.fx;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import ru.cashprediction.fx.ui.FxEarlyHandoffDeniedSession;

/**
 * Fork-probe запускается Java launcher как подкласс Application, а не через поддельный UI.
 * LauncherImpl должен инициализировать toolkit до main; start не вызывается ни в одном режиме.
 * Проверяет latent keepalive условие, но не доказывает причину первого native failure.
 */
public final class FxEarlyHandoffApplication extends Application {
    /** Создаётся только если launcher ошибочно попытается запустить интерфейс. */
    public FxEarlyHandoffApplication() { }

    /** Отвергает запуск приложения: проверяемый handoff обязан происходить до start и окон. */
    @Override public void start(Stage stage) { throw new AssertionError("UI start must not run during early handoff"); }

    /** Проверяет уже живой toolkit, реальный denied session и настоящий exitAfterLaunch или контрольный return. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("exit") || args[0].equals("return")))
            throw new IllegalArgumentException("Expected mode and ownership token");
        String prefix = "PROBE:" + args[1] + ":";
        CountDownLatch initialized = new CountDownLatch(1);
        Platform.runLater(() -> {
            if (!Platform.isFxApplicationThread()) throw new AssertionError("Expected actual FX callback");
            System.out.println(prefix + "TOOLKIT_READY:" + ProcessHandle.current().pid());
            initialized.countDown();
        });
        if (!initialized.await(5, TimeUnit.SECONDS)) throw new AssertionError("FX toolkit callback deadline");
        AtomicInteger closed = new AtomicInteger();
        var session = FxEarlyHandoffDeniedSession.create(closed);
        var updates = FxMain.class.getDeclaredField("updates"); updates.setAccessible(true); updates.set(null, session);
        if (session.beforeUi()) throw new AssertionError("Expected denied beforeUi");
        if (closed.get() != 1) throw new AssertionError("Denied session must close exactly once");
        System.out.println(prefix + "BEFORE_DENIED:CLOSED=" + closed.get());
        FxMain.recordCleanExit(7);
        if (args[0].equals("exit")) {
            FxMain.exitAfterLaunch();
            throw new AssertionError("exitAfterLaunch returned");
        }
        System.out.println(prefix + "RETURN_WITHOUT_EXIT");
        // Контроль воспроизводит прежний main return при уже инициализированном toolkit.
    }
}
