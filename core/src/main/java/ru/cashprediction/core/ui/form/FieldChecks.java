package ru.cashprediction.core.ui.form;

import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.ui.text.UiText;

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
 * <p><b>Больше двух цифр после запятой</b> (решение L1b): {@code val.money.fractionThousands} объясняет правило и
 * предлагает запись тысяч («1,234» → «1 234»), если ввод на неё похож; иначе {@code val.money.fraction}. Слишком
 * большая сумма - больше {@link PlanValidator#MAX_AMOUNT} по модулю или не помещается в число.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class FieldChecks {

    /** Что требуется от суммы. */
    public enum MoneyRule {
        /** Обязательна и больше нуля. */
        REQUIRED_POSITIVE,
        /** Может быть пустой; если заполнена - больше нуля. */
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
        Objects.requireNonNull(rule, "rule");
        FieldCodec.MoneyInput input = FieldCodec.analyzeMoney(raw);
        return switch (input.status()) {
            case EMPTY -> rule == MoneyRule.OPTIONAL_POSITIVE
                    ? Optional.empty()
                    : Optional.of(UiText.get("val.money.required", label));
            case INVALID -> Optional.of(UiText.get("val.money.invalid", label, input.text()));
            case FRACTION -> Optional.of(input.suggestion().isEmpty()
                    ? UiText.get("val.money.fraction", label, input.text())
                    : UiText.get("val.money.fractionThousands", label, input.text(), input.suggestion()));
            case TOO_BIG -> Optional.of(UiText.get("val.money.tooBig", label));
            case OK -> checkValue(label, input.value(), rule);
        };
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
        if (raw == null || raw.isBlank()) {
            return required ? Optional.of(UiText.get("val.date.required", label)) : Optional.empty();
        }
        return FieldCodec.parseDate(raw).isPresent()
                ? Optional.empty()
                : Optional.of(UiText.get("val.date.invalid", label));
    }

    /**
     * Проверка обязательного текста.
     *
     * @param label подпись поля
     * @param raw   значение
     * @return текст ошибки или пусто
     */
    public static Optional<String> requiredText(String label, String raw) {
        return raw == null || raw.isBlank() ? Optional.of(UiText.get("val.text.required", label)) : Optional.empty();
    }

    /**
     * Первая найденная проблема из проверок по порядку полей формы.
     *
     * @param checks результаты проверок
     * @return первый непустой результат или пусто
     */
    @SafeVarargs
    public static Optional<String> first(Optional<String>... checks) {
        for (Optional<String> check : checks) {
            if (check != null && check.isPresent()) {
                return check;
            }
        }
        return Optional.empty();
    }

    /** @return проблема знака или величины корректной суммы */
    private static Optional<String> checkValue(String label, Money value, MoneyRule rule) {
        if (value.abs().compareTo(PlanValidator.MAX_AMOUNT) > 0) {
            return Optional.of(UiText.get("val.money.tooBig", label));
        }
        return switch (rule) {
            case REQUIRED_POSITIVE, OPTIONAL_POSITIVE -> value.isPositive()
                    ? Optional.empty()
                    : Optional.of(UiText.get("val.money.positive", label));
            case NON_NEGATIVE -> value.isNegative()
                    ? Optional.of(UiText.get("val.money.nonNegative", label))
                    : Optional.empty();
            case ANY -> Optional.empty();
        };
    }
}
