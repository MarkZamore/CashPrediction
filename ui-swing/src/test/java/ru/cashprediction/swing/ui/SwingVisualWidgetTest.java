package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.dump.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.*;

/** Проверяет реальные пиксели и раскладку невидимых виджетов, без Robot и подмены дампа. */
class SwingVisualWidgetTest {
    @Test void summaryUsesSharedLineHeightsAndGrowsForLargerRealGlyphs() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var model = new ru.cashprediction.core.ui.view.summary.CardModel("card", UiText.get("toolbar.tb.save"), "177 000 ₽", ColorToken.INCOME,
                    UiText.get("form.planSettings.startBalance"), ColorToken.TEXT_MUTED, null, "", "");
            var card = new SwingSummaryPanel(null).new Card(model);
            assertEquals(DesignTokens.CARD_TITLE_LINE_HEIGHT, card.title.getPreferredSize().height);
            assertEquals(DesignTokens.CARD_VALUE_LINE_HEIGHT, card.value.getPreferredSize().height);
            assertEquals(DesignTokens.CARD_CAPTION_LINE_HEIGHT, card.caption.getPreferredSize().height);
            assertEquals(DesignTokens.CARD_CONTENT_GAP, card.getComponent(1).getPreferredSize().height);
            assertEquals(DesignTokens.CARD_CONTENT_GAP, card.getComponent(3).getPreferredSize().height);
            card.setSize(124, card.getPreferredSize().height); card.doLayout();
            for (JLabel label : List.of(card.title, card.value, card.caption)) {
                assertEquals(label.getPreferredSize().height, label.getHeight());
                int baseline = label.getBaseline(label.getWidth(), label.getHeight());
                var glyphs = new java.awt.font.TextLayout(label.getText(), label.getFont(), new java.awt.font.FontRenderContext(null, true, true)).getBounds();
                assertTrue(baseline + glyphs.getMinY() >= 0, label.getText());
                assertTrue(baseline + glyphs.getMaxY() <= label.getHeight(), label.getText());
                BufferedImage paint = new BufferedImage(label.getWidth(), label.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = paint.createGraphics();
                try { label.paint(graphics); } finally { graphics.dispose(); }
                assertTrue(java.util.Arrays.stream(paint.getRGB(0, 0, paint.getWidth(), paint.getHeight(), null, 0, paint.getWidth())).anyMatch(pixel -> (pixel >>> 24) != 0));
            }
            int originalHeight = card.getPreferredSize().height;
            card.value.setFont(card.value.getFont().deriveFont(28f));
            card.invalidate();
            assertTrue(card.value.getPreferredSize().height >= card.value.getFontMetrics(card.value.getFont()).getHeight());
            assertTrue(card.getPreferredSize().height > originalHeight);
            card.caption.setText(""); assertEquals(DesignTokens.CARD_CAPTION_LINE_HEIGHT, card.caption.getPreferredSize().height);
            System.out.println("shared summary originalHeight=" + originalHeight + " largerFontHeight=" + card.getPreferredSize().height);
        });
    }
    @Test void summaryRowsExposeActualFontLineMetrics() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var model = new ru.cashprediction.core.ui.view.summary.CardModel("card", "title", "value", ColorToken.TEXT_PRIMARY,
                    "caption", ColorToken.TEXT_MUTED, null, "", "");
            var card = new SwingSummaryPanel(null).new Card(model);
            var context = new java.awt.font.FontRenderContext(null, true, true);
            for (JLabel label : List.of(card.title, card.value, card.caption)) {
                assertEquals(label.getFontMetrics(label.getFont()).getHeight(), label.getPreferredSize().height);
                var layout = new java.awt.font.TextLayout(label.getText(), label.getFont(), context);
                var primary = new java.awt.font.TextLayout(label.getText(), new Font(label.getFont().getName(), label.getFont().getStyle(), label.getFont().getSize()), context);
                System.out.println("summary font=" + label.getFont() + " preferred=" + label.getPreferredSize()
                        + " metrics=" + label.getFontMetrics(label.getFont()).getHeight() + " textLayoutHeight="
                        + (layout.getAscent() + layout.getDescent() + layout.getLeading()) + " ascent=" + layout.getAscent()
                        + " descent=" + layout.getDescent() + " leading=" + layout.getLeading() + " primaryHeight="
                        + (primary.getAscent() + primary.getDescent() + primary.getLeading()) + " visual=" + layout.getBounds());
            }
            int rowHeight = List.of(card.title, card.value, card.caption).stream().mapToInt(label -> label.getPreferredSize().height).sum();
            assertEquals(rowHeight + card.getComponent(1).getPreferredSize().height + card.getComponent(3).getPreferredSize().height
                    + card.getInsets().top + card.getInsets().bottom, card.getPreferredSize().height);
            assertEquals(DesignTokens.CARD_PAD_V + 1, card.getInsets().top);
            assertEquals(DesignTokens.CARD_PAD_H + 1, card.getInsets().left);
            System.out.println("summary card preferred=" + card.getPreferredSize() + " insets=" + card.getInsets());
        });
    }
    @Test void toolbarButtonActuallyPaintsWindowBackgroundAtVisualProbe() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            SwingToolbar toolbar = toolbar();
            BufferedImage image = new BufferedImage(1200, 36, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try { toolbar.paint(graphics); } finally { graphics.dispose(); }
            assertEquals(SwingLook.color(ColorToken.BG_WINDOW).getRGB(), image.getRGB(2, 18));
            assertEquals(SwingLook.color(ColorToken.BG_WINDOW), toolbar.widget("first").getBackground());
        });
    }

    @Test void trailingSaveFitsActualContentAndExposesIndependentNormalizationRounding() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); SwingToolbar toolbar = toolbar();
            JComponent save = toolbar.widget("tb.save");
            assertEquals(1200, save.getX() + save.getWidth());
            assertEquals(save.getPreferredSize().width, save.getWidth());
            var actual = SwingUiDumper.toolbar(toolbar).items().getLast().bounds();
            assertEquals(save.getX(), actual.x()); assertEquals(save.getWidth(), actual.width());
            assertEquals(1200, actual.x() + actual.width());
            double normalizedRight = Math.round(actual.x() / 2) * 2 + Math.round(actual.width() / 2) * 2;
            System.out.println("save actual=" + actual + " actualRight=" + (actual.x() + actual.width())
                    + " independentlyRoundedRight=" + normalizedRight);
        });
    }

    @Test void fullFieldCaptionUsesNaturalWidthAndUpdatesWithoutEllipsis() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            JLabel label = SwingFieldWidgets.formLabel(UiText.get("form.planSettings.startBalance") + ":");
            label.setSize(label.getPreferredSize());
            assertTrue(label.getWidth() > DesignTokens.FORM_LABEL_MIN_WIDTH);
            assertEquals(label.getText(), displayed(label));
            label.setText(UiText.get("form.planSettings.startBalance") + ": " + UiText.get("form.planSettings.startBalance"));
            label.setSize(label.getPreferredSize()); assertEquals(label.getText(), displayed(label));
            label.setText("x"); assertEquals(DesignTokens.FORM_LABEL_MIN_WIDTH, label.getPreferredSize().width);
            assertEquals(DesignTokens.CONTROL_HEIGHT, label.getPreferredSize().height);
            System.out.println("field caption naturalWidth=" + SwingFieldWidgets.formLabel(UiText.get("form.planSettings.startBalance") + ":").getPreferredSize().width);
        });
    }

    /** Раскладывает настоящий тулбар с общей локализованной подписью последней кнопки. */
    private static SwingToolbar toolbar() {
        SwingToolbar toolbar = new SwingToolbar(null);
        toolbar.render(new ToolbarModel(List.of(
                new ToolbarNode.Button("first", CommandId.FILE_SAVE, UiText.get("toolbar.tb.add"), "", true, Emphasis.NONE),
                new ToolbarNode.Spacer("space"),
                new ToolbarNode.Button("tb.save", CommandId.FILE_SAVE, UiText.get("toolbar.tb.save"), "", true, Emphasis.ACCENT))));
        toolbar.setSize(1200, DesignTokens.TOOLBAR_HEIGHT); toolbar.doLayout(); return toolbar;
    }

    /** Получает ту строку, которую штатный LabelUI действительно передаст рисованию. */
    private static String displayed(JLabel label) {
        return SwingUtilities.layoutCompoundLabel(label, label.getFontMetrics(label.getFont()), label.getText(), null,
                label.getVerticalAlignment(), label.getHorizontalAlignment(), label.getVerticalTextPosition(), label.getHorizontalTextPosition(),
                new Rectangle(0, 0, label.getWidth(), label.getHeight()), new Rectangle(), new Rectangle(), label.getIconTextGap());
    }
}
