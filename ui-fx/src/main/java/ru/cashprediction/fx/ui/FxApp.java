package ru.cashprediction.fx.ui;

import javafx.stage.Stage;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.selftest.*;
import java.nio.file.Path;

/** Запуск общего контроллера с JavaFX-портом и изолируемым настоящим самотестом. */
public final class FxApp {
    private FxApp() { }

    /** Создаёт конкретный порт и сохраняет его для драйвера до обёртки контроллера. */
    public static void start(Stage stage, LaunchOptions options) {
        AppEnvironment environment = AppEnvironment.from(options);
        FxUpdateSession updates = FxUpdateSession.open(environment, options.toArguments().toArray(String[]::new));
        if (!updates.beforeUi()) { javafx.application.Platform.exit(); return; }
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(updates::close, "cp-fx-update-close"));
            start(stage, environment, updates);
        }
        catch (RuntimeException | LinkageError failure) { updates.close(); throw failure; }
    }

    /** Запускает интерфейс после успешного общего предстартового барьера. */
    public static void start(Stage stage, AppEnvironment environment, FxUpdateSession updates) {
        var options = environment.options();
        FxIcons.application(stage);
        FxUiPort port = new FxUiPort(stage, environment.clock()::today); port.selftest = options.selftest() != null;
        port.updateCallbacks(updates::afterUiReady, updates::close);
        AppController controller = new AppController(port, environment); port.bind(controller);
        FxShutdownHook.install(controller::saveShutdownSnapshot);
        Thread.currentThread().setUncaughtExceptionHandler(controller::uncaught); Thread.setDefaultUncaughtExceptionHandler((thread, error) -> port.executor().execute(() -> controller.uncaught(thread, error)));
        controller.start();
        if (options.selftest() != null) {
            FxUiDriver driver = new FxUiDriver(port, controller, environment);
            Thread worker = new Thread(() -> {
                Path out = options.selftestOut() == null ? environment.cashMemory().resolve("selftest") : options.selftestOut();
                SelfTestRunner.Report report = new SelfTestRunner(driver, out, environment).run(SelfTestScript.load(options.selftest()));
                System.out.println("SELFTEST DONE " + (report.ok() ? "OK" : "FAIL"));
                // Отчёт должен быть опубликован до возврата launch и явного System.exit в точке входа.
                if (port.exited) javafx.application.Platform.runLater(javafx.application.Platform::exit);
            }, "cp-fx-widget-selftest"); worker.start();
        }
    }
}
