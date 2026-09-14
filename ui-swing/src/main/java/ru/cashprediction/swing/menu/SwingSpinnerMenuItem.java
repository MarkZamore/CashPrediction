package ru.cashprediction.swing.menu;

import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * Пункт меню со спиннером «Откладывать доп.: [ 5 000 ] ₽/мес» — Swing-аналог JavaFX {@code CustomMenuItem} со
 * {@code Spinner}.
 *
 * <p>Как и {@link SwingSliderMenuItem}, это панель, добавленная в меню компонентом: щелчки по стрелкам и ввод
 * числа не закрывают меню. Модель спиннера общая для копий пункта (меню «Инструменты → Что-если» и кнопка
 * «Что-если ▾»), поэтому обе копии всегда показывают одно значение; команду вызывает слушатель общей модели.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: CustomMenuItem (Spinner, hideOnClick=false) → Swing: SwingSpinnerMenuItem = JPanel(JLabel+JSpinner) в JMenu → Web: <li class="custom"><input type="number">
public final class SwingSpinnerMenuItem extends JPanel {

    private final JSpinner spinner;

    /**
     * Создаёт пункт.
     *
     * @param caption подпись перед спиннером
     * @param model   общая модель (рубли в месяц)
     * @param suffix  подпись после спиннера
     */
    public SwingSpinnerMenuItem(String caption, SpinnerNumberModel model, String suffix) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 2));
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(2, 22, 2, 8));
        spinner = new JSpinner(model);
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "#,##0");
        editor.getTextField().setColumns(8);
        spinner.setEditor(editor);
        add(new JLabel(caption));
        add(spinner);
        add(new JLabel(suffix));
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        spinner.setToolTipText("Что-если: сколько дополнительно откладывать в конце каждого месяца (0 - выключено)");
    }

    /**
     * Спиннер пункта.
     *
     * @return спиннер
     */
    public JSpinner spinner() {
        return spinner;
    }
}
