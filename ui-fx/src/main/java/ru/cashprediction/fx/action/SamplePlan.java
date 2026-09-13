package ru.cashprediction.fx.action;

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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * План «Пример» для команды «Файл → Открыть пример».
 *
 * <p>Состав плана зафиксирован общей спецификацией трёх клиентов (JavaFX, Swing, Web): один и тот же пример
 * даёт одинаковую таблицу и одинаковый файл .md в любом клиенте. Категории и заметки намеренно пустые —
 * спецификация их не задаёт. Класс без состояния, потокобезопасен.</p>
 */
public final class SamplePlan {

    private SamplePlan() {
    }

    /**
     * Строит план «Пример».
     *
     * <p>Начало — первое число текущего месяца, баланс 150 000,00, горизонт 12 месяцев, подушка 50 000,00;
     * правила: r1 «Зарплата» +80 000,00 5-го (выходной → раньше), r2 «Аванс» +40 000,00 20-го (выходной → раньше),
     * r3 «Аренда» −45 000,00 1-го, r4 «Продукты» −4 000,00 по субботам, r5 «Кредит» −12 345,67 31-го
     * (последний день месяца); разовая t1 «Премия» +60 000,00 20-го числа через три месяца; цель «Отпуск» 300 000,00.</p>
     *
     * @param today сегодняшняя дата
     * @return новый несохранённый план
     */
    public static Plan samplePlan(LocalDate today) {
        LocalDate start = today.withDayOfMonth(1);
        List<RecurringRule> rules = List.of(
                rule("r1", "Зарплата", Kind.INCOME, Money.ofMajor(80_000), new Recurrence.Monthly(5, 1),
                        WeekendPolicy.PREVIOUS_BUSINESS_DAY),
                rule("r2", "Аванс", Kind.INCOME, Money.ofMajor(40_000), new Recurrence.Monthly(20, 1),
                        WeekendPolicy.PREVIOUS_BUSINESS_DAY),
                rule("r3", "Аренда", Kind.EXPENSE, Money.ofMajor(45_000), new Recurrence.Monthly(1, 1),
                        WeekendPolicy.NONE),
                rule("r4", "Продукты", Kind.EXPENSE, Money.ofMajor(4_000), new Recurrence.Weekly(DayOfWeek.SATURDAY, 1),
                        WeekendPolicy.NONE),
                rule("r5", "Кредит", Kind.EXPENSE, Money.ofMinor(1_234_567), new Recurrence.Monthly(31, 1),
                        WeekendPolicy.NONE));
        OneTimeTransaction bonus = new OneTimeTransaction(new TxId("t1"), start.plusMonths(3).withDayOfMonth(20),
                "Премия", Kind.INCOME, Money.ofMajor(60_000), "", "");
        return new Plan("Пример", "", Plan.DEFAULT_CURRENCY, start, Money.ofMajor(150_000), new Horizon.Months(12),
                Money.ofMajor(50_000), new Goal("Отпуск", Money.ofMajor(300_000), null),
                rules, List.of(bonus), List.of(), List.of());
    }

    private static RecurringRule rule(String id, String title, Kind kind, Money amount, Recurrence recurrence,
                                      WeekendPolicy weekendPolicy) {
        return new RecurringRule(new RuleId(id), title, kind, amount, "", recurrence, null, null, weekendPolicy, true, "");
    }
}
