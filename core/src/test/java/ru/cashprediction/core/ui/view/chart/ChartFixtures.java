package ru.cashprediction.core.ui.view.chart;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.view.popup.DayCardModel;

/**
 * Готовые планы и состояния для тестов графика: прогноз строится настоящим {@link ForecastEngine} с явной датой
 * «сегодня», карточка дня подменяется заглушкой, чтобы раскладка проверялась независимо от всплывающих окон.
 */
final class ChartFixtures {

    /** «Сегодня» эталонных сценариев. */
    static final LocalDate TODAY = LocalDate.of(2026, 9, 13);

    private ChartFixtures() {
    }

    /**
     * Состояние с рассчитанным прогнозом.
     *
     * @param plan  план
     * @param view  вид
     * @param today сегодня
     * @return состояние
     */
    static AppState state(Plan plan, ViewState view, LocalDate today) {
        Forecast forecast = ForecastEngine.forecast(plan, view.whatIf(), today, view.showSkipped());
        return state(new DocumentView(plan, null, false, false, "", false, "", forecast, "", List.of()), view, today);
    }

    /**
     * Состояние, в котором прогноз не рассчитан.
     *
     * @param plan  план
     * @param error причина
     * @return состояние
     */
    static AppState failed(Plan plan, String error) {
        return state(new DocumentView(plan, null, false, false, "", false, "", null, error, List.of()),
                ViewState.defaults(), TODAY);
    }

    private static AppState state(DocumentView document, ViewState view, LocalDate today) {
        return new AppState(1, ClientProfile.swing(), today, Path.of("CashMemory"), null, document, view, "", false,
                AppSettings.defaults(), null, List.of(), null, null, "");
    }

    /**
     * Модель с заглушкой карточки дня и ревизией 7.
     *
     * @param state состояние
     * @return модель
     */
    static ChartModel model(AppState state) {
        return ChartLayout.model(state, 7, ChartFixtures::stubCard);
    }

    /**
     * Заглушка карточки дня: заголовок содержит дату.
     *
     * @param state состояние
     * @param date  день
     * @return карточка
     */
    static DayCardModel stubCard(AppState state, LocalDate date) {
        return new DayCardModel(date, "card " + date, "", ColorToken.TEXT_PRIMARY, List.of(), "", "");
    }

    /**
     * План без подушки и цели.
     *
     * @param start       начало
     * @param horizon     горизонт
     * @param balance     начальный баланс в рублях
     * @param rules       правила
     * @param oneTimes    разовые операции
     * @param adjustments корректировки
     * @return план
     */
    static Plan plan(LocalDate start, Horizon horizon, long balance, List<RecurringRule> rules,
                     List<OneTimeTransaction> oneTimes, List<Adjustment> adjustments) {
        return new Plan("Тест", "", Plan.DEFAULT_CURRENCY, start, Money.ofMajor(balance), horizon, Money.ZERO, null,
                rules, oneTimes, adjustments, List.of());
    }

    /**
     * План с подушкой и целью.
     *
     * @param plan    исходный план
     * @param cushion подушка в рублях
     * @param goal    цель или {@code null}
     * @return план
     */
    static Plan withCushionAndGoal(Plan plan, long cushion, Goal goal) {
        return plan.withCushion(Money.ofMajor(cushion)).withGoal(goal);
    }

    /**
     * Ежемесячное правило без сдвига с выходных.
     *
     * @param id    идентификатор
     * @param title название
     * @param kind  вид
     * @param major сумма в рублях
     * @param day   день месяца
     * @return правило
     */
    static RecurringRule monthly(String id, String title, Kind kind, long major, int day) {
        return new RecurringRule(new RuleId(id), title, kind, Money.ofMajor(major), "", new Recurrence.Monthly(day, 1),
                null, null, WeekendPolicy.NONE, true, "");
    }

    /**
     * Ежедневное правило в заданных границах.
     *
     * @param id    идентификатор
     * @param kind  вид
     * @param from  первый день
     * @param until последний день
     * @return правило
     */
    static RecurringRule daily(String id, Kind kind, LocalDate from, LocalDate until) {
        return new RecurringRule(new RuleId(id), "Каждый день", kind, Money.ofMajor(1), "", new Recurrence.EveryNDays(1),
                from, until, WeekendPolicy.NONE, true, "");
    }

    /**
     * Разовая операция.
     *
     * @param id    идентификатор
     * @param date  дата
     * @param title название
     * @param kind  вид
     * @param major сумма в рублях
     * @return операция
     */
    static OneTimeTransaction oneTime(String id, LocalDate date, String title, Kind kind, long major) {
        return new OneTimeTransaction(new TxId(id), date, title, kind, Money.ofMajor(major), "", "");
    }

    /**
     * Примитивы сцены одного вида по порядку.
     *
     * @param scene сцена
     * @param type  вид примитива
     * @param <T>   вид
     * @return примитивы
     */
    static <T extends ChartPrimitive> List<T> ofType(ChartScene scene, Class<T> type) {
        return scene.primitives().stream().filter(type::isInstance).map(type::cast).toList();
    }

    /**
     * Ширина дня области построения.
     *
     * @param scene сцена
     * @return пиксели на день
     */
    static double dayWidth(ChartScene scene) {
        return scene.plot().plotWidth() / scene.plot().dayCount();
    }
}
