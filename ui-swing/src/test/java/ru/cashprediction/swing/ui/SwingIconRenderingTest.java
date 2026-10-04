package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.font.GraphicAttribute;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.text.View;
import javax.swing.text.html.ImageView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.ui.form.Option;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.*;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет настоящие пиксели лёгких Swing-компонентов без показа окон и без эталонных снимков. */
class SwingIconRenderingTest {
    @TempDir Path directory;

    @Test void inlineImageHasFixedSizeAndCorePixelsForDifferentFontsAndColors() throws Exception {
        for (int size : new int[]{7, 13, 28}) {
            Font font = new Font(Font.MONOSPACED, Font.PLAIN, size);
            for (ColorToken color : new ColorToken[]{ColorToken.ACCENT, ColorToken.EXPENSE, ColorToken.TEXT_MUTED,
                    ColorToken.TEXT_PAST, ColorToken.TOOLTIP_TEXT}) {
                var iterator = SwingIcons.attributed("✓", font, color).getIterator();
                GraphicAttribute glyph = (GraphicAttribute) iterator.getAttribute(TextAttribute.CHAR_REPLACEMENT);
                assertEquals(DesignTokens.INLINE_ICON_SIZE, glyph.getAdvance());
                BufferedImage actual = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = actual.createGraphics();
                try { glyph.draw(graphics, 20, 32); } finally { graphics.dispose(); }
                var metrics = font.getLineMetrics("✓", new java.awt.font.FontRenderContext(null, true, true));
                int top = Math.round(32 - metrics.getAscent()
                        + (metrics.getAscent() + metrics.getDescent() - DesignTokens.INLINE_ICON_SIZE) / 2);
                BufferedImage expected = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
                graphics = expected.createGraphics();
                try { coreIcon("✓", color, DesignTokens.INLINE_ICON_SIZE).paintIcon(null, graphics, 20, top); }
                finally { graphics.dispose(); }
                assertArrayEquals(pixels(expected), pixels(actual), color + ":" + size);
            }
        }
    }

    @Test void realDisabledLabelsAndButtonsUseDisabledColorAndAlpha() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            Object oldLabel = UIManager.get("Label.disabledForeground"), oldButton = UIManager.get("Button.disabledText");
            UIManager.put("Label.disabledForeground", SwingLook.color(ColorToken.EXPENSE));
            UIManager.put("Button.disabledText", SwingLook.color(ColorToken.EXPENSE));
            try {
                for (String text : new String[]{"MMMM", "✓"}) {
                    JLabel label = new JLabel(text); SwingIcons.decorate(label);
                    JButton button = new JButton(text); SwingIcons.decorate(button);
                    button.setBorderPainted(false); button.setContentAreaFilled(false); button.setFocusPainted(false);
                    for (JComponent component : List.of(label, button)) {
                        component.setForeground(SwingLook.color(ColorToken.ACCENT));
                        component.setFont(new Font(Font.DIALOG, Font.PLAIN, 18));
                        BufferedImage enabled = paint(component, 120, 40);
                        component.setEnabled(false); BufferedImage disabled = paint(component, 120, 40);
                        assertTrue(maxAlpha(enabled) > 200);
                        assertTrue(maxAlpha(disabled) > 60 && maxAlpha(disabled) <= 141, text);
                        assertTrue(alphaSum(disabled) < alphaSum(enabled));
                        assertTrue(countColor(disabled, ColorToken.EXPENSE) > 0, text);
                        component.setEnabled(true);
                        assertArrayEquals(pixels(enabled), pixels(paint(component, 120, 40)));
                    }
                }
            } finally {
                UIManager.put("Label.disabledForeground", oldLabel); UIManager.put("Button.disabledText", oldButton);
            }
        });
    }

    @Test void genuineCheckAndRadioKeepModelsFontsAccessibilityAndSharedSelectionPixels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            JCheckBox check = new JCheckBox("sample"); JRadioButton radio = new JRadioButton("sample");
            for (AbstractButton button : List.of(check, radio)) {
                Font font = button.getFont(); String accessible = button.getAccessibleContext().getAccessibleName();
                button.setOpaque(false);
                assertEquals(0, countColor(paint(button, 160, 32), ColorToken.ACCENT));
                button.doClick(0); assertTrue(button.isSelected());
                assertTrue(countColor(paint(button, 160, 32), ColorToken.ACCENT) > 0);
                button.setEnabled(false);
                BufferedImage disabled = paint(button, 160, 32);
                assertEquals(0, countColor(disabled, ColorToken.ACCENT));
                assertTrue(countColor(disabled, ColorToken.TEXT_MUTED) > 0);
                assertEquals(font, button.getFont()); assertEquals(accessible, button.getAccessibleContext().getAccessibleName());
                Icon icon = UIManager.getIcon(button instanceof JCheckBox ? "CheckBox.icon" : "RadioButton.icon");
                assertEquals(DesignTokens.INLINE_ICON_SIZE, icon.getIconWidth());
                assertEquals(DesignTokens.INLINE_ICON_SIZE, icon.getIconHeight());
            }
            ButtonGroup group = new ButtonGroup(); JRadioButton other = new JRadioButton("other");
            group.add(radio); group.add(other); other.doClick(0);
            assertTrue(other.isSelected()); assertFalse(radio.isSelected());
        });
    }

    @Test void previewUsesPngWhileUserOptionsRetainActualTextPixels() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
            SwingLook.install(); JList<Option> list = new JList<>();
            Option preview = Option.of("date", "01.01.2026  ⇄ sample  ✎ sample");
            var renderer = new SwingFieldWidgets.OptionRenderer(true);
            JLabel label = (JLabel) renderer.getListCellRendererComponent(list, preview, 0, false, false);
            assertEquals(preview.text(), label.getText());
            assertTrue(countColor(paint(label, 360, 32), ColorToken.TEXT_PRIMARY) > 0);
            assertTrue(label.getUI().getClass().getName().contains("RasterLabelUI"));
            assertEquals(Boolean.TRUE, label.getClientProperty("cp.decorative"));
            assertContainsCoreIcon(paint(label, 360, 32), label.getBackground(), "⇄", ColorToken.TEXT_PRIMARY);
            assertContainsCoreIcon(paint(label, 360, 32), label.getBackground(), "✎", ColorToken.TEXT_PRIMARY);
            Option user = Option.of("user", "✓ sample → 123 ₽");
            renderer = new SwingFieldWidgets.OptionRenderer(false);
            label = (JLabel) renderer.getListCellRendererComponent(list, user, 0, false, false);
            JLabel original = (JLabel) new DefaultListCellRenderer().getListCellRendererComponent(list, user.text(), 0, false, false);
            assertEquals(original.getFont(), label.getFont()); assertEquals(original.getPreferredSize(), label.getPreferredSize());
            assertArrayEquals(pixels(paint(original, 240, 32)), pixels(paint(label, 240, 32)));
            } catch (Exception ex) { throw new AssertionError(ex); }
        });
    }

    @Test void multilineUserTitleAndNoteCannotBecomeDecorativeTooltipImages() {
        String flag = UiText.get("table.tip.amountChanged");
        String title = "user ✓ 123 ₽\n" + flag;
        String note = UiText.get("table.tip.note", flag + "\n" + UiText.get("table.tip.moved"));
        String text = String.join("\n", title, UiText.get("table.tip.oneTime"), flag, note);
        String html = SwingLook.tableTooltipHtml(text, title);
        assertEquals(1, html.split("<img ", -1).length - 1);
        assertEquals(text, SwingUiDumper.plain(new JLabel(html)));
    }

    @Test void unknownColorUsesExistingCoreImageAndDeltaKeepsWhatIfColor() throws Exception {
        assertIconPixels(coreIcon("✓", null, DesignTokens.INLINE_ICON_SIZE),
                SwingIcons.icon("✓", ColorToken.BG_WINDOW, DesignTokens.INLINE_ICON_SIZE));
        var iterator = SwingIcons.attributed("Δ", new Font(Font.DIALOG, Font.PLAIN, 13), ColorToken.TEXT_MUTED).getIterator();
        GraphicAttribute glyph = (GraphicAttribute) iterator.getAttribute(TextAttribute.CHAR_REPLACEMENT);
        BufferedImage image = new BufferedImage(48, 48, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { glyph.draw(graphics, 16, 24); } finally { graphics.dispose(); }
        assertTrue(countColor(image, ColorToken.WHATIF) > 0);
        assertEquals(0, countColor(image, ColorToken.TEXT_MUTED));
    }

    @Test void realHtmlTooltipImageViewsPaintSharedLightPixelsAndKeepLogicalText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                SwingLook.install();
                String flag = UiText.get("table.tip.moved");
                String note = UiText.get("table.tip.note", "user → ✓ 123 ₽\n" + flag);
                String text = String.join("\n", flag, UiText.get("table.tip.oneTime"), flag, note);
                String html = SwingLook.tableTooltipHtml(text);
                assertEquals(text, SwingUiDumper.plain(new JLabel(html)));
                // JavaFX: Tooltip → Swing: JToolTip → Web: div.tooltip
                JToolTip tooltip = new JToolTip(); tooltip.setTipText(html);
                List<ImageView> images = new ArrayList<>(); collectImages((View) tooltip.getClientProperty(BasicHTML.propertyKey), images);
                assertEquals(1, images.size());
                ImageView image = images.getFirst(); image.setLoadsSynchronously(true);
                assertTrue(image.getImageURL().toExternalForm().contains("arrow-right-tooltip_text.png"));
                BufferedImage actual = new BufferedImage(DesignTokens.INLINE_ICON_SIZE, DesignTokens.INLINE_ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = actual.createGraphics();
                try { image.paint(graphics, new Rectangle(0, 0, actual.getWidth(), actual.getHeight())); } finally { graphics.dispose(); }
                BufferedImage expected = new BufferedImage(actual.getWidth(), actual.getHeight(), BufferedImage.TYPE_INT_ARGB);
                graphics = expected.createGraphics();
                try {
                    BufferedImage source = ImageIO.read(new ByteArrayInputStream(UiIcons.png("→", ColorToken.TOOLTIP_TEXT).orElseThrow()));
                    graphics.drawImage(source, 0, 0, DesignTokens.INLINE_ICON_SIZE, DesignTokens.INLINE_ICON_SIZE, null);
                }
                finally { graphics.dispose(); }
                assertArrayEquals(pixels(expected), pixels(actual));
                assertNull(SwingLook.tableTooltipHtml(""));
            } catch (Exception ex) { throw new AssertionError(ex); }
        });
    }

    @Test void formsAndAlertsUseDistinctTokenSizesAndCoreColors() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); JLabel form = new JLabel(); SwingIcons.header(form, "⚙", "");
            assertEquals(DesignTokens.DIALOG_ICON_SIZE, form.getIcon().getIconWidth());
            for (ColorToken color : new ColorToken[]{ColorToken.ACCENT, ColorToken.WARN, ColorToken.EXPENSE}) {
                JLabel alert = new JLabel(); SwingIcons.header(alert, "⚠", "", color, DesignTokens.ALERT_ICON_SIZE);
                assertEquals(DesignTokens.ALERT_ICON_SIZE, alert.getIcon().getIconWidth());
                assertEquals("⚠", alert.getClientProperty("cp.glyph"));
                assertTrue(countColor(paint(alert, 40, 40), color) > 0);
            }
            assertEquals(DesignTokens.ALERT_ICON_SIZE, UIManager.getIcon("OptionPane.warningIcon").getIconWidth());
        });
    }

    @Test void genuineFileChooserUsesCoreFileFolderAndNavigationImages() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            // JavaFX: FileChooser → Swing: JFileChooser → Web: FILE_BROWSER
            JFileChooser chooser = new JFileChooser(directory.toFile()); SwingIcons.chooser(chooser);
            assertIconPixels(SwingIcons.icon("folder", ColorToken.TEXT_MUTED, DesignTokens.INLINE_ICON_SIZE), chooser.getIcon(directory.toFile()));
            assertIconPixels(SwingIcons.icon("≡", ColorToken.TEXT_MUTED, DesignTokens.INLINE_ICON_SIZE), chooser.getIcon(directory.resolve("sample.md").toFile()));
            for (String key : List.of("upFolder", "homeFolder", "newFolder", "listView", "detailsView")) {
                Icon icon = UIManager.getIcon("FileChooser." + key + "Icon");
                assertInstanceOf(ImageIcon.class, icon); assertEquals(DesignTokens.INLINE_ICON_SIZE, icon.getIconWidth());
            }
            assertChooserButtons(chooser);
        });
    }

    /** Проверяет реальные кнопки навигации, включая отключённое состояние. */
    private static void assertChooserButtons(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof AbstractButton button && button.getIcon() != null) {
                assertInstanceOf(ImageIcon.class, button.getIcon());
                assertEquals(DesignTokens.INLINE_ICON_SIZE, button.getIcon().getIconWidth());
                assertSame(button.getIcon(), button.getDisabledIcon());
            }
            if (child instanceof Container nested) assertChooserButtons(nested);
        }
    }

    /** Читает эталонные пиксели напрямую из ядра, независимо от кеша адаптера. */
    private static ImageIcon coreIcon(String key, ColorToken color, int size) throws Exception {
        BufferedImage source = ImageIO.read(new ByteArrayInputStream(UiIcons.png(key, color).orElseThrow()));
        return new ImageIcon(source.getScaledInstance(size, size, Image.SCALE_SMOOTH));
    }

    /** Находит полный растр PNG в реально нарисованной строке предпросмотра, включая прозрачные поля. */
    private static void assertContainsCoreIcon(BufferedImage actual, Color background, String key, ColorToken color) throws Exception {
        int size = DesignTokens.INLINE_ICON_SIZE;
        BufferedImage expected = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = expected.createGraphics();
        try {
            graphics.setColor(background); graphics.fillRect(0, 0, size, size);
            coreIcon(key, color, size).paintIcon(null, graphics, 0, 0);
        } finally { graphics.dispose(); }
        int[] expectedPixels = pixels(expected);
        for (int y = 0; y <= actual.getHeight() - size; y++) for (int x = 0; x <= actual.getWidth() - size; x++) {
            if (java.util.Arrays.equals(expectedPixels, actual.getRGB(x, y, size, size, null, 0, size))) return;
        }
        fail("Core PNG not found: " + key);
    }

    /** Рисует настоящий лёгкий компонент в память, не создавая окно. */
    private static BufferedImage paint(JComponent component, int width, int height) {
        component.setSize(width, height); component.doLayout();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { component.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }

    /** Сравнивает весь растр значков, а не наличие Icon или метаданных. */
    private static void assertIconPixels(Icon expected, Icon actual) {
        assertEquals(expected.getIconWidth(), actual.getIconWidth());
        BufferedImage first = new BufferedImage(expected.getIconWidth(), expected.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        BufferedImage second = new BufferedImage(first.getWidth(), first.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = first.createGraphics();
        try { expected.paintIcon(null, graphics, 0, 0); } finally { graphics.dispose(); }
        graphics = second.createGraphics();
        try { actual.paintIcon(null, graphics, 0, 0); } finally { graphics.dispose(); }
        assertArrayEquals(pixels(first), pixels(second));
    }

    /** Возвращает все пиксели без потери прозрачности. */
    private static int[] pixels(BufferedImage image) { return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()); }
    /** Возвращает максимальную видимую непрозрачность. */
    private static int maxAlpha(BufferedImage image) { return java.util.Arrays.stream(pixels(image)).map(pixel -> pixel >>> 24).max().orElse(0); }
    /** Измеряет суммарную непрозрачность нарисованных пикселей. */
    private static long alphaSum(BufferedImage image) { return java.util.Arrays.stream(pixels(image)).mapToLong(pixel -> pixel >>> 24).sum(); }
    /** Считает пиксели цвета токена с допуском округления при альфа-композиции. */
    private static long countColor(BufferedImage image, ColorToken color) {
        int rgb = color.argb();
        return java.util.Arrays.stream(pixels(image)).filter(pixel -> (pixel >>> 24) > 60)
                .filter(pixel -> Math.abs((pixel & 255) - (rgb & 255)) <= 2
                        && Math.abs(((pixel >>> 8) & 255) - ((rgb >>> 8) & 255)) <= 2
                        && Math.abs(((pixel >>> 16) & 255) - ((rgb >>> 16) & 255)) <= 2).count();
    }

    /** Находит настоящие ImageView внутри HTML-рендерера подсказки. */
    private static void collectImages(View view, List<ImageView> images) {
        if (view instanceof ImageView image) images.add(image);
        if (view != null) for (int index = 0; index < view.getViewCount(); index++) collectImages(view.getView(index), images);
    }
}
