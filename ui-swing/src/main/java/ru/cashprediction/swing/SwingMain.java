package ru.cashprediction.swing;

import java.util.logging.Level;
import java.util.logging.Logger;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.swing.ui.SwingCoreMain;

/** Единственная точка входа Swing-клиента с интерфейсом общего ядра. */
public final class SwingMain {
    /** Сильная ссылка сохраняет настройку журнала реестра на всё время работы. */
    private static final Logger PREFS_LOGGER = Logger.getLogger("java.util.prefs");

    private SwingMain() { }

    /** Запускает ядро с аргументами портативного приложения; ранний сбой завершает процесс с кодом 2. */
    public static void main(String[] args) {
        PREFS_LOGGER.setLevel(Level.SEVERE);
        try {
            SwingCoreMain.launch(LaunchOptions.parse(args), args);
        } catch (RuntimeException | LinkageError failure) {
            SwingCoreMain.startupFailed(failure);
        }
    }
}
