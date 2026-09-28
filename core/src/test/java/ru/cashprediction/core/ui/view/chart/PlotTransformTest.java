package ru.cashprediction.core.ui.view.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Преобразование координат графика (архитектура §3.4): формула x → дата с ограничением краями, дата → x, сумма → y,
 * попадание в область построения и вырожденные значения.
 */
class PlotTransformTest {

    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final PlotTransform PLOT = new PlotTransform(80, 28, 1096, 640, FROM, 365, 0, 60_000_000);

    @Test
    void dateAtClampsAtPlotEdges() {
        assertEquals(FROM, PLOT.dateAt(80));
        assertEquals(FROM, PLOT.dateAt(79));
        assertEquals(FROM, PLOT.dateAt(-1e9));
        assertEquals(FROM, PLOT.dateAt(Double.NaN));
        assertEquals(LocalDate.of(2026, 12, 31), PLOT.dateAt(80 + 1096));
        assertEquals(LocalDate.of(2026, 12, 31), PLOT.dateAt(1e9));
        assertEquals(LocalDate.of(2026, 12, 31), PLOT.dateAt(Double.POSITIVE_INFINITY));
    }

    @Test
    void dateAtUsesFloorFormula() {
        double dayWidth = 1096.0 / 365;
        assertEquals(LocalDate.of(2026, 1, 1), PLOT.dateAt(80 + dayWidth * 0.99));
        assertEquals(LocalDate.of(2026, 1, 2), PLOT.dateAt(80 + dayWidth * 1.01));
        for (int i = 0; i < 365; i++) {
            LocalDate day = FROM.plusDays(i);
            assertEquals(day, PLOT.dateAt(PLOT.xOf(day) + dayWidth / 2), day.toString());
        }
    }

    @Test
    void xOfIsLeftEdgeOfDay() {
        assertEquals(80, PLOT.xOf(FROM), 1e-9);
        assertEquals(80 + 1096, PLOT.xOf(FROM.plusDays(365)), 1e-9);
        assertEquals(80 + 1096.0 / 365 * 10, PLOT.xOf(FROM.plusDays(10)), 1e-9);
    }

    @Test
    void yOfMapsScaleToPlotHeight() {
        assertEquals(28, PLOT.yOf(60_000_000), 1e-9);
        assertEquals(668, PLOT.yOf(0), 1e-9);
        assertEquals(348, PLOT.yOf(30_000_000), 1e-9);
        PlotTransform negative = new PlotTransform(80, 28, 1096, 640, FROM, 365, -10_000_000, 30_000_000);
        assertEquals(28 + 640 * 0.75, negative.yOf(0), 1e-9);
    }

    @Test
    void containsIncludesBorders() {
        assertTrue(PLOT.contains(80, 28));
        assertTrue(PLOT.contains(1176, 668));
        assertTrue(PLOT.contains(600, 300));
        assertFalse(PLOT.contains(79.9, 300));
        assertFalse(PLOT.contains(1176.1, 300));
        assertFalse(PLOT.contains(600, 27.9));
        assertFalse(PLOT.contains(600, 668.1));
        assertFalse(PLOT.contains(Double.NaN, 300));
    }

    @Test
    void degenerateValuesDoNotThrow() {
        PlotTransform zeroDays = new PlotTransform(80, 28, 100, 100, FROM, 0, 5, 5);
        assertEquals(FROM, zeroDays.dateAt(150));
        assertEquals(78, zeroDays.yOf(123), 1e-9, "min == max - середина по высоте");
        PlotTransform zeroWidth = new PlotTransform(80, 28, 0, 100, FROM, 30, 0, 100);
        assertEquals(FROM, zeroWidth.dateAt(500));
    }
}
