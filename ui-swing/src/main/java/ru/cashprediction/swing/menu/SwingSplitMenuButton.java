package ru.cashprediction.swing.menu;

import java.awt.BorderLayout;
import java.awt.Insets;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/**
 * Кнопка «Добавить доход» со списком других команд — Swing-аналог JavaFX {@code SplitMenuButton}.
 *
 * <p>Панель из двух кнопок: основная выполняет команду по умолчанию (новый регулярный доход), узкая «▾» показывает
 * {@link JPopupMenu} под всей составной кнопкой (расход, разовая операция, корректировка события).</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: SplitMenuButton → Swing: SwingSplitMenuButton = JPanel(JButton + JButton("▾") с JPopupMenu) → Web: две кнопки + меню
public final class SwingSplitMenuButton extends JPanel {

    private final JButton mainButton;
    private final JButton arrowButton = new JButton("▾");
    // JavaFX: ContextMenu (список SplitMenuButton) → Swing: JPopupMenu → Web: <ul role="menu">
    private final JPopupMenu popup = new JPopupMenu();

    /**
     * Создаёт кнопку.
     *
     * @param text         текст основной кнопки
     * @param tooltip      подсказка основной кнопки
     * @param arrowTooltip подсказка кнопки списка
     * @param action       команда основной кнопки
     */
    public SwingSplitMenuButton(String text, String tooltip, String arrowTooltip, Runnable action) {
        super(new BorderLayout(0, 0));
        setOpaque(false);
        mainButton = new JButton(text);
        mainButton.setFocusable(false);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        mainButton.setToolTipText(tooltip);
        mainButton.addActionListener(e -> action.run());
        arrowButton.setFocusable(false);
        arrowButton.setMargin(new Insets(mainButton.getMargin().top, 3, mainButton.getMargin().bottom, 3));
        arrowButton.setToolTipText(arrowTooltip);
        arrowButton.addActionListener(e -> popup.show(this, 0, getHeight()));
        add(mainButton, BorderLayout.CENTER);
        add(arrowButton, BorderLayout.EAST);
    }

    /**
     * Список дополнительных команд.
     *
     * @return меню
     */
    public JPopupMenu popup() {
        return popup;
    }

    /**
     * Основная кнопка.
     *
     * @return кнопка
     */
    public JButton mainButton() {
        return mainButton;
    }

    /**
     * Не растягивается по ширине панели инструментов: {@code JToolBar} раздаёт свободное место компонентам без
     * ограничения максимального размера.
     *
     * @return предпочтительный размер
     */
    @Override
    public java.awt.Dimension getMaximumSize() {
        return getPreferredSize();
    }

    /**
     * Включает или отключает обе части составной кнопки: основную и «▾».
     *
     * @param enabled {@code true} — кнопка доступна
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mainButton.setEnabled(enabled);
        arrowButton.setEnabled(enabled);
    }
}
