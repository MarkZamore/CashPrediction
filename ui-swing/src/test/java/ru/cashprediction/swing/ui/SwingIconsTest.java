package ru.cashprediction.swing.ui;

import java.awt.Font;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.token.UiIcons;
import ru.cashprediction.core.ui.token.DesignTokens;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет общий источник изображений и реальные лёгкие компоненты без показа окон. */
class SwingIconsTest {
    @Test void everyManifestImageMatchesCorePixels() throws Exception {
        for (String key : UiIcons.manifest().keySet()) {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(UiIcons.png(key).orElseThrow()));
            ImageIcon icon = SwingIcons.icon(key);
            assertEquals(source.getWidth(), icon.getIconWidth(), key);
            assertEquals(source.getHeight(), icon.getIconHeight(), key);
            BufferedImage rendered = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
            var graphics = rendered.createGraphics();
            graphics.setComposite(java.awt.AlphaComposite.Src);
            try { icon.paintIcon(null, graphics, 0, 0); } finally { graphics.dispose(); }
            assertArrayEquals(source.getRGB(0, 0, source.getWidth(), source.getHeight(), null, 0, source.getWidth()),
                    rendered.getRGB(0, 0, rendered.getWidth(), rendered.getHeight(), null, 0, rendered.getWidth()), key);
        }
    }

    @Test void brandingUsesCoreBytes() throws Exception {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(UiIcons.applicationPng()));
        assertEquals(source.getWidth(), SwingIcons.application().getWidth(null));
        assertThrows(IllegalArgumentException.class, () -> SwingIcons.icon("unknown"));
    }

    @Test void textMetricsAndAccessibilityRemainLogical() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel label = new JLabel("▾ sample"); var size = label.getPreferredSize();
            String accessible = label.getAccessibleContext().getAccessibleName();
            SwingIcons.decorate(label);
            assertEquals("▾ sample", label.getText());
            assertEquals(size.width + DesignTokens.INLINE_ICON_SIZE - label.getFontMetrics(label.getFont()).stringWidth("▾"), label.getPreferredSize().width);
            assertEquals(Math.max(size.height, DesignTokens.INLINE_ICON_SIZE), label.getPreferredSize().height);
            assertEquals(accessible, label.getAccessibleContext().getAccessibleName());
            JButton button = new JButton("↶"); var buttonSize = button.getPreferredSize(); SwingIcons.decorate(button);
            assertEquals("↶", button.getText());
            assertEquals(buttonSize.width + DesignTokens.INLINE_ICON_SIZE - button.getFontMetrics(button.getFont()).stringWidth("↶"), button.getPreferredSize().width);
            assertEquals(buttonSize.height, button.getPreferredSize().height);
            JLabel header = new JLabel(); SwingIcons.header(header, "₽", "");
            assertEquals("₽", header.getClientProperty("cp.glyph")); assertEquals("₽", header.getAccessibleContext().getAccessibleName());
            assertTrue(header.getIcon() instanceof ImageIcon); assertEquals("", header.getText());
        });
    }

    @Test void decoratedAndCombinedMarksUseImagesButCurrencyAndProseDoNot() {
        Font font = new Font(Font.DIALOG, Font.PLAIN, 13);
        var marks = SwingIcons.attributed("✎ → ⇄ ≡ ✕ Δ", font).getIterator();
        for (int index = 0; index < marks.getEndIndex(); index += 2) {
            marks.setIndex(index); assertNotNull(marks.getAttribute(TextAttribute.CHAR_REPLACEMENT));
        }
        var text = SwingIcons.attributed("123 ₽ menu → action", font).getIterator();
        for (int index = 0; index < text.getEndIndex(); index++) {
            text.setIndex(index); assertNull(text.getAttribute(TextAttribute.CHAR_REPLACEMENT));
        }
        var suffix = SwingIcons.attributed("details ▸", font).getIterator(); suffix.last();
        assertNotNull(suffix.getAttribute(TextAttribute.CHAR_REPLACEMENT));
    }

    @Test void comboAndSpinnerKeepNativeBehaviourWithSharedArrowImages() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JComboBox<String> combo = new JComboBox<>(new String[]{"a", "b"}); SwingIcons.arrows(combo);
            JButton arrow = java.util.Arrays.stream(combo.getComponents()).filter(JButton.class::isInstance)
                    .map(JButton.class::cast).findFirst().orElseThrow();
            assertTrue(arrow.getIcon() instanceof ImageIcon); combo.setSelectedIndex(1); assertEquals("b", combo.getSelectedItem());
            combo.setEditable(true); assertNotNull(combo.getEditor());
            JSpinner spinner = new JSpinner(new SpinnerNumberModel(2, 0, 5, 1)); SwingIcons.arrows(spinner);
            for (var child : spinner.getComponents()) if (child instanceof JButton button) {
                assertTrue(button.getIcon() instanceof ImageIcon);
                assertTrue(button.getMouseListeners().length > 0); assertTrue(button.getActionListeners().length > 0);
                if (button.getName().equals("Spinner.nextButton")) button.doClick(0);
            }
            assertEquals(3, spinner.getValue());
        });
    }

    @Test void rendererSourcesCannotLoadClientIconsOrDrawToolbarArrow() throws Exception {
        Path root = Files.isDirectory(Path.of("src/main/java/ru/cashprediction/swing/ui"))
                ? Path.of("src/main/java/ru/cashprediction/swing/ui")
                : Path.of("ui-swing/src/main/java/ru/cashprediction/swing/ui");
        try (var files = Files.list(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                assertFalse(source.contains("/ru/cashprediction/swing/icon.png"), file.toString());
                assertFalse(source.contains("class Arrow"), file.toString());
                if (!file.getFileName().toString().equals("SwingIcons.java")) {
                    assertFalse(source.contains("new ImageIcon("), file.toString());
                    assertFalse(source.contains("getResource("), file.toString());
                }
            }
        }
    }
}
