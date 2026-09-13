package ru.cashprediction.core.ui.view.popup;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Календарь поля даты (спецификация v2, §5.6.5): JavaFX собственный {@code Popup} (DatePicker не используется) →
 * Swing {@code JPopupMenu} → Web div (нативный {@code input type=date} не используется).
 *
 * @param month        показываемый месяц
 * @param title        «Октябрь 2026» между кнопками «◀» и «▶»
 * @param prevTooltip  {@code calendar.prev} «Предыдущий месяц»
 * @param nextTooltip  {@code calendar.next} «Следующий месяц»
 * @param weekdays     заголовки «пн вт ср чт пт сб вс»
 * @param days         42 ячейки (6 недель с понедельника), включая дни соседних месяцев
 */
public record CalendarModel(YearMonth month, String title, String prevTooltip, String nextTooltip,
                            List<String> weekdays, List<Day> days) {

    /**
     * Ячейка дня.
     *
     * @param date       дата
     * @param text       номер дня
     * @param inMonth    принадлежит ли показываемому месяцу (иначе {@code text.past})
     * @param selected   выбранный день (фон {@code accent.weak})
     * @param today      сегодня (текст {@code accent})
     * @param textColor  цвет текста: сб и вс — {@code expense}, сегодня — {@code accent}, иначе {@code text.primary}
     */
    public record Day(LocalDate date, String text, boolean inMonth, boolean selected, boolean today,
                      ColorToken textColor) {
        /** Проверяет поля. */
        public Day {
            Objects.requireNonNull(date, "date");
            text = Objects.requireNonNullElse(text, "");
            textColor = textColor == null ? ColorToken.TEXT_PRIMARY : textColor;
        }
    }

    /** Проверяет поля и копирует списки. */
    public CalendarModel {
        Objects.requireNonNull(month, "month");
        title = Objects.requireNonNullElse(title, "");
        prevTooltip = Objects.requireNonNullElse(prevTooltip, "");
        nextTooltip = Objects.requireNonNullElse(nextTooltip, "");
        weekdays = List.copyOf(Objects.requireNonNull(weekdays, "weekdays"));
        days = List.copyOf(Objects.requireNonNull(days, "days"));
    }
}
