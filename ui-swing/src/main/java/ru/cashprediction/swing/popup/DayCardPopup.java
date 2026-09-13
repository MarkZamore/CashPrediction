package ru.cashprediction.swing.popup;

import java.awt.Component;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Window;
import java.time.LocalDate;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;
import ru.cashprediction.swing.view.Palette;

/**
 * Карточка дня на графике: дата, баланс на конец дня и события этого дня — Swing-аналог JavaFX
 * {@code PopupWindow} ({@code DayCardPopupWindow}).
 *
 * <p>Безрамочное {@link JWindow}, не забирающее фокус ({@code setFocusableWindowState(false)}): карточка
 * следует за курсором над графиком и не мешает клавиатуре. Скрывается, когда курсор уходит с графика (аналог
 * {@code setAutoHide(true)}).</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
// JavaFX: PopupWindow → Swing: JWindow (undecorated, setFocusableWindowState(false)) → Web: absolute div, autoHide по pointerdown
public final class DayCardPopup {

    /** Больше событий в карточке не помещается; остальные сводятся в строку «и ещё N». */
    private static final int MAX_EVENTS = 8;

    private JWindow window;
    private LocalDate shownDate;

    /** Создаёт карточку (окно появится при первом показе). */
    public DayCardPopup() {
    }

    /**
     * Показывает карточку рядом с точкой экрана.
     *
     * @param invoker  компонент графика (владелец окна)
     * @param screenX  координата x курсора на экране
     * @param screenY  координата y курсора на экране
     * @param date     день
     * @param balance  баланс на конец дня
     * @param events   события дня
     * @param currency валюта плана
     */
    public void show(Component invoker, int screenX, int screenY, LocalDate date, Money balance, List<ForecastRow> events,
                     String currency) {
        if (!invoker.isShowing()) {
            return;
        }
        Window owner = SwingUtilities.getWindowAncestor(invoker);
        if (window == null || window.getOwner() != owner) {
            if (window != null) {
                window.dispose();
            }
            window = new JWindow(owner);
            window.setFocusableWindowState(false);
        }
        if (!date.equals(shownDate) || !window.isVisible()) {
            window.setContentPane(content(date, balance, events, currency));
            window.pack();
            shownDate = date;
        }
        int x = screenX + 16;
        int y = screenY + 16;
        GraphicsConfiguration gc = invoker.getGraphicsConfiguration();
        if (gc != null) {
            Rectangle screen = gc.getBounds();
            if (x + window.getWidth() > screen.x + screen.width) {
                x = screenX - window.getWidth() - 16;
            }
            if (y + window.getHeight() > screen.y + screen.height) {
                y = screenY - window.getHeight() - 16;
            }
        }
        window.setLocation(x, y);
        window.setVisible(true);
    }

    private static JPanel content(LocalDate date, Money balance, List<ForecastRow> events, String currency) {
        JPanel panel = new JPanel(new GridLayout(0, 1, 0, 2));
        panel.setBackground(Palette.POPUP_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Palette.BORDER),
                BorderFactory.createEmptyBorder(6, 10, 6, 10)));
        JLabel title = new JLabel(RuText.weekdayFull(date.getDayOfWeek()) + ", " + DateFormats.ru(date));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        panel.add(title);
        JLabel balanceLabel = new JLabel("Баланс на конец дня: " + balance.format(currency));
        if (balance.isNegative()) {
            balanceLabel.setForeground(Palette.EXPENSE);
        }
        panel.add(balanceLabel);
        List<ForecastRow> shown = events.stream().filter(r -> r.origin() != Origin.START).toList();
        if (shown.isEmpty()) {
            JLabel none = new JLabel("Событий нет");
            none.setForeground(Palette.PAST);
            panel.add(none);
        }
        for (int i = 0; i < Math.min(MAX_EVENTS, shown.size()); i++) {
            ForecastRow r = shown.get(i);
            String amount = r.flags().skipped() ? "пропущено" : r.amount().formatSigned() + " " + currency;
            JLabel line = new JLabel("• " + r.title() + ": " + amount);
            line.setForeground(r.flags().skipped() ? Palette.PAST : r.isIncome() ? Palette.INCOME : Palette.EXPENSE);
            panel.add(line);
        }
        if (shown.size() > MAX_EVENTS) {
            panel.add(new JLabel("и ещё " + (shown.size() - MAX_EVENTS)));
        }
        return panel;
    }

    /** Скрывает карточку. */
    public void hide() {
        if (window != null) {
            window.setVisible(false);
        }
        shownDate = null;
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
