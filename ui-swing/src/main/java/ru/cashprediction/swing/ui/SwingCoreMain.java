package ru.cashprediction.swing.ui;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import javax.swing.SwingUtilities;
import javax.swing.JOptionPane;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.selftest.*;

/** Запуск единственного Swing-интерфейса общего ядра. */
public final class SwingCoreMain {
    private SwingCoreMain() { }
    private static volatile SwingUpdateSession updates;

    /** Запускает контроллер в EDT и настоящий драйвер самотеста в отдельном потоке. */
    public static void launch(LaunchOptions options) {
        launch(options, options.toArguments().toArray(String[]::new));
    }

    /** Сохраняет исходные служебные аргументы для проверки общим lifecycle. */
    public static void launch(LaunchOptions options, String[] args) {
        AppEnvironment environment = AppEnvironment.from(options);
        SwingUpdateSession session = SwingUpdateSession.open(environment, args);
        updates = session;
        if (!session.beforeUi()) return;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(session::close, "cp-swing-update-close"));
            if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException(UiText.get("s2.startup.errorHeader"));
            SwingUtilities.invokeLater(() -> {
                final SwingUiPort port;
                final AppController app;
                try {
                    SwingLook.install();
                    port = new SwingUiPort(environment);
                    port.updateCallbacks(session::afterUiReady, session::close);
                    app = new AppController(port, environment); port.bind(app);
                } catch (RuntimeException | LinkageError failure) {
                    startupFailed(failure);
                    return;
                }
                try {
                    Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> port.executor().execute(() -> app.uncaught(thread, failure)));
                    installShutdownHook(app);
                    app.start();
                }
                catch (RuntimeException | LinkageError failure) {
                    session.close();
                    startupFailed(failure);
                    return;
                }
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
        } catch (RuntimeException | LinkageError failure) { session.close(); throw failure; }
    }
    /** Сохраняет только последний захваченный снимок при внешнем выходе JVM, не закрывая сеанс чисто. */
    static void installShutdownHook(AppController app) {
        Runtime.getRuntime().addShutdownHook(new Thread(app::saveShutdownSnapshot, "cashprediction-swing-shutdown"));
    }

    /** Показывает раннюю ошибку без обещания сохранить ещё не созданную сессию и завершает процесс. */
    public static void startupFailed(Throwable failure) {
        if (updates != null) updates.close();
        failure.printStackTrace(System.err);
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                Runnable show = () -> {
                    String title = UiText.get("s2.startup.errorHeader");
                    // JavaFX: Alert → Swing: JOptionPane → Web: диалог ошибки.
                    JOptionPane.showMessageDialog(null, title + "\n" + failure, title, JOptionPane.ERROR_MESSAGE);
                };
                if (SwingUtilities.isEventDispatchThread()) show.run();
                else SwingUtilities.invokeAndWait(show);
            }
        } catch (Exception | LinkageError reportingFailure) {
            reportingFailure.printStackTrace(System.err);
        } finally {
            // Ранний сбой не является чистым выходом: чужие shutdown hooks не должны изменить маркер сессии.
            Runtime.getRuntime().halt(2);
        }
    }
}
