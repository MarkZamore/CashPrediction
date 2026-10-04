package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import javax.swing.*;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.menu.ContextTarget;
import ru.cashprediction.core.ui.token.*;
import ru.cashprediction.core.ui.view.summary.*;

/** Карточки одинаковой ширины с переносом, не обрезающие девять значений на узком окне. */
public final class SwingSummaryPanel extends JPanel {
    private final SwingUiPort port;
    private final SwingPaintContext paintContext;
    private final JLabel unavailable = new JLabel();

    /** Создаёт панель с переносом и общим фоном. */
    public SwingSummaryPanel(SwingUiPort port) {
        this(port, null);
    }

    /** Связывает реальные карточки с context content root до первого render. */
    SwingSummaryPanel(SwingUiPort port, SwingPaintContext paintContext) {
        this.paintContext = paintContext;
        this.port = port; setLayout(new WrapLayout()); SwingLook.id(this, "summary");
        setBackground(SwingLook.color(ColorToken.BG_WINDOW)); setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
    }

    /** Наблюдает настоящий полный paint панели и автоматические paint вне capture. */
    @Override public void paint(Graphics graphics) {
        if (paintContext == null) super.paint(graphics); else paintContext.paint(this, graphics, super::paint);
    }

    /** Применяет только готовые значения и цвета карточек. */
    public void render(SummaryModel model) {
        port.popups.hide(); removeAll(); setVisible(model.visible());
        unavailable.setText(model.unavailableText()); unavailable.setForeground(SwingLook.color(ColorToken.EXPENSE));
        if (!model.unavailableText().isEmpty()) add(unavailable);
        else model.cards().forEach(card -> add(new Card(card)));
        revalidate(); repaint();
    }

    /** Реальная карточка с доступным фокусом и событиями мыши. */
    final class Card extends JPanel {
        final JLabel title, value, caption;
        final CardModel data;
        private final Timer delay;
        private boolean hovered;
        private boolean focused;
        Card(CardModel model) {
            data = model; setLayout(new BoxLayout(this, BoxLayout.Y_AXIS)); setFocusable(true);
            SwingLook.id(this, model.id()); setBackground(SwingLook.color(ColorToken.BG_CARD)); setOpaque(false);
            SwingLook.tooltip(this, model.explanation());
            setBorder(BorderFactory.createEmptyBorder(DesignTokens.CARD_PAD_V + 1, DesignTokens.CARD_PAD_H + 1,
                    DesignTokens.CARD_PAD_V + 1, DesignTokens.CARD_PAD_H + 1));
            title = new CardLine(model.title(), ColorToken.TEXT_MUTED, FontToken.SMALL, DesignTokens.CARD_TITLE_LINE_HEIGHT);
            value = new CardLine(model.value(), model.valueColor(), FontToken.CARD, DesignTokens.CARD_VALUE_LINE_HEIGHT);
            caption = new CardLine(model.caption(), model.captionColor(), FontToken.SMALL, DesignTokens.CARD_CAPTION_LINE_HEIGHT);
            add(title); add(Box.createVerticalStrut(DesignTokens.CARD_CONTENT_GAP)); add(value);
            add(Box.createVerticalStrut(DesignTokens.CARD_CONTENT_GAP)); add(caption);
            delay = new Timer(DesignTokens.CARD_POPUP_DELAY_MS, e -> port.popups.spark(port.intents().sparkline(model.id()), this)); delay.setRepeats(false);
            // JavaFX: ContextMenuEvent → Swing: MouseEvent.isPopupTrigger → Web: contextmenu
            MouseAdapter mouse = new MouseAdapter() {
                /** Начинает задержку показа спарклайна. */
                @Override public void mouseEntered(MouseEvent e) { hovered = true; repaint(); delay.restart(); }
                /** Закрывает всплывающую карточку при уходе мыши. */
                @Override public void mouseExited(MouseEvent e) {
                    hovered = contains(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), Card.this));
                    repaint();
                    if (!hovered) { delay.stop(); port.popups.hide(); }
                }
                /** Показывает контекстное меню на платформенном триггере. */
                @Override public void mousePressed(MouseEvent e) { popup(e); }
                /** Показывает контекстное меню на отпускании. */
                @Override public void mouseReleased(MouseEvent e) { popup(e); }
                /** Двойной щелчок отправляет общую команду карточки. */
                @Override public void mouseClicked(MouseEvent e) {
                    delay.stop(); port.popups.hide(); requestFocusInWindow();
                    if (e.getClickCount() == 2) port.intents().command(CommandId.CARD_SHOW_IN_TABLE, new CommandArgs(null, null, model.id(), null, null), InvokeSource.MAIN);
                }
                private void popup(MouseEvent e) { if (e.isPopupTrigger()) { delay.stop(); port.popups.hide(); port.context(new ContextTarget.Card(model.id()), Card.this, e.getX(), e.getY()); } }
            };
            addMouseListener(mouse);
            for (Component label : getComponents()) label.addMouseListener(mouse);
            addFocusListener(new FocusAdapter() {
                /** Показывает тот же спарклайн с клавиатуры. */
                @Override public void focusGained(FocusEvent e) { focused = true; repaint(); delay.restart(); }
                /** Закрывает спарклайн при уходе фокуса. */
                @Override public void focusLost(FocusEvent e) { focused = false; repaint(); delay.stop(); port.popups.hide(); }
            });
        }

        /** Открывает owner вокруг component/border/children; полный delegate census пока Unsupported. */
        @Override public void paint(Graphics graphics) {
            if (paintContext == null) super.paint(graphics); else paintContext.paint(this, graphics, super::paint);
        }

        /** Рисует только скруглённую заливку, оставляя углы прозрачными для фона панели. */
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                copy.setColor(getBackground());
                if (paintContext == null) copy.fill(outline());
                else paintContext.fill(this, copy, outline(), "SwingSummaryPanel.Card.paintComponent");
            } finally { copy.dispose(); }
        }

        /** Рисует рамку толщиной один пиксель; наведение и фокус независимо сохраняют акцент. */
        @Override protected void paintBorder(Graphics graphics) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                copy.setStroke(new BasicStroke(1f));
                copy.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                copy.setColor(SwingLook.color(hovered || focused ? ColorToken.ACCENT : ColorToken.BORDER));
                if (paintContext == null) copy.draw(outline());
                else paintContext.draw(this, copy, outline(), "SwingSummaryPanel.Card.paintBorder");
            } finally { copy.dispose(); }
        }

        /** Совмещает внешнюю границу однопиксельной рамки с границами компонента. */
        private RoundRectangle2D outline() {
            return new RoundRectangle2D.Double(0.5, 0.5, Math.max(0, getWidth() - 1), Math.max(0, getHeight() - 1),
                    DesignTokens.RADIUS * 2 - 1, DesignTokens.RADIUS * 2 - 1);
        }
    }

    /** Настоящая строка карточки: общий line-height и штатное рисование/многоточие LabelUI. */
    private static final class CardLine extends JLabel {
        private final int lineHeight;

        /** Сохраняет готовый текст и шрифт ядра, не назначая общую высоту всей карточки. */
        CardLine(String text, ColorToken color, FontToken font, int lineHeight) {
            super(text); this.lineHeight = lineHeight;
            setFont(SwingLook.font(font)); setForeground(SwingLook.color(color)); setAlignmentX(0);
        }

        /** Не обрезает глифы, если реальная метрика шрифта превышает общий шаг строки. */
        @Override public Dimension getPreferredSize() {
            Dimension natural = super.getPreferredSize();
            return new Dimension(natural.width, Math.max(lineHeight, natural.height));
        }

        /** Горизонтальное многоточие допустимо, вертикальное сжатие строки недопустимо. */
        @Override public Dimension getMinimumSize() { return new Dimension(0, getPreferredSize().height); }

        /** Свободная ширина отдаётся подписи, но не меняет её вертикальный контракт. */
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    }

    /** Раскладка вычисляет только размеры виджетов, а не значения карточек. */
    private static final class WrapLayout implements LayoutManager {
        /** Добавление компонента не требует состояния. */
        @Override public void addLayoutComponent(String name, Component component) { }
        /** Удаление компонента не требует состояния. */
        @Override public void removeLayoutComponent(Component component) { }
        /** Возвращает высоту с учётом переноса карточек. */
        @Override public Dimension preferredLayoutSize(Container parent) { return size(parent); }
        /** Возвращает минимальный размер раскладки. */
        @Override public Dimension minimumLayoutSize(Container parent) { return size(parent); }
        private Dimension size(Container parent) {
            Insets in = parent.getInsets(); int width = parent.getWidth() > 0 ? parent.getWidth() : DesignTokens.MAIN_DEFAULT_WIDTH;
            int available = Math.max(DesignTokens.CARD_MIN_WIDTH, width - in.left - in.right);
            int perRow = columns(parent, available);
            int height = 0; for (Component child : parent.getComponents()) height = Math.max(height, child.getPreferredSize().height);
            int rows = (parent.getComponentCount() + perRow - 1) / perRow;
            return new Dimension(width, in.top + in.bottom + rows * height + Math.max(0, rows - 1) * DesignTokens.CARD_GAP);
        }
        /** Одна сетка колонок задаёт одинаковую ширину во всех рядах, включая неполный последний. */
        private int columns(Container parent, int available) {
            return Math.max(1, Math.min(parent.getComponentCount(),
                    (available + DesignTokens.CARD_GAP) / (DesignTokens.CARD_MIN_WIDTH + DesignTokens.CARD_GAP)));
        }
        /** Размещает карточки равными колонками без горизонтальной прокрутки. */
        @Override public void layoutContainer(Container parent) {
            Insets in = parent.getInsets(); int available = Math.max(DesignTokens.CARD_MIN_WIDTH, parent.getWidth() - in.left - in.right);
            int perRow = columns(parent, available);
            int width = (available - (perRow - 1) * DesignTokens.CARD_GAP) / perRow, height = 0;
            for (Component child : parent.getComponents()) height = Math.max(height, child.getPreferredSize().height);
            for (int i = 0; i < parent.getComponentCount(); i++) parent.getComponent(i).setBounds(
                    in.left + i % perRow * (width + DesignTokens.CARD_GAP),
                    in.top + i / perRow * (height + DesignTokens.CARD_GAP), width, height);
        }
    }
}
