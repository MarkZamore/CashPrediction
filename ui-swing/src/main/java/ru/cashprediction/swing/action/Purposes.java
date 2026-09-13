package ru.cashprediction.swing.action;

/**
 * Значения контекста {@code purpose} универсальных восстанавливаемых окон ({@code TEXT_INPUT}, {@code CHOICE},
 * {@code ALERT}). Одинаковы в трёх клиентах: по ним окно из снимка открывается той же командой.
 *
 * <p>Класс только с константами.</p>
 */
public final class Purposes {

    /** {@code TEXT_INPUT}: переименование плана. */
    public static final String RENAME = "rename";
    /** {@code TEXT_INPUT}: сверка баланса. */
    public static final String RECONCILE = "reconcile";
    /** {@code TEXT_INPUT}: горизонт — произвольное число месяцев. */
    public static final String CUSTOM_MONTHS = "customMonths";
    /** {@code TEXT_INPUT}: своя валюта. */
    public static final String CUSTOM_CURRENCY = "customCurrency";
    /** {@code CHOICE}: выбор валюты. */
    public static final String CURRENCY = "currency";
    /** {@code CHOICE}: открыть план из CashMemory. */
    public static final String OPEN_PLAN = "openPlan";
    /** {@code ALERT}: подтверждение удаления регулярной операции. */
    public static final String DELETE_RULE = "deleteRule";
    /** {@code ALERT}: подтверждение удаления разовой операции. */
    public static final String DELETE_ONE_TIME = "deleteOneTime";
    /** {@code ALERT}: подтверждение «Актуализировать на сегодня». */
    public static final String ACTUALIZE = "actualize";
    /** {@code ALERT}: подтверждение «Что-если → Применить к плану». */
    public static final String APPLY_WHAT_IF = "applyWhatIf";
    /** {@code ALERT}: подтверждение «Очистить снимки». */
    public static final String CLEAR_SNAPSHOTS = "clearSnapshots";

    private Purposes() {
    }
}
