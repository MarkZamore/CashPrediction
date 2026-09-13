package ru.cashprediction.swing.popup;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import ru.cashprediction.swing.view.Palette;

/**
 * Всплывающая карточка со спарклайном при наведении на карточку сводки — Swing-аналог JavaFX {@code PopupControl}
 * ({@code SparklinePopupControl} со своим {@code Skin}).
 *
 * <p>У {@code PopupControl} внешний вид задаёт отдельный скин; здесь ту же роль играет панель с рамкой
 * ({@code Border}) внутри безрамочного {@link JWindow}. Окно не забирает фокус
 * ({@code setFocusableWindowState(false)}): карточка появляется при наведении и не должна отнимать клавиатуру у
 * главного окна. Окно создаётся заново для каждого владельца: {@code JWindow} привязан к окну-владельцу.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: PopupControl → Swing: SwingPopupControl = JWindow + JPanel с Border → Web: .popover div
public final class SwingPopupControl {

    private JWindow window;
    private final JPanel skin = new JPanel(new BorderLayout());

    /** Создаёт всплывающую карточку (окно появится при первом показе). */
    public SwingPopupControl() {
        // «Скин» карточки: фон и рамка, как CSS-класс .sparkline-popup в JavaFX-клиенте.
        skin.setBackground(Palette.POPUP_BG);
        skin.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Palette.BORDER),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    }

    /**
     * Показывает карточку под компонентом.
     *
     * @param anchor  компонент, под которым показать (карточка сводки)
     * @param content содержимое
     */
    public void show(Component anchor, JComponent content) {
        if (!anchor.isShowing()) {
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(anchor);
        if (window == null || window.getOwner() != owner) {
            if (window != null) {
                window.dispose();
            }
            window = new JWindow(owner);
            window.setFocusableWindowState(false);
            window.setContentPane(skin);
        }
        skin.removeAll();
        skin.add(content, BorderLayout.CENTER);
        window.pack();
        Point p = anchor.getLocationOnScreen();
        int x = p.x;
        int y = p.y + anchor.getHeight() + 2;
        // Не даём карточке уйти за правый или нижний край экрана.
        GraphicsConfiguration gc = anchor.getGraphicsConfiguration();
        if (gc != null) {
            Rectangle screen = gc.getBounds();
            x = Math.min(x, screen.x + screen.width - window.getWidth() - 4);
            if (y + window.getHeight() > screen.y + screen.height) {
                y = p.y - window.getHeight() - 2;
            }
        }
        window.setLocation(x, y);
        window.setVisible(true);
    }

    /** Скрывает карточку. */
    public void hide() {
        if (window != null) {
            window.setVisible(false);
        }
    }

    /**
     * Показана ли карточка.
     *
     * @return {@code true}, если окно видно
     */
    public boolean isShowing() {
        return window != null && window.isVisible();
    }
}
