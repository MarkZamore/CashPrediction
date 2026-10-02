package ru.cashprediction.swing.ui;

import java.awt.Dimension;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import ru.cashprediction.core.ui.token.FontToken;

/** Резерв строк результата по фактическим метрикам шрифта, без пустых видимых строк. */
final class SwingResultsPanel extends JPanel {
    private final int minLines;

    /** Создаёт общий блок результата с минимальным числом строк из FormRow.Results. */
    SwingResultsPanel(String id, int minLines) {
        this.minLines = minLines;
        setFont(SwingLook.font(FontToken.BASE));
        setOpaque(false); setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        SwingLook.id(this, id);
    }

    /** Предпочтительная высота растёт с реальными дочерними строками, но не опускается ниже резерва. */
    @Override public Dimension getPreferredSize() { return reserve(super.getPreferredSize()); }

    /** Минимальная высота сохраняет тот же резерв при пустом результате ошибки ввода. */
    @Override public Dimension getMinimumSize() { return reserve(super.getMinimumSize()); }

    private Dimension reserve(Dimension measured) {
        return new Dimension(measured.width, Math.max(measured.height, getFontMetrics(getFont()).getHeight() * minLines));
    }
}
