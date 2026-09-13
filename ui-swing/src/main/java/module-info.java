/**
 * Настольный клиент CashPrediction на Swing: аналоги всех JavaFX-классов меню, всплывающих окон и диалогов.
 */
module ru.cashprediction.swing {
    // Ядро: модель, прогноз, файлы CashMemory, запись и восстановление сессии.
    requires ru.cashprediction.core;
    // Swing/AWT, а также ImageIO для сохранения графика в PNG.
    requires java.desktop;
    // Только чтобы приглушить журнал java.util.prefs (иначе при недоступном реестре он пишет в консоль).
    requires java.logging;
}
