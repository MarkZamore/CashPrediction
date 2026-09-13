package ru.cashprediction.fx.dialog;

import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.WindowType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Диалог 2 «Параметры плана»: имя, валюта, начало, баланс, горизонт, подушка, заметка и цель.
 *
 * <p>Если план уже связан с файлом, имя здесь не редактируется: имя плана — это имя файла, и менять его
 * нужно командой «Переименовать…», которая переименует и файл. Иначе после перечитывания файла имя
 * молча вернулось бы прежним.</p>
 *
 * <p>Результат — {@link PlanParameters}. Только FX Application Thread.</p>
 */
// JavaFX: Dialog<R> + DialogPane (AppDialogPane) + ButtonType → Swing: SwingDialog<R> (PlanSettingsDialog) → Web: openDialog('planSettings')
public final class PlanSettingsDialog extends FxStatefulDialog<PlanParameters> {

    private final Plan basePlan;
    private final boolean nameEditable;

    private final TextField name = new TextField();
    private final ComboBox<String> currency = new ComboBox<>();
    private final DatePicker startDate;
    private final TextField startBalance;
    private final HorizonEditor horizon;
    private final TextField cushion;
    private final TextArea note = new TextArea();
    private final TextField goalTitle = new TextField();
    private final TextField goalTarget;
    private final DatePicker goalDate;

    /**
     * Создаёт диалог по текущему плану.
     *
     * @param plan         план на момент открытия (начальные значения полей)
     * @param nameEditable можно ли менять имя (план ещё не сохранён в файл)
     */
    public PlanSettingsDialog(Plan plan, boolean nameEditable) {
        super(WindowType.PLAN_SETTINGS, new AppDialogPane("Параметры плана «" + plan.name() + "»", "⚙"));
        this.basePlan = plan;
        this.nameEditable = nameEditable;
        name.setText(plan.name());
        name.setEditable(nameEditable);
        if (!nameEditable) {
            // JavaFX: Tooltip → Swing: setToolTipText → Web: title / <div class="tooltip">
            name.setTooltip(new Tooltip("Имя плана совпадает с именем файла. Изменить: Файл → Переименовать… (F2)"));
            name.setStyle("-fx-opacity: 0.75;");
        }
        currency.getItems().setAll(NewPlanWizard.CURRENCIES);
        currency.setEditable(true);
        currency.setValue(plan.currency());
        startDate = FxInputs.datePicker(plan.startDate());
        startBalance = FxInputs.moneyField(plan.startBalance());
        horizon = new HorizonEditor(plan.horizon());
        cushion = FxInputs.moneyField(plan.cushion());
        note.setText(plan.note());
        note.setPrefRowCount(3);
        note.setWrapText(true);
        Goal goal = plan.goal();
        goalTitle.setText(goal == null ? "" : goal.title());
        goalTitle.setPromptText("например, Отпуск");
        goalTarget = FxInputs.moneyField(goal == null ? null : goal.target());
        goalTarget.setPromptText("пусто — цели нет");
        goalDate = FxInputs.datePicker(goal == null ? null : goal.wishDate());

        FormGrid form = new FormGrid()
                .row("Название плана", name)
                .row("Валюта", currency)
                .row("Дата начала", startDate)
                .row("Баланс на эту дату", startBalance)
                .row("Горизонт прогноза", horizon.node())
                .row("Подушка безопасности", cushion)
                .row("Заметка", note)
                .section("Цель накопления")
                .row("Название цели", goalTitle)
                .row("Сумма цели", goalTarget)
                .row("Желаемая дата", goalDate);
        appPane().setForm(form);
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        appPane().getButtonTypes().setAll(AppButtonTypes.SAVE, AppButtonTypes.CANCEL);

        // Порядок привязки = порядок полей словаря WindowType.PLAN_SETTINGS.
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

        setResultConverter(button -> button == AppButtonTypes.SAVE
                ? read(new ArrayList<>(), new ArrayList<>()).orElse(null) : null);
        activate();
    }

    /** {@inheritDoc} */
    @Override
    protected void validateForm(List<String> errors, List<String> warnings) {
        read(errors, warnings);
    }

    private Optional<PlanParameters> read(List<String> errors, List<String> warnings) {
        String planName = nameEditable ? name.getText().strip() : basePlan.name();
        PlanValidator.checkPlanName(planName).ifPresent(errors::add);
        if (FxInputs.isBlank(currency.getEditor())) {
            errors.add("Укажите валюту");
        }
        Optional<LocalDate> start = FxInputs.date(startDate);
        if (start.isEmpty()) {
            errors.add("Укажите дату начала (ДД.ММ.ГГГГ)");
        }
        Optional<Money> balance = FxInputs.money(startBalance);
        if (!FxInputs.isBlank(startBalance) && balance.isEmpty()) {
            errors.add("Некорректный начальный баланс");
        }
        Optional<Horizon> horizonValue = horizon.read(start, errors, warnings);
        Optional<Money> cushionValue = FxInputs.money(cushion);
        if (!FxInputs.isBlank(cushion) && cushionValue.isEmpty()) {
            errors.add("Некорректная сумма подушки безопасности");
        } else if (cushionValue.isPresent() && cushionValue.get().isNegative()) {
            errors.add("Подушка безопасности не может быть отрицательной");
        }
        Goal goal = readGoal(start, errors, warnings);
        if (!errors.isEmpty()) {
            return Optional.empty();
        }
        PlanParameters parameters = new PlanParameters(planName, currency.getEditor().getText().strip(), start.get(),
                balance.orElse(Money.ZERO), horizonValue.get(), cushionValue.orElse(Money.ZERO), note.getText(), goal);
        // Грубая оценка числа строк до запуска движка: «каждый день на 50 лет» не должен подвесить программу.
        long rows = PlanValidator.estimateRowCount(parameters.applyTo(basePlan));
        if (rows > PlanValidator.MAX_ROWS) {
            errors.add("План даст около " + rows + " строк прогноза (допустимо " + PlanValidator.MAX_ROWS + "): сократите горизонт");
            return Optional.empty();
        }
        if (!start.get().equals(basePlan.startDate()) && !basePlan.adjustments().isEmpty()) {
            warnings.add("Корректировки привязаны к датам событий: при смене даты начала часть из них может перестать совпадать");
        }
        return Optional.of(parameters);
    }

    private Goal readGoal(Optional<LocalDate> start, List<String> errors, List<String> warnings) {
        boolean anyGoalField = !FxInputs.isBlank(goalTitle) || !FxInputs.isBlank(goalDate);
        if (FxInputs.isBlank(goalTarget)) {
            if (anyGoalField) {
                errors.add("Укажите сумму цели или очистите название и дату цели");
            }
            return null;
        }
        Optional<Money> target = FxInputs.money(goalTarget);
        if (target.isEmpty() || !target.get().isPositive()) {
            errors.add("Сумма цели должна быть числом больше нуля");
            return null;
        }
        LocalDate wish = null;
        if (!FxInputs.isBlank(goalDate)) {
            Optional<LocalDate> parsed = FxInputs.date(goalDate);
            if (parsed.isEmpty()) {
                errors.add("Некорректная желаемая дата цели (ДД.ММ.ГГГГ)");
                return null;
            }
            wish = parsed.get();
            if (start.isPresent() && wish.isBefore(start.get())) {
                warnings.add("Желаемая дата цели раньше начала плана");
            }
        }
        return new Goal(goalTitle.getText(), target.get(), wish);
    }
}
