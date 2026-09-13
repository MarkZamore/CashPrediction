package ru.cashprediction.core.ui.forms.simple;

import ru.cashprediction.core.ui.form.FormLogic;

/**
 * Формы выбора из списка (представление {@code CHOICE}; JavaFX {@code ChoiceDialog<T>} → Swing
 * {@code SwingChoiceDialog} → Web {@code <dialog>}), тип окна CHOICE, поле {@code value}.
 *
 * <p>{@link #currency()} — §6.8 «Валюта плана»: заголовок «Валюта плана «{name}» (сейчас: {cur}). Суммы не
 * пересчитываются.»; варианты ₽, $, €, ₸, BYN, «другая…»; по умолчанию текущая или «другая…»; кнопки [Выбрать]
 * [Отмена]. Результат {@code Close(String валюта)} или {@code Close(}{@link #CUSTOM}{@code )} — тогда контроллер
 * открывает {@code TextInputForms.customCurrency()}. Отмена действия {@code undo.currency}.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class ChoiceForms {

    /** Назначение «Валюта плана». */
    public static final String PURPOSE_CURRENCY = "currency";

    /** Результат «другая…»: открыть ввод своей валюты. */
    public static final String CUSTOM = "custom";

    private ChoiceForms() {
    }

    /** @return логика §6.8 */
    public static FormLogic currency() {
        throw new UnsupportedOperationException("S1: core-forms-framework — ChoiceForms.currency");
    }
}
