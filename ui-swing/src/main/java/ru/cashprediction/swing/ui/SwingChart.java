package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.menu.ContextTarget;
import ru.cashprediction.core.ui.token.*;
import ru.cashprediction.core.ui.view.chart.*;

/** Рисует готовую сцену ядра одним и тем же Graphics2D на экране и в PNG. */
public final class SwingChart extends JComponent {
    private ChartModel model;
    ChartScene painted;
    ru.cashprediction.core.ui.dump.UiDump.Chart drawn;
    private ChartHover hover;
    private final SwingUiPort port;

    /** Создаёт область графика с наведением и контекстным меню. */
    public SwingChart(SwingUiPort port) {
        this.port = port; setFocusable(true); SwingLook.id(this, "chart");
        // JavaFX: Tooltip → Swing: ToolTipManager → Web: div.tooltip
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            /** Запрашивает готовую карточку дня у модели ядра. */
            @Override public void mouseMoved(MouseEvent e) {
                hover = model == null ? null : model.hover(e.getX(), e.getY(), getWidth(), getHeight()).orElse(null);
                if (hover == null) port.popups.hide(); else port.popups.day(hover.card(), SwingChart.this, e.getX() + 16, e.getY() + 16);
                repaint();
            }
            /** Убирает карточку дня при выходе из области. */
            @Override public void mouseExited(MouseEvent e) { hover = null; port.popups.hide(); repaint(); }
            /** Обрабатывает платформенный триггер контекстного меню. */
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            /** Обрабатывает триггер при отпускании. */
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
            /** Передаёт дату готового преобразования ядра при двойном щелчке. */
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && painted != null && painted.plot() != null && painted.plot().contains(e.getX(), e.getY())) {
                    port.intents().command(CommandId.CHART_SHOW_IN_TABLE, new CommandArgs(null, painted.plot().dateAt(e.getX()), null, null, null), InvokeSource.MAIN);
                }
            }
            private void popup(MouseEvent e) {
                // JavaFX: ContextMenuEvent → Swing: MouseEvent.isPopupTrigger → Web: contextmenu
                if (e.isPopupTrigger()) port.context(new ContextTarget.Chart(e.getX(), e.getY(), getWidth(), getHeight()), SwingChart.this, e.getX(), e.getY());
            }
        };
        addMouseListener(mouse); addMouseMotionListener(mouse);
    }

    /** Применяет новую модель сцены. */
    public void render(ChartModel model) { this.model = model; hover = null; drawn = null; repaint(); }

    /** Рисует неактивную карточку тем же настоящим компонентом в буфер, сохраняя наблюдение художника. */
    void observeDormant() {
        if (model == null || isShowing()) return;
        Container parent = getParent();
        int width = Math.max(1, parent == null ? getWidth() : parent.getWidth());
        int height = Math.max(1, parent == null ? getHeight() : parent.getHeight());
        setSize(width, height);
        BufferedImage buffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = buffer.createGraphics(); paint(graphics); graphics.dispose();
    }

    /** Рисует сцену, а затем готовую геометрию наведения. */
    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics); if (model == null) return;
        painted = model.layout(getWidth(), getHeight()); drawn = paintObserved((Graphics2D) graphics, painted);
        if (hover != null) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setColor(SwingLook.color(ColorToken.TEXT_MUTED)); g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{3, 3}, 0));
            g.draw(new Line2D.Double(hover.lineX(), painted.plot().plotY(), hover.lineX(), painted.plot().plotY() + painted.plot().plotHeight()));
            g.setColor(SwingLook.color(ColorToken.ACCENT)); g.fill(new Ellipse2D.Double(hover.dot().x() - 4.5, hover.dot().y() - 4.5, 9, 9)); g.dispose();
        }
    }

    /** Возвращает подсказку зоны сцены под указателем. */
    @Override public String getToolTipText(MouseEvent e) {
        if (painted == null) return null;
        // JavaFX: Tooltip → Swing: JToolTip → Web: div.tooltip
        return painted.hits().stream().filter(hit -> hit.contains(e.getX(), e.getY())).findFirst().map(hit -> SwingLook.html(hit.tooltip(), 420)).orElse(null);
    }

    /** Кодирует PNG строго по переданной сцене, не рассчитывая прогноз. */
    public static byte[] png(ChartScene scene) throws IOException {
        BufferedImage image = new BufferedImage(Math.max(1, (int) scene.width()), Math.max(1, (int) scene.height()), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics(); paint(g, scene); g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) throw new IOException("PNG encoder");
        return bytes.toByteArray();
    }

    /** Рисует примитивы в порядке, уже вычисленном ядром. */
    public static void paint(Graphics2D source, ChartScene scene) {
        paintObserved(source, scene);
    }

    private static ru.cashprediction.core.ui.dump.UiDump.Chart paintObserved(Graphics2D source, ChartScene scene) {
        java.util.List<String> xLabels = new java.util.ArrayList<>(), yLabels = new java.util.ArrayList<>(), lineLabels = new java.util.ArrayList<>(), legendLabels = new java.util.ArrayList<>();
        int markers = 0, bars = 0;
        Graphics2D g = (Graphics2D) source.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(SwingLook.color(ColorToken.BG_SURFACE)); g.fill(new Rectangle2D.Double(0, 0, scene.width(), scene.height()));
        for (ChartPrimitive primitive : scene.primitives()) {
            g.setComposite(AlphaComposite.SrcOver);
            switch (primitive) {
                case ChartPrimitive.Area p -> {
                    if (p.points().isEmpty()) continue;
                    Path2D path = path(p.points()); path.lineTo(p.points().getLast().x(), p.baselineY()); path.lineTo(p.points().getFirst().x(), p.baselineY()); path.closePath();
                    fill(g, p.fill(), p.opacity()); g.fill(path);
                }
                case ChartPrimitive.Polyline p -> { stroke(g, p.stroke()); g.draw(path(p.points())); }
                case ChartPrimitive.Line p -> { stroke(g, p.stroke()); g.draw(new Line2D.Double(p.x1(), p.y1(), p.x2(), p.y2())); }
                case ChartPrimitive.Box p -> { fill(g, p.fill(), p.opacity()); g.fill(new Rectangle2D.Double(p.x(), p.y(), p.width(), p.height())); bars++; }
                case ChartPrimitive.Circle p -> {
                    Shape circle = new Ellipse2D.Double(p.cx() - p.r(), p.cy() - p.r(), 2 * p.r(), 2 * p.r());
                    g.setColor(SwingLook.color(p.fill())); g.fill(circle);
                    if (p.stroke() != null) { g.setColor(SwingLook.color(p.stroke())); g.setStroke(new BasicStroke((float) p.strokeWidth())); g.draw(circle); }
                    markers++;
                }
                case ChartPrimitive.Label p -> {
                    g.setColor(SwingLook.color(p.color())); g.setFont(SwingLook.font(p.font()));
                    int width = g.getFontMetrics().stringWidth(p.text());
                    double x = p.x() - switch (p.anchor()) { case START -> 0; case MIDDLE -> width / 2.0; case END -> width; };
                    g.drawString(p.text(), (float) x, (float) p.y());
                    if (scene.plot() != null && p.y() > scene.plot().plotY() + scene.plot().plotHeight()) xLabels.add(p.text());
                    else if (scene.plot() != null && p.x() < scene.plot().plotX()) yLabels.add(p.text()); else lineLabels.add(p.text());
                }
            }
        }
        g.setComposite(AlphaComposite.SrcOver);
        float legendX = 8; g.setFont(SwingLook.font(FontToken.LEGEND));
        for (LegendItem item : scene.legend()) {
            g.setColor(SwingLook.color(item.color())); g.fill(new Rectangle2D.Float(legendX, 10, 12, 3));
            g.setColor(SwingLook.color(ColorToken.TEXT_PRIMARY)); g.drawString(item.text(), legendX + 16, 17);
            legendLabels.add(item.text()); legendX += 28 + g.getFontMetrics().stringWidth(item.text());
        }
        if (!scene.emptyText().isEmpty()) {
            g.setFont(SwingLook.font(FontToken.BASE)); g.setColor(SwingLook.color(scene.emptyColor()));
            g.drawString(scene.emptyText(), (float) ((scene.width() - g.getFontMetrics().stringWidth(scene.emptyText())) / 2), (float) (scene.height() / 2));
        }
        g.dispose();
        return new ru.cashprediction.core.ui.dump.UiDump.Chart(legendLabels, xLabels, yLabels, lineLabels, markers, bars, scene.emptyText());
    }

    private static Path2D path(List<ChartPoint> points) {
        Path2D path = new Path2D.Double(); if (points.isEmpty()) return path;
        path.moveTo(points.getFirst().x(), points.getFirst().y());
        for (int i = 1; i < points.size(); i++) path.lineTo(points.get(i).x(), points.get(i).y()); return path;
    }
    private static void fill(Graphics2D g, ColorToken color, double opacity) { g.setColor(SwingLook.color(color)); g.setComposite(AlphaComposite.SrcOver.derive((float) opacity)); }
    private static void stroke(Graphics2D g, ru.cashprediction.core.ui.view.chart.Stroke stroke) {
        g.setColor(SwingLook.color(stroke.color())); g.setComposite(AlphaComposite.SrcOver.derive((float) stroke.opacity()));
        float[] dash = stroke.dash().isEmpty() ? null : new float[stroke.dash().size()];
        if (dash != null) for (int i = 0; i < dash.length; i++) dash[i] = stroke.dash().get(i).floatValue();
        g.setStroke(new BasicStroke((float) stroke.width(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, dash, 0));
    }
}
