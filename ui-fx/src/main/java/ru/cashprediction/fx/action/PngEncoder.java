package ru.cashprediction.fx.action;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Кодирует изображение JavaFX в PNG без {@code javax.imageio} и {@code javafx.swing}.
 *
 * <p>Почему свой кодировщик: привычный путь {@code SwingFXUtils.fromFXImage + ImageIO.write} тянет модули
 * {@code javafx.swing} и {@code java.desktop}, а JavaFX-клиент по плану зависит только от {@code javafx.controls}
 * (меньше рантайм jlink, нет AWT в процессе). Формат PNG простой: сигнатура, заголовок {@code IHDR}, сжатые
 * {@code zlib} строки пикселей {@code IDAT} и завершающий {@code IEND}; всё нужное есть в {@code java.util.zip}.</p>
 *
 * <p>Пишется 8-битный RGBA без фильтров строк (фильтр 0) и без чересстрочности — любой просмотрщик открывает
 * такой файл. Класс без состояния, потокобезопасен (само изображение читать нужно в FX Application Thread,
 * если оно ещё показывается на экране).</p>
 */
public final class PngEncoder {

    /** Сигнатура файла PNG (8 байт). */
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    /** Глубина цвета: 8 бит на канал. */
    private static final int BIT_DEPTH = 8;
    /** Тип цвета 6 — RGBA (truecolor с альфа-каналом). */
    private static final int COLOR_TYPE_RGBA = 6;

    private PngEncoder() {
    }

    /**
     * Кодирует изображение в байты PNG.
     *
     * @param image изображение (например, снимок графика {@code node.snapshot(...)})
     * @return содержимое файла PNG
     * @throws IllegalArgumentException если изображение пустое или без доступа к пикселям
     */
    public static byte[] encode(Image image) {
        int width = (int) Math.round(image.getWidth());
        int height = (int) Math.round(image.getHeight());
        PixelReader reader = image.getPixelReader();
        if (width <= 0 || height <= 0 || reader == null) {
            throw new IllegalArgumentException("Изображение пустое: сохранять нечего");
        }
        int[] argb = new int[width * height];
        reader.getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);

        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes(SIGNATURE);
        writeChunk(file, "IHDR", header(width, height));
        writeChunk(file, "IDAT", compressedRows(argb, width, height));
        writeChunk(file, "IEND", new byte[0]);
        return file.toByteArray();
    }

    /** Данные IHDR: ширина, высота, глубина, тип цвета, сжатие 0, фильтр 0, без чересстрочности. */
    private static byte[] header(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(13);
        writeInt(out, width);
        writeInt(out, height);
        out.write(BIT_DEPTH);
        out.write(COLOR_TYPE_RGBA);
        out.write(0);
        out.write(0);
        out.write(0);
        return out.toByteArray();
    }

    /** Строки пикселей в порядке RGBA, каждая с байтом фильтра 0, сжатые zlib. */
    private static byte[] compressedRows(int[] argb, int width, int height) {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        // Deflater держит нативную память zlib: try-with-resources освобождает её сразу, а не при сборке мусора.
        try (Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
             DeflaterOutputStream zlib = new DeflaterOutputStream(compressed, deflater)) {
            byte[] row = new byte[1 + width * 4];
            for (int y = 0; y < height; y++) {
                row[0] = 0; // фильтр «None»: график в основном однотонный и сжимается и так хорошо
                for (int x = 0; x < width; x++) {
                    int pixel = argb[y * width + x];
                    int offset = 1 + x * 4;
                    row[offset] = (byte) (pixel >>> 16);
                    row[offset + 1] = (byte) (pixel >>> 8);
                    row[offset + 2] = (byte) pixel;
                    row[offset + 3] = (byte) (pixel >>> 24);
                }
                zlib.write(row);
            }
        } catch (IOException e) {
            // ByteArrayOutputStream не бросает IOException; ветка нужна только из-за сигнатуры потока.
            throw new UncheckedIOException(e);
        }
        return compressed.toByteArray();
    }

    /** Блок PNG: длина, тип, данные, CRC32 от типа и данных. */
    private static void writeChunk(ByteArrayOutputStream file, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(file, data.length);
        file.writeBytes(typeBytes);
        file.writeBytes(data);
        writeInt(file, (int) crc.getValue());
    }

    /** Целое в порядке «старший байт первым», как требует PNG. */
    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }
}
