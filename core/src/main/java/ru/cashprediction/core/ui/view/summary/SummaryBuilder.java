package ru.cashprediction.core.ui.view.summary;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastSummary;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.ui.text.Plurals;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Построение панели сводки из состояния (спецификация v2, §5.1).
 *
 * <p>Опорные даты: anchor = max(начало плана, сегодня) ({@code Forecast.anchor()}), end = конец прогноза. Цвет
 * значения денежной карточки («Сейчас», «Через N месяцев», «Минимум»): сумма &lt; 0 → {@code expense}; подушка &gt; 0
 * и сумма ниже подушки → {@code warn}; иначе {@code text.primary}. Карточки m1-m12 за горизонтом (anchor + N позже
 * end) показывают «-» ({@code text.muted}) и «за горизонтом плана» без даты. «Первый минус»: дата ({@code expense})
 * или «нет»; подпись «ниже подушки с …» ({@code warn}) или «подушка не нарушается»; дата карточки - первый минус,
 * иначе первый день ниже подушки. «Средний итог/мес»: целые со знаком и валютой ({@code expense} при минусе), подпись -
 * доходы и расходы горизонта сокращённо. «Цель»: без цели «не задана» ({@code text.muted}); с целью - дата
 * достижения или «не достигается» ({@code warn}). Если прогноз не рассчитан - карточек нет, текст
 * {@code summary.unavailable}.</p>
 *
 * <p><b>Первая строка всплывающего окна</b> ({@link CardModel#popupHeader()}): «{Заголовок}: {точное значение}».
 * Точное значение денежной карточки - сумма с копейками и валютой; «Первый минус» - баланс на дату карточки или «нет»;
 * «Средний итог/мес» - средний итог с копейками и валютой; «Цель» - значение карточки (дата, «не достигается»,
 * «не задана»); за горизонтом - «{Заголовок}: за горизонтом».</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class SummaryBuilder {

    /** Идентификаторы карточек по порядку. */
    public static final List<String> CARD_IDS = List.of("now", "m1", "m3", "m6", "m12", "min", "firstNegative", "avg",
            "goal");

    /** Число месяцев карточек «Через N месяцев» по порядку. */
    public static final List<Integer> MONTH_CARDS = List.of(1, 3, 6, 12);

    /** Значение карточки за горизонтом плана. */
    static final String NO_VALUE = "-";

    private SummaryBuilder() {
    }

    /**
     * Строит модель панели.
     *
     * @param state состояние приложения
     * @return модель сводки
     */
    public static SummaryModel build(AppState state) {
        Objects.requireNonNull(state, "state");
        boolean visible = state.view().summaryPanel();
        Forecast forecast = state.document().forecast();
        if (forecast == null) {
            return new SummaryModel(visible, List.of(),
                    UiText.get("summary.unavailable", state.document().forecastError()));
        }
        return new SummaryModel(visible, cards(forecast), "");
    }

    /**
     * Карточка по id.
     *
     * @param state  состояние
     * @param cardId id карточки
     * @return карточка или пусто, если прогноз не рассчитан или id неизвестен
     */
    public static Optional<CardModel> card(AppState state, String cardId) {
        return build(state).cards().stream().filter(card -> card.id().equals(cardId)).findFirst();
    }

    /** @return девять карточек по порядку */
    private static List<CardModel> cards(Forecast forecast) {
        Plan plan = forecast.plan();
        ForecastSummary summary = forecast.summary();
        String cur = plan.currency();
        Money cushion = plan.cushion();
        LocalDate anchor = forecast.anchor();
        Money now = forecast.balanceAt(anchor);
        List<CardModel> cards = new ArrayList<>(CARD_IDS.size());

        String nowTitle = UiText.get("summary.now.title");
        cards.add(new CardModel("now", nowTitle, UiFormats.whole(now, cur), moneyColor(now, cushion),
                UiText.get("summary.now.caption", UiFormats.date(anchor)), ColorToken.TEXT_MUTED, anchor,
                UiText.get("summary.popup.header", nowTitle, now.format(cur)), UiText.get("summary.now.explain")));

        for (int months : MONTH_CARDS) {
            cards.add(monthCard(forecast, months, now, cushion, cur));
        }

        String minTitle = UiText.get("summary.min.title");
        Money min = summary.minBalance();
        cards.add(new CardModel("min", minTitle, UiFormats.whole(min, cur), moneyColor(min, cushion),
                UiFormats.date(summary.minBalanceDate()), ColorToken.TEXT_MUTED, summary.minBalanceDate(),
                UiText.get("summary.popup.header", minTitle, min.format(cur)), UiText.get("summary.min.explain")));

        cards.add(firstNegativeCard(forecast, cur));

        String avgTitle = UiText.get("summary.avg.title");
        Money avg = summary.averageMonthlyNet();
        cards.add(new CardModel("avg", avgTitle,
                UiText.get("summary.avg.value", UiFormats.wholeSigned(avg), cur).strip(),
                avg.isNegative() ? ColorToken.EXPENSE : ColorToken.TEXT_PRIMARY,
                UiText.get("summary.avg.caption", UiFormats.compact(summary.totalIncome()),
                        UiFormats.compact(summary.totalExpense())),
                ColorToken.TEXT_MUTED, null, UiText.get("summary.popup.header", avgTitle, avg.format(cur)),
                UiText.get("summary.avg.explain", summary.totalIncome().format(cur),
                        summary.totalExpense().format(cur))));

        cards.add(goalCard(plan.goal(), summary, cur));
        return List.copyOf(cards);
    }

    /** @return карточка «Через N месяцев» или её вариант за горизонтом */
    private static CardModel monthCard(Forecast forecast, int months, Money now, Money cushion, String cur) {
        String id = "m" + months;
        String title = UiText.get("summary.months.title", Plurals.count(Plurals.MONTH, months));
        LocalDate date = forecast.anchor().plusMonths(months);
        if (date.isAfter(forecast.endDate())) {
            return new CardModel(id, title, NO_VALUE, ColorToken.TEXT_MUTED, UiText.get("summary.beyond.caption"),
                    ColorToken.TEXT_MUTED, null, UiText.get("summary.popup.beyond", title),
                    UiText.get("summary.beyond.explain", UiFormats.date(date)));
        }
        Money value = forecast.balanceAt(date);
        Money change = value.minus(now);
        return new CardModel(id, title, UiFormats.whole(value, cur), moneyColor(value, cushion),
                UiText.get("summary.months.caption", UiFormats.date(date), UiFormats.wholeSigned(change)),
                ColorToken.TEXT_MUTED, date, UiText.get("summary.popup.header", title, value.format(cur)),
                UiText.get("summary.months.explain", Plurals.count(Plurals.MONTH, months), change.formatSigned()));
    }

    /** @return карточка «Первый минус» */
    private static CardModel firstNegativeCard(Forecast forecast, String cur) {
        ForecastSummary summary = forecast.summary();
        String title = UiText.get("summary.firstNegative.title");
        Optional<LocalDate> negative = summary.firstNegativeDate();
        Optional<LocalDate> belowCushion = summary.firstBelowCushionDate();
        String value = negative.map(UiFormats::date).orElseGet(() -> UiText.get("summary.firstNegative.none"));
        String caption = belowCushion
                .map(date -> UiText.get("summary.firstNegative.cushion", UiFormats.date(date)))
                .orElseGet(() -> UiText.get("summary.firstNegative.cushionOk"));
        String exact = negative.map(date -> forecast.balanceAt(date).format(cur)).orElse(value);
        return new CardModel("firstNegative", title, value,
                negative.isPresent() ? ColorToken.EXPENSE : ColorToken.TEXT_PRIMARY, caption,
                belowCushion.isPresent() ? ColorToken.WARN : ColorToken.TEXT_MUTED,
                negative.or(() -> belowCushion).orElse(null), UiText.get("summary.popup.header", title, exact),
                UiText.get("summary.firstNegative.explain"));
    }

    /** @return карточка «Цель» с целью или без неё */
    private static CardModel goalCard(Goal goal, ForecastSummary summary, String cur) {
        String title = UiText.get("summary.goal.title");
        if (goal == null) {
            String value = UiText.get("summary.goal.unset");
            return new CardModel("goal", title, value, ColorToken.TEXT_MUTED, UiText.get("summary.goal.unsetCaption"),
                    ColorToken.TEXT_MUTED, null, UiText.get("summary.popup.header", title, value),
                    UiText.get("summary.goal.unsetExplain"));
        }
        Optional<LocalDate> reach = summary.goalReachDate();
        String value = reach.map(UiFormats::date).orElseGet(() -> UiText.get("summary.goal.notReached"));
        String target = UiFormats.whole(goal.target(), cur);
        String caption = goal.title().isEmpty() ? target : UiText.get("summary.goal.caption", goal.title(), target);
        String explanation = goal.title().isEmpty()
                ? UiText.get("summary.goal.explainUntitled", goal.target().format(cur))
                : UiText.get("summary.goal.explain", goal.title(), goal.target().format(cur));
        return new CardModel("goal", title, value, reach.isPresent() ? ColorToken.TEXT_PRIMARY : ColorToken.WARN,
                caption, ColorToken.TEXT_MUTED, reach.orElse(null), UiText.get("summary.popup.header", title, value),
                explanation);
    }

    /** @return цвет денежного значения: минус → {@code expense}, ниже подушки → {@code warn}, иначе основной */
    private static ColorToken moneyColor(Money value, Money cushion) {
        if (value.isNegative()) {
            return ColorToken.EXPENSE;
        }
        if (cushion.isPositive() && value.isLessThan(cushion)) {
            return ColorToken.WARN;
        }
        return ColorToken.TEXT_PRIMARY;
    }
}
