package ru.cashprediction.core.forecast;

import java.util.Objects;
import ru.cashprediction.core.model.Money;

/**
 * Итоги одного месяца прогноза: строки-группы «Октябрь 2026 — итог/баланс» в таблице.
 *
 * <p>Пропущенные события в итоги не входят. Record неизменяем и потокобезопасен.</p>
 *
 * @param income         сумма доходов месяца (неотрицательная)
 * @param expense        сумма расходов месяца (неотрицательная, без знака минус)
 * @param net            {@code income - expense}
 * @param closingBalance баланс на конец последнего дня месяца (или на конец горизонта, если месяц последний)
 */
public record MonthTotals(Money income, Money expense, Money net, Money closingBalance) {

    /** Проверяет обязательные поля. */
    public MonthTotals {
        Objects.requireNonNull(income, "income");
        Objects.requireNonNull(expense, "expense");
        Objects.requireNonNull(net, "net");
        Objects.requireNonNull(closingBalance, "closingBalance");
    }
}
