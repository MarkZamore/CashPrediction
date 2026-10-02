package ru.cashprediction.parity.check.hotkey;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.command.KeyChord;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.core.ui.text.UiText;
import static org.junit.jupiter.api.Assertions.*;

/** Ограниченные регрессии достоверности стенда; окна, браузер и Robot не запускаются. */
final class HotkeyFidelityTest {
    /** Исчезновение нулевой истории запрещено и для выполненной, и для отключённой команды. */
    @Test void rejectsDisappearingZeroCounters() {
        var before = Map.of("file.sample", 1, "edit.undo", 0);
        assertThrows(AssertionError.class, () -> HotkeyAssertions.exactlyOnce("file.save", before,
                Map.of("file.sample", 1, "file.save", 1)));
        assertThrows(AssertionError.class, () -> HotkeyAssertions.unchanged(before, Map.of("file.sample", 1)));
        assertThrows(AssertionError.class, () -> HotkeyAssertions.exactlyOnce("file.save",
                Map.of("file.sample", 1, "file.save", 0), Map.of("file.sample", 1)));
        HotkeyAssertions.exactlyOnce("file.save", before,
                Map.of("file.sample", 1, "edit.undo", 0, "file.save", 1));
        HotkeyAssertions.unchanged(before, before);
    }

    /** Правильная подсказка не скрывает открытие или закрытие быстрой правки отключённой командой. */
    @Test void disabledHintRejectsPopupChanges() {
        String hint = "status.hint.noOperation";
        // JavaFX: Popup → Swing: JWindow → Web: элемент всплывающего окна
        var popup = new UiDump.Popup("quickEdit", List.of("123"), new UiDump.Box(1, 2, 3, 4));
        var before = observation("", List.of(), List.of());
        var after = observation(UiText.get(hint), List.of(popup), List.of());
        assertThrows(AssertionError.class, () -> HotkeyAssertions.disabledHint(hint, before, after));
        assertThrows(AssertionError.class, () -> HotkeyAssertions.disabledHint(hint,
                observation("", List.of(popup), List.of()), observation(UiText.get(hint), List.of(), List.of())));
        HotkeyAssertions.disabledHint(hint, observation("", List.of(popup), List.of()), after);
    }

    /** Правильная подсказка не скрывает новый запрос выбора файла без приращения команды. */
    @Test void disabledHintRejectsChooserChanges() {
        String hint = "status.hint.noOperation";
        // JavaFX: FileChooser → Swing: JFileChooser → Web: запрос выбора файла
        var chooser = new UiDump.ChooserRequest("file", "SAVE", "unit", "", "", "unit.csv");
        assertThrows(AssertionError.class, () -> HotkeyAssertions.disabledHint(hint,
                observation("", List.of(), List.of()), observation(UiText.get(hint), List.of(), List.of(chooser))));
        assertThrows(AssertionError.class, () -> HotkeyAssertions.disabledHint(hint,
                observation("", List.of(), List.of(chooser)), observation(UiText.get(hint), List.of(), List.of())));
        HotkeyAssertions.disabledHint(hint, observation("", List.of(), List.of(chooser)),
                observation(UiText.get(hint), List.of(), List.of(chooser)));
    }

    /** Все восемь сочетаний модификаторов сохраняются при независимом физическом коде и русском символе. */
    @Test void russianEventEncodesEveryModifierCombination() {
        for (int mask = 0; mask < 8; mask++) {
            boolean ctrl = (mask & 1) != 0, shift = (mask & 2) != 0, alt = (mask & 4) != 0;
            var chord = new KeyChord(ctrl, shift, alt, "S");
            String expression = RussianKeyInput.expression(chord);
            assertTrue(expression.contains("code:'KeyS',key:'" + (shift ? "Ы" : "ы") + "'"));
            assertTrue(expression.contains("ctrlKey:" + ctrl + ",altKey:" + alt + ",shiftKey:" + shift));
            assertTrue(expression.contains("const target=document.activeElement; if (!target) throw new Error('No focus');"));
            assertEquals(shift ? "Ы" : "ы", RussianKeyInput.character(chord, true));
            assertEquals(shift ? "S" : "s", RussianKeyInput.character(chord, false));
        }
    }

    /** Каждая отключённая привязка имеет точную подсказку, подготовку и русскую буквенную пробу во всех клиентах. */
    @Test void disabledMatrixCoversEveryBindingAndLayout() {
        var hints = Map.of("edit.undo Ctrl+Z", "status.hint.nothingToUndo",
                "edit.redo Ctrl+Y", "status.hint.nothingToRedo",
                "edit.redo Ctrl+Shift+Z", "status.hint.nothingToRedo",
                "edit.adjust Ctrl+J", "status.hint.noRuleEvent",
                "edit.edit Enter", "status.hint.noOperation",
                "edit.delete Delete", "status.hint.noOperation");
        for (String client : List.of("fx", "swing", "web")) {
            var probes = HotkeyCases.forClient(client);
            for (var entry : hints.entrySet()) {
                String name = "disabled " + entry.getKey();
                var base = HotkeyCases.select(probes, name).getFirst();
                assertEquals(entry.getValue(), base.hint());
                assertEquals("select total@2026-10\n", base.setup());
                assertFalse(base.russian());
                if (base.chord().key().matches("[A-Z]")) {
                    var russian = HotkeyCases.select(probes, name + " ru").getFirst();
                    assertTrue(russian.russian());
                    assertEquals(base.command(), russian.command());
                    assertEquals(base.chord(), russian.chord());
                    assertEquals(base.hint(), russian.hint());
                    assertEquals(base.setup(), russian.setup());
                }
            }
        }
    }

    /** Подготовка фокуса завершается до измерения, а Enter фильтра не подменяется Enter таблицы. */
    @Test void focusPreparationPrecedesMeasuredKeyForEveryVariant() {
        for (String client : List.of("fx", "swing", "web")) {
            for (var probe : HotkeyCases.forClient(client)) {
                String expected;
                if (!probe.hint().isEmpty()) expected = "select total@2026-10\n";
                else expected = switch (probe.command()) {
                    case "filter.clear", "filter.focusTable" -> "filtertype probe\n";
                    case "edit.undo" -> "select r1@2026-10-05\nmenu edit.skip\nselect total@2026-10\n";
                    case "edit.redo" -> "select r1@2026-10-05\nmenu edit.skip\nmenu edit.undo\nselect r1@2026-10-05\n";
                    case "past.toggle" -> client.equals("web") ? "rowclick past@group date\n" : "select past@group\n";
                    default -> "select r1@2026-10-05\n";
                };
                assertEquals(expected, probe.setup(), client + ": " + probe.name());
                var lines = SelfTestScript.parse("hotkey", probe.script()).lines();
                assertEquals("key Esc", lines.getFirst().text());
                assertEquals("sample", lines.get(1).text());
                int measured = lines.size() - 2;
                assertEquals("dump before", lines.get(measured - 1).text());
                assertEquals(new SelfTestCommand.Key(probe.chord()), lines.get(measured).command());
                assertEquals("dump after", lines.getLast().text());
            }
        }
    }

    /** Создаёт только синтетические данные проверяемого утверждения, без клиентского порта. */
    private static UiDump observation(String message, List<UiDump.Popup> popups, List<UiDump.ChooserRequest> choosers) {
        return new UiDump(UiDump.SCHEMA, "swing", "assertion-unit", "unit", null, List.of(), null, null, null, null,
                List.of(new UiDump.Segment("message", message, "", "text.primary", true)), List.of(), List.of(),
                List.of(), popups, List.of(), choosers, Map.of(), Map.of("file.sample", 1));
    }
}
