package ru.cashprediction.core.ui.forms.ops;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.ButtonSpecs;
import ru.cashprediction.core.ui.form.ButtonView;
import ru.cashprediction.core.ui.form.FieldChecks;
import ru.cashprediction.core.ui.form.FieldCodec;
import ru.cashprediction.core.ui.form.FieldSpecs;
import ru.cashprediction.core.ui.form.FieldView;
import ru.cashprediction.core.ui.form.FormPage;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.Presentation;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.DesignTokens;
import ru.cashprediction.core.session.WindowType;

/**
 * §5.6.1 QUICK_EDIT_POPUP «Быстрая правка суммы» (представление {@code POPUP}: JavaFX {@code Popup} → Swing
 * {@code PopupFactory} → Web div; контекст {@code ruleId}, {@code originalDate}; поле {@code amount}; немодальное,
 * одновременно одно).
 *
 * <p>Содержимое: жирная подпись {@code quick.caption} ««{title}», dd.MM.yyyy — новая сумма, {cur}»; денежное поле
 * 140 (текущая сумма «95 000,00», выделено, фокус); ошибка {@code quick.error} «Введите сумму больше нуля, например
 * 95 000,00» (скрыта, пока ввод корректен); подсказка {@code quick.hint} «Enter — сохранить, Esc — закрыть». Кнопок
 * нет: Enter — кнопка {@code ok}, Esc/щелчок вне окна — {@code cancel}.</p>
 *
 * <p>Результат {@code Close(AdjustmentForm.Result)}: CHANGE_AMOUNT; если событие уже перенесено — REPLACE с прежней
 * датой; заметка сохраняется; если сумма равна сумме правила без переноса и заметки — {@code adjustment = null}
 * (корректировка удаляется). Контроллер: {@code undo.quickAmount}, {@code status.msg.quickAmount}.</p>
 */
public final class QuickEditForm implements FormLogic {

    /** Создаёт форму. */
    public QuickEditForm() {
    }

    @Override
    public FormSpec spec(FormContext context) {
        var amount = FieldSpecs.focused(FieldSpecs.withTooltip(
                FieldSpecs.withWidthPx(FieldSpecs.money("amount", ""), DesignTokens.QUICK_EDIT_FIELD_WIDTH), UiText.get("quick.hint")));
        return new FormSpec("quickEdit", WindowType.QUICK_EDIT_POPUP, "", Presentation.POPUP, "", "", DesignTokens.QUICK_EDIT_POPUP_WIDTH,
                false, false, true, List.of(new FormPage("main", List.of(new FormRow.Field(amount),
                        new FormRow.Hint("quickHint", UiText.get("quick.hint"))))), List.of(), ButtonSpecs.OK);
    }

    @Override
    public Map<String, String> defaults(FormContext context) {
        OccurrenceKey key = key(context);
        RecurringRule rule = rule(context);
        if (key == null || rule == null) return Map.of("amount", "");
        Adjustment adjustment = context.app().document().plan().findAdjustment(key).orElse(null);
        return Map.of("amount", FieldCodec.money(adjustment == null ? rule.amount() : adjustment.action().newAmount().orElse(rule.amount())));
    }

    @Override
    public FormView evaluate(FormState state, FormContext context) {
        OccurrenceKey key = key(context);
        RecurringRule rule = rule(context);
        Optional<String> check = FieldChecks.money(UiText.get("quick.amount"), state.value("amount"), FieldChecks.MoneyRule.REQUIRED_POSITIVE);
        Problem problem = check.map(ignored -> Problem.error(UiText.get("quick.error"))).orElse(Problem.NONE);
        String header = rule == null || key == null ? UiText.get("quick.missing") : UiText.get("quick.caption", rule.title(), UiFormats.date(key.originalDate()), context.app().document().plan().currency());
        Map<String, FieldView> fields = OpsForms.values(state, "amount");
        return new FormView(0, 0, header, fields, problem, Map.of(ButtonSpecs.OK, check.isPresent() ? ButtonView.DISABLED : ButtonView.ENABLED), List.of(), List.of(), "", false);
    }

    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        if (ButtonSpecs.CANCEL.equals(buttonId)) return new FormOutcome.Close(null);
        OccurrenceKey key = key(context);
        RecurringRule rule = rule(context);
        if (key == null || rule == null) return new FormOutcome.Close(null);
        FormView view = evaluate(state, context);
        if (view.problem().severity() == Problem.Severity.ERROR) return new FormOutcome.Stay(view.problem());
        Adjustment existing = context.app().document().plan().findAdjustment(key).orElse(null);
        var amount = OpsForms.money(state.value("amount"));
        Adjustment next;
        if (existing == null || existing.action() instanceof Adjustment.ChangeAmount) {
            next = amount.equals(rule.amount()) && (existing == null || existing.note().isBlank()) ? null : new Adjustment(key, new Adjustment.ChangeAmount(amount), existing == null ? "" : existing.note());
        } else if (existing.action() instanceof Adjustment.MoveDate move) {
            next = new Adjustment(key, new Adjustment.Replace(amount, move.date()), existing.note());
        } else if (existing.action() instanceof Adjustment.Replace replace) {
            next = new Adjustment(key, new Adjustment.Replace(amount, replace.date()), existing.note());
        } else {
            next = new Adjustment(key, new Adjustment.ChangeAmount(amount), existing.note());
        }
        return new FormOutcome.Close(new AdjustmentForm.Result(key, next));
    }

    private OccurrenceKey key(FormContext context) {
        try { LocalDate date = OpsForms.date(context.contextValue(WindowType.CONTEXT_ORIGINAL_DATE)); return date == null ? null : new OccurrenceKey(new RuleId(context.contextValue(WindowType.CONTEXT_RULE_ID)), date); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private RecurringRule rule(FormContext context) {
        OccurrenceKey key = key(context); return key == null ? null : context.app().document().plan().findRule(key.ruleId()).orElse(null);
    }
}
