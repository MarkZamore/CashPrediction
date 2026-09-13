package ru.cashprediction.fx.menu;

import javafx.scene.control.MenuButton;
import javafx.scene.control.Tooltip;
import ru.cashprediction.core.document.ViewState;

/**
 * Кнопка-меню «Период ▾» на панели инструментов.
 *
 * <p>Список кнопки — те же {@code RadioMenuItem}, что и в меню «Вид» (общая {@code ToggleGroup}, пункты
 * переносятся между меню через {@link SharedMenuItems}). Подпись кнопки показывает выбранный период.
 * Период — только вид: он ограничивает таблицу и график, но не меняет план.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
// JavaFX: MenuButton → Swing: SwingMenuButton (JButton + JPopupMenu.show(btn, 0, h)) → Web: <button> + <ul role="menu">
public final class PeriodMenuButton extends MenuButton {

    /**
     * Создаёт кнопку и подключает к ней общие пункты периода.
     *
     * @param controls элементы управления видом
     */
    public PeriodMenuButton(ViewControls controls) {
        setText("Период");
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        setTooltip(new Tooltip("Какой отрезок плана показывать в таблице и на графике (горизонт плана не меняется)"));
        controls.periodShared().attach(this);
    }

    /**
     * Обновляет подпись по виду.
     *
     * @param view текущий вид
     */
    public void update(ViewState view) {
        setText("Период: " + view.period().label());
    }
}
