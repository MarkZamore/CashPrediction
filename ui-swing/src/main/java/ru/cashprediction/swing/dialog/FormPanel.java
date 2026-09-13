package ru.cashprediction.swing.dialog;

import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSeparator;
import javax.swing.JSpinner;

/**
 * Форма диалога «подпись — поле» на {@link GridBagLayout}: единый вид форм всех диалогов Swing-клиента.
 *
 * <p>Левая колонка — подписи, правая — поля, растягивающиеся по ширине. Строка может содержать несколько
 * компонентов подряд ({@link #addRow(String, JComponent...)}), заголовок раздела ({@link #addSection})
 * или компонент на всю ширину ({@link #addWide}).</p>
 *
 * <p>Компонент используется только в потоке EDT.</p>
 */
public final class FormPanel extends JPanel {

    private int row;

    /**
     * Создаёт пустую форму.
     */
    public FormPanel() {
        super(new GridBagLayout());
    }

    /**
     * Добавляет строку «подпись — поле».
     *
     * @param label подпись (без двоеточия)
     * @param field поле
     * @return созданная подпись (чтобы менять её текст или видимость вместе с полем)
     */
    public JLabel addRow(String label, JComponent field) {
        JLabel caption = new JLabel(label + ":");
        caption.setLabelFor(field instanceof DateField date ? date.textField() : field);
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.anchor = GridBagConstraints.LINE_START;
        left.insets = new Insets(3, 0, 3, 10);
        add(caption, left);

        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        right.anchor = GridBagConstraints.LINE_START;
        // Спиннеры и короткие поля не растягиваем: широкий спиннер для числа 1..31 выглядит странно.
        right.fill = field instanceof JSpinner ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
        right.insets = new Insets(3, 0, 3, 0);
        add(field, right);
        row++;
        return caption;
    }

    /**
     * Добавляет строку с несколькими компонентами подряд.
     *
     * @param label      подпись
     * @param components компоненты слева направо
     * @return созданная подпись
     */
    public JLabel addRow(String label, JComponent... components) {
        return addRow(label, inline(components));
    }

    /**
     * Панель с компонентами в строку без внешних отступов.
     *
     * @param components компоненты
     * @return панель
     */
    public static JPanel inline(JComponent... components) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(0, -6, 0, 0));
        for (JComponent component : components) {
            panel.add(component);
        }
        return panel;
    }

    /**
     * Добавляет заголовок раздела с разделительной линией.
     *
     * @param title текст заголовка
     */
    public void addSection(String title) {
        JLabel caption = new JLabel(title);
        caption.setFont(caption.getFont().deriveFont(Font.BOLD));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(row == 1 ? 0 : 10, 0, 2, 0);
        add(caption, c);
        GridBagConstraints line = new GridBagConstraints();
        line.gridx = 0;
        line.gridy = row++;
        line.gridwidth = 2;
        line.fill = GridBagConstraints.HORIZONTAL;
        line.insets = new Insets(0, 0, 4, 0);
        add(new JSeparator(), line);
    }

    /**
     * Добавляет компонент на всю ширину формы.
     *
     * @param component компонент
     * @param growVertically растягивать ли по высоте (списки, многострочные заметки)
     */
    public void addWide(JComponent component, boolean growVertically) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row++;
        c.gridwidth = 2;
        c.weightx = 1;
        c.weighty = growVertically ? 1 : 0;
        c.fill = growVertically ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 0, 3, 0);
        add(component, c);
    }
}
