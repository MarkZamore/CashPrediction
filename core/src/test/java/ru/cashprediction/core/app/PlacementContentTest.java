package ru.cashprediction.core.app;

import org.junit.jupiter.api.Test;
import ru.cashprediction.core.session.WindowBounds;
import static org.junit.jupiter.api.Assertions.*;

/** Центрирование использует измеренное содержимое владельца без предположений о рамке ОС. */
final class PlacementContentTest {
    /** Перенос монитора не меняет положение относительно владельца, в том числе вложенного. */
    @Test void translatedOwnerProducesTranslatedContent() {
        var first = Placement.centerContent(new WindowBounds(20, 30, 1200, 800), 560, 694);
        assertEquals(new WindowBounds(340, 83, 560, 694), first);
        var moved = Placement.centerContent(new WindowBounds(-1900, 130, 1200, 800), 560, 694);
        assertEquals(first.x() - 1920, moved.x());
        assertEquals(first.y() + 100, moved.y());
        var nested = Placement.centerContent(first, 400, 300);
        assertEquals(new WindowBounds(420, 280, 400, 300), nested);
    }

    /** Неизвестные и некорректные размеры не заменяются выдуманными координатами. */
    @Test void invalidMeasurementsAreRejected() {
        var owner = new WindowBounds(0, 0, 1200, 800);
        for (double value : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> Placement.centerContent(owner, value, 300));
            assertThrows(IllegalArgumentException.class, () -> Placement.centerContent(owner, 400, value));
        }
        assertThrows(NullPointerException.class, () -> Placement.centerContent(null, 400, 300));
        assertThrows(IllegalArgumentException.class,
                () -> Placement.centerContent(new WindowBounds(Double.NaN, 0, 100, 100), 40, 30));
        // Крупный диалог честно выходит за владельца: клиент затем учитывает реальную видимую область экрана.
        assertEquals(new WindowBounds(-100, -100, 1400, 1000), Placement.centerContent(owner, 1400, 1000));
    }
}
