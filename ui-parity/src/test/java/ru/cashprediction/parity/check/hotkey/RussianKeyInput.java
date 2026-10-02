package ru.cashprediction.parity.check.hotkey;

import java.util.Locale;
import ru.cashprediction.core.ui.command.KeyChord;

/** Строит событие русской раскладки с независимыми физическим code и печатным key. */
public final class RussianKeyInput {
    private static final String LETTERS = "фисвуапршолдьтщзйкыегмцчня";
    private RussianKeyInput() { }

    /** Возвращает символ выбранной раскладки отдельно от физического кода клавиши. */
    public static String character(KeyChord chord, boolean russian) {
        if (!chord.key().matches("[A-Z]")) return "";
        String letter = russian ? String.valueOf(LETTERS.charAt(chord.key().charAt(0) - 'A'))
                : chord.key().toLowerCase(Locale.ROOT);
        return chord.shift() ? letter.toUpperCase(Locale.ROOT) : letter;
    }

    /** Возвращает ограниченное выражение для реальной вкладки; допускаются только буквенные физические коды. */
    public static String expression(KeyChord chord) {
        String physical = chord.key();
        if (!physical.matches("[A-Z]")) throw new IllegalArgumentException("Russian letter requires physical A-Z");
        String letter = String.valueOf(LETTERS.charAt(physical.charAt(0) - 'A'));
        String value = chord.shift() ? letter.toUpperCase(Locale.ROOT) : letter;
        return "(() => {const target=document.activeElement; if (!target) throw new Error('No focus');"
                + "target.dispatchEvent(new KeyboardEvent('keydown',{code:'Key" + physical + "',key:'" + value
                + "',ctrlKey:" + chord.ctrl() + ",altKey:" + chord.alt() + ",shiftKey:" + chord.shift()
                + ",bubbles:true,cancelable:true}));return true;})()";
    }
}
