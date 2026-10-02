package ru.cashprediction.parity.check.hotkey;

import java.util.*;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.ui.command.HotkeyTable;
import ru.cashprediction.core.ui.command.KeyChord;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.core.ui.text.UiText;
import static org.junit.jupiter.api.Assertions.*;

/** Ограниченные мутации наблюдений проверяют сам проверяющий код без запуска или имитации клиентов. */
final class HotkeyRegressionTest {
    /** Физический код сохраняется, русский символ и модификаторы проверяются независимо от клиентского диспетчера. */
    @Test void russianEventRetainsPhysicalCodeAndModifiers() {
        for (String[] pair : List.of(new String[]{"S", "ы"}, new String[]{"N", "т"},
                new String[]{"O", "щ"}, new String[]{"T", "е"}, new String[]{"C", "с"}, new String[]{"Q", "й"})) {
            String ordinary = RussianKeyInput.expression(KeyChord.parse("Ctrl+" + pair[0]));
            assertTrue(ordinary.contains("code:'Key" + pair[0] + "',key:'" + pair[1] + "'"));
            assertTrue(ordinary.contains("ctrlKey:true,altKey:false,shiftKey:false"));
            String alternate = RussianKeyInput.expression(KeyChord.parse("Alt+Shift+" + pair[0]));
            assertTrue(alternate.contains("key:'" + pair[1].toUpperCase(Locale.ROOT) + "'"));
            assertTrue(alternate.contains("ctrlKey:false,altKey:true,shiftKey:true"));
            assertTrue(alternate.contains("document.activeElement"));
            assertTrue(alternate.contains("bubbles:true,cancelable:true"));
        }
        assertThrows(IllegalArgumentException.class, () -> RussianKeyInput.expression(KeyChord.parse("F1")));
        // Текущий замороженный язык не умеет передавать отдельно символ раскладки настольному драйверу.
        assertThrows(IllegalArgumentException.class, () -> SelfTestScript.parse("hotkey", "key Ctrl+S key=ы"));
    }
    /** Проверяет успешное приращение при ненулевой истории и отсутствие изменения чужого счётчика. */
    @Test void acceptsOneIncrementWithHistory() {
        HotkeyAssertions.exactlyOnce("file.save", Map.of("file.save", 7, "edit.undo", 3),
                Map.of("file.save", 8, "edit.undo", 3));
        HotkeyAssertions.exactlyOnce("file.save", Map.of(), Map.of("file.save", 1));
    }

    /** Подмена типа счётчика и происхождения JSON не засчитывается как выполнение настоящего клиента. */
    @Test void rejectsMalformedRawCountersAndForeignDumpIdentity() {
        var fixture = observation("message", "", true, Map.of("file.sample", 1));
        String valid = UiJson.write(new UiDump(UiDump.SCHEMA, "swing", "hotkey", "before", fixture.frame(),
                fixture.menuBar(), fixture.toolbar(), fixture.summary(), fixture.table(), fixture.chart(),
                fixture.status(), fixture.contextMenus(), fixture.windows(), fixture.alerts(), fixture.popups(),
                fixture.screens(), fixture.chooserRequests(), fixture.classCensus(), fixture.counters()));
        assertEquals(Map.of("file.sample", 1), HotkeyAssertions.observation(valid, "swing", "before").counters());
        for (String value : List.of("\"1\"", "null", "true", "[]", "{}", "1.5", "-1", "2147483648"))
            assertThrows(AssertionError.class, () -> HotkeyAssertions.observation(
                    valid.replace("\"file.sample\":1", "\"file.sample\":" + value), "swing", "before"));
        for (String mutation : List.of(valid.replace("\"counters\":{\"file.sample\":1}", "\"counters\":null"),
                valid.replace("\"swing\"", "\"model\""), valid.replace("\"hotkey\"", "\"foreign\""),
                valid.replace("\"before\"", "\"after\"")))
            assertThrows(AssertionError.class, () -> HotkeyAssertions.observation(mutation, "swing", "before"));
    }

    /** Диагностический выбор требует существующего точного имени и не скрывает ошибку пустой матрицей. */
    @Test void rejectsUnknownOrEmptyDiagnosticSelection() {
        var probes = HotkeyCases.forClient("web");
        assertEquals(probes, HotkeyCases.select(probes, ""));
        assertEquals(1, HotkeyCases.select(probes, "file.save Ctrl+S ru").size());
        for (String invalid : List.of("missing", "file.save Ctrl+S,", "file.save Ctrl+S,file.save Ctrl+S"))
            assertThrows(IllegalArgumentException.class, () -> HotkeyCases.select(probes, invalid));
    }

    /** Проверяет ограниченный набор поломок: двойное, неверное, отсутствующее выполнение и сброс истории. */
    @Test void rejectsBoundedCounterMutations() {
        Map<String, Integer> before = Map.of("file.save", 7, "edit.undo", 3);
        List<Map<String, Integer>> mutations = List.of(
                Map.of("file.save", 9, "edit.undo", 3),
                Map.of("file.save", 7, "edit.undo", 4),
                before,
                Map.of("file.save", 8, "edit.undo", 4),
                Map.of("file.save", 8, "edit.undo", 3, "help.about", 1),
                Map.of("file.save", 8),
                Map.of("file.save", -1, "edit.undo", 3),
                Map.of("file.save", Integer.MAX_VALUE, "edit.undo", 3));
        for (var mutation : mutations) assertThrows(AssertionError.class,
                () -> HotkeyAssertions.exactlyOnce("file.save", before, mutation), mutation.toString());
        assertThrows(AssertionError.class, () -> HotkeyAssertions.exactlyOnce("file.save", Map.of(), Map.of()));
    }

    /** Проверяет нулевое приращение отключённой команды и новую видимую подсказку именно в строке состояния. */
    @Test void disabledHintRequiresNewVisibleMessageAndNoDispatch() {
        String hint = "status.hint.nothingToUndo", text = UiText.get(hint);
        UiDump before = observation("sample", "", true, Map.of("file.sample", 1));
        UiDump after = observation("message", text, true, before.counters());
        HotkeyAssertions.disabledHint(hint, before, after);
        for (UiDump mutation : List.of(
                observation("message", text, false, before.counters()),
                observation("other", text, true, before.counters()),
                observation("message", "wrong", true, before.counters()),
                observation("message", text, true, Map.of("file.sample", 1, "edit.undo", 1))))
            assertThrows(AssertionError.class, () -> HotkeyAssertions.disabledHint(hint, before, mutation));
        assertThrows(AssertionError.class, () -> HotkeyAssertions.disabledHint(hint, after, after));
    }

    /** Проверяет полную матрицу таблицы и принимаемый синтаксис внешнего сценария для каждого опыта. */
    @Test void coversAllRelevantBindingsAndRussianWebLetters() {
        for (String client : List.of("fx", "swing", "web")) {
            ClientKind kind = ClientKind.valueOf(client.toUpperCase(Locale.ROOT));
            var probes = HotkeyCases.forClient(client);
            assertEquals(probes.size(), probes.stream().map(HotkeyCases.Probe::name).distinct().count());
            for (var binding : HotkeyTable.bindings(kind)) {
                boolean reserved = client.equals("web") && List.of("Ctrl+N", "Ctrl+O", "Ctrl+T")
                        .contains(binding.chord().display());
                assertEquals(reserved ? 0L : 1L, probes.stream().filter(p -> p.hint().isEmpty() && !p.russian()
                        && !p.name().endsWith(" en") && p.command().equals(binding.command().id()) && p.chord().equals(binding.chord())).count(),
                        client + " " + binding);
                if (client.equals("web") && !reserved && binding.chord().key().matches("[A-Z]"))
                    assertTrue(probes.stream().anyMatch(p -> p.russian() && p.chord().equals(binding.chord())));
                if (!client.equals("web") && binding.chord().key().matches("[A-Z]"))
                    for (String suffix : List.of(" en", " ru")) assertEquals(1L, probes.stream()
                            .filter(p -> p.hint().isEmpty() && p.command().equals(binding.command().id())
                                    && p.chord().equals(binding.chord()) && p.name().endsWith(suffix)).count());
            }
            for (var probe : probes) {
                var lines = SelfTestScript.parse("hotkey", probe.script()).lines();
                assertEquals("dump before", lines.get(lines.size() - 3).text());
                assertEquals("key " + probe.chord().display(), lines.get(lines.size() - 2).text());
                assertEquals("dump after", lines.getLast().text());
            }
            assertTrue(probes.stream().anyMatch(p -> p.name().startsWith("disabled edit.undo")));
            assertTrue(probes.stream().anyMatch(p -> p.name().startsWith("disabled edit.redo")));
            assertTrue(probes.stream().anyMatch(p -> p.chord().display().equals("Alt+Shift+N")));
            assertTrue(probes.stream().filter(p -> p.command().equals("edit.undo") && p.hint().isEmpty())
                    .allMatch(p -> p.setup().endsWith("select total@2026-10\n")),
                    "Skipped row becomes hidden; undo must focus another visible row");
        }
    }

    /** Подготовка web использует реально существующую объединённую ячейку заголовка прошедших. */
    @Test void webPastPreparationUsesVisibleSpannedCell() {
        var probe = HotkeyCases.select(HotkeyCases.forClient("web"), "past.toggle Space").getFirst();
        assertEquals("rowclick past@group date\n", probe.setup());
        assertEquals("ы", RussianKeyInput.character(KeyChord.parse("Ctrl+S"), true));
        assertEquals("s", RussianKeyInput.character(KeyChord.parse("Ctrl+S"), false));
        assertEquals("Ы", RussianKeyInput.character(KeyChord.parse("Ctrl+Shift+S"), true));
        assertEquals("", RussianKeyInput.character(KeyChord.parse("Alt"), true));
    }

    /** Ограниченный прогон не содержит уже зелёные обычные сочетания и не исключает остаточные запреты. */
    @Test void residualSelectionPreservesExistingEvidence() {
        assertEquals(List.of("past.toggle Space"), HotkeyCases.residual(HotkeyCases.forClient("web"), "web")
                .stream().map(HotkeyCases.Probe::name).toList());
        for (String client : List.of("fx", "swing")) {
            var probes = HotkeyCases.residual(HotkeyCases.forClient(client), client);
            assertTrue(probes.stream().anyMatch(p -> p.name().equals("ui.menuBar Alt")));
            assertFalse(probes.stream().anyMatch(p -> p.name().equals("file.save Ctrl+S")));
            assertTrue(probes.stream().anyMatch(p -> p.name().equals("disabled edit.undo Ctrl+Z ru")));
            assertTrue(probes.stream().anyMatch(p -> p.name().equals("disabled edit.undo Ctrl+Z en")));
        }
    }

    /** Даже DONE и правильный дамп не скрывают пропуск, перестановку, дубликат или FAIL шага. */
    @Test void rejectsIncompleteAndFailedWidgetLogs() {
        var script = SelfTestScript.parse("hotkey", "sample\ndump before\nkey Ctrl+S\ndump after\n");
        String log = "SELFTEST 1 OK sample\nSELFTEST 2 OK dump before\nSELFTEST 3 OK key Ctrl+S\n"
                + "SELFTEST 4 OK dump after\nSELFTEST DONE\n";
        HotkeyRun.requireLog(script, log);
        for (String mutation : List.of(log.replace("SELFTEST 3 OK key Ctrl+S\n", ""),
                log.replace("3 OK", "3 FAIL"), log.replace("3 OK", "2 OK"),
                log.replace("SELFTEST DONE", "SELFTEST DONE\nSELFTEST DONE"),
                log.replace("key Ctrl+S", "key Ctrl+O")))
            assertThrows(AssertionError.class, () -> HotkeyRun.requireLog(script, mutation));
    }

    /** Строит только данные для проверки утверждений; эти наблюдения никогда не передаются реальному тесту. */
    private static UiDump observation(String id, String text, boolean visible, Map<String, Integer> counters) {
        return new UiDump(1, "swing", "assertion-unit", "unit", null, List.of(), null, null, null, null,
                List.of(new UiDump.Segment(id, text, "", "text.primary", visible)), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), Map.of(), counters);
    }
}
