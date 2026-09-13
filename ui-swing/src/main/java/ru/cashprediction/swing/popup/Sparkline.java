package ru.cashprediction.swing.popup;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Objects;
import javax.swing.JComponent;
import ru.cashprediction.core.forecast.DailyPoint;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.swing.view.Palette;

/**
 * Маленький график-«спарклайн» баланса для всплывающей карточки сводки ({@link SwingPopupControl}).
 *
 * <p>Точки берутся из {@code ChartSeries.sample} (та же децимация, что у большого графика), поэтому линия
 * совпадает с графиком главного окна. Необязательная пунктирная горизонталь показывает порог (ноль, подушку или
 * цель). Класс используется только в потоке EDT.</p>
 */
public final class Sparkline extends JComponent {

    private final List<DailyPoint> points;
    private final Money reference;
    private final Color color;

    /**
     * Создаёт спарклайн.
     *
     * @param points    точки баланса по возрастанию дат
     * @param reference порог для пунктирной линии или {@code null}
     * @param color     цвет линии
     */
    public Sparkline(List<DailyPoint> points, Money reference, Color color) {
        this.points = List.copyOf(points);
        this.reference = reference;
        this.color = Objects.requireNonNullElse(color, Palette.BALANCE_LINE);
        setPreferredSize(new Dimension(240, 64));
        setOpaque(false);
    }

    /**
     * Рисует спарклайн со сглаживанием; при менее чем двух точках ничего не рисует.
     *
     * @param graphics контекст рисования Swing
     */
    @Override
    protected void paintComponent(Graphics graphics) {
        if (points.size() < 2) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - 4;
            int h = getHeight() - 4;
            long min = Long.MAX_VALUE;
            long max = Long.MIN_VALUE;
            for (DailyPoint p : points) {
                min = Math.min(min, p.balance().minor());
                max = Math.max(max, p.balance().minor());
            }
            if (reference != null) {
                min = Math.min(min, reference.minor());
                max = Math.max(max, reference.minor());
            }
            double span = Math.max(1, max - min);
            long first = points.getFirst().date().toEpochDay();
            double days = Math.max(1, points.getLast().date().toEpochDay() - first);
            Path2D line = new Path2D.Double();
            for (int i = 0; i < points.size(); i++) {
                DailyPoint p = points.get(i);
                double x = 2 + w * (p.date().toEpochDay() - first) / days;
                double y = 2 + h - h * (p.balance().minor() - min) / span;
                if (i == 0) {
                    line.moveTo(x, y);
                } else {
                    line.lineTo(x, y);
                }
            }
            if (reference != null) {
                double y = 2 + h - h * (reference.minor() - min) / span;
                g.setColor(Palette.ZERO_LINE);
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {3f, 3f}, 0f));
                g.drawLine(2, (int) y, 2 + w, (int) y);
            }
            g.setColor(color);
            g.setStroke(new BasicStroke(1.6f));
            g.draw(line);
        } finally {
            g.dispose();
        }
    }
}
