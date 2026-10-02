package ru.cashprediction.core.ui.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** Проверяет JSON Pointer, недостающие элементы и допуск прямоугольников. */
class DumpDiffTest {

    @Test
    void reportsSortedEscapedPointersAndMissingValues() {
        List<DumpDiff.Difference> differences = DumpDiff.diff(
                Map.of("z", 1, "a/b", List.of("one", "two")),
                Map.of("z", 2, "a/b", List.of("one")), 0);
        assertEquals(List.of("/a~1b/1", "/z"), differences.stream().map(DumpDiff.Difference::pointer).toList());
    }

    @Test
    void acceptsOnlyBoxCoordinateDifferencesInsideTolerance() {
        assertEquals(List.of(), DumpDiff.diff(Map.of("bounds", Map.of("x", 10.0, "width", 20.0)),
                Map.of("bounds", Map.of("x", 11.5, "width", 18.1)), 2));
        assertEquals(List.of("/rowCount"), DumpDiff.diff(Map.of("rowCount", 10), Map.of("rowCount", 11), 2)
                .stream().map(DumpDiff.Difference::pointer).toList());
    }

    @Test
    void preservesIntegerAndDecimalPrecision() {
        assertEquals(1, DumpDiff.diff(9007199254740992L, 9007199254740993L, 0).size());
        assertEquals(1, DumpDiff.diff(new BigDecimal("1.00000000000000001"), BigDecimal.ONE, 0).size());
        assertEquals(List.of(), DumpDiff.diff(new BigInteger("123456789012345678901234567890"),
                new BigDecimal("123456789012345678901234567890.00"), 0));
        assertEquals(List.of(), DumpDiff.diff(1L, new BigDecimal("1.00"), 0));
    }

    @Test
    void toleranceCannotHideNonGeometryOrInvalidTolerance() {
        assertEquals(List.of("/height", "/width", "/x"), DumpDiff.diff(
                Map.of("x", 1, "width", 10, "height", 20),
                Map.of("x", 2, "width", 11, "height", 21), 4)
                .stream().map(DumpDiff.Difference::pointer).toList());
        for (double tolerance : new double[] {-1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> DumpDiff.diff(1, 1, tolerance));
        }
    }

    @Test
    void placeholderButtonsUseTheSamePixelToleranceWithoutHidingContent() {
        var expected = Map.of("table", Map.of("placeholderButtons", Map.of("empty.addIncome",
                Map.of("x", 404, "text", "income", "enabled", true))));
        var inside = Map.of("table", Map.of("placeholderButtons", Map.of("empty.addIncome",
                Map.of("x", 400, "text", "income", "enabled", true))));
        assertEquals(List.of(), DumpDiff.diff(expected, inside, 4));
        assertEquals(List.of("/table/placeholderButtons/empty.addIncome/x"),
                DumpDiff.diff(expected, inside, 0).stream().map(DumpDiff.Difference::pointer).toList());
        var outside = Map.of("table", Map.of("placeholderButtons", Map.of("empty.addIncome",
                Map.of("x", 399, "text", "different", "enabled", false))));
        assertEquals(List.of("/table/placeholderButtons/empty.addIncome/enabled",
                "/table/placeholderButtons/empty.addIncome/text",
                "/table/placeholderButtons/empty.addIncome/x"),
                DumpDiff.diff(expected, outside, 4).stream().map(DumpDiff.Difference::pointer).toList());
        assertEquals(1, DumpDiff.diff(Map.of("data", Map.of("placeholderButtons", Map.of("id", Map.of("x", 1)))),
                Map.of("data", Map.of("placeholderButtons", Map.of("id", Map.of("x", 2)))), 4).size());
    }

    @Test
    void geometryToleranceHasAnExactBoundary() {
        assertEquals(List.of(), DumpDiff.diff(Map.of("bounds", Map.of("x", new BigDecimal("0.1"))),
                Map.of("bounds", Map.of("x", new BigDecimal("0.3"))), 0.2));
        assertEquals(1, DumpDiff.diff(Map.of("bounds", Map.of("x", new BigDecimal("0.1"))),
                Map.of("bounds", Map.of("x", new BigDecimal("0.30000000000000001"))), 0.2).size());
        assertEquals(List.of(), DumpDiff.diff(Map.of("frame", Map.of("regions", Map.of("center",
                Map.of("width", 100)))), Map.of("frame", Map.of("regions", Map.of("center",
                Map.of("width", 104)))), 4));
    }
}
