package ru.cashprediction.swing;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import ru.cashprediction.swing.session.SwingCrashHooks;

/**
 * Точка входа Swing-клиента CashPrediction ({@code CashPrediction-Swing.exe}).
 *
 * <p>До запуска Swing: приглушается журнал {@code java.util.prefs} (при недоступном реестре он писал бы в консоль),
 * устанавливается обработчик необработанных исключений ({@link SwingCrashHooks}: снимок сессии → сообщение →
 * {@code halt(2)}; исключения потока EDT тоже попадают в него) и системный вид окон (Windows). Сам запуск —
 * {@link StartupFlow} — выполняется в потоке EDT.</p>
 *
 * <p>Режим самотеста для разработчиков включается системным свойством {@code cashprediction.selftest}
 * (см. {@code ru.cashprediction.swing.selftest.SwingSelfTest}).</p>
 */
public final class SwingMain {

    /**
     * Сильная ссылка на журнал {@code java.util.prefs}: без неё журнал мог бы быть собран сборщиком мусора вместе с
     * заданным уровнем, и предупреждения реестра снова пошли бы в консоль.
     */
    private static final Logger PREFS_LOGGER = Logger.getLogger("java.util.prefs");

    private SwingMain() {
    }

    /**
     * Запускает приложение.
     *
     * @param args аргументы командной строки (не используются)
     */
    public static void main(String[] args) {
        PREFS_LOGGER.setLevel(Level.SEVERE);
        // Обработчик — до создания любых окон: сбой при запуске тоже должен сохранить то, что уже есть.
        SwingCrashHooks.install();
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException e) {
            // Системный вид недоступен — остаётся стандартный Metal, программа работает так же.
        }
        SwingUtilities.invokeLater(() -> {
            // Подсказки таблицы и графика длинные: даём время их прочитать.
            ToolTipManager.sharedInstance().setDismissDelay(20_000);
            new StartupFlow().run();
        });
    }
}
