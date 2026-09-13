package ru.cashprediction.core.ui.forms.ops;

import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;

/**
 * §6.5 ADJUSTMENT_EDITOR «Корректировка события» (✎, 560; контекст {@code ruleId}, {@code originalDate};
 * представление {@code DIALOG}).
 *
 * <p>Заголовок ««{rule title}»: событие пн, 05.10.2026», вторая строка «Сейчас: {…}», если корректировка есть. Широкая
 * строка «По правилу: доход 80 000,00 ₽» (+ «, с учётом выходных — dd.MM.yyyy»). Поля: {@code action} —
 * вертикальное радио SKIP / CHANGE_AMOUNT / MOVE / REPLACE; {@code amount} (доступна для изменения и замены);
 * {@code date} (для переноса и замены); {@code note} (2 строки). Недоступные поля сохраняют значение. Ошибки и
 * предупреждения — §6.5. Если правила нет: заголовок «Операция «{id}» не найдена в плане», поля отключены, кнопка
 * [Закрыть]. Кнопки: [Сбросить корректировку] (LEFT, только если есть) · [Сохранить] · [Отмена].</p>
 *
 * <p>Результат {@code Close(}{@link Result}{@code )}; контроллер: сохранение — {@code undo.adjust},
 * {@code status.msg.adjustSaved}; сброс — {@code undo.adjustReset}, {@code status.msg.adjustReset}.</p>
 */
public final class AdjustmentForm implements FormLogic {

    /**
     * Результат корректировки (общий с {@link QuickEditForm}).
     *
     * @param key        событие правила
     * @param adjustment новая корректировка или {@code null} — удалить корректировку (сброс)
     */
    public record Result(OccurrenceKey key, Adjustment adjustment) {
        /** Проверяет ключ. */
        public Result {
            Objects.requireNonNull(key, "key");
        }
    }

    /** Создаёт форму. */
    public AdjustmentForm() {
    }

    @Override
    public FormSpec spec(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops — AdjustmentForm.spec");
    }

    @Override
    public Map<String, String> defaults(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops — AdjustmentForm.defaults");
    }

    @Override
    public FormView evaluate(FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops — AdjustmentForm.evaluate");
    }

    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops — AdjustmentForm.onButton");
    }
}
