package ru.cashprediction.swing.menu;

import javax.swing.JButton;
import javax.swing.JPopupMenu;

/**
 * Кнопка панели инструментов с выпадающим меню («Период ▾», «Что-если ▾») — Swing-аналог JavaFX {@code MenuButton}.
 *
 * <p>Обычная {@code JButton}; щелчок показывает {@link JPopupMenu} сразу под кнопкой
 * ({@code popup.show(button, 0, button.getHeight())}). Пункты меню добавляет тот, кто создал кнопку.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: MenuButton → Swing: SwingMenuButton = JButton + JPopupMenu.show(btn, 0, h) → Web: <button> + <ul role="menu">
public final class SwingMenuButton extends JButton {

    // JavaFX: ContextMenu (меню MenuButton) → Swing: JPopupMenu → Web: <ul role="menu">
    private final JPopupMenu popup = new JPopupMenu();

    /**
     * Создаёт кнопку.
     *
     * @param text    текст без стрелки (стрелка «▾» добавляется сама)
     * @param tooltip подсказка
     */
    public SwingMenuButton(String text, String tooltip) {
        super(text + " ▾");
        setFocusable(false);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        setToolTipText(tooltip);
        addActionListener(e -> {
            if (popup.isVisible()) {
                popup.setVisible(false);
            } else {
                popup.show(this, 0, getHeight());
            }
        });
    }

    /**
     * Выпадающее меню кнопки.
     *
     * @return меню
     */
    public JPopupMenu popup() {
        return popup;
    }
}
