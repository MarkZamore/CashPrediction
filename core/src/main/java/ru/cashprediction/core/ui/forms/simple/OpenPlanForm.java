package ru.cashprediction.core.ui.forms.simple;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.io.PlanFileInfo;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.ButtonRole;
import ru.cashprediction.core.ui.form.ButtonSpecs;
import ru.cashprediction.core.ui.form.FieldSpecs;
import ru.cashprediction.core.ui.form.FieldView;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormPage;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.Option;
import ru.cashprediction.core.ui.form.Presentation;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;

/** Список планов текущей папки. */
public final class OpenPlanForm implements FormLogic {
    public static final String PURPOSE = "openPlan";
    public static final String FROM_FILE = "fromFile";
    private final List<PlanFileInfo> plans;
    public OpenPlanForm(List<PlanFileInfo> plans) { this.plans = List.copyOf(plans); }
    public List<PlanFileInfo> plans() { return plans; }
    @Override public FormSpec spec(FormContext context) { String key = plans.isEmpty() ? "dialog.openPlan.empty" : "dialog.openPlan.title"; return new FormSpec(PURPOSE, WindowType.CHOICE, PURPOSE, Presentation.LIST_CHOICE, UiText.get(key, context.app().plansFolder()), "", 560, true, false, true, List.of(new FormPage("main", List.of(new FormRow.Field(FieldSpecs.list("value", UiText.get("dialog.openPlan.value"), 8, options(context)))))), List.of(ButtonSpecs.of("open", UiText.get("button.open"), ButtonRole.OK), ButtonSpecs.cancel()), "open"); }
    @Override public Map<String, String> defaults(FormContext context) { String current = context.app().document().fileOptional().map(Object::toString).orElse(""); for (PlanFileInfo info : plans) if (info.path().toString().equals(current)) return Map.of("value", info.path().toString()); return Map.of("value", plans.isEmpty() ? FROM_FILE : plans.getFirst().path().toString()); }
    @Override public Map<String, String> normalizeRestoredValues(Map<String, String> values, FormContext context) {
        String value = values.get("value");
        if (value == null || FROM_FILE.equals(value) || plans.stream().anyMatch(plan -> plan.path().toString().equals(value))) {
            return values;
        }
        for (PlanFileInfo plan : plans) {
            if (plan.name().equals(value)) {
                java.util.LinkedHashMap<String, String> normalized = new java.util.LinkedHashMap<>(values);
                normalized.put("value", plan.path().toString());
                return Map.copyOf(normalized);
            }
        }
        return values;
    }
    @Override public FormView evaluate(FormState state, FormContext context) { return new FormView(0, 0, "", Map.of("value", new FieldView(state.value("value"), true, true, false, null, options(context), null)), Problem.NONE, Map.of(), List.of(), List.of(), "", false); }
    @Override public FormOutcome onButton(String buttonId, FormState state, FormContext context) { return ButtonSpecs.CANCEL.equals(buttonId) ? new FormOutcome.Close(null) : new FormOutcome.Close(FROM_FILE.equals(state.value("value")) ? FROM_FILE : plans.stream().filter(p -> p.path().toString().equals(state.value("value"))).findFirst().map(PlanFileInfo::path).orElse(null)); }
    @Override public FormOutcome onFieldActivated(String fieldId, int index, FormState state, FormContext context) { return "value".equals(fieldId) ? onButton("open", state, context) : FormOutcome.stay(); }
    private List<Option> options(FormContext context) { List<Option> out = new ArrayList<>(); String current = context.app().document().fileOptional().map(Object::toString).orElse(""); for (PlanFileInfo info : plans) out.add(new Option(info.path().toString(), info.name() + (info.path().toString().equals(current) ? UiText.get("dialog.openPlan.open") : ""), UiFormats.dateTime(info.lastModified().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()), false)); out.add(Option.of(FROM_FILE, UiText.get("dialog.openPlan.fromFile"))); return List.copyOf(out); }
}
