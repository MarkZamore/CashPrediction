package ru.cashprediction.parity.check.hotkey;

import java.util.Map;
import java.util.TreeSet;
import java.util.Objects;
import java.math.BigDecimal;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.parity.pipeline.DumpTrees;
import static org.junit.jupiter.api.Assertions.*;

/** Строгие проверки наблюдений; ожидаемые результаты не строятся из модели интерфейса. */
public final class HotkeyAssertions {
    private HotkeyAssertions() { }

    /** Читает реальный дамп, отклоняя строковые, дробные и отрицательные счётчики до преобразования схемы. */
    public static UiDump observation(String json, String client, String step) {
        Object tree = JsonParser.parse(json);
        assertTrue(tree instanceof Map<?, ?>, "Missing dump object");
        Object raw = ((Map<?, ?>) tree).get("counters");
        assertTrue(raw instanceof Map<?, ?>, "Missing controller counters object");
        for (var entry : ((Map<?, ?>) raw).entrySet()) {
            assertTrue(entry.getKey() instanceof String && entry.getValue() instanceof Number,
                    "Invalid controller counter: " + entry);
            int count;
            try { count = new BigDecimal(entry.getValue().toString()).intValueExact(); }
            catch (ArithmeticException failure) { throw new AssertionError("Invalid controller counter: " + entry, failure); }
            assertTrue(count >= 0, "Negative controller counter: " + entry.getKey());
        }
        UiDump dump = DumpTrees.read(json);
        assertEquals(UiDump.SCHEMA, dump.schema(), "Unsupported real dump schema");
        assertEquals(client, dump.client()); assertEquals("hotkey", dump.scenario()); assertEquals(step, dump.step());
        return dump;
    }

    /** Требует приращение только указанной команды на единицу, включая ранее отсутствующие счётчики. */
    public static void exactlyOnce(String command, Map<String, Integer> before, Map<String, Integer> after) {
        Objects.requireNonNull(command);
        assertFalse(command.isBlank(), "Empty command");
        delta(command, before, after);
    }

    /** Требует неизменные счётчики: отключённая команда не считается выполненной. */
    public static void unchanged(Map<String, Integer> before, Map<String, Integer> after) {
        delta(null, before, after);
    }

    /** Проверяет видимый сегмент сообщения и отсутствие той же подсказки до нажатия. */
    public static void disabledHint(String hintKey, UiDump before, UiDump after) {
        unchanged(before.counters(), after.counters());
        String text = UiText.get(hintKey);
        assertFalse(message(before, text), "Hint already visible before input: " + hintKey);
        assertTrue(message(after, text), "Missing visible status/message: " + hintKey);
        assertEquals(before.windows(), after.windows(), "Disabled command opened/changed a form");
        assertEquals(before.alerts(), after.alerts(), "Disabled command opened/changed an alert");
        assertEquals(before.popups(), after.popups(), "Disabled command opened/changed a popup");
        assertEquals(before.chooserRequests(), after.chooserRequests(), "Disabled command opened/changed a chooser request");
    }

    private static boolean message(UiDump dump, String text) {
        return dump.status().stream().anyMatch(segment -> segment.id().equals("message")
                && segment.visible() && segment.text().equals(text));
    }

    private static void delta(String command, Map<String, Integer> before, Map<String, Integer> after) {
        Objects.requireNonNull(before); Objects.requireNonNull(after);
        assertTrue(after.keySet().containsAll(before.keySet()), "Controller counter disappeared");
        var keys = new TreeSet<>(before.keySet()); keys.addAll(after.keySet());
        if (command != null) keys.add(command);
        for (String key : keys) {
            int old = before.getOrDefault(key, 0), current = after.getOrDefault(key, 0);
            assertTrue(old >= 0 && current >= 0, "Negative counter: " + key);
            assertEquals(key.equals(command) ? 1L : 0L, (long) current - old, "Counter delta: " + key);
        }
    }
}
