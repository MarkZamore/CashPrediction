package ru.cashprediction.fx.session;

import javafx.beans.Observable;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import ru.cashprediction.core.util.DateFormats;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Связывает элементы формы диалога с идентификаторами полей словаря {@code WindowType} и переводит
 * их значения в канонические строки снимка и обратно.
 *
 * <p>Зачем отдельный класс: снимок сессии обязан одинаково описывать поля во всех клиентах. Каждый
 * диалог только объявляет «поле {@code amount} — это вот этот TextField, и это сумма», а правила
 * канонической записи ({@link FieldValues}) и подписка на изменения живут здесь, в одном месте.
 * Аналоги: Swing — {@code SwingFieldBinder} ({@code DocumentListener/ItemListener/ChangeListener}),
 * Web — обработчик события {@code input} с debounce.</p>
 *
 * <p>Любое изменение значения (набор символа, выбор в списке, флажок) вызывает {@code onChange}:
 * диалог перепроверяет форму, а рекордер получает {@code touch()}. Во время {@link #apply(Map)}
 * уведомления подавляются и отправляются один раз в конце.</p>
 *
 * <p>Используется только в FX Application Thread; не потокобезопасен.</p>
 */
public final class FxFieldBinder {

    /** Одна привязка: как снять значение и как его вернуть. */
    private interface Binding {
        /** @return каноническая строка */
        String capture();

        /** @param value каноническая строка из снимка */
        void apply(String value);
    }

    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Runnable onChange;
    private boolean applying;

    /**
     * Создаёт связыватель.
     *
     * @param onChange вызывается после каждого изменения любого привязанного поля
     */
    public FxFieldBinder(Runnable onChange) {
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    // ------------------------------------------------------------------ привязки

    /**
     * Текстовое поле или многострочная заметка: значение — текст как набран.
     *
     * @param id      идентификатор поля
     * @param control поле
     */
    public void bindText(String id, TextInputControl control) {
        control.textProperty().addListener((o, a, b) -> changed());
        put(id, new Binding() {
            /**
             * Снимает значение элемента формы для снимка сессии.
             *
             * @return значение в канонической форме (недопечатанный текст — как набран)
             */
            @Override
            public String capture() {
                return Objects.requireNonNullElse(control.getText(), "");
            }

            /**
             * Ставит значение из снимка в элемент формы; срабатывают слушатели элемента и проверка формы.
             *
             * @param value каноническое значение
             */
            @Override
            public void apply(String value) {
                control.setText(value);
            }
        });
    }

    /**
     * Поле суммы: корректная сумма пишется как {@code 95000,00}, недопечатанная — как набрана.
     *
     * @param id      идентификатор поля
     * @param control поле ввода суммы
     */
    public void bindMoney(String id, TextInputControl control) {
        control.textProperty().addListener((o, a, b) -> changed());
        put(id, new Binding() {
            /**
             * Снимает значение элемента формы для снимка сессии.
             *
             * @return значение в канонической форме (недопечатанный текст — как набран)
             */
            @Override
            public String capture() {
                return FieldValues.canonicalMoney(control.getText());
            }

            /**
             * Ставит значение из снимка в элемент формы; срабатывают слушатели элемента и проверка формы.
             *
             * @param value каноническое значение
             */
            @Override
            public void apply(String value) {
                control.setText(FieldValues.displayMoney(value));
            }
        });
    }

    /**
     * Поле дня года ({@code MM-dd}).
     *
     * @param id      идентификатор поля
     * @param control поле ввода
     */
    public void bindMonthDay(String id, TextInputControl control) {
        control.textProperty().addListener((o, a, b) -> changed());
        put(id, new Binding() {
            /**
             * Снимает значение элемента формы для снимка сессии.
             *
             * @return значение в канонической форме (недопечатанный текст — как набран)
             */
            @Override
            public String capture() {
                return FieldValues.canonicalMonthDay(control.getText());
            }

            /**
             * Ставит значение из снимка в элемент формы; срабатывают слушатели элемента и проверка формы.
             *
             * @param value каноническое значение
             */
            @Override
            public void apply(String value) {
                control.setText(value);
            }
        });
    }

    /**
     * Выбор даты. Значение снимается из текста редактора, а не из {@code value}: DatePicker фиксирует
     * значение только при потере фокуса или Enter, а снимок должен видеть и недопечатанную дату.
     *
     * @param id     идентификатор поля
     * @param picker элемент выбора даты
     */
    public void bindDate(String id, DatePicker picker) {
        picker.valueProperty().addListener((o, a, b) -> changed());
        picker.getEditor().textProperty().addListener((o, a, b) -> changed());
        put(id, new Binding() {
            /**
             * Снимает значение элемента формы для снимка сессии.
             *
             * @return значение в канонической форме (недопечатанный текст — как набран)
             */
            @Override
            public String capture() {
                return FieldValues.canonicalDate(picker.getEditor().getText());
            }

            /**
             * Ставит значение из снимка в элемент формы; срабатывают слушатели элемента и проверка формы.
             *
             * @param value каноническое значение
             */
            @Override
            public void apply(String value) {
                if (value == null || value.isBlank()) {
                    picker.setValue(null);
                    picker.getEditor().setText("");
                    return;
                }
                FieldValues.parseDate(value).ifPresentOrElse(date -> {
                    picker.setValue(date);
                    picker.getEditor().setText(DateFormats.ru(date));
                }, () -> picker.getEditor().setText(value));
            }
        });
    }

    /**
     * Флажок: {@code true/false}.
     *
     * @param id  идентификатор поля
     * @param box флажок
     */
    public void bindCheck(String id, CheckBox box) {
        box.selectedProperty().addListener((o, a, b) -> changed());
        put(id, new Binding() {
            /**
             * Снимает значение элемента формы для снимка сессии.
             *
             * @return значение в канонической форме (недопечатанный текст — как набран)
             */
            @Override
            public String capture() {
                return Boolean.toString(box.isSelected());
            }

            /**
             * Ставит значение из снимка в элемент формы; срабатывают слушатели элемента и проверка формы.
             *
             * @param value каноническое значение
             */
            @Override
            public void apply(String value) {
                box.setSelected(Boolean.parseBoolean(value));
            }
        });
    }

    /**
     * Группа переключателей: значение — строка из {@code userData} выбранного переключателя
     * (например, {@code INCOME}, {@code MONTHS}, {@code ;}).
     *
     * @param id    идентификатор поля
     * @param group группа; у каждого переключателя {@code userData} — ключ-строка
     */
    public void bindToggle(String id, ToggleGroup group) {
        group.selectedToggleProperty().addListener((o, a, b) -> changed());
        put(id, new Binding() {
            /**
             * Снимает значение элемента формы для снимка сессии.
             *
             * @return значение в канонической форме (недопечатанный текст — как набран)
             */
            @Override
            public String capture() {
                Toggle selected = group.getSelectedToggle();
                return selected == null || selected.getUserData() == null ? "" : selected.getUserData().toString();
            }

            /**
             * Ставит значение из снимка в элемент формы; срабатывают слушатели элемента и проверка формы.
             *
             * @param value каноническое значение
             */
            @Override
            public void apply(String value) {
                List<Toggle> toggles = group.getToggles();
                for (Toggle toggle : toggles) {
                    if (toggle.getUserData() != null && toggle.getUserData().toString().equals(value)) {
                        group.selectToggle(toggle);
                        return;
                    }
                }
            }
        });
    }

    /**
     * Нередактируемый выпадающий список: значение — ключ выбранного элемента (имя перечисления).
     *
     * @param id      идентификатор поля
     * @param combo   список
     * @param toKey   элемент → ключ
     * @param fromKey ключ → элемент ({@code null}, если ключ неизвестен)
     * @param <T>     тип элементов
     */
    public <T> void bindCombo(String id, ComboBox<T> combo, Function<T, String> toKey, Function<String, T> fromKey) {
        combo.valueProperty().addListener((o, a, b) -> changed());
        put(id, new Binding() {
            /**
             * Снимает значение элемента формы для снимка сессии.
             *
             * @return значение в канонической форме (недопечатанный текст — как набран)
             */
            @Override
            public String capture() {
                return combo.getValue() == null ? "" : toKey.apply(combo.getValue());
            }

            /**
             * Ставит значение из снимка в элемент формы; срабатывают слушатели элемента и проверка формы.
             *
             * @param value каноническое значение
             */
            @Override
            public void apply(String value) {
                T item = safeFromKey(fromKey, value);
                if (item != null) {
                    combo.setValue(item);
                }
            }
        });
    }

    /**
     * Редактируемый выпадающий список (категория, валюта): значение — текст редактора как набран.
     *
     * @param id    идентификатор поля
     * @param combo редактируемый список строк
     */
    public void bindEditableCombo(String id, ComboBox<String> combo) {
        combo.valueProperty().addListener((o, a, b) -> changed());
        combo.getEditor().textProperty().addListener((o, a, b) -> changed());
        put(id, new Binding() {
            /**
             * Снимает значение элемента формы для снимка сессии.
             *
             * @return значение в канонической форме (недопечатанный текст — как набран)
             */
            @Override
            public String capture() {
                return Objects.requireNonNullElse(combo.getEditor().getText(), "");
            }

            /**
             * Ставит значение из снимка в элемент формы; срабатывают слушатели элемента и проверка формы.
             *
             * @param value каноническое значение
             */
            @Override
            public void apply(String value) {
                combo.setValue(value);
                combo.getEditor().setText(value);
            }
        });
    }

    /**
     * ChoiceBox: значение — ключ выбранного элемента.
     *
     * @param id      идентификатор поля
     * @param box     список выбора
     * @param toKey   элемент → ключ
     * @param fromKey ключ → элемент ({@code null}, если ключ неизвестен)
     * @param <T>     тип элементов
     */
    public <T> void bindChoice(String id, ChoiceBox<T> box, Function<T, String> toKey, Function<String, T> fromKey) {
        box.valueProperty().addListener((o, a, b) -> changed());
        put(id, new Binding() {
            /**
             * Снимает значение элемента формы для снимка сессии.
             *
             * @return значение в канонической форме (недопечатанный текст — как набран)
             */
            @Override
            public String capture() {
                return box.getValue() == null ? "" : toKey.apply(box.getValue());
            }

            /**
             * Ставит значение из снимка в элемент формы; срабатывают слушатели элемента и проверка формы.
             *
             * @param value каноническое значение
             */
            @Override
            public void apply(String value) {
                T item = safeFromKey(fromKey, value);
                if (item != null) {
                    box.setValue(item);
                }
            }
        });
    }

    /**
     * Числовой Spinner: значение — текст редактора (число или недопечатанный текст).
     *
     * @param id      идентификатор поля
     * @param spinner редактируемый Spinner целых чисел
     */
    public void bindSpinner(String id, Spinner<Integer> spinner) {
        spinner.valueProperty().addListener((o, a, b) -> changed());
        spinner.getEditor().textProperty().addListener((o, a, b) -> changed());
        put(id, new Binding() {
            /**
             * Снимает значение элемента формы для снимка сессии.
             *
             * @return значение в канонической форме (недопечатанный текст — как набран)
             */
            @Override
            public String capture() {
                String text = spinner.getEditor().getText();
                return FieldValues.parseInt(text).map(String::valueOf).orElse(Objects.requireNonNullElse(text, ""));
            }

            /**
             * Ставит значение из снимка в элемент формы; срабатывают слушатели элемента и проверка формы.
             *
             * @param value каноническое значение
             */
            @Override
            public void apply(String value) {
                FieldValues.parseInt(value).ifPresentOrElse(number -> {
                    if (spinner.getValueFactory() != null) {
                        // Фабрика значений сама прижмёт число к своим границам min..max.
                        spinner.getValueFactory().setValue(number);
                    }
                    spinner.getEditor().setText(String.valueOf(spinner.getValue()));
                }, () -> spinner.getEditor().setText(Objects.requireNonNullElse(value, "")));
            }
        });
    }

    /**
     * Произвольное значение, для которого нет готовой привязки (например, выбор в {@code ChoiceDialog},
     * чей внутренний ComboBox недоступен снаружи).
     *
     * @param id      идентификатор поля
     * @param source  наблюдаемое свойство, изменение которого означает изменение поля
     * @param capture снимает каноническую строку
     * @param apply   применяет каноническую строку
     */
    public void bindCustom(String id, Observable source, Supplier<String> capture, Consumer<String> apply) {
        source.addListener(o -> changed());
        put(id, new Binding() {
            /**
             * Снимает значение элемента формы для снимка сессии.
             *
             * @return значение в канонической форме (недопечатанный текст — как набран)
             */
            @Override
            public String capture() {
                return Objects.requireNonNullElse(capture.get(), "");
            }

            /**
             * Ставит значение из снимка в элемент формы; срабатывают слушатели элемента и проверка формы.
             *
             * @param value каноническое значение
             */
            @Override
            public void apply(String value) {
                apply.accept(value);
            }
        });
    }

    // ------------------------------------------------------------------ снимок

    /**
     * Снимает значения всех полей в порядке привязки (он же порядок полей на форме).
     *
     * @return неизменяемая карта «идентификатор → каноническая строка»
     */
    public Map<String, String> capture() {
        Map<String, String> result = new LinkedHashMap<>();
        bindings.forEach((id, binding) -> result.put(id, binding.capture()));
        return Collections.unmodifiableMap(result);
    }

    /**
     * Возвращает в форму значения из снимка. Поля, которых нет в карте, не трогаются: снимок, записанный
     * другим клиентом или старой версией, может содержать не все поля.
     *
     * @param values значения полей
     */
    public void apply(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        applying = true;
        try {
            bindings.forEach((id, binding) -> {
                if (values.containsKey(id)) {
                    try {
                        binding.apply(values.get(id));
                    } catch (RuntimeException e) {
                        // Одно испорченное значение не должно помешать восстановить остальные поля окна.
                    }
                }
            });
        } finally {
            applying = false;
        }
        onChange.run();
    }

    /**
     * Проверяет, идёт ли сейчас применение значений из снимка.
     *
     * @return {@code true} внутри {@link #apply(Map)}
     */
    public boolean isApplying() {
        return applying;
    }

    // ------------------------------------------------------------------ служебное

    private void put(String id, Binding binding) {
        if (bindings.putIfAbsent(id, binding) != null) {
            throw new IllegalArgumentException("Поле «" + id + "» уже привязано");
        }
    }

    private void changed() {
        if (!applying) {
            onChange.run();
        }
    }

    private static <T> T safeFromKey(Function<String, T> fromKey, String value) {
        try {
            return value == null || value.isBlank() ? null : fromKey.apply(value);
        } catch (RuntimeException e) {
            // Неизвестный ключ (например, от более новой версии) — оставляем значение формы по умолчанию.
            return null;
        }
    }
}
