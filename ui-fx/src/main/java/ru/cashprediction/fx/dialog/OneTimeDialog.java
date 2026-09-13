package ru.cashprediction.fx.dialog;

import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Диалог 4 «Разовая операция»: премия, покупка ноутбука, возврат долга — событие одного дня.
 *
 * <p>Дата вне горизонта плана допустима (пользователь может планировать и дальше), но об этом
 * предупреждается: в текущий прогноз такая операция не попадёт.</p>
 *
 * <p>Результат — операция; для новой идентификатор назначает фасад в момент применения.
 * Только FX Application Thread.</p>
 */
// JavaFX: Dialog<R> + DialogPane (AppDialogPane) + ButtonType → Swing: SwingDialog<R> (OneTimeDialog) → Web: openDialog('oneTime')
public final class OneTimeDialog extends FxStatefulDialog<OneTimeTransaction> {

    private final Plan plan;
    private final OneTimeTransaction existing;

    private final DatePicker date;
    private final TextField title = new TextField();
    private final ToggleGroup kindGroup = new ToggleGroup();
    private final RadioButton income = new RadioButton(Kind.INCOME.title());
    private final RadioButton expense = new RadioButton(Kind.EXPENSE.title());
    private final TextField amount;
    private final ComboBox<String> category = new ComboBox<>();
    private final TextArea note = new TextArea();

    /**
     * Создаёт редактор разовой операции.
     *
     * @param plan          текущий план
     * @param existing      изменяемая операция или {@code null} для новой
     * @param dateForCreate дата новой операции
     * @param kindForCreate тип новой операции
     */
    public OneTimeDialog(Plan plan, OneTimeTransaction existing, LocalDate dateForCreate, Kind kindForCreate) {
        super(WindowType.ONE_TIME_EDITOR, new AppDialogPane(existing == null ? "Новая разовая операция"
                : "Изменение разовой операции «" + existing.title() + "»", "≡"));
        this.plan = plan;
        this.existing = existing;
        stateSupport().putContext(WindowType.CONTEXT_MODE, existing == null ? WindowType.MODE_CREATE : WindowType.MODE_EDIT);
        if (existing != null) {
            stateSupport().putContext(WindowType.CONTEXT_TX_ID, existing.id().value());
        }

        date = FxInputs.datePicker(existing == null ? dateForCreate : existing.date());
        title.setText(existing == null ? "" : existing.title());
        title.setPromptText("например, Премия или Ноутбук");
        income.setUserData(Kind.INCOME.name());
        expense.setUserData(Kind.EXPENSE.name());
        income.setToggleGroup(kindGroup);
        expense.setToggleGroup(kindGroup);
        Kind kind = existing == null ? kindForCreate : existing.kind();
        (kind == Kind.INCOME ? income : expense).setSelected(true);
        amount = FxInputs.moneyField(existing == null ? null : existing.amount());
        category.getItems().setAll(plan.categories());
        category.setEditable(true);
        category.setValue(existing == null ? "" : existing.category());
        category.setMaxWidth(Double.MAX_VALUE);
        note.setText(existing == null ? "" : existing.note());
        note.setPrefRowCount(2);
        note.setWrapText(true);

        appPane().setForm(new FormGrid()
                .row("Дата", date)
                .row("Название", title)
                .row("Тип", new HBox(16, income, expense))
                .row("Сумма", amount)
                .row("Категория", category)
                .row("Заметка", note));
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        appPane().getButtonTypes().setAll(AppButtonTypes.SAVE, AppButtonTypes.CANCEL);

        // Порядок привязки = порядок полей словаря WindowType.ONE_TIME_EDITOR.
        binder().bindDate("date", date);
        binder().bindText("title", title);
        binder().bindToggle("kind", kindGroup);
        binder().bindMoney("amount", amount);
        binder().bindEditableCombo("category", category);
        binder().bindText("note", note);

        setResultConverter(button -> button == AppButtonTypes.SAVE
                ? build(new ArrayList<>(), new ArrayList<>()).orElse(null) : null);
        activate();
    }

    /** {@inheritDoc} */
    @Override
    protected void validateForm(List<String> errors, List<String> warnings) {
        build(errors, warnings);
    }

    private Optional<OneTimeTransaction> build(List<String> errors, List<String> warnings) {
        Optional<LocalDate> day = FxInputs.date(date);
        if (day.isEmpty()) {
            errors.add("Укажите дату (ДД.ММ.ГГГГ)");
        }
        if (FxInputs.isBlank(title)) {
            errors.add("Укажите название операции");
        }
        Optional<Money> money = FxInputs.money(amount);
        if (FxInputs.isBlank(amount)) {
            errors.add("Укажите сумму");
        } else if (money.isEmpty()) {
            errors.add("Некорректная сумма: «" + amount.getText().strip() + "»");
        } else if (!money.get().isPositive()) {
            errors.add("Сумма должна быть больше нуля (доход это или расход, задаёт «Тип»)");
        } else if (money.get().compareTo(PlanValidator.MAX_AMOUNT) > 0) {
            errors.add("Сумма слишком большая");
        }
        if (!errors.isEmpty()) {
            return Optional.empty();
        }
        if (day.get().isBefore(plan.startDate()) || day.get().isAfter(plan.endDate())) {
            warnings.add("Дата вне горизонта плана (" + DateFormats.ru(plan.startDate()) + " — "
                    + DateFormats.ru(plan.endDate()) + "): операция не попадёт в прогноз");
        }
        TxId id = existing != null ? existing.id() : plan.nextTxId();
        Kind kind = expense.isSelected() ? Kind.EXPENSE : Kind.INCOME;
        return Optional.of(new OneTimeTransaction(id, day.get(), title.getText(), kind, money.get(),
                category.getEditor().getText(), note.getText()));
    }
}
