package ru.cashprediction.fx.ui;

import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import ru.cashprediction.core.ui.text.UiText;

/** Ранняя ошибка до готовности контроллера: без ложного обещания сохранённой сессии. */
public final class FxStartupErrors {
    private static final AtomicBoolean HANDLING = new AtomicBoolean();

    private FxStartupErrors() { }

    /** Устанавливает временный обработчик; готовый FxApp заменяет его обработчиком ядра. */
    public static void installDefault() {
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> fatal(error));
    }

    /** Сообщает об ошибке и завершает процесс кодом 1 без маркера корректного закрытия. */
    public static void fatal(Throwable error) {
        ru.cashprediction.fx.FxMain.closeUpdates();
        error.printStackTrace(System.err);
        if (!HANDLING.compareAndSet(false, true)) {
            Runtime.getRuntime().halt(1);
            return;
        }
        // До запуска toolkit и после его остановки нельзя надёжно поставить runLater.
        // Вне FX-потока завершаемся сразу, а не оставляем процесс ждать непоказанного окна.
        if (!Platform.isFxApplicationThread()) {
            Runtime.getRuntime().halt(1);
            return;
        }
        try {
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.alert
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setGraphic(FxIcons.view("\u2716", ru.cashprediction.core.ui.token.DesignTokens.ALERT_ICON_SIZE,
                    ru.cashprediction.core.ui.token.ColorToken.EXPENSE));
            alert.setOnShown(e -> FxIcons.application((javafx.stage.Stage) alert.getDialogPane().getScene().getWindow()));
            alert.setTitle(UiText.get("alert.uncaught.title"));
            alert.setHeaderText(UiText.get("s2.startup.errorHeader"));
            alert.setContentText(error.getMessage() == null ? error.getClass().getName() : error.getMessage());
            // JavaFX: ButtonType → Swing: JButton → Web: button.dialog-action
            ButtonType close = new ButtonType(UiText.get("button.close"), ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(close);
            alert.showAndWait();
        } catch (Throwable secondary) {
            secondary.printStackTrace(System.err);
        } finally {
            // Не запускаем shutdown hooks, способные ошибочно пометить частичный запуск чистым.
            Runtime.getRuntime().halt(1);
        }
    }
}
