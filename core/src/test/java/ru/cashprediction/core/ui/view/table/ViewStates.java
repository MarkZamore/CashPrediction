package ru.cashprediction.core.ui.view.table;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.app.SamplePlan;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
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

/**
 * Готовые {@link AppState} с настоящим прогнозом для тестов моделей сводки, таблицы и всплывающих окон: прогноз
 * считается {@link ForecastEngine} так же, как это делает контроллер (с «что-если» и показом пропущенных из вида).
 */
public final class ViewStates {

    /** «Сегодня» эталонных сценариев (архитектура §6.3): воскресенье 13.09.2026. */
    public static final LocalDate TODAY = LocalDate.of(2026, 9, 13);

    private ViewStates() {
    }

    /**
     * Состояние с рассчитанным прогнозом.
     *
     * @param plan          план
     * @param today         сегодня
     * @param view          вид
     * @param pastExpanded  раскрыта ли группа прошедших
     * @param selectedRowId выделенная строка
     * @return состояние
     */
    public static AppState of(Plan plan, LocalDate today, ViewState view, boolean pastExpanded, String selectedRowId) {
        Forecast forecast = ForecastEngine.forecast(plan, view.whatIf(), today, view.showSkipped());
        DocumentView document = new DocumentView(plan, null, false, false, "", false, "", forecast, "", List.of());
        return new AppState(1, ClientProfile.swing(), today, Path.of("CashMemory"), null, document, view,
                selectedRowId, pastExpanded, AppSettings.defaults(), null, List.of(), null, null, "");
    }

    /**
     * Состояние на {@link #TODAY} со свёрнутой группой и без выделения.
     *
     * @param plan план
     * @param view вид
     * @return состояние
     */
    public static AppState of(Plan plan, ViewState view) {
        return of(plan, TODAY, view, false, "");
    }

    /** @return план «Пример» на {@link #TODAY} с видом по умолчанию */
    public static AppState sample() {
        return of(SamplePlan.create(TODAY), ViewState.defaults());
    }

    /**
     * Состояние, в котором прогноз не рассчитан.
     *
     * @param plan  план
     * @param error причина
     * @param view  вид
     * @return состояние без прогноза
     */
    public static AppState failed(Plan plan, String error, ViewState view) {
        DocumentView document = new DocumentView(plan, null, false, false, "", false, "", null, error, List.of());
        return new AppState(1, ClientProfile.swing(), TODAY, Path.of("CashMemory"), null, document, view, "", false,
                AppSettings.defaults(), null, List.of(), null, null, "");
    }

    /**
     * План без цели.
     *
     * @param start        начало
     * @param balanceMajor начальный баланс в рублях
     * @param horizon      горизонт
     * @param cushionMajor подушка в рублях
     * @param rules        правила
     * @param oneTimes     разовые операции
     * @return план «Тест»
     */
    public static Plan plan(LocalDate start, long balanceMajor, Horizon horizon, long cushionMajor,
                            List<RecurringRule> rules, List<OneTimeTransaction> oneTimes) {
        return new Plan("Тест", "", Plan.DEFAULT_CURRENCY, start, Money.ofMajor(balanceMajor), horizon,
                Money.ofMajor(cushionMajor), null, rules, oneTimes, List.of(), List.of());
    }

    /**
     * Разовая операция без категории и заметки.
     *
     * @param id    id
     * @param date  дата
     * @param title название
     * @param kind  тип
     * @param major сумма в рублях
     * @return операция
     */
    public static OneTimeTransaction oneTime(String id, LocalDate date, String title, Kind kind, long major) {
        return new OneTimeTransaction(new TxId(id), date, title, kind, Money.ofMajor(major), "", "");
    }

    /**
     * Ежемесячное правило без сдвига с выходных.
     *
     * @param id    id
     * @param title название
     * @param kind  тип
     * @param major сумма в рублях
     * @param day   день месяца
     * @return правило
     */
    public static RecurringRule monthly(String id, String title, Kind kind, long major, int day) {
        return new RecurringRule(new RuleId(id), title, kind, Money.ofMajor(major), "", new Recurrence.Monthly(day, 1),
                null, null, WeekendPolicy.NONE, true, "");
    }

    /**
     * Прогноз состояния.
     *
     * @param state состояние
     * @return прогноз (не {@code null})
     */
    public static Forecast forecast(AppState state) {
        return state.document().forecast();
    }
}
