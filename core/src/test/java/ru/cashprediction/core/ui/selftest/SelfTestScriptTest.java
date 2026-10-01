package ru.cashprediction.core.ui.selftest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Проверки языка сценариев, охватывающие каждый вариант замороженного контракта. */
class SelfTestScriptTest {
    @TempDir Path temporary;

    @Test void parsesEveryCommandType() {
        String text = """
                wait 0
                sample
                open RULE_EDITOR mode=create
                fill last title="Моя аренда" amount=45000,00
                ok last
                cancel w1
                view TABLE
                period ALL
                filter showIncome=true
                select r1@2026-10-05
                quickedit r1@2026-10-05 80000
                save
                snapshot
                menus tree.json
                signal ready
                crash
                throw
                exit
                today 2026-09-13
                size 1200 800
                menu "Файл/Сохранить"
                click tb.add.menu:edit.addExpense
                key Ctrl+Shift+S
                dblclick r1@2026-10-05 income
                context chart:300,200
                hover card:now
                field "Новый план" "Название" "Мой план"
                button "Новый план" "Далее ›"
                answer "Отмена"
                chooser cancel
                dump current
                shot current
                slider view.horizonSlider 24
                spinner whatIf.extra 5000
                filtertype ""
                rowclick past@group
                pick "Открыть план" "План" "Из файла…" activate
                enter "Выбор файла" "Путь"
                """;
        var script = SelfTestScript.parse("all", text);
        Set<Class<?>> actual = script.lines().stream().map(l -> l.command().getClass()).collect(Collectors.toSet());
        assertEquals(Set.copyOf(Arrays.asList(SelfTestCommand.class.getPermittedSubclasses())), actual);
        assertEquals(38, script.lines().size());
    }

    @Test void tokenizerPreservesQuotesEmptyFieldsCommentsAndWindowsPaths() {
        var script = SelfTestScript.parse("quoted", "\uFEFF# комментарий\n\nfill last title=\"A # B \\\"C\\\"\" note=\"\" # хвост\nchooser \"C:\\Temp\\a b.md\"\n");
        assertEquals(3, script.lines().getFirst().number());
        assertFalse(script.lines().getFirst().text().contains("хвост"));
        assertTrue(script.lines().getFirst().text().contains("# B"));
        var fill = (SelfTestCommand.Fill) script.lines().getFirst().command();
        assertEquals("A # B \"C\"", fill.values().get("title"));
        assertEquals("", fill.values().get("note"));
        var choose = (SelfTestCommand.Chooser) script.lines().getLast().command();
        assertEquals(Path.of("C:\\Temp\\a b.md"), choose.path());
    }

    @Test void dependentFieldUpdatesKeepScriptOrderAndAreImmutable() {
        var c = (SelfTestCommand.Fill) SelfTestScript.parse("ordered", "fill last fromEnabled=true from=2026-10-05").lines().getFirst().command();
        assertEquals(java.util.List.of("fromEnabled", "from"), c.values().keySet().stream().toList());
        assertThrows(UnsupportedOperationException.class, () -> c.values().put("other", "value"));
        var original = new LinkedHashMap<>(c.values()); var copy = new SelfTestCommand.Fill("last", original);
        original.clear(); assertEquals(2, copy.values().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "sample extra", "wait -1", "size 0 800", "filter showIncome=yes",
            "fill last no-pair", "fill last x=1 x=2", "open UNKNOWN", "period M5", "view OTHER",
            "today 2026-02-30", "field x y", "pick a b c wrong", "dblclick a b c", "filtertype \"unfinished"})
    void rejectsMalformedCommandsWithPhysicalLine(String line) {
        var e = assertThrows(IllegalArgumentException.class, () -> SelfTestScript.parse("broken", "# header\n\n" + line));
        assertTrue(e.getMessage().contains("3")); assertNotNull(e.getCause());
    }

    @Test void loadsUtf8FileAndLegacyDumpFileName() throws Exception {
        Path path = temporary.resolve("external.cps");
        Files.writeString(path, "dump old/folder/result.json\nfiltertype \"Зарплата\"");
        var s = SelfTestScript.load(path.toString());
        assertEquals("external", s.name()); assertEquals(new SelfTestCommand.Dump("result"), s.lines().getFirst().command());
    }

    @Test void missingFileHasLocalizedLoadError() {
        assertThrows(IllegalArgumentException.class, () -> SelfTestScript.load(temporary.resolve("missing.cps").toString()));
    }

    @Test void allEighteenResourcesParseAndContainActualCheckpoints() {
        assertEquals(18, SelfTestScript.SCENARIOS.size());
        for (String name : SelfTestScript.SCENARIOS) {
            assertNotNull(SelfTestScript.class.getResource(SelfTestScript.RESOURCE_DIR + name + ".cps"),
                    "Scenario must ship in core main resources: " + name);
            var script = SelfTestScript.load(name);
            assertTrue(script.lines().stream().anyMatch(l -> l.command() instanceof SelfTestCommand.Dump), name);
            assertEquals(new SelfTestCommand.Today(java.time.LocalDate.of(2026, 9, 13)), script.lines().getFirst().command(), name);
            var dumps = script.lines().stream().filter(l -> l.command() instanceof SelfTestCommand.Dump)
                    .map(l -> ((SelfTestCommand.Dump) l.command()).step()).toList();
            assertEquals(dumps.size(), Set.copyOf(dumps).size(), name);
            assertFalse(script.lines().stream().anyMatch(l -> l.command() instanceof SelfTestCommand.Open
                    || l.command() instanceof SelfTestCommand.Snapshot || l.command() instanceof SelfTestCommand.Crash), name);
        }
    }
}
