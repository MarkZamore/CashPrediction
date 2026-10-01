package ru.cashprediction.parity.pipeline;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ru.cashprediction.core.ui.dump.*;
import ru.cashprediction.core.ui.json.UiJson;

/** Проверки выбора матрицы, строгого чтения и отчётов об отсутствующих результатах. */
class ParityPipelineTest {
    Path temp = Path.of(System.getProperty("parity.reactor.root", ".")).toAbsolutePath()
            .resolve("ui-parity/target/parity/unit-" + UUID.randomUUID());
    /** Фильтры не позволяют прогнать пустую или ошибочно названную матрицу. */
    @Test void selectionIsStrict() {
        assertEquals(List.of("fx", "web"), ParityPipeline.clients("fx,web"));
        assertThrows(IllegalArgumentException.class, () -> ParityPipeline.clients("fx,fx"));
        assertThrows(IllegalArgumentException.class, () -> ParityPipeline.clients("fx,unknown"));
        assertEquals(List.of("s02-sample", "s05-forms"), ParityPipeline.scenarios(
                List.of("s01-first", "s02-sample", "s05-forms"), "s02,s05"));
        assertThrows(IllegalArgumentException.class, () -> ParityPipeline.scenarios(List.of("s01"), "s99"));
    }
    /** Полная схема выдерживает чтение, отсутствующее поле отклоняется. */
    @Test void dumpReaderIsStrict() {
        var dump = ParityPipelineIT.fixture("fx", "s02", "sample");
        assertEquals(dump, DumpTrees.read(UiJson.write(dump)));
        assertThrows(IllegalArgumentException.class, () -> DumpTrees.read("{\"schema\":1}"));
    }
    /** Новый additive API ядра используется для обоих клиентов без сравнения служебного происхождения. */
    @Test void comparisonApiIsRequired() throws Exception {
        var dump = ParityPipelineIT.fixture("model", "s02", "sample");
        assertEquals(DumpTrees.normalized(dump, temp, ""),
                DumpTrees.normalized(ParityPipelineIT.fixture("fx", "s02", "sample"), temp, ""));
    }
    /** Именованный pointer и перестановка меню берутся из API ядра, wire списки остаются массивами. */
    @Test void namedPointersAndOrderComeFromCore() {
        var save = new UiDump.MenuItem("file.save", "Action", "Save", "", true, false, "", "", "", "", List.of());
        var open = new UiDump.MenuItem("file.open", "Action", "Open", "", true, false, "", "", "", "", List.of());
        Map<String, Object> wire = new LinkedHashMap<>(ru.cashprediction.core.json.Json.asObject(
                UiJson.toTree(ParityPipelineIT.fixture("fx", "s02", "sample")), "fixture"));
        wire.put("menuBar", UiJson.toTree(List.of(save, open)));
        var expected = DumpTrees.normalized(DumpTrees.read(UiJson.write(wire)), temp, "");
        var changed = new UiDump.MenuItem("file.save", "Action", "Changed", "", true, false, "", "", "", "", List.of());
        wire.put("menuBar", UiJson.toTree(List.of(open, changed)));
        var actual = DumpTrees.normalized(DumpTrees.read(UiJson.write(wire)), temp, "");
        var diffs = DumpDiff.diff(expected, actual, 0);
        assertTrue(diffs.stream().anyMatch(d -> d.pointer().equals("/menuBar/file.save/text")));
        assertTrue(diffs.stream().anyMatch(d -> d.pointer().startsWith("/menuBar/$order/")));
        assertInstanceOf(List.class, wire.get("menuBar"));
    }
    /** Пустой каталог и ошибка команды остаются ошибками отчёта. */
    @Test void missingOutputAndUnsupportedOperationFail() throws Exception {
        Path goldens = temp.resolve("goldens/s02"); Files.createDirectories(goldens);
        Files.writeString(goldens.resolve("sample.json"), UiJson.write(ParityPipelineIT.fixture("model", "s02", "sample")));
        var result = ParityPipeline.run(temp.resolve("goldens"), temp.resolve("out"), List.of("fx", "web"),
                List.of("s02"), new AllowedDiffs(List.of()), false, (client, scenario, output) -> {
                    if (client.equals("web")) throw new UnsupportedOperationException("<unavailable>");
                    Files.createDirectories(output);
                    return new ParityPipeline.Collection(output, temp, "");
                });
        assertFalse(result.ok());
        String html = Files.readString(result.report());
        assertTrue(html.contains("No dumps")); assertTrue(html.contains("&lt;unavailable&gt;"));
    }
    /** Неиспользованный допуск запрещает успех полного прогона. */
    @Test void unusedAllowanceFailsFullMatrix() throws Exception {
        Path folder = temp.resolve("goldens/s02"); Files.createDirectories(folder);
        Files.writeString(folder.resolve("sample.json"), UiJson.write(ParityPipelineIT.fixture("model", "s02", "sample")));
        var allowed = new AllowedDiffs(List.of(new AllowedDiffs.Entry(11, "/frame/titleBar", Set.of("web"), "§10 №11", "frame")));
        var result = ParityPipeline.run(temp.resolve("goldens"), temp.resolve("out"), List.of("fx"), List.of("s02"),
                allowed, true, (client, scenario, output) -> {
                    Files.createDirectories(output);
                    Files.writeString(output.resolve("sample.json"), UiJson.write(ParityPipelineIT.fixture(client, scenario, "sample")));
                    return new ParityPipeline.Collection(output, temp, "");
                });
        assertTrue(result.failures().stream().anyMatch(f -> f.contains("Unused allowance")));
    }
}
