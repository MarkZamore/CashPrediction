package ru.cashprediction.fx.session;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.image.Image;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.fx.dialog.AppDialogPane;
import ru.cashprediction.fx.dialog.Dialogs;
import ru.cashprediction.fx.dialog.FxDialogHost;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Обработка необработанных исключений: сохранить сессию, сообщить пользователю, завершить процесс.
 *
 * <p>Порядок (раздел 5.2 плана): {@code recorder.saveNow()} → {@code Alert(ERROR)} «Непредвиденная ошибка. Сессия
 * сохранена, при следующем запуске её можно восстановить.» со стеком в раскрываемой области →
 * {@code Runtime.getRuntime().halt(2)}. Именно {@code halt}, а не {@code System.exit}: выход не должен ставить маркер
 * «корректно закрыто» и не должен запускать shutdown hook, иначе при следующем запуске не появится диалог восстановления.</p>
 *
 * <p>Обработчик ставится дважды: {@link #installDefault()} в {@code main()} до запуска JavaFX (исключения любых
 * потоков, в том числе при старте) и {@link #installOnCurrentThread()} в {@code Application.start()} для самого
 * FX Application Thread. Повторное исключение, пока окно ошибки уже показано, только печатается в консоль.</p>
 *
 * <p>Окна ошибки показываются здесь напрямую, а не через {@link FxDialogHost}: хост может быть ещё не создан
 * (ошибка при запуске) или находиться в неисправном состоянии (ошибка внутри него самого).</p>
 *
 * <p>Потокобезопасен: состояние — volatile-ссылки и атомарный флаг.</p>
 */
public final class FxCrashHooks {

    /** Код завершения после необработанного исключения. */
    public static final int HALT_UNCAUGHT = 2;
    /** Заголовок окна ошибки: одинаков во всех клиентах. */
    public static final String MESSAGE = "Непредвиденная ошибка. Сессия сохранена, при следующем запуске её можно восстановить.";

    private static volatile SessionRecorder recorder;
    private static volatile boolean autoClose;
    private static final AtomicBoolean HANDLING = new AtomicBoolean();

    private FxCrashHooks() {
    }

    /** Ставит обработчик по умолчанию для всех потоков (вызывается в {@code main()} до {@code Application.launch}). */
    public static void installDefault() {
        Thread.setDefaultUncaughtExceptionHandler(FxCrashHooks::handle);
    }

    /** Ставит обработчик на текущий поток (вызывается в FX Application Thread из {@code start()}). */
    public static void installOnCurrentThread() {
        Thread.currentThread().setUncaughtExceptionHandler(FxCrashHooks::handle);
    }

    /**
     * Подключает рекордер текущего сеанса.
     *
     * @param sessionRecorder рекордер
     * @param selfTest        режим самотеста: окно ошибки закрывается само через 4 с, чтобы процесс завершился без человека
     */
    public static void attach(SessionRecorder sessionRecorder, boolean selfTest) {
        recorder = sessionRecorder;
        autoClose = selfTest;
    }

    /**
     * Обрабатывает необработанное исключение.
     *
     * @param thread поток, в котором оно возникло
     * @param error  исключение
     */
    public static void handle(Thread thread, Throwable error) {
        System.err.println("Необработанное исключение в потоке " + thread.getName() + ":");
        error.printStackTrace();
        if (!HANDLING.compareAndSet(false, true)) {
            // Окно ошибки уже показано (или процесс уже завершается): второе окно только запутало бы пользователя.
            return;
        }
        SessionRecorder current = recorder;
        if (current != null) {
            // saveNow никогда не бросает; из фонового потока он пишет последний снятый в UI-потоке снимок.
            current.saveNow();
        }
        if (Platform.isFxApplicationThread()) {
            showAndHalt(error);
            return;
        }
        try {
            Platform.runLater(() -> showAndHalt(error));
        } catch (IllegalStateException toolkitNotRunning) {
            // JavaFX ещё не запущен или уже остановлен: показать окно нечем.
            Runtime.getRuntime().halt(HALT_UNCAUGHT);
        }
    }

    /**
     * Ошибка при запуске программы (например, нельзя создать CashMemory): окно ошибки, затем завершение.
     *
     * @param header что не получилось
     * @param error  исключение
     */
    public static void fatalStartup(String header, Throwable error) {
        error.printStackTrace();
        try {
            // JavaFX: Alert(ERROR) + expandableContent → Swing: JOptionPane.showMessageDialog(ERROR_MESSAGE) → Web: <dialog class="alert error">
            Alert alert = Dialogs.error(header, error);
            alert.initModality(Modality.APPLICATION_MODAL);
            alert.setOnHidden(e -> Runtime.getRuntime().halt(1));
            alert.show();
            decorate(alert);
            closeLaterInSelfTest(alert);
        } catch (RuntimeException e) {
            Runtime.getRuntime().halt(1);
        }
    }

    private static void showAndHalt(Throwable error) {
        try {
            // JavaFX: Alert(ERROR) + expandableContent → Swing: JOptionPane.showMessageDialog(ERROR_MESSAGE) + JTextArea → Web: <dialog class="alert error">
            Alert alert = new Alert(AlertType.ERROR);
            alert.setTitle("CashPrediction - ошибка");
            alert.setHeaderText(MESSAGE);
            alert.setContentText(Objects.requireNonNullElse(error.getMessage(), error.getClass().getName()));
            // JavaFX: DialogPane → Swing: SwingDialogPane (JPanel header/content/кнопки) → Web: <dialog><form method="dialog">
            alert.getDialogPane().setExpandableContent(AppDialogPane.detailsArea(Dialogs.stackTrace(error)));
            alert.getDialogPane().setMinWidth(560);
            alert.initModality(Modality.APPLICATION_MODAL);
            alert.setOnHidden(e -> Runtime.getRuntime().halt(HALT_UNCAUGHT));
            alert.show();
            decorate(alert);
            closeLaterInSelfTest(alert);
        } catch (Throwable secondary) {
            // Даже окно не показалось: сессия уже сохранена, завершаемся.
            Runtime.getRuntime().halt(HALT_UNCAUGHT);
        }
    }

    /**
     * Доводит показанное окно ошибки до вида остальных окон: русская ссылка «Подробности» вместо «Show Details»
     * и значок приложения в заголовке (без него Windows показывает безликий значок по умолчанию).
     *
     * @param alert показанное окно ошибки
     */
    private static void decorate(Alert alert) {
        Dialogs.localizeDetailsButton(alert.getDialogPane());
        Image icon = FxDialogHost.appIcon();
        if (icon != null && alert.getDialogPane().getScene() != null
                && alert.getDialogPane().getScene().getWindow() instanceof Stage stage && stage.getIcons().isEmpty()) {
            stage.getIcons().add(icon);
        }
    }

    private static void closeLaterInSelfTest(Alert alert) {
        if (!autoClose) {
            return;
        }
        // 4 с: достаточно, чтобы проверяющий скрипт успел снять окно ошибки на снимок экрана.
        PauseTransition pause = new PauseTransition(Duration.millis(4000));
        pause.setOnFinished(e -> alert.close());
        pause.play();
    }
}
