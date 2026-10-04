package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.*;
import ru.cashprediction.core.ui.view.summary.CardModel;

/** Проверяет геометрию и рисование карточек без окон, с учётом дробной растеризации. */
class SwingSummaryGeometryTest {
    /** При обеих ширинах каждый ряд использует одну сетку с общим зазором шесть пикселей. */
    @Test void allNineCardsHaveEqualWidthsAndSharedGapsAt900And1200() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            assertEquals(6, DesignTokens.CARD_GAP);
            for (int panelWidth : new int[]{900, 1200}) {
                var panel = new SwingSummaryPanel(null);
                for (int i = 0; i < 9; i++) panel.add(panel.new Card(model("card" + i)));
                panel.setSize(panelWidth, 1);
                Dimension preferred = panel.getPreferredSize();
                panel.setSize(panelWidth, preferred.height); panel.doLayout();
                Insets in = panel.getInsets();
                int columns = panelWidth == 900 ? 7 : 9;
                int expectedWidth = panelWidth == 900 ? 121 : 126;
                int height = panel.getComponent(0).getPreferredSize().height;
                int rows = panelWidth == 900 ? 2 : 1;
                assertEquals(in.top + in.bottom + rows * height + (rows - 1) * 6, preferred.height);
                for (int i = 0; i < 9; i++) {
                    Component card = panel.getComponent(i);
                    assertEquals(expectedWidth, card.getWidth());
                    assertTrue(card.getWidth() >= DesignTokens.CARD_MIN_WIDTH);
                    assertEquals(height, card.getHeight());
                    assertEquals(in.left + (i % columns) * (expectedWidth + 6), card.getX());
                    assertEquals(in.top + (i / columns) * (height + 6), card.getY());
                    assertTrue(card.getX() + card.getWidth() <= panelWidth - in.right);
                    ((JComponent) card).doLayout();
                    pixels(panel, (SwingSummaryPanel.Card) card, ColorToken.BORDER);
                    if (i % columns > 0) {
                        Component previous = panel.getComponent(i - 1);
                        assertEquals(6, card.getX() - previous.getX() - previous.getWidth());
                    }
                    if (i >= columns) {
                        Component above = panel.getComponent(i - columns);
                        assertEquals(6, card.getY() - above.getY() - above.getHeight());
                        assertEquals(above.getWidth(), card.getWidth());
                    }
                }
                assertEquals(panelWidth, preferred.width);
            }
        });
    }

    /** Прозрачные углы и скруглённая рамка сохраняются при независимых наведении и фокусе. */
    @Test void roundedCardPaintsExactCornersFillAndHoverFocusBorders() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var port = new SwingUiPort(null);
            var panel = new SwingSummaryPanel(port);
            var card = panel.new Card(model("card")); panel.add(card);
            try {
                panel.setSize(900, 1); panel.setSize(900, panel.getPreferredSize().height);
                panel.doLayout(); card.doLayout();
                assertFalse(card.isOpaque()); assertTrue(card.isFocusable());
                assertEquals(6, DesignTokens.RADIUS);
                Insets originalInsets = card.getInsets();
                Dimension originalSize = card.getPreferredSize();
                assertEquals(DesignTokens.CARD_PAD_V + 1, originalInsets.top);
                assertEquals(DesignTokens.CARD_PAD_V + 1, originalInsets.bottom);
                assertEquals(DesignTokens.CARD_PAD_H + 1, originalInsets.left);
                assertEquals(DesignTokens.CARD_PAD_H + 1, originalInsets.right);
                pixels(panel, card, ColorToken.BORDER);
                mouse(card, true); pixels(panel, card, ColorToken.ACCENT);
                focus(card, true); mouse(card, false); pixels(panel, card, ColorToken.ACCENT);
                focus(card, false); pixels(panel, card, ColorToken.BORDER);
                focus(card, true); mouse(card, true); focus(card, false); pixels(panel, card, ColorToken.ACCENT);
                mouse(card, false); pixels(panel, card, ColorToken.BORDER);
                // Переход с карточки на подпись не считается уходом за её границы.
                mouse(card, true);
                var internalExit = new MouseEvent(card, MouseEvent.MOUSE_EXITED, 0, 0,
                        card.title.getX(), card.title.getY(), 0, false);
                for (MouseListener listener : card.getMouseListeners()) listener.mouseExited(internalExit);
                pixels(panel, card, ColorToken.ACCENT);
                mouse(card, false);
                for (JLabel child : new JLabel[]{card.title, card.caption}) {
                    mouse(card, true);
                    // Координаты за пределами дочерней подписи остаются внутри карточки.
                    Point inside = new Point(1, card.getHeight() / 2);
                    assertTrue(card.contains(inside));
                    assertFalse(child.contains(SwingUtilities.convertPoint(card, inside, child)));
                    childExit(card, child, inside);
                    pixels(panel, card, ColorToken.ACCENT);
                    // Настоящий выход из карточки переводится в координаты того же дочернего источника.
                    childExit(card, child, new Point(-2, card.getHeight() / 2));
                    pixels(panel, card, ColorToken.BORDER);
                }
                focus(card, true);
                mouse(card, true);
                childExit(card, card.caption, new Point(card.getWidth() + 2, card.getHeight() / 2));
                pixels(panel, card, ColorToken.ACCENT);
                focus(card, false);
                pixels(panel, card, ColorToken.BORDER);
                assertEquals(originalInsets, card.getInsets());
                assertEquals(originalSize, card.getPreferredSize());
            } finally {
                mouse(card, false); focus(card, false); port.scheduler().shutdown();
                // JavaFX: Popup → Swing: SwingPopups → Web: div[role=tooltip]
                port.popups.hide();
            }
        });
    }


    /** Одна панель пересчитывает ряды при сужении и расширении через порог девяти колонок. */
    @Test void samePanelResizesAcrossNineColumnThreshold() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var panel = nineCards(null);
            for (int width : new int[]{900, 1125, 1126, 1125, 1200, 900}) {
                panel.setSize(width, panel.getHeight());
                panel.setSize(width, panel.getPreferredSize().height);
                panel.doLayout();
                geometry(panel, width);
            }
        });
    }

    /** Повторяет цепочку BorderLayout главного окна и повторную раскладку после revalidate. */
    @Test void parentBorderLayoutKeepsResizedSummaryAndCenterUnclipped() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var panel = nineCards(null);
            var root = new JPanel(new BorderLayout());
            var north = new JPanel(new BorderLayout());
            var controls = new JPanel(new BorderLayout());
            var toolbar = new JPanel();
            toolbar.setPreferredSize(new Dimension(1, DesignTokens.TOOLBAR_HEIGHT));
            var center = new JPanel();
            controls.add(toolbar, BorderLayout.NORTH);
            controls.add(panel, BorderLayout.CENTER);
            north.add(controls, BorderLayout.CENTER);
            root.add(north, BorderLayout.NORTH);
            root.add(center, BorderLayout.CENTER);
            for (int width : new int[]{1200, 1125, 1126, 900, 1200}) {
                root.setSize(width, 600);
                // Первый проход присваивает новую ширину; следующий моделирует revalidate после resize.
                layoutTree(root);
                panel.revalidate();
                root.invalidate();
                layoutTree(root);
                geometry(panel, width);
                assertFalse(panel.isPreferredSizeSet());
                assertEquals(panel.getPreferredSize().height, panel.getHeight());
                assertEquals(toolbar.getHeight() + panel.getHeight(), north.getHeight());
                assertEquals(north.getHeight(), center.getY());
                assertTrue(center.getHeight() > 0);
                for (Component card : panel.getComponents()) {
                    assertTrue(card.getY() + card.getHeight() <= panel.getHeight() - panel.getInsets().bottom);
                }
            }
        });
    }

    /** Дробный transform сохраняет радиус, покрытие рамки и пустые промежутки в координатах устройства. */
    @Test void fractionalPaintingPreservesCornersStrokeCoverageAndGaps() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            var port = new SwingUiPort(null);
            var panel = nineCards(port);
            try {
                for (int width : new int[]{900, 1200}) {
                    panel.setSize(width, 1);
                    panel.setSize(width, panel.getPreferredSize().height);
                    layoutTree(panel);
                    for (double scale : new double[]{1.25, 1.5}) {
                        BufferedImage composed = paint(panel, scale);
                        for (Component component : panel.getComponents()) {
                            var card = (SwingSummaryPanel.Card) component;
                            BufferedImage normal = paint(card, scale);
                            int right = (int) Math.ceil(card.getWidth() * scale) - 1;
                            int bottom = (int) Math.ceil(card.getHeight() * scale) - 1;
                            for (Point corner : new Point[]{new Point(0, 0), new Point(right, 0),
                                    new Point(0, bottom), new Point(right, bottom)}) {
                                assertEquals(0, normal.getRGB(corner.x, corner.y));
                            }
                            assertEquals(0, normal.getRGB(0, (int) scale));
                            assertEquals(SwingLook.color(ColorToken.BG_CARD).getRGB(),
                                    normal.getRGB((int) (4 * scale), (int) (4 * scale)));
                            // Проба отстоит от дробной границы и находится в прозрачном углу.
                            assertEquals(SwingLook.color(ColorToken.BG_WINDOW).getRGB(),
                                    composed.getRGB((int) Math.ceil(card.getX() * scale),
                                            (int) Math.ceil(card.getY() * scale)));
                            mouse(card, true);
                            BufferedImage accent = paint(card, scale);
                            mouse(card, false);
                            int x = (int) (card.getWidth() * scale / 2);
                            int y = (int) (card.getHeight() * scale / 2);
                            int band = (int) Math.ceil(2 * scale) + 1;
                            // JComponent.paint ограничивает правую/нижнюю границу целым device clip.
                            // Сравниваем пересечение геометрической рамки 1px с этим clip, не расширяем допуск.
                            double rightCoverage = Math.floor(card.getWidth() * scale) - (card.getWidth() - 1) * scale;
                            double bottomCoverage = Math.floor(card.getHeight() * scale) - (card.getHeight() - 1) * scale;
                            assertEquals(scale, strokeCoverage(normal, accent, true, x, 0, band), 0.12);
                            assertEquals(bottomCoverage, strokeCoverage(normal, accent, true, x, bottom - band + 1, bottom + 1), 0.12);
                            assertEquals(scale, strokeCoverage(normal, accent, false, y, 0, band), 0.12);
                            assertEquals(rightCoverage, strokeCoverage(normal, accent, false, y, right - band + 1, right + 1), 0.12);
                        }
                        scaledGaps(panel, composed, scale);
                    }
                }
            } finally {
                for (Component component : panel.getComponents()) mouse((SwingSummaryPanel.Card) component, false);
                port.scheduler().shutdown();
                // JavaFX: Popup → Swing: SwingPopups → Web: div[role=tooltip]
                port.popups.hide();
            }
        });
    }

    /** Создаёт все девять реальных карточек на одной панели. */
    private static SwingSummaryPanel nineCards(SwingUiPort port) {
        var panel = new SwingSummaryPanel(port);
        for (int i = 0; i < 9; i++) panel.add(panel.new Card(model("card" + i)));
        return panel;
    }

    /** Проверяет текущую сетку и высоту, включая неполный ряд после resize. */
    private static void geometry(SwingSummaryPanel panel, int width) {
        Insets in = panel.getInsets();
        int columns = Math.min(9, (width - in.left - in.right + 6) / 124);
        assertEquals(width == 1125 ? 8 : width == 1126 ? 9 : width == 900 ? 7 : 9, columns);
        int cardWidth = (width - in.left - in.right - (columns - 1) * 6) / columns;
        int height = panel.getComponent(0).getPreferredSize().height;
        int rows = (9 + columns - 1) / columns;
        assertEquals(in.top + in.bottom + rows * height + (rows - 1) * 6, panel.getPreferredSize().height);
        for (int i = 0; i < 9; i++) {
            Component card = panel.getComponent(i);
            assertEquals(new Rectangle(in.left + i % columns * (cardWidth + 6),
                    in.top + i / columns * (height + 6), cardWidth, height), card.getBounds());
            assertTrue(card.getX() + card.getWidth() <= width - in.right);
            assertTrue(card.getY() + card.getHeight() <= panel.getHeight() - in.bottom);
        }
    }

    /** Выполняет раскладку сверху вниз, не задавая высоту дочерней панели вручную. */
    private static void layoutTree(Container parent) {
        parent.doLayout();
        for (Component component : parent.getComponents()) {
            if (component instanceof Container child) layoutTree(child);
        }
    }

    /** Суммирует покрытие рамки по разнице premultiplied цветов до и после наведения. */
    private static double strokeCoverage(BufferedImage normal, BufferedImage accent,
            boolean vertical, int fixed, int from, int to) {
        Color first = SwingLook.color(ColorToken.BORDER), second = SwingLook.color(ColorToken.ACCENT);
        int shift = 0, delta = 0;
        for (int candidate : new int[]{0, 8, 16}) {
            int difference = ((second.getRGB() >> candidate) & 255) - ((first.getRGB() >> candidate) & 255);
            if (Math.abs(difference) > Math.abs(delta)) { shift = candidate; delta = difference; }
        }
        assertNotEquals(0, delta);
        double coverage = 0;
        for (int offset = from; offset < to; offset++) {
            int a = normal.getRGB(vertical ? fixed : offset, vertical ? offset : fixed);
            int b = accent.getRGB(vertical ? fixed : offset, vertical ? offset : fixed);
            double before = ((a >> shift) & 255) * (a >>> 24) / 255.0;
            double after = ((b >> shift) & 255) * (b >>> 24) / 255.0;
            coverage += (after - before) / delta;
        }
        return coverage;
    }

    /** Сверяет полные пиксели внутри зазора, исключая частично покрытые дробные границы. */
    private static void scaledGaps(SwingSummaryPanel panel, BufferedImage image, double scale) {
        Component first = panel.getComponent(0), next = panel.getComponent(1);
        assertEquals(6, next.getX() - first.getX() - first.getWidth());
        int from = (int) Math.ceil((first.getX() + first.getWidth()) * scale);
        int to = (int) Math.floor(next.getX() * scale);
        assertTrue(to - from >= Math.floor(6 * scale) - 1);
        int y = (int) ((first.getY() + first.getHeight() / 2.0) * scale);
        for (int x = from; x < to; x++) assertEquals(SwingLook.color(ColorToken.BG_WINDOW).getRGB(), image.getRGB(x, y));
        Component below = panel.getComponent(8);
        if (below.getY() > first.getY()) {
            assertEquals(6, below.getY() - first.getY() - first.getHeight());
            from = (int) Math.ceil((first.getY() + first.getHeight()) * scale);
            to = (int) Math.floor(below.getY() * scale);
            assertTrue(to - from >= Math.floor(6 * scale) - 1);
            int x = (int) ((below.getX() + below.getWidth() / 2.0) * scale);
            for (y = from; y < to; y++) assertEquals(SwingLook.color(ColorToken.BG_WINDOW).getRGB(), image.getRGB(x, y));
        }
    }

    /** Использует локализованные подписи ядра, не создавая клиентский каталог текстов. */
    private static CardModel model(String id) {
        return new CardModel(id, UiText.get("toolbar.tb.save"), "177 000", ColorToken.TEXT_PRIMARY,
                UiText.get("form.planSettings.startBalance"), ColorToken.TEXT_MUTED, null, "", "");
    }

    /** Доставляет наведение слушателям невидимой карточки и останавливает таймер при уходе. */
    private static void mouse(SwingSummaryPanel.Card card, boolean entered) {
        var event = new MouseEvent(card, entered ? MouseEvent.MOUSE_ENTERED : MouseEvent.MOUSE_EXITED,
                0, 0, entered ? 10 : -1, entered ? 10 : -1, 0, false);
        for (MouseListener listener : card.getMouseListeners()) {
            if (entered) listener.mouseEntered(event); else listener.mouseExited(event);
        }
    }

    /** Посылает выход от дочернего компонента с явным обратным переводом координат. */
    private static void childExit(SwingSummaryPanel.Card card, JComponent child, Point cardPoint) {
        assertTrue(child.getMouseListeners().length > 0);
        Point local = SwingUtilities.convertPoint(card, cardPoint, child);
        assertNotEquals(cardPoint, local);
        assertEquals(cardPoint, SwingUtilities.convertPoint(child, local, card));
        var event = new MouseEvent(child, MouseEvent.MOUSE_EXITED, 0, 0, local.x, local.y, 0, false);
        for (MouseListener listener : child.getMouseListeners()) listener.mouseExited(event);
    }

    /** Доставляет изменение фокуса без показа окна и запроса фокуса у ОС. */
    private static void focus(SwingSummaryPanel.Card card, boolean gained) {
        var event = new FocusEvent(card, gained ? FocusEvent.FOCUS_GAINED : FocusEvent.FOCUS_LOST);
        for (FocusListener listener : card.getFocusListeners()) {
            if (gained) listener.focusGained(event); else listener.focusLost(event);
        }
    }

    /** Сверяет точные цвета четырёх углов, середины заливки и четырёх прямых участков рамки. */
    private static void pixels(SwingSummaryPanel panel, SwingSummaryPanel.Card card, ColorToken border) {
        BufferedImage transparent = paint(card);
        int right = card.getWidth() - 1, bottom = card.getHeight() - 1;
        for (Point corner : new Point[]{new Point(0, 0), new Point(right, 0),
                new Point(0, bottom), new Point(right, bottom)}) {
            assertEquals(0, transparent.getRGB(corner.x, corner.y));
        }
        // Весь пиксель (0, 1) лежит вне внешней дуги радиуса 6; (1, 1) пересекает её и сглаживается.
        assertEquals(0, transparent.getRGB(0, 1));
        assertEquals(SwingLook.color(ColorToken.BG_CARD).getRGB(), transparent.getRGB(4, 4));
        assertEquals(SwingLook.color(ColorToken.BG_CARD).getRGB(), transparent.getRGB(right / 2, 4));
        for (Point edge : new Point[]{new Point(right / 2, 0), new Point(right / 2, bottom),
                new Point(0, bottom / 2), new Point(right, bottom / 2)}) {
            assertEquals(SwingLook.color(border).getRGB(), transparent.getRGB(edge.x, edge.y));
        }
        BufferedImage composed = paint(panel);
        for (Point corner : new Point[]{new Point(0, 0), new Point(right, 0),
                new Point(0, bottom), new Point(right, bottom)}) {
            assertEquals(SwingLook.color(ColorToken.BG_WINDOW).getRGB(),
                    composed.getRGB(card.getX() + corner.x, card.getY() + corner.y));
        }
    }

    /** Рисует настоящий компонент в память, не запуская GUI и не подменяя его оформление. */
    private static BufferedImage paint(JComponent component) {
        return paint(component, 1.0);
    }

    /** Масштабирует Graphics2D в логических координатах, выделяя полный размер устройства. */
    private static BufferedImage paint(JComponent component, double scale) {
        var image = new BufferedImage((int) Math.ceil(component.getWidth() * scale),
                (int) Math.ceil(component.getHeight() * scale), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try { graphics.scale(scale, scale); component.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }
}
