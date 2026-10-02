package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.event.KeyEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.UiIntents;
import ru.cashprediction.core.ui.command.KeyChord;

/** Проверяет полный цикл драйвера через настоящий единственный диспетчер Swing. */
class SwingKeyCycleTest {
    @Test void standaloneAltHasPhysicalModifierTransition() {
        var events = SwingUiDriver.keyEvents(new JPanel(), KeyChord.parse("Alt"));
        assertEquals(List.of(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED), events.stream().map(KeyEvent::getID).toList());
        assertTrue(events.getFirst().isAltDown()); assertFalse(events.getLast().isAltDown());
        assertTrue(events.stream().allMatch(event -> event.getKeyCode() == KeyEvent.VK_ALT && event.getKeyChar() == KeyEvent.CHAR_UNDEFINED));
    }
    @Test void pressReleaseDispatchesAltAndRegularChordExactlyOnce() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<KeyChord> dispatched = new ArrayList<>(); SwingUiPort port = new SwingUiPort(null);
            UiIntents intents = (UiIntents) Proxy.newProxyInstance(UiIntents.class.getClassLoader(), new Class<?>[]{UiIntents.class}, (proxy, method, args) -> {
                if (method.getName().equals("key")) { dispatched.add((KeyChord) args[0]); return true; }
                return method.getReturnType() == boolean.class ? false : null;
            });
            port.bind(intents);
            try {
                for (KeyChord chord : List.of(KeyChord.parse("Alt"), KeyChord.parse("Ctrl+S"), KeyChord.parse("Alt+Shift+N"))) {
                    var events = SwingUiDriver.keyEvents(new JPanel(), chord);
                    events.forEach(port.keys::dispatchKeyEvent);
                    if (!chord.key().equals("ALT")) assertEquals(events.getFirst().getModifiersEx(), events.getLast().getModifiersEx());
                }
                assertEquals(List.of(KeyChord.parse("Alt"), KeyChord.parse("Ctrl+S"), KeyChord.parse("Alt+Shift+N")), dispatched);
            } finally { port.keys.close(); port.scheduler().shutdown(); }
        });
    }
}
