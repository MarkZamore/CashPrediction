package ru.cashprediction.fx.dialog;

/**
 * Выбор пользователя в диалоге восстановления после сбоя.
 */
public enum RecoveryChoice {
    /** Восстановить из реестра Windows. */
    REGISTRY,
    /** Восстановить из XML-файла в CashMemory. */
    XML,
    /** Не восстанавливать: начать сеанс заново. */
    NONE
}
