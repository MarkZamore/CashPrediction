package ru.cashprediction.parity.browserprobe;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет строгость DOM-доказательств устаревшего FormWindow.show, без запуска браузера. */
class FormShowEvidenceTest {
    /** Принимает только две полные записи измерений, отдельно для обычного окна и popup. */
    @Test void acceptsCompleteDialogAndPopupMeasurements() {
        requirePreserved(List.of(evidence("dialog"), evidence("popup")));
    }

    /** Отвергает изменение фокуса, выделения и геометрии даже при нулевых счётчиках отправки. */
    @Test void countersAloneCannotProveUnchangedDom() {
        for (String flag : List.of("focusPreserved", "selectionPreserved", "boundsPreserved")) {
            for (boolean missing : List.of(false, true)) {
                var changed = evidence("dialog");
                if (missing) changed.remove(flag); else changed.put(flag, false);
                assertThrows(AssertionError.class, () -> requirePreserved(List.of(changed, evidence("popup"))), flag);
            }
        }
    }

    /** Отвергает подтверждение окна или отправку его границ, включая дробный ненулевой счётчик. */
    @Test void rejectsEveryWindowSideEffect() {
        for (String counter : List.of("shownCount", "boundsCount", "totalSent")) {
            for (Number value : List.of(1, new BigDecimal("0.5"))) {
                var changed = evidence("popup"); changed.put(counter, value);
                assertThrows(AssertionError.class, () -> requirePreserved(List.of(evidence("dialog"), changed)), counter);
            }
        }
    }

    /** Неполный или продублированный набор окон не выдаётся за проверку обоих путей. */
    @Test void requiresBothKindsAndAllCounters() {
        assertThrows(AssertionError.class, () -> requirePreserved(null));
        assertThrows(AssertionError.class, () -> requirePreserved(List.of(evidence("dialog"))));
        assertThrows(AssertionError.class, () -> requirePreserved(List.of(evidence("dialog"), evidence("dialog"))));
        for (String key : List.of("shownCount", "boundsCount", "totalSent")) {
            var changed = evidence("popup"); changed.remove(key);
            assertThrows(AssertionError.class, () -> requirePreserved(List.of(evidence("dialog"), changed)), key);
        }
    }

    /** Проверяет записи фактического probe; gate обязан вызвать эту проверку после браузера. */
    static void requirePreserved(Object value) {
        var entries = assertInstanceOf(List.class, value, "Actual form-show DOM evidence required");
        assertEquals(2, entries.size(), "Dialog and popup must both execute");
        Set<Object> kinds = new HashSet<>();
        for (Object item : entries) {
            var entry = assertInstanceOf(Map.class, item);
            assertTrue(kinds.add(entry.get("kind")), "Duplicate form-show kind");
            for (String flag : List.of("focusPreserved", "selectionPreserved", "boundsPreserved")) {
                assertEquals(Boolean.TRUE, entry.get(flag), "Missing or changed actual DOM measurement: " + flag);
            }
            for (String counter : List.of("shownCount", "boundsCount", "totalSent")) {
                Number number = assertInstanceOf(Number.class, entry.get(counter), "Missing counter: " + counter);
                assertEquals(0, new BigDecimal(number.toString()).compareTo(BigDecimal.ZERO), "Window side effect: " + counter);
            }
        }
        assertEquals(Set.of("dialog", "popup"), kinds);
    }

    /** Создаёт синтетическую запись только для негативных проверок самого валидатора. */
    private static Map<String, Object> evidence(String kind) {
        return new LinkedHashMap<>(Map.of("kind", kind, "focusPreserved", true, "selectionPreserved", true,
                "boundsPreserved", true, "shownCount", 0, "boundsCount", 0, "totalSent", 0));
    }
}
