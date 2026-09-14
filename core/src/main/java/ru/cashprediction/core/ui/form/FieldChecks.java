package ru.cashprediction.core.ui.form;

import java.util.Optional;

/**
 * Общие проверки полей (спецификация v2, §6.0 «Общие тексты проверок»): тексты {@code val.*} с именем поля в
 * кавычках.
 *
 * <ul>
 *   <li>{@code val.money.required} «Укажите сумму в поле «{0}»»</li>
 *   <li>{@code val.money.invalid} «Поле «{0}»: некорректная сумма «{1}»»</li>
 *   <li>{@code val.money.positive} «Поле «{0}»: сумма должна быть больше нуля»</li>
 *   <li>{@code val.money.tooBig} «Поле «{0}»: слишком большая сумма»</li>
 *   <li>{@code val.money.nonNegative} «Поле «{0}»: сумма не может быть отрицательной»</li>
 *   <li>{@code val.date.required} «Укажите дату в поле «{0}» (ДД.ММ.ГГГГ)»</li>
 *   <li>{@code val.date.invalid} «Поле «{0}»: некорректная дата (ДД.ММ.ГГГГ)»</li>
 *   <li>{@code val.text.required} «Заполните поле «{0}»»</li>
 * </ul>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class FieldChecks {

    /** Что требуется от суммы. */
    public enum MoneyRule {
        /** Обязательна и больше нуля. */
        REQUIRED_POSITIVE,
        /** Может быть пустой; если заполнена — больше нуля. */
        OPTIONAL_POSITIVE,
        /** Обязательна и не меньше нуля (подушка). */
        NON_NEGATIVE,
        /** Обязательна, любой знак (начальный баланс, сверка). */
        ANY
    }

    private FieldChecks() {
    }

    /**
     * Проверка суммы.
     *
     * @param label подпись поля без двоеточия
     * @param raw   значение из {@code FormState}
     * @param rule  требование
     * @return текст ошибки или пусто
     */
    public static Optional<String> money(String label, String raw, MoneyRule rule) {
        throw new UnsupportedOperationException("S1: core-forms-framework - FieldChecks.money");
    }

    /**
     * Проверка даты.
     *
     * @param label    подпись поля
     * @param raw      значение из {@code FormState}
     * @param required обязательна ли
     * @return текст ошибки или пусто
     */
    public static Optional<String> date(String label, String raw, boolean required) {
        throw new UnsupportedOperationException("S1: core-forms-framework - FieldChecks.date");
    }

    /**
     * Проверка обязательного текста.
     *
     * @param label подпись поля
     * @param raw   значение
     * @return текст ошибки или пусто
     */
    public static Optional<String> requiredText(String label, String raw) {
        throw new UnsupportedOperationException("S1: core-forms-framework - FieldChecks.requiredText");
    }
}
