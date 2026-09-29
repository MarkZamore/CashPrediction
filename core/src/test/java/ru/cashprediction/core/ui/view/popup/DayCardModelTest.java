package ru.cashprediction.core.ui.view.popup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.view.table.ViewStates;

/** Проверяет лимит строк, текст пустого дня и цвет баланса карточки дня. */
class DayCardModelTest {

    @Test
    void eightLinesAreKeptAndTheRestIsSummarized() {
        LocalDate date = ViewStates.TODAY.plusDays(1);
        List<ru.cashprediction.core.model.OneTimeTransaction> entries = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            entries.add(ViewStates.oneTime("t" + i, date, "T" + i, Kind.EXPENSE, 1));
        }
        Plan plan = ViewStates.plan(ViewStates.TODAY, 100, new Horizon.Months(1), 0, List.of(), entries);
        DayCardModel card = PopupBuilders.dayCard(ViewStates.of(plan, ViewState.defaults()), date);
        assertEquals(PopupBuilders.DAY_CARD_MAX_LINES, card.lines().size());
        assertEquals("… и ещё 2", card.moreText());
        assertEquals("", card.noneText());
        assertEquals(ColorToken.EXPENSE, card.lines().getFirst().color());
    }

    @Test
    void dayWithoutEventsHasHumanReadableEmptyText() {
        DayCardModel card = PopupBuilders.dayCard(ViewStates.sample(), ViewStates.TODAY.plusDays(2));
        assertTrue(card.lines().isEmpty());
        assertEquals("Событий нет", card.noneText());
        assertEquals("", card.moreText());
    }
}
