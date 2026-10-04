package ru.cashprediction.fx;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import ru.cashprediction.fx.ui.FxStartupErrors;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Точка входа JavaFX-клиента CashPrediction.
 *
 * <p>{@link #main(String[])} до запуска JavaFX приглушает журнал {@code java.util.prefs} (при недоступном реестре
 * JDK печатает предупреждения в консоль) и ставит обработчик необработанных исключений по умолчанию.
 * {@link #start(Stage)} ставит такой же обработчик на FX Application Thread, отключает неявное завершение JavaFX
 * при закрытии последнего окна и передаёт управление общему контроллеру ядра. Любая ошибка инициализации
 * показывается окном ошибки, после чего процесс завершается.</p>
 *
 * <p>Режим самотеста (для разработчиков и автоматической проверки) включается системным свойством
 * {@code cashprediction.selftest} или аргументом {@code --selftest}.</p>
 */
public final class FxMain extends Application {

    /**
     * Сильная ссылка на журнал {@code java.util.prefs}: {@code LogManager} держит журналы слабыми ссылками,
     * и без неё настроенный уровень мог бы потеряться при сборке мусора.
     */
    private static final Logger PREFS_LOGGER = Logger.getLogger("java.util.prefs");

    /** Код обычного завершения публикуется из потока JavaFX до возврата launch. */
    private static volatile int cleanExitCode;
    private static ru.cashprediction.core.app.AppEnvironment environment;
    private static ru.cashprediction.fx.ui.FxUpdateSession updates;

    /** Освобождает обновлятор до явного выхода или аварийного halt. */
    public static void closeUpdates() { if (updates != null) updates.close(); }

    /** Сохраняет код обычного выхода, включая отложенное завершение самотеста после публикации отчёта. */
    public static void recordCleanExit(int code) {
        cleanExitCode = code;
    }

    /** Завершает процесс после возврата launch с кодом, переданным общим контроллером. */
    static void exitAfterLaunch() {
        closeUpdates();
        System.exit(cleanExitCode);
    }

    /** Создаётся JavaFX через {@code Application.launch}. */
    public FxMain() {
    }

    /**
     * Запускает приложение.
     *
     * @param args аргументы окружения и самотеста общего приложения
     */
    public static void main(String[] args) {
        PREFS_LOGGER.setLevel(Level.SEVERE);
        // До создания контроллера нельзя обещать сохранение ещё не существующей сессии.
        FxStartupErrors.installDefault();
        try {
            var options = ru.cashprediction.core.app.LaunchOptions.parse(args);
            environment = ru.cashprediction.core.app.AppEnvironment.from(options);
            updates = ru.cashprediction.fx.ui.FxUpdateSession.open(environment, args);
            if (!updates.beforeUi()) {
                // LauncherImpl может запустить toolkit до вызова main: простой return
                // оставляет FX-поток и lease живыми, мешая помощнику заменить файлы.
                // UI ещё не создан; запрос перезапуска уже передан общему ядру.
                exitAfterLaunch();
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(FxMain::closeUpdates, "cp-fx-update-close"));
            launch(FxMain.class, args);
        } catch (Exception | LinkageError error) {
            closeUpdates();
            FxStartupErrors.fatal(error);
        }
        // Сюда управление приходит только после корректного выхода (Platform.exit): аварийные пути завершают
        // процесс через Runtime.halt. Явный exit не даёт зависшим сторонним потокам задержать завершение.
        exitAfterLaunch();
    }

    /**
     * Инициализирует приложение в FX Application Thread.
     *
     * @param stage главная сцена
     */
    @Override
    public void start(Stage stage) {
        Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> FxStartupErrors.fatal(error));
        try {
            // Прямой Application.launch также проходит барьер до создания контроллера и окон.
            if (environment == null) {
                var raw = getParameters().getRaw();
                environment = ru.cashprediction.core.app.AppEnvironment.from(
                        ru.cashprediction.core.app.LaunchOptions.parse(raw, System.getProperties()));
                updates = ru.cashprediction.fx.ui.FxUpdateSession.open(environment, raw.toArray(String[]::new));
                if (!updates.beforeUi()) { Platform.exit(); return; }
                Runtime.getRuntime().addShutdownHook(new Thread(FxMain::closeUpdates, "cp-fx-update-close"));
            }
            // Runtime home и предстартовый барьер проверены до конфигурации UI.
            // Диалог восстановления показывается до главного окна: его закрытие не должно завершать JavaFX.
            Platform.setImplicitExit(false);
            ru.cashprediction.fx.ui.FxApp.start(stage, environment, updates);
        } catch (Exception | LinkageError e) {
            closeUpdates();
            FxStartupErrors.fatal(e);
        }
    }
}
