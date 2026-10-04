package ru.cashprediction.swing.ui;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import ru.cashprediction.core.app.FocusTarget;
import ru.cashprediction.core.app.UiIntents;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.token.*;

/** Тулбар, целиком определяемый моделью ядра. */
public final class SwingToolbar extends JPanel {
    private final UiIntents intents;
    private final SwingMenus menus;
    private final SwingPaintContext paintContext;
    private JTextField filter;
    private Timer filterDelay;
    private boolean applying;
    private final Map<String, JComponent> widgets = new HashMap<>();

    /** Создаёт пустую панель действий. */
    public SwingToolbar(UiIntents intents) {
        this(intents, null);
    }

    /** Получает context уже созданного content root до первоначального decode своих значков. */
    SwingToolbar(UiIntents intents, SwingPaintContext paintContext) {
        this.paintContext = paintContext;
        this.intents = intents; menus = new SwingMenus(intents);
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS) {
            /** Размещает компоненты тем же точным алгоритмом и при автоматической валидации контейнера. */
            @Override public void layoutContainer(Container target) { layoutControls(); }
        });
        setBackground(SwingLook.color(ColorToken.BG_WINDOW));
        setBorder(BorderFactory.createEmptyBorder(DesignTokens.SPACING, 0, DesignTokens.SPACING, 0));
        SwingLook.id(this, "toolbar");
    }

    /** Наблюдает полный paint toolbar, сохраняя непокрытый delegate census явным отказом. */
    @Override public void paint(Graphics graphics) {
        if (paintContext == null) super.paint(graphics); else paintContext.paint(this, graphics, super::paint);
    }

    /** Применяет готовые узлы, сохраняя фокус и текущий ввод фильтра. */
    public void render(ToolbarModel model) {
        boolean focused = filter != null && filter.isFocusOwner();
        int caret = filter == null ? 0 : filter.getCaretPosition();
        if (filterDelay != null) filterDelay.stop();
        applying = true;
        // Живое поле нельзя удалять при debounce-render: AWT теряет его focus owner до следующей клавиши.
        Component retainedFilter = filter == null ? null : filter.getParent();
        for (Component child : getComponents()) if (child != retainedFilter) remove(child);
        widgets.clear();
        var order = new ArrayList<Component>();
        Map<String, ButtonGroup> groups = new HashMap<>();
        for (ToolbarNode node : model.items()) {
            JComponent widget = switch (node) {
                case ToolbarNode.SplitButton n -> {
                    // JavaFX: SplitMenuButton → Swing: JPanel с основной кнопкой и стрелкой → Web: пара button
                    JPanel panel = ownerPanel(new BorderLayout()); panel.setOpaque(false);
                    JButton main = button(n.text(), n.tooltip());
                    main.setEnabled(n.main().enabled());
                    main.addActionListener(e -> intents.command(n.main().command(), n.main().args(), InvokeSource.TOOLBAR));
                    JButton arrow = menuButton("", n.tooltip(), n.items());
                    size(arrow, DesignTokens.CONTROL_HEIGHT - 2 * DesignTokens.SPACING);
                    panel.add(main); panel.add(arrow, BorderLayout.EAST);
                    panel.putClientProperty("cp.main", main); panel.putClientProperty("cp.popup", arrow.getClientProperty("cp.popup"));
                    yield panel;
                }
                case ToolbarNode.MenuButton n -> {
                    // JavaFX: MenuButton → Swing: JButton со стрелкой PNG → Web: button + div[role=menu]
                    JButton button = menuButton(n.text(), n.tooltip(), n.items()); emphasis(button, n.emphasis()); yield button;
                }
                case ToolbarNode.Toggle n -> {
                    JToggleButton button = paintContext == null ? new JToggleButton(n.text(), n.selected()) : paintContext.toggle(n.text(), n.selected()); button.setFocusable(false);
                    groups.computeIfAbsent(n.group(), unused -> new ButtonGroup()).add(button);
                    SwingLook.tooltip(button, n.tooltip());
                    button.addActionListener(e -> intents.command(n.command(), CommandArgs.NONE, InvokeSource.TOOLBAR));
                    yield button;
                }
                case ToolbarNode.Button n -> {
                    JButton button = button(n.glyphOrText(), n.tooltip());
                    if (n.command() == CommandId.EDIT_UNDO || n.command() == CommandId.EDIT_REDO)
                        SwingIcons.standalone(button, n.glyphOrText(), paintContext);
                    button.setEnabled(n.enabled()); emphasis(button, n.emphasis());
                    button.addActionListener(e -> intents.command(n.command(), CommandArgs.NONE, InvokeSource.TOOLBAR)); yield button;
                }
                case ToolbarNode.FilterField n -> filter(n);
                case ToolbarNode.Separator n -> {
                    JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
                    separator.setPreferredSize(new Dimension(1, DesignTokens.CONTROL_HEIGHT)); yield separator;
                }
                case ToolbarNode.Spacer n -> {
                    JPanel spacer = new JPanel(); spacer.setOpaque(false);
                    spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28)); yield spacer;
                }
            };
            SwingLook.id(widget, node.id()); widget.putClientProperty("cp.kind", node.getClass().getSimpleName());
            if (widget instanceof AbstractButton button) fit(button);
            widget.setAlignmentY(Component.CENTER_ALIGNMENT);
            widgets.put(node.id(), widget);
            if (!(node instanceof ToolbarNode.Spacer)) {
                Dimension preferred = new Dimension(widget.getPreferredSize().width, DesignTokens.CONTROL_HEIGHT);
                widget.setPreferredSize(preferred); widget.setMinimumSize(preferred); widget.setMaximumSize(preferred);
            }
            if (!order.isEmpty()) order.add(Box.createHorizontalStrut(DesignTokens.SPACING));
            order.add(widget);
        }
        if (retainedFilter != null && !order.contains(retainedFilter)) remove(retainedFilter);
        for (int index = 0; index < order.size(); index++) {
            Component child = order.get(index);
            if (child.getParent() != this) add(child);
            setComponentZOrder(child, index);
        }
        setPreferredSize(new Dimension(super.getPreferredSize().width, DesignTokens.TOOLBAR_HEIGHT));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, DesignTokens.TOOLBAR_HEIGHT));
        applying = false; revalidate(); repaint();
        if (focused && filter != null) { filter.requestFocusInWindow(); filter.setCaretPosition(Math.min(caret, filter.getText().length())); }
    }

    /** Возвращает реальное поле фильтра. */
    public JTextField filter() { return filter; }

    /** Размещает фиксированные размеры без округления BoxLayout; только распорка получает остаток ширины. */
    private void layoutControls() {
        Insets insets = getInsets(); int fixed = 0;
        for (Component child : getComponents()) {
            if (!(child instanceof JComponent c && "Spacer".equals(c.getClientProperty("cp.kind")))) fixed += child.getPreferredSize().width;
        }
        int remaining = Math.max(0, getWidth() - insets.left - insets.right - fixed), x = insets.left;
        for (Component child : getComponents()) {
            int width = child instanceof JComponent c && "Spacer".equals(c.getClientProperty("cp.kind")) ? remaining : child.getPreferredSize().width;
            int height = Math.min(DesignTokens.CONTROL_HEIGHT, Math.max(0, getHeight() - insets.top - insets.bottom));
            child.setBounds(x, insets.top + (getHeight() - insets.top - insets.bottom - height) / 2, width, height); x += width;
        }
    }

    /** Возвращает узел по стабильному id. */
    public JComponent widget(String id) { return widgets.get(id); }

    private JPanel filter(ToolbarNode.FilterField n) {
        if (filter != null && (n.id() + ".input").equals(filter.getClientProperty("cp.id"))
                && filter.getParent() instanceof JPanel retained && retained.getParent() == this) {
            // Обновляем модель без нового document, listeners или detach; каретка остаётся при неизменном тексте.
            if (!filter.getText().equals(n.text())) filter.setText(n.text());
            filter.putClientProperty("cp.prompt", n.prompt());
            filter.getAccessibleContext().setAccessibleName(n.prompt());
            filter.getAccessibleContext().setAccessibleDescription(n.tooltip());
            SwingLook.tooltip(filter, n.tooltip());
            filterDelay.setDelay(n.debounceMs()); filterDelay.setInitialDelay(n.debounceMs());
            for (Component child : retained.getComponents()) if (child instanceof JButton clear) {
                clear.setVisible(n.clearVisible()); SwingLook.tooltip(clear, n.clearTooltip());
            }
            filter.setMargin(new Insets(0, 0, 0, n.clearVisible() ? DesignTokens.CONTROL_HEIGHT + DesignTokens.SPACING : 0));
            retained.setPreferredSize(new Dimension(n.widthPx(), DesignTokens.CONTROL_HEIGHT));
            return retained;
        }
        JPanel panel = ownerPanel(new BorderLayout(4, 0)); panel.setOpaque(false);
        filter = SwingLook.id(new FilterInput(n.text(), paintContext), n.id() + ".input");
        filter.putClientProperty("cp.prompt", n.prompt());
        filter.getAccessibleContext().setAccessibleName(n.prompt());
        filter.getAccessibleContext().setAccessibleDescription(n.tooltip());
        filter.setPreferredSize(new Dimension(n.widthPx(), 28)); SwingLook.tooltip(filter, n.tooltip());
        JTextField current = filter;
        filterDelay = new Timer(n.debounceMs(), e -> intents.filterText(current.getText())); filterDelay.setRepeats(false);
        current.getDocument().addDocumentListener(new DocumentListener() {
            /** Передаёт изменение после общей задержки. */
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            /** Передаёт удаление после общей задержки. */
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            /** Передаёт изменение атрибутов после задержки. */
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
            private void changed() { if (!applying) filterDelay.restart(); }
        });
        current.addActionListener(e -> { filterDelay.stop(); intents.filterText(current.getText()); intents.command(CommandId.FILTER_FOCUS_TABLE, CommandArgs.NONE, InvokeSource.MAIN); });
        JButton clear = button("✕", n.clearTooltip());
        SwingIcons.standalone(clear, "✕", paintContext); fit(clear); clear.setVisible(n.clearVisible());
        clear.addActionListener(e -> { filterDelay.stop(); intents.filterText(""); });
        SwingLook.id(clear, n.id() + ".clear");
        panel.add(current); panel.add(clear, BorderLayout.EAST);
        // Очистка лежит внутри поля: его настоящая ширина 220 не меняется при появлении кнопки.
        current.setMargin(new Insets(0, 0, 0, n.clearVisible() ? DesignTokens.CONTROL_HEIGHT + DesignTokens.SPACING : 0));
        panel.setComponentZOrder(clear, 0);
        panel.setLayout(new BorderLayout() {
            /** Поле занимает весь узел; для текста зарезервирован отступ под реальную кнопку очистки. */
            @Override public void layoutContainer(Container target) {
                current.setBounds(0, 0, target.getWidth(), target.getHeight());
                clear.setBounds(Math.max(0, target.getWidth() - DesignTokens.CONTROL_HEIGHT), 0, DesignTokens.CONTROL_HEIGHT, target.getHeight());
            }
        });
        panel.setPreferredSize(new Dimension(n.widthPx(), DesignTokens.CONTROL_HEIGHT));
        return panel;
    }

    /** Рисует локализованную подсказку модели поверх пустого поля, не меняя документ. */
    private static final class FilterInput extends JTextField {
        private final SwingPaintContext context;
        /** Создаёт поле только с настоящим значением фильтра. */
        FilterInput(String text, SwingPaintContext context) { super(text); this.context = context; }

        /** Наблюдает полный paint поля, включая подсказку, border и UI delegate. */
        @Override public void paint(Graphics graphics) {
            if (context == null) super.paint(graphics); else context.paint(this, graphics, super::paint);
        }

        /** Показывает подсказку только при пустом документе внутри доступной области текста. */
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getDocument().getLength() != 0) return;
            Object value = getClientProperty("cp.prompt");
            if (!(value instanceof String prompt) || prompt.isEmpty()) return;
            Insets insets = getInsets();
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.clipRect(insets.left, insets.top,
                        Math.max(0, getWidth() - insets.left - insets.right),
                        Math.max(0, getHeight() - insets.top - insets.bottom));
                copy.setFont(getFont()); copy.setColor(SwingLook.color(ColorToken.TEXT_MUTED));
                FontMetrics metrics = copy.getFontMetrics();
                int baseline = insets.top
                        + (getHeight() - insets.top - insets.bottom - metrics.getHeight()) / 2 + metrics.getAscent();
                copy.drawString(prompt, insets.left, baseline);
            } finally { copy.dispose(); }
        }
    }

    private JButton button(String text, String tooltip) {
        JButton button = paintContext == null ? new JButton(text) : paintContext.button(text);
        SwingIcons.decorate(button, paintContext); button.setFocusable(false); fit(button);
        SwingLook.tooltip(button, tooltip); return button;
    }

    private JButton menuButton(String text, String tooltip, java.util.List<MenuNode> nodes) {
        // JavaFX: MenuButton → Swing: JButton + JPopupMenu → Web: button + div[role=menu]
        JButton button = button(text, tooltip); button.setIcon(SwingIcons.icon("▾", null, DesignTokens.INLINE_ICON_SIZE, paintContext));
        button.setDisabledIcon(SwingIcons.icon("▾", ColorToken.TEXT_MUTED, DesignTokens.INLINE_ICON_SIZE, paintContext));
        // PNG занимает 16 px внутри общей области стрелки 20 px, как в JavaFX и Web.
        button.setIconTextGap(DesignTokens.CONTROL_HEIGHT - 2 * DesignTokens.SPACING
                - DesignTokens.INLINE_ICON_SIZE);
        button.setHorizontalTextPosition(SwingConstants.LEFT); fit(button);
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: div[role=menu]
        JPopupMenu popup = menus.popup(nodes, InvokeSource.TOOLBAR); button.putClientProperty("cp.popup", popup);
        button.addActionListener(e -> popup.show(button, 0, button.getHeight())); return button;
    }

    /** Убирает добавочные Metal Insets; размер определяется только токенами и реальной метрикой текста. */
    private void fit(AbstractButton button) {
        // Обычный Color исключает серую Metal-подложку/градиент над общим фоном тулбара.
        button.setBackground(SwingLook.color(ColorToken.BG_WINDOW));
        if (!button.getFont().isBold()) button.setFont(SwingLook.font(FontToken.BASE));
        button.setMargin(new Insets(0, 0, 0, 0));
        if (button.getClientProperty("cp.text") instanceof String && button.getIcon() != null) {
            int inset = (DesignTokens.CONTROL_HEIGHT - DesignTokens.INLINE_ICON_SIZE) / 2;
            button.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(SwingLook.color(ColorToken.BORDER)),
                    BorderFactory.createEmptyBorder(inset - 1, inset - 1, inset - 1, inset - 1)));
            size(button, DesignTokens.CONTROL_HEIGHT);
            return;
        }
        button.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(SwingLook.color(ColorToken.BORDER)),
                BorderFactory.createEmptyBorder(DesignTokens.TOOLBAR_BUTTON_PAD_V - 1, DesignTokens.TOOLBAR_BUTTON_PAD_H - 1,
                        DesignTokens.TOOLBAR_BUTTON_PAD_V - 1, DesignTokens.TOOLBAR_BUTTON_PAD_H - 1)));
        int width = SwingIcons.textWidth(button.getText(), button.getFontMetrics(button.getFont())) + 2 * DesignTokens.TOOLBAR_BUTTON_PAD_H;
        if (button.getIcon() != null) width += button.getIcon().getIconWidth() + button.getIconTextGap();
        if (button.getIcon() == null && button.getText().codePointCount(0, button.getText().length()) == 1) width = DesignTokens.CONTROL_HEIGHT;
        size(button, width);
    }
    private static void size(JComponent widget, int width) {
        Dimension size = new Dimension(width, DesignTokens.CONTROL_HEIGHT); widget.setPreferredSize(size); widget.setMinimumSize(size); widget.setMaximumSize(size);
    }

    private void emphasis(AbstractButton button, Emphasis emphasis) {
        if (emphasis != Emphasis.NONE) {
            button.setFont(SwingLook.font(FontToken.BASE).deriveFont(Font.BOLD));
            button.setForeground(SwingLook.color(emphasis.color()));
        }
    }

    /** Выбирает явно привязанный owner только при наличии selftest context корня. */
    private JPanel ownerPanel(LayoutManager layout) {
        return paintContext == null ? new JPanel(layout) : paintContext.panel(layout);
    }

}
