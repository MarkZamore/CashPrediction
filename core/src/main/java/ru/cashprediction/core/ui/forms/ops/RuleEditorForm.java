package ru.cashprediction.core.ui.forms.ops;

import java.util.Map;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;

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

    @Override
    public FormSpec spec(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops - RuleEditorForm.spec");
    }

    @Override
    public Map<String, String> defaults(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops - RuleEditorForm.defaults");
    }

    @Override
    public FormView evaluate(FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops - RuleEditorForm.evaluate");
    }

    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops - RuleEditorForm.onButton");
    }

    @Override
    public FormOutcome onPreview(int index, boolean activated, FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops - RuleEditorForm.onPreview");
    }
}
