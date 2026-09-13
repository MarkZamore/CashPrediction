package ru.cashprediction.core.app;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
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
import ru.cashprediction.core.ui.text.UiText;

/**
 * Единственный план «Пример» для трёх клиентов (спецификация v2, §6.24): «Файл → Открыть пример», кнопка мастера
 * «Открыть пример» и кнопка пустого состояния таблицы.
 *
 * <p>Состав: начало — 1-е число текущего месяца, баланс 150 000,00, горизонт 12 месяцев, подушка 50 000,00, цель
 * «Отпуск» 300 000,00 без даты, валюта ₽; правила r1 «Зарплата» +80 000 5-го (выходной → раньше), r2 «Аванс»
 * +40 000 20-го (раньше), r3 «Аренда» −45 000 1-го, r4 «Продукты» −4 000 каждую субботу, r5 «Кредит» −12 345,67
 * 31-го (в коротком месяце — последний день); разовая t1 «Премия» +60 000 20-го числа через 3 месяца.
 * Категории и заметки пустые: спецификация их не задаёт. План открывается несохранённым
 * (статус {@code status.msg.sample} показывает {@code FileFlow}).</p>
 *
 * <p>Названия плана, операций и цели пользователь видит в интерфейсе, поэтому они берутся из каталога текстов
 * ({@code sample.*} в {@code app_ru.properties}, решение L13), а не пишутся литералами.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class SamplePlan {

    /** Ключ каталога с именем плана-примера. */
    public static final String NAME_KEY = "sample.name";

    private SamplePlan() {
    }

    /** @return имя плана-примера («Пример») */
    public static String name() {
        return UiText.get(NAME_KEY);
    }

    /**
     * Строит план «Пример» относительно сегодняшней даты.
     *
     * @param today сегодняшняя дата ({@link AppClock#today()})
     * @return новый план
     */
    public static Plan create(LocalDate today) {
        LocalDate start = today.withDayOfMonth(1);
        List<RecurringRule> rules = List.of(
                rule("r1", "sample.rule.salary", Kind.INCOME, Money.ofMajor(80_000), new Recurrence.Monthly(5, 1),
                        WeekendPolicy.PREVIOUS_BUSINESS_DAY),
                rule("r2", "sample.rule.advance", Kind.INCOME, Money.ofMajor(40_000), new Recurrence.Monthly(20, 1),
                        WeekendPolicy.PREVIOUS_BUSINESS_DAY),
                rule("r3", "sample.rule.rent", Kind.EXPENSE, Money.ofMajor(45_000), new Recurrence.Monthly(1, 1),
                        WeekendPolicy.NONE),
                rule("r4", "sample.rule.groceries", Kind.EXPENSE, Money.ofMajor(4_000),
                        new Recurrence.Weekly(DayOfWeek.SATURDAY, 1), WeekendPolicy.NONE),
                rule("r5", "sample.rule.loan", Kind.EXPENSE, Money.ofMinor(1_234_567), new Recurrence.Monthly(31, 1),
                        WeekendPolicy.NONE));
        OneTimeTransaction bonus = new OneTimeTransaction(new TxId("t1"), start.plusMonths(3).withDayOfMonth(20),
                UiText.get("sample.oneTime.bonus"), Kind.INCOME, Money.ofMajor(60_000), "", "");
        return new Plan(name(), "", Plan.DEFAULT_CURRENCY, start, Money.ofMajor(150_000), new Horizon.Months(12),
                Money.ofMajor(50_000), new Goal(UiText.get("sample.goal"), Money.ofMajor(300_000), null),
                rules, List.of(bonus), List.of(), List.of());
    }

    private static RecurringRule rule(String id, String titleKey, Kind kind, Money amount, Recurrence recurrence,
                                      WeekendPolicy weekendPolicy) {
        return new RecurringRule(new RuleId(id), UiText.get(titleKey), kind, amount, "", recurrence, null, null,
                weekendPolicy, true, "");
    }
}
