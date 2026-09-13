package ru.cashprediction.fx;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import ru.cashprediction.fx.session.FxCrashHooks;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Точка входа JavaFX-клиента CashPrediction.
 *
 * <p>{@link #main(String[])} до запуска JavaFX приглушает журнал {@code java.util.prefs} (при недоступном реестре
 * JDK печатает предупреждения в консоль) и ставит обработчик необработанных исключений по умолчанию.
 * {@link #start(Stage)} ставит такой же обработчик на FX Application Thread, отключает неявное завершение JavaFX
 * при закрытии последнего окна и передаёт управление {@link AppController}. Любая ошибка инициализации
 * показывается окном ошибки, после чего процесс завершается.</p>
 *
 * <p>Режим самотеста (для разработчиков и автоматической проверки) включается системным свойством
 * {@code cashprediction.selftest}, см. {@link FxSelfTest}.</p>
 */
public final class FxMain extends Application {

    /**
     * Сильная ссылка на журнал {@code java.util.prefs}: {@code LogManager} держит журналы слабыми ссылками,
     * и без неё настроенный уровень мог бы потеряться при сборке мусора.
     */
    private static final Logger PREFS_LOGGER = Logger.getLogger("java.util.prefs");

    private AppController controller;

    /** Создаётся JavaFX через {@code Application.launch}. */
    public FxMain() {
    }

    /**
     * Запускает приложение.
     *
     * @param args аргументы командной строки (не используются)
     */
    public static void main(String[] args) {
        PREFS_LOGGER.setLevel(Level.SEVERE);
        // До запуска JavaFX: исключение при старте тоже должно пройти через сохранение сессии и окно ошибки.
        FxCrashHooks.installDefault();
        launch(FxMain.class, args);
        // Сюда управление приходит только после корректного выхода (Platform.exit): аварийные пути завершают
        // процесс через Runtime.halt. Явный exit не даёт зависшим сторонним потокам задержать завершение.
        System.exit(0);
    }

    /**
     * Инициализирует приложение в FX Application Thread.
     *
     * @param stage главная сцена
     */
    @Override
    public void start(Stage stage) {
        FxCrashHooks.installOnCurrentThread();
        // Диалог восстановления показывается до главного окна: его закрытие не должно завершать JavaFX.
        Platform.setImplicitExit(false);
        try {
            controller = new AppController(stage, FxSelfTest.fromSystemProperties());
            controller.start();
        } catch (Exception | LinkageError e) {
            FxCrashHooks.fatalStartup("Не удалось запустить CashPrediction", e);
        }
    }

    /**
     * Контроллер запущенного приложения.
     *
     * @return контроллер или {@code null} до {@link #start(Stage)}
     */
    public AppController controller() {
        return controller;
    }
}
