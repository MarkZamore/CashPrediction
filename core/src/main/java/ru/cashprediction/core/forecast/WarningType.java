package ru.cashprediction.core.forecast;

import ru.cashprediction.core.text.Texts;

/**
 * Вид предупреждения прогноза. Позволяет интерфейсу группировать и фильтровать предупреждения,
 * не разбирая текст сообщения.
 *
 * <p>Подпись берётся из общего каталога текстов ({@code forecast_ru.properties}) при каждом вызове
 * {@link #title()}, а не в конструкторе константы: отсутствующий ключ не должен ломать загрузку перечисления.</p>
 */
public enum WarningType {
    /** Баланс уходит в минус. */
    NEGATIVE_BALANCE,
    /** Баланс опускается ниже подушки безопасности. */
    BELOW_CUSHION,
    /** Корректировка не относится ни к одному событию. */
    ORPHAN_ADJUSTMENT,
    /** Корректировка перенесла событие за пределы горизонта. */
    MOVED_OUT_OF_HORIZON,
    /** Окно «С/По» правила не пересекается с горизонтом. */
    RULE_OUTSIDE_HORIZON,
    /** Несколько корректировок одного события. */
    DUPLICATE_ADJUSTMENT,
    /** Цель не достигается (или достигается позже желаемой даты). */
    GOAL_NOT_REACHED,
    /** Разовая операция за пределами горизонта. */
    ONE_TIME_OUTSIDE_HORIZON;

    /** @return подпись для интерфейса, например «Отрицательный баланс» */
    public String title() {
        return switch (this) {
            case NEGATIVE_BALANCE -> Texts.get("warning.type.negativeBalance");
            case BELOW_CUSHION -> Texts.get("warning.type.belowCushion");
            case ORPHAN_ADJUSTMENT -> Texts.get("warning.type.orphanAdjustment");
            case MOVED_OUT_OF_HORIZON -> Texts.get("warning.type.movedOutOfHorizon");
            case RULE_OUTSIDE_HORIZON -> Texts.get("warning.type.ruleOutsideHorizon");
            case DUPLICATE_ADJUSTMENT -> Texts.get("warning.type.duplicateAdjustment");
            case GOAL_NOT_REACHED -> Texts.get("warning.type.goalNotReached");
            case ONE_TIME_OUTSIDE_HORIZON -> Texts.get("warning.type.oneTimeOutsideHorizon");
        };
    }
}
