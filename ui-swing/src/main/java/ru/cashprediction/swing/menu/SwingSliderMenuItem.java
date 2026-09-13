package ru.cashprediction.swing.menu;

import java.awt.BorderLayout;
import java.util.Objects;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;

/**
 * Пункт меню со слайдером «Горизонт плана: 12 мес» — Swing-аналог JavaFX {@code CustomMenuItem} со {@code Slider}.
 *
 * <p>Это обычная панель ({@code JPanel}: подпись + {@code JSlider}), добавленная в меню через
 * {@code JMenu.add(Component)}. Панель не является {@code MenuElement}, поэтому щелчки и перетаскивание внутри неё
 * не закрывают меню — ровно как {@code hideOnClick=false} у {@code CustomMenuItem}. Подпись меняется во время
 * перетаскивания, а команда вызывается один раз — когда пользователь отпустил ползунок.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: CustomMenuItem (Slider, hideOnClick=false) → Swing: SwingSliderMenuItem = JPanel(JLabel+JSlider) в JMenu → Web: <li class="custom"><input type="range">
public final class SwingSliderMenuItem extends JPanel {

    private final JLabel label = new JLabel();
    private final JSlider slider;
    private final IntFunction<String> caption;
    /** Программная установка значения: не команда пользователя. */
    private boolean quiet;

    /**
     * Создаёт пункт.
     *
     * @param min      минимум слайдера
     * @param max      максимум слайдера
     * @param caption  значение → подпись («Горизонт плана: 12 мес»)
     * @param onCommit команда с окончательным значением (после отпускания ползунка)
     */
    public SwingSliderMenuItem(int min, int max, IntFunction<String> caption, IntConsumer onCommit) {
        super(new BorderLayout(0, 2));
        this.caption = Objects.requireNonNull(caption, "caption");
        slider = new JSlider(min, max, min);
        slider.setMajorTickSpacing(12);
        slider.setPaintTicks(true);
        slider.setSnapToTicks(false);
        slider.setOpaque(false);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(4, 28, 6, 12));
        add(label, BorderLayout.NORTH);
        add(slider, BorderLayout.CENTER);
        label.setText(caption.apply(min));
        slider.addChangeListener(e -> {
            label.setText(caption.apply(slider.getValue()));
            // Пока ползунок тянут, план не меняется: иначе каждый пиксель стал бы отдельным шагом «Отменить».
            if (!quiet && !slider.getValueIsAdjusting()) {
                onCommit.accept(slider.getValue());
            }
        });
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        slider.setToolTipText("Горизонт плана в месяцах: сколько месяцев вперёд считать прогноз (меняет план)");
    }

    /**
     * Устанавливает значение без вызова команды (синхронизация с планом).
     *
     * @param value значение; ограничивается границами слайдера
     */
    public void setValueQuietly(int value) {
        quiet = true;
        try {
            slider.setValue(Math.max(slider.getMinimum(), Math.min(slider.getMaximum(), value)));
            label.setText(caption.apply(value));
        } finally {
            quiet = false;
        }
    }

    /**
     * Слайдер (для подсказок и проверки).
     *
     * @return слайдер
     */
    public JSlider slider() {
        return slider;
    }
}
