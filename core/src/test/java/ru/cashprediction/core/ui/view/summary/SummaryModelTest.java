package ru.cashprediction.core.ui.view.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.cashprediction.core.ui.view.table.ViewStates.TODAY;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.SamplePlan;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.view.table.ViewStates;

/**
 * Панель сводки (спецификация v2, §5.1): девять карточек на плане «Пример» (значения §5.1: «177 000 ₽»,
 * «13.10.2026 · +46 654», «доходы 1,5 млн · расходы 896 тыс»), отрицательное значение, ниже подушки, за горизонтом,
 * без цели, цель достигнута и не достигнута, прогноз не рассчитан.
 */
class SummaryModelTest {

    @Test
    void sampleCardsFollowSpec() {
        AppState state = ViewStates.sample();
        SummaryModel model = SummaryBuilder.build(state);
        assertTrue(model.visible());
        assertEquals("", model.unavailableText());
        assertEquals(SummaryBuilder.CARD_IDS, model.cards().stream().map(CardModel::id).toList());

        assertEquals(new CardModel("now", "Сейчас", "177 000 ₽", ColorToken.TEXT_PRIMARY, "на 13.09.2026",
                ColorToken.TEXT_MUTED, TODAY, "Сейчас: 177 000,00 ₽",
                "Баланс на конец сегодняшнего дня (или дня начала плана, если он впереди)"), card(model, "now"));
        assertEquals(new CardModel("m1", "Через 1 месяц", "223 654 ₽", ColorToken.TEXT_PRIMARY, "13.10.2026 · +46 654",
                ColorToken.TEXT_MUTED, LocalDate.of(2026, 10, 13), "Через 1 месяц: 223 654,33 ₽",
                "Баланс через 1 месяц и изменение относительно «Сейчас»: +46 654,33"), card(model, "m1"));

        Forecast forecast = ViewStates.forecast(state);
        for (int months : List.of(3, 6)) {
            LocalDate date = TODAY.plusMonths(months);
            Money value = forecast.balanceAt(date);
            CardModel card = card(model, "m" + months);
            assertEquals("Через " + months + " месяц" + (months == 3 ? "а" : "ев"), card.title());
            assertEquals(UiFormats.whole(value, "₽"), card.value());
            assertEquals(UiFormats.date(date) + " · " + UiFormats.wholeSigned(value.minus(Money.ofMajor(177_000))),
                    card.caption());
            assertEquals(date, card.date());
        }

        assertEquals(new CardModel("m12", "Через 12 месяцев", "за горизонтом", ColorToken.TEXT_MUTED, "за горизонтом плана",
                ColorToken.TEXT_MUTED, null, "Через 12 месяцев: за горизонтом",
                "Дата 13.09.2027 за пределами горизонта: увеличьте горизонт в меню «Вид»"), card(model, "m12"));
        assertEquals(new CardModel("min", "Минимум", "147 654 ₽", ColorToken.TEXT_PRIMARY, "03.10.2026",
                ColorToken.TEXT_MUTED, LocalDate.of(2026, 10, 3), "Минимум: 147 654,33 ₽",
                "Самый низкий баланс до конца плана"), card(model, "min"));
        assertEquals(new CardModel("firstNegative", "Первый минус", "нет", ColorToken.TEXT_PRIMARY,
                "подушка не нарушается", ColorToken.TEXT_MUTED, null, "Первый минус: нет",
                "Первый день, когда баланс уходит ниже нуля"), card(model, "firstNegative"));

        Money avg = forecast.summary().averageMonthlyNet();
        assertEquals(new CardModel("avg", "Средний итог/мес", UiFormats.wholeSigned(avg) + " ₽", ColorToken.TEXT_PRIMARY,
                "доходы 1,5 млн · расходы 896 тыс", ColorToken.TEXT_MUTED, null,
                "Средний итог/мес: " + avg.format("₽"),
                "Сколько в среднем прибавляется за месяц. Доходы 1 500 000,00 ₽, расходы 896 148,04 ₽"),
                card(model, "avg"));
        assertTrue(card(model, "avg").value().startsWith("+"));

        assertEquals(new CardModel("goal", "Цель", "20.11.2026", ColorToken.TEXT_PRIMARY, "«Отпуск» 300 000 ₽",
                ColorToken.TEXT_MUTED, LocalDate.of(2026, 11, 20), "Цель: 20.11.2026",
                "Когда баланс впервые достигнет цели «Отпуск» 300 000,00 ₽"), card(model, "goal"));
    }

    @Test
    void negativeValuesAreExpense() {
        Plan plan = ViewStates.plan(TODAY, 10_000, new Horizon.Months(12), 0,
                List.of(ViewStates.monthly("r1", "Кредит", Kind.EXPENSE, 20_000, 15)), List.of());
        SummaryModel model = SummaryBuilder.build(ViewStates.of(plan, ViewState.defaults()));

        CardModel now = card(model, "now");
        assertEquals("10 000 ₽", now.value());
        assertEquals(ColorToken.TEXT_PRIMARY, now.valueColor());
        CardModel m1 = card(model, "m1");
        assertEquals("-10 000 ₽", m1.value());
        assertEquals(ColorToken.EXPENSE, m1.valueColor());
        assertEquals("13.10.2026 · -20 000", m1.caption());
        assertEquals(ColorToken.EXPENSE, card(model, "min").valueColor());

        assertEquals(new CardModel("firstNegative", "Первый минус", "15.09.2026", ColorToken.EXPENSE,
                "подушка не нарушается", ColorToken.TEXT_MUTED, LocalDate.of(2026, 9, 15),
                "Первый минус: -10 000,00 ₽", "Первый день, когда баланс уходит ниже нуля"),
                card(model, "firstNegative"));

        CardModel avg = card(model, "avg");
        assertTrue(avg.value().startsWith("-") && avg.value().endsWith(" ₽"), avg.value());
        assertEquals(ColorToken.EXPENSE, avg.valueColor());
        assertEquals(new CardModel("goal", "Цель", "не задана", ColorToken.TEXT_MUTED, "Инструменты → Калькулятор цели",
                ColorToken.TEXT_MUTED, null, "Цель: не задана",
                "Задайте цель в калькуляторе цели (Ctrl+G) или в параметрах плана"), card(model, "goal"));
    }

    @Test
    void belowCushionIsWarn() {
        Plan plan = ViewStates.plan(TODAY, 60_000, new Horizon.Months(12), 50_000, List.of(),
                List.of(ViewStates.oneTime("t1", LocalDate.of(2026, 9, 15), "Ремонт", Kind.EXPENSE, 15_000)));
        SummaryModel model = SummaryBuilder.build(ViewStates.of(plan, ViewState.defaults()));
        assertEquals(ColorToken.TEXT_PRIMARY, card(model, "now").valueColor());
        assertEquals("45 000 ₽", card(model, "m1").value());
        assertEquals(ColorToken.WARN, card(model, "m1").valueColor());
        assertEquals(ColorToken.WARN, card(model, "min").valueColor());

        CardModel firstNegative = card(model, "firstNegative");
        assertEquals("нет", firstNegative.value());
        assertEquals(ColorToken.TEXT_PRIMARY, firstNegative.valueColor());
        assertEquals("ниже подушки с 15.09.2026", firstNegative.caption());
        assertEquals(ColorToken.WARN, firstNegative.captionColor());
        assertEquals(LocalDate.of(2026, 9, 15), firstNegative.date(), "без минуса дата - первый день ниже подушки");
    }

    @Test
    void beyondHorizonCards() {
        Plan plan = SamplePlan.create(TODAY).withHorizon(new Horizon.Months(2));
        SummaryModel model = SummaryBuilder.build(ViewStates.of(plan, ViewState.defaults()));
        assertEquals("223 654 ₽", card(model, "m1").value(), "13.10.2026 внутри горизонта до 31.10.2026");
        for (String id : List.of("m3", "m6", "m12")) {
            CardModel card = card(model, id);
            assertEquals("за горизонтом", card.value());
            assertEquals(ColorToken.TEXT_MUTED, card.valueColor());
            assertEquals("за горизонтом плана", card.caption());
            assertEquals(null, card.date());
            assertEquals(card.title() + ": за горизонтом", card.popupHeader());
        }
        assertEquals("Дата 13.12.2026 за пределами горизонта: увеличьте горизонт в меню «Вид»",
                card(model, "m3").explanation());
    }

    @Test
    void goalReachedNotReachedAndUntitled() {
        Plan notReached = SamplePlan.create(TODAY).withGoal(new Goal("Дом", Money.ofMajor(10_000_000), null));
        assertEquals(new CardModel("goal", "Цель", "не достигается", ColorToken.WARN, "«Дом» 10 000 000 ₽",
                ColorToken.TEXT_MUTED, null, "Цель: не достигается",
                "Когда баланс впервые достигнет цели «Дом» 10 000 000,00 ₽"),
                card(SummaryBuilder.build(ViewStates.of(notReached, ViewState.defaults())), "goal"));

        Plan untitled = SamplePlan.create(TODAY).withGoal(new Goal("", Money.ofMajor(300_000), null));
        CardModel card = card(SummaryBuilder.build(ViewStates.of(untitled, ViewState.defaults())), "goal");
        assertEquals("20.11.2026", card.value());
        assertEquals("300 000 ₽", card.caption());
        assertEquals("Когда баланс впервые достигнет цели 300 000,00 ₽", card.explanation());

        Plan noGoal = SamplePlan.create(TODAY).withGoal(null);
        assertEquals("не задана", card(SummaryBuilder.build(ViewStates.of(noGoal, ViewState.defaults())), "goal").value());
    }

    @Test
    void forecastFailureShowsOneLine() {
        AppState failed = ViewStates.failed(SamplePlan.create(TODAY), "Горизонт слишком длинный",
                ViewState.defaults().withSummaryPanel(false));
        assertEquals(new SummaryModel(false, List.of(), "Сводка недоступна: Горизонт слишком длинный"),
                SummaryBuilder.build(failed));
        assertFalse(SummaryBuilder.card(failed, "now").isPresent());
        assertTrue(SummaryBuilder.card(ViewStates.sample(), "now").isPresent());
        assertFalse(SummaryBuilder.card(ViewStates.sample(), "nope").isPresent());
    }

    /** @return карточка по id */
    private static CardModel card(SummaryModel model, String id) {
        return model.cards().stream().filter(card -> card.id().equals(id)).findFirst().orElseThrow();
    }
}
