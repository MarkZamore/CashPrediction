package ru.cashprediction.parity.audit;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.dump.DumpNormalizer;
import ru.cashprediction.core.ui.dump.AllowedDiffs;
import ru.cashprediction.core.ui.dump.DumpDiff;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет извлечение отчёта и ограничение нормализации без GUI, Robot и реестра. */
final class ReportAuditTest {
    /** Две разные записи details остаются двумя неиспользованными записями, а не одним указателем. */
    @Test void preservesDuplicateUnusedPointersAndCountsRoots() {
        var result = ReportAudit.extract("<h2>s08 / fx vs web</h2>"
                + "<pre>s08 / fx vs web / about /alerts/about/content expected=a actual=b</pre>"
                + "<pre>s08 / fx vs web / table /table/rows/r1/cells/0 expected=a actual=b</pre>"
                + "<pre>Unused allowance: §10 №8 /alerts/lastSnapshot/details</pre>"
                + "<pre>Unused allowance: §10 №8 /alerts/lastSnapshot/details</pre><p>Failures: 4</p>");
        assertEquals(2, result.get("unusedEntryCount"));
        assertEquals(2, ((List<?>) result.get("unusedEntries")).size());
        assertEquals(Map.of("/alerts", 1, "/table", 1), result.get("rootGroups"));
        assertEquals(Map.of("s08", 2), result.get("scenarioDifferenceCounts"));
    }

    /** Ошибка сбора отделена от расхождений, сообщения HTML раскодируются ровно один раз. */
    @Test void collectionAndUnknownErrorsAreNeverDropped() {
        var result = ReportAudit.extract("<pre>s12 / swing: failed &lt;node&gt;\nSELFTEST 5 FAIL fill</pre>"
                + "<pre>unexpected &amp;lt;literal&amp;gt;</pre><p>Failures: 2</p>");
        assertEquals(List.of("s12 / swing: failed <node>"), result.get("collectionErrors"));
        assertEquals(List.of("unexpected &lt;literal&gt;"), result.get("unclassifiedErrors"));
        assertEquals(0, result.get("differenceCount"));
    }

    /** Неполный или дублированный итог не превращается в достоверный аудит. */
    @Test void inconsistentSummaryIsRejected() {
        for (String html : List.of("", "<p>Failures: 1</p>",
                "<p>Failures: 0</p><p>Failures: 0</p>"))
            assertThrows(IllegalArgumentException.class, () -> ReportAudit.extract(html));
        assertEquals(0, ReportAudit.extract("<p>Failures: 0</p>").get("reportedFailures"));
    }

    /** Полный node/client стирает различие клиента; префикс сохраняет его для будущей пробы lastSnapshot. */
    @Test void fullNodeNormalizationRemovesClientSuffixBeforeDiff() {
        Path home = Path.of("unit-CashMemory").toAbsolutePath();
        String prefix = "ru/cashprediction/selftest/unit";
        String fx = "HKCU/Software/JavaSoft/Prefs/" + prefix + "/fx (JSON) same";
        String swing = "HKCU/Software/JavaSoft/Prefs/" + prefix + "/swing (JSON) same";
        assertEquals(DumpNormalizer.normalizeText(fx, home, prefix + "/fx"),
                DumpNormalizer.normalizeText(swing, home, prefix + "/swing"));
        assertNotEquals(DumpNormalizer.normalizeText(fx, home, prefix),
                DumpNormalizer.normalizeText(swing, home, prefix));
    }

    /** Даже покрытое состояние без реального различия оставляет запись неиспользованной. */
    @Test void equalTreesDoNotExerciseAllowance() {
        var entry = new AllowedDiffs.Entry(8, "/alerts/lastSnapshot/details", java.util.Set.of("fx", "swing"),
                "§10 №8", "client suffix");
        var allowed = new AllowedDiffs(List.of(entry));
        var tree = Map.of("alerts", Map.of("lastSnapshot", Map.of("details", "<node> (JSON) same")));
        assertTrue(allowed.filter("swing", DumpDiff.diff(tree, tree, 0), tree, tree).isEmpty());
        assertEquals(List.of(entry), allowed.unused());
    }

    /** Перенос разрешён только при измеренной ширине меньше 1200 в обоих деревьях. */
    @Test void wrapRequiresBothNarrowFramesAndAnActualDifference() {
        var entry = new AllowedDiffs.Entry(15, "/toolbar/wrap", java.util.Set.of("web"), "§10 №15", "narrow toolbar");
        var allowed = new AllowedDiffs(List.of(entry));
        var difference = new DumpDiff.Difference("/toolbar/wrap", false, true);
        var narrow = Map.of("frame", Map.of("contentWidth", 1198));
        var wide = Map.of("frame", Map.of("contentWidth", 1200));
        assertEquals(List.of(difference), allowed.filter("web", List.of(difference), wide, narrow));
        assertEquals(List.of(difference), allowed.filter("web", List.of(difference), narrow, wide));
        assertEquals(List.of(difference), allowed.filter("web", List.of(difference)));
        assertEquals(List.of(entry), allowed.unused());
        assertTrue(allowed.filter("web", List.of(difference), narrow, narrow).isEmpty());
        assertTrue(allowed.unused().isEmpty());
    }
}
