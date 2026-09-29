package ru.cashprediction.core.ui.view.popup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.token.ColorToken;

/** Проверяет неизменяемую сетку календаря: шесть недель, выбор, сегодня и выходные. */
class CalendarModelTest {

    @Test
    void sixMondayFirstWeeksIncludeAdjacentMonthsAndSelection() {
        LocalDate selected = LocalDate.of(2026, 10, 5);
        CalendarModel calendar = PopupBuilders.calendar(YearMonth.of(2026, 10), selected, selected);
        assertEquals("Октябрь 2026", calendar.title());
        assertEquals(7, calendar.weekdays().size());
        assertEquals(PopupBuilders.CALENDAR_CELLS, calendar.days().size());
        assertEquals(LocalDate.of(2026, 9, 28), calendar.days().getFirst().date());
        assertEquals(LocalDate.of(2026, 11, 8), calendar.days().getLast().date());

        CalendarModel.Day day = calendar.days().stream().filter(item -> item.date().equals(selected)).findFirst().orElseThrow();
        assertTrue(day.inMonth());
        assertTrue(day.selected());
        assertTrue(day.today());
        assertEquals(ColorToken.ACCENT, day.textColor());
        assertFalse(calendar.days().getFirst().inMonth());
    }

    @Test
    void weekendUsesExpenseColorUnlessItIsToday() {
        CalendarModel calendar = PopupBuilders.calendar(YearMonth.of(2026, 10), null, LocalDate.of(2026, 10, 5));
        CalendarModel.Day saturday = calendar.days().stream()
                .filter(day -> day.date().equals(LocalDate.of(2026, 10, 3))).findFirst().orElseThrow();
        assertEquals(ColorToken.EXPENSE, saturday.textColor());
        assertFalse(saturday.today());
    }
}
