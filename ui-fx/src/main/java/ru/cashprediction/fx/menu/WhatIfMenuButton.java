package ru.cashprediction.fx.menu;

import javafx.scene.control.MenuButton;
import javafx.scene.control.Tooltip;
import ru.cashprediction.core.document.ViewState;

/**
 * Кнопка-меню «Что-если ▾» на панели инструментов.
 *
 * <p>Список — те же пункты, что и в «Инструменты → Что-если» (флажки «Доходы −10 %», «Расходы +10 %»,
 * {@code CustomMenuItem} со {@code Spinner} дополнительной экономии, «Применить к плану…», «Сбросить»).
 * Когда режим включён, подпись кнопки об этом напоминает: прогноз на экране гипотетический.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
// JavaFX: MenuButton → Swing: SwingMenuButton (JButton + JPopupMenu) → Web: <button> + <ul role="menu">
public final class WhatIfMenuButton extends MenuButton {

    /**
     * Создаёт кнопку и подключает к ней общие пункты «что-если».
     *
     * @param controls элементы управления видом
     */
    public WhatIfMenuButton(ViewControls controls) {
        setText("Что-если");
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        setTooltip(new Tooltip("Посмотреть прогноз при других доходах, расходах или дополнительной экономии, не меняя план"));
        controls.whatIfShared().attach(this);
    }

    /**
     * Обновляет подпись по виду.
     *
     * @param view текущий вид
     */
    public void update(ViewState view) {
        boolean active = !view.whatIf().isNone();
        setText(active ? "Что-если: включено" : "Что-если");
        // Класс оформления, а не inline-стиль: inline-стиль кнопки наследовался бы пунктами её всплывающего меню.
        getStyleClass().remove("what-if-active");
        if (active) {
            getStyleClass().add("what-if-active");
        }
    }
}
