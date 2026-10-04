package ru.cashprediction.fx.ui;

import java.io.ByteArrayInputStream;
import javafx.geometry.Insets;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.token.DesignTokens;
import ru.cashprediction.core.ui.token.UiIcons;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет настоящие intrinsic-размеры шапки с общим PNG без toolkit, окон и подмены дампа. */
class FxFormHeadingTest {
    /** PNG заголовка вместе с разделителем занимает 27 px, без прежних четырёх пикселей вокруг глифа. */
    @Test void sharedIconDeterminesSingleLineHeaderHeight() {
        for (String key : new String[]{"\u20bd", "\u21bb", "\u21e9", "\u2699", "\u25ce", "\u270e"}) {
            StackPane glyph = glyph(key);
            glyph.setPadding(new Insets(DesignTokens.SPACING / 2.0, 0, DesignTokens.SPACING / 2.0, 0));
            double previous = glyph.prefHeight(-1);
            Region text = text(20);
            FxFormHeading heading = new FxFormHeading(glyph, text);
            assertEquals(DesignTokens.SPACING, previous - glyph.prefHeight(-1), key);
            assertEquals(DesignTokens.DIALOG_ICON_SIZE + 1, heading.prefHeight(500), key);
            assertEquals(Insets.EMPTY, glyph.getPadding());
            assertEquals(Insets.EMPTY, heading.getPadding());
        }
    }

    /** Высоту многострочной шапки задаёт текст, а не фиксированная высота PNG или всего диалога. */
    @Test void tallerTextGrowsNaturallyAndKeepsTheDivider() {
        for (double height : new double[]{40, 60}) {
            Region text = text(height);
            FxFormHeading heading = new FxFormHeading(glyph("\u20bd"), text);
            assertEquals(height + 1, heading.prefHeight(500));
            heading.resize(500, heading.prefHeight(500));
            heading.layout();
            assertEquals(height, text.getHeight());
            assertEquals(0, text.getMinWidth());
        }
    }

    private static StackPane glyph(String key) {
        Image image = new Image(new ByteArrayInputStream(UiIcons.png(key).orElseThrow()));
        assertFalse(image.isError());
        ImageView graphic = new ImageView(image);
        graphic.setFitWidth(DesignTokens.DIALOG_ICON_SIZE);
        graphic.setFitHeight(DesignTokens.DIALOG_ICON_SIZE);
        graphic.setPreserveRatio(true);
        return new StackPane(graphic);
    }

    private static Region text(double height) {
        Region text = new Region();
        text.setPrefSize(200, height);
        return text;
    }
}
