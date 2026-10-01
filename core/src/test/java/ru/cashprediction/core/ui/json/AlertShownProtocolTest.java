package ru.cashprediction.core.ui.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;

/** Проверяет подтверждение реального показа сообщения и строгость его идентификатора. */
class AlertShownProtocolTest {

    /** Проверяет имена полей на проводе и полное преобразование записи в JSON и обратно. */
    @Test
    void alertShownRoundTripsWithWindowId() {
        WebIntent.AlertShown intent = new WebIntent.AlertShown("a3");
        String wire = "{\"type\":\"alertShown\",\"windowId\":\"a3\"}";
        assertEquals("alertShown", intent.type());
        assertEquals(wire, UiJson.write(intent));
        assertEquals(Map.of("type", "alertShown", "windowId", "a3"), UiJson.toTree(intent));
        assertEquals(intent, UiJson.readIntent(parse(wire)));
        assertEquals(intent, UiJson.readIntent(parse(UiJson.write(intent))));
    }

    /** Не позволяет отсутствующему, пустому или неверно типизированному id подтвердить показ. */
    @Test
    void malformedWindowIdsAreRejected() {
        for (String wire : List.of(
                "{\"type\":\"alertShown\"}",
                "{\"type\":\"alertShown\",\"alertId\":\"a3\"}",
                "{\"type\":\"alertShown\",\"windowId\":null}",
                "{\"type\":\"alertShown\",\"windowId\":\"\"}",
                "{\"type\":\"alertShown\",\"windowId\":\" \\t\\n\"}",
                "{\"type\":\"alertShown\",\"windowId\":17}",
                "{\"type\":\"alertShown\",\"windowId\":true}",
                "{\"type\":\"alertShown\",\"windowId\":[]}",
                "{\"type\":\"alertShown\",\"windowId\":{}}")) {
            assertThrows(IllegalArgumentException.class, () -> UiJson.readIntent(parse(wire)), wire);
        }
    }

    /** Проверяет, что разбор не переписывает непрозрачный идентификатор окна. */
    @Test
    void windowIdIsPreservedWithoutTrimmingOrPrefixRestrictions() {
        assertEquals(new WebIntent.AlertShown("restored-42"),
                UiJson.readIntent(Map.of("type", "alertShown", "windowId", "restored-42")));
        assertEquals(new WebIntent.AlertShown(" a3 "),
                UiJson.readIntent(Map.of("type", "alertShown", "windowId", " a3 ")));
    }

    /** Читает объект протокола независимо от сериализатора моделей интерфейса. */
    private static Map<String, Object> parse(String wire) {
        return Json.asObject(JsonParser.parse(wire), "test");
    }
}
