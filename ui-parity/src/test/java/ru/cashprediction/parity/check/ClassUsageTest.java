package ru.cashprediction.parity.check;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Каркас проверки реальной переписи классов FX; список ожидаемых классов задаёт спецификация. */
public final class ClassUsageTest {
    /** Требует каждый ожидаемый класс в реальной переписи с положительным числом экземпляров. */
    public static void census(Set<String> expected, Map<String, Integer> actual) {
        if (expected.isEmpty()) throw new IllegalArgumentException("Empty expected census");
        for (String name : expected) assertTrue(actual.getOrDefault(name, 0) > 0, "Not instantiated: " + name);
    }
    /** Проверяет, что список имён без экземпляров не засчитывается. */
    @Test void namesWithoutInstancesFail() {
        census(Set.of("Menu"), Map.of("Menu", 1));
        assertThrows(AssertionError.class, () -> census(Set.of("Menu"), Map.of("Menu", 0)));
    }
}
