package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.geom.*;
import javax.swing.*;
import ru.cashprediction.core.ui.token.*;
import ru.cashprediction.core.ui.view.popup.*;

/** Невосстанавливаемые всплывающие представления готовых моделей дня и спарклайна. */
public final class SwingPopups {
    private JWindow window;
    JPanel content;
    String kind = "";

    /** Закрывает текущее всплывающее окно и освобождает его ресурсы. */
    public void hide() { if (window != null) { window.dispose(); window = null; content = null; kind = ""; } }

    /** Показывает карточку дня у указателя, не перехватывая фокус. */
    public void day(DayCardModel model, Component owner, int x, int y) {
        show(dayContent(model), "dayCard", owner, x, y);
    }

    /** Строит содержимое карточки дня с нормативными промежутками, не показывая окно. */
    static JPanel dayContent(DayCardModel model) {
        JPanel panel = panel();
        addText(panel, model.header(), ColorToken.TEXT_PRIMARY, FontToken.HEADER, 304);
        addText(panel, model.balanceLine(), model.balanceColor(), FontToken.BASE, 304);
        model.lines().forEach(line -> addText(panel, line.text(), line.color(), FontToken.BASE, 304));
        if (!model.moreText().isEmpty()) panel.add(SwingLook.label(model.moreText(), ColorToken.TEXT_MUTED, FontToken.SMALL));
        if (!model.noneText().isEmpty()) panel.add(SwingLook.label(model.noneText(), ColorToken.TEXT_MUTED, FontToken.SMALL));
        for (int index = panel.getComponentCount() - 1; index > 0; index--) panel.add(Box.createVerticalStrut(3), index);
        Dimension measured = panel.getPreferredSize();
        panel.setPreferredSize(new Dimension(Math.max(200, measured.width), measured.height));
        return panel;
    }

    /** Показывает спарклайн под карточкой по модели ядра. */
    public void spark(SparklineModel model, Component card) {
        show(sparkContent(model), "sparkline", card, 0, card.getHeight() + 4);
    }

    /** Строит те же настоящие компоненты спарклайна без показа окна. */
    static JPanel sparkContent(SparklineModel model) {
        JPanel panel = panel();
        panel.add(new SwingSparkCaption(model.header(), ColorToken.TEXT_PRIMARY, FontToken.HEADER, DesignTokens.SPARK_HEADER_LINE_HEIGHT, DesignTokens.SPARK_WIDTH));
        JLabel explanation = new SwingSparkCaption(model.explanation(), ColorToken.TEXT_MUTED, FontToken.SMALL, DesignTokens.SPARK_BODY_LINE_HEIGHT, DesignTokens.SPARK_WIDTH);
        explanation.putClientProperty("cp.text", model.explanation()); panel.add(explanation);
        if (model.points().size() < 2) {
            panel.add(new SwingSparkCaption(model.noDataText(), ColorToken.TEXT_MUTED, FontToken.SMALL, DesignTokens.SPARK_BODY_LINE_HEIGHT, DesignTokens.SPARK_WIDTH));
        } else {
            Sparkline graph = new Sparkline(model); graph.setAlignmentX(0); panel.add(graph);
        }
        if (!model.minText().isEmpty() || !model.maxText().isEmpty()) {
            JPanel range = new JPanel(new BorderLayout()); range.setOpaque(false); range.setAlignmentX(0);
            int width = model.minText().isEmpty() || model.maxText().isEmpty() ? DesignTokens.SPARK_WIDTH : DesignTokens.SPARK_WIDTH / 2;
            if (!model.minText().isEmpty()) range.add(new SwingSparkCaption(model.minText(), ColorToken.TEXT_MUTED, FontToken.MICRO, DesignTokens.SPARK_FOOTER_LINE_HEIGHT, width), BorderLayout.WEST);
            if (!model.maxText().isEmpty()) {
                JLabel maximum = new SwingSparkCaption(model.maxText(), ColorToken.TEXT_MUTED, FontToken.MICRO, DesignTokens.SPARK_FOOTER_LINE_HEIGHT, width);
                maximum.setHorizontalAlignment(SwingConstants.RIGHT); range.add(maximum, BorderLayout.EAST);
            }
            panel.add(range);
        }
        if (DesignTokens.SPARK_CONTENT_GAP > 0) for (int index = panel.getComponentCount() - 1; index > 0; index--) panel.add(Box.createVerticalStrut(DesignTokens.SPARK_CONTENT_GAP), index);
        return panel;
    }

    private static JPanel panel() {
        JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(SwingLook.color(ColorToken.BG_SURFACE));
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(SwingLook.color(ColorToken.BORDER_STRONG)), BorderFactory.createEmptyBorder(8, 8, 8, 8)));
        return panel;
    }

    /** Переносит настоящую подпись в ограниченной ширине, сохраняя исходный текст для наблюдения. */
    static void addText(JPanel panel, String text, ColorToken color, FontToken font, int maxWidth) {
        JLabel measure = SwingLook.label(text, color, font);
        int width = Math.min(maxWidth, measure.getPreferredSize().width);
        // Swing HTML не ограничивает длинную строку одним CSS width: перенос задаётся по настоящим метрикам глифов.
        FontMetrics metrics = measure.getFontMetrics(measure.getFont());
        StringBuilder wrapped = new StringBuilder(); int lineWidth = 0;
        for (String word : text.split(" ")) {
            int wordWidth = metrics.stringWidth(word), gap = lineWidth == 0 ? 0 : metrics.charWidth(' ');
            if (lineWidth > 0 && lineWidth + gap + wordWidth > maxWidth) { wrapped.append('\n'); lineWidth = 0; gap = 0; }
            if (gap > 0) wrapped.append(' ');
            wrapped.append(word); lineWidth += gap + wordWidth;
        }
        JLabel label = SwingLook.label(SwingLook.html(wrapped.toString(), width), color, font);
        label.putClientProperty("cp.text", text); panel.add(label);
    }

    private void show(JPanel panel, String type, Component owner, int x, int y) {
        hide();
        // JavaFX: PopupWindow / PopupControl → Swing: JWindow → Web: div-popover
        window = new JWindow(SwingUtilities.getWindowAncestor(owner)); window.setFocusableWindowState(false);
        content = panel; kind = type; window.setContentPane(panel); window.pack();
        Point p = new Point(x, y); SwingUtilities.convertPointToScreen(p, owner);
        Rectangle bounds = owner.getGraphicsConfiguration().getBounds();
        window.setLocation(Math.min(p.x, bounds.x + bounds.width - window.getWidth()), Math.min(p.y, bounds.y + bounds.height - window.getHeight()));
        window.setVisible(true);
    }

    /** Рисует нормированные точки без расчёта денежных значений. */
    private static final class Sparkline extends JComponent {
        private final SparklineModel model;
        Sparkline(SparklineModel model) {
            this.model = model;
            setFont(SwingLook.font(FontToken.SMALL));
            Dimension graph = new Dimension(DesignTokens.SPARK_WIDTH, DesignTokens.SPARK_HEIGHT);
            setPreferredSize(graph); setMinimumSize(graph); setMaximumSize(graph);
        }
        /** Масштабирует геометрию ядра в область 240 на 60. */
        @Override protected void paintComponent(Graphics source) {
            Graphics2D g = (Graphics2D) source.create(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (model.points().size() < 2) { g.setColor(SwingLook.color(ColorToken.TEXT_MUTED)); g.drawString(model.noDataText(), 8, 30); }
            else {
                if (model.zeroY() != null) {
                    g.setColor(SwingLook.color(ColorToken.EXPENSE)); g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{3, 3}, 0));
                    g.draw(new Line2D.Double(0, model.zeroY() * 60, 240, model.zeroY() * 60));
                }
                Path2D path = new Path2D.Double(); path.moveTo(model.points().getFirst().x() * 240, model.points().getFirst().y() * 60);
                model.points().stream().skip(1).forEach(point -> path.lineTo(point.x() * 240, point.y() * 60));
                g.setColor(SwingLook.color(ColorToken.ACCENT)); g.setStroke(new BasicStroke(1.5f)); g.draw(path);
                if (model.marker() != null) { g.setColor(SwingLook.color(ColorToken.LINE_TODAY)); g.fill(new Ellipse2D.Double(model.marker().x() * 240 - 3, model.marker().y() * 60 - 3, 6, 6)); }
            }
            g.dispose();
        }
    }
}
