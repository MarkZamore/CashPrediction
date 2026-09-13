package ru.cashprediction.web;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Маленькое окно статуса web-сервера (раздел 2 плана): адрес, «Открыть в браузере», «Копировать адрес»,
 * «Остановить сервер» и последние строки журнала.
 *
 * <p>Нужно потому, что {@code CashPrediction-Web.exe} запускается без консоли: без окна пользователь не увидел бы
 * адрес и не смог бы корректно остановить сервер. Закрытие окна — тоже корректная остановка.</p>
 *
 * <p>Все методы Swing вызываются в EDT; строки журнала приходят из других потоков через {@code invokeLater}.</p>
 */
public final class ServerStatusWindow {

    private final WebServer server;
    private final ServerLog log;
    private final JFrame frame = new JFrame("CashPrediction Web — сервер");
    private final JTextArea logArea = new JTextArea(12, 60);
    private final Consumer<String> logListener = line -> SwingUtilities.invokeLater(() -> append(line));

    private ServerStatusWindow(WebServer server, ServerLog log) {
        this.server = server;
        this.log = log;
    }

    /**
     * Показывает окно статуса (из любого потока).
     *
     * @param server запущенный сервер
     * @param log    журнал сервера
     */
    public static void show(WebServer server, ServerLog log) {
        SwingUtilities.invokeLater(() -> new ServerStatusWindow(server, log).build());
    }

    /** Строит и показывает окно; вызывается в EDT. */
    private void build() {
        // Адрес — кликабельная «ссылка»: щелчок открывает страницу в браузере по умолчанию.
        String address = server.browserUri().toString();
        JLabel link = new JLabel("<html><a href=\"#\">" + escapeHtml(address) + "</a></html>");
        link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        link.setToolTipText("Щёлкните, чтобы открыть CashPrediction в браузере");
        link.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                browse();
            }
        });
        // Поле с тем же адресом — чтобы его можно было выделить и скопировать вручную.
        JTextField url = new JTextField(address);
        url.setEditable(false);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        url.setToolTipText("Адрес содержит секретный токен: не передавайте его другим");

        JButton open = new JButton("Открыть в браузере");
        open.addActionListener(e -> browse());
        JButton copy = new JButton("Копировать адрес");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(url.getText()), null));
        JButton stop = new JButton("Остановить сервер");
        stop.addActionListener(e -> confirmStop());

        JPanel top = new JPanel(new BorderLayout(6, 6));
        top.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
        top.add(new JLabel("Адрес:"), BorderLayout.WEST);
        JPanel addressPanel = new JPanel(new BorderLayout(0, 4));
        addressPanel.add(link, BorderLayout.NORTH);
        addressPanel.add(url, BorderLayout.CENTER);
        top.add(addressPanel, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(open);
        buttons.add(copy);
        buttons.add(stop);
        top.add(buttons, BorderLayout.SOUTH);

        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.tail(ServerLog.CAPACITY).forEach(this::append);
        log.addListener(logListener);
        JScrollPane scroll = new JScrollPane(logArea);
        scroll.setBorder(BorderFactory.createTitledBorder("Журнал"));

        frame.setLayout(new BorderLayout());
        frame.add(top, BorderLayout.NORTH);
        frame.add(scroll, BorderLayout.CENTER);
        java.net.URL icon = ServerStatusWindow.class.getResource("/web/favicon.png");
        if (icon != null) {
            frame.setIconImage(new ImageIcon(icon).getImage());
        }
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                confirmStop();
            }
        });
        server.addStopListener(() -> SwingUtilities.invokeLater(() -> {
            log.removeListener(logListener);
            frame.dispose();
        }));
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    /** Открывает страницу в браузере по умолчанию. */
    private void browse() {
        // Desktop.browse может ждать секунды (запуск браузера) — не в EDT, чтобы окно не «замерзало».
        Thread thread = new Thread(() -> {
            if (!WebMain.browse(server.browserUri())) {
                SwingUtilities.invokeLater(() ->
                        // JavaFX: Alert(ERROR) → Swing: JOptionPane.showMessageDialog(ERROR_MESSAGE) → Web: <dialog class="alert">
                        JOptionPane.showMessageDialog(frame, "Не удалось открыть браузер."
                                + "\nСкопируйте адрес и откройте его вручную.", "CashPrediction", JOptionPane.ERROR_MESSAGE));
            }
        }, "cashprediction-browser");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Экранирует текст для HTML-подписи Swing (в адресе нет угловых скобок, но токен лучше не доверять разметке).
     *
     * @param text текст
     * @return текст с заменёнными {@code & < > "}
     */
    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** Спрашивает подтверждение и корректно останавливает сервер. */
    private void confirmStop() {
        // JavaFX: Alert(CONFIRMATION) → Swing: JOptionPane.showConfirmDialog → Web: <dialog class="alert">
        int answer = JOptionPane.showConfirmDialog(frame,
                "Остановить сервер CashPrediction? Открытые вкладки браузера перестанут работать.",
                "CashPrediction", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            // Остановка пишет файлы и ждёт запись снимка — не в EDT, чтобы окно не «зависало».
            new Thread(server::shutdownAndExit, "cashprediction-shutdown").start();
        }
    }

    /**
     * Добавляет строку журнала в окно.
     *
     * @param line строка
     */
    private void append(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }
}
