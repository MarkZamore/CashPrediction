package ru.cashprediction.core.ui.selftest.paint;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;

/** Строгая схема и происхождение actual-данных; unit-успех не доказывает нативную краску. */
final class PaintObservationCodecTest {
    /** Дробные рамки, слои, ошибочные actual-цвета и альфа сохраняются без нормализации. */
    @Test void roundTripsActualMeasurementsWithoutExpectedTokens() {
        var original = PaintCaptureFixtures.observation(); byte[] bytes = PaintObservationCodec.write(original);
        var restored = PaintObservationCodec.read(bytes);
        assertEquals(original, restored); assertArrayEquals(bytes, PaintObservationCodec.write(restored));
        assertEquals(20.25, restored.icons().getFirst().bounds().x());
        assertEquals(0.55, restored.icons().getFirst().effectiveOpacity());
        assertNull(restored.icons().getFirst().variantToken());
        assertEquals(0, restored.cards().getFirst().backgrounds().getFirst().radii().getFirst().rx());
        assertEquals(8, restored.cards().getFirst().borders().getFirst().radii().getFirst().ry());
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"logicalToPng\":[1.0,0.0,0.0,1.0,0.25,0.5]"));
        assertTrue(json.contains("\"startNanos\":\"-100\""));
        assertFalse(json.contains("expected"));
    }

    /** Один и тот же нейтральный контракт используется всеми тремя реальными клиентами. */
    @Test void supportsAllClientsAndSynchronizationModes() {
        var original = PaintCaptureFixtures.observation();
        for (String client : List.of("fx", "swing", "web")) {
            var id = original.identity(); var before = original.synchronization();
            String mode = switch (client) { case "fx" -> "fx-scene"; case "swing" -> "bracketed-screen"; default -> "bracketed-cdp"; };
            var identity = new PaintObservation.Identity(id.runId(), id.captureId(), id.commandNumber(), client,
                    id.scenario(), id.step(), id.attempt(), id.planSha256());
            var sync = new PaintObservation.Synchronization(mode, before.epochBefore(), before.epochAfter(),
                    before.layoutRevisionBefore(), before.layoutRevisionAfter(), before.paintRevisionBefore(), before.paintRevisionAfter(),
                    before.renderGenerationBefore(), before.renderGenerationAfter(), before.frameId(), before.fingerprintBefore(),
                    before.fingerprintAfter(), before.startNanos(), before.endNanos(), before.settled(), before.changes());
            var viewport = original.viewport();
            if (client.equals("web")) viewport = new PaintObservation.Viewport(1200, 800, 1200, 800,
                    PaintCaptureFixtures.TRANSFORM, null, 1, 1, 1.0, "root-1");
            var observation = new PaintObservation(1, PaintObservation.KIND, identity, viewport,
                    original.environment(), sync, original.interaction(), original.surfaces(), original.assets(), original.icons(), original.cards(), original.unsupported());
            assertEquals(observation, PaintObservationCodec.read(PaintObservationCodec.write(observation)));
            assertDoesNotThrow(() -> new WidgetCapture(PaintCaptureFixtures.raw(client, "normal"), PaintCaptureFixtures.png(1200, 800), observation));
        }
    }

    /** Каждый фиксированный объект требует точного набора ключей, включая обязательные nullable-поля. */
    @Test void rejectsUnknownMissingAndNullKeysAtEveryRecordLevel() {
        List<String[]> paths = List.of(new String[] {}, new String[] {"identity"}, new String[] {"viewport"},
                new String[] {"environment"}, new String[] {"synchronization"}, new String[] {"interaction"},
                new String[] {"surfaces", "0"}, new String[] {"surfaces", "0", "box"}, new String[] {"assets", "0"},
                new String[] {"icons", "0"}, new String[] {"icons", "0", "localBox"},
                new String[] {"icons", "0", "opacityFactors", "0"}, new String[] {"cards", "0"},
                new String[] {"cards", "0", "backgrounds", "0"}, new String[] {"cards", "0", "backgrounds", "0", "radii", "0"},
                new String[] {"cards", "0", "borders", "0"}, new String[] {"cards", "0", "borders", "0", "insets"});
        for (String[] path : paths) {
            assertBad(root -> object(at(root, path)).put("expectedToken", "ACCENT"));
            assertBad(root -> { var target = object(at(root, path)); target.remove(target.keySet().iterator().next()); });
        }
        assertBad(root -> object(root.get("identity")).put("client", null));
        assertBad(root -> object(root.get("viewport")).remove("browserDpr"));
        assertBad(root -> object(root.get("environment")).put("artifactDigests", null));
        assertBad(root -> object(at(root, "icons", "0")).put("opacityFactors", Arrays.asList((Object) null)));
    }

    /** Версия, вид, идентификаторы и числовые типы не имеют permissive fallback. */
    @Test void rejectsVersionsIdentityAndWrongTypes() {
        assertBad(root -> root.put("schema", 2)); assertBad(root -> root.put("schema", "1"));
        assertBad(root -> root.put("kind", "model-paint"));
        assertBad(root -> object(root.get("identity")).put("client", "model"));
        assertBad(root -> object(root.get("identity")).put("captureId", "2-2-2-2-2"));
        assertBad(root -> object(root.get("identity")).put("runId", "FFFFFFFF-FFFF-4FFF-8FFF-FFFFFFFFFFFF"));
        assertBad(root -> object(root.get("identity")).put("planSha256", "f".repeat(63)));
        assertBad(root -> object(root.get("identity")).put("attempt", 0));
        assertBad(root -> object(root.get("identity")).put("commandNumber", 3.5));
        assertBad(root -> object(root.get("synchronization")).put("epochAfter", 9_007_199_254_740_992L));
        assertBad(root -> object(root.get("synchronization")).put("startNanos", -100));
        assertBad(root -> object(root.get("synchronization")).put("endNanos", "01"));
        assertBad(root -> object(root.get("viewport")).put("logicalToPng", List.of(1, 0, 0, 1)));
        assertBad(root -> object(at(root, "icons", "0")).put("effectiveOpacity", 1.01));
        assertBad(root -> object(at(root, "icons", "0", "bounds")).put("width", -1));
        assertBad(root -> object(at(root, "cards", "0", "borders", "0")).put("widths", List.of(1, 1, 1)));
        assertBad(root -> object(at(root, "cards", "0", "backgrounds", "0")).put("radii", List.of()));
    }

    /** Источник нельзя подтвердить правильным маркером при неверных байтах или идентичности. */
    @Test void rejectsTamperedAssetsAndInstalledIdentity() {
        assertBad(root -> object(at(root, "assets", "0")).put("sha256", "b".repeat(64)));
        assertBad(root -> object(at(root, "assets", "0")).put("byteLength", 1));
        assertBad(root -> object(at(root, "assets", "0")).put("base64", "%%%"));
        assertBad(root -> object(at(root, "assets", "0")).put("decodedWidth", 15));
        assertBad(root -> object(at(root, "assets", "0")).put("decodedArgbSha256", "bad"));
        assertBad(root -> object(at(root, "assets", "0")).put("provenanceMethod", "expected-resource"));
        assertBad(root -> object(at(root, "icons", "0")).put("sourceObjectIdentity", "replacement-image"));
        assertBad(root -> object(at(root, "icons", "0")).put("assetId", "missing"));
        assertBad(root -> object(at(root, "icons", "0")).put("assetId", null));
        assertBad(root -> object(at(root, "icons", "0")).put("complete", false));
        assertBad(root -> object(at(root, "icons", "0")).put("paintSource", "unclassified"));
        byte[] bad = PaintCaptureFixtures.png(16, 16); bad[bad.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> new PaintObservation.Asset("a", bad.length,
                PaintObservationCodec.sha256(bad), bad, "unit", 16, 16, PaintCaptureFixtures.HASH, "decode-capture", "image"));
    }

    /** Дубли instance/asset/card-id запрещены, но повторные рисования одного источника не теряются. */
    @Test void preservesOccurrencesAndRejectsDuplicateIdentities() {
        var icons = List.of(PaintCaptureFixtures.icon("icon-1", "asset-1", "image-1"), PaintCaptureFixtures.icon("icon-2", "asset-1", "image-1"));
        var observation = PaintCaptureFixtures.observation(PaintCaptureFixtures.sync(), List.of(PaintCaptureFixtures.asset()), icons, List.of(), List.of());
        assertEquals(2, PaintObservationCodec.read(PaintObservationCodec.write(observation)).icons().size());
        assertBad(root -> { var list = list(root.get("icons")); list.add(list.getFirst()); });
        assertBad(root -> { var list = list(root.get("assets")); list.add(list.getFirst()); });
        assertBad(root -> { var cards = list(root.get("cards")); var duplicate = new java.util.LinkedHashMap<>(object(cards.getFirst()));
            duplicate.put("instanceId", "card-2"); cards.add(duplicate); });
        assertBad(root -> object(at(root, "icons", "0")).put("instanceId", "root-1"));
        assertBad(root -> object(root.get("viewport")).put("rootInstance", "missing"));
    }

    /** Unsupported сохраняется для диагностики и не превращается в выдуманный ресурс. */
    @Test void retainsUnresolvedObservationOnlyWithDiagnostic() {
        var observation = PaintCaptureFixtures.observation(PaintCaptureFixtures.sync(), List.of(),
                List.of(PaintCaptureFixtures.icon("icon-1", null, "unknown-image")), List.of(),
                List.of(new PaintObservation.Unsupported("icon-1", "source", "unknown installed image")));
        var restored = PaintObservationCodec.read(PaintObservationCodec.write(observation));
        assertNull(restored.icons().getFirst().assetId()); assertEquals(observation, restored);
    }

    /** Лимиты действуют для байтов, числа элементов и aggregate-ресурсов. */
    @Test void enforcesTransportAndCollectionLimits() {
        assertThrows(IllegalArgumentException.class, () -> PaintObservationCodec.read(new byte[PaintObservationCodec.MAX_JSON_BYTES + 1]));
        assertBad(root -> root.put("icons", Collections.nCopies(129, list(root.get("icons")).getFirst())));
        assertBad(root -> root.put("cards", Collections.nCopies(33, list(root.get("cards")).getFirst())));
        assertBad(root -> object(at(root, "assets", "0")).put("base64", "A".repeat(1_398_109)));
        byte[] large = new byte[PaintObservation.MAX_ASSET_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> new PaintObservation.Asset("a", large.length,
                PaintCaptureFixtures.HASH, large, "unit", 1, 1, PaintCaptureFixtures.HASH, "decode-capture", "image"));
        byte[] padded = PaintCaptureFixtures.png(16, 16, 600_000);
        var first = new PaintObservation.Asset("asset-a", padded.length, PaintObservationCodec.sha256(padded), padded,
                "unit", 16, 16, PaintCaptureFixtures.HASH, "decode-capture", "image-a");
        var second = new PaintObservation.Asset("asset-b", padded.length, PaintObservationCodec.sha256(padded), padded,
                "unit", 16, 16, PaintCaptureFixtures.HASH, "decode-capture", "image-b");
        assertThrows(IllegalArgumentException.class, () -> PaintCaptureFixtures.observation(PaintCaptureFixtures.sync(),
                List.of(first, second), List.of(), List.of(), List.of()));
    }

    /** Конечность проверяется и без JSON, на границах DTO. */
    @Test void rejectsNonfiniteGeometry() {
        for (double number : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new PaintObservation.Box(number, 0, 1, 1));
            assertThrows(IllegalArgumentException.class, () -> new PaintObservation.Transform(1, 0, number, 1, 0, 0));
            assertThrows(IllegalArgumentException.class, () -> new PaintObservation.Radius(0, 0, false, false, "px", number, 0));
            assertThrows(IllegalArgumentException.class, () -> new PaintObservation.OpacityFactor("root", number, "opacity"));
        }
        assertThrows(IllegalArgumentException.class, () -> new PaintObservation.Box(Double.MAX_VALUE, 0, Double.MAX_VALUE, 1));
        assertBad(root -> object(at(root, "icons", "0", "bounds")).put("x", new java.math.BigDecimal("1e1000")));
    }

    /** UTF-8, одиночные суррогаты, повторные ключи и мусор после документа отклоняются. */
    @Test void rejectsMalformedEncodingAndJson() {
        byte[] original = PaintObservationCodec.write(PaintCaptureFixtures.observation());
        String json = new String(original, StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> PaintObservationCodec.read(new byte[] {(byte) 0xc3, 0x28}));
        for (String invalid : List.of("null", "\ufeff" + json, json + "x", json.replaceFirst("\\{", "{\"schema\":1,"),
                json.replace("unit-os", "\\uD800"), json.replace("unit-os", "\\uDC00")))
            assertThrows(IllegalArgumentException.class, () -> PaintObservationCodec.read(invalid.getBytes(StandardCharsets.UTF_8)));
    }

    /** Коллекции и физические байты защищены от изменения после построения. */
    @Test void defensivelyCopiesBytesAndNestedCollections() {
        var original = PaintCaptureFixtures.asset(); byte[] bytes = original.base64();
        var asset = new PaintObservation.Asset(original.assetId(), bytes.length, original.sha256(), bytes, original.source(),
                16, 16, original.decodedArgbSha256(), original.provenanceMethod(), original.sourceObjectIdentity());
        bytes[0] = 0; byte[] returned = asset.base64(); returned[0] = 0;
        assertArrayEquals(original.base64(), asset.base64()); assertEquals(original, asset); assertEquals(original.hashCode(), asset.hashCode());
        var icons = new ArrayList<>(List.of(PaintCaptureFixtures.icon("icon-1", "asset-1", "image-1")));
        var observation = PaintCaptureFixtures.observation(PaintCaptureFixtures.sync(), List.of(asset), icons, List.of(PaintCaptureFixtures.card()), List.of());
        icons.clear(); assertEquals(1, observation.icons().size());
        assertThrows(UnsupportedOperationException.class, () -> observation.icons().clear());
        assertThrows(UnsupportedOperationException.class, () -> observation.icons().getFirst().quad().clear());
        assertThrows(UnsupportedOperationException.class, () -> observation.cards().getFirst().borders().getFirst().widths().clear());
        assertThrows(UnsupportedOperationException.class, () -> observation.environment().artifactDigests().clear());
    }

    /** Меняет один документ и требует отказ строгого кодека. */
    private static void assertBad(Consumer<Map<String, Object>> mutation) {
        var root = object(JsonParser.parse(new String(PaintObservationCodec.write(PaintCaptureFixtures.observation()), StandardCharsets.UTF_8)));
        mutation.accept(root); byte[] bytes = JsonWriter.write(root).getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> PaintObservationCodec.read(bytes));
    }

    /** Адресует вложенный объект unit-документа. */
    private static Object at(Object root, String... path) {
        Object value = root;
        for (String part : path) value = value instanceof Map<?, ?> ? object(value).get(part) : list(value).get(Integer.parseInt(part));
        return value;
    }

    /** Приводит объект, созданный JSON-разборщиком. */
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    /** Приводит массив, созданный JSON-разборщиком. */
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>) value; }
}
