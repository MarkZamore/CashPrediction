package ru.cashprediction.core.ui.selftest.paint;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Проверяет стабильную actual-пару и привязку commit к точным сохраняемым байтам. */
final class WidgetCaptureTest {
    /** PNG не отдаётся наружу по ссылке, raw сохраняет прежнюю схему 1. */
    @Test void preservesRawSchemaAndCopiesOriginalPng() {
        byte[] png = PaintCaptureFixtures.png(1200, 800); byte[] expected = png.clone();
        var capture = new WidgetCapture(PaintCaptureFixtures.raw("fx", "normal"), png, PaintCaptureFixtures.observation());
        png[0] = 0; byte[] returned = capture.png(); returned[0] = 0;
        assertArrayEquals(expected, capture.png()); assertEquals(1, capture.raw().schema());
        var rawTree = ru.cashprediction.core.json.JsonParser.parse(new String(capture.rawJson(), StandardCharsets.UTF_8));
        assertEquals(1L, ((Map<?, ?>) rawTree).get("schema"));
        assertFalse(((Map<?, ?>) rawTree).containsKey("paint"));
    }

    /** Неподдержанный и модельный источник не становятся успешной actual-парой. */
    @Test void rejectsModelWrongRawAndUnsupported() {
        byte[] png = PaintCaptureFixtures.png(1200, 800); var observation = PaintCaptureFixtures.observation();
        assertThrows(IllegalArgumentException.class, () -> new WidgetCapture(PaintCaptureFixtures.raw("model", "normal"), png, observation));
        assertThrows(IllegalArgumentException.class, () -> new WidgetCapture(PaintCaptureFixtures.raw("swing", "normal"), png, observation));
        assertThrows(IllegalArgumentException.class, () -> new WidgetCapture(PaintCaptureFixtures.raw("fx", "other"), png, observation));
        var unsupported = PaintCaptureFixtures.observation(PaintCaptureFixtures.sync(), observation.assets(), observation.icons(), observation.cards(),
                List.of(new PaintObservation.Unsupported("root-1", "occlusion", "unknown occlusion")));
        assertThrows(IllegalArgumentException.class, () -> new WidgetCapture(PaintCaptureFixtures.raw("fx", "normal"), png, unsupported));
    }

    /** ABA-поколения, changed-флаг и unsettled не проходят даже при одинаковом fingerprint. */
    @Test void rejectsUnstableBracketsIncludingChangeAndRevert() {
        for (int mutation = 0; mutation < 7; mutation++) {
            var original = PaintCaptureFixtures.sync();
            var sync = new PaintObservation.Synchronization("fx-scene", 7, mutation == 0 ? 8 : 7,
                    8, mutation == 1 ? 9 : 8, 9, mutation == 2 ? 10 : 9, 10, mutation == 3 ? 11 : 10,
                    original.frameId(), original.fingerprintBefore(), mutation == 4 ? "b".repeat(64) : original.fingerprintAfter(),
                    original.startNanos(), original.endNanos(), mutation != 5, mutation == 6 ? List.of("focus changed and reverted") : List.of());
            assertFalse(sync.stable());
            var observation = PaintCaptureFixtures.observation(sync, List.of(), List.of(), List.of(), List.of());
            assertThrows(IllegalArgumentException.class, () -> new WidgetCapture(PaintCaptureFixtures.raw("fx", "normal"), PaintCaptureFixtures.png(1200, 800), observation));
        }
        var wrapped = new PaintObservation.Synchronization("fx-scene", 1, 1, 1, 1, 1, 1, 1, 1, "frame", PaintCaptureFixtures.HASH,
                PaintCaptureFixtures.HASH, Long.MAX_VALUE - 5, Long.MIN_VALUE + 5, true, List.of());
        assertTrue(wrapped.stable());
    }

    /** Каждый компонент запроса проверяется независимо; internally-consistent чужая пара недостаточна. */
    @Test void rejectsStaleRunCaptureCommandStepScenarioAttemptAndPlan() {
        var capture = capture(); var request = PaintCaptureFixtures.request(); capture.requireRequest(request);
        for (int field = 0; field < 7; field++) {
            var wrong = new PaintCaptureRequest(field == 0 ? UUID.randomUUID() : request.runId(),
                    field == 1 ? UUID.randomUUID() : request.captureId(), field == 2 ? 4 : request.commandNumber(),
                    field == 3 ? "other" : request.scenario(), field == 4 ? "other" : request.step(),
                    field == 5 ? 2 : request.attempt(), field == 6 ? "b".repeat(64) : request.planSha256(), request.cardStates(), request.deadlineNanos());
            assertThrows(IllegalArgumentException.class, () -> capture.requireRequest(wrong));
        }
    }

    /** Манифест хеширует исходные пробелы и отвергает частичные/подменённые файлы. */
    @Test void commitBindsExactFilesAndRoundTripsStrictly() {
        var capture = capture(); var request = PaintCaptureFixtures.request();
        byte[] raw = (" \n" + new String(capture.rawJson(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
        byte[] paint = (new String(PaintObservationCodec.write(capture.observation()), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
        var commit = capture.commit(request, raw, paint);
        assertEquals(PaintObservationCodec.sha256(raw), commit.raw().sha256());
        assertEquals(raw.length, commit.raw().byteLength());
        assertEquals(PaintObservationCodec.sha256(capture.png()), commit.png().sha256());
        assertEquals(commit, PaintObservationCodec.readCommit(PaintObservationCodec.writeCommit(commit)));
        commit.verify(request, capture, raw, paint);
        assertThrows(IllegalArgumentException.class, () -> commit.verify(request, capture, capture.rawJson(), paint));
        assertThrows(IllegalArgumentException.class, () -> commit.verify(request, capture, raw, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> capture.commit(request,
                new String(raw, StandardCharsets.UTF_8).replace("normal", "other").getBytes(StandardCharsets.UTF_8), paint));
        assertThrows(IllegalArgumentException.class, () -> capture.commit(request, "null".getBytes(StandardCharsets.UTF_8), paint));
        assertThrows(IllegalArgumentException.class, () -> capture.commit(request, raw,
                new String(paint, StandardCharsets.UTF_8).replace("0.55", "0.65").getBytes(StandardCharsets.UTF_8)));
        String json = new String(PaintObservationCodec.writeCommit(commit), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> PaintObservationCodec.readCommit(json.replace("\"schema\":1", "\"schema\":2").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> PaintObservationCodec.readCommit((json.substring(0, json.length() - 1) + ",\"unknown\":0}").getBytes(StandardCharsets.UTF_8)));
    }

    /** Исходный PNG должен соответствовать viewport, лимиту и целостной структуре. */
    @Test void rejectsWrongOrTruncatedPng() {
        var raw = PaintCaptureFixtures.raw("fx", "normal"); var observation = PaintCaptureFixtures.observation();
        assertThrows(IllegalArgumentException.class, () -> new WidgetCapture(raw, PaintCaptureFixtures.png(1199, 800), observation));
        byte[] png = PaintCaptureFixtures.png(1200, 800);
        assertThrows(IllegalArgumentException.class, () -> new WidgetCapture(raw, java.util.Arrays.copyOf(png, png.length - 1), observation));
        assertThrows(IllegalArgumentException.class, () -> new WidgetCapture(raw, new byte[PaintObservationCodec.MAX_PNG_BYTES + 1], observation));
    }

    /** Создаёт только unit-пару. */
    private static WidgetCapture capture() {
        return new WidgetCapture(PaintCaptureFixtures.raw("fx", "normal"), PaintCaptureFixtures.png(1200, 800), PaintCaptureFixtures.observation());
    }
}
