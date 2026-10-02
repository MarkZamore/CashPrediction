package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.util.List;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.*;
import ru.cashprediction.core.ui.view.popup.SparklineModel;

/** Проверяет размеры настоящих popup-компонентов без показа окон и использования Robot. */
class SwingPopupMetricsTest {
    @Test void captionsUseTokenBaselineStepsAndGrowWithMeasuredWrappedAndExplicitLines() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            for (var token : List.of(FontToken.HEADER, FontToken.SMALL, FontToken.MICRO)) {
                int height = token == FontToken.HEADER ? DesignTokens.SPARK_HEADER_LINE_HEIGHT
                        : token == FontToken.SMALL ? DesignTokens.SPARK_BODY_LINE_HEIGHT : DesignTokens.SPARK_FOOTER_LINE_HEIGHT;
                for (String text : List.of("first\n\nlast", "caption ".repeat(30), "unbroken".repeat(40))) {
                    var caption = new SwingSparkCaption(text, ColorToken.TEXT_PRIMARY, token, height, DesignTokens.SPARK_WIDTH);
                    assertTrue(caption.lineCount() >= 3);
                    assertEquals(caption.lineCount() * height, caption.getPreferredSize().height);
                    assertEquals(text, caption.getText());
                    for (int index = 0; index < caption.lineCount(); index++) {
                        assertTrue(caption.lineAdvance(index) <= DesignTokens.SPARK_WIDTH + 1);
                        if (caption.lineAdvance(index) > 0) assertTrue(caption.lineBaseline(index) >= index * height && caption.lineBaseline(index) < (index + 1) * height);
                    }
                    caption.setSize(caption.getPreferredSize());
                    var image = new java.awt.image.BufferedImage(caption.getWidth(), caption.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    var graphics = image.createGraphics(); try { caption.paint(graphics); } finally { graphics.dispose(); }
                    boolean painted = false;
                    for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) painted |= (image.getRGB(x, y) >>> 24) != 0;
                    assertTrue(painted);
                    System.out.println("caption token=" + token + " lines=" + caption.lineCount() + " actual=" + caption.getSize() + " firstBaseline=" + caption.getBaseline(caption.getWidth(), caption.getHeight()));
                }
            }
        });
    }

    @Test void zeroAndOnePointUseRealNoDataCaptionWithMeasuredWrappingAndNoEmptyFooter() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            for (int count : new int[]{0, 1}) for (String text : List.of("no data", "no data ".repeat(30))) {
                List<ru.cashprediction.core.ui.view.chart.ChartPoint> points = count == 0 ? List.of() : List.of(new ru.cashprediction.core.ui.view.chart.ChartPoint(0, 0));
                var model = new SparklineModel("card", "header", "explanation", points, null, null, "", "", text);
                JPanel content = SwingPopups.sparkContent(model);
                assertEquals(3, content.getComponentCount());
                var caption = assertInstanceOf(SwingSparkCaption.class, content.getComponent(2));
                assertEquals(text, caption.getText()); assertEquals(SwingLook.font(FontToken.SMALL), caption.getFont());
                assertEquals(caption.lineCount() * DesignTokens.SPARK_BODY_LINE_HEIGHT, caption.getPreferredSize().height);
                if (text.length() > 20) assertTrue(caption.lineCount() > 1); else assertEquals(1, caption.lineCount());
                caption.setSize(caption.getPreferredSize());
                var image = new java.awt.image.BufferedImage(caption.getWidth(), caption.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                var graphics = image.createGraphics(); try { caption.paint(graphics); } finally { graphics.dispose(); }
                boolean painted = false;
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) painted |= (image.getRGB(x, y) >>> 24) != 0;
                assertTrue(painted);
                System.out.println("noData points=" + count + " lines=" + caption.lineCount() + " caption=" + caption.getPreferredSize() + " popup=" + content.getPreferredSize());
            }
        });
    }

    @Test void twoPointsKeepRealChartWhileLongRangeGrowsNaturally() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var points = List.of(new ru.cashprediction.core.ui.view.chart.ChartPoint(0, 0), new ru.cashprediction.core.ui.view.chart.ChartPoint(1, 1));
            var normal = new SparklineModel("card", "header", "explanation", points, null, null, "", "", "");
            JPanel content = SwingPopups.sparkContent(normal);
            assertEquals(3, content.getComponentCount());
            assertFalse(content.getComponent(2) instanceof SwingSparkCaption);
            assertEquals(new Dimension(DesignTokens.SPARK_WIDTH, DesignTokens.SPARK_HEIGHT), content.getComponent(2).getPreferredSize());
            var longRange = new SparklineModel("card", "header", "explanation", points, null, null, "minimum ".repeat(20), "maximum ".repeat(20), "");
            JPanel grown = SwingPopups.sparkContent(longRange);
            JPanel range = (JPanel) grown.getComponent(3);
            for (Component component : range.getComponents()) {
                var caption = (SwingSparkCaption) component;
                assertTrue(caption.lineCount() > 1);
                assertEquals(caption.lineCount() * DesignTokens.SPARK_FOOTER_LINE_HEIGHT, caption.getPreferredSize().height);
            }
            assertTrue(grown.getPreferredSize().height > content.getPreferredSize().height);
        });
    }
    @Test void shortTooltipUsesActualTwelvePixelFontAndNaturalWidth() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            JButton owner = new JButton(); String text = UiText.get("menu.file.save.tip");
            SwingLook.tooltip(owner, text);
            // JavaFX: Tooltip → Swing: JToolTip → Web: div.tooltip
            JToolTip tooltip = owner.createToolTip(); tooltip.setTipText(owner.getToolTipText());
            assertEquals(SwingLook.font(FontToken.LEGEND), tooltip.getFont());
            assertEquals(SwingLook.color(ColorToken.TOOLTIP_BG), tooltip.getBackground());
            int textWidth = tooltip.getFontMetrics(tooltip.getFont()).stringWidth(text);
            assertEquals(textWidth + 16, tooltip.getPreferredSize().width);
            assertTrue(tooltip.getPreferredSize().width < 420);
            assertEquals(tooltip.getFontMetrics(tooltip.getFont()).getHeight() + 16, tooltip.getPreferredSize().height);
            System.out.println("actual tooltip=" + tooltip.getPreferredSize() + " font=" + tooltip.getFont());
            SwingLook.tooltip(owner, "long ".repeat(200)); tooltip.setTipText(owner.getToolTipText());
            assertTrue(tooltip.getPreferredSize().width <= 420);
            assertTrue(tooltip.getPreferredSize().height > 32);
        });
    }

    @Test void sparklineHeaderWrapsWithoutExpandingRealGraphContainer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var model = new SparklineModel("card", "header ".repeat(8), "explanation", List.of(new ru.cashprediction.core.ui.view.chart.ChartPoint(0, 0), new ru.cashprediction.core.ui.view.chart.ChartPoint(1, 1)), null, null, "", "", "");
            JPanel content = SwingPopups.sparkContent(model);
            for (Component component : content.getComponents()) System.out.println("popup child=" + component.getClass().getSimpleName() + " pref=" + component.getPreferredSize() + " min=" + component.getMinimumSize() + " alignment=" + component.getAlignmentX());
            Insets padding = content.getInsets();
            assertEquals(new Insets(9, 9, 9, 9), padding);
            assertEquals(240 + padding.left + padding.right, content.getPreferredSize().width);
            JLabel header = (JLabel) content.getComponent(0);
            assertEquals(240, header.getPreferredSize().width);
            assertTrue(header.getPreferredSize().height > header.getFontMetrics(header.getFont()).getHeight());
            assertEquals(SwingLook.font(FontToken.HEADER), header.getFont());
            assertEquals(3, content.getComponentCount());
            assertEquals(new Dimension(240, 60), content.getComponent(2).getPreferredSize());
            assertEquals(new Dimension(240, 60), content.getComponent(2).getMaximumSize());
        });
    }
}
