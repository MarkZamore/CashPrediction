package ru.cashprediction.parity.browser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Чтение размеров PNG из заголовка на синтетических изображениях ImageIO (JDK).
 */
class PngHeaderTest {

    @Test
    void readsWidthAndHeightFromIhdr() throws IOException {
        BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);

        assertEquals(new PngHeader(1200, 800), PngHeader.read(out.toByteArray()));
    }

    @Test
    void rejectsNonPngBytes() {
        assertThrows(IllegalArgumentException.class, () -> PngHeader.read("<!doctype html><html></html>".getBytes()));
        assertThrows(IllegalArgumentException.class, () -> PngHeader.read(new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> PngHeader.read(null));
    }
}
