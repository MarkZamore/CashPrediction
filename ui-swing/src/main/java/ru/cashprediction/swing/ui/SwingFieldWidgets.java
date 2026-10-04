package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.event.*;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.text.JTextComponent;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.*;

/** Закрытый набор полей формы: значения и доступность определяет FormSession. */
public final class SwingFieldWidgets {
    private SwingFieldWidgets() { }

    /** Измеряет полную подпись: 150 px является минимумом, а не пределом с многоточием (§6.0). */
    static JLabel formLabel(String text) {
        JLabel label = new JLabel(text, SwingConstants.RIGHT) {
            /** Сохраняет естественную ширину также после изменения подписи в FormView. */
            @Override public Dimension getPreferredSize() {
                Dimension natural = super.getPreferredSize();
                return new Dimension(Math.max(DesignTokens.FORM_LABEL_MIN_WIDTH, natural.width), DesignTokens.CONTROL_HEIGHT);
            }
        };
        label.setFont(SwingLook.font(FontToken.BASE));
        label.setForeground(SwingLook.color(ColorToken.TEXT_PRIMARY));
        return label;
    }

    /** Привязка одного физического виджета к стабильному id поля. */
    public static final class Binding {
        final FieldSpec spec;
        final JComponent root;
        final JComponent input;
        final JLabel label;
        final List<Option> options = new ArrayList<>();
        final List<AbstractButton> radios = new ArrayList<>();
        private final SwingFormDialog form;
        private boolean applying;
        private String sent;
        private boolean pendingText;
        boolean readOnly;

        private Binding(FieldSpec spec, SwingFormDialog form) {
            this.spec = spec; this.form = form;
            label = formLabel(spec.label().isEmpty() ? "" : spec.label() + ":");
            input = switch (spec.kind()) {
                case CHECK -> new JCheckBox(spec.label());
                case CHOICE, EDITABLE_CHOICE -> {
                    JComboBox<Option> combo = new JComboBox<>(); SwingIcons.arrows(combo); combo.setEditable(spec.kind() == FieldKind.EDITABLE_CHOICE);
                    if (combo.isEditable()) combo.setEditor(new javax.swing.plaf.basic.BasicComboBoxEditor() {
                        /** Показывает текст варианта, а не технический record.toString. */
                        @Override public void setItem(Object item) { super.setItem(item instanceof Option option ? option.text() : item); }
                    });
                    combo.setRenderer(new OptionRenderer(false)); yield combo;
                }
                case LIST, PREVIEW -> { JList<Option> list = new JList<>(); list.setVisibleRowCount(Math.max(2, spec.textRows())); list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); list.setCellRenderer(new OptionRenderer(spec.kind() == FieldKind.PREVIEW)); yield list; }
                case RADIO -> new JPanel();
                case MULTILINE -> { JTextArea text = new JTextArea(Math.max(2, spec.textRows()), Math.max(16, spec.columns())); text.setLineWrap(true); text.setWrapStyleWord(true); yield text; }
                case RESULT_LINES -> new JPanel();
                case BUTTON -> new JButton(spec.label());
                case SPINNER -> spinner(spec);
                default -> { JTextField text = new JTextField(Math.max(8, spec.columns())); if (spec.kind() == FieldKind.MONEY) text.setHorizontalAlignment(SwingConstants.RIGHT); yield text; }
            };
            if (!(input instanceof JTextArea || input instanceof JList<?> || input instanceof JPanel)) {
                input.setPreferredSize(new Dimension(input.getPreferredSize().width, DesignTokens.CONTROL_HEIGHT));
                input.setMinimumSize(new Dimension(0, DesignTokens.CONTROL_HEIGHT));
            }
            SwingLook.id(input, spec.id()); input.putClientProperty("cp.prompt", spec.prompt()); SwingLook.tooltip(input, spec.tooltip());
            JPanel wrapper = new JPanel(new BorderLayout(4, 0)); wrapper.setOpaque(false);
            JComponent control = input instanceof JList<?> || input instanceof JTextArea ? new JScrollPane(input) : input;
            wrapper.add(controlWithWidth(control, spec.widthPx()), BorderLayout.CENTER);
            if (!spec.suffix().isEmpty()) wrapper.add(new JLabel(spec.suffix()), BorderLayout.EAST);
            if (spec.kind() == FieldKind.DATE) {
                JButton calendar = new JButton("▦"); SwingIcons.decorate(calendar); SwingLook.tooltip(calendar, UiText.get("calendar.button.tip"));
                calendar.addActionListener(e -> {
                    LocalDate selected = FieldCodec.parseDate(raw()).orElse(null);
                    // JavaFX: Popup → Swing: SwingCalendarPopup → Web: div календаря
                    new SwingCalendarPopup(form.port.intents(), selected, date -> setAndSend(FieldCodec.display(FieldKind.DATE, date.toString()), true))
                            .show(calendar, selected == null ? YearMonth.from(form.port.environment.clock().today()) : YearMonth.from(selected));
                }); wrapper.add(calendar, BorderLayout.EAST);
            }
            root = wrapper; root.putClientProperty("cp.field", this);
            options(spec.options()); listen();
        }

        /** Возвращает фактический текст или значение выбора виджета. */
        public String raw() {
            return switch (input) {
                case JSpinner spinner -> ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().getText();
                case JTextComponent text -> text.getText();
                case JCheckBox check -> Boolean.toString(check.isSelected());
                case JComboBox<?> combo -> { Object v = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem(); yield v instanceof Option option ? option.value() : v == null ? "" : v.toString(); }
                case JList<?> list -> list.getSelectedValue() instanceof Option option ? option.value() : "";
                default -> {
                    ButtonGroup group = form.radioGroups.get(spec.id());
                    yield group != null && group.getSelection() != null ? group.getSelection().getActionCommand() : "";
                }
            };
        }

        /** Возвращает видимый пользователю текст выбора для дампа. */
        public String displayed() {
            // Предпросмотр не имеет вводимого значения: его строки и индекс выбора дамп читает отдельно.
            if (spec.kind() == FieldKind.PREVIEW) return "";
            Object choice = input instanceof JComboBox<?> combo ? combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem()
                    : input instanceof JList<?> list ? list.getSelectedValue() : null;
            if (choice instanceof Option option) return option.text();
            if (spec.kind() == FieldKind.RADIO) return radios.stream().filter(AbstractButton::isSelected).map(AbstractButton::getText).findFirst().orElse("");
            return raw();
        }

        /** Возвращает физические варианты общей группы, в том числе расположенные в другой строке. */
        List<AbstractButton> radioPeers() {
            ButtonGroup group = form.radioGroups.get(spec.id());
            return group == null ? List.of() : java.util.Collections.list(group.getElements());
        }

        /** Заполняет реальный виджет, затем передаёт обычное событие ядру. */
        public void setAndSend(String value, boolean committed) { set(value); send(committed); }

        /** Завершает текущую правку той же привязкой, что и потеря фокуса, снимая отложенное событие документа. */
        void commit() { send(true); }

        void apply(FieldView view) {
            applying = true;
            root.setVisible(view.visible()); label.setVisible(view.visible());
            readOnly = view.readOnly(); enable(root, view.enabled());
            if (input instanceof JTextComponent text) text.setEditable(!view.readOnly());
            if (view.label() != null) {
                label.setText(view.label().isEmpty() ? "" : view.label() + ":");
                if (input instanceof JCheckBox check) check.setText(view.label());
            }
            if (view.options() != null && !options.equals(view.options())) options(view.options());
            if (input instanceof JSpinner spinner && spinner.getModel() instanceof SpinnerNumberModel range) {
                // Изменение границ тоже уведомляет NumberEditor: возвращаем исходный текст до применения эха ядра.
                JTextField editor = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
                String raw = editor.getText();
                range.setMinimum(view.min() == null ? spec.min() : view.min()); range.setMaximum(view.max() == null ? spec.max() : view.max());
                if (!editor.getText().equals(raw)) editor.setText(raw);
            }
            if (view.tooltip() != null) SwingLook.tooltip(input, view.tooltip());
            if (view.value() != null && !pendingText && (sent == null || raw().equals(sent))) {
                set(view.value());
                sent = raw();
            }
            applying = false;
        }

        private void set(String value) {
            boolean before = applying; applying = true;
            switch (input) {
                case JTextComponent text -> { if (!text.getText().equals(value)) text.setText(value); }
                case JCheckBox check -> check.setSelected(Boolean.parseBoolean(value));
                case JComboBox<?> combo -> {
                    Option selected = options.stream().filter(o -> o.value().equals(value) || o.text().equals(value)).findFirst().orElse(null);
                    combo.setSelectedItem(selected == null && combo.isEditable() ? value : selected);
                }
                case JList<?> list -> {
                    int index = -1; for (int i = 0; i < options.size(); i++) if (options.get(i).value().equals(value) || options.get(i).text().equals(value)) index = i;
                    list.setSelectedIndex(index);
                }
                case JSpinner spinner -> {
                    JTextField editor = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
                    FieldCodec.parseLong(value).ifPresent(spinner::setValue);
                    editor.setText(value);
                }
                default -> radios.forEach(b -> b.setSelected(value.equals(b.getClientProperty("cp.value")) || value.equals(b.getText())));
            }
            applying = before;
        }

        @SuppressWarnings("unchecked")
        private void options(List<Option> next) {
            boolean before = applying; applying = true;
            options.clear(); options.addAll(next);
            if (input instanceof JComboBox<?> combo) ((JComboBox<Option>) combo).setModel(new DefaultComboBoxModel<>(next.toArray(Option[]::new)));
            if (input instanceof JList<?> list) ((JList<Option>) list).setListData(next.toArray(Option[]::new));
            if (spec.kind() == FieldKind.RADIO) {
                JPanel panel = (JPanel) input; panel.removeAll();
                ButtonGroup group = form.radioGroups.computeIfAbsent(spec.id(), unused -> new ButtonGroup());
                radios.forEach(group::remove); radios.clear();
                panel.setLayout(new BoxLayout(panel, spec.orientation() == Orientation.VERTICAL ? BoxLayout.Y_AXIS : BoxLayout.X_AXIS)); panel.setOpaque(false);
                for (Option option : next) {
                    JRadioButton button = new JRadioButton(option.text()); button.putClientProperty("cp.value", option.value()); button.setOpaque(false);
                    button.setPreferredSize(new Dimension(button.getPreferredSize().width, DesignTokens.CONTROL_HEIGHT));
                    button.setActionCommand(option.value());
                    group.add(button); radios.add(button); panel.add(button); button.addActionListener(e -> send(true));
                }
            }
            applying = before;
        }

        private void listen() {
            JTextComponent text = input instanceof JTextComponent t ? t : input instanceof JSpinner s ? ((JSpinner.DefaultEditor) s.getEditor()).getTextField()
                    : input instanceof JComboBox<?> c && c.isEditable() ? (JTextComponent) c.getEditor().getEditorComponent() : null;
            if (text != null) {
                text.getDocument().addDocumentListener(new DocumentListener() {
                    /** Передаёт вставку без изменения документа в его слушателе. */
                    @Override public void insertUpdate(DocumentEvent e) { later(); }
                    /** Передаёт удаление без изменения документа в его слушателе. */
                    @Override public void removeUpdate(DocumentEvent e) { later(); }
                    /** Передаёт изменение атрибутов. */
                    @Override public void changedUpdate(DocumentEvent e) { later(); }
                    private void later() {
                        if (applying) return;
                        // Эхо другого поля не должно затирать ещё не переданную правку документа.
                        if (pendingText) return;
                        pendingText = true;
                        SwingUtilities.invokeLater(() -> {
                            if (pendingText && !form.closed && !applying) send(false);
                        });
                    }
                });
                text.addFocusListener(new FocusAdapter() {
                    /** Форматирует значение при потере фокуса средствами ядра. */
                    @Override public void focusLost(FocusEvent e) { send(true); }
                });
                if (text instanceof JTextField field) field.addActionListener(e -> { send(true); form.session.fieldSubmitted(spec.id()); });
            }
            if (input instanceof JCheckBox check) check.addActionListener(e -> send(true));
            if (input instanceof JComboBox<?> combo) combo.addActionListener(e -> send(true));
            if (input instanceof JSpinner spinner) spinner.addChangeListener(e -> {
                if (applying || form.applying || form.closed) return;
                // Слушатели модели вызываются в обратном порядке: редактор должен обновиться до отправки стрелки ядру.
                boolean before = applying; applying = true;
                try { ((JSpinner.DefaultEditor) spinner.getEditor()).stateChanged(e); }
                finally { applying = before; }
                send(true);
            });
            if (input instanceof JButton button) button.addActionListener(e -> form.session.buttonPressed(spec.id()));
            if (input instanceof JList<?> list) {
                list.addListSelectionListener(e -> { if (!applying && !e.getValueIsAdjusting()) { if (spec.kind() == FieldKind.PREVIEW) form.session.previewSelected(list.getSelectedIndex(), false); else send(true); } });
                list.addMouseListener(new MouseAdapter() {
                    /** Активирует физически выбранный элемент списка. */
                    @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) activate(); }
                    /** Передаёт контекстное меню предпросмотра через общий порт. */
                    @Override public void mousePressed(MouseEvent e) { popup(e); }
                    /** Передаёт платформенный триггер при отпускании. */
                    @Override public void mouseReleased(MouseEvent e) { popup(e); }
                    private void popup(MouseEvent e) {
                        // JavaFX: ContextMenuEvent → Swing: MouseEvent.isPopupTrigger → Web: contextmenu
                        if (e.isPopupTrigger() && spec.kind() == FieldKind.PREVIEW) {
                            int index = list.locationToIndex(e.getPoint()); list.setSelectedIndex(index);
                            form.port.context(new ru.cashprediction.core.ui.menu.ContextTarget.Preview(form.session.windowId(), index), list, e.getX(), e.getY());
                        }
                    }
                });
            }
        }

        void activate() {
            if (input instanceof JList<?> list) {
                if (spec.kind() == FieldKind.PREVIEW) form.session.previewSelected(list.getSelectedIndex(), true);
                else form.session.fieldActivated(spec.id(), list.getSelectedIndex());
            }
        }

        void preview(List<PreviewItem> items) {
            if (spec.kind() != FieldKind.PREVIEW || !(input instanceof JList<?>)) return;
            List<Option> next = items.stream().map(p -> Option.of(p.text(), p.text())).toList();
            if (!options.equals(next)) options(next);
        }

        private void send(boolean committed) {
            if (applying || form.closed || form.applying) return;
            pendingText = false;
            sent = raw(); form.session.fieldChanged(spec.id(), sent, committed, ++form.clientRev);
        }
    }

    /** Создаёт виджет для описанного поля. */
    public static Binding create(FieldSpec spec, SwingFormDialog form) { return new Binding(spec, form); }

    /** Создаёт числовое поле, сохраняя некорректный текст при потере фокуса для валидации и снимка ядра. */
    static JSpinner spinner(FieldSpec spec) {
        // Явные Long выбирают объектный конструктор: примитивный long иначе расширяется до double,
        // и NumberEditor меняет тип числа при commit, вызывая лишнее эхо старого значения.
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(Long.valueOf(spec.min()),
                Long.valueOf(spec.min()), Long.valueOf(spec.max()), Long.valueOf(Math.max(1, spec.step()))));
        SwingIcons.arrows(spinner);
        JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "0");
        // COMMIT сохраняет ошибочный ввод, а корректное число по-прежнему попадает в модель и работает со стрелками.
        editor.getTextField().setFocusLostBehavior(JFormattedTextField.COMMIT);
        spinner.setEditor(editor);
        return spinner;
    }

    /** Ограничивает настоящий контрол общей пиксельной шириной, не растягивая его вместе с колонкой формы. */
    static JComponent controlWithWidth(JComponent control, int widthPx) {
        if (widthPx == 0) return control;
        JPanel holder = new JPanel(new BorderLayout()) {
            /** Сохраняет высоту настоящего содержимого и ширину из описания поля. */
            @Override public Dimension getPreferredSize() {
                return new Dimension(widthPx, control.getPreferredSize().height);
            }
            /** Передаёт контролу фактическую фиксированную ширину и доступную высоту строки. */
            @Override public void doLayout() {
                control.setBounds(0, 0, widthPx, getHeight());
            }
        };
        holder.setOpaque(false); holder.add(control, BorderLayout.CENTER);
        return holder;
    }

    private static void enable(Component component, boolean enabled) {
        component.setEnabled(enabled); if (component instanceof Container container) for (Component child : container.getComponents()) enable(child, enabled);
    }

    /** Рендерер выбора не показывает техническое значение Option.value. */
    static final class OptionRenderer extends DefaultListCellRenderer {
        private final boolean preview;
        /** Только системный предпросмотр дат содержит декоративные символы; названия вариантов остаются текстом. */
        OptionRenderer(boolean preview) { this.preview = preview; SwingIcons.decorate(this); }
        /** Показывает локализованный текст варианта. */
        @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focused) {
            Option option = value instanceof Option o ? o : null;
            super.getListCellRendererComponent(list, option == null ? value : option.text(), index, selected, focused);
            putClientProperty("cp.decorative", preview);
            if (option != null && option.bold()) setFont(getFont().deriveFont(Font.BOLD));
            // JavaFX: Tooltip → Swing: JToolTip → Web: div.tooltip
            SwingLook.tooltip(this, option == null ? "" : option.detail()); return this;
        }
    }
}
