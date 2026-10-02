package ru.cashprediction.swing.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import ru.cashprediction.core.app.UiIntents;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.token.*;

/** Универсальный перевод каждого узла меню ядра в настоящий виджет Swing. */
public final class SwingMenus {
    private final UiIntents intents;

    /** Создаёт рендерер меню без собственного списка команд. */
    public SwingMenus(UiIntents intents) { this.intents = intents; }

    /** Строит строку меню в порядке модели. */
    public JMenuBar bar(MenuBarModel model) {
        // JavaFX: MenuBar → Swing: JMenuBar → Web: div[role=menubar]
        JMenuBar bar = SwingLook.id(new JMenuBar(), "menuBar");
        Map<String, ButtonGroup> groups = new HashMap<>();
        model.menus().forEach(n -> bar.add(widget(n, InvokeSource.MENU, groups)));
        bar.setPreferredSize(new Dimension(bar.getPreferredSize().width, ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT));
        return bar;
    }

    /** Строит контекстное меню или меню кнопки. */
    public JPopupMenu popup(List<MenuNode> nodes, InvokeSource source) {
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: div[role=menu]
        JPopupMenu popup = new JPopupMenu();
        popupStyle(popup);
        Map<String, ButtonGroup> groups = new HashMap<>();
        nodes.forEach(n -> popup.add(widget(n, source, groups)));
        return popup;
    }

    /** Строит узел, сохраняя id, доступность, отметки и порядок ядра. */
    public JComponent widget(MenuNode node, InvokeSource source) {
        return widget(node, source, new HashMap<>());
    }

    private JComponent widget(MenuNode node, InvokeSource source, Map<String, ButtonGroup> groups) {
        JComponent widget = switch (node) {
            case MenuNode.Submenu n -> {
                // JavaFX: Menu → Swing: JMenu → Web: div[role=menuitem] + div[role=menu]
                JMenu menu = new JMenu(n.text()) {
                    /** Привязывает подсказку к целому submenu тем же способом, что к обычному пункту. */
                    @Override public java.awt.Point getToolTipLocation(MouseEvent event) { return new java.awt.Point(DesignTokens.SPACING, getHeight() + DesignTokens.SPACING); }
                };
                popupStyle(menu.getPopupMenu());
                menu.setEnabled(n.enabled());
                n.children().forEach(child -> menu.add(widget(child, source, groups)));
                SwingLook.tooltip(menu, n.tooltip());
                menu.addMenuListener(new MenuListener() {
                    /** Сбрасывает подсказку после закрытия меню. */
                    @Override public void menuDeselected(MenuEvent e) { intents.menuHover(null); }
                    /** Сбрасывает подсказку после отмены меню. */
                    @Override public void menuCanceled(MenuEvent e) { intents.menuHover(null); }
                    /** Сообщает ядру о наведении на открытое меню. */
                    @Override public void menuSelected(MenuEvent e) { intents.menuHover(n.id()); }
                });
                yield menu;
            }
            case MenuNode.Action n -> {
                // JavaFX: MenuItem → Swing: JMenuItem → Web: div[role=menuitem]
                yield action(new JMenuItem(n.text()) {
                    /** Размещает подсказку под настоящим пунктом, независимо от положения указателя внутри него. */
                    @Override public java.awt.Point getToolTipLocation(MouseEvent event) { return new java.awt.Point(DesignTokens.SPACING, getHeight() + DesignTokens.SPACING); }
                }, n.command(), n.args(), n.accel(), n.tooltip(), n.enabled(), source);
            }
            case MenuNode.Check n -> {
                // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: div[role=menuitemcheckbox]
                yield action(new JCheckBoxMenuItem(n.text(), n.checked()) {
                    /** Размещает подсказку под настоящим пунктом. */
                    @Override public java.awt.Point getToolTipLocation(MouseEvent event) { return new java.awt.Point(DesignTokens.SPACING, getHeight() + DesignTokens.SPACING); }
                }, n.command(), n.args(), n.accel(), n.tooltip(), n.enabled(), source);
            }
            case MenuNode.Radio n -> {
                // JavaFX: RadioMenuItem → Swing: JRadioButtonMenuItem + ButtonGroup → Web: div[role=menuitemradio]
                JRadioButtonMenuItem item = new JRadioButtonMenuItem(n.text(), n.selected()) {
                    /** Размещает подсказку под настоящим пунктом. */
                    @Override public java.awt.Point getToolTipLocation(MouseEvent event) { return new java.awt.Point(DesignTokens.SPACING, getHeight() + DesignTokens.SPACING); }
                };
                groups.computeIfAbsent(n.group(), unused -> new ButtonGroup()).add(item);
                item.putClientProperty("cp.group", n.group());
                yield action(item, n.command(), n.args(), n.accel(), n.tooltip(), n.enabled(), source);
            }
            case MenuNode.Separator n -> {
                // JavaFX: SeparatorMenuItem → Swing: JPopupMenu.Separator → Web: hr
                JPopupMenu.Separator separator = new JPopupMenu.Separator() {
                    /** Рисует одну настоящую линию между четырёхпиксельными вертикальными отступами. */
                    @Override protected void paintComponent(java.awt.Graphics graphics) {
                        graphics.setColor(SwingLook.color(ColorToken.BORDER));
                        graphics.drawLine(0, DesignTokens.SPACING, Math.max(0, getWidth() - 1), DesignTokens.SPACING);
                    }
                };
                separator.setPreferredSize(new Dimension(0, 2 * DesignTokens.SPACING + 1));
                separator.setEnabled(false); yield separator;
            }
            case MenuNode.Info n -> {
                // JavaFX: MenuItem → Swing: JMenuItem → Web: div[role=menuitem]
                JMenuItem info = new JMenuItem(n.text()); info.setEnabled(false); yield info;
            }
            case MenuNode.Slider n -> slider(n);
            case MenuNode.Spinner n -> spinner(n);
        };
        if (widget instanceof JMenuItem item) {
            item.setFont(SwingLook.font(FontToken.BASE));
            Dimension measured = item.getPreferredSize();
            item.setPreferredSize(new Dimension(measured.width, DesignTokens.CONTROL_HEIGHT));
        }
        SwingLook.id(widget, node.id());
        widget.putClientProperty("cp.kind", node.getClass().getSimpleName());
        widget.addMouseListener(new MouseAdapter() {
            /** Передаёт идентификатор пункта для общей строки состояния. */
            @Override public void mouseEntered(MouseEvent e) { intents.menuHover(node.id()); }
            /** Убирает временную подсказку пункта. */
            @Override public void mouseExited(MouseEvent e) { intents.menuHover(null); }
        });
        return widget;
    }

    /** Применяет общие отступы ко всем настоящим popup меню, не ограничивая размеры custom rows. */
    private static void popupStyle(JPopupMenu popup) {
        int pad = DesignTokens.SPACING;
        popup.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(SwingLook.color(ColorToken.BORDER_STRONG)),
                BorderFactory.createEmptyBorder(pad, pad, pad, pad)));
        popup.setBackground(SwingLook.color(ColorToken.BG_SURFACE));
    }

    private JMenuItem action(JMenuItem item, CommandId command, CommandArgs args, KeyChord accel,
                             String tooltip, boolean enabled, InvokeSource source) {
        item.setEnabled(enabled);
        // Ускоритель рисуется отдельной меткой: Swing не регистрирует второе действие клавиши.
        String shown = accel == null ? "" : accel.display();
        item.putClientProperty("cp.accel", shown);
        if (!shown.isEmpty()) {
            item.setLayout(new BorderLayout(20, 0));
            JLabel label = new JLabel(shown); label.setForeground(item.getForeground());
            item.add(label, BorderLayout.EAST);
            Dimension d = item.getPreferredSize(); item.setPreferredSize(new Dimension(d.width + label.getPreferredSize().width + 30, d.height));
        }
        SwingLook.tooltip(item, tooltip);
        item.addActionListener(e -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            intents.menuHover(null);
            intents.command(command, args, source);
        });
        return item;
    }

    private JComponent slider(MenuNode.Slider n) {
        // JavaFX: CustomMenuItem → Swing: JPanel + JSlider → Web: input[type=range]
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        JLabel label = new JLabel(n.currentLabel());
        JSlider slider = SwingLook.id(new JSlider(n.min(), n.max(), n.value()), n.id() + ".value");
        slider.setMajorTickSpacing(n.majorTick()); slider.setPaintTicks(true);
        slider.setPreferredSize(new Dimension(n.widthPx(), 44));
        slider.addChangeListener(e -> {
            label.setText(n.labels().get(slider.getValue() - n.min()));
            if (!slider.getValueIsAdjusting()) intents.sliderCommit(n.id(), slider.getValue());
        });
        panel.add(label, BorderLayout.NORTH); panel.add(slider);
        panel.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        SwingLook.tooltip(panel, n.tooltip());
        return panel;
    }

    private JComponent spinner(MenuNode.Spinner n) {
        // JavaFX: CustomMenuItem → Swing: JPanel + JSpinner → Web: input[type=number]
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        JSpinner spinner = SwingLook.id(new JSpinner(new SpinnerNumberModel(n.value(), n.min(), n.max(), n.step())), n.id() + ".value");
        spinner.setEditor(new JSpinner.NumberEditor(spinner, "0"));
        spinner.setPreferredSize(new Dimension(n.fieldWidthPx(), 28));
        // ToolsFlow уже откладывает применение на общие 600 мс; второй таймер удваивал задержку.
        spinner.addChangeListener(e -> intents.spinnerCommit(n.id(), ((Number) spinner.getValue()).longValue()));
        panel.add(new JLabel(n.label()), BorderLayout.WEST); panel.add(spinner);
        panel.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        SwingLook.tooltip(panel, n.tooltip());
        return panel;
    }
}
