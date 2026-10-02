package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.*;

/** Проверяет дробные метрики настоящей метки и её нарисованные глифы, а не подмену dump-width. */
class SwingFractionalLabelTest {
    @Test void actualPlaceholderButtonCaptionUsesItsPaintContextAndRealPadding() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); var button = new SwingFractionalButton(UiText.get("table.empty.addIncome"));
            SwingLook.dialogButton(button);
            var metrics = button.getFontMetrics(button.getFont()); assertTrue(metrics.getFontRenderContext().usesFractionalMetrics());
            Insets insets = button.getInsets();
            assertEquals(Math.max(DesignTokens.BUTTON_MIN_WIDTH, metrics.stringWidth(button.getText()) + insets.left + insets.right), button.getPreferredSize().width);
            assertEquals(SwingLook.font(FontToken.BASE), button.getFont());
            button.setSize(button.getPreferredSize());
            var image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics(); try { button.paint(graphics); } finally { graphics.dispose(); }
            assertNotEquals(0, image.getRGB(1, 1));
        });
    }

    @Test void actualStatusTextUsesFractionalLayoutAndPaintWithUnchangedFont() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var label = new SwingFractionalLabel(UiText.get("status.file", UiText.get("form.newPlan.name")), ColorToken.TEXT_PRIMARY, FontToken.SMALL);
            var metrics = label.getFontMetrics(label.getFont());
            assertTrue(metrics.getFontRenderContext().usesFractionalMetrics());
            assertEquals(SwingLook.font(FontToken.SMALL), label.getFont());
            int expected = (int) Math.round(label.getFont().getStringBounds(label.getText(), metrics.getFontRenderContext()).getWidth());
            assertEquals(expected, label.getPreferredSize().width);
            label.setSize(label.getPreferredSize());
            var image = new BufferedImage(label.getWidth(), label.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics(); try { label.paint(graphics); } finally { graphics.dispose(); }
            boolean painted = false;
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) if ((image.getRGB(x, y) >>> 24) != 0) painted = true;
            assertTrue(painted);
            System.out.println("actual fractional status font=" + label.getFont() + " width=" + label.getWidth() + " frc=" + metrics.getFontRenderContext());
        });
    }
}
