package ru.cashprediction.swing.dialog;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.KeyEvent;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.event.ActionEvent;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

/**
 * Поле ввода даты: текст в виде {@code дд.мм.гггг}, кнопка календаря и клавиши «вверх/вниз» (±1 день),
 * PageUp/PageDown (±1 месяц). Swing-замена JavaFX {@code DatePicker}.
 *
 * <p>Основа — {@link JFormattedTextField} без форматтера и с политикой {@code PERSIST}: форматтер
 * (или {@code JSpinner} с {@code SpinnerDateModel}) молча откатывал бы недописанную дату при потере фокуса,
 * а по требованию снимка сессии недописанное значение должно сохраняться и восстанавливаться как набрано.
 * Поэтому текст разбирается только при чтении {@link #value()}; неверный текст — это ошибка формы,
 * а не повод стирать ввод.</p>
 *
 * <p>Канонический вид для снимка ({@link #canonicalValue()}): ISO {@code гггг-мм-дд}, пустая строка для пустого
 * поля и текст «как набран», если дата сейчас некорректна.</p>
 *
 * <p>Компонент используется только в потоке EDT.</p>
 */
public final class DateField extends JPanel {

    private final JFormattedTextField text = new JFormattedTextField();
    private final JButton calendarButton = new JButton("▦");
    private final List<Runnable> changeListeners = new ArrayList<>();

    /**
     * Создаёт пустое поле даты.
     */
    public DateField() {
        super(new BorderLayout(2, 0));
        text.setColumns(9);
        text.setFocusLostBehavior(JFormattedTextField.PERSIST);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        text.setToolTipText("Дата в формате дд.мм.гггг; ↑/↓ - день, PgUp/PgDn - месяц");
        text.getDocument().addDocumentListener(new DocumentListener() {
            /** Символы вставлены в поле: обрабатывается как любое изменение текста. */
            @Override
            public void insertUpdate(DocumentEvent e) {
                fireChanged();
            }

            /** Символы удалены из поля: обрабатывается как любое изменение текста. */
            @Override
            public void removeUpdate(DocumentEvent e) {
                fireChanged();
            }

            /** Изменились атрибуты текста (у простых полей не приходит): обрабатывается единообразно. */
            @Override
            public void changedUpdate(DocumentEvent e) {
                fireChanged();
            }
        });
        bindStep(KeyEvent.VK_UP, 0, 1);
        bindStep(KeyEvent.VK_DOWN, 0, -1);
        bindStep(KeyEvent.VK_PAGE_UP, 1, 0);
        bindStep(KeyEvent.VK_PAGE_DOWN, -1, 0);

        calendarButton.setMargin(new Insets(0, 4, 0, 4));
        calendarButton.setFocusable(false);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        calendarButton.setToolTipText("Выбрать дату в календаре");
        calendarButton.addActionListener(e -> showCalendar());

        add(text, BorderLayout.CENTER);
        add(calendarButton, BorderLayout.EAST);
    }

    private void bindStep(int keyCode, int months, int days) {
        String name = "step" + keyCode;
        text.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), name);
        text.getActionMap().put(name, new AbstractAction() {
            /** Срабатывание действия (кнопка, Enter, клавиша или таймер): выполняет команду этого слушателя. */
            @Override
            public void actionPerformed(ActionEvent e) {
                // Шаг от текущей даты; пустое или неверное поле начинается с сегодняшнего дня.
                LocalDate base = value().orElse(LocalDate.now());
                setValue(base.plusMonths(months).plusDays(days));
            }
        });
    }

    // ------------------------------------------------------------------ значение

    /**
     * Дата из поля.
     *
     * @return дата или пусто, если поле пустое или текст не является датой
     */
    public Optional<LocalDate> value() {
        try {
            return isBlank() ? Optional.empty() : Optional.of(DateFormats.parse(text.getText()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Записывает дату в поле в виде {@code дд.мм.гггг}.
     *
     * @param date дата; {@code null} очищает поле
     */
    public void setValue(LocalDate date) {
        text.setText(DateFormats.ru(date));
    }

    /**
     * Пусто ли поле.
     *
     * @return {@code true}, если текст пустой или из пробелов
     */
    public boolean isBlank() {
        return text.getText().isBlank();
    }

    /**
     * Корректна ли дата (пустое поле некорректной датой не считается).
     *
     * @return {@code true}, если поле пустое или содержит дату
     */
    public boolean isValidOrBlank() {
        return isBlank() || value().isPresent();
    }

    /**
     * Текст ошибки для формы.
     *
     * @param fieldTitle название поля («Дата начала»)
     * @param required   обязательно ли поле
     * @return текст ошибки или {@code null}, если значение допустимо
     */
    public String validationError(String fieldTitle, boolean required) {
        if (isBlank()) {
            return required ? "Укажите поле «" + fieldTitle + "»" : null;
        }
        return value().isPresent() ? null : "Поле «" + fieldTitle + "»: некорректная дата (ожидается дд.мм.гггг)";
    }

    /**
     * Значение для снимка сессии: ISO-дата, пустая строка или текст как набран.
     *
     * @return каноническое значение
     */
    public String canonicalValue() {
        if (isBlank()) {
            return "";
        }
        return value().map(DateFormats::iso).orElse(text.getText());
    }

    /**
     * Восстанавливает значение из снимка: ISO-дата показывается как {@code дд.мм.гггг}, иной текст — как есть.
     *
     * @param canonical значение из снимка
     */
    public void applyCanonical(String canonical) {
        String value = canonical == null ? "" : canonical;
        try {
            text.setText(value.isBlank() ? "" : DateFormats.ru(LocalDate.parse(value.strip())));
        } catch (RuntimeException e) {
            // Недописанная дата из снимка восстанавливается «как была набрана».
            text.setText(value);
        }
    }

    /**
     * Текстовое поле внутри компонента (для подписи {@code JLabel.setLabelFor} и фокуса).
     *
     * @return текстовое поле
     */
    public JFormattedTextField textField() {
        return text;
    }

    /**
     * Добавляет слушателя изменения текста.
     *
     * @param listener действие при каждом изменении
     */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    private void fireChanged() {
        for (Runnable listener : List.copyOf(changeListeners)) {
            listener.run();
        }
    }

    /**
     * Включает или отключает поле целиком: панель, текстовое поле и кнопку календаря.
     *
     * @param enabled {@code true} — поле доступно для ввода
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        text.setEnabled(enabled);
        calendarButton.setEnabled(enabled);
    }

    /**
     * Ставит подсказку на текстовое поле: именно над ним останавливается курсор, а у кнопки календаря своя подсказка.
     *
     * @param tip текст подсказки или {@code null}
     */
    @Override
    public void setToolTipText(String tip) {
        text.setToolTipText(tip);
    }

    // ------------------------------------------------------------------ календарь

    /** Показывает всплывающий календарь месяца под полем. */
    private void showCalendar() {
        // Календарь — просто содержимое всплывающего JPopupMenu (замена выпадающей части DatePicker).
        JPopupMenu popup = new JPopupMenu();
        CalendarPanel panel = new CalendarPanel(value().orElse(LocalDate.now()), date -> {
            setValue(date);
            popup.setVisible(false);
            text.requestFocusInWindow();
        });
        popup.add(panel);
        popup.show(this, 0, getHeight());
    }

    /** Сетка дней месяца с переключением месяцев. */
    private static final class CalendarPanel extends JPanel {
        private final java.util.function.Consumer<LocalDate> onPick;
        private final LocalDate selected;
        private final JLabel monthLabel = new JLabel("", SwingConstants.CENTER);
        private final JPanel grid = new JPanel(new GridLayout(0, 7, 1, 1));
        private YearMonth month;

        CalendarPanel(LocalDate selected, java.util.function.Consumer<LocalDate> onPick) {
            super(new BorderLayout(0, 4));
            this.selected = selected;
            this.onPick = onPick;
            this.month = YearMonth.from(selected);
            setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            JButton prev = navButton("◀", -1);
            JButton next = navButton("▶", 1);
            JPanel header = new JPanel(new BorderLayout());
            header.add(prev, BorderLayout.WEST);
            header.add(monthLabel, BorderLayout.CENTER);
            header.add(next, BorderLayout.EAST);
            add(header, BorderLayout.NORTH);
            add(grid, BorderLayout.CENTER);
            rebuild();
        }

        private JButton navButton(String caption, int delta) {
            JButton button = new JButton(caption);
            button.setMargin(new Insets(0, 6, 0, 6));
            button.setFocusable(false);
            button.addActionListener(e -> {
                month = month.plusMonths(delta);
                rebuild();
            });
            return button;
        }

        private void rebuild() {
            monthLabel.setText(DateFormats.monthTitle(month));
            grid.removeAll();
            for (DayOfWeek day : DayOfWeek.values()) {
                JLabel label = new JLabel(RuText.weekdayShort(day), SwingConstants.CENTER);
                label.setForeground(day.getValue() >= 6 ? new Color(0xB0, 0x1E, 0x1E) : Color.DARK_GRAY);
                grid.add(label);
            }
            int offset = month.atDay(1).getDayOfWeek().getValue() - 1;
            for (int i = 0; i < offset; i++) {
                grid.add(new JLabel());
            }
            for (int d = 1; d <= month.lengthOfMonth(); d++) {
                LocalDate date = month.atDay(d);
                JButton button = new JButton(Integer.toString(d));
                button.setMargin(new Insets(1, 2, 1, 2));
                button.setFocusable(false);
                if (date.equals(selected)) {
                    button.setBackground(new Color(0xCF, 0xE3, 0xFA));
                }
                if (date.equals(LocalDate.now())) {
                    button.setForeground(new Color(0x1A, 0x5F, 0xB4));
                }
                button.addActionListener(e -> onPick.accept(date));
                grid.add(button);
            }
            grid.revalidate();
            grid.repaint();
            // Перестраиваем только само всплывающее меню: окно диалога под ним менять нельзя.
            JPopupMenu popup = (JPopupMenu) javax.swing.SwingUtilities.getAncestorOfClass(JPopupMenu.class, this);
            if (popup != null) {
                popup.pack();
            }
        }
    }
}
