package ru.cashprediction.fx.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.json.JsonParser;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет измеренные позиции кнопок настоящих двух визуальных проб, не подменяя дампы моделями. */
@EnabledIfSystemProperty(named = "fx.visualDump", matches = ".+")
class FxVisualRenderedTest {
    /** Нативное выделение занимает строку таблицы и реально рисует цвет accent.weak в PNG. */
    @Test void selectedRowIsPaintedInActualScenePixels() throws Exception {
        var image = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(Files.readAllBytes(artifact("undo-skip.png"))));
        assertFalse(image.isError());
        var tree = dump("undo-skip"); var regions = (Map<?, ?>) ((Map<?, ?>) tree.get("frame")).get("regions");
        var header = (Map<?, ?>) regions.get("table.header"); var status = (Map<?, ?>) regions.get("status");
        int from = ((Number) header.get("y")).intValue() + ((Number) header.get("height")).intValue();
        int to = ((Number) status.get("y")).intValue(); int painted = 0;
        int expected = ru.cashprediction.core.ui.token.ColorToken.ACCENT_WEAK.argb();
        for (int y = from; y < to; y++) for (int x = 0; x < 1180; x++) if (image.getPixelReader().getArgb(x, y) == expected) painted++;
        assertTrue(painted > 10000, "selected row pixels: " + painted);
    }
    private Map<?, ?> dump(String step) throws Exception {
        return (Map<?, ?>) JsonParser.parse(Files.readString(artifact(step + ".json")));
    }
    private Path artifact(String name) throws Exception {
        try (var paths = Files.walk(Path.of(System.getProperty("fx.visualDump")))) {
            return paths.filter(p -> p.getFileName().toString().equals(name)).findFirst().orElseThrow();
        }
    }
    private void positions(List<?> buttons, List<String> ids, double first, double second) {
        assertEquals(ids, buttons.stream().map(b -> ((Map<?, ?>) b).get("id")).toList());
        assertEquals(first, ((Number) ((Map<?, ?>) buttons.getFirst()).get("x")).doubleValue(), 4);
        assertEquals(second, ((Number) ((Map<?, ?>) buttons.getLast()).get("x")).doubleValue(), 4);
    }
    /** Отступ панели параметров даёт те же content-local позиции, что независимый Web-снимок. */
    @Test void settingsButtonsMatchActualWebWithinFourPixels() throws Exception {
        var window = (Map<?, ?>) ((List<?>) dump("settings").get("windows")).getFirst();
        positions((List<?>) window.get("buttons"), List.of("ok", "cancel"), 359, 455);
    }
    /** Перенос длинной шапки Alert не сдвигает кнопки за ширину контента подтверждения. */
    @Test void deleteRuleButtonsMatchActualWebWithinFourPixels() throws Exception {
        var alert = (Map<?, ?>) ((List<?>) dump("delete-rule").get("alerts")).getFirst();
        positions((List<?>) alert.get("buttons"), List.of("delete", "cancel"), 259, 355);
        assertEquals(460, ((Number) alert.get("minWidth")).intValue());
        assertEquals(true, ((Map<?, ?>) ((List<?>) alert.get("buttons")).getFirst()).get("isDefault"));
    }
    /** Штатный синий значок вопроса остаётся видимым после изменения раскладки Alert. */
    @Test void confirmationKeepsItsNativeQuestionGraphic() throws Exception {
        var image = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(Files.readAllBytes(artifact("delete-rule.png"))));
        assertFalse(image.isError()); assertEquals(1200, image.getWidth()); assertEquals(800, image.getHeight());
        int blue = 0;
        // Фиксированная область значка в снимке контрольной точки 1200x800 не содержит кнопок.
        for (int y = 348; y < 404; y++) for (int x = 764; x < 824; x++) {
            var color = image.getPixelReader().getColor(x, y);
            if (color.getBlue() > 0.5 && color.getBlue() > color.getRed() + 0.2 && color.getBlue() > color.getGreen() + 0.1) blue++;
        }
        assertTrue(blue > 100, "native confirmation graphic: " + blue);
    }
}
