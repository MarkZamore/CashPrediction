package ru.cashprediction.fx.ui;

import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyEvent;
import ru.cashprediction.core.app.UiIntents;
import ru.cashprediction.core.ui.command.*;

/** Единый перехват клавиш перед встроенными ускорителями JavaFX. */
public final class FxKeyBridge {
    private FxKeyBridge() { }

    /** Подключает физические коды клавиш к диспетчеру ядра. */
    public static void install(Scene scene, UiIntents intents) {
        AltGesture alt = new AltGesture();
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            alt.pressed(event.getCode());
            if (event.getCode() == javafx.scene.input.KeyCode.ALT) { event.consume(); return; }
            var focus = scene.getFocusOwner();
            String id = focus == null ? "" : String.valueOf(focus.getProperties().getOrDefault("cp.id", ""));
            FocusScope scope = focus instanceof TextInputControl ? FocusScope.TEXT_INPUT : FocusScope.MAIN;
            if (id.equals("tb.filter")) scope = FocusScope.FILTER;
            for (var parent = focus; parent != null; parent = parent.getParent()) {
                if (parent.getProperties().containsKey("cp.focusId")) id = parent.getProperties().get("cp.focusId").toString();
                if (parent.getProperties().containsKey("cp.scope")) {
                    if (!(focus instanceof TextInputControl)) scope = (FocusScope) parent.getProperties().get("cp.scope");
                    break;
                }
            }
            if (intents.key(chord(event), scope, id)) event.consume();
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (alt.released(event.getCode())) {
                var focus = scene.getFocusOwner();
                FocusScope scope = focus instanceof TextInputControl ? FocusScope.TEXT_INPUT : FocusScope.MAIN;
                String id = focus == null ? "" : String.valueOf(focus.getProperties().getOrDefault("cp.id", ""));
                for (var parent = focus; parent != null; parent = parent.getParent()) {
                    if (parent.getProperties().containsKey("cp.focusId")) id = parent.getProperties().get("cp.focusId").toString();
                    if (!(focus instanceof TextInputControl) && parent.getProperties().containsKey("cp.scope")) { scope = (FocusScope) parent.getProperties().get("cp.scope"); break; }
                }
                if (id.equals("tb.filter")) scope = FocusScope.FILTER;
                if (intents.key(new KeyChord(false, false, false, "ALT"), scope, id)) event.consume();
            }
        });
    }

    /** Преобразует событие без зависимости от раскладки клавиатуры. */
    public static KeyChord chord(KeyEvent event) {
        return new KeyChord(event.isControlDown(), event.isShiftDown(), event.isAltDown(), event.getCode().name());
    }

    /** Отслеживает именно жест одиночного Alt, не вызывая действий при нажатии. */
    static final class AltGesture {
        private boolean armed;
        private boolean down;
        void pressed(javafx.scene.input.KeyCode code) {
            if (code == javafx.scene.input.KeyCode.ALT) { if (!down) armed = true; down = true; }
            else armed = false;
        }
        boolean released(javafx.scene.input.KeyCode code) {
            if (code != javafx.scene.input.KeyCode.ALT) return false;
            boolean result = armed; armed = false; down = false; return result;
        }
    }
}
