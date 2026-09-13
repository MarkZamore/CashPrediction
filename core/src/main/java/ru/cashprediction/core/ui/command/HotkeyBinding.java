package ru.cashprediction.core.ui.command;

import java.util.Objects;
import java.util.Set;

/**
 * Привязка сочетания клавиш к команде (одна строка таблицы спецификации v2, §7).
 *
 * @param command команда
 * @param chord   сочетание по физической клавише
 * @param scopes  области фокуса, в которых сочетание действует
 * @param shown   показывается ли это сочетание ускорителем пункта меню на данной платформе
 *                (колонка «Desktop» для FX/Swing, «Web» для браузера); остальные сочетания работают, но не видны
 */
public record HotkeyBinding(CommandId command, KeyChord chord, Set<FocusScope> scopes, boolean shown) {

    /** Проверяет поля и копирует множество областей. */
    public HotkeyBinding {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(chord, "chord");
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
    }
}
