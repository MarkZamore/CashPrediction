package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.*;
import ru.cashprediction.core.app.WindowHandle;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.ui.alert.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.*;

/** Сообщение с заданным ядром порядком кнопок и однократным ответом. */
public final class SwingAlerts implements WindowHandle {
    final SwingUiPort port;
    final JDialog dialog;
    final AlertSession session;
    final String id;
    AlertSpec spec;
    final JLabel header = new JLabel(), content = new JLabel(), glyph = new JLabel();
    final JTextArea details = new JTextArea(16, 80);
    final JButton detailsLink = new JButton();
    final JPanel buttonBar = new JPanel();
    final Map<String, JButton> buttons = new LinkedHashMap<>();
    private final Consumer<String> onButton;
    private final JScrollPane detailsScroll = new JScrollPane(details);
    private boolean answered, expanded, closed;

    /** Создаёт физическое сообщение, не подтверждая показ в конструкторе. */
    public SwingAlerts(SwingUiPort port, String id, AlertSpec spec, AlertSession session, Consumer<String> onButton) {
        this.port = port; this.id = id; this.session = session; this.onButton = onButton;
        // JavaFX: Alert → Swing: JDialog с иконкой JOptionPane → Web: dialog
        dialog = new JDialog(session == null ? port.visibleOwner() : port.owner(session.ownerId()), spec.windowTitle(), Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        // JavaFX: DialogPane → Swing: JPanel → Web: div.dialog-pane
        JPanel panel = new JPanel(new BorderLayout(10, 10)); panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16)); panel.setBackground(SwingLook.color(ColorToken.BG_SURFACE));
        JPanel heading = new JPanel(new BorderLayout(10, 0)); heading.setOpaque(false); header.setFont(SwingLook.font(FontToken.HEADER));
        heading.add(glyph, BorderLayout.WEST); heading.add(header); panel.add(heading, BorderLayout.NORTH);
        JPanel center = new JPanel(); center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS)); center.setOpaque(false);
        center.add(content); center.add(Box.createVerticalStrut(8)); center.add(detailsLink); center.add(detailsScroll); panel.add(center);
        details.setEditable(false); details.setFont(SwingLook.font(FontToken.MONO));
        detailsScroll.setPreferredSize(new Dimension(Math.max(430, spec.minWidth() - 30), 250));
        detailsLink.addActionListener(e -> { expanded = !expanded; detailsVisibility(); fitContent(); });
        buttonBar.setLayout(new BoxLayout(buttonBar, BoxLayout.X_AXIS)); buttonBar.setOpaque(false); panel.add(buttonBar, BorderLayout.SOUTH);
        dialog.setContentPane(panel);
        dialog.addWindowListener(new WindowAdapter() {
            /** Подтверждает фактический показ сообщения. */
            @Override public void windowOpened(WindowEvent e) { if (session != null && dialog.isShowing() && !closed) session.shown(); }
            /** Крестик выбирает заданную ядром кнопку отмены. */
            @Override public void windowClosing(WindowEvent e) { cancel(); }
        });
        dialog.getRootPane().registerKeyboardAction(e -> cancel(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        updateAlert(spec); fitContent(); dialog.setMinimumSize(new Dimension(dialog.getWidth(), 120));
        WindowBounds restored = session == null ? null : session.captureState().bounds();
        SwingUiPort.place(dialog, restored, dialog.getOwner());
        if (session != null) session.attach(this);
    }

    /** Показывает сообщение после привязки живой ручки сеансу ядра. */
    public void showLater() { SwingUtilities.invokeLater(() -> { if (!closed) dialog.setVisible(true); }); }

    private void answer(String id) {
        JButton button = buttons.get(id); if (answered || closed || button == null || !button.isEnabled()) return;
        answered = true; close(); if (session != null) session.closed(); onButton.accept(id);
    }
    private void cancel() { spec.buttons().stream().filter(b -> b.role() == ButtonRole.CANCEL).findFirst().ifPresent(b -> answer(b.id())); }

    /** Применяет обновление текста и кнопок сообщения в порядке ядра. */
    @Override public void updateAlert(AlertSpec spec) {
        this.spec = spec; dialog.setTitle(spec.windowTitle());
        Container pane = dialog.getContentPane();
        pane.setMinimumSize(new Dimension(spec.minWidth(), pane.getMinimumSize().height));
        header.setText(SwingLook.html(spec.header(), spec.minWidth() - 80)); header.putClientProperty("cp.text", spec.header());
        content.setText(SwingLook.html(spec.content(), spec.minWidth() - 32)); content.putClientProperty("cp.text", spec.content());
        glyph.setIcon(UIManager.getIcon(switch (spec.kind()) { case INFORMATION -> "OptionPane.informationIcon"; case WARNING -> "OptionPane.warningIcon"; case ERROR -> "OptionPane.errorIcon"; case CONFIRMATION -> "OptionPane.questionIcon"; }));
        glyph.setText(spec.glyph()); glyph.setFont(SwingLook.font(FontToken.HEADER).deriveFont(26f)); glyph.setForeground(SwingLook.color(ColorToken.ACCENT));
        if (!glyph.getText().isEmpty()) glyph.setIcon(null);
        details.setText(spec.details());
        // Новый текст показывается с начала: caret в конце иначе прокручивает настоящий viewport вправо и вниз.
        details.setCaretPosition(0);
        expanded = spec.detailsExpanded(); detailsVisibility(); buttonBar.removeAll(); buttons.clear(); buttonBar.add(Box.createHorizontalGlue());
        for (AlertButton b : spec.buttons()) {
            // JavaFX: ButtonType → Swing: JButton → Web: button
            JButton button = SwingLook.id(new SwingFractionalButton(b.text()), b.id()); button.setEnabled(b.enabled()); SwingLook.tooltip(button, b.tooltip());
            SwingLook.dialogButton(button);
            if (!buttons.isEmpty()) buttonBar.add(Box.createHorizontalStrut(DesignTokens.FORM_VGAP));
            button.addActionListener(e -> answer(b.id())); buttons.put(b.id(), button); buttonBar.add(button);
        }
        dialog.getRootPane().setDefaultButton(buttons.get(spec.defaultButtonId())); dialog.revalidate(); dialog.repaint();
    }
    /** Ширина из спецификации относится к контенту, а не к рамке и заголовку ОС. */
    private void fitContent() {
        dialog.pack();
        Insets decoration = dialog.getInsets();
        dialog.setSize(spec.minWidth() + decoration.left + decoration.right, dialog.getHeight());
        dialog.validate();
    }
    private void detailsVisibility() { boolean has = !details.getText().isEmpty(); detailsLink.setVisible(has); detailsLink.setText(UiText.get(expanded ? "details.hide" : "details.show")); detailsScroll.setVisible(has && expanded); }
    /** Эта ручка предназначена только для сообщений. */
    @Override public void update(FormView view) { throw new UnsupportedOperationException("Alert handle"); }
    /** Закрывает окно без повторного ответа. */
    @Override public void close() { if (!closed) { closed = true; dialog.dispose(); port.alerts.remove(id); port.refreshTooltips(); } }
    /** Поднимает уже открытое сообщение. */
    @Override public void toFront() { dialog.toFront(); }
    /** Возвращает актуальные экранные границы. */
    @Override public WindowBounds bounds() { return SwingUiPort.bounds(dialog); }
    /** Проверяет фактическую видимость сообщения. */
    @Override public boolean showing() { return dialog.isShowing(); }
}
