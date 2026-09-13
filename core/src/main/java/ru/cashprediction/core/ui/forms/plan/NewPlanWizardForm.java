package ru.cashprediction.core.ui.forms.plan;

import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;

/**
 * §6.1 NEW_PLAN_WIZARD «Новый план» (₽, 600; представление {@code WIZARD}; контекст {@code page} = 0..2).
 *
 * <p>Страница 1: {@code name} (первое свободное «Мой план», «Мой план 2», …; checkPlanName, {@code val.plan.exists}),
 * {@code currency} (editableChoice ₽ $ € ₸ BYN), широкая подсказка. Страница 2: {@code startDate} (сегодня),
 * {@code startBalance} (0,00), горизонт ({@link HorizonFields}), {@code cushion} (0,00, неотрицательная), подсказка.
 * Страница 3: разделы «Ежемесячный доход» ({@code quickIncomeTitle} «Зарплата», {@code quickIncomeAmount},
 * {@code quickIncomeDay} 5) и «Ежемесячный расход» ({@code quickExpenseTitle} «Аренда», {@code quickExpenseAmount},
 * {@code quickExpenseDay} 1); проверки только при заполненной сумме.</p>
 *
 * <p>Кнопки: [Открыть пример] (LEFT, только страница 1) · [‹ Назад] (BACK, отключена на странице 1) · [Далее ›] (NEXT,
 * отключена на странице 3 или при ошибке страницы) · [Готово] (FINISH, когда все страницы корректны; иначе строка
 * проблем «✖ Шаг {N}: {ошибка}») · [Отмена]. Enter = «Далее» на страницах 1–2, «Готово» на странице 3.</p>
 *
 * <p>Результат: {@code Close(}{@link Created}{@code )} с планом (ежемесячные правила для заполненных сумм, выходные
 * «Не сдвигать») или {@code Close(}{@link OpenSample}{@code )}; отмена — {@code Close(null)}.</p>
 */
public final class NewPlanWizardForm implements FormLogic {

    /**
     * Мастер создал план.
     *
     * @param plan новый план (контроллер сохраняет его в CashMemory и открывает)
     */
    public record Created(Plan plan) {
        /** Проверяет план. */
        public Created {
            Objects.requireNonNull(plan, "plan");
        }
    }

    /** Нажата «Открыть пример»: мастер закрывается и открывается {@code SamplePlan}. */
    public record OpenSample() {
    }

    /** Создаёт мастер. */
    public NewPlanWizardForm() {
    }

    @Override
    public FormSpec spec(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-plan — NewPlanWizardForm.spec");
    }

    @Override
    public Map<String, String> defaults(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-plan — NewPlanWizardForm.defaults");
    }

    @Override
    public FormView evaluate(FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-plan — NewPlanWizardForm.evaluate");
    }

    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-plan — NewPlanWizardForm.onButton");
    }
}
