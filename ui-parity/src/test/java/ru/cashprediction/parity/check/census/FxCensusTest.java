package ru.cashprediction.parity.check.census;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.parity.check.ClassUsageTest;
import static org.junit.jupiter.api.Assertions.*;

/** Регрессии переписи на явно тестовых данных, которые не используются как доказательство реального FX. */
class FxCensusTest {
    @TempDir Path directory;

    /** Проверяет каждый из 23 обязательных классов при отсутствии, нуле и отрицательном числе. */
    @TestFactory Stream<DynamicTest> everyRequiredClassNeedsPositiveEvidence() {
        return FxCensus.REQUIRED.stream().flatMap(name -> Stream.of("missing", "zero", "negative").map(mutation ->
                DynamicTest.dynamicTest(name + " " + mutation, () -> {
                    Map<String, Integer> counts = complete();
                    if (mutation.equals("missing")) counts.remove(name);
                    else counts.put(name, mutation.equals("zero") ? 0 : -1);
                    var census = census();
                    if (mutation.equals("negative")) {
                        var error = assertThrows(IllegalArgumentException.class,
                                () -> census.addScenario("s-test", Map.of("first", dump("fx", "s-test", "first", counts))));
                        assertTrue(error.getMessage().contains(name));
                        assertTrue(error.getMessage().contains("s-test/first"));
                    } else {
                        census.addScenario("s-test", Map.of("first", dump("fx", "s-test", "first", counts)));
                        var error = assertThrows(AssertionError.class, () -> census.requireComplete("test-report"));
                        assertTrue(error.getMessage().contains(name + ": NOT INSTANTIATED; s-test="));
                    }
                })));
    }

    /** Разрешает только простые имена настоящих классов и их точные полные имена. */
    @Test void canonicalAndSimpleNamesAreSupported() {
        assertEquals(23, FxCensus.REQUIRED.size());
        assertTrue(FxCensus.REQUIRED.contains("javafx.scene.control.MenuItem"));
        var census = census();
        Map<String, Integer> counts = new LinkedHashMap<>();
        complete().forEach((name, count) -> counts.put(name.substring(name.lastIndexOf('.') + 1), count));
        counts.put("Node", 4);
        census.addScenario("simple", Map.of("first", dump("fx", "simple", "first", counts)));
        census.requireComplete("unused");
        assertTrue(census.report().startsWith("FX instantiated census: 23/23"));
        var full = census();
        full.addScenario("full", Map.of("first", dump("fx", "full", "first", complete())));
        full.requireComplete("unused");
    }

    /** Не принимает выдуманные имена, псевдонимы Swing и текстовые параметры обобщённых типов. */
    @Test void injectedUnsupportedClassesAreRejectedAtomically() {
        for (String name : List.of("FakeMenu", "synthetic.Menu", "CheckBoxMenuItem", "RadioButtonMenuItem",
                "SplitButton", "Dialog<R>", "ChoiceDialog<T>", "javafx.scene.control.NotAClass")) {
            var census = census();
            Map<String, Integer> counts = complete(); counts.put(name, 1);
            var error = assertThrows(IllegalArgumentException.class,
                    () -> census.addScenario("injected", Map.of("first", dump("fx", "injected", "first", counts))));
            assertTrue(error.getMessage().contains(name));
            assertTrue(census.report().startsWith("FX instantiated census: 0/23"));
        }
    }

    /** Отклоняет двойное представление одного класса в одном снимке. */
    @Test void duplicateAliasesAreRejected() {
        var counts = complete(); counts.put("Menu", 1);
        assertThrows(IllegalArgumentException.class,
                () -> census().addScenario("s-test", Map.of("first", dump("fx", "s-test", "first", counts))));
    }

    /** Объединяет независимые сценарии и сохраняет конкретный шаг положительного счётчика. */
    @Test void scenariosAggregateWithoutRequiringEveryClassInEveryScenario() {
        var census = census();
        int index = 0;
        for (String name : FxCensus.REQUIRED) {
            String scenario = "s" + index++;
            census.addScenario(scenario, Map.of("evidence", dump("fx", scenario, "evidence", Map.of(name, 1))));
            assertTrue(census.report().contains(name + ": " + scenario + "/evidence count=1"));
        }
        census.requireComplete("unused");
    }

    /** Не суммирует накопительные снимки и не позволяет нулю стереть ранее созданные экземпляры. */
    @Test void cumulativeSnapshotsUsePositiveEvidenceWithoutOverflow() {
        var census = census();
        var first = complete(); first.put("javafx.scene.control.Menu", Integer.MAX_VALUE);
        var second = complete(); second.put("javafx.scene.control.Menu", 0);
        census.addScenario("s-test", Map.of("first", dump("fx", "s-test", "first", first),
                "second", dump("fx", "s-test", "second", second)));
        census.requireComplete("unused");
        assertTrue(census.report().contains("s-test/first count=2147483647"));
    }

    /** Не принимает эталон модели, другой клиент, чужой сценарий, шаг и неизвестную версию. */
    @Test void wrongDumpIdentityCannotSupplyEvidence() {
        for (UiDump value : List.of(dump("model", "s-test", "first", complete()),
                dump("swing", "s-test", "first", complete()), dump("web", "s-test", "first", complete()),
                dump("fx", "old-legacy", "first", complete()), dump("fx", "s-test", "another", complete())))
            assertThrows(IllegalArgumentException.class, () -> census().addScenario("s-test", Map.of("first", value)));
        UiDump value = dump("fx", "s-test", "first", complete());
        var wrongSchema = new UiDump(2, value.client(), value.scenario(), value.step(), null, null, null, null,
                null, null, null, null, null, null, null, null, null, value.classCensus(), null);
        assertThrows(IllegalArgumentException.class, () -> census().addScenario("s-test", Map.of("first", wrongSchema)));
    }

    /** Сбой любого запуска остаётся ошибкой даже после получения всех 23 классов. */
    @Test void collectorFailuresCannotBeHiddenByCompleteCensus() {
        var census = census();
        census.addScenario("valid", Map.of("first", dump("fx", "valid", "first", complete())));
        census.failedScenario("s04-context-menus", "step rule-context failed");
        var error = assertThrows(AssertionError.class, () -> census.requireComplete("report.txt"));
        assertTrue(error.getMessage().contains("FAILED scenario s04-context-menus: step rule-context failed"));
        assertTrue(error.getMessage().contains("Report: report.txt"));
    }

    /** Полные 23 класса в одном сценарии не заменяют исполнение остальных сценариев матрицы. */
    @Test void completeClassCountsCannotHidePartialScenarioSuite() {
        var census = census();
        census.addScenario("first", Map.of("step", dump("fx", "first", "step", complete())));
        var error = assertThrows(AssertionError.class,
                () -> census.requireComplete(Set.of("first", "second"), "report.txt"));
        assertTrue(error.getMessage().contains("missing=[second]"));
        census.addScenario("second", Map.of("step", dump("fx", "second", "step", complete())));
        census.requireComplete(Set.of("first", "second"), "report.txt");
        assertThrows(AssertionError.class, () -> census.requireComplete(Set.of("first"), "report.txt"));
    }

    /** Пустые результаты и повторный сценарий не засчитываются как новый запуск. */
    @Test void emptyAndDuplicateScenariosAreRejected() {
        var census = census();
        assertThrows(IllegalArgumentException.class, () -> census.addScenario("empty", Map.of()));
        assertThrows(AssertionError.class, () -> census.requireComplete("unused"));
        census.addScenario("valid", Map.of("first", dump("fx", "valid", "first", complete())));
        assertThrows(IllegalArgumentException.class,
                () -> census.addScenario("valid", Map.of("first", dump("fx", "valid", "first", complete()))));
    }

    /** Читает исходный JSON UiDump, требуя ровно набор команд dump, а не случайные старые файлы. */
    @Test void readerRequiresExactScriptStepsAndIdentity() throws Exception {
        var script = SelfTestScript.parse("s-test", "dump first\ndump second\n");
        Files.writeString(directory.resolve("first.json"), UiJson.write(dump("fx", "s-test", "first", complete())));
        assertThrows(IllegalArgumentException.class, () -> FxCensus.readScenario(directory, script));
        Files.writeString(directory.resolve("second.json"), UiJson.write(dump("fx", "s-test", "second", complete())));
        assertEquals(Set.of("first", "second"), FxCensus.readScenario(directory, script).keySet());
        Files.writeString(directory.resolve("legacy.json"), UiJson.write(dump("fx", "s-test", "legacy", complete())));
        assertThrows(IllegalArgumentException.class, () -> FxCensus.readScenario(directory, script));
    }

    /** Отклоняет отсутствующую карту, строки, null, дроби, переполнение и отрицательные числа в JSON. */
    @Test void unsupportedJsonCountsNeverCoerceIntoInstances() throws Exception {
        var script = SelfTestScript.parse("s-test", "dump first\n");
        String valid = UiJson.write(dump("fx", "s-test", "first", Map.of("Menu", 1)));
        for (String count : List.of("\"1\"", "null", "true", "[]", "{}", "1.5", "2147483648", "-1")) {
            Files.writeString(directory.resolve("first.json"), valid.replace("\"Menu\":1", "\"Menu\":" + count));
            assertThrows(RuntimeException.class, () -> FxCensus.readScenario(directory, script), count);
        }
        Files.writeString(directory.resolve("first.json"), valid.replace("\"classCensus\":{\"Menu\":1}", "\"classCensus\":null"));
        assertThrows(IllegalArgumentException.class, () -> FxCensus.readScenario(directory, script));
        Files.writeString(directory.resolve("first.json"), valid.replace("\"classCensus\":{\"Menu\":1},", ""));
        assertThrows(IllegalArgumentException.class, () -> FxCensus.readScenario(directory, script));
    }

    /** Не превращает ошибку записи дробного счётчика в сообщение без класса и шага. */
    @Test void malformedCountDiagnosticIdentifiesClassAndScenario() throws Exception {
        String json = UiJson.write(dump("fx", "s-test", "first", Map.of("Menu", 1)))
                .replace("\"Menu\":1", "\"Menu\":1.5");
        Files.writeString(directory.resolve("first.json"), json);
        var error = assertThrows(IllegalArgumentException.class,
                () -> FxCensus.readScenario(directory, SelfTestScript.parse("s-test", "dump first\n")));
        assertTrue(error.getMessage().contains("s-test/first Menu=1.5"));
    }

    /** Поддерживает прежний публичный контракт, но отклоняет отрицательные дополнительные счётчики. */
    @Test void legacyEntryPointAlsoRejectsNegativeInjection() {
        assertThrows(AssertionError.class, () -> ClassUsageTest.census(Set.of("Menu"), Map.of("Menu", 1, "Injected", -1)));
        assertThrows(IllegalArgumentException.class, () -> ClassUsageTest.census(Set.of(), Map.of()));
    }

    /** Создаёт проверяющий объект с явным тестовым каталогом классов, не читая старые дампы. */
    private static FxCensus census() {
        Set<String> names = new LinkedHashSet<>(FxCensus.REQUIRED); names.add("javafx.scene.Node");
        return new FxCensus(names);
    }

    /** Создаёт положительные счётчики исключительно для регрессионных тестов валидатора. */
    private static Map<String, Integer> complete() {
        Map<String, Integer> counts = new LinkedHashMap<>(); FxCensus.REQUIRED.forEach(name -> counts.put(name, 1));
        return counts;
    }

    /** Создаёт минимальную фикстуру схемы, которая не запускается вместо настоящего клиента. */
    private static UiDump dump(String client, String scenario, String step, Map<String, Integer> counts) {
        return new UiDump(UiDump.SCHEMA, client, scenario, step, null, null, null, null, null, null,
                null, null, null, null, null, null, null, counts, null);
    }
}
