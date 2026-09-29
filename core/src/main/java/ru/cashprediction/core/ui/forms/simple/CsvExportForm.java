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
import ru.cashprediction.core.ui.form.Orientation;
import ru.cashprediction.core.ui.form.Presentation;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;

/** Форма параметров экспорта CSV. */
public final class CsvExportForm implements FormLogic {
    public record Choice(String separator, boolean bom, String range) { }
    @Override public FormSpec spec(FormContext context) {
        String periodEnd = context.app().document().forecastAvailable() ? UiFormats.date(context.app().document().forecast().endDate()) : "";
        return new FormSpec("csvExport", WindowType.CSV_EXPORT, "", Presentation.DIALOG, UiText.get("dialog.csv.title"), "⇩", 560, true, false, true,
                List.of(new FormPage("main", List.of(new FormRow.Field(FieldSpecs.radio("separator", UiText.get("dialog.csv.separator"), Orientation.VERTICAL, List.of(Option.of(";", UiText.get("dialog.csv.semicolon")), Option.of(",", UiText.get("dialog.csv.comma")), Option.of("TAB", UiText.get("dialog.csv.tab"))))), new FormRow.Field(FieldSpecs.wide(FieldSpecs.check("bom", UiText.get("dialog.csv.bom")))), new FormRow.Field(FieldSpecs.radio("range", UiText.get("dialog.csv.range"), Orientation.VERTICAL, List.of(Option.of("PERIOD", UiText.get("dialog.csv.period", UiFormats.date(context.app().today()), periodEnd)), Option.of("ALL", UiText.get("dialog.csv.all", UiFormats.date(context.app().document().plan().startDate()), UiFormats.date(context.app().document().plan().endDate())))))), new FormRow.Hint("columns", UiText.get("dialog.csv.columns"))))), List.of(ButtonSpecs.of("export", UiText.get("button.export"), ButtonRole.OK), ButtonSpecs.cancel()), "export");
    }
    @Override public Map<String, String> defaults(FormContext context) { return Map.of("separator", ";", "bom", "true", "range", "PERIOD"); }
    @Override public FormView evaluate(FormState state, FormContext context) { return new FormView(0, 0, "", Map.of("separator", FieldView.of(state.value("separator")), "bom", FieldView.of(state.value("bom")), "range", FieldView.of(state.value("range"))), Problem.NONE, Map.of(), List.of(), List.of(), "", false); }
    @Override public FormOutcome onButton(String buttonId, FormState state, FormContext context) { return ButtonSpecs.CANCEL.equals(buttonId) ? new FormOutcome.Close(null) : new FormOutcome.Close(new Choice(state.value("separator"), "true".equals(state.value("bom")), state.value("range"))); }
}
