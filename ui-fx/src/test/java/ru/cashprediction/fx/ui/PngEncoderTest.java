package ru.cashprediction.fx.ui;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.text.Texts;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Узкие проверки локализованной ошибки и структуры PNG без запуска окон. */
class PngEncoderTest {

    /** Повреждённое изображение даёт прежнее сообщение из общего каталога. */
    @Test
    void unreadableImageUsesSharedLocalizedMessage() {
        Image image = new Image(new ByteArrayInputStream(new byte[0]));
        assertTrue(image.isError());
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PngEncoder.encode(image));
        assertEquals(Texts.get("s2.file.pngEmpty"), error.getMessage());
        assertEquals("Изображение пустое: сохранять нечего", error.getMessage());
    }

    /** Проверяет сигнатуру, заголовок, CRC всех блоков и точные RGBA-пиксели. */
    @Test
    void encodesValidPngWithoutChangingPixels() throws Exception {
        WritableImage image = new WritableImage(2, 1);
        image.getPixelWriter().setArgb(0, 0, 0xffff0000);
        image.getPixelWriter().setArgb(1, 0, 0x00000000);
        byte[] png = PngEncoder.encode(image);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(png))) {
            assertArrayEquals(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, in.readNBytes(8));
            List<String> chunks = new ArrayList<>();
            while (in.available() > 0) {
                int length = in.readInt();
                byte[] type = in.readNBytes(4);
                byte[] data = in.readNBytes(length);
                assertEquals(length, data.length);
                CRC32 crc = new CRC32();
                crc.update(type);
                crc.update(data);
                assertEquals((int) crc.getValue(), in.readInt());
                String name = new String(type, StandardCharsets.US_ASCII);
                chunks.add(name);
                switch (name) {
                    case "IHDR" -> {
                        try (DataInputStream header = new DataInputStream(new ByteArrayInputStream(data))) {
                            assertEquals(2, header.readInt());
                            assertEquals(1, header.readInt());
                            assertArrayEquals(new byte[]{8, 6, 0, 0, 0}, header.readAllBytes());
                        }
                    }
                    case "IDAT" -> {
                        try (InflaterInputStream rows = new InflaterInputStream(new ByteArrayInputStream(data))) {
                            assertArrayEquals(new byte[]{0, (byte) 255, 0, 0, (byte) 255, 0, 0, 0, 0},
                                    rows.readAllBytes());
                        }
                    }
                    case "IEND" -> assertEquals(0, data.length);
                    default -> fail("Unexpected PNG chunk: " + name);
                }
            }
            assertEquals(List.of("IHDR", "IDAT", "IEND"), chunks);
        }
    }
}
