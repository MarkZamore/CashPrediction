package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import ru.cashprediction.core.app.Placement;
import ru.cashprediction.core.ui.form.*;

/** Всплывающая быстрая правка на PopupFactory с настоящими полями общей формы. */
public final class SwingQuickEditPopup extends SwingFormDialog {
    private Popup popup;
    private AWTEventListener outside;
    private boolean shown;

    /** Создаёт содержимое быстрой правки без собственного алгоритма изменения суммы. */
    public SwingQuickEditPopup(SwingUiPort port, FormSession session, FormSpec spec, FormView view, Placement placement) {
        // JavaFX: Popup → Swing: PopupFactory → Web: div быстрой правки
        super(port, session, spec, view, placement);
    }

    /** Обновляет реальное содержимое и размер окна PopupFactory при появлении или исчезновении ошибки. */
    @Override public void update(FormView view) {
        super.update(view);
        if (shown && content.isShowing()) {
            sizeContent();
            Window window = SwingUtilities.getWindowAncestor(content);
            // JavaFX: Popup → Swing: JWindow окна PopupFactory → Web: div быстрой правки
            if (window instanceof JWindow) window.pack();
            else {
                // Lightweight и medium-weight PopupFactory размещают свой внешний контейнер в слое владельца.
                Container container = content;
                while (container.getParent() != null && container.getParent() != port.frame.getLayeredPane()) container = container.getParent();
                if (container.getParent() == port.frame.getLayeredPane()) {
                    container.setSize(container.getPreferredSize()); container.validate();
                }
                content.revalidate(); content.repaint();
            }
        }
    }

    /** Показывает реальный PopupFactory и регистрирует окно только после видимости содержимого. */
    @Override public void showLater() {
        SwingUtilities.invokeLater(() -> {
            if (closed) return;
            Point p = port.frame.getLocationOnScreen(); p.translate(port.frame.getWidth() / 2 - 150, port.frame.getHeight() / 2 - 50);
            if (placement.anchor() != null) {
                int row = port.frame.model.table().indexOf(placement.anchor().rowId());
                int col = 0; for (int i = 0; i < port.frame.model.table().columns().size(); i++) if (port.frame.model.table().columns().get(i).id().equals(placement.anchor().columnId())) col = i;
                if (row >= 0) {
                    Rectangle cell = port.frame.table.table.getCellRect(row, col, true);
                    if (port.frame.table.table.getVisibleRect().intersects(cell)) { p = new Point(cell.x, cell.y + cell.height); SwingUtilities.convertPointToScreen(p, port.frame.table.table); }
                }
            }
            if (placement.bounds() != null) p = new Point((int) placement.bounds().x(), (int) placement.bounds().y());
            sizeContent();
            // JavaFX: Popup → Swing: PopupFactory.getPopup → Web: div быстрой правки
            popup = PopupFactory.getSharedInstance().getPopup(port.frame, content, p.x, p.y); popup.show();
            shown = content.isShowing(); if (!shown) throw new IllegalStateException("Popup not shown");
            session.shown();
            fields.values().stream().flatMap(java.util.List::stream).findFirst().ifPresent(b -> { b.input.requestFocusInWindow(); if (b.input instanceof javax.swing.text.JTextComponent text) text.selectAll(); });
            outside = event -> {
                if (event instanceof MouseEvent mouse && mouse.getID() == MouseEvent.MOUSE_PRESSED && mouse.getSource() instanceof Component source && !SwingUtilities.isDescendingFrom(source, content)) session.closeRequested();
            };
            Toolkit.getDefaultToolkit().addAWTEventListener(outside, AWTEvent.MOUSE_EVENT_MASK);
        });
    }

    /** Применяет общую ширину popup-контента, сохраняя вычисленную реальными виджетами высоту. */
    void sizeContent() {
        content.setPreferredSize(null);
        invalidateLayout(content);
        content.setPreferredSize(new Dimension(spec.width(), content.getPreferredSize().height));
    }

    /** Сбрасывает кэш раскладки до измерения: revalidate показанного popup выполняется позднее в EDT. */
    private static void invalidateLayout(Container container) {
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) invalidateLayout(nested);
            else child.invalidate();
        }
        container.invalidate();
    }

    /** Подтверждает текущее поле по Enter, в том числе после переноса фокуса внутри всплывающего содержимого. */
    void submit() {
        commit();
        fields.values().stream().flatMap(java.util.List::stream).findFirst().ifPresent(binding -> session.fieldSubmitted(binding.spec.id()));
    }
    /** Закрывает всплывающее содержимое и удаляет глобальный слушатель. */
    @Override public void close() {
        shown = false; if (popup != null) popup.hide();
        if (outside != null) Toolkit.getDefaultToolkit().removeAWTEventListener(outside); super.close();
    }
    /** Возвращает реальные границы PopupFactory вместо скрытого каркаса. */
    @Override public ru.cashprediction.core.session.WindowBounds bounds() {
        if (!content.isShowing()) return super.bounds(); Point p = content.getLocationOnScreen(); return new ru.cashprediction.core.session.WindowBounds(p.x, p.y, content.getWidth(), content.getHeight());
    }
    /** Проверяет фактический показ всплывающего содержимого. */
    @Override public boolean showing() { return shown && content.isShowing(); }
    /** Поднимает фокус в поле быстрой правки. */
    @Override public void toFront() { fields.values().stream().flatMap(java.util.List::stream).findFirst().ifPresent(b -> b.input.requestFocusInWindow()); }
}
