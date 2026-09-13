package ru.cashprediction.core.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Тесты пункта списка «- ключ: значение».
 */
class ListItemTest {

    @Test
    void parsesTolerantly() {
        assertEquals(Optional.of(new ListItem("Валюта", "₽")), ListItem.parse("- Валюта: ₽"));
        assertEquals(Optional.of(new ListItem("Горизонт", "до 2027-01-01")), ListItem.parse("  *   Горизонт  :  до 2027-01-01  "));
        assertEquals(Optional.of(new ListItem("Время", "10:30")), ListItem.parse("+ Время: 10:30"), "значение с двоеточием");
        assertEquals(Optional.of(new ListItem("Пусто", "")), ListItem.parse("- Пусто:"));
        assertEquals(Optional.of(new ListItem("Слитно", "да")), ListItem.parse("-Слитно:да"));
    }

    @Test
    void rejectsNonItems() {
        assertTrue(ListItem.parse("Валюта: ₽").isEmpty());
        assertTrue(ListItem.parse("- без двоеточия").isEmpty());
        assertTrue(ListItem.parse("- : значение").isEmpty());
        assertTrue(ListItem.parse("| a: b |").isEmpty());
        assertTrue(ListItem.parse(null).isEmpty());
    }

    @Test
    void formatsCanonically() {
        assertEquals("- Валюта: ₽", new ListItem(" Валюта ", " ₽ ").format());
        assertEquals("- Пусто:", new ListItem("Пусто", null).format());
    }
}
