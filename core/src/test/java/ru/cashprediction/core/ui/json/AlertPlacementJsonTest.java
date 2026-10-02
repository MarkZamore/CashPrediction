package ru.cashprediction.core.ui.json;

import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.Placement;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет передачу владельца и восстановленной геометрии сообщения без изменения старого формата. */
final class AlertPlacementJsonTest {
    /** Границы и вложенный владелец доступны браузеру после восстановления. */
    @Test void restoredAlertIncludesPlacement() {
        var placement = Placement.restored("w-parent", new WindowBounds(30, 40, 500, 300));
        var wire = object(new WebEffect.AlertOpen("a1", AlertCatalog.unsavedChanges("Plan"), placement));
        var position = (Map<?, ?>) wire.get("placement");
        assertEquals("w-parent", position.get("ownerId"));
        var bounds = (Map<?, ?>) position.get("bounds");
        assertEquals(30.0, ((Number) bounds.get("x")).doubleValue());
        assertEquals(40.0, ((Number) bounds.get("y")).doubleValue());
        assertEquals(500.0, ((Number) bounds.get("width")).doubleValue());
        assertEquals(300.0, ((Number) bounds.get("height")).doubleValue());
    }

    /** Старые сообщения сохраняют прежние JSON-фикстуры и центрирование. */
    @Test void oldConstructorOmitsPlacement() {
        assertFalse(object(new WebEffect.AlertOpen("a1", AlertCatalog.unsavedChanges("Plan"))).containsKey("placement"));
    }

    /** Использует настоящий сериализатор эффектов, а не вручную составленный JSON. */
    private Map<?, ?> object(WebEffect effect) {
        return (Map<?, ?>) JsonParser.parse(UiJson.write(effect));
    }
}
