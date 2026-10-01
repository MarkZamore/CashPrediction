package ru.cashprediction.core.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/** Проверяет порядок, модальность и каскадное закрытие окон контроллера. */
class OpenWindowsTest {

    @Test
    void topModalUsesTheLastModalWindowAndSingleInstanceIsFound() {
        OpenWindows.OpenWindow first = window("w1", true, WindowState.MAIN_OWNER, "");
        OpenWindows.OpenWindow calculator = window("w2", false, WindowState.MAIN_OWNER, "GOAL_CALCULATOR");
        OpenWindows.OpenWindow second = window("w3", true, "w1", "");
        OpenWindows windows = new OpenWindows(List.of(first, calculator, second));

        assertTrue(windows.modalOpen());
        assertEquals(second, windows.topModal().orElseThrow());
        assertEquals(calculator, windows.findSingleInstance("GOAL_CALCULATOR").orElseThrow());
        assertTrue(windows.findSingleInstance("").isEmpty());
    }

    @Test
    void closingParentAlsoClosesEveryDescendantButKeepsIndependentWindows() {
        OpenWindows windows = new OpenWindows(List.of(
                window("w1", true, WindowState.MAIN_OWNER, ""),
                window("w2", true, "w1", ""),
                window("w3", false, "w2", ""),
                window("w4", false, WindowState.MAIN_OWNER, "")));

        OpenWindows remaining = windows.without("w1");
        assertEquals(List.of("w4"), remaining.windows().stream().map(OpenWindows.OpenWindow::windowId).toList());
        assertFalse(remaining.modalOpen());
    }

    @Test
    void closingUnknownWindowReturnsTheSameImmutableValue() {
        OpenWindows windows = OpenWindows.NONE.with(window("w1", false, WindowState.MAIN_OWNER, ""));
        assertSame(windows, windows.without("missing"));
    }

    private static OpenWindows.OpenWindow window(String id, boolean modal, String owner, String key) {
        return new OpenWindows.OpenWindow(id, WindowType.TEXT_INPUT, "", modal, owner, key);
    }
}
