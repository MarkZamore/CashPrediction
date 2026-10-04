package ru.cashprediction.parity.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.UiIcons;

/** Проверяет физические PNG и добавочный контракт цвета без запуска клиентов и GUI. */
class SharedIconContractTest {
    private static final List<ColorToken> COLORS = List.of(ColorToken.ACCENT, ColorToken.WHATIF,
            ColorToken.EXPENSE, ColorToken.INCOME, ColorToken.TEXT_PRIMARY, ColorToken.TEXT_MUTED,
            ColorToken.WARN, ColorToken.TOOLTIP_TEXT, ColorToken.TEXT_PAST);

    /** Прежние семантические ключи остаются и разрешаются в реальные PNG ядра. */
    @Test void retainedKeysResolveToPhysicalCorePngs() throws Exception {
        for (String key : List.of("\u21b6", "\u21b7", "\u25be", "\u25b8", "\u2715", "\u2713",
                "\u2717", "\u25cf", "\u25c0", "\u25b6", "\u25a6", "\u2191", "\u270e", "\u2192",
                "\u21c4", "\u2261", "\u0394", "\u20bd", "\u2699", "\u21bb", "\u25ce", "\u21e9",
                "\u27f2", "\u2139", "\u26a0", "\u2716", "?", "\u2039", "\u203a", "folder", "search")) {
            String route = UiIcons.manifest().get(key);
            assertNotNull(route, key);
            assertTrue(route.matches("/app/icons/[a-z0-9-]+\\.png"), route);
            byte[] bytes = UiIcons.resource(route.substring("/app/icons/".length())).orElseThrow();
            assertArrayEquals(bytes, UiIcons.png(key).orElseThrow(), key);
            assertNotNull(ImageIO.read(new ByteArrayInputStream(bytes)), key);
        }
    }

    /** Все девять цветов имеют ASCII-псевдонимы без расширения и физические пиксели нужного цвета. */
    @Test void colorVariantsArePhysicalResourcesWithFrozenAliases() throws Exception {
        var originals = UiIcons.manifest().entrySet().stream()
                .filter(entry -> !entry.getKey().matches("[a-z0-9-]+-(accent|whatif|expense|income|text_primary|text_muted|warn|tooltip_text|text_past)"))
                .toList();
        assertFalse(originals.isEmpty());
        for (var entry : originals) {
            String basename = entry.getValue().substring("/app/icons/".length()).replaceFirst("\\.png$", "");
            for (ColorToken color : COLORS) {
                String alias = basename + "-" + color.name().toLowerCase(Locale.ROOT);
                String route = "/app/icons/" + alias + ".png";
                assertEquals(route, UiIcons.manifest().get(alias), entry.getKey() + ": " + color);
                assertFalse(UiIcons.manifest().containsKey(alias + ".png"), alias);
                byte[] physical = UiIcons.resource(alias + ".png").orElseThrow();
                assertArrayEquals(physical, UiIcons.png(entry.getKey(), color).orElseThrow(), alias);
                assertArrayEquals(physical, UiIcons.png(alias).orElseThrow(), alias);
                var image = ImageIO.read(new ByteArrayInputStream(physical));
                assertNotNull(image, alias);
                var original = ImageIO.read(new ByteArrayInputStream(UiIcons.png(entry.getKey()).orElseThrow()));
                assertEquals(original.getWidth(), image.getWidth(), alias);
                assertEquals(original.getHeight(), image.getHeight(), alias);
                int visible = 0;
                int opaque = 0;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int pixel = image.getRGB(x, y);
                        int alpha = pixel >>> 24;
                        assertEquals(original.getRGB(x, y) >>> 24, alpha, alias + ": alpha");
                        if (alpha == 0) continue;
                        visible++;
                        if (alpha == 255) {
                            opaque++;
                            assertEquals(color.argb() & 0xffffff, pixel & 0xffffff, alias);
                        }
                        // Цвет проверяется на непрозрачном штрихе; сглаженные пересечения округляют каналы.
                    }
                }
                assertTrue(visible > 0, alias);
                assertTrue(opaque > 0, alias);
            }
        }
    }

    /** Неизвестный ключ остаётся пустым, неподдерживаемый цвет возвращает исходный PNG независимо. */
    @Test void unknownAndUnavailableColorsKeepSafeFallbackAndIndependentBytes() throws Exception {
        assertTrue(UiIcons.png("unknown-icon", ColorToken.ACCENT).isEmpty());
        assertTrue(UiIcons.png(null, ColorToken.ACCENT).isEmpty());
        for (ColorToken color : new ColorToken[] {null, ColorToken.BG_WINDOW}) {
            byte[] first = UiIcons.png("\u21b6", color).orElseThrow();
            assertArrayEquals(UiIcons.png("\u21b6").orElseThrow(), first);
            Arrays.fill(first, (byte) 0);
            assertArrayEquals(UiIcons.png("\u21b6").orElseThrow(), UiIcons.png("\u21b6", color).orElseThrow());
        }
        byte[] variant = UiIcons.png("\u21b6", ColorToken.ACCENT).orElseThrow();
        byte[] expected = variant.clone();
        Arrays.fill(variant, (byte) 0);
        assertArrayEquals(expected, UiIcons.png("\u21b6", ColorToken.ACCENT).orElseThrow());
    }
}
