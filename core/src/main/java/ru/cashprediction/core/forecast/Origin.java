package ru.cashprediction.core.forecast;

import ru.cashprediction.core.text.Texts;

/**
 * Источник строки прогноза. Порядок констант важен: он же порядок строк внутри одного дня
 * (после разделения на доходы и расходы).
 *
 * <p>Подпись берётся из общего каталога текстов ({@code forecast_ru.properties}) при каждом вызове
 * {@link #title()}, а не в конструкторе константы: отсутствующий ключ не должен ломать загрузку перечисления.</p>
 */
public enum Origin {
    /** Строка начального баланса; всегда первая. */
    START,
    /** Событие регулярной операции. */
    RULE,
    /** Разовая операция. */
    ONE_TIME,
    /** Синтетическая строка режима «что-если» (дополнительная экономия). */
    WHAT_IF;

    /** @return подпись для интерфейса: «Начальный баланс», «Регулярная», «Разовая», «Что-если» */
    public String title() {
        return switch (this) {
            case START -> Texts.get("origin.start");
            case RULE -> Texts.get("origin.rule");
            case ONE_TIME -> Texts.get("origin.oneTime");
            case WHAT_IF -> Texts.get("origin.whatIf");
        };
    }
}
