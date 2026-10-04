package ru.cashprediction.core.ui.selftest.paint;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Проверяет идентичность и ограниченные намерения опыта без ожидаемой краски. */
final class PaintCaptureRequestTest {
    /** Намерения копируются, а активная карточка в опыте только одна. */
    @Test void copiesIntentAndRejectsMultipleTargets() {
        var states = new HashMap<String, PaintCaptureRequest.CardState>(); states.put("now", PaintCaptureRequest.CardState.HOVER);
        var request = request("paint", 1, states); states.clear();
        assertEquals(Map.of("now", PaintCaptureRequest.CardState.HOVER), request.cardStates());
        assertThrows(UnsupportedOperationException.class, () -> request.cardStates().clear());
        assertThrows(IllegalArgumentException.class, () -> request("paint", 1,
                Map.of("now", PaintCaptureRequest.CardState.HOVER, "min", PaintCaptureRequest.CardState.FOCUS)));
    }

    /** Пути, номера попыток и хеш плана не принимают невалидные значения. */
    @Test void rejectsUnsafeNamesAndAttempts() {
        for (String name : new String[] {"../x", "x/y", "x\\y", ".", "..", "CON", "nul.json", "name.", "C:x", ""})
            assertThrows(IllegalArgumentException.class, () -> request(name, 1, Map.of()), name);
        assertThrows(IllegalArgumentException.class, () -> request("paint", 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new PaintCaptureRequest(PaintCaptureFixtures.RUN,
                PaintCaptureFixtures.CAPTURE, -1, "paint", "normal", 1, PaintCaptureFixtures.HASH, Map.of(), 10));
        assertThrows(IllegalArgumentException.class, () -> new PaintCaptureRequest(PaintCaptureFixtures.RUN,
                PaintCaptureFixtures.CAPTURE, 1, "paint", "normal", 1, "A".repeat(64), Map.of(), 10));
    }

    /** Кодек сохраняет намерения и знаковый дедлайн без потери точности JS. */
    @Test void requestRoundTrip() {
        var request = new PaintCaptureRequest(PaintCaptureFixtures.RUN, PaintCaptureFixtures.CAPTURE, 3,
                "paint", "normal", 1, PaintCaptureFixtures.HASH, Map.of(), Long.MIN_VALUE);
        byte[] bytes = PaintObservationCodec.writeRequest(request);
        assertEquals(request, PaintObservationCodec.readRequest(bytes));
        assertTrue(new String(bytes, java.nio.charset.StandardCharsets.UTF_8).contains("\"deadlineNanos\":\"-9223372036854775808\""));
    }

    /** Создаёт ограниченный вариант запроса. */
    private static PaintCaptureRequest request(String scenario, int attempt, Map<String, PaintCaptureRequest.CardState> states) {
        return new PaintCaptureRequest(PaintCaptureFixtures.RUN, PaintCaptureFixtures.CAPTURE, 3,
                scenario, "normal", attempt, PaintCaptureFixtures.HASH, states, 1000);
    }
}
