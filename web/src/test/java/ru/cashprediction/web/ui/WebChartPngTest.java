package ru.cashprediction.web.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.FontToken;
import ru.cashprediction.core.ui.view.chart.*;

/** Проверяет реальный PNG Java2D по размеру, порядку примитивов и цветам токенов. */
class WebChartPngTest {
    private static ChartScene scene(List<ChartPrimitive> primitives, String empty) {
        return new ChartScene(1200, 700, new PlotTransform(80, 28, 1096, 640, LocalDate.of(2026, 9, 13), 365, 0, 100),
                primitives, List.of(), List.of(), empty, ColorToken.TEXT_MUTED);
    }
    @Test void headlessPngPreservesPrimitiveOrderAndTokens() throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(WebChartPng.render(scene(List.of(
                new ChartPrimitive.Box(100, 100, 60, 60, ColorToken.INCOME, 1),
                new ChartPrimitive.Box(120, 120, 60, 60, ColorToken.EXPENSE, 1),
                new ChartPrimitive.Circle(250, 200, 10, ColorToken.ACCENT, ColorToken.BG_SURFACE, 1),
                new ChartPrimitive.Line(300, 100, 300, 200, Stroke.solid(ColorToken.LINE_GOAL, 4))), ""))));
        assertEquals(1200, image.getWidth()); assertEquals(700, image.getHeight());
        assertEquals(ColorToken.BG_SURFACE.argb(), image.getRGB(0, 0));
        assertEquals(ColorToken.INCOME.argb(), image.getRGB(110, 110));
        assertEquals(ColorToken.EXPENSE.argb(), image.getRGB(140, 140));
        assertEquals(ColorToken.ACCENT.argb(), image.getRGB(250, 200));
        assertEquals(ColorToken.LINE_GOAL.argb(), image.getRGB(300, 150));
    }
    @Test void areaPolylineOpacityDashAndAllAnchorsRender() throws Exception {
        var points = List.of(new ChartPoint(100, 100), new ChartPoint(200, 100));
        var image = ImageIO.read(new ByteArrayInputStream(WebChartPng.render(scene(List.of(
                new ChartPrimitive.Area(points, 200, ColorToken.ACCENT, .5),
                new ChartPrimitive.Polyline(points, new Stroke(ColorToken.INCOME, 4, List.of(6.0, 4.0), 1)),
                new ChartPrimitive.Label(400, 300, "start", TextAnchor.START, ColorToken.TEXT_PRIMARY, FontToken.BASE),
                new ChartPrimitive.Label(400, 340, "middle", TextAnchor.MIDDLE, ColorToken.TEXT_PRIMARY, FontToken.HEADER),
                new ChartPrimitive.Label(400, 380, "end", TextAnchor.END, ColorToken.TEXT_PRIMARY, FontToken.MONO)), ""))));
        assertNotEquals(ColorToken.BG_SURFACE.argb(), image.getRGB(150, 150));
        assertEquals(ColorToken.INCOME.argb(), image.getRGB(102, 100));
    }
    @Test void emptySceneUsesSharedTextWithoutInventingPlaceholder() throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(WebChartPng.render(scene(List.of(), "empty"))));
        long nonWhite = 0;
        for (int y = 330; y <= 350; y++) for (int x = 550; x <= 650; x++) if (image.getRGB(x, y) != ColorToken.BG_SURFACE.argb()) nonWhite++;
        assertTrue(nonWhite > 0);
    }
    @Test void legendSwatchAndSharedLabelAreIncludedInExport() throws Exception {
        ChartScene base = scene(List.of(), "");
        ChartScene legend = new ChartScene(base.width(), base.height(), base.plot(), List.of(),
                List.of(new LegendItem("income", ru.cashprediction.core.ui.text.UiText.get("chart.legend.income"),
                        LegendItem.Swatch.LINE, ColorToken.INCOME, "")), List.of(), "", ColorToken.TEXT_MUTED);
        var image = ImageIO.read(new ByteArrayInputStream(WebChartPng.render(legend)));
        assertEquals(ColorToken.INCOME.argb(), image.getRGB(15, 14));
        long letters = 0;
        for (int y = 5; y <= 20; y++) for (int x = 30; x < 100; x++) if (image.getRGB(x, y) != ColorToken.BG_SURFACE.argb()) letters++;
        assertTrue(letters > 0);
    }
}
