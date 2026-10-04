package ru.cashprediction.core.service.plan;

import java.util.Objects;
import ru.cashprediction.core.model.Money;

/** Типизированные данные для результата сверки и очистки, без массивов и обратных вызовов. */
public record PlanCommandEffect(Money reconciliationDifference, int removedAdjustments) {
    /** Результат обычной команды без дополнительных данных. */
    public static final PlanCommandEffect NONE = new PlanCommandEffect(Money.ZERO, 0);

    /** Проверяет обязательную сумму результата. */
    public PlanCommandEffect {
        Objects.requireNonNull(reconciliationDifference, "reconciliationDifference");
    }
}
