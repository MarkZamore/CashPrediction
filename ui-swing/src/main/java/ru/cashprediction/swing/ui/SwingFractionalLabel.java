package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import javax.swing.JLabel;
import ru.cashprediction.core.ui.token.*;

/** Метка с одинаковыми дробными метриками при измерении и настоящем рисовании строки состояния. */
final class SwingFractionalLabel extends JLabel {
    private Font measuredFont;
    private FontMetrics measuredMetrics;

    /** Создаёт метку из готового общего текста и токенов без изменения размера или семейства шрифта. */
    SwingFractionalLabel(String text, ColorToken color, FontToken font) {
        super(text); setFont(SwingLook.font(font)); setForeground(SwingLook.color(color));
        SwingIcons.decorate(this);
    }

    /** Возвращает метрики того же контекста рисования, которым метка выводит текст. */
    @Override public FontMetrics getFontMetrics(Font font) {
        if (!font.equals(measuredFont)) {
            Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
            try { hints(graphics); measuredMetrics = graphics.getFontMetrics(font); measuredFont = font; }
            finally { graphics.dispose(); }
        }
        return measuredMetrics;
    }

    /** Рисует штатным LabelUI с теми же дробными метриками, которые использует раскладка. */
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try { hints(copy); super.paintComponent(copy); } finally { copy.dispose(); }
    }

    /** Настраивает единый контекст сглаживания и дробных ширин глифов. */
    static void hints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }
}
