package ru.cashprediction.core.ui.forms.ops;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurrenceKind;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.recurrence.Occurrence;
import ru.cashprediction.core.recurrence.OccurrenceGenerator;
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
import ru.cashprediction.core.ui.form.Orientation;
import ru.cashprediction.core.ui.form.Presentation;
import ru.cashprediction.core.ui.form.PreviewItem;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.DialogWidth;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/**
 * §6.3 RULE_EDITOR «Регулярная операция» (↻, 880; контекст {@code mode}, {@code ruleId}; представление {@code DIALOG}).
 *
 * <p>Заголовок «Новый регулярный доход» / «Новый регулярный расход» (меняется вместе с «Тип») или «Изменение
 * регулярной операции «{title}»». Раскладка: форма | вертикальный разделитель | колонка предпросмотра 300
 * ({@code FormRow.SideColumn}). Поля 1–14 таблицы §6.3 ({@code title}, {@code kind}, {@code amount},
 * {@code category}, {@code recurrenceKind}, {@code dayOfMonth}, {@code everyN}, {@code weekday}, {@code monthDay}
 * (MONTH_DAY), {@code fromEnabled}+{@code from}, {@code untilEnabled}+{@code until}, {@code weekendPolicy},
 * {@code enabled}, {@code note}) с видимостью по виду повтора.</p>
 *
 * <p>Предпросмотр: жирно «Ближайшие даты», 6 дат с max(сегодня, начало), элемент «пн, 05.10.2026», «  ⇄ с сб
 * 03.10.2026», «  ✎ корректировка»; пустой — «Заполните форму — здесь появятся даты» или «У правила нет ближайших
 * дат: проверьте «Начало» и «Окончание»». Кнопка «Скорректировать выбранную дату…» (только режим edit с выбранной
 * датой), двойной щелчок и контекстное меню («Скорректировать эту дату…») → {@code OpenChild(ADJUSTMENT_EDITOR)}.</p>
 *
 * <p>Ошибки и предупреждения — §6.3 (включая «{N корректировка перестанет / …} совпадать с датами правила»).
 * Кнопки [Сохранить] [Отмена]. Результат {@code Close(RecurringRule)}; контроллер: {@code undo.ruleAdd} /
 * {@code undo.ruleEdit}, статус {@code status.msg.ruleAdded} / {@code ruleChanged}.</p>
 */
public final class RuleEditorForm implements FormLogic {

    /** Создаёт форму (режим, id правила и вид по умолчанию — в контексте окна). */
    public RuleEditorForm() {
    }

    /** Предпросмотр корректировок обновляется после правки дочернего окна, сохраняя введённые поля родителя. */
    @Override public boolean reevaluateOnDocumentChange() {
        return true;
    }

    /**
     * Строит диалог регулярной операции с полями повтора, границами действия, настройками
     * выходных и боковой колонкой ближайших дат с кнопкой корректировки выбранного события.
     * @param context окружение с категориями текущего плана
     * @return спецификация редактора с сохранением по умолчанию и отменой
     */
    @Override
    public FormSpec spec(FormContext context) {
        return new FormSpec("ruleEditor", WindowType.RULE_EDITOR, "", Presentation.DIALOG, UiText.get("rule.window"), "↻",
                DialogWidth.RULE.px(), true, true, true, List.of(new FormPage("main", List.of(
                new FormRow.Field(FieldSpecs.focused(FieldSpecs.text("title", UiText.get("rule.title"), UiText.get("rule.title.prompt")))),
                new FormRow.Field(FieldSpecs.radio("kind", UiText.get("rule.kind"), Orientation.HORIZONTAL, OpsForms.kindOptions())),
                new FormRow.Field(FieldSpecs.money("amount", UiText.get("rule.amount"))),
                new FormRow.Field(FieldSpecs.editableChoice("category", UiText.get("rule.category"), OpsForms.categoryOptions(context.app().document().plan().categories()))),
                new FormRow.Section(UiText.get("rule.section.recurrence")),
                new FormRow.Field(FieldSpecs.choice("recurrenceKind", UiText.get("rule.recurrenceKind"), OpsForms.recurrenceOptions())),
                new FormRow.Field(FieldSpecs.withTooltip(FieldSpecs.spinner("dayOfMonth", UiText.get("rule.dayOfMonth"), 1, Recurrence.MAX_DAY_OF_MONTH), UiText.get("rule.dayOfMonth.tip"))),
                new FormRow.Field(FieldSpecs.spinner("everyN", UiText.get("rule.everyN"), 1, Recurrence.MAX_EVERY_DAYS)),
                new FormRow.Field(FieldSpecs.choice("weekday", UiText.get("rule.weekday"), OpsForms.weekdayOptions())),
                new FormRow.Field(FieldSpecs.monthDay("monthDay", UiText.get("rule.monthDay"), UiText.get("rule.monthDay.prompt"))),
                new FormRow.Inline(UiText.get("rule.from"), List.of(FieldSpecs.check("fromEnabled", UiText.get("rule.from.enabled")), FieldSpecs.date("from", ""))),
                new FormRow.Inline(UiText.get("rule.until"), List.of(FieldSpecs.check("untilEnabled", UiText.get("rule.until.enabled")), FieldSpecs.date("until", ""))),
                new FormRow.Field(FieldSpecs.choice("weekendPolicy", UiText.get("rule.weekend"), OpsForms.weekendOptions())),
                new FormRow.Field(FieldSpecs.wide(FieldSpecs.check("enabled", UiText.get("rule.enabled")))),
                new FormRow.Field(FieldSpecs.multiline("note", UiText.get("rule.note"), 2)),
                new FormRow.SideColumn(UiText.get("rule.preview"), FieldSpecs.preview("dates", ""),
                        List.of(new FormRow.FormButtonSpec("adjustSelected", UiText.get("button.adjustSelected"), UiText.get("rule.adjust.tip"))), true)))),
                List.of(ButtonSpecs.ok(UiText.get("button.save")), ButtonSpecs.cancel()), ButtonSpecs.OK);
    }

    /**
     * Возвращает поля найденного правила для изменения либо значения новой регулярной операции.
     * Новое правило включено, имеет ежемесячный повтор и день более поздней из сегодняшней
     * даты и начала плана; ограничения начала и окончания выключены, сдвиг выходных отсутствует.
     * Вид берёт из контекста, по умолчанию задаёт расход.
     * @param context окружение с режимом, идентификатором правила и возможным видом операции
     * @return значения по идентификаторам полей в канонической форме
     */
    @Override
    public Map<String, String> defaults(FormContext context) {
        RecurringRule existing = existing(context);
        LocalDate anchor = OpsForms.effectiveToday(context.app().today(), context.app().document().plan().startDate());
        if (existing != null) {
            return ruleValues(existing);
        }
        Kind kind = OpsForms.enumValue(Kind.class, context.contextValue("kind"), Kind.EXPENSE);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", ""); values.put("kind", kind.name()); values.put("amount", ""); values.put("category", "");
        values.put("recurrenceKind", RecurrenceKind.MONTHLY.name()); values.put("dayOfMonth", Integer.toString(anchor.getDayOfMonth()));
        values.put("everyN", "1"); values.put("weekday", anchor.getDayOfWeek().name()); values.put("monthDay", FieldCodec.monthDay(MonthDay.from(anchor)));
        values.put("fromEnabled", "false"); values.put("from", FieldCodec.date(context.app().document().plan().startDate()));
        values.put("untilEnabled", "false"); values.put("until", ""); values.put("weekendPolicy", WeekendPolicy.NONE.name());
        values.put("enabled", "true"); values.put("note", "");
        return Map.copyOf(values);
    }

    /**
     * Проверяет название, положительную сумму, повтор и включённые границы дат.
     * Настраивает видимость полей повтора и доступность дат по флажкам, вычисляет предупреждение
     * и до шести ближайших событий. Сохранение доступно без ошибки; корректировка выбранной
     * даты доступна только для существующего правила и выбираемого элемента предпросмотра.
     * @param state введённые значения и индекс выбранной даты
     * @param context окружение с текущим планом и целью изменения
     * @return модель редактора с заголовком, строкой проблем, кнопками и предпросмотром
     */
    @Override
    public FormView evaluate(FormState state, FormContext context) {
        RecurrenceKind recurrenceKind = OpsForms.enumValue(RecurrenceKind.class, state.value("recurrenceKind"), RecurrenceKind.MONTHLY);
        Kind kind = OpsForms.enumValue(Kind.class, state.value("kind"), Kind.EXPENSE);
        Optional<String> error = validate(state, recurrenceKind, context);
        RecurringRule rule = error.isPresent() ? null : OpsForms.rule(ruleId(context), state, context.app().document().plan().startDate());
        String warning = error.isPresent() ? "" : warning(rule, state, context);
        Map<String, FieldView> fields = OpsForms.values(state, "title", "kind", "amount", "category", "recurrenceKind", "dayOfMonth", "everyN",
                "weekday", "monthDay", "fromEnabled", "from", "untilEnabled", "until", "weekendPolicy", "enabled", "note");
        fields.put("dayOfMonth", new FieldView(state.value("dayOfMonth"), recurrenceKind == RecurrenceKind.MONTHLY, true, false, null, null, null));
        fields.put("everyN", new FieldView(state.value("everyN"), recurrenceKind != RecurrenceKind.YEARLY, true, false,
                recurrenceKind == RecurrenceKind.MONTHLY ? UiText.get("rule.everyN.month") : recurrenceKind == RecurrenceKind.WEEKLY ? UiText.get("rule.everyN.week") : UiText.get("rule.everyN.day"), null, null));
        fields.put("weekday", new FieldView(state.value("weekday"), recurrenceKind == RecurrenceKind.WEEKLY, true, false, null, null, null));
        fields.put("monthDay", new FieldView(state.value("monthDay"), recurrenceKind == RecurrenceKind.YEARLY, true, false, null, null, null));
        boolean fromEnabled = FieldCodec.parseBoolean(state.value("fromEnabled"));
        boolean untilEnabled = FieldCodec.parseBoolean(state.value("untilEnabled"));
        fields.put("from", new FieldView(state.value("from"), true, fromEnabled, false, null, null, null));
        fields.put("until", new FieldView(state.value("until"), true, untilEnabled, false, null, null, null));
        List<PreviewItem> preview = preview(rule, context);
        boolean editable = existing(context) != null && state.previewIndex() >= 0 && state.previewIndex() < preview.size() && preview.get(state.previewIndex()).selectable();
        Map<String, ButtonView> buttons = Map.of(ButtonSpecs.OK, error.isPresent() ? ButtonView.DISABLED : ButtonView.ENABLED,
                "adjustSelected", new ButtonView(editable, true, null, existing(context) == null ? UiText.get("rule.adjust.tip") : null));
        String header = existing(context) == null ? UiText.get(kind == Kind.INCOME ? "rule.header.newIncome" : "rule.header.newExpense")
                : UiText.get("rule.header.edit", existing(context).title());
        return new FormView(0, 0, header, fields, OpsForms.problem(error, warning), buttons, List.of(), preview, "", false);
    }

    /**
     * Обрабатывает отмену, открытие корректировки выбранной даты или сохранение проверенного правила.
     * При сохранении возвращает правило с прежним идентификатором либо следующим идентификатором
     * плана для применения контроллером. Неизвестная кнопка оставляет форму открытой; план не меняется.
     * @param buttonId идентификатор кнопки
     * @param state введённые значения и выбор предпросмотра
     * @param context окружение с текущим планом и целью изменения
     * @return закрытие, продолжение ввода либо запрос дочернего редактора корректировки
     */
    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        if (ButtonSpecs.CANCEL.equals(buttonId)) {
            return new FormOutcome.Close(null);
        }
        if ("adjustSelected".equals(buttonId)) {
            return onPreview(state.previewIndex(), true, state, context);
        }
        if (!ButtonSpecs.OK.equals(buttonId)) {
            return FormOutcome.stay();
        }
        FormView view = evaluate(state, context);
        if (view.problem().severity() == Problem.Severity.ERROR) {
            return new FormOutcome.Stay(view.problem());
        }
        return new FormOutcome.Close(OpsForms.rule(ruleId(context), state, context.app().document().plan().startDate()));
    }

    /**
     * Для активированной даты существующего правила запрашивает дочерний редактор корректировки.
     * Сопоставляет индекс с предпросмотром текущих введённых значений и передаёт исходную,
     * а не сдвинутую дату события. Обычный выбор, неверный индекс, невыбираемый элемент
     * или новое правило не открывают дочернее окно.
     * @param index индекс даты в предпросмотре, начиная с нуля
     * @param activated признак активации, а не одиночного выбора
     * @param state введённые значения правила
     * @param context окружение с текущим планом и идентификатором родительского окна
     * @return запрос модального дочернего окна с временным идентификатором либо продолжение ввода
     */
    @Override
    public FormOutcome onPreview(int index, boolean activated, FormState state, FormContext context) {
        if (!activated || existing(context) == null) {
            return FormOutcome.stay();
        }
        RecurringRule rule = OpsForms.rule(ruleId(context), state, context.app().document().plan().startDate());
        List<PreviewItem> items = preview(rule, context);
        if (index < 0 || index >= items.size() || !items.get(index).selectable()) {
            return FormOutcome.stay();
        }
        List<Occurrence> occurrences = occurrences(rule, context);
        if (index >= occurrences.size()) {
            return FormOutcome.stay();
        }
        Occurrence occurrence = occurrences.get(index);
        Map<String, String> childContext = Map.of(WindowType.CONTEXT_RULE_ID, rule.id().value(), WindowType.CONTEXT_ORIGINAL_DATE,
                FieldCodec.date(occurrence.nominal()));
        return new FormOutcome.OpenChild(new WindowState(FormOutcome.OpenChild.PENDING_ID, WindowType.ADJUSTMENT_EDITOR, true,
                context.windowId(), null, childContext, Map.of()));
    }

    private Optional<String> validate(FormState state, RecurrenceKind kind, FormContext context) {
        Optional<String> base = FieldChecks.first(FieldChecks.requiredText(UiText.get("rule.title"), state.value("title")),
                FieldChecks.money(UiText.get("rule.amount"), state.value("amount"), FieldChecks.MoneyRule.REQUIRED_POSITIVE));
        if (base.isPresent()) return base;
        if (kind == RecurrenceKind.YEARLY) {
            if (state.value("monthDay").isBlank()) return Optional.of(UiText.get("rule.error.monthDay.required"));
            if (OpsForms.monthDay(state.value("monthDay")) == null) return Optional.of(UiText.get("rule.error.monthDay.invalid", state.value("monthDay")));
        }
        if (OpsForms.recurrence(state, context.app().document().plan().startDate()) == null) return Optional.of(UiText.get("rule.error.recurrence"));
        boolean fromEnabled = FieldCodec.parseBoolean(state.value("fromEnabled"));
        boolean untilEnabled = FieldCodec.parseBoolean(state.value("untilEnabled"));
        if (fromEnabled && OpsForms.date(state.value("from")) == null) return Optional.of(UiText.get("rule.error.from"));
        if (untilEnabled && OpsForms.date(state.value("until")) == null) return Optional.of(UiText.get("rule.error.until"));
        LocalDate from = OpsForms.date(state.value("from"));
        LocalDate until = OpsForms.date(state.value("until"));
        return fromEnabled && untilEnabled && from != null && until != null && until.isBefore(from)
                ? Optional.of(UiText.get("rule.error.range")) : Optional.empty();
    }

    private String warning(RecurringRule rule, FormState state, FormContext context) {
        if (rule == null) return "";
        LocalDate start = context.app().document().plan().startDate();
        if (rule.recurrence().needsAnchor() && !FieldCodec.parseBoolean(state.value("fromEnabled"))) {
            return UiText.get("rule.warning.anchor", UiFormats.date(start));
        }
        try {
            if (OccurrenceGenerator.nominalDates(rule, start, context.app().document().plan().endDate()).isEmpty()) {
                return UiText.get("rule.warning.none", UiFormats.date(start), UiFormats.date(context.app().document().plan().endDate()));
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        if (existing(context) != null) {
            // Сверяем исходные даты с предложенным повтором, независимо от горизонта и сдвига выходных.
            long lost = context.app().document().plan().adjustmentsOf(rule.id()).stream()
                    .filter(adjustment -> !OccurrenceGenerator.isNominalDate(rule, start,
                            adjustment.key().originalDate()))
                    .count();
            if (lost > 0) {
                return UiFormats.count(lost, UiText.get("rule.warning.orphan.one"),
                        UiText.get("rule.warning.orphan.few"), UiText.get("rule.warning.orphan.many"));
            }
        }
        return "";
    }

    private List<PreviewItem> preview(RecurringRule rule, FormContext context) {
        if (rule == null) return List.of(new PreviewItem(UiText.get("rule.preview.fill"), false));
        List<Occurrence> occurrences = occurrences(rule, context);
        if (occurrences.isEmpty()) return List.of(new PreviewItem(UiText.get("rule.preview.none"), false));
        List<PreviewItem> result = new ArrayList<>();
        for (Occurrence occurrence : occurrences) {
            String text = UiFormats.weekdayDate(occurrence.actual());
            // Properties убирает начальные пробелы шаблона; разделение частей задаём явно по §6.3.
            if (occurrence.shifted()) text += "  " + UiText.get("rule.preview.shift", UiFormats.weekdayDate(occurrence.nominal())).stripLeading();
            if (context.app().document().plan().findAdjustment(new ru.cashprediction.core.model.OccurrenceKey(rule.id(), occurrence.nominal())).isPresent()) text += "  " + UiText.get("rule.preview.adjusted").stripLeading();
            result.add(new PreviewItem(text, true));
        }
        return List.copyOf(result);
    }

    private List<Occurrence> occurrences(RecurringRule rule, FormContext context) {
        if (rule == null) return List.of();
        try { return OccurrenceGenerator.upcoming(rule, context.app().document().plan().startDate(), OpsForms.effectiveToday(context.app().today(), context.app().document().plan().startDate()), 6); }
        catch (RuntimeException ignored) { return List.of(); }
    }

    private RecurringRule existing(FormContext context) {
        if (!WindowType.MODE_EDIT.equals(context.contextValue(WindowType.CONTEXT_MODE))) return null;
        try { return context.app().document().plan().findRule(new RuleId(context.contextValue(WindowType.CONTEXT_RULE_ID))).orElse(null); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private String ruleId(FormContext context) { return existing(context) == null ? context.app().document().plan().nextRuleId().value() : existing(context).id().value(); }

    private Map<String, String> ruleValues(RecurringRule rule) {
        Recurrence recurrence = rule.recurrence();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("title", rule.title()); values.put("kind", rule.kind().name()); values.put("amount", FieldCodec.money(rule.amount())); values.put("category", rule.category());
        values.put("recurrenceKind", recurrence.kind().name()); values.put("dayOfMonth", recurrence instanceof Recurrence.Monthly monthly ? Integer.toString(monthly.dayOfMonth()) : "1");
        values.put("everyN", recurrence instanceof Recurrence.Monthly monthly ? Integer.toString(monthly.everyMonths()) : recurrence instanceof Recurrence.Weekly weekly ? Integer.toString(weekly.everyWeeks()) : recurrence instanceof Recurrence.EveryNDays daily ? Integer.toString(daily.days()) : "1");
        values.put("weekday", recurrence instanceof Recurrence.Weekly weekly ? weekly.weekday().name() : DayOfWeek.MONDAY.name()); values.put("monthDay", recurrence instanceof Recurrence.Yearly yearly ? FieldCodec.monthDay(yearly.monthDay()) : "");
        values.put("fromEnabled", FieldCodec.bool(rule.from() != null)); values.put("from", FieldCodec.date(rule.from())); values.put("untilEnabled", FieldCodec.bool(rule.until() != null)); values.put("until", FieldCodec.date(rule.until()));
        values.put("weekendPolicy", rule.weekendPolicy().name()); values.put("enabled", FieldCodec.bool(rule.enabled())); values.put("note", rule.note()); return Map.copyOf(values);
    }
}
