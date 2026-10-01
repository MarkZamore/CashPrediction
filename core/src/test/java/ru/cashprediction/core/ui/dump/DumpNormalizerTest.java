package ru.cashprediction.core.ui.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Проверяет переносимость эталонов без потери текстов, порядка и точности данных. */
class DumpNormalizerTest {

    private static final Path HOME = Path.of("target", "normalizer", "CashMemory").toAbsolutePath();
    private static final String NODE = "ru/cashprediction/selftest/example";

    @Test
    void replacesOnlyValidTimesAndExactPathPrefixes() {
        String home = HOME.toString();
        assertEquals("<CashMemory>/plan.md <node>/fx <time> 24:00:00 12:60:00 112:34:56",
                DumpNormalizer.normalizeText(home + "/plan.md " + NODE + "/fx 23:59:59 24:00:00 12:60:00 112:34:56",
                        HOME, NODE));
        assertEquals(home + "Other " + NODE + "Other",
                DumpNormalizer.normalizeText(home + "Other " + NODE + "Other", HOME, NODE));
        assertEquals("<CashMemory> <node>", DumpNormalizer.normalizeText(
                home.replace('\\', '/') + " " + NODE.replace('/', '\\'), HOME, NODE));
        assertEquals("plain", DumpNormalizer.normalizeText("plain", HOME, ""));
    }

    @Test
    void recursivelyCopiesSchemaAndRoundsOnlyGeometry() {
        UiDump original = dump(new UiDump.Box(1.1, -1.1, 101.1, 49.1));
        UiDump normalized = DumpNormalizer.normalize(original, HOME, NODE);
        assertNotSame(original, normalized);
        assertEquals(new UiDump.Box(2, -2, 102, 50), normalized.frame().regions().get("center"));
        assertEquals(1202, normalized.frame().contentWidth());
        assertEquals("<CashMemory>", normalized.frame().title());
        assertEquals("<time>", normalized.summary().cards().getFirst().tooltip());
        assertEquals("123.123", normalized.table().rows().getFirst().cells().getFirst());
        assertEquals(7, normalized.table().rowCount());
        assertEquals(List.of("b", "a"), normalized.menuBar().stream().map(UiDump.MenuItem::id).toList());
        assertEquals(Map.of("SAVE", 5), normalized.counters());
        assertEquals(4, normalized.table().placeholderButtons().getFirst().x());
        assertEquals(HOME.toString(), original.frame().title());
        assertEquals(normalized, DumpNormalizer.normalize(normalized, HOME, NODE));
    }

    @Test
    void invalidGeometryCannotBeDisguisedAsAValidBox() {
        assertThrows(IllegalArgumentException.class, () -> DumpNormalizer.normalize(
                dump(new UiDump.Box(Double.NaN, 0, 100, 100)), HOME, NODE));
    }

    static UiDump dump(UiDump.Box box) {
        UiDump.MenuItem first = new UiDump.MenuItem("b", "Action", "first", "", true, false,
                "", "", "", "", List.of());
        UiDump.MenuItem second = new UiDump.MenuItem("a", "Action", "second", "", true, false,
                "", "", "", "", List.of());
        return new UiDump(1, "model", "scenario", "step",
                new UiDump.Frame(HOME.toString(), "os", new UiDump.Size(900, 600), 1201.1, 800,
                        Map.of("center", box)), List.of(first, second), null,
                new UiDump.Summary(true, List.of(new UiDump.Card("now", "title", "123.123", "text.primary",
                        "caption", "text.primary", "12:34:56", box)), ""),
                new UiDump.Table(List.of("column"), 7,
                        List.of(new UiDump.Row(1, "r1", "INCOME", List.of("123.123"), "", Map.of())),
                        "digest", "", List.of(new UiDump.Button("add", "add", "", true, true, 3.1)), "r1"),
                null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of(), Map.of("SAVE", 5));
    }
}
