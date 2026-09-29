package ru.cashprediction.core.ui.forms.simple;

import java.util.List;
import java.util.Map;
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
import ru.cashprediction.core.ui.text.UiText;

/** Логика выбора валюты плана. */
public final class ChoiceForms {
    public static final String PURPOSE_CURRENCY = "currency";
    public static final String CUSTOM = "custom";
    private ChoiceForms() { }
    public static FormLogic currency() { return new Currency(); }
    private static final class Currency implements FormLogic {
        private static List<Option> options() { return List.of(Option.of("₽", "₽"), Option.of("$", "$"), Option.of("€", "€"), Option.of("₸", "₸"), Option.of("BYN", "BYN"), Option.of(CUSTOM, UiText.get("dialog.currency.custom"))); }
        @Override public FormSpec spec(FormContext context) { return new FormSpec(PURPOSE_CURRENCY, WindowType.CHOICE, PURPOSE_CURRENCY, Presentation.CHOICE, UiText.get("dialog.currency.title", context.app().document().plan().name(), context.app().document().plan().currency()), "", 460, true, false, true, List.of(new FormPage("main", List.of(new FormRow.Field(FieldSpecs.choice("value", UiText.get("dialog.currency.value"), options()))))), List.of(ButtonSpecs.of("choose", UiText.get("button.choose"), ButtonRole.OK), ButtonSpecs.cancel()), "choose"); }
        @Override public Map<String, String> defaults(FormContext context) { String current = context.app().document().plan().currency(); return Map.of("value", options().stream().anyMatch(o -> o.value().equals(current)) ? current : CUSTOM); }
        @Override public FormView evaluate(FormState state, FormContext context) { return new FormView(0, 0, "", Map.of("value", new FieldView(state.value("value"), true, true, false, null, options(), null)), Problem.NONE, Map.of(), List.of(), List.of(), "", false); }
        @Override public FormOutcome onButton(String buttonId, FormState state, FormContext context) { return ButtonSpecs.CANCEL.equals(buttonId) ? new FormOutcome.Close(null) : new FormOutcome.Close(state.value("value")); }
    }
}
