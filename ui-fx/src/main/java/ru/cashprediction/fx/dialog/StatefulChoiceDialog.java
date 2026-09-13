package ru.cashprediction.fx.dialog;

import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import ru.cashprediction.core.session.WindowType;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;

/**
 * Восстанавливаемый выбор из списка ({@code WindowType.CHOICE}): валюта, открытие плана из CashMemory.
 *
 * <p>Поле {@code value} — ключ выбранного элемента. Внутренний выпадающий список {@link ChoiceDialog}
 * недоступен снаружи, поэтому изменения отслеживаются через {@code selectedItemProperty()}.</p>
 *
 * <p>Только FX Application Thread.</p>
 *
 * @param <T> тип элементов списка
 */
// JavaFX: ChoiceDialog<T> → Swing: JOptionPane.showInputDialog(..., selectionValues[], initial) → Web: <dialog> с <select>
public final class StatefulChoiceDialog<T> extends ChoiceDialog<T> implements FxRestorableDialog {

    private final DialogStateSupport support;

    /**
     * Создаёт диалог выбора.
     *
     * @param purpose       назначение для снимка: {@code currency} или {@code openPlan}
     * @param title         заголовок окна
     * @param header        крупный текст
     * @param label         подпись списка
     * @param defaultChoice выбранный изначально элемент
     * @param choices       элементы
     * @param okButton      кнопка подтверждения (роль OK_DONE)
     * @param toKey         элемент → ключ для снимка
     * @param fromKey       ключ → элемент ({@code null}, если такого элемента нет)
     */
    public StatefulChoiceDialog(String purpose, String title, String header, String label, T defaultChoice,
                                Collection<T> choices, ButtonType okButton, Function<T, String> toKey,
                                Function<String, T> fromKey) {
        super(defaultChoice, choices);
        Objects.requireNonNull(toKey, "toKey");
        Objects.requireNonNull(fromKey, "fromKey");
        setTitle(title);
        setHeaderText(header);
        setContentText(label);
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        getDialogPane().getButtonTypes().setAll(okButton, AppButtonTypes.CANCEL);
        // JavaFX: DialogPane → Swing: SwingDialogPane (JPanel header/content/кнопки) → Web: <dialog><form method="dialog">
        getDialogPane().setMinWidth(460);
        support = new DialogStateSupport(this, WindowType.CHOICE, () -> { });
        support.putContext(WindowType.CONTEXT_PURPOSE, purpose);
        support.binder().bindCustom("value", selectedItemProperty(),
                () -> getSelectedItem() == null ? "" : toKey.apply(getSelectedItem()),
                value -> {
                    T item = fromKey.apply(value);
                    if (item != null && getItems().contains(item)) {
                        setSelectedItem(item);
                    }
                });
        support.activate();
    }

    /** {@inheritDoc} */
    @Override
    public DialogStateSupport stateSupport() {
        return support;
    }
}
