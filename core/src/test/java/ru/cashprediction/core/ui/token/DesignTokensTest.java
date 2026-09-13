package ru.cashprediction.core.ui.token;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Токены дизайна против таблиц спецификации v2 (§1.1 палитра, §1.2 шрифты, §1.3 размеры, §1.4 значки)
 * и их представления для web, JavaFX и Swing.
 */
class DesignTokensTest {

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', value = {
        "bg.window     | #F6F8FA", "bg.surface   | #FFFFFF", "bg.card      | #FFFFFF", "bg.alt       | #F0F3F6",
        "text.primary  | #1F2328", "text.muted   | #57606A", "text.past    | #8A8F98", "border       | #D0D7DE",
        "border.strong | #8C959F", "accent       | #1F6FEB", "accent.weak  | #DDEBFF", "income       | #1B7F3B",
        "expense       | #B3261E", "negative.bg  | #FDE2E1", "cushion.bg   | #FFF4C2", "warn         | #8A5300",
        "total.bg      | #E8EEF6", "grid         | #E3E6EA", "line.zero    | #6E7781", "line.cushion | #D4A72C",
        "line.goal     | #2E8B57", "line.today   | #8250DF", "whatif       | #8250DF", "marker.mixed | #8A5300",
        "tooltip.bg    | #262C34", "tooltip.text | #F5F7FA"
    })
    void paletteMatchesSpec(String id, String hex) {
        ColorToken token = ColorToken.byId(id).orElseThrow();
        assertEquals(hex, token.hex());
        assertEquals(0xFF000000 | Integer.parseInt(hex.substring(1), 16), token.argb());
        assertEquals(hex, token.css());
        assertEquals(1.0, token.opacity());
    }

    @Test
    void paletteIsComplete() {
        assertEquals(27, ColorToken.values().length, "26 цветов таблицы §1.1 и тень");
    }

    @Test
    void shadowIsTranslucentBlack() {
        assertEquals("rgba(0,0,0,0.18)", ColorToken.SHADOW.css());
        assertEquals(46, ColorToken.SHADOW.argb() >>> 24);
        assertEquals("#0000002E", ColorToken.SHADOW.hex());
    }

    @Test
    void byArgbMapsBackToCanonicalToken() {
        for (ColorToken token : ColorToken.values()) {
            ColorToken found = ColorToken.byArgb(token.argb()).orElseThrow();
            assertEquals(token.canonical(), found);
            assertEquals(token.argb(), found.argb());
        }
        assertEquals(ColorToken.BG_SURFACE, ColorToken.BG_CARD.canonical());
        assertEquals(ColorToken.WARN, ColorToken.MARKER_MIXED.canonical());
        assertEquals(ColorToken.LINE_TODAY, ColorToken.WHATIF.canonical());
        assertEquals(Optional.empty(), ColorToken.byArgb(0xFF123456), "цвет вне палитры не сопоставляется");
    }

    @Test
    void cssNames() {
        assertEquals("--cp-bg-window", ColorToken.BG_WINDOW.cssVar());
        assertEquals("-cp-accent-weak", ColorToken.ACCENT_WEAK.fxLookup());
        assertEquals("--cp-font-base-size", FontToken.BASE.cssVar());
    }

    @Test
    void fontsMatchSpec() {
        assertEquals(13, FontToken.BASE.sizePx());
        assertEquals(11, FontToken.SMALL.sizePx());
        assertEquals(16, FontToken.CARD.sizePx());
        assertTrue(FontToken.CARD.bold());
        assertEquals(14, FontToken.HEADER.sizePx());
        assertTrue(FontToken.HEADER.bold());
        assertEquals(12, FontToken.MONO.sizePx());
        assertTrue(FontToken.MONO.mono());
        assertFalse(FontToken.BASE.bold());
        assertEquals("\"Segoe UI\", \"Tahoma\", sans-serif", DesignTokens.FONT_FAMILY);
        assertEquals("\"Consolas\", \"Cascadia Mono\", monospace", DesignTokens.MONO_FAMILY);
        assertEquals("bold 16px \"Segoe UI\", \"Tahoma\", sans-serif", FontToken.CARD.css());
        assertEquals("Consolas", FontToken.MONO.primaryFamily());
    }

    @Test
    void sizesMatchSpec() {
        assertEquals(26, DesignTokens.ROW_HEIGHT);
        assertEquals(28, DesignTokens.HEADER_HEIGHT);
        assertEquals(28, DesignTokens.CONTROL_HEIGHT);
        assertEquals(36, DesignTokens.TOOLBAR_HEIGHT);
        assertEquals(24, DesignTokens.STATUS_HEIGHT);
        assertEquals(88, DesignTokens.BUTTON_MIN_WIDTH);
        assertEquals(6, DesignTokens.RADIUS);
        assertEquals(1200, DesignTokens.MAIN_DEFAULT_WIDTH);
        assertEquals(800, DesignTokens.MAIN_DEFAULT_HEIGHT);
        assertEquals(900, DesignTokens.MAIN_MIN_WIDTH);
        assertEquals(600, DesignTokens.MAIN_MIN_HEIGHT);
        assertEquals(118, DesignTokens.CARD_MIN_WIDTH);
        assertEquals(List.of(80, 24, 28, 32), List.of(DesignTokens.CHART_MARGIN_LEFT, DesignTokens.CHART_MARGIN_RIGHT,
                DesignTokens.CHART_MARGIN_TOP, DesignTokens.CHART_MARGIN_BOTTOM));
    }

    @Test
    void dialogWidthsMatchSpec() {
        assertEquals(460, DesignTokens.dialogWidth(DialogWidth.ALERT));
        assertEquals(560, DesignTokens.dialogWidth(DialogWidth.FORM));
        assertEquals(600, DesignTokens.dialogWidth(DialogWidth.WIZARD));
        assertEquals(640, DesignTokens.dialogWidth(DialogWidth.GOAL));
        assertEquals(880, DesignTokens.dialogWidth(DialogWidth.RULE));
        assertEquals(720, DesignTokens.dialogWidth(DialogWidth.RECOVERY));
        assertEquals(760, DesignTokens.dialogWidth(DialogWidth.HELP));
        assertEquals(680, DesignTokens.dialogWidth(DialogWidth.FILE_BROWSER));
    }

    @Test
    void glyphSetIsClosedAndEmojiFree() {
        String spec = "↶ ↷ ▾ ▸ ✕ ✓ ✗ ● ◀ ▶ ▦ ↑ ✎ → ⇄ ≡ Δ ₽ ⚙ ↻ ≡ ✎ ◎ ⇩ ⟲ ℹ ⚠ ✖ ?";
        for (String glyph : spec.split(" ")) {
            assertTrue(DesignTokens.GLYPHS.contains(glyph), glyph);
        }
        for (String glyph : DesignTokens.GLYPHS) {
            assertEquals(1, glyph.codePointCount(0, glyph.length()), "значок — один символ: " + glyph);
            int cp = glyph.codePointAt(0);
            assertTrue(cp < 0x1F000 && (cp < 0xFE00 || cp > 0xFE0F), "эмодзи запрещены: " + glyph);
            assertTrue(DesignTokens.isAllowedInText(cp));
        }
    }

    @Test
    void webCssContainsEveryToken() {
        String css = TokenCss.webCss();
        for (ColorToken token : ColorToken.values()) {
            assertTrue(css.contains("  " + token.cssVar() + ": " + token.css() + ";\n"), token.id());
        }
        for (FontToken font : FontToken.values()) {
            assertTrue(css.contains(font.cssVar() + ": " + font.sizePx() + "px;"), font.id());
        }
        assertTrue(css.contains("--cp-row-height: 26px;"));
        assertTrue(css.contains("--cp-dialog-file-browser: 680px;"));
        assertFalse(css.contains("prefers-color-scheme"), "одна светлая тема");
        assertEquals(css, TokenCss.webCss(), "генерация детерминирована");
    }

    @Test
    void fxLookupsAndSwingDefaults() {
        Map<String, String> fx = TokenCss.fxLookups();
        assertEquals(ColorToken.values().length, fx.size());
        assertEquals("#F6F8FA", fx.get("-cp-bg-window"));
        Map<String, Object> swing = TokenCss.swingDefaults();
        assertEquals(Boolean.FALSE, swing.get("swing.boldMetal"));
        assertEquals(ColorToken.ACCENT_WEAK.argb(), swing.get("Table.selectionBackground"));
        assertEquals(FontToken.BASE, swing.get("Menu.font"));
        assertEquals(FontToken.LEGEND, swing.get("ToolTip.font"));
        for (Map.Entry<String, Object> entry : swing.entrySet()) {
            Object value = entry.getValue();
            assertTrue(value instanceof Integer || value instanceof FontToken || value instanceof Boolean, entry.getKey());
            if (value instanceof Integer argb) {
                assertTrue(ColorToken.byArgb(argb).isPresent(), entry.getKey());
            }
        }
    }
}
