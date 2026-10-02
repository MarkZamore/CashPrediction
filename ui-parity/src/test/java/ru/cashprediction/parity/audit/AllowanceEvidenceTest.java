package ru.cashprediction.parity.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.ui.dump.AllowedDiffs;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.parity.pipeline.ParityPipeline;
import static org.junit.jupiter.api.Assertions.*;

/** Синтетические регрессии интеграции: не объявляют исполненными native references или UI-паритет. */
final class AllowanceEvidenceTest {
    @TempDir Path temp;
    private static final String NODE = "ru/cashprediction/selftest/00000000-0000-0000-0000-000000000001";

    /** Реальный DumpDiff перед unused-аудитом изменяет тот же AllowedDiffs; равные деревья не изменяют его. */
    @Test void sharedPipelineConsumesOnlyActualDifference() throws Exception {
        Path manifest = manifest("halt-header", fixture("fx", UiText.get("alert.halt.header")),
                fixture("web", UiText.get("alert.halt.web")));
        var pairs = AllowanceEvidence.load(manifest);
        var allowed = haltAllowance();
        assertTrue(pipeline(allowed, () -> pairs).ok());
        assertTrue(allowed.unused().isEmpty());
        manifest("halt-header", fixture("fx", UiText.get("alert.halt.header")),
                fixture("web", UiText.get("alert.halt.header")));
        var equal = haltAllowance();
        assertFalse(pipeline(equal, () -> AllowanceEvidence.load(manifest)).ok());
        assertEquals(1, equal.unused().size());
    }

    /** Неверная замена не поглощается правилом №4 и остаётся ошибкой отчёта. */
    @Test void rejectedDifferenceRemainsVisible() throws Exception {
        Path manifest = manifest("halt-header", fixture("fx", UiText.get("alert.halt.header")), fixture("web", "wrong"));
        var allowed = haltAllowance();
        var result = pipeline(allowed, () -> AllowanceEvidence.load(manifest));
        assertTrue(result.failures().stream().anyMatch(f -> f.contains("/alerts/simulateHalt/header")));
        assertEquals(1, allowed.unused().size());
    }

    /** Повреждённый SHA и отсутствующий reference не превращаются в поглощённую запись. */
    @Test void corruptAndMissingReferenceAreFailures() throws Exception {
        Path manifest = manifest("halt-header", fixture("fx", UiText.get("alert.halt.header")),
                fixture("web", UiText.get("alert.halt.web")));
        Files.writeString(temp.resolve("reference.json"), "changed");
        var allowed = haltAllowance();
        assertThrows(IllegalArgumentException.class, () -> AllowanceEvidence.load(manifest));
        assertFalse(pipeline(allowed, () -> AllowanceEvidence.load(manifest)).ok());
        assertEquals(1, allowed.unused().size());
        Files.delete(temp.resolve("reference.json"));
        assertThrows(java.io.IOException.class, () -> AllowanceEvidence.load(manifest));
    }

    /** Модельный reference запрещён независимо от того, насколько похож его header. */
    @Test void modelReferenceIsRejected() throws Exception {
        Path manifest = manifest("halt-header", fixture("model", UiText.get("alert.halt.header")),
                fixture("web", UiText.get("alert.halt.web")));
        assertThrows(IllegalArgumentException.class, () -> AllowanceEvidence.load(manifest));
    }

    /** Post-exit наблюдение валидируется самостоятельно, а не только широким allowance №14. */
    @Test void screenRequiresLocalizedModalStateAndRetry() {
        Map<String, Object> valid = Map.of("kind", "offline", "title", UiText.get("offline.title"),
                "text", UiText.get("offline.text"), "open", true, "mainInert", true,
                "buttons", List.of(Map.of("id", "offline.retry", "text", UiText.get("offline.retry"), "enabled", true)));
        assertNotNull(AllowanceEvidence.screen(valid, "offline"));
        var changed = new java.util.LinkedHashMap<>(valid);
        changed.put("mainInert", false);
        assertThrows(IllegalArgumentException.class, () -> AllowanceEvidence.screen(changed, "offline"));
        changed.put("mainInert", true); changed.put("buttons", List.of());
        assertThrows(IllegalArgumentException.class, () -> AllowanceEvidence.screen(changed, "offline"));
    }

    /** Ограниченная screen-пара использует настоящее отсутствие в native dump, а не counters после exit. */
    @Test void screenEvidenceUsesNativeAbsence() throws Exception {
        UiDump nativeDump = empty("swing");
        Path manifest = manifest("stopped", nativeDump, Map.of("kind", "stopped", "title", UiText.get("offline.stopped.title"),
                "text", UiText.get("offline.stopped.text"), "open", true, "mainInert", true, "buttons", List.of()));
        var allowed = new AllowedDiffs(List.of(new AllowedDiffs.Entry(14, "/screens/stopped", Set.of("web"), "§10 №14", "screen")));
        var result = pipeline(allowed, () -> AllowanceEvidence.load(manifest));
        assertTrue(result.ok(), result.failures().toString()); assertTrue(allowed.unused().isEmpty());
    }

    /** Нельзя использовать narrow allowance с различными измеренными ширинами либо при 1200. */
    @Test void narrowEvidenceRequiresComparableMeasuredFrames() throws Exception {
        Path manifest = manifest("narrow-toolbar", toolbar("fx", 900, false), toolbar("web", 900, true));
        var allowed = new AllowedDiffs(List.of(new AllowedDiffs.Entry(15, "/toolbar/wrap", Set.of("web"), "§10 №15", "wrap")));
        assertTrue(pipeline(allowed, () -> AllowanceEvidence.load(manifest)).ok());
        assertTrue(allowed.unused().isEmpty());
        manifest("narrow-toolbar", toolbar("fx", 900, false), toolbar("web", 1000, true));
        assertThrows(IllegalArgumentException.class, () -> AllowanceEvidence.load(manifest));
        manifest("narrow-toolbar", toolbar("fx", 1200, false), toolbar("web", 1200, true));
        assertThrows(IllegalArgumentException.class, () -> AllowanceEvidence.load(manifest));
    }

    /** Пишет только синтетические unit-данные в отдельную временную папку. */
    private Path manifest(String scope, Object reference, Object actual) throws Exception {
        Path manifest = temp.resolve("manifest.json");
        Files.writeString(manifest, UiJson.write(Map.of("pairs", List.of(Map.of("label", "unit", "scope", scope,
                "reference", source("reference.json", reference), "actual", source("actual.json", actual))))));
        return manifest;
    }

    /** Закрепляет SHA байтов тестового файла. */
    private Map<String, Object> source(String name, Object value) throws Exception {
        Path path = temp.resolve(name); Files.writeString(path, UiJson.write(value));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        return Map.of("path", path.toString(), "sha256", sha, "cashMemory", temp.toString(), "node", NODE);
    }

    /** Выполняет минимальную синтетическую матрицу, сохраняя строгий full unused-аудит. */
    private ParityPipeline.Result pipeline(AllowedDiffs allowed, ParityPipeline.Evidence evidence) throws Exception {
        Path golden = temp.resolve("goldens/unit"); Files.createDirectories(golden);
        Files.writeString(golden.resolve("step.json"), UiJson.write(empty("model")));
        return ParityPipeline.run(temp.resolve("goldens"), temp.resolve("out"), List.of("fx"), List.of("unit"),
                allowed, true, (client, scenario, output) -> {
                    Files.createDirectories(output); Files.writeString(output.resolve("step.json"), UiJson.write(empty("fx")));
                    return new ParityPipeline.Collection(output, temp, NODE);
                }, evidence);
    }

    /** Таблица сохраняет точное значение правила №4. */
    private static AllowedDiffs haltAllowance() {
        return new AllowedDiffs(List.of(new AllowedDiffs.Entry(4, "/alerts/simulateHalt/header", Set.of("web"), "§10 №4", "header")));
    }

    /** Минимальный синтетический wire dump. */
    private static UiDump empty(String client) {
        return new UiDump(1, client, "unit", "step", null, List.of(), null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
    }

    /** Добавляет синтетический header, не используя ModelUiDriver. */
    private static Object fixture(String client, String header) {
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog; здесь только wire fixture unit-теста.
        var alert = new UiDump.Alert("a1", "simulateHalt", "WARNING", "", "", 460, header, "", "", "", false, List.of());
        var wire = new java.util.LinkedHashMap<>(ru.cashprediction.core.json.Json.asObject(UiJson.toTree(empty(client)), "fixture"));
        wire.put("alerts", UiJson.toTree(List.of(alert))); return wire;
    }

    /** Синтетические измерения защищают контракт проекции, а не доказывают перенос настоящего тулбара. */
    private static Object toolbar(String client, double width, boolean wrap) {
        var wire = new java.util.LinkedHashMap<>(ru.cashprediction.core.json.Json.asObject(UiJson.toTree(empty(client)), "fixture"));
        wire.put("frame", UiJson.toTree(new UiDump.Frame("", "", null, width, 800, Map.of())));
        wire.put("toolbar", UiJson.toTree(new UiDump.Toolbar(wrap, List.of()))); return wire;
    }
}
