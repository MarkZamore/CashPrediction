package ru.cashprediction.fx.dialog;

import javafx.stage.Window;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/**
 * Восстанавливаемый диалог JavaFX: окно, чьё состояние входит в снимок сессии.
 *
 * <p>Все методы {@link StatefulWindow} реализованы по умолчанию через {@link DialogStateSupport}, поэтому
 * классу-диалогу достаточно вернуть свою поддержку. Реализуют: {@link FxStatefulDialog} (редакторы),
 * {@link StatefulAlert}, {@link StatefulTextInputDialog}, {@link StatefulChoiceDialog}.</p>
 */
public interface FxRestorableDialog extends StatefulWindow {

    /**
     * Поддержка состояния окна.
     *
     * @return поддержка (одна на всё время жизни диалога)
     */
    DialogStateSupport stateSupport();

    /** {@inheritDoc} */
    @Override
    default String windowId() {
        return stateSupport().windowId();
    }

    /** {@inheritDoc} */
    @Override
    default WindowType windowType() {
        return stateSupport().type();
    }

    /** {@inheritDoc} */
    @Override
    default boolean modal() {
        return stateSupport().modal();
    }

    /** {@inheritDoc} */
    @Override
    default String ownerId() {
        return stateSupport().ownerId();
    }

    /** {@inheritDoc} */
    @Override
    default WindowState captureState() {
        return stateSupport().capture();
    }

    /** {@inheritDoc} */
    @Override
    default void applyState(WindowState state) {
        stateSupport().apply(state);
    }

    /**
     * Окно, в котором показан диалог (для назначения владельца вложенным диалогам).
     *
     * @return окно или {@code null}, если диалог не показан
     */
    default Window dialogWindow() {
        return stateSupport().window();
    }
}
