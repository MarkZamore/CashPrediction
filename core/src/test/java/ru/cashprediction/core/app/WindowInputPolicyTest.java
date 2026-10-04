package ru.cashprediction.core.app;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/** Проверяет общую modal policy без добавления проверки существования адресата. */
class WindowInputPolicyTest {
    /** Без модальности сохраняется permissive predicate, а lookup живого окна остаётся у вызывающего слоя. */
    @Test void noModalKeepsExistingPermissivePolicy() {
        var windows = OpenWindows.NONE.with(window("form", false, WindowState.MAIN_OWNER));
        for (String id : new String[]{"form", "unknown", "main", "", null}) {
            assertTrue(OpenWindows.NONE.acceptsWindowInput(id));
            assertTrue(windows.acceptsWindowInput(id));
        }
    }

    /** Последнее модальное окно блокирует родителя; более позднее немодальное его не вытесняет. */
    @Test void nestedModalAndClosureMatchPreviousWebPredicate() {
        var windows = new OpenWindows(List.of(window("parent", true, WindowState.MAIN_OWNER),
                window("child", true, "parent"), window("nonmodal", false, WindowState.MAIN_OWNER)));
        assertTrue(windows.acceptsWindowInput("child"));
        assertFalse(windows.acceptsWindowInput("parent"));
        assertFalse(windows.acceptsWindowInput("nonmodal"));
        assertFalse(windows.acceptsWindowInput("unknown"));
        var remaining = windows.without("child");
        assertTrue(remaining.acceptsWindowInput("parent"));
        assertFalse(remaining.acceptsWindowInput("nonmodal"));
        for (OpenWindows value : List.of(OpenWindows.NONE, windows, remaining, windows.without("parent"))) {
            for (String id : new String[]{"parent", "child", "nonmodal", "unknown", "main", "", null}) {
                boolean previous = value.topModal().map(window -> window.windowId().equals(id)).orElse(true);
                assertEquals(previous, value.acceptsWindowInput(id));
            }
        }
        assertEquals("child", windows.topModal().orElseThrow().windowId());
    }

    /** Создаёт только неизменяемый ledger entry, не mock native-окна. */
    private static OpenWindows.OpenWindow window(String id, boolean modal, String owner) {
        return new OpenWindows.OpenWindow(id, WindowType.TEXT_INPUT, "", modal, owner, "");
    }
}
