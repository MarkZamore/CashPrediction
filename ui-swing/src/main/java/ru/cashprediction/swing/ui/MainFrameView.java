package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.event.*;
import java.util.EnumSet;
import javax.swing.*;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.ui.token.*;
import ru.cashprediction.core.ui.view.*;
import ru.cashprediction.core.document.ViewMode;

/** Главное окно - композиция представлений общих моделей ядра. */
public final class MainFrameView extends JFrame {
    final SwingUiPort port;
    final SwingPaintRoot root;
    final JPanel north = new JPanel();
    final JPanel center = new JPanel(new CardLayout());
    final SwingToolbar toolbar;
    final SwingSummaryPanel summary;
    final SwingTable table;
    final SwingChart chart;
    final SwingStatusBar status = new SwingStatusBar();
    JMenuBar menus;
    MainScreenModel model;

    /** Создаёт окно, связывая элементы исключительно с UiIntents. */
    public MainFrameView(SwingUiPort port) {
        this.port = port; setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));
        root = new SwingPaintRoot(port.environment.options().isSelftest());
        toolbar = new SwingToolbar(port.intents(), root.context()); summary = new SwingSummaryPanel(port, root.context());
        table = new SwingTable(port); chart = new SwingChart(port, root.context());
        north.setLayout(new BorderLayout()); north.setBackground(SwingLook.color(ColorToken.BG_WINDOW));
        JPanel controls = new JPanel(new BorderLayout()); controls.add(toolbar, BorderLayout.NORTH); controls.add(summary); north.add(controls);
        center.add(table, ViewMode.TABLE.name()); center.add(chart, ViewMode.CHART.name());
        root.add(north, BorderLayout.NORTH); root.add(center); root.add(status, BorderLayout.SOUTH); setContentPane(root);
        root.setPreferredSize(new Dimension(1200, 800)); pack();
        addWindowListener(new WindowAdapter() {
            /** Передаёт закрытие окна единому потоку выхода ядра. */
            @Override public void windowClosing(WindowEvent e) { port.intents().closeMainRequested(); }
        });
        addComponentListener(new ComponentAdapter() {
            /** Передаёт ядру текущие границы главного окна. */
            @Override public void componentMoved(ComponentEvent e) { geometry(); }
            /** Передаёт размер и обновляет высоту переносимой сводки. */
            @Override public void componentResized(ComponentEvent e) { summary.revalidate(); geometry(); }
            private void geometry() { if (isShowing()) port.intents().mainGeometry(SwingUiPort.bounds(MainFrameView.this), (getExtendedState() & MAXIMIZED_BOTH) != 0); }
        });
        setIconImage(SwingIcons.application());
    }

    /** Показывает главное окно один раз, применяя восстановленную геометрию. */
    public void show(MainScreenModel model, MainWindowState restored) {
        render(model, EnumSet.allOf(ScreenPart.class));
        SwingUiPort.place(this, restored == null ? null : restored.bounds(), null);
        if (restored != null && restored.maximized()) setExtendedState(MAXIMIZED_BOTH);
        setVisible(true); table.table.requestFocusInWindow();
    }

    /** Обновляет только части, перечисленные контроллером. */
    public void render(MainScreenModel model, EnumSet<ScreenPart> changed) {
        this.model = model;
        if (changed.contains(ScreenPart.TITLE)) setTitle(model.windowTitle());
        if (changed.contains(ScreenPart.MENU)) {
            if (menus != null) north.remove(menus);
            menus = new SwingMenus(port.intents()).bar(model.menuBar());
            menus.setAlignmentX(Component.LEFT_ALIGNMENT);
            menus.setMaximumSize(new Dimension(Integer.MAX_VALUE, menus.getPreferredSize().height)); north.add(menus, BorderLayout.NORTH);
        }
        if (changed.contains(ScreenPart.TOOLBAR)) toolbar.render(model.toolbar());
        if (changed.contains(ScreenPart.SUMMARY)) summary.render(model.summary());
        if (changed.contains(ScreenPart.TABLE)) table.render(model.table());
        if (changed.contains(ScreenPart.CHART)) chart.render(model.chart());
        if (changed.contains(ScreenPart.STATUS)) status.render(model.status());
        if (changed.contains(ScreenPart.MODE)) { port.popups.hide(); ((CardLayout) center.getLayout()).show(center, model.mode().name()); }
        root.revalidate(); root.repaint();
    }
}
