package ru.cashprediction.fx.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import ru.cashprediction.core.ui.view.chart.*;
import java.util.List;
import java.util.ArrayList;
import ru.cashprediction.core.ui.dump.UiDump;

/** Рисует готовую сцену графика, не вычисляя прогноз или шкалы. */
public final class FxChartCanvas {
    private FxChartCanvas() { }

    /** Рисует образец легенды настоящим Canvas; подпись и подсказка остаются соседним Label. */
    static Canvas legendSample(LegendItem item) {
        Canvas canvas = new Canvas(14, 14);
        var g = canvas.getGraphicsContext2D();
        g.setFill(Color.web(item.color().hex())); g.setStroke(Color.web(item.color().hex())); g.setLineWidth(2);
        switch (item.swatch()) {
            case LINE -> g.strokeLine(0, 7, 14, 7);
            case DASH -> { g.setLineDashes(4, 4); g.strokeLine(0, 7, 14, 7); }
            case DOT -> g.fillOval(3.5, 3.5, 7, 7);
            case BOX -> g.fillRect(0, 3, 12, 8);
            case NONE -> { }
        }
        return canvas;
    }

    /** Перерисовывает холст по примитивам в указанном ядром порядке. */
    public static void paint(Canvas canvas, ChartScene scene) {
        canvas.setWidth(scene.width()); canvas.setHeight(scene.height());
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setGlobalAlpha(1); g.setFill(Color.WHITE); g.fillRect(0, 0, scene.width(), scene.height());
        List<String> xLabels = new ArrayList<>(), yLabels = new ArrayList<>(), lineLabels = new ArrayList<>();
        int markers = 0, bars = 0;
        for (var primitive : scene.primitives()) {
            g.save();
            switch (primitive) {
                case ChartPrimitive.Area p -> {
                    g.setFill(Color.web(p.fill().hex())); g.setGlobalAlpha(p.opacity());
                    path(g, p.points());
                    if (!p.points().isEmpty()) { g.lineTo(p.points().getLast().x(), p.baselineY()); g.lineTo(p.points().getFirst().x(), p.baselineY()); }
                    g.closePath(); g.fill();
                }
                case ChartPrimitive.Polyline p -> { stroke(g, p.stroke()); path(g, p.points()); g.stroke(); }
                case ChartPrimitive.Line p -> { stroke(g, p.stroke()); g.strokeLine(p.x1(), p.y1(), p.x2(), p.y2()); }
                case ChartPrimitive.Box p -> { g.setFill(Color.web(p.fill().hex())); g.setGlobalAlpha(p.opacity()); g.fillRect(p.x(), p.y(), p.width(), p.height()); bars++; }
                case ChartPrimitive.Circle p -> {
                    g.setFill(Color.web(p.fill().hex())); g.fillOval(p.cx() - p.r(), p.cy() - p.r(), p.r() * 2, p.r() * 2);
                    markers++;
                    if (p.stroke() != null) { g.setStroke(Color.web(p.stroke().hex())); g.setLineWidth(p.strokeWidth()); g.strokeOval(p.cx() - p.r(), p.cy() - p.r(), p.r() * 2, p.r() * 2); }
                }
                case ChartPrimitive.Label p -> {
                    g.setFill(Color.web(p.color().hex())); g.setFont(Font.font(p.font().primaryFamily(), p.font().bold() ? FontWeight.BOLD : FontWeight.NORMAL, p.font().sizePx()));
                    g.setTextAlign(switch (p.anchor()) { case START -> TextAlignment.LEFT; case MIDDLE -> TextAlignment.CENTER; case END -> TextAlignment.RIGHT; });
                    g.fillText(p.text(), p.x(), p.y());
                    // Протокол реально выполненных команд рисования: Canvas не имеет дочерних текстовых узлов.
                    if (p.y() > scene.plot().plotY() + scene.plot().plotHeight()) xLabels.add(p.text());
                    else if (p.x() < scene.plot().plotX()) yLabels.add(p.text()); else lineLabels.add(p.text());
                }
            }
            g.restore();
        }
        if (!scene.emptyText().isEmpty()) { g.setFill(Color.web(scene.emptyColor().hex())); g.setTextAlign(TextAlignment.CENTER); g.fillText(scene.emptyText(), scene.width() / 2, scene.height() / 2); }
        canvas.getProperties().put("cp.drawn", new UiDump.Chart(List.of(), xLabels, yLabels, lineLabels, markers, bars, scene.emptyText()));
    }

    private static void path(GraphicsContext g, List<ChartPoint> points) {
        g.beginPath(); if (points.isEmpty()) return;
        g.moveTo(points.getFirst().x(), points.getFirst().y()); for (var p : points.subList(1, points.size())) g.lineTo(p.x(), p.y());
    }

    private static void stroke(GraphicsContext g, Stroke stroke) {
        g.setStroke(Color.web(stroke.color().hex())); g.setLineWidth(stroke.width()); g.setGlobalAlpha(stroke.opacity());
        g.setLineDashes(stroke.dash().stream().mapToDouble(Double::doubleValue).toArray());
    }
}
