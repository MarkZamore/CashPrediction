package ru.cashprediction.core.ui.forms.ops;

import java.util.Map;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;

/**
 * §5.6.1 QUICK_EDIT_POPUP «Быстрая правка суммы» (представление {@code POPUP}: JavaFX {@code Popup} → Swing
 * {@code PopupFactory} → Web div; контекст {@code ruleId}, {@code originalDate}; поле {@code amount}; немодальное,
 * одновременно одно).
 *
 * <p>Содержимое: жирная подпись {@code quick.caption} ««{title}», dd.MM.yyyy — новая сумма, {cur}»; денежное поле
 * 140 (текущая сумма «95 000,00», выделено, фокус); ошибка {@code quick.error} «Введите сумму больше нуля, например
 * 95 000,00» (скрыта, пока ввод корректен); подсказка {@code quick.hint} «Enter — сохранить, Esc — закрыть». Кнопок
 * нет: Enter — кнопка {@code ok}, Esc/щелчок вне окна — {@code cancel}.</p>
 *
 * <p>Результат {@code Close(AdjustmentForm.Result)}: CHANGE_AMOUNT; если событие уже перенесено — REPLACE с прежней
 * датой; заметка сохраняется; если сумма равна сумме правила без переноса и заметки — {@code adjustment = null}
 * (корректировка удаляется). Контроллер: {@code undo.quickAmount}, {@code status.msg.quickAmount}.</p>
 */
public final class QuickEditForm implements FormLogic {

    /** Создаёт форму. */
    public QuickEditForm() {
    }

    @Override
    public FormSpec spec(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops — QuickEditForm.spec");
    }

    @Override
    public Map<String, String> defaults(FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops — QuickEditForm.defaults");
    }

    @Override
    public FormView evaluate(FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops — QuickEditForm.evaluate");
    }

    @Override
    public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
        throw new UnsupportedOperationException("S1: core-forms-ops — QuickEditForm.onButton");
    }
}
