package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.*;

/** Проверяет реальные шрифты и ключи локализации темы Metal. */
class SwingLookGlyphCoverageTest {
    @Test void everyGlyphHasCompositeFontCoverage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            for (FontToken token : FontToken.values()) for (String glyph : DesignTokens.GLYPHS)
                assertEquals(-1, SwingLook.font(token).canDisplayUpTo(glyph), token + ": " + glyph);
        });
    }
    @Test void chooserAndOptionButtonsUseSharedCatalog() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            assertEquals(UiText.get("button.open"), UIManager.getString("FileChooser.openButtonText"));
            assertEquals(UiText.get("button.save"), UIManager.getString("FileChooser.saveButtonText"));
            assertEquals(UiText.get("button.cancel"), UIManager.getString("FileChooser.cancelButtonText"));
            assertEquals(UiText.get("button.ok"), UIManager.getString("OptionPane.okButtonText"));
        });
    }
    @Test void tokenColorsAreExactArgbValues() { assertEquals(ColorToken.ACCENT.argb(), SwingLook.color(ColorToken.ACCENT).getRGB()); }
}
