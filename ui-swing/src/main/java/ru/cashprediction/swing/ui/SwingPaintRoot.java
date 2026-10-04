package ru.cashprediction.swing.ui;

import java.awt.BorderLayout;
import java.awt.Graphics;
import javax.swing.JPanel;

/** Настоящий content root с ранним root-local context только в selftest запуске. */
final class SwingPaintRoot extends JPanel {
    private final SwingPaintContext context;

    /** Создаёт context до конструирования toolbar и первой установки его PNG. */
    SwingPaintRoot(boolean selftest) {
        super(new BorderLayout()); context = selftest ? new SwingPaintContext(this) : null;
    }

    /** Возвращает context именно этого объекта root, без поиска по маркерам. */
    SwingPaintContext context() { return context; }

    /** Наблюдает полный callback, включая border/children и paint вне собственной эпохи. */
    @Override public void paint(Graphics graphics) {
        if (context == null) super.paint(graphics); else context.paint(this, graphics, super::paint);
    }
}
