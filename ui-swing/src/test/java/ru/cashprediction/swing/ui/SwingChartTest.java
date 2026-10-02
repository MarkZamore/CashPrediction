package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.view.chart.*;

/** Проверяет экранный художник PNG по геометрическим примитивам контракта. */
class SwingChartTest {
    @Test void dormantWidgetIsPaintedAndNewRevisionInvalidatesObservedOutput() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            SwingChart widget = new SwingChart(null);
            javax.swing.JPanel parent = new javax.swing.JPanel(); parent.setSize(500, 300); parent.add(widget);
            ChartModel model = new ChartModel() {
                public long revision() { return 1; }
                public ChartScene layout(double width, double height) { return new ChartScene(width, height, plot(), List.of(new ChartPrimitive.Circle(40, 40, 3, ColorToken.ACCENT, null, 0)), List.of(), List.of(), "", ColorToken.TEXT_MUTED); }
                public ru.cashprediction.core.ui.view.popup.DayCardModel dayCard(java.time.LocalDate date) { return null; }
                public java.util.Optional<ChartHover> hover(double x, double y, double width, double height) { return java.util.Optional.empty(); }
            };
            widget.render(model); assertNull(widget.drawn); widget.observeDormant();
            assertEquals(1, widget.drawn.markerCount()); assertEquals(500, widget.painted.width());
            widget.render(model); assertNull(widget.drawn);
        });
    }
    @Test void pngHasRequiredDimensionsBackgroundAndPrimitiveColor() throws Exception {
        ChartScene scene = new ChartScene(1200, 700, plot(), List.of(new ChartPrimitive.Box(10, 10, 20, 20, ColorToken.INCOME, 1)), List.of(), List.of(), "", ColorToken.TEXT_MUTED);
        var image = ImageIO.read(new ByteArrayInputStream(SwingChart.png(scene)));
        assertEquals(1200, image.getWidth()); assertEquals(700, image.getHeight());
        assertEquals(ColorToken.BG_SURFACE.argb(), image.getRGB(100, 100)); assertEquals(ColorToken.INCOME.argb(), image.getRGB(20, 20));
    }
    @Test void alphaIsAppliedToFill() throws Exception {
        ChartScene scene = new ChartScene(100, 100, plot(), List.of(new ChartPrimitive.Box(10, 10, 20, 20, ColorToken.ACCENT, .1)), List.of(), List.of(), "", ColorToken.TEXT_MUTED);
        var image = ImageIO.read(new ByteArrayInputStream(SwingChart.png(scene)));
        assertNotEquals(ColorToken.ACCENT.argb(), image.getRGB(20, 20)); assertNotEquals(ColorToken.BG_SURFACE.argb(), image.getRGB(20, 20));
    }
    private PlotTransform plot() { return new PlotTransform(0, 0, 100, 100, java.time.LocalDate.of(2026, 9, 13), 30, 0, 100); }
}
