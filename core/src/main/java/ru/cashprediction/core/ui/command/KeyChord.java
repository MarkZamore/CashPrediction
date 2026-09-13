package ru.cashprediction.core.ui.command;

import java.util.Locale;
import java.util.Objects;

/**
 * Сочетание клавиш по физической клавише (архитектура §3.2, спецификация v2, §7): работает в русской раскладке,
 * потому что клиент передаёт код клавиши ({@code KeyCode} FX, {@code KeyEvent.VK_*} Swing, {@code event.code} web),
 * а не введённый символ.
 *
 * <p>Имена клавиш: буквы {@code A}–{@code Z}; цифры {@code DIGIT0}–{@code DIGIT9}; {@code F1}–{@code F12};
 * {@code ENTER}, {@code DELETE}, {@code ESCAPE}, {@code SPACE}, {@code TAB}, {@code UP}, {@code DOWN},
 * {@code LEFT}, {@code RIGHT}, {@code PAGE_UP}, {@code PAGE_DOWN}, {@code HOME}, {@code END}, {@code CONTEXT_MENU},
 * {@code ALT}. Клавиша {@code ALT} без модификаторов — отдельное нажатие и отпускание Alt («F10 / Alt» — строка
 * меню, §7): клиент передаёт её при отпускании Alt, если между нажатием и отпусканием не было другой клавиши.</p>
 *
 * @param ctrl  нажат Ctrl
 * @param shift нажат Shift
 * @param alt   нажат Alt
 * @param key   имя физической клавиши в верхнем регистре
 */
public record KeyChord(boolean ctrl, boolean shift, boolean alt, String key) {

    /** Проверяет и нормализует имя клавиши. */
    public KeyChord {
        key = Objects.requireNonNull(key, "key").strip().toUpperCase(Locale.ROOT);
        if (key.isEmpty()) {
            // Ошибка программиста (пустая запись сочетания в таблице), поэтому сообщение латиницей, не из каталога.
            throw new IllegalArgumentException("Key chord has no key");
        }
    }

    /**
     * Разбирает запись спецификации: {@code Ctrl+Shift+S}, {@code Alt+Shift+1}, {@code F2}, {@code Enter},
     * {@code Delete}, {@code Esc}, {@code Shift+F10}, {@code Menu}, {@code PgUp}, {@code Alt} (одна клавиша Alt).
     *
     * @param text запись сочетания
     * @return сочетание
     * @throws IllegalArgumentException если запись пуста
     */
    public static KeyChord parse(String text) {
        String[] parts = Objects.requireNonNull(text, "text").strip().split("\\+");
        boolean ctrl = false;
        boolean shift = false;
        boolean alt = false;
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i].strip().toLowerCase(Locale.ROOT)) {
                case "ctrl" -> ctrl = true;
                case "shift" -> shift = true;
                case "alt" -> alt = true;
                default -> throw new IllegalArgumentException("Unknown modifier '" + parts[i] + "' in '" + text + "'");
            }
        }
        String key = parts[parts.length - 1].strip();
        String normalized = switch (key.toLowerCase(Locale.ROOT)) {
            case "enter" -> "ENTER";
            case "delete", "del" -> "DELETE";
            case "esc", "escape" -> "ESCAPE";
            case "space" -> "SPACE";
            case "menu" -> "CONTEXT_MENU";
            case "pgup", "pageup" -> "PAGE_UP";
            case "pgdn", "pagedown" -> "PAGE_DOWN";
            default -> key.length() == 1 && Character.isDigit(key.charAt(0)) ? "DIGIT" + key : key;
        };
        return new KeyChord(ctrl, shift, alt, normalized);
    }

    /**
     * Запись для показа в меню и подсказках: {@code Ctrl+Shift+S}, {@code Alt+Shift+1}, {@code Enter}, {@code Esc}.
     *
     * @return текст сочетания
     */
    public String display() {
        StringBuilder sb = new StringBuilder();
        if (ctrl) {
            sb.append("Ctrl+");
        }
        if (alt) {
            sb.append("Alt+");
        }
        if (shift) {
            sb.append("Shift+");
        }
        sb.append(switch (key) {
            case "ENTER" -> "Enter";
            case "DELETE" -> "Delete";
            case "ESCAPE" -> "Esc";
            case "SPACE" -> "Space";
            case "CONTEXT_MENU" -> "Menu";
            case "PAGE_UP" -> "PgUp";
            case "PAGE_DOWN" -> "PgDn";
            case "HOME" -> "Home";
            case "END" -> "End";
            case "UP" -> "Up";
            case "DOWN" -> "Down";
            case "LEFT" -> "Left";
            case "RIGHT" -> "Right";
            case "TAB" -> "Tab";
            case "ALT" -> "Alt";
            default -> key.startsWith("DIGIT") && key.length() == 6 ? key.substring(5) : key;
        });
        return sb.toString();
    }

    /** @return то же, что {@link #display()} */
    @Override
    public String toString() {
        return display();
    }
}
