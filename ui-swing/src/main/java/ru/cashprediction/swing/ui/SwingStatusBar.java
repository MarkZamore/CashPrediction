package ru.cashprediction.swing.ui;

import java.awt.*;
import javax.swing.*;
import ru.cashprediction.core.ui.token.*;
import ru.cashprediction.core.ui.view.status.*;

/** Строка состояния из готовых сегментов ядра. */
public final class SwingStatusBar extends JPanel {
    /** Создаёт панель фиксированной высоты. */
    public SwingStatusBar() {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS)); SwingLook.id(this, "status");
        setBackground(SwingLook.color(ColorToken.BG_WINDOW));
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, SwingLook.color(ColorToken.BORDER)), BorderFactory.createEmptyBorder(0, 8, 0, 8)));
        setPreferredSize(new Dimension(0, DesignTokens.STATUS_HEIGHT));
    }
    /** Сохраняет порядок, видимость, текст и цвет сегментов. */
    public void render(StatusModel model) {
        removeAll(); boolean first = true;
        for (StatusSegment segment : model.segments()) {
            if (segment.visible()) {
                if (!first) add(SwingLook.label(" | ", ColorToken.BORDER_STRONG, FontToken.SMALL)); first = false;
            }
            JLabel label = SwingLook.id(new SwingFractionalLabel(segment.text(), segment.color(), FontToken.SMALL), segment.id());
            label.setVisible(segment.visible()); SwingLook.tooltip(label, segment.tooltip()); add(label);
            if (segment.visible() && segment.grow()) add(Box.createHorizontalGlue());
        }
        revalidate(); repaint();
    }
}
