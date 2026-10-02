package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.Map;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import ru.cashprediction.core.ui.command.*;

/** Единственный диспетчер физических клавиш перед штатными ускорителями Swing. */
public final class SwingKeyBridge implements KeyEventDispatcher, AutoCloseable {
    private final SwingUiPort port;
    private boolean altAlone;
    private boolean installed;

    /** Создаёт диспетчер для одного приложения. */
    public SwingKeyBridge(SwingUiPort port) { this.port = port; }
    /** Устанавливает ровно один глобальный мост. */
    public void install() { if (!installed) { installed = true; KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this); } }
    /** Удаляет глобальную привязку при завершении. */
    @Override public void close() { if (installed) { installed = false; KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this); } }
    /** Отправляет физическую клавишу ядру и поглощает только обработанное событие. */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (port.exited || event.isConsumed()) return false;
        if (event.getID() == KeyEvent.KEY_PRESSED && event.getKeyCode() == KeyEvent.VK_ALT) { altAlone = true; return false; }
        if (event.getID() == KeyEvent.KEY_RELEASED && event.getKeyCode() == KeyEvent.VK_ALT) {
            boolean single = altAlone; altAlone = false;
            if (single) return dispatch(new KeyChord(false, false, false, "ALT"), event);
            return false;
        }
        if (event.getID() != KeyEvent.KEY_PRESSED) return false;
        altAlone = false;
        String name = keyName(event.getKeyCode()); if (name == null) return false;
        return dispatch(new KeyChord(event.isControlDown(), event.isShiftDown(), event.isAltDown(), name), event);
    }
    private boolean dispatch(KeyChord chord, KeyEvent event) {
        Component focused = event.getComponent();
        SwingFormDialog form = port.forms.values().stream().filter(f -> focused != null && SwingUtilities.isDescendingFrom(focused, f.content)).findFirst().orElse(null);
        if (form != null) {
            if (form instanceof SwingQuickEditPopup && chord.key().equals("ESCAPE")) { form.session.closeRequested(); event.consume(); return true; }
            if (form instanceof SwingQuickEditPopup popup && chord.key().equals("ENTER") && !chord.ctrl() && !chord.alt() && !chord.shift()) { popup.submit(); event.consume(); return true; }
            if (chord.key().equals("ENTER") && focused instanceof JList<?>) { form.fields.values().stream().flatMap(java.util.List::stream).filter(b -> b.input == focused).findFirst().ifPresent(SwingFieldWidgets.Binding::activate); event.consume(); return true; }
            if (form.spec.modal()) return false;
        }
        if (port.alerts.values().stream().anyMatch(SwingAlerts::showing)) return false;
        FocusScope scope = FocusScope.MAIN; String id = null;
        if (form instanceof SwingQuickEditPopup) { scope = FocusScope.POPUP; id = form.session.windowId(); }
        else if (port.frame != null && focused == port.frame.toolbar.filter()) scope = FocusScope.FILTER;
        else if (focused instanceof JTextComponent) scope = FocusScope.TEXT_INPUT;
        else if (port.frame != null && focused == port.frame.table.table) scope = FocusScope.TABLE;
        else if (focused instanceof SwingSummaryPanel.Card card) { scope = FocusScope.CARD; id = card.data.id(); }
        boolean handled = port.intents().key(chord, scope, id);
        if (handled) event.consume(); return handled;
    }

    /** Возвращает имя физической клавиши без обращения к введённому символу. */
    public static String keyName(int code) {
        if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) return Character.toString((char) code);
        if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) return "DIGIT" + (code - KeyEvent.VK_0);
        if (code >= KeyEvent.VK_F1 && code <= KeyEvent.VK_F12) return "F" + (code - KeyEvent.VK_F1 + 1);
        return switch (code) { case KeyEvent.VK_ENTER -> "ENTER"; case KeyEvent.VK_ESCAPE -> "ESCAPE"; case KeyEvent.VK_DELETE -> "DELETE";
            case KeyEvent.VK_SPACE -> "SPACE"; case KeyEvent.VK_CONTEXT_MENU -> "CONTEXT_MENU"; case KeyEvent.VK_TAB -> "TAB";
            case KeyEvent.VK_UP -> "UP"; case KeyEvent.VK_DOWN -> "DOWN"; case KeyEvent.VK_LEFT -> "LEFT"; case KeyEvent.VK_RIGHT -> "RIGHT";
            case KeyEvent.VK_PAGE_UP -> "PAGE_UP"; case KeyEvent.VK_PAGE_DOWN -> "PAGE_DOWN"; case KeyEvent.VK_HOME -> "HOME"; case KeyEvent.VK_END -> "END"; default -> null; };
    }
    /** Переводит имя ядра в код KeyEvent для физического самотеста. */
    public static int keyCode(String name) {
        if (name.length() == 1) return name.charAt(0);
        if (name.startsWith("DIGIT")) return KeyEvent.VK_0 + Integer.parseInt(name.substring(5));
        if (name.matches("F[0-9]+")) return KeyEvent.VK_F1 + Integer.parseInt(name.substring(1)) - 1;
        return Map.ofEntries(Map.entry("ENTER", KeyEvent.VK_ENTER), Map.entry("ESCAPE", KeyEvent.VK_ESCAPE), Map.entry("DELETE", KeyEvent.VK_DELETE), Map.entry("SPACE", KeyEvent.VK_SPACE),
                Map.entry("CONTEXT_MENU", KeyEvent.VK_CONTEXT_MENU), Map.entry("TAB", KeyEvent.VK_TAB), Map.entry("UP", KeyEvent.VK_UP), Map.entry("DOWN", KeyEvent.VK_DOWN), Map.entry("LEFT", KeyEvent.VK_LEFT), Map.entry("RIGHT", KeyEvent.VK_RIGHT),
                Map.entry("PAGE_UP", KeyEvent.VK_PAGE_UP), Map.entry("PAGE_DOWN", KeyEvent.VK_PAGE_DOWN), Map.entry("HOME", KeyEvent.VK_HOME), Map.entry("END", KeyEvent.VK_END), Map.entry("ALT", KeyEvent.VK_ALT)).get(name);
    }
}
