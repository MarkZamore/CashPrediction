package ru.cashprediction.core.ui.forms.plan;

import java.util.Map;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;

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
        throw new UnsupportedOperationException("S1: core-forms-plan - PlanSettingsForm.spec");
    }

    @Override
    public Map<String, String> defaults(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-plan - PlanSettingsForm.defaults");
    }

    @Override
    public FormView evaluate(FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-plan - PlanSettingsForm.evaluate");
    }

    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-plan - PlanSettingsForm.onButton");
    }
}
