package ru.cashprediction.parity.check;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Каркас проверки счётчиков до и после настоящего нажатия клавиши S3. */
public final class HotkeyParityTest {
    /** Проверяет, что изменился только нужный счётчик ровно на единицу. */
    public static void exactlyOnce(String command, Map<String, Integer> before, Map<String, Integer> after) {
        var keys = new java.util.HashSet<>(before.keySet()); keys.addAll(after.keySet()); keys.add(command);
        for (String key : keys) assertEquals(key.equals(command) ? 1 : 0,
                after.getOrDefault(key, 0) - before.getOrDefault(key, 0), key);
    }
    /** Проверяет защиту от двойного выполнения команды. */
    @Test void counterProbeRejectsDoubleDispatch() {
        exactlyOnce("save", Map.of(), Map.of("save", 1));
        assertThrows(AssertionError.class, () -> exactlyOnce("save", Map.of(), Map.of("save", 2)));
        assertThrows(AssertionError.class, () -> exactlyOnce("save", Map.of(), Map.of("save", 1, "other", 1)));
    }
}
