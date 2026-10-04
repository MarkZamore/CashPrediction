package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.Placement;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.ui.form.Presentation;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.DesignTokens;

/** Проверяет настоящую шапку формы без окон, экрана и повторного запуска сценариев паритета. */
class SwingDialogHeaderGeometryTest {
    @Test void rasterHeaderUsesSharedIconAndFxInsetsAcrossDialogPresentations() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            for (Presentation presentation : new Presentation[]{Presentation.DIALOG, Presentation.WIZARD,
                    Presentation.TEXT_INPUT, Presentation.CHOICE, Presentation.LIST_CHOICE, Presentation.CONFIRM}) {
                JLabel glyph = new JLabel(), text = new JLabel("header");
                SwingIcons.header(glyph, "?", "");
                Icon source = glyph.getIcon();
                // JavaFX: DialogPane → Swing: JPanel → Web: div.dialog-pane
                JPanel heading = SwingFormDialog.heading(glyph, text, presentation);
                assertSame(source, glyph.getIcon());
                assertEquals(DesignTokens.DIALOG_ICON_SIZE, source.getIconHeight());
                assertEquals(0, glyph.getInsets().top);
                assertEquals(0, glyph.getInsets().bottom);
                int rowHeight = Math.max(source.getIconHeight(), text.getPreferredSize().height);
                assertEquals(rowHeight + heading.getInsets().bottom, heading.getPreferredSize().height);
                heading.setSize(560, heading.getPreferredSize().height); heading.doLayout();
                assertEquals(rowHeight, glyph.getHeight());
                assertEquals(glyph.getY(), text.getY());
                assertEquals(glyph.getHeight(), text.getHeight());
                assertEquals(DesignTokens.FORM_HGAP, text.getX() - glyph.getX() - glyph.getWidth());
                assertEquals("?", glyph.getClientProperty("cp.glyph"));
                assertEquals("", glyph.getText());
            }
        });
    }

    @Test void emptyCsvCaptionStillMeasuresTheIconRow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); JLabel glyph = new JLabel(), text = new JLabel();
            SwingIcons.header(glyph, "\u21e9", "");
            // JavaFX: DialogPane → Swing: JPanel → Web: div.dialog-pane
            JPanel heading = SwingFormDialog.heading(glyph, text, Presentation.DIALOG);
            assertEquals(DesignTokens.DIALOG_ICON_SIZE + 1, heading.getPreferredSize().height);
            assertEquals(glyph.getIcon().getIconHeight() + glyph.getInsets().top + glyph.getInsets().bottom + 1,
                    heading.getPreferredSize().height);
        });
    }

    @Test void multilineAndChangedWizardCaptionsUseNaturalTextHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); JLabel glyph = new JLabel(), text = new JLabel();
            SwingIcons.header(glyph, "\u20bd", "");
            // JavaFX: DialogPane → Swing: JPanel → Web: div.dialog-pane
            JPanel heading = SwingFormDialog.heading(glyph, text, Presentation.WIZARD);
            int iconRow = heading.getPreferredSize().height;
            String caption = UiText.get("form.newPlan.title");
            text.setText(SwingLook.html((caption + "\n").repeat(3) + caption, 200));
            int wrapped = heading.getPreferredSize().height;
            assertTrue(wrapped > iconRow);
            assertEquals(text.getPreferredSize().height + heading.getInsets().bottom, wrapped);
            heading.setSize(260, wrapped); heading.doLayout();
            BufferedImage image = new BufferedImage(260, wrapped, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try { heading.paint(graphics); } finally { graphics.dispose(); }
            assertEquals("\u20bd", glyph.getClientProperty("cp.glyph"));
            assertEquals(SwingLook.html((caption + "\n").repeat(3) + caption, 200), text.getText());
            text.setText("short");
            assertEquals(iconRow, heading.getPreferredSize().height);
        });
    }

    @Test void popupAndAbsentIconKeepTheirOwnNaturalGeometry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            for (String key : new String[]{"?", ""}) {
                JLabel glyph = new JLabel(), text = new JLabel("header"); SwingIcons.header(glyph, key, "");
                // JavaFX: DialogPane → Swing: JPanel → Web: div.dialog-pane
                JPanel popup = SwingFormDialog.heading(glyph, text, Presentation.POPUP);
                assertNull(popup.getBorder()); assertEquals(0, glyph.getInsets().top + glyph.getInsets().bottom);
                assertEquals(!key.isEmpty(), glyph.isVisible());
                assertEquals(Math.max(key.isEmpty() ? 0 : glyph.getIcon().getIconHeight(), text.getPreferredSize().height),
                        popup.getPreferredSize().height);
            }
            JLabel glyph = new JLabel(), text = new JLabel("header"); SwingIcons.header(glyph, "", "");
            // JavaFX: DialogPane → Swing: JPanel → Web: div.dialog-pane
            JPanel heading = SwingFormDialog.heading(glyph, text, Presentation.TEXT_INPUT);
            assertEquals(0, glyph.getPreferredSize().height);
            assertEquals(text.getPreferredSize().height + 1, heading.getPreferredSize().height);
        });
    }

    @Test void measuredHeaderGrowthMovesContentCenterByHalfItsHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); JLabel glyph = new JLabel(), text = new JLabel("header");
            SwingIcons.header(glyph, "?", "");
            // JavaFX: DialogPane → Swing: JPanel → Web: div.dialog-pane
            JPanel heading = SwingFormDialog.heading(glyph, text, Presentation.DIALOG);
            int measured = heading.getPreferredSize().height;
            var owner = new WindowBounds(100, 80, 1200, 800);
            var centered = Placement.centerContent(owner, 600, measured);
            assertEquals(owner.y() + owner.height() / 2, centered.y() + measured / 2.0);
            heading.setBorder(null);
            int bare = heading.getPreferredSize().height;
            var before = Placement.centerContent(owner, 600, bare);
            assertEquals(1, measured - bare);
            assertEquals(0.5, before.y() - centered.y());
        });
    }
}
