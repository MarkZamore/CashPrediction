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
    /**
     * Выбранные параметры, передаваемые контроллеру для экспорта; запись сама файл не создаёт.
     *
     * @param separator значение разделителя из формы: {@code ;}, {@code ,} или обозначение {@code TAB}
     * @param bom добавлять ли метку BOM
     * @param range значение диапазона из формы: {@code PERIOD} или {@code ALL}
     */
    public record Choice(String separator, boolean bom, String range) { }
    /**
     * Описывает выбор разделителя, BOM и диапазона экспорта с локализованными границами дат.
     * Конец текущего периода оставляет пустым, если прогноз недоступен.
     *
     * @param context контекст с планом, прогнозом и текущей датой
     * @return спецификация параметров экспорта с кнопками экспорта и отмены
     */
    @Override public FormSpec spec(FormContext context) {
        String periodEnd = context.app().document().forecastAvailable() ? UiFormats.date(context.app().document().forecast().endDate()) : "";
        return new FormSpec("csvExport", WindowType.CSV_EXPORT, "", Presentation.DIALOG, UiText.get("dialog.csv.title"), "⇩", 560, true, false, true,
                List.of(new FormPage("main", List.of(new FormRow.Field(FieldSpecs.radio("separator", UiText.get("dialog.csv.separator"), Orientation.VERTICAL, List.of(Option.of(";", UiText.get("dialog.csv.semicolon")), Option.of(",", UiText.get("dialog.csv.comma")), Option.of("TAB", UiText.get("dialog.csv.tab"))))), new FormRow.Field(FieldSpecs.wide(FieldSpecs.check("bom", UiText.get("dialog.csv.bom")))), new FormRow.Field(FieldSpecs.radio("range", UiText.get("dialog.csv.range"), Orientation.VERTICAL, List.of(Option.of("PERIOD", UiText.get("dialog.csv.period", UiFormats.date(context.app().today()), periodEnd)), Option.of("ALL", UiText.get("dialog.csv.all", UiFormats.date(context.app().document().plan().startDate()), UiFormats.date(context.app().document().plan().endDate())))))), new FormRow.Hint("columns", UiText.get("dialog.csv.columns"))))), List.of(ButtonSpecs.of("export", UiText.get("button.export"), ButtonRole.OK), ButtonSpecs.cancel()), "export");
    }
    /**
     * Задаёт начальный экспорт текущего периода с точкой с запятой и включённой BOM.
     *
     * @param context контекст формы
     * @return неизменяемая карта начальных параметров
     */
    @Override public Map<String, String> defaults(FormContext context) { return Map.of("separator", ";", "bom", "true", "range", "PERIOD"); }
    /**
     * Переносит выбранные параметры в представление без дополнительной проверки их значений.
     *
     * @param state текущие значения разделителя, BOM и диапазона
     * @param context контекст формы
     * @return представление без проблем и переопределений доступности кнопок
     */
    @Override public FormView evaluate(FormState state, FormContext context) { return new FormView(0, 0, "", Map.of("separator", FieldView.of(state.value("separator")), "bom", FieldView.of(state.value("bom")), "range", FieldView.of(state.value("range"))), Problem.NONE, Map.of(), List.of(), List.of(), "", false); }
    /**
     * Закрывает форму с параметрами для любой кнопки, кроме отмены, без выполнения экспорта.
     * BOM включается только для точного значения {@code true}; разделитель и диапазон передаются как есть.
     *
     * @param buttonId идентификатор нажатой кнопки
     * @param state выбранные параметры экспорта
     * @param context контекст формы
     * @return закрытие с {@link Choice} либо с {@code null} при отмене
     */
    @Override public FormOutcome onButton(String buttonId, FormState state, FormContext context) { return ButtonSpecs.CANCEL.equals(buttonId) ? new FormOutcome.Close(null) : new FormOutcome.Close(new Choice(state.value("separator"), "true".equals(state.value("bom")), state.value("range"))); }
}
