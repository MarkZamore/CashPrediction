package ru.cashprediction.parity.pipeline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.dump.AllowedDiffs;
import ru.cashprediction.core.ui.dump.DumpDiff;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.json.UiJson;
import static org.junit.jupiter.api.Assertions.*;

/** Регрессии: отсутствие измерений модели не скрывает ошибки настоящих клиентов. */
class GoldenMeasurementsTest {
    /** Нулевой служебный x не отменяет проверку порядка, текста и доступности кнопок. */
    @Test void buttonCoordinatesAreProjectedButSemanticsAndOrderRemain() {
        var expected = Map.of("alerts", Map.of("about", Map.of("buttons", Map.of(
                "$order", List.of("ok", "cancel"), "ok", Map.of("x", 0, "text", "OK", "enabled", true)))));
        var actual = Map.of("alerts", Map.of("about", Map.of("buttons", Map.of(
                "$order", List.of("cancel", "ok"), "ok", Map.of("x", 420, "text", "Wrong", "enabled", false)))));
        var pair = GoldenMeasurements.project(expected, actual);
        var diffs = DumpDiff.diff(pair.expected(), pair.actual(), 0);
        assertTrue(diffs.stream().anyMatch(d -> d.pointer().endsWith("/text")));
        assertTrue(diffs.stream().anyMatch(d -> d.pointer().endsWith("/enabled")));
        assertTrue(diffs.stream().anyMatch(d -> d.pointer().contains("/$order/")));
        assertFalse(diffs.stream().anyMatch(d -> d.pointer().endsWith("/x")));
        assertEquals(420, ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) actual.get("alerts"))
                .get("about")).get("buttons")).get("ok")).get("x"));
    }

    /** Заданные размеры окна и формы, цвета и отсутствующие измерения разных смыслов не смешиваются. */
    @Test void specifiedMeasurementsAndUnrelatedNullsAreNotMasked() {
        Map<String, Object> field = new LinkedHashMap<>(); field.put("bounds", null);
        var expected = Map.of("frame", Map.of("contentWidth", 1200, "regions", Map.of()),
                "windows", Map.of("rule", Map.of("bounds", Map.of("width", 500), "fields", Map.of("custom", field))));
        var actual = Map.of("frame", Map.of("contentWidth", 1100, "regions", Map.of("toolbar", Map.of("width", 1100))),
                "windows", Map.of("rule", Map.of("bounds", Map.of("width", 400),
                        "fields", Map.of("custom", Map.of("bounds", Map.of("width", 100))))));
        var pair = GoldenMeasurements.project(expected, actual);
        var diffs = DumpDiff.diff(pair.expected(), pair.actual(), 0);
        assertEquals(3, diffs.size(), diffs.toString());
        assertTrue(diffs.stream().anyMatch(d -> d.pointer().equals("/frame/contentWidth")));
        assertTrue(diffs.stream().anyMatch(d -> d.pointer().equals("/windows/rule/bounds/width")));
        assertTrue(diffs.stream().anyMatch(d -> d.pointer().equals("/windows/rule/fields/custom/bounds")));
    }

    /** Лишний и отсутствующий виджет всё ещё дают ошибку, даже если модель не задаёт bounds. */
    @Test void missingAndExtraWidgetsRemainDifferences() {
        Map<String, Object> card = new LinkedHashMap<>(); card.put("bounds", null); card.put("value", "100");
        var expected = Map.of("summary", Map.of("cards", Map.of("now", card)));
        var actual = Map.of("summary", Map.of("cards", Map.of("extra", Map.of("bounds", Map.of("width", 1)))));
        var pair = GoldenMeasurements.project(expected, actual);
        assertEquals(2, DumpDiff.diff(pair.expected(), pair.actual(), 0).size());
    }

    /** Полный стенд принимает модель без bounds, но отвергает несовпадение bounds двух клиентов. */
    @Test void pairwiseGeometryStillFailsEndToEnd() throws Exception {
        Path root = Path.of(System.getProperty("parity.reactor.root", ".")).toAbsolutePath()
                .resolve("ui-parity/target/parity/geometry-" + UUID.randomUUID());
        Path golden = root.resolve("goldens/s02"); Files.createDirectories(golden);
        Files.writeString(golden.resolve("sample.json"), UiJson.write(fixture("model", null)));
        var result = ParityPipeline.run(root.resolve("goldens"), root.resolve("output"), List.of("fx", "swing"),
                List.of("s02"), new AllowedDiffs(List.of()), false, (client, scenario, output) -> {
                    Files.createDirectories(output);
                    Files.writeString(output.resolve("sample.json"), UiJson.write(fixture(client,
                            new UiDump.Box(client.equals("fx") ? 0 : 12, 0, 100, 80))));
                    return new ParityPipeline.Collection(output, root, "");
                });
        assertFalse(result.ok());
        assertEquals(1, result.failures().size(), result.failures().toString());
        assertTrue(result.failures().getFirst().contains("fx vs swing"));
        assertTrue(result.failures().getFirst().contains("/summary/cards/now/bounds/x"));
        assertTrue(Files.readString(result.report()).contains("sample: PASS"));
    }

    /** Один минимальный дамп с настоящим либо незаданным измерением карточки. */
    private static UiDump fixture(String client, UiDump.Box bounds) {
        return new UiDump(1, client, "s02", "sample", null, List.of(), null,
                new UiDump.Summary(true, List.of(new UiDump.Card("now", "Now", "100", "text.primary",
                        "", "text.muted", "", bounds)), ""), null, null, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
    }
}
