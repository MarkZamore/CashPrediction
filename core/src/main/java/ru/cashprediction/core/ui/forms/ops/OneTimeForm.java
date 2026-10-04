package ru.cashprediction.core.ui.forms.ops;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.ButtonSpecs;
import ru.cashprediction.core.ui.form.ButtonView;
import ru.cashprediction.core.ui.form.FieldChecks;
import ru.cashprediction.core.ui.form.FieldSpecs;
import ru.cashprediction.core.ui.form.FieldView;
import ru.cashprediction.core.ui.form.FormPage;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.Option;
import ru.cashprediction.core.ui.form.Orientation;
import ru.cashprediction.core.ui.form.Presentation;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.session.WindowType;

/**
 * §6.4 ONE_TIME_EDITOR «Разовая операция» (≡, 560; контекст {@code mode}, {@code txId}; представление {@code DIALOG}).
 *
 * <p>Заголовок «Новая разовая операция» / «Изменение разовой операции «{title}»». Поля: {@code date} (переданная
 * дата, иначе max(сегодня, начало)); {@code title} (подсказка «например, Премия или Ноутбук»); {@code kind} (радио,
 * по умолчанию «Расход», если вид не передан); {@code amount}; {@code category} (editableChoice); {@code note}
 * (2 строки). Фокус: при создании {@code title}, при изменении {@code amount}. Ошибки: {@code val.date.*}, «Укажите
 * название операции», {@code val.money.*} с пояснением про «Тип». Предупреждение: «Дата вне горизонта плана
 * (dd.MM.yyyy — dd.MM.yyyy): операция не попадёт в прогноз». Кнопки [Сохранить] [Отмена].</p>
 *
 * <p>Результат {@code Close(OneTimeTransaction)}; контроллер: {@code undo.oneTimeAdd} / {@code undo.oneTimeEdit},
 * статус {@code status.msg.oneTimeAdded} / {@code oneTimeChanged}.</p>
 */
public final class OneTimeForm implements FormLogic {

    /** Контекст окна: дата по умолчанию (ISO) для «Добавить разовую на dd.MM.yyyy…». */
    public static final String CONTEXT_DATE = "date";

    /** Создаёт форму. */
    public OneTimeForm() {
    }

    /**
     * Строит раскладку диалога разовой операции с датой, названием, видом, суммой,
     * категорией и заметкой, кнопками сохранения и отмены.
     * @param context окружение формы с категориями текущего плана
     * @return спецификация формы с кнопкой сохранения по умолчанию
     */
    @Override
    public FormSpec spec(FormContext context) {
        return new FormSpec("oneTime", WindowType.ONE_TIME_EDITOR, "", Presentation.DIALOG,
                UiText.get("oneTime.window"), "≡", 560, true, false, true,
                List.of(new FormPage("main", List.of(
                        new FormRow.Field(FieldSpecs.date("date", UiText.get("oneTime.date"))),
                        new FormRow.Field(FieldSpecs.focused(FieldSpecs.text("title", UiText.get("oneTime.title"), UiText.get("oneTime.title.prompt")))),
                        new FormRow.Field(FieldSpecs.radio("kind", UiText.get("oneTime.kind"), Orientation.HORIZONTAL, OpsForms.kindOptions())),
                        new FormRow.Field(FieldSpecs.money("amount", UiText.get("oneTime.amount"))),
                        new FormRow.Field(FieldSpecs.editableChoice("category", UiText.get("oneTime.category"), OpsForms.categoryOptions(context.app().document().plan().categories()))),
                        new FormRow.Field(FieldSpecs.multiline("note", UiText.get("oneTime.note"), 2))))),
                List.of(ButtonSpecs.ok(UiText.get("button.save")), ButtonSpecs.cancel()), ButtonSpecs.OK);
    }

    /**
     * Возвращает поля найденной операции в режиме изменения либо начальные значения новой.
     * Для новой операции использует корректную дату из контекста, иначе более позднюю
     * из сегодняшней даты и начала плана; вид по умолчанию задаёт как расход.
     * @param context окружение с режимом, целью изменения и возможными датой и видом
     * @return значения по идентификаторам полей; суммы и даты в канонической форме
     */
    @Override
    public Map<String, String> defaults(FormContext context) {
        OneTimeTransaction existing = existing(context);
        LocalDate date = OpsForms.date(context.contextValue(CONTEXT_DATE));
        LocalDate defaultDate = date == null ? OpsForms.effectiveToday(context.app().today(), context.app().document().plan().startDate()) : date;
        if (existing != null) {
            return Map.of("date", ru.cashprediction.core.ui.form.FieldCodec.date(existing.date()), "title", existing.title(),
                    "kind", existing.kind().name(), "amount", ru.cashprediction.core.ui.form.FieldCodec.money(existing.amount()),
                    "category", existing.category(), "note", existing.note());
        }
        return Map.of("date", ru.cashprediction.core.ui.form.FieldCodec.date(defaultDate), "title", "", "kind",
                OpsForms.enumValue(Kind.class, context.contextValue("kind"), Kind.EXPENSE).name(), "amount", "", "category", "", "note", "");
    }

    /**
     * Проверяет обязательные дату, название и положительную сумму, формирует заголовок и поля.
     * Дата вне горизонта даёт предупреждение и допускает сохранение; ошибка отключает сохранение.
     * @param state введённые значения
     * @param context окружение с текущим планом и целью изменения
     * @return модель формы со строкой проблем и доступностью кнопки сохранения
     */
    @Override
    public FormView evaluate(FormState state, FormContext context) {
        Optional<String> error = FieldChecks.first(
                FieldChecks.date(UiText.get("oneTime.date"), state.value("date"), true),
                FieldChecks.requiredText(UiText.get("oneTime.title"), state.value("title")),
                FieldChecks.money(UiText.get("oneTime.amount"), state.value("amount"), FieldChecks.MoneyRule.REQUIRED_POSITIVE));
        LocalDate date = OpsForms.date(state.value("date"));
        String warning = error.isPresent() || date == null || !date.isBefore(context.app().document().plan().startDate())
                && !date.isAfter(context.app().document().plan().endDate()) ? ""
                : UiText.get("oneTime.warning.outside", ru.cashprediction.core.ui.text.UiFormats.date(context.app().document().plan().startDate()),
                        ru.cashprediction.core.ui.text.UiFormats.date(context.app().document().plan().endDate()));
        Map<String, FieldView> fields = OpsForms.values(state, "date", "title", "kind", "amount", "category", "note");
        boolean edit = existing(context) != null;
        fields.put("title", new FieldView(state.value("title"), true, true, false, null, null, null));
        fields.put("amount", new FieldView(state.value("amount"), true, true, false, null, null, null));
        return new FormView(0, 0, edit ? UiText.get("oneTime.header.edit", existing(context).title()) : UiText.get("oneTime.header.new"), fields,
                OpsForms.problem(error, warning), Map.of(ButtonSpecs.OK, error.isPresent() ? ButtonView.DISABLED : ButtonView.ENABLED), List.of(), List.of(), "", false);
    }

    /**
     * Отменяет ввод либо после проверки возвращает разовую операцию для применения контроллером.
     * Сохраняет идентификатор найденной операции, для новой берёт следующий идентификатор плана.
     * Сам план не изменяет; неизвестная кнопка оставляет форму открытой.
     * @param buttonId идентификатор кнопки
     * @param state введённые значения
     * @param context окружение с текущим планом и целью изменения
     * @return закрытие без результата при отмене, операция при успехе либо продолжение ввода
     */
    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        if (ButtonSpecs.CANCEL.equals(buttonId)) {
            return new FormOutcome.Close(null);
        }
        if (!ButtonSpecs.OK.equals(buttonId)) {
            return FormOutcome.stay();
        }
        FormView view = evaluate(state, context);
        if (view.problem().severity() == Problem.Severity.ERROR) {
            return new FormOutcome.Stay(view.problem());
        }
        OneTimeTransaction existing = existing(context);
        TxId id = existing == null ? context.app().document().plan().nextTxId() : existing.id();
        return new FormOutcome.Close(new OneTimeTransaction(id, OpsForms.date(state.value("date")), state.value("title"),
                OpsForms.enumValue(Kind.class, state.value("kind"), Kind.EXPENSE), OpsForms.money(state.value("amount")),
                state.value("category"), state.value("note")));
    }

    private OneTimeTransaction existing(FormContext context) {
        if (!WindowType.MODE_EDIT.equals(context.contextValue(WindowType.CONTEXT_MODE))) {
            return null;
        }
        String id = context.contextValue(WindowType.CONTEXT_TX_ID);
        try {
            return context.app().document().plan().findOneTime(new TxId(id)).orElse(null);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
