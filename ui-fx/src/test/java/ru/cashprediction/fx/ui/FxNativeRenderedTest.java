package ru.cashprediction.fx.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.json.JsonParser;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет отдельные настоящие пробы нативных классов и снимки сцен без искусственного заполнения переписи. */
@EnabledIfSystemProperty(named = "fx.nativeDump", matches = ".+")
class FxNativeRenderedTest {
    private Map<?, ?> dump(String step) throws Exception {
        return (Map<?, ?>) JsonParser.parse(Files.readString(Path.of(System.getProperty("fx.nativeDump"), "native23", step + ".json")));
    }
    /** Путь выбора другой папки создаёт DirectoryChooser и регистрирует настоящий запрос. */
    @Test void directoryChooserIsActuallyConstructed() throws Exception {
        var directory = dump("directory-request");
        assertEquals(1, ((Number) ((Map<?, ?>) directory.get("classCensus")).get("DirectoryChooser")).intValue());
        var requests = (List<?>) directory.get("chooserRequests"); assertEquals(1, requests.size());
        assertEquals("directory", ((Map<?, ?>) requests.getFirst()).get("kind"));
        assertFalse(((Map<?, ?>) requests.getFirst()).get("title").toString().isEmpty());
    }
    /** Контекстное событие проходит реальный обработчик и открывает нативное меню. */
    @Test void contextEventReachesNativeMenu() throws Exception {
        var context = dump("context-event");
        assertTrue(((Number) ((Map<?, ?>) context.get("classCensus")).get("ContextMenuEvent")).intValue() > 0);
        assertEquals("row:r1@2026-10-05", ((Map<?, ?>) ((List<?>) context.get("contextMenus")).getFirst()).get("target"));
    }
    /** Сцены графика и обоих всплывающих окон дают полноценные снимки PNG. */
    @Test void sceneSnapshotsContainRealPixels() throws Exception {
        for (String step : List.of("day-card", "sparkline", "about")) {
            byte[] bytes = Files.readAllBytes(Path.of(System.getProperty("fx.nativeDump"), "native23", step + ".png"));
            assertTrue(bytes.length > 10000, step);
            var image = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(bytes));
            assertFalse(image.isError()); assertEquals(1200, image.getWidth()); assertEquals(800, image.getHeight());
        }
    }
}
