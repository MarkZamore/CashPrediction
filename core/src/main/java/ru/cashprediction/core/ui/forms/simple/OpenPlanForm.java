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
    /**
     * Создаёт форму с неизменяемой копией списка планов в переданном порядке.
     * @param plans доступные планы; список и его элементы не должны быть {@code null}
     * @throws NullPointerException если список или один из его элементов равен {@code null}
     */
    public OpenPlanForm(List<PlanFileInfo> plans) { this.plans = List.copyOf(plans); }
    /** @return неизменяемый список планов, зафиксированный при создании формы */
    public List<PlanFileInfo> plans() { return plans; }
    /**
     * Создаёт раскладку списка с выбором плана или открытием отдельного файла.
     * Заголовок зависит от наличия планов и папки из контекста.
     * @param context окружение формы
     * @return раскладка с кнопками открытия и отмены
     */
    @Override public FormSpec spec(FormContext context) { String key = plans.isEmpty() ? "dialog.openPlan.empty" : "dialog.openPlan.title"; return new FormSpec(PURPOSE, WindowType.CHOICE, PURPOSE, Presentation.LIST_CHOICE, UiText.get(key, context.app().plansFolder()), "", 560, true, false, true, List.of(new FormPage("main", List.of(new FormRow.Field(FieldSpecs.list("value", UiText.get("dialog.openPlan.value"), 8, options(context)))))), List.of(ButtonSpecs.of("open", UiText.get("button.open"), ButtonRole.OK), ButtonSpecs.cancel()), "open"); }
    /**
     * Выбирает текущий открытый файл, если он есть в списке; иначе первый план или выбор отдельного файла.
     * @param context окружение с текущим документом
     * @return начальное значение поля {@code value}: строка пути либо {@link #FROM_FILE}
     */
    @Override public Map<String, String> defaults(FormContext context) { String current = context.app().document().fileOptional().map(Object::toString).orElse(""); for (PlanFileInfo info : plans) if (info.path().toString().equals(current)) return Map.of("value", info.path().toString()); return Map.of("value", plans.isEmpty() ? FROM_FILE : plans.getFirst().path().toString()); }
    /**
     * Заменяет прежнее сохранённое имя плана строкой пути первого плана с таким именем.
     * Уже известный путь, выбор отдельного файла, отсутствующее или неизвестное значение остаются прежними.
     * @param values значения полей из снимка
     * @param context окружение формы; при нормализации не используется
     * @return исходная карта либо неизменяемая копия с заменённым полем {@code value}
     */
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
    /**
     * Строит доступный список вариантов с текущим выбранным значением без диагностики ошибок ввода.
     * @param state текущее значение выбора
     * @param context окружение для подписей и отметки открытого плана
     * @return модель списка; ревизию устанавливает сеанс формы
     */
    @Override public FormView evaluate(FormState state, FormContext context) { return new FormView(0, 0, "", Map.of("value", new FieldView(state.value("value"), true, true, false, null, options(context), null)), Problem.NONE, Map.of(), List.of(), List.of(), "", false); }
    /**
     * Закрывает форму: отмена возвращает {@code null}, любая другая кнопка подтверждает текущий выбор.
     * @param buttonId идентификатор нажатой кнопки
     * @param state текущее значение поля {@code value}
     * @param context окружение формы; при обработке кнопки не используется
     * @return закрытие с путём выбранного плана, маркером {@link #FROM_FILE} или {@code null}, если путь не найден
     */
    @Override public FormOutcome onButton(String buttonId, FormState state, FormContext context) { return ButtonSpecs.CANCEL.equals(buttonId) ? new FormOutcome.Close(null) : new FormOutcome.Close(FROM_FILE.equals(state.value("value")) ? FROM_FILE : plans.stream().filter(p -> p.path().toString().equals(state.value("value"))).findFirst().map(PlanFileInfo::path).orElse(null)); }
    /**
     * Подтверждает выбор при активации списка {@code value}; для остальных полей оставляет форму открытой.
     * @param fieldId идентификатор активированного поля
     * @param index индекс элемента; выбор берётся из состояния, поэтому индекс не используется
     * @param state состояние с уже выбранным значением
     * @param context окружение формы
     * @return результат кнопки открытия либо действие сохранения текущей формы
     */
    @Override public FormOutcome onFieldActivated(String fieldId, int index, FormState state, FormContext context) { return "value".equals(fieldId) ? onButton("open", state, context) : FormOutcome.stay(); }
    private List<Option> options(FormContext context) { List<Option> out = new ArrayList<>(); String current = context.app().document().fileOptional().map(Object::toString).orElse(""); for (PlanFileInfo info : plans) out.add(new Option(info.path().toString(), info.name() + (info.path().toString().equals(current) ? UiText.get("dialog.openPlan.open") : ""), UiFormats.dateTime(info.lastModified().toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()), false)); out.add(Option.of(FROM_FILE, UiText.get("dialog.openPlan.fromFile"))); return List.copyOf(out); }
}
