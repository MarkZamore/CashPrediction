package ru.cashprediction.swing.ui;

import java.awt.*;
import java.time.*;
import java.util.function.Consumer;
import javax.swing.*;
import ru.cashprediction.core.app.UiIntents;
import ru.cashprediction.core.ui.token.*;
import ru.cashprediction.core.ui.view.popup.CalendarModel;

/** Собственный календарь даты по готовой модели ядра. */
public final class SwingCalendarPopup {
    private final UiIntents intents;
    private final Consumer<LocalDate> chosen;
    private final LocalDate selected;
    // JavaFX: Popup → Swing: JPopupMenu → Web: div календаря
    private final JPopupMenu popup = new JPopupMenu();

    /** Создаёт календарь с обратным вызовом выбранной даты. */
    public SwingCalendarPopup(UiIntents intents, LocalDate selected, Consumer<LocalDate> chosen) {
        this.intents = intents; this.selected = selected; this.chosen = chosen;
    }
    /** Показывает календарь под кнопкой поля. */
    public void show(JComponent owner, YearMonth month) { render(month); popup.show(owner, 0, owner.getHeight()); }
    private void render(YearMonth month) {
        CalendarModel model = intents.calendar(month, selected); popup.removeAll();
        JPanel panel = new JPanel(new BorderLayout(4, 4)); JPanel header = new JPanel(new BorderLayout());
        JButton prev = new JButton("◀"), next = new JButton("▶");
        SwingLook.tooltip(prev, model.prevTooltip()); SwingLook.tooltip(next, model.nextTooltip());
        prev.addActionListener(e -> render(month.minusMonths(1))); next.addActionListener(e -> render(month.plusMonths(1)));
        header.add(prev, BorderLayout.WEST); header.add(new JLabel(model.title(), SwingConstants.CENTER)); header.add(next, BorderLayout.EAST); panel.add(header, BorderLayout.NORTH);
        JPanel days = new JPanel(new GridLayout(7, 7, 2, 2));
        for (int i = 0; i < model.weekdays().size(); i++) days.add(SwingLook.label(model.weekdays().get(i), i >= 5 ? ColorToken.EXPENSE : ColorToken.TEXT_MUTED, FontToken.SMALL));
        for (CalendarModel.Day day : model.days()) {
            JButton button = new JButton(day.text()); button.setMargin(new Insets(3, 3, 3, 3));
            button.setForeground(SwingLook.color(day.textColor()));
            if (day.selected()) button.setBackground(SwingLook.color(ColorToken.ACCENT_WEAK));
            button.addActionListener(e -> { popup.setVisible(false); chosen.accept(day.date()); }); days.add(button);
        }
        panel.add(days); popup.add(panel); popup.pack();
    }
}
