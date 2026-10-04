package ru.cashprediction.core.ui.token;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.InflaterInputStream;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;

/** Проверки единого каталога значков, формата файлов и неизменности исходного брендинга. */
class UiIconsTest {
    private static final Set<ColorToken> COLORS = Set.of(ColorToken.ACCENT, ColorToken.WHATIF,
            ColorToken.EXPENSE, ColorToken.INCOME, ColorToken.TEXT_PRIMARY, ColorToken.TEXT_MUTED,
            ColorToken.WARN, ColorToken.TOOLTIP_TEXT, ColorToken.TEXT_PAST);

    @Test
    void coversEverySemanticGlyphAndExtraControl() {
        Set<String> expected = new HashSet<>(DesignTokens.GLYPHS);
        expected.addAll(Set.of("‹", "›", "folder", "search"));
        Set<String> basenames = new HashSet<>();
        for (String key : expected) {
            String url = UiIcons.manifest().get(key);
            basenames.add(url.substring("/app/icons/".length(), url.length() - 4));
        }
        assertEquals(29, basenames.size());
        for (String basename : basenames) {
            for (ColorToken color : COLORS) {
                expected.add(basename + "-" + color.name().toLowerCase(Locale.ROOT));
            }
        }
        assertEquals(expected, UiIcons.manifest().keySet());
        assertEquals(292, UiIcons.manifest().size());
        assertEquals(290, new HashSet<>(UiIcons.manifest().values()).size());
        for (String key : expected) {
            String url = UiIcons.manifest().get(key);
            assertTrue(url.matches("/app/icons/[a-z]+(?:[-_][a-z]+)*\\.png"), url);
            String filename = url.substring("/app/icons/".length());
            assertArrayEquals(UiIcons.resource(filename).orElseThrow(), UiIcons.png(key).orElseThrow());
            assertArrayEquals(UiIcons.png(key).orElseThrow(),
                    UiIcons.png(filename.substring(0, filename.length() - 4)).orElseThrow());
        }
    }

    @Test
    void contextVariantsKeepGeometryAndExactTokenPalette() throws Exception {
        Set<String> keys = new HashSet<>(DesignTokens.GLYPHS);
        keys.addAll(Set.of("‹", "›", "folder", "search"));
        for (String key : keys) {
            String url = UiIcons.manifest().get(key);
            String basename = url.substring("/app/icons/".length(), url.length() - 4);
            byte[] original = rgbaPixels(UiIcons.png(key).orElseThrow());
            for (ColorToken color : COLORS) {
                String alias = basename + "-" + color.name().toLowerCase(Locale.ROOT);
                assertEquals("/app/icons/" + alias + ".png", UiIcons.manifest().get(alias));
                byte[] bytes = UiIcons.png(key, color).orElseThrow();
                assertArrayEquals(bytes, UiIcons.png(basename, color).orElseThrow());
                assertArrayEquals(bytes, UiIcons.png(alias).orElseThrow());
                byte[] pixels = rgbaPixels(bytes);
                int rgb = Integer.parseInt(color.hex().substring(1), 16);
                for (int i = 0; i < pixels.length; i += 4) {
                    assertEquals(original[i + 3], pixels[i + 3], alias + " alpha " + i);
                    if (Byte.toUnsignedInt(pixels[i + 3]) == 255) {
                        int actual = Byte.toUnsignedInt(pixels[i]) << 16
                                | Byte.toUnsignedInt(pixels[i + 1]) << 8
                                | Byte.toUnsignedInt(pixels[i + 2]);
                        assertEquals(rgb, actual, alias + " RGB " + i);
                    }
                }
            }
        }
    }

    @Test
    void unavailableColorsAndVariantsFallBackToOriginal() {
        for (ColorToken color : ColorToken.values()) {
            if (!COLORS.contains(color)) {
                assertArrayEquals(UiIcons.png("✓").orElseThrow(), UiIcons.png("✓", color).orElseThrow());
            }
        }
        assertArrayEquals(UiIcons.png("folder").orElseThrow(), UiIcons.png("folder", null).orElseThrow());
        assertArrayEquals(UiIcons.png("application").orElseThrow(),
                UiIcons.png("application", ColorToken.ACCENT).orElseThrow());
        assertArrayEquals(UiIcons.png("folder-accent").orElseThrow(),
                UiIcons.png("folder-accent", ColorToken.INCOME).orElseThrow());
        assertTrue(UiIcons.png(null, ColorToken.ACCENT).isEmpty());
        assertTrue(UiIcons.png("unknown", ColorToken.ACCENT).isEmpty());
    }

    @Test
    void sharesFilesForIdenticalShapes() {
        assertEquals(UiIcons.manifest().get("✕"), UiIcons.manifest().get("✗"));
        assertEquals(UiIcons.manifest().get("▸"), UiIcons.manifest().get("›"));
        assertNotEquals(UiIcons.manifest().get("✕"), UiIcons.manifest().get("✖"));
    }

    @Test
    void manifestCannotBeChangedThroughAnyView() {
        Map<String, String> manifest = UiIcons.manifest();
        assertThrows(UnsupportedOperationException.class, () -> manifest.put("other", "/app/icons/other.png"));
        assertThrows(UnsupportedOperationException.class, () -> manifest.keySet().remove("folder"));
        assertThrows(UnsupportedOperationException.class, () -> manifest.values().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.entrySet().iterator().next().setValue("/app/icons/other.png"));
    }

    @Test
    void returnsIndependentByteArrays() {
        byte[] expected = UiIcons.png("folder").orElseThrow();
        byte[] changed = UiIcons.png("folder").orElseThrow();
        changed[0] = 0;
        assertArrayEquals(expected, UiIcons.png("folder").orElseThrow());
        byte[] resource = UiIcons.resource("folder.png").orElseThrow();
        resource[0] = 0;
        assertArrayEquals(expected, UiIcons.resource("folder.png").orElseThrow());
        byte[] variant = UiIcons.png("folder", ColorToken.WHATIF).orElseThrow();
        byte[] variantExpected = variant.clone();
        variant[0] = 0;
        assertArrayEquals(variantExpected, UiIcons.png("folder", ColorToken.WHATIF).orElseThrow());
        byte[] branding = UiIcons.applicationPng();
        branding[0] = 0;
        assertEquals((byte) 0x89, UiIcons.applicationPng()[0]);
        byte[] ico = UiIcons.resource("application.ico").orElseThrow();
        ico[0] = 127;
        assertEquals(0, UiIcons.resource("application.ico").orElseThrow()[0]);
    }

    @Test
    void rejectsUnknownNamesAndEveryPathSyntax() {
        for (String filename : new String[] {
                "", "../application.png", "./folder.png", "icons/folder.png", "icons\\folder.png",
                "/folder.png", "/app/icons/folder.png", "C:\\folder.png", "folder.PNG",
                "folder.png?x=1", "folder.png#x", "%2e%2e%2ffolder.png", "unknown.png",
                "folder.png\0", "application.svg", "icon-256.png", "folder-ACCENT.png",
                "folder-bg_window.png", "folder-text-muted.png", "../folder-accent.png",
                "folder-accent.png?x=1", "folder-accent.png#x", "folder-accent.png\0"
        }) {
            assertTrue(UiIcons.resource(filename).isEmpty(), filename);
            assertTrue(UiIcons.png(filename, ColorToken.ACCENT).isEmpty(), filename);
        }
        assertTrue(UiIcons.resource(null).isEmpty());
        assertTrue(UiIcons.png(null).isEmpty());
        assertTrue(UiIcons.png("unknown").isEmpty());
        assertTrue(UiIcons.png("../application").isEmpty());
        assertTrue(UiIcons.png("folder.png").isEmpty());
    }

    @Test
    void interfacePngsHaveValidHeadersPixelsAndSharedResolution() throws Exception {
        Set<String> filenames = new HashSet<>();
        UiIcons.manifest().values().forEach(url -> filenames.add(url.substring("/app/icons/".length())));
        for (String filename : filenames) {
            byte[] bytes = UiIcons.resource(filename).orElseThrow();
            assertArrayEquals(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10},
                    java.util.Arrays.copyOf(bytes, 8), filename);
            ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
            assertEquals(13, header.getInt(8), filename);
            assertEquals(0x49484452, header.getInt(12), filename);
            assertEquals(64, header.getInt(16), filename);
            assertEquals(64, header.getInt(20), filename);
            assertEquals(8, bytes[24], filename);
            assertEquals(6, bytes[25], filename);
            byte[] pixels = rgbaPixels(bytes);
            boolean transparent = false, opaque = false, antialiased = false;
            for (int y = 0; y < 64; y++) {
                for (int x = 0; x < 64; x++) {
                    int alpha = Byte.toUnsignedInt(pixels[(y * 64 + x) * 4 + 3]);
                    transparent |= alpha == 0;
                    opaque |= alpha == 255;
                    antialiased |= alpha > 0 && alpha < 255;
                    if (x == 0 || x == 63 || y == 0 || y == 63) {
                        assertEquals(0, alpha, filename);
                    }
                }
            }
            assertTrue(transparent && opaque && antialiased, filename);
        }
    }

    @Test
    void keepsBrandingBytesAndValidPngAndIcoHeaders() throws Exception {
        byte[] png = UiIcons.applicationPng();
        assertEquals("1ff938cba8531be23e9048420e137d2c0c2c077511672c4b321ecc15316f7537",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)));
        assertArrayEquals(png, UiIcons.resource("application.png").orElseThrow());
        ByteBuffer pngHeader = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
        assertEquals(256, pngHeader.getInt(16));
        assertEquals(256, pngHeader.getInt(20));
        byte[] ico = UiIcons.resource("application.ico").orElseThrow();
        assertEquals("e7bad3044548d717126a9bb615983477ce01dca643451a81ae2e6efbbdec3cd3",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ico)));
        ByteBuffer header = ByteBuffer.wrap(ico).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, header.getShort(0));
        assertEquals(1, header.getShort(2));
        assertTrue(header.getShort(4) > 0);
    }

    @Test
    void physicalCatalogContainsExactlyWhitelistedFiles() throws Exception {
        Set<String> expected = new HashSet<>();
        UiIcons.manifest().values().forEach(url -> expected.add(url.substring("/app/icons/".length())));
        expected.addAll(Set.of("application.png", "application.ico"));
        try (var paths = Files.list(projectRoot().resolve(
                "core/src/main/resources/ru/cashprediction/core/ui/icons"))) {
            Set<String> actual = new HashSet<>();
            paths.forEach(path -> actual.add(path.getFileName().toString()));
            assertEquals(expected, actual);
        }
    }

    /** Читает RGBA PNG средствами java.base, не добавляя ядру зависимость java.desktop. */
    private static byte[] rgbaPixels(byte[] png) throws Exception {
        ByteBuffer input = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        int offset = 8;
        while (offset < png.length) {
            int length = input.getInt(offset);
            assertTrue(length >= 0 && (long) offset + length + 12 <= png.length);
            if (input.getInt(offset + 4) == 0x49444154) {
                compressed.write(png, offset + 8, length);
            }
            offset += length + 12;
        }
        byte[] filtered;
        try (var inflater = new InflaterInputStream(new ByteArrayInputStream(compressed.toByteArray()))) {
            filtered = inflater.readAllBytes();
        }
        int stride = 64 * 4;
        assertEquals((stride + 1) * 64, filtered.length);
        byte[] pixels = new byte[stride * 64];
        for (int y = 0; y < 64; y++) {
            int filter = Byte.toUnsignedInt(filtered[y * (stride + 1)]);
            assertTrue(filter <= 4);
            for (int x = 0; x < stride; x++) {
                int position = y * stride + x;
                int left = x < 4 ? 0 : Byte.toUnsignedInt(pixels[position - 4]);
                int up = y == 0 ? 0 : Byte.toUnsignedInt(pixels[position - stride]);
                int corner = y == 0 || x < 4 ? 0 : Byte.toUnsignedInt(pixels[position - stride - 4]);
                int predictor = switch (filter) {
                    case 0 -> 0;
                    case 1 -> left;
                    case 2 -> up;
                    case 3 -> (left + up) / 2;
                    case 4 -> paeth(left, up, corner);
                    default -> throw new AssertionError(filter);
                };
                pixels[position] = (byte) (filtered[y * (stride + 1) + x + 1] + predictor);
            }
        }
        return pixels;
    }

    /** Восстанавливает PNG-фильтр Paeth с порядком разрешения равных расстояний по стандарту. */
    private static int paeth(int left, int up, int corner) {
        int value = left + up - corner;
        int a = Math.abs(value - left), b = Math.abs(value - up), c = Math.abs(value - corner);
        return a <= b && a <= c ? left : b <= c ? up : corner;
    }

    private static Path projectRoot() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isRegularFile(path.resolve("dist/icons/GenerateUiIcons.java"))) {
            path = path.getParent();
        }
        return java.util.Objects.requireNonNull(path);
    }
}
