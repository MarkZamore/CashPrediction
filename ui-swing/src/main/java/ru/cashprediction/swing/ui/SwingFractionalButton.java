package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.JButton;

/** Кнопка пустого состояния с согласованными дробными метриками реального текста и раскладки. */
final class SwingFractionalButton extends JButton {
    private Font measuredFont;
    private FontMetrics measuredMetrics;

    /** Принимает готовую локализованную подпись общей модели. */
    SwingFractionalButton(String text) { super(text); }

    /** Измеряет тем же контекстом, который используется при настоящем рисовании кнопки. */
    @Override public FontMetrics getFontMetrics(Font font) {
        if (!font.equals(measuredFont)) {
            Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
            try { SwingFractionalLabel.hints(graphics); measuredMetrics = graphics.getFontMetrics(font); measuredFont = font; }
            finally { graphics.dispose(); }
        }
        return measuredMetrics;
    }

    /** Рисует штатным ButtonUI без отдельного коэффициента ширины или подмены размера текста. */
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try { SwingFractionalLabel.hints(copy); super.paintComponent(copy); } finally { copy.dispose(); }
    }
}
