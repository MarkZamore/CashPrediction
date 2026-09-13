package ru.cashprediction.core.forecast;

import java.time.LocalDate;
import java.util.List;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.model.WeekendPolicy;

/**
 * Построители тестовых планов. Все даты заданы явно (начало плана — вторник 01.09.2026),
 * поэтому тесты не зависят от текущей даты.
 */
public final class TestPlans {

    /** Дата начала тестовых планов: вторник. */
    public static final LocalDate START = LocalDate.of(2026, 9, 1);

    private TestPlans() {
    }

    /** @return дата из ISO-строки */
    public static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    /** @return ежемесячное правило без сдвига с выходных и без «С/По» */
    public static RecurringRule monthly(String id, String title, Kind kind, long major, int day) {
        return rule(id, title, kind, Money.ofMajor(major), new Recurrence.Monthly(day, 1), null, null, WeekendPolicy.NONE);
    }

    /** @return включённое правило с произвольными параметрами */
    public static RecurringRule rule(String id, String title, Kind kind, Money amount, Recurrence recurrence,
                                     LocalDate from, LocalDate until, WeekendPolicy policy) {
        return new RecurringRule(new RuleId(id), title, kind, amount, "", recurrence, from, until, policy, true, "");
    }

    /** @return разовая операция */
    public static OneTimeTransaction oneTime(String id, String date, String title, Kind kind, long major) {
        return new OneTimeTransaction(new TxId(id), d(date), title, kind, Money.ofMajor(major), "", "");
    }

    /** @return корректировка события правила */
    public static Adjustment adjust(String ruleId, String date, Adjustment.Action action) {
        return new Adjustment(new OccurrenceKey(new RuleId(ruleId), d(date)), action, "");
    }

    /** @return план с началом {@link #START} */
    public static Plan plan(long startMajor, Horizon horizon, List<RecurringRule> rules, List<OneTimeTransaction> oneTimes,
                            List<Adjustment> adjustments) {
        return new Plan("Тест", "", "₽", START, Money.ofMajor(startMajor), horizon, Money.ZERO, null,
                rules, oneTimes, adjustments, List.of());
    }

    /** @return план на 12 месяцев только с правилами */
    public static Plan plan(long startMajor, RecurringRule... rules) {
        return plan(startMajor, new Horizon.Months(12), List.of(rules), List.of(), List.of());
    }

    /** @return прогноз без «что-если», «сегодня» = начало плана, без пропущенных */
    public static Forecast run(Plan plan) {
        return ForecastEngine.forecast(plan, WhatIf.NONE, START, false);
    }

    /** @return строки регулярных операций */
    public static List<ForecastRow> ruleRows(Forecast forecast) {
        return forecast.rows().stream().filter(r -> r.origin() == Origin.RULE).toList();
    }

    /** @return предупреждения заданного вида */
    public static List<Warning> warningsOf(Forecast forecast, WarningType type) {
        return forecast.warnings().stream().filter(w -> w.type() == type).toList();
    }
}
