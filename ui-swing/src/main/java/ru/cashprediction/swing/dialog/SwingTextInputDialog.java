package ru.cashprediction.swing.dialog;

import java.awt.Window;
import java.util.function.Function;
import javax.swing.JTextField;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.WindowType;

/**
 * Диалог ввода одной строки: переименование плана, сверка баланса, «другая» валюта, произвольный горизонт.
 * Swing-аналог JavaFX {@code TextInputDialog}.
 *
 * <p>Штатный Swing-аналог — {@code JOptionPane.showInputDialog(parent, msg, title, QUESTION_MESSAGE, null, null,
 * initial)}, но он блокирует вызывающий код и не умеет отключать «ОК» при неверном вводе. Поэтому диалог построен
 * на {@link SwingDialog}: та же неблокирующая модель, проверка ввода на лету и запись поля {@code value} в снимок
 * сессии (окно {@link WindowType#TEXT_INPUT} с контекстом {@code purpose}).</p>
 *
 * <p>Результат — введённый текст без краевых пробелов; для денежного режима — текст суммы.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: TextInputDialog → Swing: SwingTextInputDialog (аналог JOptionPane.showInputDialog) → Web: <dialog> с <input>
public final class SwingTextInputDialog extends SwingDialog<String> {

    /** Идентификатор поля в снимке. */
    public static final String FIELD_VALUE = "value";

    private final JTextField editor;

    /**
     * Создаёт диалог ввода.
     *
     * @param owner        окно-владелец
     * @param ownerId      идентификатор владельца для снимка
     * @param recorder     рекордер сессии или {@code null}
     * @param purpose      назначение ({@code rename}, {@code reconcile}, {@code customMonths}, {@code customCurrency});
     *                     {@code null} — окно не восстанавливается
     * @param money        {@code true} — поле суммы ({@link MoneyField}, в снимке {@code formatPlain})
     * @param title        заголовок окна
     * @param header       поясняющий заголовок
     * @param label        подпись поля
     * @param initialValue начальное значение (для суммы — в любом разбираемом виде)
     * @param validator    текст → сообщение об ошибке или {@code null}
     */
    public SwingTextInputDialog(Window owner, String ownerId, SessionRecorder recorder, String purpose, boolean money,
                                String title, String header, String label, String initialValue,
                                Function<String, String> validator) {
        super(owner, ownerId, purpose == null ? null : WindowType.TEXT_INPUT, true, title, recorder);
        if (purpose != null) {
            putContext(WindowType.CONTEXT_PURPOSE, purpose);
        }
        if (money) {
            MoneyField field = new MoneyField();
            field.applyCanonical(initialValue);
            binder().bindMoney(FIELD_VALUE, field);
            editor = field;
        } else {
            editor = new JTextField(initialValue == null ? "" : initialValue, 24);
            binder().bindText(FIELD_VALUE, editor);
        }
        editor.selectAll();

        FormPanel form = new FormPanel();
        form.addRow(label, editor);
        pane().setHeaderText(header);
        pane().setContent(form);
        setValidator(() -> validator == null ? null : validator.apply(editor.getText()));
        setResultConverter(button -> button.isDefaultButton() ? editor.getText().strip() : null);
        setButtonTypes(SwingButtonType.OK, SwingButtonType.CANCEL);
        setInitialFocus(editor);
    }

    /**
     * Поле ввода (например, чтобы добавить подсказку).
     *
     * @return поле
     */
    public JTextField editor() {
        return editor;
    }
}
