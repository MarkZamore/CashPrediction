package ru.cashprediction.swing.dialog;

import java.awt.Window;
import java.time.LocalDate;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;

/**
 * Диалог 4 «Разовая операция»: дата, название, тип, сумма, категория, заметка.
 *
 * <p>Режим {@code create} или {@code edit} и идентификатор операции хранятся в контексте окна. Результат —
 * {@link OneTimeTransaction}; при создании её идентификатор предварительный, окончательный назначает фасад
 * действий по плану на момент сохранения.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: Dialog<OneTimeTransaction> + DialogPane → Swing: SwingDialog<OneTimeTransaction> + SwingDialogPane → Web: openDialog(id): Promise<R> поверх <dialog>
public final class OneTimeDialog extends SwingDialog<OneTimeTransaction> {

    private final Plan plan;
    private final TxId txId;

    private final DateField date = new DateField();
    private final JTextField title = new JTextField(24);
    private final JComboBox<Kind> kind = Combos.choice(Kind::title, Kind.EXPENSE, Kind.values());
    private final MoneyField amount = new MoneyField();
    private final JComboBox<String> category;
    private final JTextField note = new JTextField(24);

    /**
     * Создаёт диалог.
     *
     * @param owner      окно-владелец
     * @param ownerId    идентификатор владельца для снимка
     * @param recorder   рекордер сессии или {@code null}
     * @param plan       текущий план (категории, горизонт, редактируемая операция)
     * @param existing   редактируемая операция или {@code null} для создания
     * @param presetDate дата для новой операции или {@code null} (тогда сегодня или начало плана)
     * @param presetKind тип для новой операции или {@code null}
     * @param today      сегодняшняя дата
     */
    public OneTimeDialog(Window owner, String ownerId, SessionRecorder recorder, Plan plan, OneTimeTransaction existing,
                         LocalDate presetDate, Kind presetKind, LocalDate today) {
        super(owner, ownerId, WindowType.ONE_TIME_EDITOR, true, "Разовая операция", recorder);
        this.plan = plan;
        this.txId = existing != null ? existing.id() : plan.nextTxId();
        putContext(WindowType.CONTEXT_MODE, existing != null ? WindowType.MODE_EDIT : WindowType.MODE_CREATE);
        putContext(WindowType.CONTEXT_TX_ID, existing != null ? existing.id().value() : "");
        category = Combos.editable(plan.categories(), existing != null ? existing.category() : "");

        if (existing != null) {
            date.setValue(existing.date());
            title.setText(existing.title());
            kind.setSelectedItem(existing.kind());
            amount.setValue(existing.amount());
            note.setText(existing.note());
        } else {
            LocalDate defaultDate = today.isBefore(plan.startDate()) ? plan.startDate() : today;
            date.setValue(presetDate != null ? presetDate : defaultDate);
            if (presetKind != null) {
                kind.setSelectedItem(presetKind);
            }
        }

        FormPanel form = new FormPanel();
        form.addRow("Дата", date);
        form.addRow("Название", title);
        form.addRow("Тип", kind);
        form.addRow("Сумма", amount);
        form.addRow("Категория", category);
        form.addRow("Заметка", note);
        pane().setHeaderText(existing != null
                ? "Изменение разовой операции «" + existing.title() + "»"
                : "Новая разовая операция: премия, покупка, возврат долга…");
        pane().setContent(form);

        binder().bindDate("date", date);
        binder().bindText("title", title);
        binder().bindEnumCombo("kind", kind, Kind.class);
        binder().bindMoney("amount", amount);
        binder().bindEditableCombo("category", category);
        binder().bindText("note", note);

        setValidator(this::formError);
        setWarningSupplier(this::formWarning);
        setResultConverter(button -> button.isDefaultButton() ? build() : null);
        setButtonTypes(SwingButtonType.OK, SwingButtonType.CANCEL);
        setInitialFocus(existing != null ? amount : title);
    }

    private String formError() {
        String error = date.validationError("Дата", true);
        if (error == null && title.getText().isBlank()) {
            error = "Укажите название операции";
        }
        if (error == null) {
            error = amount.validationError("Сумма", true, true);
        }
        return error;
    }

    private String formWarning() {
        return date.value()
                .filter(d -> d.isBefore(plan.startDate()) || d.isAfter(plan.endDate()))
                .map(d -> "Дата вне горизонта прогноза (" + DateFormats.ru(plan.startDate()) + " - "
                        + DateFormats.ru(plan.endDate()) + "): операция не попадёт в прогноз")
                .orElse(null);
    }

    private OneTimeTransaction build() {
        String error = formError();
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        return new OneTimeTransaction(txId, date.value().orElseThrow(), title.getText(), Combos.selected(kind),
                amount.value().orElseThrow(), Combos.editorText(category), note.getText());
    }
}
