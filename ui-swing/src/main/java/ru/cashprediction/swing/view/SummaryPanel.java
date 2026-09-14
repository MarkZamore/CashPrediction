package ru.cashprediction.swing.view;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import ru.cashprediction.core.forecast.ChartSeries;
import ru.cashprediction.core.forecast.DailyPoint;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastSummary;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.swing.popup.Sparkline;
import ru.cashprediction.swing.popup.SwingPopupControl;

/**
 * Панель сводки над таблицей: карточки «Сейчас», «Через 1/3/6/12 мес.», «Минимум», «Первый минус»,
 * «Средний итог/мес», «Цель».
 *
 * <p>Все числа считаются от {@code anchor = max(начало плана, сегодня)} ({@code ForecastSummary}). Наведение на
 * карточку показывает {@link SwingPopupControl} со спарклайном баланса за соответствующий промежуток (аналог
 * JavaFX {@code PopupControl}); правая кнопка — контекстное меню карточки.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
public final class SummaryPanel extends JPanel {

    /** Реакции карточек, которые выполняет главное окно. */
    public interface CardHandler {
        /**
         * Показать контекстное меню карточки.
         *
         * @param title     заголовок карточки
         * @param value     значение карточки текстом
         * @param date      дата карточки или {@code null}
         * @param component карточка
         * @param x         координата x
         * @param y         координата y
         */
        void showContextMenu(String title, String value, LocalDate date, Component component, int x, int y);
    }

    /** Одна карточка: подписи и то, что показать во всплывающей карточке. */
    private final class Card extends JPanel {
        private final JLabel title = new JLabel();
        private final JLabel value = new JLabel("-");
        private final JLabel note = new JLabel(" ");
        private LocalDate date;
        private LocalDate sparkFrom;
        private LocalDate sparkTo;
        private Money reference;
        private String explanation = "";
        /**
         * Полный текст подписи для всплывающей карточки. Карточек девять, и на узком окне длинная подпись
         * обрезалась бы многоточием, поэтому на самой карточке показывается короткий вариант, а полный — здесь.
         */
        private String detailText = "";

        Card(String caption) {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(Palette.CARD_BG);
            setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Palette.BORDER),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)));
            title.setText(caption);
            title.setForeground(Palette.PAST);
            title.setFont(title.getFont().deriveFont(title.getFont().getSize2D() - 1f));
            value.setFont(value.getFont().deriveFont(Font.BOLD, value.getFont().getSize2D() + 1f));
            note.setForeground(Palette.PAST);
            note.setFont(note.getFont().deriveFont(note.getFont().getSize2D() - 1f));
            add(title);
            add(value);
            add(note);
            // JavaFX: ContextMenuEvent → Swing: MouseAdapter.isPopupTrigger() в mousePressed и mouseReleased → Web: contextmenu + preventDefault
            MouseAdapter mouse = new MouseAdapter() {
                /** Курсор вошёл в карточку: показывается всплывающая карточка со спарклайном. */
                @Override
                public void mouseEntered(MouseEvent e) {
                    hideTimer.stop();
                    showPopup(Card.this);
                }

                /** Курсор покинул компонент: всплывающие элементы скрываются. */
                @Override
                public void mouseExited(MouseEvent e) {
                    // Небольшая задержка: курсор переходит между подписями внутри карточки.
                    hideTimer.restart();
                }

                /** Нажатие кнопки мыши: на части платформ именно оно является запросом контекстного меню. */
                @Override
                public void mousePressed(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        menu(e);
                    }
                }

                /** Отпускание кнопки мыши: на Windows запрос контекстного меню приходит здесь. */
                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger()) {
                        menu(e);
                    }
                }
            };
            addMouseListener(mouse);
            for (JLabel label : List.of(title, value, note)) {
                label.addMouseListener(mouse);
            }
        }

        private void menu(MouseEvent e) {
            popupControl.hide();
            Component source = e.getComponent();
            java.awt.Point p = javax.swing.SwingUtilities.convertPoint(source, e.getPoint(), this);
            handler.showContextMenu(title.getText(), value.getText(), date, this, p.x, p.y);
        }

        void set(String valueText, Color color, String noteText, LocalDate cardDate, LocalDate from, LocalDate to,
                 Money line, String tooltip) {
            value.setText(valueText);
            value.setForeground(color == null ? Color.BLACK : color);
            note.setText(noteText == null || noteText.isBlank() ? " " : noteText);
            date = cardDate;
            sparkFrom = from;
            sparkTo = to;
            reference = line;
            explanation = tooltip;
            // Подробность относится к прежним значениям карточки — сбрасываем; вызывающий задаёт её заново.
            detailText = "";
        }

        /**
         * Задаёт полный текст подписи, который показывается во всплывающей карточке.
         *
         * @param text полный текст или {@code null}
         */
        void detail(String text) {
            detailText = text == null ? "" : text;
        }
    }

    private final CardHandler handler;
    private final List<Card> cards = new ArrayList<>();
    private final Card now = new Card("Сейчас");
    private final Card month1 = new Card("Через 1 мес.");
    private final Card month3 = new Card("Через 3 мес.");
    private final Card month6 = new Card("Через 6 мес.");
    private final Card month12 = new Card("Через 12 мес.");
    private final Card minimum = new Card("Минимум");
    private final Card firstNegative = new Card("Первый минус");
    private final Card average = new Card("Средний итог/мес");
    private final Card goal = new Card("Цель");
    // JavaFX: PopupControl → Swing: SwingPopupControl (JWindow + JPanel с Border) → Web: .popover div
    private final SwingPopupControl popupControl = new SwingPopupControl();
    private final Timer hideTimer = new Timer(150, e -> popupControl.hide());
    private Forecast forecast;
    private String currency = "₽";

    /**
     * Создаёт панель сводки.
     *
     * @param handler реакции карточек
     */
    public SummaryPanel(CardHandler handler) {
        super(new GridLayout(1, 0, 6, 0));
        this.handler = Objects.requireNonNull(handler, "handler");
        hideTimer.setRepeats(false);
        setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        cards.addAll(List.of(now, month1, month3, month6, month12, minimum, firstNegative, average, goal));
        cards.forEach(this::add);
        // JavaFX: Tooltip → Swing: setToolTipText → Web: title
        setToolTipText(null);
    }

    /**
     * Пересчитывает карточки по прогнозу.
     *
     * @param newForecast прогноз
     * @param plan        план (валюта, подушка, цель)
     */
    public void update(Forecast newForecast, Plan plan) {
        forecast = newForecast;
        currency = plan.currency();
        ForecastSummary s = newForecast.summary();
        LocalDate anchor = s.anchor();
        LocalDate end = newForecast.endDate();
        Money nowBalance = newForecast.balanceAt(anchor.isAfter(end) ? end : anchor);
        now.set(nowBalance.format(currency), colorOf(nowBalance, plan.cushion()), "на " + DateFormats.ru(anchor), anchor,
                newForecast.startDate(), end, Money.ZERO, "Баланс на сегодня (или на начало плана, если оно впереди)");
        setMonthCard(month1, 1, s, anchor, plan);
        setMonthCard(month3, 3, s, anchor, plan);
        setMonthCard(month6, 6, s, anchor, plan);
        setMonthCard(month12, 12, s, anchor, plan);
        minimum.set(s.minBalance().format(currency), colorOf(s.minBalance(), plan.cushion()), DateFormats.ru(s.minBalanceDate()),
                s.minBalanceDate(), anchor, end, plan.cushion().isPositive() ? plan.cushion() : Money.ZERO,
                "Самый низкий баланс от сегодняшнего дня до конца горизонта");
        firstNegative.set(s.firstNegativeDate().map(DateFormats::ru).orElse("нет"),
                s.firstNegativeDate().isPresent() ? Palette.EXPENSE : Palette.INCOME,
                // Короткая подпись помещается в узкую карточку; полный текст — во всплывающей карточке.
                s.firstBelowCushionDate().map(d -> "< подушки " + DateFormats.ru(d)).orElse("подушка цела"),
                s.firstNegativeDate().or(s::firstBelowCushionDate).orElse(null), anchor, end, Money.ZERO,
                "Первый день, когда баланс уходит ниже нуля");
        firstNegative.detail(s.firstBelowCushionDate().map(d -> "Ниже подушки безопасности с " + DateFormats.ru(d))
                .orElse("Подушка безопасности не нарушается"));
        average.set(s.averageMonthlyNet().formatSigned() + " " + currency,
                s.averageMonthlyNet().isNegative() ? Palette.EXPENSE : Palette.INCOME,
                "в среднем за месяц", null,
                newForecast.startDate(), end, null, "Средний прирост баланса за месяц на всём горизонте");
        // Суммы доходов и расходов длинные (миллионы) — только во всплывающей карточке.
        average.detail("Доходы " + s.totalIncome().format(currency) + ", расходы " + s.totalExpense().format(currency));
        Optional<String> goalTitle = plan.goalOptional().map(g -> "«" + g.title() + "» " + g.target().format(currency));
        goal.set(plan.goalOptional().isEmpty() ? "не задана" : s.goalReachDate().map(DateFormats::ru).orElse("не достигается"),
                plan.goalOptional().isEmpty() ? Palette.PAST : s.goalReachDate().isPresent() ? Palette.INCOME : Palette.EXPENSE,
                goalTitle.orElse("задать: Ctrl+G"), s.goalReachDate().orElse(null), anchor, end,
                plan.goalOptional().map(g -> g.target()).orElse(null), "Когда баланс впервые достигнет цели");
        goal.detail(goalTitle.map(t -> "Цель " + t).orElse("Задать цель: Инструменты → Калькулятор цели (Ctrl+G)"));
    }

    private void setMonthCard(Card card, int months, ForecastSummary s, LocalDate anchor, Plan plan) {
        LocalDate date = anchor.plusMonths(months);
        Optional<Money> balance = s.balanceAfter(months);
        if (balance.isPresent()) {
            card.set(balance.get().format(currency), colorOf(balance.get(), plan.cushion()), "на " + DateFormats.ru(date), date,
                    anchor, date, Money.ZERO, "Баланс через " + months + " мес. от сегодняшнего дня");
        } else {
            card.set("-", Palette.PAST, "за горизонтом", null, anchor, forecast.endDate(), null,
                    "Дата " + DateFormats.ru(date) + " за пределами горизонта плана");
        }
    }

    private static Color colorOf(Money balance, Money cushion) {
        if (balance.isNegative()) {
            return Palette.EXPENSE;
        }
        return cushion.isPositive() && balance.compareTo(cushion) < 0 ? Palette.CUSHION_LINE : Color.BLACK;
    }

    /**
     * Показывает, что сводку посчитать нельзя (например, горизонт слишком длинный).
     *
     * @param message причина
     */
    public void showUnavailable(String message) {
        forecast = null;
        for (Card card : cards) {
            card.set("-", Palette.PAST, " ", null, null, null, null, message);
        }
    }

    /** Скрывает всплывающую карточку. */
    public void hidePopups() {
        popupControl.hide();
    }

    private void showPopup(Card card) {
        if (forecast == null || card.sparkFrom == null || card.sparkTo == null) {
            return;
        }
        LocalDate from = card.sparkFrom.isBefore(forecast.startDate()) ? forecast.startDate() : card.sparkFrom;
        LocalDate to = card.sparkTo.isAfter(forecast.endDate()) ? forecast.endDate() : card.sparkTo;
        if (!to.isAfter(from)) {
            to = forecast.endDate();
            from = forecast.startDate();
        }
        List<DailyPoint> points = ChartSeries.sample(forecast, from, to, 120);
        JPanel content = new JPanel(new BorderLayout(0, 4));
        content.setOpaque(false);
        JLabel header = new JLabel(card.title.getText() + ": " + card.value.getText());
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        content.add(header, BorderLayout.NORTH);
        JComponent spark = new Sparkline(points, card.reference, Palette.BALANCE_LINE);
        content.add(spark, BorderLayout.CENTER);
        String detail = card.detailText.isBlank() ? "" : "<br>" + ru.cashprediction.swing.dialog.SwingText.escape(card.detailText);
        JLabel caption = new JLabel("<html>" + ru.cashprediction.swing.dialog.SwingText.escape(card.explanation) + detail
                + "<br>Баланс " + DateFormats.ru(from) + " - " + DateFormats.ru(to) + "</html>");
        caption.setForeground(Palette.PAST);
        content.add(caption, BorderLayout.SOUTH);
        popupControl.show(card, content);
    }
}
