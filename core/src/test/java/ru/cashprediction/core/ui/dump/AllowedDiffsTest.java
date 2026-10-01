package ru.cashprediction.core.ui.dump;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Проверяет чтение и строгий учёт допустимых различий. */
class AllowedDiffsTest {

    @Test
    void acceptsOnlyMatchingClientAndTracksEveryUsedEntry() {
        AllowedDiffs allowed = AllowedDiffs.parse("""
                [{"number": 5, "pointer": "/toolbar/items/*/bounds/**", "clients": ["web"],
                  "specSection": "§10 №5", "description": "layout"}]
                """);
        List<DumpDiff.Difference> rejected = allowed.filter("web", List.of(
                new DumpDiff.Difference("/toolbar/items/1/bounds/x", 1, 2),
                new DumpDiff.Difference("/table/rowCount", 1, 2)));
        assertEquals(List.of("/table/rowCount"), rejected.stream().map(DumpDiff.Difference::pointer).toList());
        assertEquals(List.of(), allowed.unused());
        assertEquals(1, allowed.filter("fx", List.of(new DumpDiff.Difference("/toolbar/items/1/bounds/x", 1, 2))).size());
    }

    @Test
    void rejectsMalformedEntryInsteadOfSilentlyAllowingIt() {
        assertThrows(RuntimeException.class, () -> AllowedDiffs.parse("[{\"number\":1,\"pointer\":\"/x\"}]"));
    }

    @Test
    void rejectsInvalidPointerPatternsAndClients() {
        for (String pointer : List.of("", "bounds/x", "/**", "/*", "/windows/**/x", "/windows/w*/x",
                "/windows/~2/x", "/windows/~")) {
            assertThrows(IllegalArgumentException.class, () -> entry(pointer, Set.of("web")), pointer);
        }
        assertThrows(IllegalArgumentException.class, () -> entry("/toolbar/wrap", Set.of()));
        assertThrows(IllegalArgumentException.class, () -> entry("/toolbar/wrap", Set.of("model")));
        assertThrows(IllegalArgumentException.class, () -> new AllowedDiffs.Entry(17, "/toolbar/wrap",
                Set.of("web"), "§10 №17", "layout"));
        assertThrows(IllegalArgumentException.class, () -> new AllowedDiffs.Entry(15, "/toolbar/wrap",
                Set.of("web"), "", "layout"));
    }

    @Test
    void rejectsDuplicateRulesAndDuplicateClients() {
        AllowedDiffs.Entry rule = entry("/toolbar/wrap", Set.of("web"));
        assertThrows(IllegalArgumentException.class, () -> new AllowedDiffs(List.of(rule, rule)));
        assertThrows(IllegalArgumentException.class, () -> AllowedDiffs.parse("""
                [{"number":15,"pointer":"/toolbar/wrap","clients":["web","web"],
                  "specSection":"§10 №15","description":"layout"}]
                """));
        assertThrows(IllegalArgumentException.class, () -> new AllowedDiffs(List.of(rule))
                .filter("model", List.of()));
    }

    @Test
    void wildcardMatchesWholeSegmentsAndEscapedNamesOnly() {
        AllowedDiffs allowed = new AllowedDiffs(List.of(entry("/windows/a~1b/fields/*/text", Set.of("web"))));
        List<DumpDiff.Difference> differences = List.of(
                new DumpDiff.Difference("/windows/a~1b/fields/name/text", "a", "b"),
                new DumpDiff.Difference("/windows/a~1b/fields/name/text/extra", "a", "b"),
                new DumpDiff.Difference("/windows/a/b/fields/name/text", "a", "b"));
        assertEquals(differences.subList(1, 3), allowed.filter("web", differences));
        assertEquals(List.of(), allowed.unused());
    }

    private static AllowedDiffs.Entry entry(String pointer, Set<String> clients) {
        return new AllowedDiffs.Entry(5, pointer, clients, "§10 №5", "layout");
    }
}
