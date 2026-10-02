package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.*;

/** Универсальная форма по FormSpec, без решений о валидации и бизнес-поведении. */
public class SwingFormDialog implements WindowHandle {
    final SwingUiPort port;
    final FormSession session;
    final FormSpec spec;
    final JDialog dialog;
    final JPanel content = new JPanel(new BorderLayout(10, 8));
    final JPanel body = new JPanel(new BorderLayout(10, 8));
    final JPanel buttonBar = new JPanel();
    final JLabel header = new JLabel(), glyph = new JLabel(), problem = new JLabel();
    final JTextArea details = new JTextArea(16, 80);
    final JButton detailsLink = new JButton();
    final Map<String, List<SwingFieldWidgets.Binding>> fields = new LinkedHashMap<>();
    final Map<String, JButton> buttons = new LinkedHashMap<>();
    final Map<String, ButtonGroup> radioGroups = new LinkedHashMap<>();
    final List<JLabel> sections = new ArrayList<>(), hints = new ArrayList<>(), results = new ArrayList<>();
    final Placement placement;
    boolean applying, closed;
    long clientRev;
    int page = -1;
    private JPanel resultPanel;
    private final JScrollPane detailsScroll = new JScrollPane(details);
    private boolean detailsExpanded;

    /** Создаёт каркас, не подтверждая показ раньше реального события окна. */
    public SwingFormDialog(SwingUiPort port, FormSession session, FormSpec spec, FormView initial, Placement placement) {
        this.port = port; this.session = session; this.spec = spec; this.placement = placement;
        // JavaFX: Dialog<R> → Swing: JDialog → Web: dialog
        dialog = new JDialog(port.owner(placement.ownerId()), spec.windowTitle(), spec.modal() ? Dialog.ModalityType.APPLICATION_MODAL : Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE); dialog.setResizable(spec.resizable());
        // §5.6.1: всплывающая форма имеет рамку, отступ 10 и промежутки 6; диалоги сохраняют §6.
        int padding = spec.presentation() == Presentation.POPUP ? 10 : 16;
        // JavaFX: DialogPane → Swing: JPanel → Web: div.dialog-pane
        content.setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding)); content.setBackground(SwingLook.color(ColorToken.BG_SURFACE));
        if (spec.presentation() == Presentation.POPUP) {
            content.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(SwingLook.color(ColorToken.BORDER_STRONG)), content.getBorder()));
            ((BorderLayout) content.getLayout()).setVgap(6); body.setOpaque(false);
            problem.setFont(SwingLook.font(FontToken.SMALL));
        }
        // В форме без кнопок фокус может перейти с поля на настоящее содержимое всплывающего окна.
        content.setFocusable(spec.buttons().isEmpty());
        JPanel top = new JPanel(new BorderLayout(10, 0)); top.setOpaque(false);
        glyph.setText(spec.glyph()); glyph.setFont(SwingLook.font(FontToken.HEADER).deriveFont(26f)); glyph.setForeground(SwingLook.color(ColorToken.ACCENT));
        header.setFont(SwingLook.font(FontToken.HEADER)); top.add(glyph, BorderLayout.WEST); top.add(header);
        top.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, SwingLook.color(ColorToken.BORDER)));
        if (spec.presentation() == Presentation.POPUP) {
            top.setBorder(null); glyph.setVisible(!spec.glyph().isEmpty());
        }
        content.add(top, BorderLayout.NORTH); content.add(body);
        JPanel bottom = new JPanel(); bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS)); bottom.setOpaque(false);
        problem.setPreferredSize(new Dimension(spec.width() - 2 * padding, 32)); bottom.add(problem);
        details.setEditable(false); details.setFont(SwingLook.font(FontToken.MONO));
        detailsLink.addActionListener(e -> { detailsExpanded = !detailsExpanded; updateDetails(); dialog.pack(); });
        detailsLink.setAlignmentX(0); detailsScroll.setAlignmentX(0); detailsScroll.setPreferredSize(new Dimension(spec.width() - 2 * padding, 230));
        bottom.add(detailsLink); bottom.add(detailsScroll);
        buttonBar.setLayout(new BoxLayout(buttonBar, BoxLayout.X_AXIS)); buttonBar.setOpaque(false); buttonBar.setFocusable(true);
        buttonBar.setAlignmentX(0);
        boolean spacer = false;
        for (ButtonSpec buttonSpec : spec.buttons()) {
            if (!spacer && buttonSpec.role() != ButtonRole.LEFT) { buttonBar.add(Box.createHorizontalGlue()); spacer = true; }
            if (!buttons.isEmpty()) buttonBar.add(Box.createHorizontalStrut(DesignTokens.FORM_VGAP));
            JButton button = button(buttonSpec.id(), buttonSpec.text(), buttonSpec.tooltip()); buttonBar.add(button);
        }
        bottom.add(Box.createVerticalStrut(8)); bottom.add(buttonBar);
        if (spec.presentation() != Presentation.POPUP) content.add(bottom, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.addWindowListener(new WindowAdapter() {
            /** Крестик проходит тем же путём ядра, что отмена формы. */
            @Override public void windowClosing(WindowEvent e) { session.closeRequested(); }
            /** Подтверждает показ лишь видимого окна. */
            @Override public void windowOpened(WindowEvent e) { if (!closed && dialog.isShowing()) { session.shown(); focusFirst(); } }
        });
        dialog.addComponentListener(new ComponentAdapter() {
            /** Сразу передаёт актуальные границы сеансу. */
            @Override public void componentMoved(ComponentEvent e) { if (!closed && dialog.isShowing()) session.boundsChanged(bounds()); }
            /** Сразу передаёт новый размер сеансу. */
            @Override public void componentResized(ComponentEvent e) { if (!closed && dialog.isShowing()) session.boundsChanged(bounds()); }
        });
        dialog.getRootPane().registerKeyboardAction(e -> session.closeRequested(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
        update(initial);
        fitContent(); dialog.setMinimumSize(new Dimension(dialog.getWidth(), 120));
        SwingUiPort.place(dialog, placement.bounds(), port.owner(placement.ownerId()));
    }

    /** Показывает окно после возврата ручки ядру, чтобы сеанс успел привязать её. */
    public void showLater() { SwingUtilities.invokeLater(() -> { if (!closed) dialog.setVisible(true); }); }

    /** Применяет модель формы и правило эха к настоящим виджетам. */
    @Override public void update(FormView view) {
        if (closed) return; applying = true;
        if (page != view.page()) { page = view.page(); buildPage(); }
        header.setText(SwingLook.html(view.header(), spec.presentation() == Presentation.POPUP ? innerContentWidth() : Math.max(200, spec.width() - 80))); header.putClientProperty("cp.text", view.header());
        view.fields().forEach((id, state) -> fields.getOrDefault(id, List.of()).forEach(binding -> binding.apply(state)));
        fields.values().forEach(list -> list.forEach(binding -> binding.preview(view.preview())));
        problem.setText(SwingLook.html(view.problem().display(), spec.presentation() == Presentation.POPUP ? innerContentWidth() : spec.width() - 32)); problem.putClientProperty("cp.text", view.problem().display()); problem.setForeground(SwingLook.color(view.problem().color()));
        if (spec.presentation() == Presentation.POPUP) { problem.setPreferredSize(null); problem.setVisible(!view.problem().display().isEmpty()); }
        JButton defaultButton = null;
        for (Map.Entry<String, JButton> entry : buttons.entrySet()) {
            ButtonView state = view.buttons().get(entry.getKey()); JButton button = entry.getValue();
            if (state != null) { button.setEnabled(state.enabled()); button.setVisible(state.visible()); if (state.text() != null) button.setText(state.text()); if (state.tooltip() != null) SwingLook.tooltip(button, state.tooltip()); }
            if (entry.getKey().equals(spec.defaultButtonId()) && button.isVisible()) defaultButton = button;
        }
        dialog.getRootPane().setDefaultButton(defaultButton);
        if (resultPanel != null) {
            resultPanel.removeAll(); results.clear();
            for (ResultLine line : view.results()) { JLabel label = SwingLook.label(SwingLook.html(line.text(), spec.width() - 80), line.color(), FontToken.BASE); label.putClientProperty("cp.text", line.text()); results.add(label); resultPanel.add(label); }
        }
        details.setText(view.details()); detailsExpanded = view.detailsExpanded(); updateDetails();
        applying = false; body.revalidate(); content.revalidate();
        if (dialog.isShowing() && !spec.resizable()) {
            Dimension previous = dialog.getSize(); fitContent();
            if (placement.bounds() == null && !previous.equals(dialog.getSize())) SwingUiPort.place(dialog, null, dialog.getOwner());
        }
        dialog.repaint();
    }

    private void buildPage() {
        fields.clear(); radioGroups.clear(); sections.clear(); hints.clear(); results.clear(); resultPanel = null; body.removeAll();
        JPanel grid = new JPanel(new GridBagLayout()); grid.setOpaque(false); int row = 0;
        boolean popup = spec.presentation() == Presentation.POPUP, problemAdded = false;
        for (FormRow formRow : spec.pages().get(page).rows()) {
            switch (formRow) {
                case FormRow.Field field -> {
                    SwingFieldWidgets.Binding binding = field(field.field());
                    if (field.field().wide() || popup && field.field().label().isEmpty()) add(grid, binding.root, 0, row++, 2, 1); else { add(grid, binding.label, 0, row, 1, 0); add(grid, binding.root, 1, row++, 1, 1); }
                }
                case FormRow.Inline inline -> {
                    JLabel label = SwingFieldWidgets.formLabel(inline.label().isEmpty() ? "" : inline.label() + ":");
                    add(grid, label, 0, row, 1, 0); JPanel group = new JPanel(); group.setOpaque(false); group.setLayout(new BoxLayout(group, BoxLayout.X_AXIS));
                    for (FieldSpec fieldSpec : inline.fields()) { group.add(field(fieldSpec).root); group.add(Box.createHorizontalStrut(6)); }
                    add(grid, group, 1, row++, 1, 1);
                }
                case FormRow.Section section -> {
                    JLabel label = SwingLook.label(section.caption(), ColorToken.TEXT_PRIMARY, FontToken.HEADER);
                    // §6.0: раздел состоит из настоящего разделителя и жирной подписи.
                    label.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, SwingLook.color(ColorToken.BORDER)));
                    sections.add(label); add(grid, label, 0, row++, 2, 1);
                }
                case FormRow.Hint hint -> {
                    if (popup && !problemAdded) { add(grid, problem, 0, row++, 2, 1); problemAdded = true; }
                    JLabel label = SwingLook.label(SwingLook.html(hint.text(), popup ? innerContentWidth() : Math.max(200, spec.width() - 60)), ColorToken.TEXT_MUTED, FontToken.SMALL); label.putClientProperty("cp.text", hint.text()); hints.add(label); add(grid, label, 0, row++, 2, 1);
                }
                case FormRow.Results result -> { resultPanel = new SwingResultsPanel(result.id(), result.minLines()); add(grid, resultPanel, 0, row++, 2, 1); }
                case FormRow.SideColumn side -> {
                    JPanel panel = new JPanel(new BorderLayout(DesignTokens.SPACING, DesignTokens.FORM_VGAP)); panel.setOpaque(false);
                    panel.setPreferredSize(new Dimension(DesignTokens.RULE_PREVIEW_WIDTH, 0));
                    JLabel label = SwingLook.label(side.caption(), ColorToken.TEXT_PRIMARY, FontToken.HEADER); sections.add(label); panel.add(label, BorderLayout.NORTH);
                    panel.add(field(side.preview()).root); JPanel sideButtons = new JPanel(); sideButtons.setOpaque(false); sideButtons.setLayout(new BoxLayout(sideButtons, BoxLayout.Y_AXIS));
                    for (FormRow.FormButtonSpec b : side.buttons()) sideButtons.add(button(b.id(), b.text(), b.tooltip()));
                    panel.add(sideButtons, BorderLayout.SOUTH);
                    // §6.3: колонка предпросмотра отделена вертикальной линией, её содержимое остаётся шириной 300.
                    JPanel separated = new JPanel(new BorderLayout(DesignTokens.FORM_HGAP, 0)); separated.setOpaque(false);
                    // JavaFX: Separator → Swing: JSeparator → Web: div.form-divider
                    separated.add(new JSeparator(SwingConstants.VERTICAL), BorderLayout.WEST); separated.add(panel);
                    body.add(separated, BorderLayout.EAST);
                }
            }
        }
        if (popup && !problemAdded) add(grid, problem, 0, row, 2, 1);
        if (popup) body.add(grid);
        else { JScrollPane scroll = new JScrollPane(grid); scroll.setBorder(null); body.add(scroll); }
        body.putClientProperty("cp.grid", grid);
    }

    private SwingFieldWidgets.Binding field(FieldSpec field) {
        SwingFieldWidgets.Binding binding = SwingFieldWidgets.create(field, this); fields.computeIfAbsent(field.id(), unused -> new ArrayList<>()).add(binding); return binding;
    }
    /** Возвращает ширину текста внутри настоящей рамки и отступов содержимого. */
    private int innerContentWidth() {
        Insets insets = content.getInsets();
        return Math.max(1, spec.width() - insets.left - insets.right);
    }
    /** Размер контента задаёт FormSpec, высоту задают реальные строки и доступная область экрана. */
    private void fitContent() {
        content.setPreferredSize(null);
        dialog.pack();
        Insets decoration = dialog.getInsets();
        Rectangle screen = dialog.getGraphicsConfiguration().getBounds();
        Insets taskbar = Toolkit.getDefaultToolkit().getScreenInsets(dialog.getGraphicsConfiguration());
        int available = screen.height - taskbar.top - taskbar.bottom;
        dialog.setSize(spec.width() + decoration.left + decoration.right, Math.min(dialog.getHeight(), available));
        dialog.validate();
    }
    private void add(JPanel grid, Component child, int x, int y, int span, double weight) {
        GridBagConstraints c = new GridBagConstraints(); c.gridx = x; c.gridy = y; c.gridwidth = span; c.weightx = weight; c.fill = GridBagConstraints.HORIZONTAL; c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = spec.presentation() == Presentation.POPUP ? new Insets(y == 0 ? 0 : 6, 0, 0, 0) : new Insets(4, 5, 4, 5);
        grid.add(child, c);
    }
    private JButton button(String id, String text, String tooltip) {
        // JavaFX: ButtonType → Swing: JButton → Web: button
        JButton button = SwingLook.id(new JButton(text), id); SwingLook.tooltip(button, tooltip);
        SwingLook.dialogButton(button);
        button.addActionListener(e -> { commit(); session.buttonPressed(id); }); buttons.put(id, button); return button;
    }
    /** Подтверждает поля через их привязки, чтобы эхо ядра учитывало последнюю отправленную правку. */
    void commit() { fields.values().forEach(list -> { if (!list.isEmpty()) list.getFirst().commit(); }); }
    private void updateDetails() {
        boolean has = !details.getText().isEmpty(); detailsLink.setVisible(has); detailsScroll.setVisible(has && detailsExpanded);
        detailsLink.setText(UiText.get(detailsExpanded ? "details.hide" : "details.show"));
    }
    private void focusFirst() {
        fields.values().stream().flatMap(List::stream).filter(b -> b.spec.focusFirst() && b.root.isVisible() && b.input.isEnabled()).findFirst().ifPresent(b -> { b.input.requestFocusInWindow(); if (b.input instanceof javax.swing.text.JTextComponent text) text.selectAll(); });
    }
    /** Не используется для форм: сообщения имеют собственную ручку. */
    @Override public void updateAlert(ru.cashprediction.core.ui.alert.AlertSpec alert) { throw new UnsupportedOperationException("Form handle"); }
    /** Закрывает физическое окно по запросу ядра без повторного обратного вызова. */
    @Override public void close() { if (!closed) { closed = true; dialog.dispose(); port.forms.remove(session.windowId()); port.refreshTooltips(); } }
    /** Поднимает уже открытое окно. */
    @Override public void toFront() { dialog.toFront(); dialog.requestFocus(); }
    /** Возвращает реальные экранные границы окна. */
    @Override public WindowBounds bounds() { return SwingUiPort.bounds(dialog); }
    /** Проверяет реальный показ физического окна. */
    @Override public boolean showing() { return dialog.isShowing(); }
}
