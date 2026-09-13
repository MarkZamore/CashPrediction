package ru.cashprediction.fx.dialog;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import ru.cashprediction.core.session.WindowType;

/**
 * Восстанавливаемое подтверждение ({@code WindowType.ALERT}): «Удалить правило?», «Актуализировать план?».
 *
 * <p>Полей у такого окна нет: после сбоя оно пересоздаётся тем же методом фасада по контексту
 * {@code purpose} и {@code targetId}, поэтому текст получается тот же, что был на экране.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
// JavaFX: Alert → Swing: JOptionPane.showConfirmDialog/showOptionDialog → Web: <dialog class="alert">
public final class StatefulAlert extends Alert implements FxRestorableDialog {

    private final DialogStateSupport support;

    /**
     * Создаёт подтверждение.
     *
     * @param alertType вид сообщения (обычно CONFIRMATION)
     * @param purpose   назначение для снимка: {@code deleteRule}, {@code deleteOneTime}, {@code actualize}, ...
     * @param targetId  объект плана ({@code r3}, {@code t1}) или {@code null}
     * @param title     заголовок окна
     * @param header    крупный текст
     * @param content   пояснение
     * @param buttons   кнопки (роли задают порядок и кнопку по умолчанию)
     */
    public StatefulAlert(AlertType alertType, String purpose, String targetId, String title, String header,
                         String content, ButtonType... buttons) {
        super(alertType, content, buttons);
        setTitle(title);
        setHeaderText(header);
        // JavaFX: DialogPane → Swing: SwingDialogPane (JPanel header/content/кнопки) → Web: <dialog><form method="dialog">
        getDialogPane().setMinWidth(460);
        support = new DialogStateSupport(this, WindowType.ALERT, () -> { });
        support.putContext(WindowType.CONTEXT_PURPOSE, purpose);
        if (targetId != null && !targetId.isBlank()) {
            support.putContext(WindowType.CONTEXT_TARGET_ID, targetId);
        }
        support.activate();
    }

    /** {@inheritDoc} */
    @Override
    public DialogStateSupport stateSupport() {
        return support;
    }
}
