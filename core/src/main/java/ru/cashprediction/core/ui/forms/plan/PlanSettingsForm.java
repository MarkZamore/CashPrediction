package ru.cashprediction.core.ui.forms.plan;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.form.ButtonSpecs;
import ru.cashprediction.core.ui.form.ButtonView;
import ru.cashprediction.core.ui.form.FieldChecks;
import ru.cashprediction.core.ui.form.FieldCodec;
import ru.cashprediction.core.ui.form.FieldSpecs;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.FormPage;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.Option;
import ru.cashprediction.core.ui.form.Presentation;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.form.FieldView;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.util.RuText;
import ru.cashprediction.core.session.WindowType;

/**
 * §6.2 PLAN_SETTINGS «Параметры плана» (⚙, 560; представление {@code DIALOG}).
 *
 * <p>Заголовок «Параметры плана «{name}»». Разделы: «План» ({@code name} — только чтение, если у плана есть файл,
 * непрозрачность 0,75, подсказка «Имя плана совпадает с именем файла. Изменить: Файл → Переименовать… (F2)»;
 * {@code currency}; {@code startDate}; {@code startBalance}); «Горизонт и подушка» ({@link HorizonFields},
 * {@code cushion}); «Цель накопления» ({@code goalTitle}, {@code goalTarget}, {@code goalDate}); «Заметка»
 * ({@code note}, 3 строки). Ошибки и предупреждения — §6.2 (в том числе «План даст около {N} строк прогноза
 * (допустимо 200 000): сократите горизонт»). Кнопки [Сохранить] (OK) [Отмена].</p>
 *
 * <p>Результат {@code Close(Plan)} — новый план; контроллер применяет его одним действием {@code undo.planSettings}
 * и показывает {@code status.msg.settings}.</p>
 */
public final class PlanSettingsForm implements FormLogic {

    /** Создаёт форму. */
    public PlanSettingsForm() {
    }

    @Override
    public FormSpec spec(FormContext context) {
        return new FormSpec("planSettings", WindowType.PLAN_SETTINGS, "", Presentation.DIALOG,
                UiText.get("form.planSettings.title", context.app().document().plan().name()), "⚙", 560,
                true, false, true, List.of(new FormPage("main", List.of(
                new FormRow.Section(UiText.get("form.planSettings.plan")),
                new FormRow.Field(FieldSpecs.focused(FieldSpecs.text("name", UiText.get("form.planSettings.name"), ""))),
                new FormRow.Field(FieldSpecs.editableChoice("currency", UiText.get("form.planSettings.currency"), currencies())),
                new FormRow.Field(FieldSpecs.date("startDate", UiText.get("form.planSettings.startDate"))),
                new FormRow.Field(FieldSpecs.money("startBalance", UiText.get("form.planSettings.startBalance"))),
                new FormRow.Section(UiText.get("form.planSettings.horizon")), HorizonFields.rows().get(0), HorizonFields.rows().get(1),
                new FormRow.Field(FieldSpecs.money("cushion", UiText.get("form.planSettings.cushion"))),
                new FormRow.Section(UiText.get("form.planSettings.goal")),
                new FormRow.Field(FieldSpecs.text("goalTitle", UiText.get("form.planSettings.goalTitle"), UiText.get("form.planSettings.goalTitle.prompt"))),
                new FormRow.Field(FieldSpecs.withPrompt(FieldSpecs.money("goalTarget", UiText.get("form.planSettings.goalTarget")), UiText.get("form.planSettings.goalTarget.prompt"))),
                new FormRow.Field(FieldSpecs.date("goalDate", UiText.get("form.planSettings.goalDate"))),
                new FormRow.Section(UiText.get("form.planSettings.note")),
                new FormRow.Field(FieldSpecs.multiline("note", UiText.get("form.planSettings.note"), 3))))),
                List.of(ButtonSpecs.ok(UiText.get("button.save")), ButtonSpecs.cancel()), "ok");
    }

    @Override
    public Map<String, String> defaults(FormContext context) {
        Plan p = context.app().document().plan();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("name", p.name()); values.put("currency", p.currency());
        values.put("startDate", FieldCodec.date(p.startDate())); values.put("startBalance", p.startBalance().formatPlain());
        values.putAll(HorizonFields.values(p.horizon())); values.put("cushion", p.cushion().formatPlain()); values.put("note", p.note());
        values.put("goalTitle", p.goal() == null ? "" : p.goal().title()); values.put("goalTarget", p.goal() == null ? "" : p.goal().target().formatPlain()); values.put("goalDate", p.goal() == null || p.goal().wishDate() == null ? "" : FieldCodec.date(p.goal().wishDate()));
        return Map.copyOf(values);
    }

    @Override
    public FormView evaluate(FormState state, FormContext context) {
        var error = check(state, context);
        Problem problem = error.map(Problem::error).orElseGet(() -> warning(state, context).map(Problem::warning).orElse(Problem.NONE));
        Map<String, FieldView> fields = new LinkedHashMap<>(HorizonFields.views(state));
        if (context.app().document().file() != null) {
            fields.put("name", new FieldView(state.value("name"), true, false, true, null, null, UiText.get("form.planSettings.name.readOnly")));
        }
        return new FormView(0, 0, "", fields, problem, Map.of("ok", error.isEmpty() ? ButtonView.ENABLED : ButtonView.DISABLED,
                "cancel", ButtonView.ENABLED), List.of(), List.of(), "", false);
    }

    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        if ("cancel".equals(buttonId)) return new FormOutcome.Close(null);
        return "ok".equals(buttonId) && check(state, context).isEmpty() ? new FormOutcome.Close(build(state, context)) : FormOutcome.stay();
    }

    /** Варианты обозначения валюты. */
    private static List<Option> currencies() { return List.of(Option.of("₽", "₽"), Option.of("$", "$"), Option.of("€", "€"), Option.of("₸", "₸"), Option.of("BYN", "BYN")); }
    /** Первая блокирующая ошибка формы. */
    private static java.util.Optional<String> check(FormState s, FormContext c) {
        String name = c.app().document().file() == null ? s.value("name") : c.app().document().plan().name();
        var basic = FieldChecks.first(PlanValidator.checkPlanName(name), s.value("currency").isBlank() ? java.util.Optional.of(UiText.get("val.currency.required")) : java.util.Optional.empty(), s.value("currency").codePointCount(0,s.value("currency").length()) > 10 ? java.util.Optional.of(UiText.get("val.currency.long")) : java.util.Optional.empty(), FieldChecks.date(UiText.get("form.planSettings.startDate"),s.value("startDate"),true), FieldChecks.money(UiText.get("form.planSettings.startBalance"),s.value("startBalance"),FieldChecks.MoneyRule.ANY));
        if (basic.isPresent()) return basic;
        var horizon = HorizonFields.error(s, FieldCodec.parseDate(s.value("startDate")).orElse(null)); if (horizon.isPresent()) return horizon;
        var cushion = FieldChecks.money(UiText.get("form.planSettings.cushion"),s.value("cushion"),FieldChecks.MoneyRule.NON_NEGATIVE); if(cushion.isPresent())return cushion;
        boolean hasGoalBits=!s.value("goalTitle").isBlank()||!s.value("goalDate").isBlank();
        if(s.value("goalTarget").isBlank() && hasGoalBits)return java.util.Optional.of(UiText.get("form.planSettings.goal.incomplete"));
        if(!s.value("goalTarget").isBlank()) { var target=FieldCodec.parseMoney(s.value("goalTarget")); if(target.isEmpty()||!target.get().isPositive())return java.util.Optional.of(UiText.get("form.planSettings.goal.positive")); }
        if(!s.value("goalDate").isBlank()&&FieldCodec.parseDate(s.value("goalDate")).isEmpty())return java.util.Optional.of(UiText.get("form.planSettings.goal.date"));
        try { if(PlanValidator.estimateRowCount(build(s,c))>PlanValidator.MAX_ROWS)return java.util.Optional.of(UiText.get("form.planSettings.tooManyRows",RuText.groupDigits(PlanValidator.estimateRowCount(build(s,c))))); } catch(RuntimeException ignored) { }
        return java.util.Optional.empty();
    }
    /** Первое неблокирующее замечание. */
    private static java.util.Optional<String> warning(FormState s, FormContext c) { var start=FieldCodec.parseDate(s.value("startDate")).orElse(null); var horizon=HorizonFields.warning(s,start); if(horizon.isPresent())return horizon; var date=FieldCodec.parseDate(s.value("goalDate")); if(start!=null&&date.isPresent()&&date.get().isBefore(start))return java.util.Optional.of(UiText.get("form.planSettings.goal.beforeStart")); if(start!=null&&!start.equals(c.app().document().plan().startDate())&&!c.app().document().plan().adjustments().isEmpty())return java.util.Optional.of(UiText.get("form.planSettings.adjustments")); return java.util.Optional.empty(); }
    /** Собирает обновлённый план одним неизменяемым объектом. */
    private static Plan build(FormState s, FormContext c) { Plan old=c.app().document().plan(); String name=c.app().document().file()==null?s.value("name"):old.name(); Money target=FieldCodec.parseMoney(s.value("goalTarget")).orElse(null); Goal goal=target==null?null:new Goal(s.value("goalTitle"),target,FieldCodec.parseDate(s.value("goalDate")).orElse(null)); return new Plan(name,s.value("note"),s.value("currency"),FieldCodec.parseDate(s.value("startDate")).orElseThrow(),FieldCodec.parseMoney(s.value("startBalance")).orElseThrow(),HorizonFields.toHorizon(s),FieldCodec.parseMoney(s.value("cushion")).orElseThrow(),goal,old.rules(),old.oneTimes(),old.adjustments(),old.rawBlocks()); }
}
