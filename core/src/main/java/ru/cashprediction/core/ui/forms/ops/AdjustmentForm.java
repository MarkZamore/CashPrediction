package ru.cashprediction.core.ui.forms.ops;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.ButtonRole;
import ru.cashprediction.core.ui.form.ButtonSpecs;
import ru.cashprediction.core.ui.form.ButtonView;
import ru.cashprediction.core.ui.form.FieldCodec;
import ru.cashprediction.core.ui.form.FieldChecks;
import ru.cashprediction.core.ui.form.FieldSpecs;
import ru.cashprediction.core.ui.form.FieldView;
import ru.cashprediction.core.ui.form.FormPage;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.Option;
import ru.cashprediction.core.ui.form.Orientation;
import ru.cashprediction.core.ui.form.Presentation;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.DialogWidth;
import ru.cashprediction.core.session.WindowType;

/**
 * §6.5 ADJUSTMENT_EDITOR «Корректировка события» (✎, 560; контекст {@code ruleId}, {@code originalDate};
 * представление {@code DIALOG}).
 *
 * <p>Заголовок ««{rule title}»: событие пн, 05.10.2026», вторая строка «Сейчас: {…}», если корректировка есть. Широкая
 * строка «По правилу: доход 80 000,00 ₽» (+ «, с учётом выходных — dd.MM.yyyy»). Поля: {@code action} —
 * вертикальное радио SKIP / CHANGE_AMOUNT / MOVE / REPLACE; {@code amount} (доступна для изменения и замены);
 * {@code date} (для переноса и замены); {@code note} (2 строки). Недоступные поля сохраняют значение. Ошибки и
 * предупреждения — §6.5. Если правила нет: заголовок «Операция «{id}» не найдена в плане», поля отключены, кнопка
 * [Закрыть]. Кнопки: [Сбросить корректировку] (LEFT, только если есть) · [Сохранить] · [Отмена].</p>
 *
 * <p>Результат {@code Close(}{@link Result}{@code )}; контроллер: сохранение — {@code undo.adjust},
 * {@code status.msg.adjustSaved}; сброс — {@code undo.adjustReset}, {@code status.msg.adjustReset}.</p>
 */
public final class AdjustmentForm implements FormLogic {

    /**
     * Результат корректировки (общий с {@link QuickEditForm}).
     *
     * @param key        событие правила
     * @param adjustment новая корректировка или {@code null} — удалить корректировку (сброс)
     */
    public record Result(OccurrenceKey key, Adjustment adjustment) {
        /** Проверяет ключ. */
        public Result {
            Objects.requireNonNull(key, "key");
        }
    }

    /** Создаёт форму. */
    public AdjustmentForm() {
    }

    /**
     * Строит раскладку редактора корректировки: сведения о правиле, выбор действия, сумма, дата и заметка.
     * В раскладку включены сброс, сохранение и отмена; их фактическую доступность определяет {@link #evaluate}.
     * При отсутствии правила строка сведений пуста, но состав полей сохраняется.
     *
     * @param context окружение формы с id правила и исходной датой события
     * @return неизменяемая раскладка модального редактора с сохранением по умолчанию
     */
    @Override
    public FormSpec spec(FormContext context) {
        return new FormSpec("adjustment", WindowType.ADJUSTMENT_EDITOR, "", Presentation.DIALOG, UiText.get("adjustment.window"), "✎",
                DialogWidth.FORM.px(), true, false, true, List.of(new FormPage("main", List.of(
                new FormRow.Hint("rule", ruleSummary(context)),
                new FormRow.Field(FieldSpecs.radio("action", UiText.get("adjustment.action"), Orientation.VERTICAL, actionOptions())),
                new FormRow.Field(FieldSpecs.money("amount", UiText.get("adjustment.amount"))),
                new FormRow.Field(FieldSpecs.date("date", UiText.get("adjustment.date"))),
                new FormRow.Field(FieldSpecs.withPrompt(FieldSpecs.multiline("note", UiText.get("adjustment.note"), 2), UiText.get("adjustment.note.prompt")))))),
                List.of(ButtonSpecs.of("reset", UiText.get("button.resetAdjustment"), ButtonRole.LEFT), ButtonSpecs.ok(UiText.get("button.save")), ButtonSpecs.cancel()), ButtonSpecs.OK);
    }

    /**
     * Заполняет поля существующей корректировкой, а при её отсутствии выбирает изменение суммы правила.
     * Если действие не задаёт новую сумму или дату, использует сумму правила и дату с учётом выходных.
     * Недопустимый ключ события или отсутствующее правило не вызывает ошибку ввода: недоступные
     * исходные значения становятся пустыми строками. Сумма и дата записываются через {@link FieldCodec}.
     *
     * @param context окружение с ключом события и текущим планом
     * @return неизменяемая карта значений action, amount, date и note для новой формы
     */
    @Override
    public Map<String, String> defaults(FormContext context) {
        OccurrenceKey key = key(context);
        var rule = rule(context);
        Adjustment adjustment = key == null ? null : context.app().document().plan().findAdjustment(key).orElse(null);
        LocalDate actual = rule == null || key == null ? null : rule.weekendPolicy().apply(key.originalDate());
        String action = adjustment == null ? "CHANGE_AMOUNT" : actionCode(adjustment.action());
        return Map.of("action", action, "amount", FieldCodec.money(adjustment == null ? rule == null ? null : rule.amount() : adjustment.action().newAmount().orElse(rule == null ? null : rule.amount())),
                "date", FieldCodec.date(adjustment == null ? actual : adjustment.action().newDate().orElse(actual)), "note", adjustment == null ? "" : adjustment.note());
    }

    /**
     * Проверяет поля выбранного действия, сохраняя введённые значения даже в отключённых полях.
     * Изменение суммы и замена требуют положительной суммы, перенос и замена требуют корректной даты;
     * первой показывается ошибка суммы. Дата вне горизонта даёт предупреждение и не запрещает сохранение.
     * Неизвестный код действия при расчёте доступности трактуется как изменение суммы.
     * Если ключ события некорректен или правило удалено, отключает поля, скрывает сохранение и сброс,
     * оставляя закрытие; иначе сброс доступен только при наличии корректировки.
     *
     * @param state текущие значения полей без изменения исходного состояния
     * @param context окружение с ключом события и текущим планом
     * @return модель полей, заголовка, проблем и кнопок; ревизию задаёт сеанс формы
     */
    @Override
    public FormView evaluate(FormState state, FormContext context) {
        OccurrenceKey key = key(context);
        var rule = rule(context);
        if (key == null || rule == null) {
            Map<String, FieldView> disabled = OpsForms.values(state, "action", "amount", "date", "note");
            disabled.replaceAll((id, value) -> new FieldView(value.value(), true, false, false, value.label(), value.options(), value.tooltip()));
            return new FormView(0, 0, UiText.get("adjustment.header.missing", context.contextValue(WindowType.CONTEXT_RULE_ID)), disabled, Problem.NONE,
                    Map.of(ButtonSpecs.CLOSE, ButtonView.ENABLED, "reset", ButtonView.HIDDEN, ButtonSpecs.OK, ButtonView.HIDDEN), List.of(), List.of(), "", false);
        }
        String action = OpsForms.enumValue(ActionCode.class, state.value("action"), ActionCode.CHANGE_AMOUNT).name();
        boolean amountEnabled = action.equals("CHANGE_AMOUNT") || action.equals("REPLACE");
        boolean dateEnabled = action.equals("MOVE") || action.equals("REPLACE");
        Optional<String> error = amountEnabled ? FieldChecks.money(UiText.get("adjustment.amount"), state.value("amount"), FieldChecks.MoneyRule.REQUIRED_POSITIVE) : Optional.empty();
        if (error.isEmpty() && dateEnabled) error = FieldChecks.date(UiText.get("adjustment.date"), state.value("date"), true);
        String warning = "";
        LocalDate newDate = OpsForms.date(state.value("date"));
        if (error.isEmpty() && dateEnabled && newDate != null && (newDate.isBefore(context.app().document().plan().startDate()) || newDate.isAfter(context.app().document().plan().endDate()))) warning = UiText.get("adjustment.warning.outside");
        Map<String, FieldView> fields = OpsForms.values(state, "action", "amount", "date", "note");
        fields.put("amount", new FieldView(state.value("amount"), true, amountEnabled, false, null, null, null));
        fields.put("date", new FieldView(state.value("date"), true, dateEnabled, false, null, null, null));
        Adjustment existing = context.app().document().plan().findAdjustment(key).orElse(null);
        String header = UiText.get("adjustment.header", rule.title(), UiFormats.weekdayDate(key.originalDate()));
        if (existing != null) header += "\n" + UiText.get("adjustment.current", UiText.get(OpsForms.adjustmentDescription(existing.action())));
        return new FormView(0, 0, header, fields, OpsForms.problem(error, warning),
                Map.of(ButtonSpecs.OK, error.isPresent() ? ButtonView.DISABLED : ButtonView.ENABLED, "reset", existing == null ? ButtonView.HIDDEN : ButtonView.ENABLED), List.of(), List.of(), "", false);
    }

    /**
     * Преобразует нажатие кнопки в результат формы, не изменяя план непосредственно.
     * Отмена, закрытие или исчезновение правила возвращают закрытие без результата.
     * Сброс возвращает ключ события с отсутствующей корректировкой без проверки полей.
     * Сохранение повторно проверяет ввод: ошибка оставляет форму открытой, предупреждение допускает результат.
     * Неизвестное или неполное действие также оставляет форму открытой с проблемой;
     * прочие кнопки не выполняют действие. Применение возвращённого результата принадлежит контроллеру.
     *
     * @param buttonId id нажатой кнопки
     * @param state текущие значения полей
     * @param context окружение с ключом события и текущим планом
     * @return закрытие с {@link Result}, закрытие без результата либо продолжение работы формы
     */
    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        if (ButtonSpecs.CANCEL.equals(buttonId) || ButtonSpecs.CLOSE.equals(buttonId)) return new FormOutcome.Close(null);
        OccurrenceKey key = key(context);
        if (key == null || rule(context) == null) return new FormOutcome.Close(null);
        if ("reset".equals(buttonId)) return new FormOutcome.Close(new Result(key, null));
        if (!ButtonSpecs.OK.equals(buttonId)) return FormOutcome.stay();
        FormView view = evaluate(state, context);
        if (view.problem().severity() == Problem.Severity.ERROR) return new FormOutcome.Stay(view.problem());
        Adjustment.Action action = OpsForms.adjustmentAction(state.value("action"), OpsForms.money(state.value("amount")), OpsForms.date(state.value("date")));
        return action == null ? new FormOutcome.Stay(Problem.error(UiText.get("adjustment.error.action"))) : new FormOutcome.Close(new Result(key, new Adjustment(key, action, state.value("note"))));
    }

    /** Коды действий формы для расчёта доступности суммы и даты; неизвестный ввод заменяется CHANGE_AMOUNT. */
    private enum ActionCode { SKIP, CHANGE_AMOUNT, MOVE, REPLACE }

    private List<Option> actionOptions() {
        return List.of(Option.of("SKIP", UiText.get("adjustment.action.skip")), Option.of("CHANGE_AMOUNT", UiText.get("adjustment.action.amount")),
                Option.of("MOVE", UiText.get("adjustment.action.move")), Option.of("REPLACE", UiText.get("adjustment.action.replace")));
    }

    private OccurrenceKey key(FormContext context) {
        try { LocalDate date = OpsForms.date(context.contextValue(WindowType.CONTEXT_ORIGINAL_DATE)); return date == null ? null : new OccurrenceKey(new ru.cashprediction.core.model.RuleId(context.contextValue(WindowType.CONTEXT_RULE_ID)), date); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private ru.cashprediction.core.model.RecurringRule rule(FormContext context) {
        OccurrenceKey key = key(context); return key == null ? null : context.app().document().plan().findRule(key.ruleId()).orElse(null);
    }

    private String actionCode(Adjustment.Action action) {
        return action instanceof Adjustment.Skip ? "SKIP" : action instanceof Adjustment.ChangeAmount ? "CHANGE_AMOUNT" : action instanceof Adjustment.MoveDate ? "MOVE" : "REPLACE";
    }

    /** @return широкая строка с исходной операцией правила */
    private String ruleSummary(FormContext context) {
        OccurrenceKey key = key(context);
        var rule = rule(context);
        if (key == null || rule == null) return "";
        LocalDate actual = rule.weekendPolicy().apply(key.originalDate());
        String result = UiText.get("adjustment.rule", rule.kind().title(), rule.amount().format(), context.app().document().plan().currency());
        return actual.equals(key.originalDate()) ? result : UiText.get("adjustment.rule.shifted", result, UiFormats.date(actual));
    }
}
