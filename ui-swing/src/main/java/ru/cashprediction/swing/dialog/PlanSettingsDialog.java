package ru.cashprediction.swing.dialog;

import java.awt.Window;
import java.time.LocalDate;
import java.util.function.Predicate;
import javax.swing.JComboBox;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.SessionRecorder;
import ru.cashprediction.core.session.WindowType;

/**
 * Диалог 2 «Параметры плана»: название, валюта, дата начала и баланс, горизонт, подушка, заметка и цель.
 *
 * <p>Результат — набор значений {@link Values}, который применяется к <i>текущему</i> плану документа
 * ({@link Values#applyTo}), а не готовый план: пока диалог открыт, план мог измениться (например, в немодальном
 * калькуляторе цели), и правила с разовыми операциями нельзя затирать копией на момент открытия.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: Dialog<R> + DialogPane → Swing: SwingDialog<R> (JDialog DOCUMENT_MODAL) + SwingDialogPane → Web: openDialog(id): Promise<R> поверх <dialog>
public final class PlanSettingsDialog extends SwingDialog<PlanSettingsDialog.Values> {

    /**
     * Значения формы.
     *
     * @param name         название плана
     * @param currency     валюта
     * @param startDate    дата начала
     * @param startBalance баланс на дату начала
     * @param horizon      горизонт
     * @param cushion      подушка безопасности
     * @param note         заметка
     * @param goal         цель или {@code null}, если сумма цели не задана
     */
    public record Values(String name, String currency, LocalDate startDate, Money startBalance, Horizon horizon,
                         Money cushion, String note, Goal goal) {

        /**
         * Применяет значения к плану.
         *
         * @param plan текущий план
         * @return план с новыми параметрами и прежними операциями
         */
        public Plan applyTo(Plan plan) {
            return plan.withName(name).withCurrency(currency).withStart(startDate, startBalance).withHorizon(horizon)
                    .withCushion(cushion).withNote(note).withGoal(goal);
        }
    }

    private final String originalName;
    private final Predicate<String> nameTaken;
    private final boolean renameSavesFile;

    private final JTextField name = new JTextField(24);
    private final JComboBox<String> currency;
    private final DateField startDate = new DateField();
    private final MoneyField startBalance = new MoneyField();
    private final HorizonEditor horizon = new HorizonEditor();
    private final MoneyField cushion = new MoneyField();
    private final JTextArea note = new JTextArea(4, 30);
    private final JTextField goalTitle = new JTextField(20);
    private final MoneyField goalTarget = new MoneyField();
    private final DateField goalDate = new DateField();

    /**
     * Создаёт диалог.
     *
     * @param owner           окно-владелец
     * @param ownerId         идентификатор владельца для снимка
     * @param recorder        рекордер сессии или {@code null}
     * @param plan            текущий план (начальные значения)
     * @param nameTaken       проверка «другой план с таким именем уже есть»
     * @param renameSavesFile переименование сохранит файл плана (у плана есть файл): об этом предупреждаем
     */
    public PlanSettingsDialog(Window owner, String ownerId, SessionRecorder recorder, Plan plan,
                              Predicate<String> nameTaken, boolean renameSavesFile) {
        super(owner, ownerId, WindowType.PLAN_SETTINGS, true, "Параметры плана", recorder);
        this.originalName = plan.name();
        this.nameTaken = nameTaken == null ? n -> false : nameTaken;
        this.renameSavesFile = renameSavesFile;
        currency = Combos.editable(Combos.CURRENCIES, plan.currency());

        name.setText(plan.name());
        startDate.setValue(plan.startDate());
        startBalance.setValue(plan.startBalance());
        horizon.setHorizon(plan.horizon());
        cushion.setValue(plan.cushion());
        note.setText(plan.note());
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        plan.goalOptional().ifPresent(goal -> {
            goalTitle.setText(goal.title());
            goalTarget.setValue(goal.target());
            goalDate.setValue(goal.wishDate());
        });

        FormPanel form = new FormPanel();
        form.addSection("План");
        form.addRow("Название", name);
        form.addRow("Валюта", currency);
        form.addRow("Дата начала", startDate);
        form.addRow("Баланс на дату начала", startBalance);
        form.addRow("Горизонт прогноза", horizon.component());
        form.addRow("Подушка безопасности", cushion);
        form.addRow("Заметка", new JScrollPane(note));
        form.addSection("Цель накоплений");
        form.addRow("Название цели", goalTitle);
        form.addRow("Сумма цели", goalTarget);
        form.addRow("Желаемая дата", goalDate);
        pane().setHeaderText("Параметры плана «" + plan.name() + "». Операции плана не меняются.");
        pane().setContent(form);

        binder().bindText("name", name);
        binder().bindEditableCombo("currency", currency);
        binder().bindDate("startDate", startDate);
        binder().bindMoney("startBalance", startBalance);
        horizon.bind(binder());
        binder().bindMoney("cushion", cushion);
        binder().bindText("note", note);
        binder().bindText("goalTitle", goalTitle);
        binder().bindMoney("goalTarget", goalTarget);
        binder().bindDate("goalDate", goalDate);

        setValidator(this::formError);
        setWarningSupplier(this::formWarning);
        setResultConverter(button -> button.isDefaultButton() ? buildValues() : null);
        setButtonTypes(SwingButtonType.OK, SwingButtonType.CANCEL);
        setInitialFocus(name);
    }

    /** {@inheritDoc} */
    @Override
    protected void onFieldsChanged() {
        horizon.updateUi();
    }

    private String formError() {
        String newName = name.getText().strip();
        String nameError = PlanValidator.checkPlanName(newName).orElse(null);
        if (nameError != null) {
            return nameError;
        }
        if (!newName.equalsIgnoreCase(originalName) && nameTaken.test(newName)) {
            return "План «" + newName + "» уже есть в папке CashMemory: выберите другое название";
        }
        if (Combos.editorText(currency).isEmpty()) {
            return "Укажите валюту";
        }
        String error = startDate.validationError("Дата начала", true);
        if (error == null) {
            error = startBalance.validationError("Баланс на дату начала", true, false);
        }
        if (error == null) {
            error = horizon.validationError(startDate.value().orElse(null));
        }
        if (error == null) {
            error = cushion.validationError("Подушка безопасности", false, false);
        }
        if (error == null && cushion.value().filter(Money::isNegative).isPresent()) {
            error = "Подушка безопасности не может быть отрицательной";
        }
        if (error == null) {
            error = goalTarget.validationError("Сумма цели", false, true);
        }
        if (error == null) {
            error = goalDate.validationError("Желаемая дата", false);
        }
        if (error == null && goalTarget.isBlank() && (!goalTitle.getText().isBlank() || !goalDate.isBlank())) {
            error = "Укажите сумму цели или очистите название и дату цели";
        }
        return error;
    }

    private String formWarning() {
        if (renameSavesFile && !name.getText().strip().equals(originalName)) {
            return "Файл плана будет переименован, а план - сохранён под новым именем";
        }
        return horizon.longHorizonWarning(startDate.value().orElse(null)).orElse(null);
    }

    private Values buildValues() {
        String error = formError();
        if (error != null) {
            throw new IllegalArgumentException(error);
        }
        Goal goal = goalTarget.value()
                .map(target -> new Goal(goalTitle.getText(), target, goalDate.value().orElse(null)))
                .orElse(null);
        return new Values(name.getText().strip(), Combos.editorText(currency), startDate.value().orElseThrow(),
                startBalance.value().orElse(Money.ZERO), horizon.horizon(), cushion.value().orElse(Money.ZERO),
                note.getText(), goal);
    }
}
