package ru.cashprediction.fx.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.json.JsonParser;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет истинные размеры четырёх форм и отсутствие обрезки на ограниченном снимке. */
@EnabledIfSystemProperty(named = "fx.verticalDump", matches = ".+")
class FxVerticalMetricsTest {
    private Path artifact(String step, String suffix) throws Exception {
        try (var paths = Files.walk(Path.of(System.getProperty("fx.verticalDump")).resolve(step))) {
            return paths.filter(p -> p.getFileName().toString().equals(step + suffix)).findFirst().orElseThrow();
        }
    }
    private Map<?, ?> bounds(String step) throws Exception {
        var tree = (Map<?, ?>) JsonParser.parse(Files.readString(artifact(step, ".json")));
        return (Map<?, ?>) ((Map<?, ?>) ((List<?>) tree.get("windows")).getLast()).get("bounds");
    }
    private void size(String step, int width, int swing, int web) throws Exception {
        var b = bounds(step);
        assertEquals(width, ((Number) b.get("width")).doubleValue(), 4);
        assertEquals(swing, ((Number) b.get("height")).doubleValue(), 4, "Swing");
        assertEquals(web, ((Number) b.get("height")).doubleValue(), 4, "Web");
    }
    /** Размеры получаются раскладкой контролов и текстов, а не подменой границ дампа. */
    @Test void fourBodiesMatchBothActualClientsWithinFourPixels() throws Exception {
        size("settings", 560, 694, 693); size("income-create", 880, 625, 625);
        size("adjustment", 560, 415, 415); size("goal", 640, 334, 329);
    }
    /** Все четыре тела целиком попадают в реальный снимок 1366x768. */
    @Test void everyDialogFitsTheActualSmallViewport() throws Exception {
        for (String step : List.of("settings", "goal", "income-create", "adjustment")) {
            var image = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(Files.readAllBytes(artifact(step, ".png"))));
            assertFalse(image.isError()); assertEquals(1366, image.getWidth()); assertEquals(768, image.getHeight());
            var b = bounds(step); double x = ((Number) b.get("x")).doubleValue(), y = ((Number) b.get("y")).doubleValue();
            assertTrue(x >= 0 && y >= 0, step);
            assertTrue(x + ((Number) b.get("width")).doubleValue() <= image.getWidth(), step);
            assertTrue(y + ((Number) b.get("height")).doubleValue() <= image.getHeight(), step);
        }
    }
    /** Диагностика считывает реальные узлы общего каркаса, сохраняя резерв строки проблем. */
    @Test void realSkeletonHasSharedTextAndControlMetrics() throws Exception {
        var root = Path.of(System.getProperty("fx.verticalDump"));
        for (String step : List.of("settings", "goal", "income-create", "adjustment")) {
            String line;
            try (var paths = Files.walk(root.resolve(step))) {
                line = paths.filter(p -> p.getFileName().toString().endsWith("stdout.log"))
                        .flatMap(p -> { try { return Files.readAllLines(p).stream(); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } })
                        .filter(s -> s.startsWith("FORM_METRICS " + step + " ")).findFirst().orElseThrow();
            }
            assertTrue(line.contains("header=37.0"), line); assertTrue(line.contains("problem=32.0"), line);
            assertTrue(line.contains("footer=36.0"), line);
        }
    }
}
