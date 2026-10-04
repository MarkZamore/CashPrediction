package ru.cashprediction.swing.ui;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.PixelGrabber;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.UiIcons;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет sidecar настоящего decode/scaling без запуска окон или выдачи полного paint census. */
class SwingIconProvenanceTest {
    @Test void cachedDecodeRetainsExactBytesAndActualImageIdentity() throws Exception {
        ImageIcon icon = SwingIcons.icon("search");
        var source = SwingIcons.decodedSource(icon.getImage()).orElseThrow();
        assertSame(icon, SwingIcons.icon("search"));
        assertSame(source, SwingIcons.decodedSource(icon.getImage()).orElseThrow());
        assertArrayEquals(UiIcons.png("search").orElseThrow(), source.bytes());
        assertEquals("UiIcons.png(search)", source.source());
        assertEquals(1, source.chain().size());
        assertNull(source.chain().getFirst().scalingHints());
        assertEquals(source.sourceObjectIdentity(), source.chain().getFirst().sourceObjectIdentity());
        assertActualPixels(icon.getImage(), source.chain().getFirst());
    }

    @Test void retainedBytesAndChainCannotBeChangedByCaller() {
        byte[] bytes = UiIcons.png("search").orElseThrow();
        byte[] original = bytes.clone();
        ImageIcon icon = SwingIcons.decode(bytes, "fixture:search");
        bytes[0] ^= 1;
        var source = SwingIcons.decodedSource(icon.getImage()).orElseThrow();
        byte[] returned = source.bytes(); returned[1] ^= 1;
        assertArrayEquals(original, source.bytes());
        assertThrows(UnsupportedOperationException.class, () -> source.chain().clear());
        assertSame(source, SwingIcons.decodedSource(icon.getImage()).orElseThrow());
    }

    @Test void equalDecodedBytesDoNotMergeIndependentSourceObjects() {
        byte[] bytes = UiIcons.png("search").orElseThrow();
        Image first = SwingIcons.decode(bytes, "fixture:first").getImage();
        Image second = SwingIcons.decode(bytes, "fixture:second").getImage();
        var a = SwingIcons.decodedSource(first).orElseThrow();
        var b = SwingIcons.decodedSource(second).orElseThrow();
        assertNotEquals(a.sourceObjectIdentity(), b.sourceObjectIdentity());
        assertEquals(a.chain().getFirst().argbSha256(), b.chain().getFirst().argbSha256());
        assertEquals("fixture:first", a.source()); assertEquals("fixture:second", b.source());
    }

    @Test void scalingChainRetainsEveryActualIntermediateObjectAndPixels() throws Exception {
        Image original = SwingIcons.decode(UiIcons.png("search").orElseThrow(), "fixture:chain").getImage();
        Image first = SwingIcons.scale(original, 17, 15, Image.SCALE_SMOOTH).getImage();
        Image second = SwingIcons.scale(first, 9, 11, Image.SCALE_FAST).getImage();
        var base = SwingIcons.decodedSource(original).orElseThrow();
        var middle = SwingIcons.decodedSource(first).orElseThrow();
        var source = SwingIcons.decodedSource(second).orElseThrow();
        assertEquals(3, source.chain().size());
        assertEquals(base.chain().getFirst(), source.chain().getFirst());
        assertEquals(middle.chain().getLast(), source.chain().get(1));
        assertEquals(Image.SCALE_SMOOTH, source.chain().get(1).scalingHints().intValue());
        assertEquals(Image.SCALE_FAST, source.chain().getLast().scalingHints().intValue());
        assertArrayEquals(base.bytes(), source.bytes()); assertEquals(base.source(), source.source());
        assertNotEquals(base.sourceObjectIdentity(), source.sourceObjectIdentity());
        assertActualPixels(original, source.chain().getFirst());
        assertActualPixels(first, source.chain().get(1));
        assertActualPixels(second, source.chain().getLast());
    }

    @Test void separateScalingCallsHaveSeparateDerivedIdentities() {
        Image original = SwingIcons.icon("search").getImage();
        Image first = SwingIcons.scale(original, 16, 16, Image.SCALE_SMOOTH).getImage();
        Image second = SwingIcons.scale(original, 16, 16, Image.SCALE_SMOOTH).getImage();
        var a = SwingIcons.decodedSource(first).orElseThrow();
        var b = SwingIcons.decodedSource(second).orElseThrow();
        assertNotSame(first, second);
        assertNotEquals(a.sourceObjectIdentity(), b.sourceObjectIdentity());
        assertEquals(a.chain().getLast().argbSha256(), b.chain().getLast().argbSha256());
        assertEquals(a.chain().getFirst(), b.chain().getFirst());
    }

    @Test void allExistingPngAndScalingEntryPointsRetainLegacyRenderedPixels() throws Exception {
        for (String key : UiIcons.manifest().keySet()) {
            byte[] bytes = UiIcons.png(key).orElseThrow();
            assertArrayEquals(render(new ImageIcon(bytes)), render(SwingIcons.icon(key)), key);
            for (int size : List.of(15, 16, 17)) {
                ImageIcon legacy = new ImageIcon(new ImageIcon(bytes).getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH));
                ImageIcon current = SwingIcons.icon(key, size);
                assertArrayEquals(render(legacy), render(current), key + ":" + size);
                var retained = SwingIcons.decodedSource(current.getImage()).orElseThrow();
                assertArrayEquals(bytes, retained.bytes());
                assertActualPixels(current.getImage(), retained.chain().getLast());
            }
        }
    }

    @Test void colorVariantsAndFallbackKeepLoadedBytesAndPixels() throws Exception {
        for (ColorToken color : List.of(ColorToken.TEXT_PRIMARY, ColorToken.TEXT_MUTED, ColorToken.BG_WINDOW)) {
            byte[] bytes = UiIcons.png("search", color).orElseThrow();
            ImageIcon current = SwingIcons.icon("search", color, 16);
            var retained = SwingIcons.decodedSource(current.getImage()).orElseThrow();
            assertArrayEquals(bytes, retained.bytes());
            ImageIcon legacy = new ImageIcon(new ImageIcon(bytes).getImage().getScaledInstance(16, 16, Image.SCALE_SMOOTH));
            assertArrayEquals(render(legacy), render(current), color.name());
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
            assertActualPixels(decoded, retained.chain().getFirst());
            assertActualPixels(current.getImage(), retained.chain().getLast());
        }
    }

    @Test void brandingRetainsItsOwnDecodeAndLegacyPixels() throws Exception {
        Image image = SwingIcons.application();
        var source = SwingIcons.decodedSource(image).orElseThrow();
        assertArrayEquals(UiIcons.applicationPng(), source.bytes());
        assertEquals("UiIcons.applicationPng()", source.source());
        assertActualPixels(image, source.chain().getFirst());
        assertArrayEquals(render(new ImageIcon(UiIcons.applicationPng())), render(new ImageIcon(image)));
    }

    @Test void replacedImageWithCorrectMarkerNeverReceivesGuessedProvenance() throws Exception {
        ImageIcon installed = new ImageIcon(SwingIcons.icon("search").getImage());
        Image unknown = ImageIO.read(new ByteArrayInputStream(UiIcons.png("search").orElseThrow()));
        JLabel label = new JLabel(installed); label.putClientProperty("cp.icon", "search");
        installed.setImage(unknown);
        assertEquals("search", label.getClientProperty("cp.icon"));
        assertTrue(SwingIcons.decodedSource(installed.getImage()).isEmpty());
        assertTrue(SwingIcons.decodedSource(null).isEmpty());
        Image derived = SwingIcons.scale(unknown, 16, 16, Image.SCALE_SMOOTH).getImage();
        assertEquals(16, derived.getWidth(null));
        assertTrue(SwingIcons.decodedSource(derived).isEmpty());
    }

    @Test void changedDecodedPixelsInvalidateLookupAndFurtherScalingProvenance() {
        BufferedImage image = (BufferedImage) SwingIcons.decode(UiIcons.png("search").orElseThrow(), "fixture:mutable").getImage();
        Image derived = SwingIcons.scale(image, 16, 16, Image.SCALE_SMOOTH).getImage();
        assertTrue(SwingIcons.decodedSource(derived).isPresent());
        image.setRGB(0, 0, image.getRGB(0, 0) ^ 0x00FFFFFF);
        assertTrue(SwingIcons.decodedSource(image).isEmpty());
        assertTrue(SwingIcons.decodedSource(derived).isEmpty());
        Image next = SwingIcons.scale(image, 17, 17, Image.SCALE_SMOOTH).getImage();
        assertTrue(SwingIcons.decodedSource(next).isEmpty());
    }

    @Test void scalingOnEdtRetainsActuallyLoadedDerivedPixels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ImageIcon icon = SwingIcons.icon("search", ColorToken.TEXT_MUTED, 16);
            var retained = SwingIcons.decodedSource(icon.getImage()).orElseThrow();
            assertEquals(2, retained.chain().size());
            try { assertActualPixels(icon.getImage(), retained.chain().getLast()); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    /** Проверяет прямой ARGB и размеры объекта независимо от production-хеширования и рисования. */
    private static void assertActualPixels(Image image, SwingIcons.DecodedImage saved) throws Exception {
        assertEquals(image.getWidth(null), saved.width()); assertEquals(image.getHeight(null), saved.height());
        PixelGrabber grabber = new PixelGrabber(image, 0, 0, saved.width(), saved.height(), true);
        assertTrue(grabber.grabPixels(1000));
        int[] argb = (int[]) grabber.getPixels();
        ByteBuffer bytes = ByteBuffer.allocate(argb.length * Integer.BYTES);
        for (int pixel : argb) bytes.putInt(pixel);
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.array())), saved.argbSha256());
    }

    /** Рисует только лёгкий Icon в память для сравнения с прежним Toolkit decode, без native GUI. */
    private static int[] render(ImageIcon icon) {
        BufferedImage canvas = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        var graphics = canvas.createGraphics();
        try { icon.paintIcon(null, graphics, 0, 0); } finally { graphics.dispose(); }
        return canvas.getRGB(0, 0, canvas.getWidth(), canvas.getHeight(), null, 0, canvas.getWidth());
    }
}
