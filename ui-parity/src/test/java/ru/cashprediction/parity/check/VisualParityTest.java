package ru.cashprediction.parity.check;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import ru.cashprediction.core.ui.dump.UiDump;

/** Проверяет геометрию и ΔE на синтетических PNG; настоящие изображения подключаются в S3. */
class VisualParityTest {
    /** Проверяет строгий цветовой предел и чтение центра PNG. */
    @Test void syntheticPngColors() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++) image.setRGB(x, y, 0xf6f8fa);
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        var box = new UiDump.Box(0, 0, 8, 8);
        VisualChecks.regionColor(bytes.toByteArray(), box, 0xf6f8fa);
        assertThrows(AssertionError.class, () -> VisualChecks.regionColor(bytes.toByteArray(), box, 0));
        assertEquals(100, VisualChecks.deltaE(0, 0xffffff), .01);
        assertTrue(VisualChecks.deltaE(0xf6f8fa, 0xf5f7f9) < 6);
    }
    /** Проверяет включённую границу 4 px и предел 3 px для базовой линии. */
    @Test void positionsHaveFiniteInclusiveTolerance() {
        var box = new UiDump.Box(10, 20, 30, 40);
        VisualChecks.position(box, new UiDump.Box(14, 16, 34, 36), 4);
        assertThrows(AssertionError.class, () -> VisualChecks.position(box, new UiDump.Box(15, 20, 30, 40), 4));
        assertThrows(AssertionError.class, () -> VisualChecks.position(box, new UiDump.Box(10, 24, 30, 40), 3));
        assertThrows(AssertionError.class, () -> VisualChecks.position(box, new UiDump.Box(Double.NaN, 20, 30, 40), 4));
    }
}
