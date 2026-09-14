package ru.cashprediction.swing.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import ru.cashprediction.core.session.StoreStatus;

/**
 * Строка состояния главного окна: файл плана (со звёздочкой при несохранённых изменениях), последнее сообщение
 * («План сохранён: …») и состояние хранилищ снимка сессии («Реестр ✓ 10:15:30 | XML ✓ 10:15:31»).
 *
 * <p>Состояние хранилищ приходит от {@code SessionRecorder.addStatusListener} из фонового потока записи; главное
 * окно пересылает его сюда через {@code invokeLater}. Класс используется только в потоке EDT.</p>
 */
public final class StatusBar extends JPanel {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JLabel fileLabel = new JLabel(" ");
    private final JLabel messageLabel = new JLabel(" ");
    private final JLabel storesLabel = new JLabel("Запись сессии ещё не начата");
    /** Последнее состояние каждого хранилища в порядке первого появления. */
    private final Map<String, StoreStatus> statuses = new LinkedHashMap<>();
    private final Timer clearMessage = new Timer(10_000, e -> setMessageText(" "));
    private String recordingNote = "";

    /** Создаёт строку состояния. */
    public StatusBar() {
        super(new BorderLayout(12, 0));
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Palette.BORDER),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)));
        clearMessage.setRepeats(false);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        right.add(storesLabel);
        messageLabel.setForeground(new Color(0x33, 0x55, 0x88));
        add(fileLabel, BorderLayout.WEST);
        add(messageLabel, BorderLayout.CENTER);
        add(right, BorderLayout.EAST);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        fileLabel.setToolTipText("Файл открытого плана; «*» - есть несохранённые изменения (Ctrl+S - сохранить)");
        storesLabel.setToolTipText("Снимок сессии для восстановления после сбоя: реестр Windows и XML-файл в CashMemory");
    }

    /**
     * Показывает файл плана.
     *
     * @param text  имя или путь файла («не сохранён», если файла нет)
     * @param dirty есть ли несохранённые изменения
     */
    public void setFile(String text, boolean dirty) {
        fileLabel.setText(text + (dirty ? " *" : ""));
    }

    /**
     * Показывает сообщение на 10 секунд.
     *
     * @param message текст
     */
    public void showMessage(String message) {
        setMessageText(message == null || message.isBlank() ? " " : message);
        clearMessage.restart();
    }

    private void setMessageText(String text) {
        messageLabel.setText(text);
    }

    /**
     * Текущее сообщение.
     *
     * @return текст сообщения (пробел, если сообщения нет)
     */
    public String message() {
        return messageLabel.getText();
    }

    /**
     * Особое состояние записи сессии («запись отключена: второй экземпляр»), показывается вместо хранилищ.
     *
     * @param note текст или пустая строка
     */
    public void setRecordingNote(String note) {
        recordingNote = note == null ? "" : note;
        render();
    }

    /**
     * Обновляет состояние хранилища.
     *
     * @param status состояние от рекордера сессии
     */
    public void updateStore(StoreStatus status) {
        statuses.put(status.storeId(), status);
        render();
    }

    /**
     * Последние состояния хранилищ.
     *
     * @return список в порядке появления
     */
    public List<StoreStatus> storeStatuses() {
        return new ArrayList<>(statuses.values());
    }

    /**
     * Текст правой части строки состояния.
     *
     * @return например «Реестр ✓ 10:15:30 | XML ✓ 10:15:31»
     */
    public String storesText() {
        return storesLabel.getText();
    }

    private void render() {
        if (!recordingNote.isBlank()) {
            storesLabel.setText(recordingNote);
            return;
        }
        if (statuses.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (StoreStatus s : statuses.values()) {
            String title = switch (s.storeId()) {
                case "registry" -> "Реестр";
                case "xml" -> "XML";
                default -> s.storeId();
            };
            String time = s.savedAt() == null ? "-" : TIME.format(s.savedAt());
            parts.add(title + (s.ok() ? " ✓ " + time : " ✗ " + time));
            details.add(title + ": " + (s.ok() ? "записано " + time : "ошибка - " + s.message()));
        }
        storesLabel.setText(String.join(" | ", parts));
        storesLabel.setForeground(statuses.values().stream().allMatch(StoreStatus::ok) ? getForeground() : Palette.EXPENSE);
        storesLabel.setToolTipText("<html>Снимок сессии для восстановления после сбоя:<br>"
                + String.join("<br>", details.stream().map(ru.cashprediction.swing.dialog.SwingText::escape).toList()) + "</html>");
    }
}
