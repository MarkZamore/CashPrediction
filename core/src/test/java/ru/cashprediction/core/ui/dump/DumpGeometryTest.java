package ru.cashprediction.core.ui.dump;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет смену начала координат без подгонки размеров и положения реальных элементов. */
final class DumpGeometryTest {
    /** Перенос всего приложения на другой монитор не меняет относительный прямоугольник диалога. */
    @Test void commonScreenTranslationDoesNotChangeObservation() {
        var first = DumpGeometry.relativeContent(new UiDump.Box(1148, 568, 560, 416), new UiDump.Box(828, 400, 1200, 800));
        var moved = DumpGeometry.relativeContent(new UiDump.Box(-172, 668, 560, 416), new UiDump.Box(-492, 500, 1200, 800));
        assertEquals(new UiDump.Box(320, 168, 560, 416), first);
        assertEquals(first, moved);
    }

    /** Ошибка положения или размера не исчезает при переводе координат. */
    @Test void differencesRemainMeasurable() {
        var owner = new UiDump.Box(828, 400, 1200, 800);
        var original = DumpGeometry.relativeContent(new UiDump.Box(1148, 568, 560, 416), owner);
        var wrong = DumpGeometry.relativeContent(new UiDump.Box(1153, 575, 567, 425), owner);
        assertEquals(5, wrong.x() - original.x());
        assertEquals(7, wrong.y() - original.y());
        assertEquals(7, wrong.width() - original.width());
        assertEquals(9, wrong.height() - original.height());
    }

    /** Отсутствующее измерение и неконечные числа отклоняются, отрицательная координата допустима. */
    @Test void invalidMeasurementsAreNotInvented() {
        var owner = new UiDump.Box(0, 0, 1200, 800);
        assertThrows(NullPointerException.class, () -> DumpGeometry.relativeContent(null, owner));
        assertThrows(IllegalArgumentException.class, () -> DumpGeometry.relativeContent(new UiDump.Box(Double.NaN, 0, 1, 1), owner));
        assertThrows(IllegalArgumentException.class, () -> DumpGeometry.relativeContent(new UiDump.Box(0, 0, 0, 1), owner));
        assertEquals(-5, DumpGeometry.relativeContent(new UiDump.Box(-5, -2, 100, 80), owner).x());
    }
}
