package ru.cashprediction.core.ui.forms.plan;

import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;

/**
 * §6.6 GOAL_CALCULATOR «Калькулятор цели» (◎, 640; немодальный, один экземпляр; представление {@code DIALOG}).
 *
 * <p>Заголовок «Когда я накоплю нужную сумму?». Поля: {@code target} «Нужная сумма» (по умолчанию сумма цели);
 * {@code byDateEnabled} + {@code byDate} «Срок» (флажок отмечен, если у цели есть дата; дата цели или конец плана);
 * {@code extraSaving} «Откладывать ещё в месяц». Раздел «Результат» — до 5 строк §6.6, пересчитываются при каждом
 * изменении поля и плана ({@link #reevaluateOnDocumentChange()}). Ошибка расчёта перехватывается: «✖ Прогноз не
 * рассчитан: {0}», окно не падает.</p>
 *
 * <p>Кнопки: [Записать цель в план] (LEFT) → {@code Apply(}{@link SaveGoal}{@code )}, окно не закрывается (отмена
 * {@code undo.goal}, статус {@code status.msg.goalSaved}); [Показать с доп. экономией] (LEFT, доступна при
 * {@code extraSaving} &gt; 0) → {@code Apply(}{@link AddWhatIfExtra}{@code , {extraSaving: ""})}; [Закрыть] (CANCEL).
 * Enter ничего не делает.</p>
 */
public final class GoalCalculatorForm implements FormLogic {

    /**
     * Записать цель в план: прежнее название или «Цель», сумма и дата (если отмечено).
     *
     * @param goal новая цель
     */
    public record SaveGoal(Goal goal) {
        /** Проверяет цель. */
        public SaveGoal {
            Objects.requireNonNull(goal, "goal");
        }
    }

    /**
     * Прибавить сумму к доп. экономии «что-если».
     *
     * @param amount сумма в месяц (&gt; 0)
     */
    public record AddWhatIfExtra(Money amount) {
        /** Проверяет сумму. */
        public AddWhatIfExtra {
            Objects.requireNonNull(amount, "amount");
        }
    }

    /** Создаёт форму. */
    public GoalCalculatorForm() {
    }

    @Override
    public FormSpec spec(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-plan - GoalCalculatorForm.spec");
    }

    @Override
    public Map<String, String> defaults(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-plan - GoalCalculatorForm.defaults");
    }

    @Override
    public FormView evaluate(FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-plan - GoalCalculatorForm.evaluate");
    }

    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-plan - GoalCalculatorForm.onButton");
    }

    /** @return {@code true}: результаты пересчитываются при изменении плана */
    @Override
    public boolean reevaluateOnDocumentChange() {
        return true;
    }
}
