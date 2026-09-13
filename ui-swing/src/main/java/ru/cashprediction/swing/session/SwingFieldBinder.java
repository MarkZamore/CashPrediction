package ru.cashprediction.swing.session;

import java.awt.event.ItemEvent;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;
import ru.cashprediction.swing.dialog.DateField;
import ru.cashprediction.swing.dialog.MoneyField;

/**
 * Связывает компоненты формы с идентификаторами полей снимка сессии ({@code WindowState.fields}).
 *
 * <p>Для каждого поля известны способ прочитать каноническое строковое значение и способ его восстановить.
 * Канонические формы одинаковы в трёх клиентах: суммы — {@code Money.formatPlain()} («95000,00»), даты — ISO
 * («2026-10-05», пустая строка для пустой даты), флажки — {@code true/false}, выбор из перечисления — имя
 * константы ({@code INCOME}), спиннер — число, текст — как набран. Недописанная сумма или дата хранится
 * дословно, чтобы восстановиться «как была набрана».</p>
 *
 * <p>На каждый компонент вешается слушатель Swing ({@code DocumentListener} для текста, {@code ChangeListener}
 * для спиннера, {@code ItemListener} для флажков, переключателей и списков): любое изменение вызывает
 * общий обработчик — диалог перепроверяет форму и вызывает {@code SessionRecorder.touch()}. Во время
 * {@link #apply(Map)} обработчик не вызывается: восстановление значений — не пользовательский ввод.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
public final class SwingFieldBinder {

    /** Способ чтения и записи одного поля. */
    private record Binding(Supplier<String> getter, Consumer<String> setter) {
    }

    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Runnable onChange;
    private boolean applying;

    /**
     * Создаёт связыватель.
     *
     * @param onChange действие при любом пользовательском изменении поля
     */
    public SwingFieldBinder(Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    // ------------------------------------------------------------------ привязки

    /**
     * Текстовое поле или многострочная область: значение — текст как набран.
     *
     * @param id        идентификатор поля
     * @param component компонент
     */
    public void bindText(String id, JTextComponent component) {
        listenText(component);
        put(id, component::getText, component::setText);
    }

    /**
     * Поле суммы: {@code formatPlain} или текст как набран.
     *
     * @param id    идентификатор поля
     * @param field поле суммы
     */
    public void bindMoney(String id, MoneyField field) {
        listenText(field);
        put(id, field::canonicalValue, field::applyCanonical);
    }

    /**
     * Поле даты: ISO, пустая строка или текст как набран.
     *
     * @param id    идентификатор поля
     * @param field поле даты
     */
    public void bindDate(String id, DateField field) {
        field.addChangeListener(this::changed);
        put(id, field::canonicalValue, field::applyCanonical);
    }

    /**
     * Числовой спиннер: значение — целое число; при восстановлении ограничивается границами модели.
     *
     * @param id      идентификатор поля
     * @param spinner спиннер с {@link SpinnerNumberModel}
     */
    public void bindSpinner(String id, JSpinner spinner) {
        spinner.addChangeListener(e -> changed());
        put(id, () -> String.valueOf(((Number) spinner.getValue()).intValue()), value -> {
            try {
                int number = Integer.parseInt(value.strip());
                if (spinner.getModel() instanceof SpinnerNumberModel model) {
                    int min = ((Number) model.getMinimum()).intValue();
                    int max = ((Number) model.getMaximum()).intValue();
                    number = Math.max(min, Math.min(max, number));
                }
                spinner.setValue(number);
            } catch (NumberFormatException e) {
                // Нечисловое значение в снимке (ручная правка XML): оставляем значение по умолчанию.
            }
        });
    }

    /**
     * Флажок или пункт-переключатель: {@code true}/{@code false}.
     *
     * @param id     идентификатор поля
     * @param button флажок
     */
    public void bindCheckBox(String id, AbstractButton button) {
        button.addItemListener(e -> changed());
        put(id, () -> Boolean.toString(button.isSelected()), value -> button.setSelected(Boolean.parseBoolean(value.strip())));
    }

    /**
     * Группа переключателей ({@code JRadioButton} в {@code ButtonGroup}): значение — ключ выбранного.
     *
     * @param id      идентификатор поля
     * @param buttons ключ → переключатель (порядок сохраняется)
     */
    public void bindRadioGroup(String id, Map<String, ? extends AbstractButton> buttons) {
        Map<String, AbstractButton> copy = Collections.unmodifiableMap(new LinkedHashMap<>(buttons));
        copy.values().forEach(button -> button.addItemListener(e -> {
            // ItemListener срабатывает и на снятие выбора с прежнего переключателя: реагируем только на выбор.
            if (e.getStateChange() == ItemEvent.SELECTED) {
                changed();
            }
        }));
        put(id, () -> copy.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                        .map(Map.Entry::getKey).findFirst().orElse(""),
                value -> {
                    AbstractButton button = copy.get(value.strip());
                    if (button != null) {
                        button.setSelected(true);
                    }
                });
    }

    /**
     * Нередактируемый выпадающий список: значение — ключ выбранного элемента.
     *
     * @param id      идентификатор поля
     * @param combo   список
     * @param toKey   элемент → ключ ({@code Enum::name} для перечислений)
     * @param fromKey ключ → элемент или {@code null}, если ключ неизвестен
     * @param <T>     тип элементов
     */
    public <T> void bindCombo(String id, JComboBox<T> combo, Function<T, String> toKey, Function<String, T> fromKey) {
        combo.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                changed();
            }
        });
        put(id, () -> {
            T selected = combo.getItemAt(combo.getSelectedIndex());
            return selected == null ? "" : toKey.apply(selected);
        }, value -> {
            T item = fromKey.apply(value.strip());
            if (item != null) {
                combo.setSelectedItem(item);
            }
        });
    }

    /**
     * Список значений перечисления: значение — имя константы.
     *
     * @param id    идентификатор поля
     * @param combo список
     * @param type  класс перечисления
     * @param <E>   тип перечисления
     */
    public <E extends Enum<E>> void bindEnumCombo(String id, JComboBox<E> combo, Class<E> type) {
        bindCombo(id, combo, Enum::name, key -> {
            for (E constant : type.getEnumConstants()) {
                if (constant.name().equals(key)) {
                    return constant;
                }
            }
            return null;
        });
    }

    /**
     * Редактируемый выпадающий список строк (категория, валюта): значение — текст редактора как набран.
     *
     * @param id    идентификатор поля
     * @param combo редактируемый список
     */
    public void bindEditableCombo(String id, JComboBox<String> combo) {
        JTextComponent editor = (JTextComponent) combo.getEditor().getEditorComponent();
        listenText(editor);
        put(id, editor::getText, value -> {
            combo.setSelectedItem(value);
            editor.setText(value);
        });
    }

    /**
     * Произвольная привязка для составных полей (например, «месяц-день» из двух компонентов).
     * Слушателей изменений вызывающий вешает сам и вызывает {@link #changed()}.
     *
     * @param id     идентификатор поля
     * @param getter чтение канонического значения
     * @param setter восстановление значения
     */
    public void bindCustom(String id, Supplier<String> getter, Consumer<String> setter) {
        put(id, getter, setter);
    }

    // ------------------------------------------------------------------ снимок

    /**
     * Снимает значения всех полей в порядке привязки.
     *
     * @return идентификатор поля → каноническое значение
     */
    public Map<String, String> capture() {
        Map<String, String> result = new LinkedHashMap<>();
        bindings.forEach((id, binding) -> result.put(id, Objects.requireNonNullElse(binding.getter().get(), "")));
        return result;
    }

    /**
     * Восстанавливает значения полей из снимка. Отсутствующие в снимке поля не меняются;
     * неизвестные идентификаторы игнорируются. Обработчик изменений при этом не вызывается.
     *
     * @param fields идентификатор поля → значение
     */
    public void apply(Map<String, String> fields) {
        applying = true;
        try {
            // Порядок привязки важен: сначала «тип повтора», затем зависящие от него поля.
            bindings.forEach((id, binding) -> {
                String value = fields.get(id);
                if (value != null) {
                    binding.setter().accept(value);
                }
            });
        } finally {
            applying = false;
        }
    }

    /**
     * Заполняет поля так, как если бы их ввёл пользователь: в отличие от {@link #apply(Map)} слушатели
     * срабатывают полностью — форма перепроверяется, рекордер получает {@code touch()}, зависимые поля
     * (например, «С» для повтора «каждые N дней») подставляются. Используется самотестом.
     *
     * @param fields идентификатор поля → значение в канонической форме
     * @return идентификаторы, которых нет среди привязанных полей (пустой список, если все известны)
     */
    public java.util.List<String> fill(Map<String, String> fields) {
        java.util.List<String> unknown = new java.util.ArrayList<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            Binding binding = bindings.get(entry.getKey());
            if (binding == null) {
                unknown.add(entry.getKey());
            } else {
                binding.setter().accept(entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return unknown;
    }

    /**
     * Идёт ли сейчас восстановление значений (слушатели с побочными эффектами должны его пропускать).
     *
     * @return {@code true} внутри {@link #apply(Map)}
     */
    public boolean isApplying() {
        return applying;
    }

    /**
     * Сообщает об изменении поля: вызывает общий обработчик, если это не восстановление.
     */
    public void changed() {
        if (!applying) {
            onChange.run();
        }
    }

    private void put(String id, Supplier<String> getter, Consumer<String> setter) {
        bindings.put(Objects.requireNonNull(id, "id"), new Binding(getter, setter));
    }

    private void listenText(JTextComponent component) {
        component.getDocument().addDocumentListener(new DocumentListener() {
            /** Символы вставлены в поле: обрабатывается как любое изменение текста. */
            @Override
            public void insertUpdate(DocumentEvent e) {
                changed();
            }

            /** Символы удалены из поля: обрабатывается как любое изменение текста. */
            @Override
            public void removeUpdate(DocumentEvent e) {
                changed();
            }

            /** Изменились атрибуты текста (у простых полей не приходит): обрабатывается единообразно. */
            @Override
            public void changedUpdate(DocumentEvent e) {
                changed();
            }
        });
    }
}
