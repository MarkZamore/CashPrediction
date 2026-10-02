package ru.cashprediction.fx.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.token.ColorToken;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет подключаемые правила токенов без запуска toolkit; реальные скины проверяются отдельно. */
class FxTokenStylesTest {
    private static String css() {
        String uri = FxStyles.stylesheet();
        assertTrue(uri.startsWith("data:text/css;base64,"));
        return new String(Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1)), StandardCharsets.UTF_8);
    }

    /** Стандартный focus lookup связан с accent, а не с бирюзовым значением Modena. */
    @Test void rootFocusUsesSharedTokens() {
        String root = FxStyles.rootCss();
        assertTrue(root.contains("-fx-focus-color: " + ColorToken.ACCENT.hex() + ";"));
        assertTrue(root.contains("-fx-faint-focus-color: " + ColorToken.ACCENT_WEAK.hex() + ";"));
        assertFalse(root.contains("#039ED3"));
    }

    /** Все стандартные виды редакторов получают реальную рамку border.strong без изменения padding. */
    @Test void editorsUseFlatTokenLayers() {
        assertTrue(css().contains(".text-input, .combo-box-base, .spinner { -fx-background-color: "
                + ColorToken.BORDER_STRONG.hex() + ", " + ColorToken.BG_SURFACE.hex() + "; -fx-background-insets: 0, 1; }"));
        assertTrue(css().contains(".spinner:contains-focus { -fx-background-color: " + ColorToken.ACCENT.hex()));
        assertTrue(css().contains(".combo-box-base:contains-focus"));
    }

    /** Default определяется pseudo-class, не локализованной подписью и не бизнес-назначением кнопки. */
    @Test void defaultEmphasisUsesSharedTokensWithoutSaveSpecialCase() {
        assertTrue(css().contains(".button:default { -fx-background-color: " + ColorToken.ACCENT.hex()
                + ", " + ColorToken.BG_SURFACE.hex() + "; -fx-background-insets: 0, 1; -fx-text-fill: " + ColorToken.ACCENT.hex()));
        assertTrue(css().contains(".button:default:armed { -fx-background-color: " + ColorToken.ACCENT.hex()
                + ", " + ColorToken.ACCENT_WEAK.hex()));
        assertFalse(css().contains("#ABD8ED"));
        assertFalse(css().contains("planSettings"));
    }
}
