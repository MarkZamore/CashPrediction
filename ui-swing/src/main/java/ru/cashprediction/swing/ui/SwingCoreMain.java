package ru.cashprediction.swing.ui;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.selftest.*;

/** Запуск опционального интерфейса общего ядра отдельно от прежнего клиента. */
public final class SwingCoreMain {
    private SwingCoreMain() { }

    /** Запускает контроллер в EDT и настоящий драйвер самотеста в отдельном потоке. */
    public static void launch(LaunchOptions options) {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Swing core requires an interactive desktop");
        AppEnvironment environment = AppEnvironment.from(options);
        SwingUtilities.invokeLater(() -> {
            SwingLook.install();
            SwingUiPort port = new SwingUiPort(environment);
            AppController app = new AppController(port, environment); port.bind(app);
            Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> port.executor().execute(() -> app.uncaught(thread, failure)));
            app.start();
            if (options.isSelftest()) {
                Thread runner = new Thread(() -> {
                    try {
                        SelfTestScript script = SelfTestScript.load(options.selftest());
                        Path out = options.selftestOut() == null ? environment.cashMemory().resolve("selftest") : options.selftestOut();
                        SwingUiDriver driver = new SwingUiDriver(port, app, script.name());
                        var report = new SelfTestRunner(driver, out, environment).run(script);
                        System.out.println("SELFTEST DONE " + (report.ok() ? "OK" : "FAIL"));
                    } catch (Exception e) { e.printStackTrace(); }
                    finally { SwingUtilities.invokeLater(() -> { if (!port.exited) port.exit(ExitKind.CLEAN, 0); }); }
                }, "swing-core-selftest"); runner.start();
            }
        });
    }
}
