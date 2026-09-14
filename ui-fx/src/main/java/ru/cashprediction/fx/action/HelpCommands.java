package ru.cashprediction.fx.action;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import ru.cashprediction.core.markdown.MarkdownFormat;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.fx.dialog.Dialogs;

/**
 * Команды меню «Справка»: «О программе» (F1), «Горячие клавиши», «Формат файла .md».
 *
 * <p>Только FX Application Thread.</p>
 */
final class HelpCommands {

    /** Версия приложения для окна «О программе». */
    static final String VERSION = "1.0.0";

    /** Текст окна «Горячие клавиши»: одинаков для настольных клиентов. */
    static final String HOTKEYS = """
            Ctrl+N          Новый план
            Ctrl+O          Открыть план
            Ctrl+S          Сохранить
            Ctrl+Shift+S    Сохранить как
            F2              Переименовать план
            Ctrl+Shift+C    Экспорт в CSV
            Ctrl+I          Добавить регулярный доход
            Ctrl+E          Добавить регулярный расход
            Ctrl+T          Разовая операция
            Ctrl+J          Скорректировать выбранное событие
            Enter           Изменить выбранную строку (в таблице)
            Delete          Удалить выбранную операцию (в таблице)
            Ctrl+Z / Ctrl+Y Отменить / Повторить
            Ctrl+1 / Ctrl+2 Таблица / График
            Ctrl+F          Перейти к фильтру
            Ctrl+G          Калькулятор цели
            F1              О программе

            В быстрой правке суммы (двойной щелчок по сумме): Enter - сохранить, Esc - закрыть.
            В веб-клиенте занятые браузером сочетания (Ctrl+N, Ctrl+T, Ctrl+W, Ctrl+O)
            заменены на Alt+Shift+буква.""";

    private final CommandSupport support;

    HelpCommands(CommandSupport support) {
        this.support = support;
    }

    /** Диалог 19 «О программе». */
    void about() {
        String javafx = System.getProperty("javafx.runtime.version", "?");
        support.info("CashPrediction " + VERSION + " - прогноз бюджета",
                "Сколько денег будет через месяц, полгода, год при текущем плане доходов и расходов.\n\n"
                        + "Клиент: JavaFX " + javafx + ", Java " + System.getProperty("java.version") + ".\n"
                        + "Все данные хранятся в папке CashMemory:\n" + support.context().layout().dir());
    }

    /** Диалог 20 «Горячие клавиши». */
    void hotkeys() {
        // JavaFX: Alert → Swing: JOptionPane.showMessageDialog → Web: <dialog class="alert">
        Alert alert = Dialogs.withDetails(AlertType.INFORMATION, "Горячие клавиши", "Горячие клавиши CashPrediction",
                "Полный список - ниже.", HOTKEYS);
        // JavaFX: DialogPane → Swing: SwingDialogPane (JPanel header/content/кнопки) → Web: <dialog><form method="dialog">
        alert.getDialogPane().setExpanded(true);
        support.host().show(alert, WindowState.MAIN_OWNER, r -> { });
    }

    /** «Справка → Формат файла .md»: руководство FORMAT.md из ресурсов ядра в раскрываемой области. */
    void formatHelp() {
        // MarkdownFormat.userGuide() читает ресурс через класс самого модуля core — работает и на module path, и на class path.
        String guide = MarkdownFormat.userGuide();
        // JavaFX: Alert → Swing: JOptionPane.showMessageDialog + JTextArea → Web: <dialog class="alert"> с <pre>
        Alert alert = Dialogs.withDetails(AlertType.INFORMATION, "Формат файла .md", "Формат файла плана CashPrediction",
                "Файл плана - обычный текст Markdown: его можно править в Блокноте. Руководство - ниже.", guide);
        // JavaFX: DialogPane → Swing: SwingDialogPane (JPanel header/content/кнопки) → Web: <dialog><form method="dialog">
        alert.getDialogPane().setExpanded(true);
        support.host().show(alert, WindowState.MAIN_OWNER, r -> { });
    }
}
