package ru.cashprediction.core.ui.selftest.paint;

import java.util.Arrays;
import java.util.Objects;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.json.UiJson;

/**
 * Стабильная пара исходного UiDump схемы 1, оригинального PNG и companion-наблюдения.
 * Конструктор проверяет контракт данных, но не заменяет честный сборщик фактической краски.
 * Модель и unsupported не могут создать успешную пару. Сохранение файлов принадлежит интегратору.
 * @param raw ненормализованный дамп настоящих виджетов
 * @param png оригинальные байты снимка
 * @param observation наблюдения того же захвата
 */
public record WidgetCapture(UiDump raw, byte[] png, PaintObservation observation) {
    /** Проверяет идентичность пары и стабильность барьера, копирует PNG. */
    public WidgetCapture {
        Objects.requireNonNull(raw); Objects.requireNonNull(observation); Objects.requireNonNull(png);
        PaintObservation.require(png.length <= PaintObservationCodec.MAX_PNG_BYTES, "PNG limit"); png = png.clone();
        var identity = observation.identity(); var viewport = observation.viewport();
        PaintObservation.require(raw.schema() == UiDump.SCHEMA && raw.client().equals(identity.client())
                && raw.scenario().equals(identity.scenario()) && raw.step().equals(identity.step()), "raw identity");
        PaintObservation.require(raw.frame() != null && raw.frame().contentWidth() == viewport.logicalWidth()
                && raw.frame().contentHeight() == viewport.logicalHeight(), "raw viewport");
        PaintObservation.require(observation.synchronization().stable() && observation.unsupported().isEmpty(), "unsupported/unstable capture");
        String mode = switch (identity.client()) { case "fx" -> "fx-scene"; case "swing" -> "bracketed-screen"; case "web" -> "bracketed-cdp"; default -> throw new IllegalArgumentException("client"); };
        PaintObservation.require(mode.equals(observation.synchronization().mode()), "client synchronization mode");
        PaintObservationCodec.pngDimensions(png, viewport.pngWidth(), viewport.pngHeight());
    }

    /** Возвращает копию оригинального PNG. */
    @Override public byte[] png() { return png.clone(); }

    /** Проверяет принадлежность пары текущему запросу, включая новую попытку и план. */
    public void requireRequest(PaintCaptureRequest request) {
        Objects.requireNonNull(request); var id = observation.identity();
        PaintObservation.require(id.runId().equals(request.runId()) && id.captureId().equals(request.captureId())
                && id.commandNumber() == request.commandNumber() && id.scenario().equals(request.scenario())
                && id.step().equals(request.step()) && id.attempt() == request.attempt()
                && id.planSha256().equals(request.planSha256()), "capture request identity");
    }

    /** Записывает исходный дамп существующим сериализатором, не меняя его схему или геометрию. */
    public byte[] rawJson() { return JsonWriter.write(UiJson.toTree(raw)).getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    /**
     * Создаёт манифест точных сохраняемых байтов после проверки их содержимого и запроса.
     * Интегратор пишет raw/png/paint во временные соседние файлы, затем commit последним.
     * Сам по себе этот метод не удостоверяет запись на диск и не разрешает повторный captureId.
     */
    public Commit commit(PaintCaptureRequest request, byte[] rawBytes, byte[] paintBytes) {
        requireRequest(request);
        PaintObservation.require(Objects.equals(JsonParser.parse(PaintObservationCodec.utf8(rawBytes)),
                JsonParser.parse(PaintObservationCodec.utf8(rawJson()))), "persisted raw differs");
        PaintObservation.require(Arrays.equals(PaintObservationCodec.write(observation),
                PaintObservationCodec.write(PaintObservationCodec.read(paintBytes))), "persisted paint differs");
        return new Commit(1, "widget-paint-capture", observation.identity(), digest(rawBytes), digest(png), digest(paintBytes));
    }

    private static FileDigest digest(byte[] bytes) { return new FileDigest(bytes.length, PaintObservationCodec.sha256(bytes)); }

    /** Хеш и размер точных байтов файла, включая исходные пробелы JSON. */
    public record FileDigest(int byteLength, String sha256) {
        /** Проверяет ограничение размера и формат хеша. */
        public FileDigest { PaintObservation.require(byteLength > 0 && byteLength <= PaintObservationCodec.MAX_JSON_BYTES, "file length"); PaintObservation.hash(sha256); }
    }

    /** Commit-манифест четырёхфайлового набора; identity повторяет всю идентичность опыта. */
    public record Commit(int schema, String kind, PaintObservation.Identity identity,
                         FileDigest raw, FileDigest png, FileDigest paint) {
        /** Проверяет версию и обязательные хеши. */
        public Commit {
            PaintObservation.require(schema == 1 && "widget-paint-capture".equals(kind), "capture schema/kind");
            Objects.requireNonNull(identity); Objects.requireNonNull(raw); Objects.requireNonNull(png); Objects.requireNonNull(paint);
        }

        /** Проверяет конкретные файлы и запрос при чтении сохранённого набора; частичный набор не проходит. */
        public void verify(PaintCaptureRequest request, WidgetCapture capture, byte[] rawBytes, byte[] paintBytes) {
            PaintObservation.require(equals(capture.commit(request, rawBytes, paintBytes)), "capture commit mismatch");
        }
    }
}
