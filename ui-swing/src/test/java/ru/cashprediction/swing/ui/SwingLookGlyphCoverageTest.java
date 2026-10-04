package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.*;

/** Проверяет общие изображения и ключи локализации темы Metal. */
class SwingLookGlyphCoverageTest {
    @Test void everyGlyphHasSharedImageCoverage() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            for (String glyph : DesignTokens.GLYPHS)
                assertTrue(SwingIcons.icon(glyph).getIconWidth() > 0, glyph);
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
