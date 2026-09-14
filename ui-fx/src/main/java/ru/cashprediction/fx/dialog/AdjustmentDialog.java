package ru.cashprediction.fx.dialog;

import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.markdown.RuFormats;
import ru.cashprediction.core.markdown.RuFormats.ActionType;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.recurrence.OccurrenceGenerator;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Диалог 5 «Корректировка события»: одно конкретное повторение правила пропускается, получает другую
 * сумму, переносится или заменяется (сумма и дата).
 *
 * <p>Корректировка привязана к <i>номинальной</i> дате правила (до сдвига с выходных) — так она не теряется,
 * если пользователь поменяет политику выходных. Контекст окна: {@code ruleId}, {@code originalDate}.
 * Кнопка «Сбросить» (только если корректировка уже есть) возвращает событие «как по правилу».</p>
 *
 * <p>Результат — {@link AdjustmentOutcome}. Только FX Application Thread.</p>
 */
// JavaFX: Dialog<R> + DialogPane (AppDialogPane) + ButtonType «Сбросить» → Swing: SwingDialog<R> (AdjustmentDialog) → Web: openDialog('adjustment')
public final class AdjustmentDialog extends FxStatefulDialog<AdjustmentOutcome> {

    private final Plan plan;
    private final RecurringRule rule;
    private final OccurrenceKey key;

    private final ToggleGroup actionGroup = new ToggleGroup();
    private final TextField amount;
    private final DatePicker date;
    private final TextArea note = new TextArea();

    /**
     * Создаёт диалог корректировки.
     *
     * @param plan         текущий план
     * @param rule         правило события
     * @param originalDate номинальная дата события
     * @param existing     текущая корректировка или {@code null}
     */
    public AdjustmentDialog(Plan plan, RecurringRule rule, LocalDate originalDate, Adjustment existing) {
        super(WindowType.ADJUSTMENT_EDITOR, new AppDialogPane("«" + rule.title() + "»: событие "
                + RuText.weekdayShort(originalDate.getDayOfWeek()) + ", " + DateFormats.ru(originalDate), "✎"));
        this.plan = plan;
        this.rule = rule;
        this.key = new OccurrenceKey(rule.id(), originalDate);
        stateSupport().putContext(WindowType.CONTEXT_RULE_ID, rule.id().value());
        stateSupport().putContext(WindowType.CONTEXT_ORIGINAL_DATE, DateFormats.iso(originalDate));

        LocalDate shifted = rule.weekendPolicy().apply(originalDate);
        ActionType initialAction = existing == null ? ActionType.CHANGE_AMOUNT : RuFormats.actionTypeOf(existing.action());
        Money initialAmount = existing == null ? rule.amount() : existing.action().newAmount().orElse(rule.amount());
        LocalDate initialDate = existing == null ? shifted : existing.action().newDate().orElse(shifted);
        amount = FxInputs.moneyField(initialAmount);
        date = FxInputs.datePicker(initialDate);
        note.setText(existing == null ? "" : existing.note());
        note.setPrefRowCount(2);
        note.setWrapText(true);

        VBox actions = new VBox(6,
                radio("Пропустить - события не будет", ActionType.SKIP, initialAction),
                radio("Изменить сумму", ActionType.CHANGE_AMOUNT, initialAction),
                radio("Перенести на другую дату", ActionType.MOVE_DATE, initialAction),
                radio("Заменить сумму и дату", ActionType.REPLACE, initialAction));
        String ruleInfo = "По правилу: " + rule.kind().label() + " " + rule.amount().format(plan.currency())
                + (shifted.equals(originalDate) ? "" : ", с учётом выходных - " + DateFormats.ru(shifted));
        Label info = new Label(ruleInfo);
        info.setWrapText(true);
        appPane().setForm(new FormGrid()
                .wide(info)
                .row("Действие", actions)
                .row("Новая сумма", amount)
                .row("Новая дата", date)
                .row("Заметка", note));
        // JavaFX: ButtonType → Swing: SwingButtonType (текст + роль) + JOptionPane.showOptionDialog → Web: <button value> → returnValue
        appPane().getButtonTypes().setAll(existing == null
                ? List.of(AppButtonTypes.SAVE, AppButtonTypes.CANCEL)
                : List.of(AppButtonTypes.SAVE, AppButtonTypes.RESET, AppButtonTypes.CANCEL));

        // Порядок привязки = порядок полей словаря WindowType.ADJUSTMENT_EDITOR.
        binder().bindToggle("action", actionGroup);
        binder().bindMoney("amount", amount);
        binder().bindDate("date", date);
        binder().bindText("note", note);

        setResultConverter(button -> {
            if (button == AppButtonTypes.RESET) {
                return new AdjustmentOutcome(key, null);
            }
            if (button == AppButtonTypes.SAVE) {
                return build(new ArrayList<>(), new ArrayList<>()).map(a -> new AdjustmentOutcome(key, a)).orElse(null);
            }
            return null;
        });
        activate();
    }

    /** {@inheritDoc} */
    @Override
    protected void validateForm(List<String> errors, List<String> warnings) {
        ActionType action = selectedAction();
        // Недоступное действию поле блокируется, но его значение не стирается: пользователь может передумать.
        amount.setDisable(!action.requiresAmount());
        date.setDisable(!action.requiresDate());
        build(errors, warnings);
    }

    private Optional<Adjustment> build(List<String> errors, List<String> warnings) {
        ActionType action = selectedAction();
        Money money = null;
        if (action.requiresAmount()) {
            Optional<Money> parsed = FxInputs.money(amount);
            if (parsed.isEmpty() || !parsed.get().isPositive()) {
                errors.add("Новая сумма должна быть числом больше нуля");
            } else if (parsed.get().compareTo(PlanValidator.MAX_AMOUNT) > 0) {
                errors.add("Сумма слишком большая");
            } else {
                money = parsed.get();
            }
        }
        LocalDate newDate = null;
        if (action.requiresDate()) {
            newDate = FxInputs.date(date).orElse(null);
            if (newDate == null) {
                errors.add("Укажите новую дату (ДД.ММ.ГГГГ)");
            } else if (newDate.isBefore(plan.startDate()) || newDate.isAfter(plan.endDate())) {
                warnings.add("Новая дата вне горизонта плана: событие выпадет из прогноза");
            }
        }
        if (!errors.isEmpty()) {
            return Optional.empty();
        }
        if (!OccurrenceGenerator.isNominalDate(rule, plan.startDate(), key.originalDate())) {
            warnings.add("Правило не создаёт событие " + DateFormats.ru(key.originalDate())
                    + ": корректировка не будет действовать");
        }
        try {
            return Optional.of(new Adjustment(key, RuFormats.buildAction(action, money, newDate), note.getText()));
        } catch (IllegalArgumentException e) {
            errors.add(e.getMessage());
            return Optional.empty();
        }
    }

    private ActionType selectedAction() {
        Object data = actionGroup.getSelectedToggle() == null ? null : actionGroup.getSelectedToggle().getUserData();
        try {
            return data == null ? ActionType.CHANGE_AMOUNT : ActionType.valueOf(data.toString());
        } catch (IllegalArgumentException e) {
            return ActionType.CHANGE_AMOUNT;
        }
    }

    private RadioButton radio(String text, ActionType type, ActionType initial) {
        RadioButton button = new RadioButton(text);
        button.setUserData(type.name());
        button.setToggleGroup(actionGroup);
        button.setSelected(type == initial);
        return button;
    }
}
