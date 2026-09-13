package ru.cashprediction.parity.browser;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Размеры PNG из заголовка IHDR без декодирования изображения.
 *
 * @param width  ширина в пикселях
 * @param height высота в пикселях
 */
public record PngHeader(int width, int height) {

    /** Подпись PNG (RFC 2083). */
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /**
     * Читает ширину и высоту из первого фрагмента IHDR.
     *
     * @param png байты файла
     * @return размеры
     * @throws IllegalArgumentException если это не PNG или заголовок повреждён
     */
    public static PngHeader read(byte[] png) {
        // Подпись 8 байт, длина фрагмента 4, тип «IHDR» 4, затем ширина и высота по 4 байта (big-endian).
        if (png == null || png.length < 24 || !Arrays.equals(Arrays.copyOf(png, 8), SIGNATURE)) {
            throw new IllegalArgumentException("Not a PNG image");
        }
        ByteBuffer buffer = ByteBuffer.wrap(png);
        if (buffer.get(12) != 'I' || buffer.get(13) != 'H' || buffer.get(14) != 'D' || buffer.get(15) != 'R') {
            throw new IllegalArgumentException("PNG does not start with an IHDR chunk");
        }
        return new PngHeader(buffer.getInt(16), buffer.getInt(20));
    }
}
