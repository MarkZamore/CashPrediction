package ru.cashprediction.parity.check.performance;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

/** Строгий бюджет настоящего render и граница DOM; отрицательные пробы не являются доказательством скорости. */
final class VirtualTablePerformanceGate {
    /** Проверяет лимиты независимо, чтобы медленная либо eager-таблица не могла дать ложный успех. */
    static void verify(Map<String, Object> evidence, int width) {
        verifyDom(evidence);
        double elapsed = number(evidence, "elapsedMs");
        assertTrue(Double.isFinite(elapsed) && elapsed >= 0 && elapsed < 50, "render budget <50ms: " + elapsed);
        assertEquals(width, (int) number(evidence, "width"));
        assertTrue(number(evidence, "documentWidth") <= width, "horizontal document scroll");
        assertTrue(number(evidence, "bodyWidth") <= width, "horizontal body scroll");
        assertEquals(List.of(), evidence.get("errors"));
        assertTrue(number(evidence, "pages") <= 7, "bounded cache");
    }

    /** Не допускает 200000 DOM-строк даже при выдуманном быстром времени. */
    static void verifyDom(Map<String, Object> evidence) {
        assertEquals(VirtualTableFixture.TOTAL, (int) number(evidence, "total"));
        double height = number(evidence, "viewportHeight");
        assertTrue(height > 0, "visible table viewport required");
        int limit = (int) Math.ceil(height / 26) + 6;
        int rows = (int) number(evidence, "rows");
        assertTrue(rows > 0 && rows <= limit, "virtual DOM row bound " + rows + " > " + limit);
        assertEquals(rows * (int) number(evidence, "columns"), (int) number(evidence, "cells"));
        double top = number(evidence, "scrollTop");
        assertTrue(Double.isFinite(top) && top >= 0);
        int first = Math.max(0, (int) Math.floor(top / 26) - 3);
        int expectedCount = Math.min(VirtualTableFixture.TOTAL - first, limit);
        assertEquals(expectedCount, rows, "complete viewport plus overscan");
        var expectedIndices = IntStream.range(first, first + expectedCount).boxed().toList();
        var indices = ((List<?>) evidence.get("indices")).stream().map(n -> ((Number) n).intValue()).toList();
        assertEquals(expectedIndices, indices, "all ordered indices required");
        assertEquals(expectedIndices.stream().map(i -> "stress@" + i).toList(), evidence.get("rowIds"));
    }

    /** Проверяет каждую ячейку по независимым данным API-фикстуры, включая размеры и видимость. */
    @SuppressWarnings("unchecked")
    static void verifyContents(Map<String, Object> evidence, List<String> columnIds, List<String> text) {
        verifyDom(evidence);
        assertEquals(8, columnIds.size(), "all bootstrap columns required");
        assertEquals(columnIds.size(), text.size());
        assertEquals(columnIds.size(), (int) number(evidence, "columns"));
        var rows = (List<List<Map<String, Object>>>) evidence.get("rowCells");
        assertEquals((int) number(evidence, "rows"), rows.size());
        double visibleArea = 0;
        int first = Math.max(0, (int) Math.floor(number(evidence, "scrollTop") / 26) - 3);
        for (int row = 0; row < rows.size(); row++) {
            var cells = rows.get(row);
            double rowArea = 0;
            assertEquals(columnIds.size(), cells.size());
            for (int col = 0; col < cells.size(); col++) {
                var cell = cells.get(col);
                assertEquals(columnIds.get(col), cell.get("id"));
                assertEquals(text.get(col), cell.get("text"));
                assertEquals(Boolean.TRUE, cell.get("visible"), "hidden cell/ancestor");
                assertTrue(number(cell, "width") > 0 && number(cell, "height") > 0, "nonzero cell geometry");
                double area = number(cell, "visibleArea");
                assertTrue(Double.isFinite(area) && area >= 0);
                visibleArea += area;
                rowArea += area;
            }
            double rowTop = (first + row) * 26.0;
            double scrollTop = number(evidence, "scrollTop");
            if (rowTop < scrollTop + number(evidence, "viewportHeight") && rowTop + 26 > scrollTop)
                assertTrue(rowArea > 0, "every visible row must intersect viewport: " + (first + row));
        }
        assertTrue(visibleArea > 0, "actual viewport intersection required");
    }

    private static double number(Map<String, Object> evidence, String key) { return ((Number) evidence.get(key)).doubleValue(); }
}
