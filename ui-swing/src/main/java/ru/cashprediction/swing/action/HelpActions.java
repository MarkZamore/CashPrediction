package ru.cashprediction.swing.action;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import ru.cashprediction.core.markdown.MarkdownFormat;

/**
 * Команды меню «Справка»: «О программе» (диалог 19, F1), «Горячие клавиши» (диалог 20), «Формат файла .md».
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
final class HelpActions extends ActionSupport {

    /** Версия приложения для окна «О программе». */
    public static final String VERSION = "1.0.0";

    /** Путь к руководству по формату плана внутри модуля core. */
    private static final String FORMAT_RESOURCE = "ru/cashprediction/core/FORMAT.md";

    /** Текст окна «Горячие клавиши»: тот же набор, что в JavaFX-клиенте. */
    public static final String HOTKEYS = """
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
            Shift+F10       Контекстное меню выбранной строки

            В быстрой правке суммы (двойной щелчок по сумме): Enter — сохранить, Esc — закрыть.
            В веб-клиенте занятые браузером сочетания (Ctrl+N, Ctrl+T, Ctrl+W, Ctrl+O)
            заменены на Alt+Shift+буква.""";

    /**
     * Создаёт команды справки.
     *
     * @param shared общие объекты команд
     */
    HelpActions(ActionShared shared) {
        super(shared);
    }

    /**
     * «О программе» (F1).
     */
    public void about() {
        // JavaFX: Alert(INFORMATION) → Swing: SwingAlert (JOptionPane.INFORMATION_MESSAGE) → Web: <dialog class="alert">
        alerts().info("CashPrediction " + VERSION + " — прогноз бюджета",
                "Сколько денег будет через месяц, полгода, год при текущем плане доходов и расходов.\n\n"
                        + "Клиент: Swing, Java " + System.getProperty("java.version") + ".\n"
                        + "Все данные хранятся в папке CashMemory:\n" + context().layout().dir());
    }

    /**
     * «Горячие клавиши» (диалог 20): список в раскрытой моноширинной области.
     */
    public void hotkeys() {
        // JavaFX: Alert + expandableContent → Swing: SwingAlert + «Подробнее» (JTextArea) → Web: <dialog class="alert"> с <pre>
        alerts().infoWithDetails("Горячие клавиши", "Горячие клавиши CashPrediction", "Полный список — ниже.", HOTKEYS);
    }

    /**
     * «Формат файла .md»: руководство FORMAT.md из ресурсов ядра в раскрываемой области.
     */
    public void formatHelp() {
        // JavaFX: Alert + expandableContent → Swing: SwingAlert + «Подробнее» (JTextArea) → Web: <dialog class="alert"> с <pre>
        alerts().infoWithDetails("Формат файла .md", "Формат файла плана CashPrediction",
                "Файл плана — обычный текст Markdown: его можно править в Блокноте. Нераспознанные строки не теряются. "
                        + "Руководство — ниже.", formatGuide());
    }

    /**
     * Текст руководства по формату плана.
     *
     * <p>Сначала ресурс читается через модуль ядра ({@code Module.getResourceAsStream}) — так он находится и на
     * module path, и на class path (там модуль безымянный). Если ресурс инкапсулирован или не найден, текст берётся
     * у самого ядра ({@link MarkdownFormat#userGuide()}), которое читает его своим классом и никогда не бросает.</p>
     *
     * @return текст FORMAT.md или сообщение ядра о том, что руководство недоступно
     */
    static String formatGuide() {
        try {
            Module core = Class.forName("ru.cashprediction.core.io.AppPaths").getModule();
            try (InputStream in = core.getResourceAsStream(FORMAT_RESOURCE)) {
                if (in != null) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    // BOM в начале файла, сохранённого Блокнотом, в окне не нужен.
                    return text.startsWith("﻿") ? text.substring(1) : text;
                }
            }
        } catch (ClassNotFoundException | IOException | RuntimeException e) {
            // Не нашли через модуль — ниже спросим ядро напрямую.
        }
        return MarkdownFormat.userGuide();
    }
}
