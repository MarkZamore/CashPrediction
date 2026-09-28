package ru.cashprediction.core.ui.view.chart;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.MonthTotals;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;

/**
 * Тексты графика (спецификация v2, §5.3, §8.9) из области каталога {@code chart}: пустые сцены, подписи линий,
 * легенда и подсказки маркеров и столбцов. Все ключи - литералы, чтобы проверка каталога видела их использование.
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
final class ChartTexts {

    /** Разделитель строк многострочной подсказки. */
    private static final String NEW_LINE = "\n";

    private ChartTexts() {
    }

    /**
     * @param reason причина, по которой прогноз не рассчитан
     * @return «Прогноз не рассчитан: {0}»
     */
    static String forecastFailed(String reason) {
        return UiText.get("chart.forecastFailed", reason);
    }

    /** @return «Недостаточно данных для графика» */
    static String noData() {
        return UiText.get("chart.noData");
    }

    /** @return подпись вертикали «сегодня» */
    static String todayLabel() {
        return UiText.get("chart.today");
    }

    /**
     * @param cushion подушка
     * @return «подушка 50 000»
     */
    static String cushionLabel(Money cushion) {
        return UiText.get("chart.cushion", UiFormats.whole(cushion, ""));
    }

    /**
     * @param goal цель
     * @return «цель «Отпуск» 300 000»
     */
    static String goalLabel(Goal goal) {
        return UiText.get("chart.goal", goal.title(), UiFormats.whole(goal.target(), ""));
    }

    /** @return подпись оси полосы столбцов «итог мес.» */
    static String barsAxis() {
        return UiText.get("chart.barsAxis");
    }

    /** @return уведомление легенды о слишком большом числе маркеров */
    static String tooManyMarkers() {
        return UiText.get("chart.tooManyMarkers");
    }

    /**
     * @param from первый день диапазона
     * @param to   последний день диапазона
     * @return «Баланс, 01.09.2026 - 12.09.2027»
     */
    static String legendBalance(LocalDate from, LocalDate to) {
        return UiText.get("chart.legend.balance", UiFormats.date(from), UiFormats.date(to));
    }

    /** @return подсказка элемента «Баланс» */
    static String legendBalanceTip() {
        return UiText.get("chart.legend.balance.tip");
    }

    /** @return «Ноль» */
    static String legendZero() {
        return UiText.get("chart.legend.zero");
    }

    /** @return подсказка элемента «Ноль» */
    static String legendZeroTip() {
        return UiText.get("chart.legend.zero.tip");
    }

    /** @return «Подушка» */
    static String legendCushion() {
        return UiText.get("chart.legend.cushion");
    }

    /**
     * @param cushion  подушка
     * @param currency валюта плана
     * @return «Подушка безопасности: 50 000,00 ₽»
     */
    static String legendCushionTip(Money cushion, String currency) {
        return UiText.get("chart.legend.cushion.tip", cushion.format(currency));
    }

    /** @return «Цель» */
    static String legendGoal() {
        return UiText.get("chart.legend.goal");
    }

    /**
     * @param goal      цель
     * @param currency  валюта плана
     * @param reachDate дата достижения или {@code null}, если цель не достигается
     * @return «Цель «Отпуск»: 300 000,00 ₽, достигается 20.03.2027» или «…, не достигается»
     */
    static String legendGoalTip(Goal goal, String currency, LocalDate reachDate) {
        return reachDate == null
                ? UiText.get("chart.legend.goal.tip.notReached", goal.title(), goal.target().format(currency))
                : UiText.get("chart.legend.goal.tip.reached", goal.title(), goal.target().format(currency),
                UiFormats.date(reachDate));
    }

    /** @return «Сегодня» */
    static String legendToday() {
        return UiText.get("chart.legend.today");
    }

    /**
     * @param today сегодня
     * @return «Сегодня, 13.09.2026: левее - прошедшие дни»
     */
    static String legendTodayTip(LocalDate today) {
        return UiText.get("chart.legend.today.tip", UiFormats.date(today));
    }

    /** @return «Доход» */
    static String legendIncome() {
        return UiText.get("chart.legend.income");
    }

    /** @return «Дни с доходами» */
    static String legendIncomeTip() {
        return UiText.get("chart.legend.income.tip");
    }

    /** @return «Расход» */
    static String legendExpense() {
        return UiText.get("chart.legend.expense");
    }

    /** @return «Дни с расходами» */
    static String legendExpenseTip() {
        return UiText.get("chart.legend.expense.tip");
    }

    /** @return «Доход и расход» */
    static String legendMixed() {
        return UiText.get("chart.legend.mixed");
    }

    /** @return «Дни с доходами и расходами» */
    static String legendMixedTip() {
        return UiText.get("chart.legend.mixed.tip");
    }

    /** @return «Итог месяца» */
    static String legendBars() {
        return UiText.get("chart.legend.bars");
    }

    /** @return «Доходы минус расходы за месяц» */
    static String legendBarsTip() {
        return UiText.get("chart.legend.bars.tip");
    }

    /**
     * Подсказка маркера дня: «dd.MM.yyyy», по строке на событие «+80 000,00 ₽ - Зарплата», «Баланс: X ₽».
     *
     * @param date     день
     * @param rows     видимые непропущенные события дня по порядку таблицы
     * @param balance  баланс на конец дня
     * @param currency валюта плана
     * @return текст с переводами строк
     */
    static String markerTooltip(LocalDate date, List<ForecastRow> rows, Money balance, String currency) {
        StringBuilder text = new StringBuilder(UiFormats.date(date));
        for (ForecastRow row : rows) {
            text.append(NEW_LINE)
                    .append(UiText.get("chart.marker.event", row.amount().formatSigned(), currency, row.title()));
        }
        text.append(NEW_LINE).append(UiText.get("chart.marker.balance", balance.format(currency)));
        return text.toString();
    }

    /**
     * Подсказка столбца итога месяца: «Октябрь 2026», «Итог: +X ₽», «Доходы: X ₽», «Расходы: X ₽»,
     * «Баланс на конец: X ₽».
     *
     * @param month    месяц
     * @param totals   итоги месяца
     * @param currency валюта плана
     * @return текст с переводами строк
     */
    static String barTooltip(YearMonth month, MonthTotals totals, String currency) {
        return UiFormats.monthTitle(month)
                + NEW_LINE + UiText.get("chart.bar.net", totals.net().formatSigned(), currency)
                + NEW_LINE + UiText.get("chart.bar.income", totals.income().format(currency))
                + NEW_LINE + UiText.get("chart.bar.expense", totals.expense().format(currency))
                + NEW_LINE + UiText.get("chart.bar.closing", totals.closingBalance().format(currency));
    }
}
