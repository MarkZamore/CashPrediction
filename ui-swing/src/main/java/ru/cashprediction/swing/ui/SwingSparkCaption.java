package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.font.*;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import ru.cashprediction.core.ui.token.*;

/** Рисует реальные строки подписи спарклайна с общим шагом baseline и переносом по метрикам TextLayout. */
final class SwingSparkCaption extends JLabel {
    private final int lineHeight, textWidth;
    private final List<TextLayout> lines = new ArrayList<>();

    /** Принимает готовый текст ядра, его шрифт и общий контракт высоты строки. */
    SwingSparkCaption(String text, ColorToken color, FontToken font, int lineHeight, int textWidth) {
        super(text); this.lineHeight = lineHeight; this.textWidth = textWidth;
        setFont(SwingLook.font(font)); setForeground(SwingLook.color(color)); setAlignmentX(0);
        putClientProperty("cp.text", text);
        Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try {
            SwingFractionalLabel.hints(graphics);
            FontRenderContext context = graphics.getFontRenderContext();
            for (String paragraph : text.split("\n", -1)) {
                if (paragraph.isEmpty()) { if (!text.isEmpty()) lines.add(null); continue; }
                AttributedString attributed = SwingIcons.attributed(paragraph, getFont(), color);
                LineBreakMeasurer measure = new LineBreakMeasurer(attributed.getIterator(), context);
                while (measure.getPosition() < paragraph.length()) lines.add(measure.nextLayout(textWidth));
            }
        } finally { graphics.dispose(); }
    }

    /** Возвращает число реально измеренных строк, включая явные пустые строки внутри подписи. */
    int lineCount() { return lines.size(); }

    /** Возвращает реальную ширину глифов строки для проверки переноса, а не ширину внешнего виджета. */
    float lineAdvance(int index) { return lines.get(index) == null ? 0 : lines.get(index).getAdvance(); }

    /** Возвращает реальный baseline выбранной нарисованной строки. */
    float lineBaseline(int index) { return index * lineHeight + (lines.get(index) == null ? 0 : baseline(lines.get(index))); }

    /** Высота растёт по измеренному числу строк, а не по назначению окна или sample-данным. */
    @Override public Dimension getPreferredSize() { return new Dimension(textWidth, lineCount() * lineHeight); }

    /** BoxLayout не должен сжимать строку ниже её общего вертикального контракта. */
    @Override public Dimension getMinimumSize() { return getPreferredSize(); }

    /** Не растягивает строки captions при распределении свободного места popup. */
    @Override public Dimension getMaximumSize() { return getPreferredSize(); }

    /** Сообщает тот же первый baseline, который применяется при фактическом рисовании. */
    @Override public int getBaseline(int width, int height) {
        if (lines.isEmpty() || lines.getFirst() == null) return -1;
        return Math.round(baseline(lines.getFirst()));
    }

    private float baseline(TextLayout layout) {
        float glyphHeight = layout.getAscent() + layout.getDescent() + layout.getLeading();
        return Math.max(0, (lineHeight - glyphHeight) / 2) + layout.getAscent();
    }

    /** Рисует каждую строку TextLayout с общим шагом, без скрытого HTML line-height или подмены dump. */
    @Override protected void paintComponent(Graphics source) {
        Graphics2D graphics = (Graphics2D) source.create();
        try {
            SwingFractionalLabel.hints(graphics); graphics.setColor(getForeground());
            for (int index = 0; index < lines.size(); index++) {
                TextLayout layout = lines.get(index); if (layout == null) continue;
                float x = getHorizontalAlignment() == RIGHT ? Math.max(0, getWidth() - layout.getAdvance()) : 0;
                layout.draw(graphics, x, index * lineHeight + baseline(layout));
            }
        } finally { graphics.dispose(); }
    }
}
