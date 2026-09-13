package ru.cashprediction.swing.dialog;

import java.awt.Font;
import java.io.PrintWriter;
import java.io.StringWriter;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * Мелкие текстовые помощники диалогов: HTML-разметка для многострочных {@code JLabel},
 * текст стека исключения, область «Подробнее» с моноширинным текстом.
 *
 * <p>Класс без состояния; методы, создающие компоненты, вызываются в потоке EDT.</p>
 */
public final class SwingText {

    private SwingText() {
    }

    /**
     * Превращает обычный текст в HTML для {@code JLabel}: экранирует спецсимволы и переводит строки в {@code <br>}.
     *
     * <p>{@code JLabel} не умеет переносить строки в простом тексте, а без экранирования имя плана
     * вида «a &lt;b&gt;» сломало бы разметку.</p>
     *
     * @param text исходный текст; {@code null} — пустая строка
     * @return HTML-строка, начинающаяся с {@code <html>}
     */
    public static String html(String text) {
        return "<html>" + escape(text).replace("\n", "<br>") + "</html>";
    }

    /**
     * Как {@link #html(String)}, но с ограничением ширины: длинные строки переносятся.
     *
     * @param text   исходный текст
     * @param widthPx ширина в пикселях
     * @return HTML-строка
     */
    public static String htmlWrapped(String text, int widthPx) {
        return "<html><div style='width:" + widthPx + "px'>" + escape(text).replace("\n", "<br>") + "</div></html>";
    }

    /**
     * Экранирует символы HTML.
     *
     * @param text текст; {@code null} — пустая строка
     * @return экранированный текст
     */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Текст стека исключения для раскрываемой области диалога ошибки.
     *
     * @param error исключение; {@code null} — пустая строка
     * @return многострочный текст стека
     */
    public static String stackTrace(Throwable error) {
        if (error == null) {
            return "";
        }
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    /**
     * Прокручиваемая область только для чтения с моноширинным текстом (стек, XML снимка, справка).
     *
     * @param text    содержимое
     * @param rows    видимых строк
     * @param columns видимых колонок
     * @return компонент для {@link SwingDialogPane#setExpandableContent}
     */
    public static JComponent readOnlyArea(String text, int rows, int columns) {
        JTextArea area = new JTextArea(text == null ? "" : text, rows, columns);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, area.getFont().getSize()));
        area.setCaretPosition(0);
        return new JScrollPane(area);
    }
}
