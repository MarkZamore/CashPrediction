package ru.cashprediction.swing.dialog;

import java.awt.Component;
import java.util.List;
import java.util.function.Function;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.text.JTextComponent;

/**
 * Фабрики выпадающих списков, общих для форм: перечисления с русскими подписями, редактируемые списки строк
 * (валюта, категория).
 *
 * <p>Класс без состояния; компоненты создаются в потоке EDT.</p>
 */
public final class Combos {

    /** Валюты, предлагаемые в мастере, параметрах плана и диалоге «Валюта». */
    public static final List<String> CURRENCIES = List.of("₽", "$", "€", "₸", "BYN");

    private Combos() {
    }

    /**
     * Нередактируемый список с подписями элементов.
     *
     * @param values  элементы
     * @param display элемент → подпись
     * @param initial выбранный элемент
     * @param <T>     тип элементов
     * @return список
     */
    @SafeVarargs
    public static <T> JComboBox<T> choice(Function<T, String> display, T initial, T... values) {
        DefaultComboBoxModel<T> model = new DefaultComboBoxModel<>();
        for (T value : values) {
            model.addElement(value);
        }
        JComboBox<T> combo = new JComboBox<>(model);
        combo.setRenderer(renderer(display));
        if (initial != null) {
            combo.setSelectedItem(initial);
        }
        return combo;
    }

    /**
     * Редактируемый список строк: можно выбрать из списка или ввести своё значение.
     *
     * @param items   предлагаемые значения
     * @param initial начальный текст
     * @return список
     */
    public static JComboBox<String> editable(List<String> items, String initial) {
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
        items.forEach(model::addElement);
        JComboBox<String> combo = new JComboBox<>(model);
        combo.setEditable(true);
        combo.setSelectedItem(initial == null ? "" : initial);
        return combo;
    }

    /**
     * Текст редактора редактируемого списка «как набран» (выбранный элемент попадает в редактор тоже).
     *
     * @param combo редактируемый список
     * @return текст без краевых пробелов
     */
    public static String editorText(JComboBox<String> combo) {
        return ((JTextComponent) combo.getEditor().getEditorComponent()).getText().strip();
    }

    /**
     * Выбранный элемент нередактируемого списка.
     *
     * @param combo список
     * @param <T>   тип элементов
     * @return элемент или {@code null}
     */
    public static <T> T selected(JComboBox<T> combo) {
        int index = combo.getSelectedIndex();
        return index < 0 ? null : combo.getItemAt(index);
    }

    /**
     * Отрисовщик элементов через функцию подписи (не требует подходящего {@code toString()}).
     *
     * @param display элемент → подпись
     * @param <T>     тип элементов
     * @return отрисовщик
     */
    public static <T> ListCellRenderer<T> renderer(Function<T, String> display) {
        DefaultListCellRenderer delegate = new DefaultListCellRenderer();
        return (JList<? extends T> list, T value, int index, boolean selected, boolean focus) -> {
            Component c = delegate.getListCellRendererComponent(list, value == null ? "" : display.apply(value),
                    index, selected, focus);
            return c;
        };
    }
}
