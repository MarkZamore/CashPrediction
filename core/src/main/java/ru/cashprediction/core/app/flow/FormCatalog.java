package ru.cashprediction.core.app.flow;

import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.session.WindowState;

/**
 * Какая форма соответствует окну снимка (архитектура §3.8).
 *
 * <table>
 *   <caption>Тип окна → форма</caption>
 *   <tr><th>WindowType</th><th>Форма</th></tr>
 *   <tr><td>NEW_PLAN_WIZARD</td><td>{@code NewPlanWizardForm}</td></tr>
 *   <tr><td>PLAN_SETTINGS</td><td>{@code PlanSettingsForm}</td></tr>
 *   <tr><td>RULE_EDITOR</td><td>{@code RuleEditorForm} (edit: правило должно существовать)</td></tr>
 *   <tr><td>ONE_TIME_EDITOR</td><td>{@code OneTimeForm}</td></tr>
 *   <tr><td>ADJUSTMENT_EDITOR</td><td>{@code AdjustmentForm} (нужны ruleId и originalDate, иначе {@code restore.warn.noContext})</td></tr>
 *   <tr><td>GOAL_CALCULATOR</td><td>{@code GoalCalculatorForm}</td></tr>
 *   <tr><td>TEXT_INPUT</td><td>{@code TextInputForms.forPurpose} (неизвестное — {@code restore.warn.unknownPurpose})</td></tr>
 *   <tr><td>CHOICE</td><td>{@code ChoiceForms.currency} / {@code OpenPlanForm}</td></tr>
 *   <tr><td>ALERT</td><td>{@code ConfirmForms} по назначению (нет цели — {@code restore.warn.targetGone})</td></tr>
 *   <tr><td>CSV_EXPORT</td><td>{@code CsvExportForm}</td></tr>
 *   <tr><td>QUICK_EDIT_POPUP</td><td>{@code QuickEditForm} (строки нет в таблице — {@code restore.warn.rowHidden})</td></tr>
 * </table>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class FormCatalog {

    private FormCatalog() {
    }

    /**
     * Форма для восстановления окна.
     *
     * @param state окно из снимка
     * @param app   состояние приложения после загрузки плана
     * @return запрос открытия
     * @throws IllegalArgumentException если окно восстановить нельзя; сообщение — готовый текст {@code restore.warn.*}
     */
    public static FormRequest forRestore(WindowState state, AppState app) {
        throw new UnsupportedOperationException("S2: core-app-session — FormCatalog.forRestore");
    }
}
