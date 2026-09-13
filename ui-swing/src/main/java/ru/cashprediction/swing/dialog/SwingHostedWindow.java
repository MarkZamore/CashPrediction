package ru.cashprediction.swing.dialog;

import java.awt.Window;
import ru.cashprediction.core.session.StatefulWindow;

/**
 * Окно, которое умеет показывать {@link SwingDialogHost}: типизированный диалог ({@link SwingDialog})
 * или сообщение ({@link SwingAlert}).
 *
 * <p>Хост показывает окно через {@code SwingUtilities.invokeLater}, регистрирует его в рекордере сессии
 * в {@code windowOpened} и снимает с регистрации в {@code windowClosed}. Окна без типа
 * ({@link #windowType()} {@code == null}) не восстанавливаются и в снимок не попадают: это, например,
 * «О программе» или диалог восстановления, который показывается до главного окна.</p>
 *
 * <p>Все методы вызываются только в потоке EDT.</p>
 */
public interface SwingHostedWindow extends StatefulWindow {

    /**
     * Окно Swing, которое будет показано.
     *
     * @return диалог
     */
    Window window();

    /**
     * Назначает идентификатор окна сеанса ({@code w1}, {@code w2}, ...), если его ещё нет.
     * Восстановленное окно получает идентификатор из снимка через {@link #applyState}.
     *
     * @param id идентификатор от {@code SessionRecorder.nextWindowId()}
     */
    void assignWindowId(String id);

    /**
     * Попадает ли окно в снимок сессии.
     *
     * @return {@code true}, если у окна есть {@link #windowType()}
     */
    default boolean isRestorable() {
        return windowType() != null;
    }

    /**
     * Готовит окно к показу: размер по содержимому и положение относительно владельца,
     * если границы не были восстановлены из снимка. Вызывается хостом непосредственно перед
     * {@code setVisible(true)}.
     */
    void prepareForShow();
}
