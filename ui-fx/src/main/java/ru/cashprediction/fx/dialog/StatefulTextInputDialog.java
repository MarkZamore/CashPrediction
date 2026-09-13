package ru.cashprediction.fx.dialog;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.VBox;
import ru.cashprediction.core.session.WindowType;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Восстанавливаемый ввод одной строки ({@code WindowType.TEXT_INPUT}): переименование плана, сверка баланса,
 * другая валюта, свой горизонт в месяцах.
 *
 * <p>Поле {@code value} — текст как набран (сумма сверки — в канонической форме {@code 95000,00}, если
 * корректна). Кнопка подтверждения блокируется, пока валидатор возвращает ошибку; ошибка показывается под полем.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
// JavaFX: TextInputDialog → Swing: JOptionPane.showInputDialog(parent, msg, title, QUESTION_MESSAGE, null, null, initial) → Web: <dialog> с <input>
public final class StatefulTextInputDialog extends TextInputDialog implements FxRestorableDialog {

    private final DialogStateSupport support;
    private final Function<String, Optional<String>> validator;
    private final BooleanProperty valid = new SimpleBooleanProperty(true);
    private final Label problem = new Label();

    /**
     * Создаёт диалог ввода.
     *
     * @param purpose      назначение для снимка: {@code rename}, {@code reconcile}, {@code customMonths}, {@code customCurrency}
     * @param title        заголовок окна
     * @param header       крупный текст с пояснением
     * @param label        подпись поля
     * @param initialValue начальный текст
     * @param okButton     кнопка подтверждения (роль OK_DONE)
     * @param validator    текст → ошибка по-русски или пусто, если значение допустимо
     * @param moneyValue   {@code true}, если в поле сумма (тогда в снимок пишется каноническая форма)
     */
    public StatefulTextInputDialog(String purpose, String title, String header, String label, String initialValue,
                                   ButtonType okButton, Function<String, Optional<String>> validator, boolean moneyValue) {
        super(initialValue);
        this.validator = Objects.requireNonNull(validator, "validator");
        setTitle(title);
        setHeaderText(header);
        // Сначала текст подписи: TextInputDialog перестраивает свою сетку при каждом его изменении.
        setContentText(label);
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        getDialogPane().getButtonTypes().setAll(okButton, AppButtonTypes.CANCEL);
        Node grid = getDialogPane().getContent();
        problem.setStyle("-fx-text-fill: #b3261e;");
        problem.setWrapText(true);
        problem.managedProperty().bind(problem.textProperty().isNotEmpty());
        problem.visibleProperty().bind(problem.textProperty().isNotEmpty());
        getDialogPane().setContent(new VBox(8, grid, problem));
        // JavaFX: DialogPane → Swing: SwingDialogPane (JPanel header/content/кнопки) → Web: <dialog><form method="dialog">
        getDialogPane().setMinWidth(460);
        Node ok = getDialogPane().lookupButton(okButton);
        if (ok != null) {
            ok.disableProperty().bind(valid.not());
        }
        getEditor().setPrefColumnCount(24);

        support = new DialogStateSupport(this, WindowType.TEXT_INPUT, this::validateValue);
        support.putContext(WindowType.CONTEXT_PURPOSE, purpose);
        if (moneyValue) {
            support.binder().bindMoney("value", getEditor());
        } else {
            support.binder().bindText("value", getEditor());
        }
        support.activate();
    }

    /** {@inheritDoc} */
    @Override
    public DialogStateSupport stateSupport() {
        return support;
    }

    private void validateValue() {
        Optional<String> error;
        try {
            error = validator.apply(Objects.requireNonNullElse(getEditor().getText(), ""));
        } catch (RuntimeException e) {
            error = Optional.of(Objects.requireNonNullElse(e.getMessage(), "Некорректное значение"));
        }
        valid.set(error.isEmpty());
        problem.setText(error.map(m -> "✖ " + m).orElse(""));
    }
}
