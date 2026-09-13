package ru.cashprediction.core.ui.forms.simple;

import ru.cashprediction.core.ui.form.FormLogic;

/**
 * Формы ввода одного значения (представление {@code TEXT_INPUT}; JavaFX {@code TextInputDialog} → Swing
 * {@code SwingTextInputDialog} → Web {@code <dialog>}), тип окна TEXT_INPUT, поле {@code value}, контекст
 * {@code purpose}. Строка проблем обтекает содержимое, как в прежнем {@code StatefulTextInputDialog}.
 *
 * <ul>
 *   <li>{@link #rename()} — §6.9 «Переименовать план»: результат {@code Close(String новое имя)}; ошибки
 *       {@code PlanValidator.checkPlanName}, «Файл «{0}.md» уже существует», «Не удалось проверить имя файла: {0}».</li>
 *   <li>{@link #reconcile()} — §6.7 «Сверить баланс»: результат {@code Close(Money фактический баланс)}; разрешён
 *       минус; ошибка «Введите сумму, например 95 000,00 (можно отрицательную)».</li>
 *   <li>{@link #customMonths()} — §6.23 «Горизонт плана»: результат {@code Close(Integer месяцев 1..600)}.</li>
 *   <li>{@link #customCurrency()} — §6.8 «Своя валюта»: результат {@code Close(String валюта)}; ошибки «Введите
 *       обозначение валюты», «Не длиннее 10 символов», «Символ «|» и перевод строки недопустимы».</li>
 * </ul>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class TextInputForms {

    /** Назначение «Переименовать план». */
    public static final String PURPOSE_RENAME = "rename";
    /** Назначение «Сверить баланс». */
    public static final String PURPOSE_RECONCILE = "reconcile";
    /** Назначение «Горизонт плана». */
    public static final String PURPOSE_CUSTOM_MONTHS = "customMonths";
    /** Назначение «Своя валюта». */
    public static final String PURPOSE_CUSTOM_CURRENCY = "customCurrency";

    private TextInputForms() {
    }

    /** @return логика §6.9 */
    public static FormLogic rename() {
        throw new UnsupportedOperationException("S1: core-forms-framework — TextInputForms.rename");
    }

    /** @return логика §6.7 */
    public static FormLogic reconcile() {
        throw new UnsupportedOperationException("S1: core-forms-framework — TextInputForms.reconcile");
    }

    /** @return логика §6.23 */
    public static FormLogic customMonths() {
        throw new UnsupportedOperationException("S1: core-forms-framework — TextInputForms.customMonths");
    }

    /** @return логика §6.8 «Своя валюта» */
    public static FormLogic customCurrency() {
        throw new UnsupportedOperationException("S1: core-forms-framework — TextInputForms.customCurrency");
    }

    /**
     * Логика по назначению (для восстановления окна из снимка).
     *
     * @param purpose назначение
     * @return логика
     * @throws IllegalArgumentException если назначение неизвестно (текст {@code restore.warn.unknownPurpose})
     */
    public static FormLogic forPurpose(String purpose) {
        throw new UnsupportedOperationException("S1: core-forms-framework — TextInputForms.forPurpose");
    }
}
