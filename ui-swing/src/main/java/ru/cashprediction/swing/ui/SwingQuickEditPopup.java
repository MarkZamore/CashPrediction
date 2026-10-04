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
    private Window popupHost;
    private FocusTraversalPolicy previousTraversal;
    private WindowFocusListener activation;
    private FocusListener inputFocus;
    private boolean focusPending;

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
            // JavaFX: Popup → Swing: JPopupMenu-контейнер PopupFactory → Web: div быстрой правки
            JPopupMenu surface = new JPopupMenu();
            surface.setLayout(new BorderLayout()); surface.setBorder(null); surface.setFocusable(true);
            surface.add(content);
            // PopupFactory выбирает фокусируемое окно до создания peer только для JPopupMenu
            // с обычными контролами. JPanel и позднее setFocusableWindowState этого не гарантируют.
            // JavaFX: Popup → Swing: PopupFactory.getPopup → Web: div быстрой правки
            popup = new InputPopupFactory().getPopup(port.frame, surface, p.x, p.y);
            popupHost = SwingUtilities.getWindowAncestor(content);
            // Оболочка меню нужна фабрике лишь для выбора фокусируемого peer.
            // В настоящем окне оставляем обычную панель: JPopupMenu перенаправляет обход фокуса меню.
            if (popupHost instanceof JWindow window) {
                window.getContentPane().remove(surface);
                window.getContentPane().add(content, BorderLayout.CENTER);
            }
            // JPopupMenu исключается стандартной политикой обхода: без defaultComponent
            // Window.isFocusableWindow() остаётся false даже при focusableWindowState=true.
            previousTraversal = popupHost.getFocusTraversalPolicy();
            popupHost.setFocusTraversalPolicy(new LayoutFocusTraversalPolicy() {
                /** Единственное поле popup служит исходной целью нативной активации. */
                @Override public Component getDefaultComponent(Container root) { return input(); }
            });
            focusPending = true;
            activation = new WindowAdapter() {
                /** После нативной активации завершает первоначальный запрос фокуса поля. */
                @Override public void windowGainedFocus(WindowEvent event) {
                    // Нативное событие ещё завершает назначение focusedWindow в KeyboardFocusManager.
                    SwingUtilities.invokeLater(SwingQuickEditPopup.this::requestInputFocus);
                }
            };
            popupHost.addWindowFocusListener(activation);
            inputFocus = new FocusAdapter() {
                /** Выделяет исходную сумму только после фактического получения фокуса. */
                @Override public void focusGained(FocusEvent event) {
                    if (!focusPending || closed) return;
                    focusPending = false;
                    if (event.getComponent() instanceof javax.swing.text.JTextComponent text) text.selectAll();
                }
            };
            input().addFocusListener(inputFocus);
            popup.show();
            shown = content.isShowing(); if (!shown) throw new IllegalStateException("Popup not shown");
            session.shown();
            focusInput();
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
        if (closed) return;
        focusPending = false;
        if (popupHost != null && activation != null) popupHost.removeWindowFocusListener(activation);
        if (inputFocus != null) input().removeFocusListener(inputFocus);
        if (popupHost != null && previousTraversal != null) popupHost.setFocusTraversalPolicy(previousTraversal);
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
    @Override public void toFront() { focusInput(); }

    /** Активирует настоящий контейнер popup и переносит фокус после нативной активации окна. */
    private void focusInput() {
        if (closed || !content.isShowing()) return;
        focusPending = !input().isFocusOwner();
        popupHost.toFront();
        // requestFocus, в отличие от requestFocusInWindow, активирует собственное окно поля.
        // Запрос самому полю также удерживает ввод в очереди AWT до доставки фокуса.
        requestInputFocus();
    }

    /** Запрашивает фокус лишь пока открытый popup ещё ожидает его передачи полю. */
    private void requestInputFocus() {
        if (!closed && focusPending && content.isShowing()) {
            if (popupHost.isFocused()) input().requestFocusInWindow();
            else input().requestFocus();
        }
    }

    /** Возвращает настоящий однострочный редактор быстрой правки. */
    private JComponent input() {
        return fields.values().stream().flatMap(java.util.List::stream).findFirst().orElseThrow().input;
    }

    /** Создаёт собственное нативное окно ввода без изменения общей фабрики подсказок и меню. */
    private static final class InputPopupFactory extends PopupFactory {
        /** Запрещает lightweight/medium-weight контейнеры, которым недоступна отдельная активация. */
        @Override public Popup getPopup(Component owner, Component contents, int x, int y) {
            // JavaFX: Popup → Swing: heavyweight PopupFactory → Web: div быстрой правки
            return super.getPopup(owner, contents, x, y, true);
        }
    }
}
