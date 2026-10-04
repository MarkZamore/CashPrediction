package ru.cashprediction.core.ui.selftest.paint;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import ru.cashprediction.core.ui.dump.UiDump;

/** Синтетические данные только для проверки контрактов; не свидетельство нативной отрисовки S5. */
final class PaintCaptureFixtures {
    static final String HASH = "a".repeat(64);
    static final UUID RUN = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID CAPTURE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final PaintObservation.Transform TRANSFORM = new PaintObservation.Transform(1, 0, 0, 1, 0.25, 0.5);
    static final PaintObservation.Box BOX = new PaintObservation.Box(20.25, 30.5, 16, 16);
    private PaintCaptureFixtures() { }

    /** Запрос единичного опыта. */
    static PaintCaptureRequest request() {
        return new PaintCaptureRequest(RUN, CAPTURE, 3, "paint", "normal", 1, HASH,
                Map.of("now", PaintCaptureRequest.CardState.NORMAL), 1000);
    }

    /** Идентичность наблюдения. */
    static PaintObservation.Identity identity() {
        return new PaintObservation.Identity(RUN, CAPTURE, 3, "fx", "paint", "normal", 1, HASH);
    }

    /** Стабильные поколения с отрицательным nanoTime. */
    static PaintObservation.Synchronization sync() {
        return new PaintObservation.Synchronization("fx-scene", 7, 7, 8, 8, 9, 9, 10, 10,
                "scene-1/frame-7", HASH, HASH, -100, -50, true, List.of());
    }

    /** Ресурс с реальными проверяемыми PNG-байтами unit-фикстуры. */
    static PaintObservation.Asset asset() {
        byte[] bytes = png(16, 16);
        return new PaintObservation.Asset("asset-1", bytes.length, PaintObservationCodec.sha256(bytes), bytes,
                "classpath:/unit.png", 16, 16, PaintObservationCodec.sha256(new byte[16 * 16 * 4]),
                "decode-capture", "image-1");
    }

    /** Фактические поля unit-рисования, без предположения об ожидаемой альфа. */
    static PaintObservation.Icon icon(String instance, String assetId, String source) {
        return new PaintObservation.Icon(instance, "button-1", "tb.undo", "glyph", "root/toolbar/button/image",
                "image-node", assetId, source, "undo", null, 0xff57606a,
                new PaintObservation.Box(20, 30, 16, 16), TRANSFORM, BOX,
                List.of(new PaintObservation.Point(20.25, 30.5), new PaintObservation.Point(36.25, 30.5),
                        new PaintObservation.Point(36.25, 46.5), new PaintObservation.Point(20.25, 46.5)),
                new PaintObservation.Box(0, 0, 16, 16), List.of(), false, true, 2,
                List.of(new PaintObservation.OpacityFactor("button-1", 0.55, "node-opacity")),
                0.55, "src-over", List.of(), List.of(), "normal", 9, 0, true);
    }

    /** Карточка сохраняет ошибочную квадратную заливку и эллиптическую рамку как actual. */
    static PaintObservation.Card card() {
        var square = new PaintObservation.Radius(0, 0, false, false, "px", 0, 0);
        var ellipse = new PaintObservation.Radius(10, 20, true, true, "%", 6, 8);
        var insets = new PaintObservation.Insets(0, 0, 0, 0);
        var backgrounds = List.of(new PaintObservation.BackgroundLayer(0xffffffff, insets,
                List.of(square, square, square, square), "actual-fill"));
        var borders = List.of(new PaintObservation.BorderLayer(List.of(0xffd0d7de, 1, 2, 3),
                List.of(1.0, 2.0, 3.0, 4.0), List.of("solid", "solid", "solid", "solid"), insets,
                List.of(ellipse, ellipse, ellipse, ellipse), List.of(square, square, square, square),
                "centered", "actual-stroke"));
        return new PaintObservation.Card("card-1", "now", BOX, TRANSFORM, BOX, true, false, false,
                List.of(), List.of(), backgrounds, borders, true, false, null, false, null, true,
                "actual-fill", "actual-stroke", 9);
    }

    /** Полное наблюдение с дробной геометрией. */
    static PaintObservation observation() { return observation(sync(), List.of(asset()), List.of(icon("icon-1", "asset-1", "image-1")), List.of(card()), List.of()); }

    /** Вариант для отрицательных опытов. */
    static PaintObservation observation(PaintObservation.Synchronization synchronization,
                                        List<PaintObservation.Asset> assets, List<PaintObservation.Icon> icons,
                                        List<PaintObservation.Card> cards, List<PaintObservation.Unsupported> unsupported) {
        return new PaintObservation(1, PaintObservation.KIND, identity(),
                new PaintObservation.Viewport(1200, 800, 1200, 800, TRANSFORM,
                        new PaintObservation.Point(-100.25, 20.5), 1, 1, null, "root-1"),
                new PaintObservation.Environment("unit-os", "unit-runtime", "unit-renderer", 1, "loaded", Map.of("core.jar", HASH)),
                synchronization, new PaintObservation.Interaction(new PaintObservation.Point(21, 31), "pointer", null, "root-1", 4, true),
                List.of(new PaintObservation.Surface("root-1", "root", new PaintObservation.Point(-100, 20),
                        new PaintObservation.Point(0, 0), new PaintObservation.Box(0, 0, 1200, 800), 0, true, false)),
                assets, icons, cards, unsupported);
    }

    /** Минимальный raw-дамп прежней схемы. */
    static UiDump raw(String client, String step) {
        return new UiDump(1, client, "paint", step, new UiDump.Frame("", "os", null, 1200, 800, Map.of()),
                List.of(), null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
    }

    /** Создаёт unit-PNG без AWT или FX, с настоящими deflate и CRC. */
    static byte[] png(int width, int height) {
        return png(width, height, 0);
    }

    /** Дополняет PNG ограниченным вспомогательным чанком для проверки суммарного лимита. */
    static byte[] png(int width, int height, int padding) {
        try {
            var result = new ByteArrayOutputStream();
            result.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            var headerBytes = new ByteArrayOutputStream(); var header = new DataOutputStream(headerBytes);
            header.writeInt(width); header.writeInt(height); header.write(new byte[] {8, 6, 0, 0, 0});
            chunk(result, "IHDR", headerBytes.toByteArray());
            if (padding > 0) chunk(result, "cpAD", new byte[padding]);
            var compressed = new ByteArrayOutputStream();
            try (var deflate = new DeflaterOutputStream(compressed)) {
                byte[] row = new byte[1 + width * 4];
                for (int y = 0; y < height; y++) deflate.write(row);
            }
            chunk(result, "IDAT", compressed.toByteArray()); chunk(result, "IEND", new byte[0]);
            return result.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    /** Записывает PNG-чанк с контрольной суммой. */
    private static void chunk(ByteArrayOutputStream target, String type, byte[] data) throws IOException {
        byte[] name = type.getBytes(StandardCharsets.US_ASCII); var output = new DataOutputStream(target);
        output.writeInt(data.length); output.write(name); output.write(data);
        var crc = new CRC32(); crc.update(name); crc.update(data); output.writeInt((int) crc.getValue());
    }
}
