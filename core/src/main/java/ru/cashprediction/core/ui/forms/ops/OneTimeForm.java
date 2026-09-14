package ru.cashprediction.core.ui.forms.ops;

import java.util.Map;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;

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

    @Override
    public FormSpec spec(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops - OneTimeForm.spec");
    }

    @Override
    public Map<String, String> defaults(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops - OneTimeForm.defaults");
    }

    @Override
    public FormView evaluate(FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops - OneTimeForm.evaluate");
    }

    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops - OneTimeForm.onButton");
    }
}
