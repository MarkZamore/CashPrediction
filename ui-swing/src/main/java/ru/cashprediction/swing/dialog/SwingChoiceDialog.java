package ru.cashprediction.swing.dialog;

import java.awt.Window;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.WindowType;

/**
 * Диалог выбора одного значения из списка: план для открытия, валюта. Swing-аналог JavaFX {@code ChoiceDialog<T>}.
 *
 * <p>Штатный Swing-аналог — {@code JOptionPane.showInputDialog(..., selectionValues[], initial)}; он блокирующий,
 * поэтому диалог построен на {@link SwingDialog} с {@link JComboBox}. В снимок сессии попадает поле {@code value} —
 * стабильный ключ выбранного элемента (окно {@link WindowType#CHOICE} с контекстом {@code purpose}).</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 *
 * @param <T> тип элементов
 */
// JavaFX: ChoiceDialog<T> → Swing: SwingChoiceDialog<T> (JComboBox, аналог JOptionPane.showInputDialog с selectionValues) → Web: <dialog> с <select>
public final class SwingChoiceDialog<T> extends SwingDialog<T> {

    /** Идентификатор поля в снимке. */
    public static final String FIELD_VALUE = "value";

    private final JComboBox<T> combo;

    /**
     * Создаёт диалог выбора.
     *
     * @param owner       окно-владелец
     * @param ownerId     идентификатор владельца для снимка
     * @param recorder    рекордер сессии или {@code null}
     * @param purpose     назначение ({@code currency}, {@code openPlan}); {@code null} — окно не восстанавливается
     * @param title       заголовок окна
     * @param header      поясняющий заголовок
     * @param label       подпись списка
     * @param items       элементы
     * @param defaultItem выбранный изначально элемент или {@code null}
     * @param toKey       элемент → ключ для снимка
     * @param toDisplay   элемент → текст в списке
     */
    public SwingChoiceDialog(Window owner, String ownerId, SessionRecorder recorder, String purpose, String title,
                             String header, String label, List<T> items, T defaultItem,
                             Function<T, String> toKey, Function<T, String> toDisplay) {
        super(owner, ownerId, purpose == null ? null : WindowType.CHOICE, true, title, recorder);
        if (purpose != null) {
            putContext(WindowType.CONTEXT_PURPOSE, purpose);
        }
        DefaultComboBoxModel<T> model = new DefaultComboBoxModel<>();
        items.forEach(model::addElement);
        combo = new JComboBox<>(model);
        combo.setRenderer(Combos.renderer(toDisplay));
        if (defaultItem != null) {
            combo.setSelectedItem(defaultItem);
        }
        binder().bindCombo(FIELD_VALUE, combo, toKey, key -> {
            for (T item : items) {
                if (Objects.equals(toKey.apply(item), key)) {
                    return item;
                }
            }
            return null;
        });

        FormPanel form = new FormPanel();
        form.addRow(label, combo);
        pane().setHeaderText(header);
        pane().setContent(form);
        setValidator(() -> selectedItem().isPresent() ? null : "Выберите значение из списка");
        setResultConverter(button -> button.isDefaultButton() ? selectedItem().orElse(null) : null);
        setButtonTypes(SwingButtonType.OK, SwingButtonType.CANCEL);
        setInitialFocus(combo);
    }

    /**
     * Список выбора.
     *
     * @return список
     */
    public JComboBox<T> comboBox() {
        return combo;
    }

    /**
     * Выбранный элемент.
     *
     * @return элемент или пусто
     */
    public Optional<T> selectedItem() {
        return Optional.ofNullable(combo.getItemAt(combo.getSelectedIndex()));
    }
}
