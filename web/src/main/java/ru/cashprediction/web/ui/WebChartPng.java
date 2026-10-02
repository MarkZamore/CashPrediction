package ru.cashprediction.web.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.FontToken;
import ru.cashprediction.core.ui.view.chart.*;

/** Рисует PNG непосредственно из сцены ядра, сохраняя порядок примитивов и токены. */
public final class WebChartPng {
    private WebChartPng() { }

    /** Кодирует сцену в PNG; не зависит от окна и пользовательского жеста браузера. */
    public static byte[] render(ChartScene scene) throws IOException {
        int width = (int) Math.ceil(scene.width()), height = (int) Math.ceil(scene.height());
        if (width < 1 || height < 1 || width > 8192 || height > 8192) throw new IllegalArgumentException("PNG size");
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color(ColorToken.BG_CARD)); graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            for (ChartPrimitive primitive : scene.primitives()) paint(graphics, primitive);
            legend(graphics, scene);
            if (!scene.emptyText().isEmpty()) label(graphics, width / 2.0, height / 2.0, scene.emptyText(),
                    TextAnchor.MIDDLE, scene.emptyColor(), FontToken.BASE);
        } finally { graphics.dispose(); }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        // Обычный ImageIO.write(OutputStream) может создать файл кэша вне CashMemory; здесь кэш только в памяти.
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(output)) {
            if (!ImageIO.write(image, "png", stream)) throw new IOException("PNG encoder");
        }
        return output.toByteArray();
    }
    private static void paint(Graphics2D graphics, ChartPrimitive primitive) {
        graphics.setComposite(AlphaComposite.SrcOver);
        switch (primitive) {
            case ChartPrimitive.Area area -> {
                Path2D path = path(area.points());
                if (!area.points().isEmpty()) {
                    path.lineTo(area.points().getLast().x(), area.baselineY());
                    path.lineTo(area.points().getFirst().x(), area.baselineY()); path.closePath();
                }
                fill(graphics, area.fill(), area.opacity()); graphics.fill(path);
            }
            case ChartPrimitive.Polyline line -> { stroke(graphics, line.stroke()); graphics.draw(path(line.points())); }
            case ChartPrimitive.Line line -> {
                stroke(graphics, line.stroke()); graphics.draw(new Line2D.Double(line.x1(), line.y1(), line.x2(), line.y2()));
            }
            case ChartPrimitive.Box box -> {
                fill(graphics, box.fill(), box.opacity()); graphics.fill(new Rectangle2D.Double(box.x(), box.y(), box.width(), box.height()));
            }
            case ChartPrimitive.Circle circle -> {
                Ellipse2D shape = new Ellipse2D.Double(circle.cx() - circle.r(), circle.cy() - circle.r(), circle.r() * 2, circle.r() * 2);
                graphics.setColor(color(circle.fill())); graphics.fill(shape);
                if (circle.stroke() != null) {
                    graphics.setColor(color(circle.stroke())); graphics.setStroke(new BasicStroke((float) circle.strokeWidth())); graphics.draw(shape);
                }
            }
            case ChartPrimitive.Label text -> label(graphics, text.x(), text.y(), text.text(), text.anchor(), text.color(), text.font());
        }
    }
    private static Path2D path(java.util.List<ChartPoint> points) {
        Path2D path = new Path2D.Double();
        if (!points.isEmpty()) {
            path.moveTo(points.getFirst().x(), points.getFirst().y());
            for (int index = 1; index < points.size(); index++) path.lineTo(points.get(index).x(), points.get(index).y());
        }
        return path;
    }
    private static void fill(Graphics2D graphics, ColorToken token, double opacity) {
        graphics.setColor(color(token)); graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) opacity));
    }
    private static void stroke(Graphics2D graphics, Stroke stroke) {
        fill(graphics, stroke.color(), stroke.opacity());
        float[] dash = stroke.dash().isEmpty() ? null : new float[stroke.dash().size()];
        if (dash != null) for (int i = 0; i < dash.length; i++) dash[i] = stroke.dash().get(i).floatValue();
        graphics.setStroke(new BasicStroke((float) stroke.width(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, dash, 0));
    }
    private static void label(Graphics2D graphics, double x, double y, String text, TextAnchor anchor, ColorToken color, FontToken token) {
        graphics.setColor(color(color)); graphics.setFont(new Font(token.primaryFamily(), token.bold() ? Font.BOLD : Font.PLAIN, token.sizePx()));
        int length = graphics.getFontMetrics().stringWidth(text);
        double shift = switch (anchor) { case START -> 0; case MIDDLE -> length / 2.0; case END -> length; };
        graphics.drawString(text, (float) (x - shift), (float) y);
    }
    private static Color color(ColorToken token) { return new Color(token.argb(), true); }
    /** Рисует образцы и готовые тексты легенды в верхнем поле сцены. */
    private static void legend(Graphics2D graphics, ChartScene scene) {
        double x = 12, baseline = 18;
        for (LegendItem item : scene.legend()) {
            graphics.setComposite(AlphaComposite.SrcOver); graphics.setColor(color(item.color()));
            double textX = x + (item.swatch() == LegendItem.Swatch.NONE ? 0 : 18);
            switch (item.swatch()) {
                case LINE, DASH -> {
                    graphics.setStroke(item.swatch() == LegendItem.Swatch.DASH
                            ? new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10, new float[]{4, 4}, 0) : new BasicStroke(2));
                    graphics.draw(new Line2D.Double(x, baseline - 4, x + 14, baseline - 4));
                }
                case DOT -> graphics.fill(new Ellipse2D.Double(x + .5, baseline - 7.5, 7, 7));
                case BOX -> graphics.fill(new Rectangle2D.Double(x, baseline - 8, 12, 8));
                case NONE -> { }
            }
            label(graphics, textX, baseline, item.text(), TextAnchor.START, ColorToken.TEXT_MUTED, FontToken.LEGEND);
            x = textX + graphics.getFontMetrics().stringWidth(item.text()) + 20;
        }
    }
}
