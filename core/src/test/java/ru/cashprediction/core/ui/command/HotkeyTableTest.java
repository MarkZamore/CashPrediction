package ru.cashprediction.core.ui.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.ClientKind;

/** Проверяет отсутствие конфликтов сочетаний и различия Desktop/Web в общей таблице клавиш. */
class HotkeyTableTest {

    @Test
    void noChordHasTwoCommandsInsideTheSameFocusScope() {
        for (ClientKind client : ClientKind.values()) {
            for (FocusScope scope : FocusScope.values()) {
                Map<KeyChord, CommandId> commands = new HashMap<>();
                for (HotkeyBinding binding : HotkeyTable.bindings(client)) {
                    if (binding.scopes().contains(scope)) {
                        CommandId previous = commands.putIfAbsent(binding.chord(), binding.command());
                        assertTrue(previous == null || previous == binding.command(),
                                () -> client + " " + scope + " " + binding.chord());
                    }
                }
            }
        }
    }

    @Test
    void desktopAndWebAlternatesResolveToTheSameCommand() {
        assertEquals(CommandId.FILE_NEW, HotkeyTable.find(KeyChord.parse("Ctrl+N"), FocusScope.MAIN, ClientKind.FX)
                .orElseThrow().command());
        assertEquals(CommandId.FILE_NEW, HotkeyTable.find(KeyChord.parse("Alt+Shift+N"), FocusScope.MAIN, ClientKind.WEB)
                .orElseThrow().command());
        assertEquals(CommandId.VIEW_TABLE, HotkeyTable.find(KeyChord.parse("Alt+Shift+1"), FocusScope.MAIN, ClientKind.SWING)
                .orElseThrow().command());
        assertTrue(HotkeyTable.shownAccelerator(CommandId.FILE_EXIT, ClientKind.FX).isPresent());
        assertTrue(HotkeyTable.shownAccelerator(CommandId.FILE_EXIT, ClientKind.WEB).isEmpty());
        assertFalse(HotkeyTable.find(KeyChord.parse("Delete"), FocusScope.FILTER, ClientKind.FX).isPresent());
    }

    @Test
    void textContainsAllPublicRowsWithoutAForbiddenDash() {
        String text = HotkeyTable.text();
        assertTrue(text.contains("Ctrl+N"));
        assertTrue(text.contains("F10"));
        assertTrue(text.contains("Alt+Shift"));
        assertFalse(text.contains(Character.toString(0x2013)));
        assertFalse(text.contains(Character.toString(0x2014)));
        assertFalse(text.contains(Character.toString(0x2212)));
    }
}
