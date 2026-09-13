package ru.cashprediction.core.forecast;

import java.math.BigDecimal;
import java.util.Objects;
import ru.cashprediction.core.model.Money;

/**
 * Параметры режима «что-если»: прогноз без изменения самого плана.
 *
 * <p>Коэффициенты умножают суммы доходов и расходов регулярных и разовых операций
 * (например, {@code 1.10} — «доходы +10 %», {@code 0.90} — «расходы −10 %»).
 * {@code extraMonthlySaving} — сколько дополнительно откладывать (экономить) каждый месяц:
 * движок добавляет синтетическую строку дохода «Доп. экономия (что-если)» в последний день каждого месяца,
 * начиная с {@code anchor = max(startDate, today)}.</p>
 *
 * <p>Record неизменяем и потокобезопасен.</p>
 *
 * @param incomeFactor       коэффициент доходов, не меньше нуля
 * @param expenseFactor      коэффициент расходов, не меньше нуля
 * @param extraMonthlySaving дополнительная экономия в месяц, не меньше нуля
 */
public record WhatIf(BigDecimal incomeFactor, BigDecimal expenseFactor, Money extraMonthlySaving) {

    /** Режим «что-если» выключен: коэффициенты 1, экономия 0. */
    public static final WhatIf NONE = new WhatIf(BigDecimal.ONE, BigDecimal.ONE, Money.ZERO);

    /** Проверяет, что значения заданы и неотрицательны. */
    public WhatIf {
        Objects.requireNonNull(incomeFactor, "incomeFactor");
        Objects.requireNonNull(expenseFactor, "expenseFactor");
        Objects.requireNonNull(extraMonthlySaving, "extraMonthlySaving");
        if (incomeFactor.signum() < 0 || expenseFactor.signum() < 0) {
            throw new IllegalArgumentException("Коэффициент «что-если» не может быть отрицательным");
        }
        if (extraMonthlySaving.isNegative()) {
            throw new IllegalArgumentException("Дополнительная экономия не может быть отрицательной");
        }
    }

    /**
     * Удобный конструктор из процентов изменения.
     *
     * @param incomePercent      изменение доходов в процентах ({@code 10} — «+10 %», {@code -10} — «−10 %»)
     * @param expensePercent     изменение расходов в процентах
     * @param extraMonthlySaving дополнительная экономия в месяц
     * @return параметры «что-если»
     */
    public static WhatIf ofPercent(int incomePercent, int expensePercent, Money extraMonthlySaving) {
        return new WhatIf(BigDecimal.valueOf(100L + incomePercent).movePointLeft(2),
                BigDecimal.valueOf(100L + expensePercent).movePointLeft(2), extraMonthlySaving);
    }

    /** @return {@code true}, если режим ничего не меняет (коэффициенты равны 1, экономия 0) */
    public boolean isNone() {
        return incomeFactor.compareTo(BigDecimal.ONE) == 0
                && expenseFactor.compareTo(BigDecimal.ONE) == 0
                && extraMonthlySaving.isZero();
    }

    /**
     * @param value новая дополнительная экономия
     * @return копия с другой экономией (нужна калькулятору цели)
     */
    public WhatIf withExtraMonthlySaving(Money value) {
        return new WhatIf(incomeFactor, expenseFactor, value);
    }

    /**
     * @param value новый коэффициент доходов
     * @return копия с другим коэффициентом доходов
     */
    public WhatIf withIncomeFactor(BigDecimal value) {
        return new WhatIf(value, expenseFactor, extraMonthlySaving);
    }

    /**
     * @param value новый коэффициент расходов
     * @return копия с другим коэффициентом расходов
     */
    public WhatIf withExpenseFactor(BigDecimal value) {
        return new WhatIf(incomeFactor, value, extraMonthlySaving);
    }
}
