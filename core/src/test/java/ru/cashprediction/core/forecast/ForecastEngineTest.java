package ru.cashprediction.core.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.cashprediction.core.forecast.TestPlans.START;
import static ru.cashprediction.core.forecast.TestPlans.adjust;
import static ru.cashprediction.core.forecast.TestPlans.d;
import static ru.cashprediction.core.forecast.TestPlans.monthly;
import static ru.cashprediction.core.forecast.TestPlans.oneTime;
import static ru.cashprediction.core.forecast.TestPlans.plan;
import static ru.cashprediction.core.forecast.TestPlans.rule;
import static ru.cashprediction.core.forecast.TestPlans.ruleRows;
import static ru.cashprediction.core.forecast.TestPlans.run;
import static ru.cashprediction.core.forecast.TestPlans.warningsOf;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.WeekendPolicy;

/**
 * Тесты движка прогноза: строки, порядок, корректировки, предупреждения, сводка, «что-если», производительность.
 */
class ForecastEngineTest {

    /** Зарплата 5-го числа на 12 месяцев даёт 12 строк. */
    @Test
    void salaryOnFifthForTwelveMonths() {
        Forecast f = run(plan(0, monthly("r1", "Зарплата", Kind.INCOME, 80_000, 5)));
        List<ForecastRow> rows = ruleRows(f);
        assertEquals(12, rows.size());
        assertEquals(13, f.rows().size(), "плюс строка начального баланса");
        for (ForecastRow row : rows) {
            assertEquals(5, row.date().getDayOfMonth());
            assertEquals(Money.ofMajor(80_000), row.amount());
        }
        assertEquals(d("2026-09-05"), rows.get(0).date());
        assertEquals(d("2027-08-05"), rows.get(11).date());
        assertEquals(Money.ofMajor(960_000), f.endBalance());
        assertEquals(Money.ofMajor(960_000), f.summary().totalIncome());
        assertEquals("r1@2026-09-05", rows.get(0).rowId());
        assertEquals(new OccurrenceKey(new RuleId("r1"), d("2026-09-05")), rows.get(0).occurrenceKey().orElseThrow());
    }

    /** Первая строка — «Начальный баланс». */
    @Test
    void startRow() {
        Forecast f = run(plan(150_000, monthly("r1", "Аренда", Kind.EXPENSE, 45_000, 1)));
        ForecastRow start = f.rows().get(0);
        assertEquals(START, start.date());
        assertEquals("Начальный баланс", start.title());
        assertEquals(Money.ZERO, start.amount());
        assertEquals(Money.ofMajor(150_000), start.balanceAfter());
        assertEquals(Origin.START, start.origin());
        assertEquals("start", start.rowId());
        assertNull(start.ruleId());
        assertTrue(start.occurrenceKey().isEmpty());
        assertEquals(Money.ofMajor(105_000), f.rows().get(1).balanceAfter(), "расход в день начала идёт после начального баланса");
    }

    /** Внутри дня: доходы раньше расходов, затем правила в порядке плана, затем разовые. */
    @Test
    void sameDayIncomeBeforeExpense() {
        Plan p = plan(0, new Horizon.Months(1),
                List.of(monthly("r1", "Аренда", Kind.EXPENSE, 100, 10),
                        monthly("r2", "Зарплата", Kind.INCOME, 200, 10),
                        monthly("r3", "Аванс", Kind.INCOME, 50, 10)),
                List.of(oneTime("t1", "2026-09-10", "Покупка", Kind.EXPENSE, 10),
                        oneTime("t2", "2026-09-10", "Возврат", Kind.INCOME, 5)),
                List.of());
        Forecast f = run(p);
        assertEquals(List.of("start", "r2@2026-09-10", "r3@2026-09-10", "t2", "r1@2026-09-10", "t1"),
                f.rows().stream().map(ForecastRow::rowId).toList());
        assertEquals(Money.ofMajor(145), f.endBalance());
    }

    /** Четыре действия корректировок; «перенести» и «заменить» игнорируют политику выходных. */
    @Test
    void allFourAdjustmentActions() {
        RecurringRule salary = rule("r1", "Зарплата", Kind.INCOME, Money.ofMajor(80_000), new Recurrence.Monthly(5, 1),
                null, null, WeekendPolicy.PREVIOUS_BUSINESS_DAY);
        Plan p = plan(0, new Horizon.Months(12), List.of(salary), List.of(), List.of(
                adjust("r1", "2026-10-05", new Adjustment.ChangeAmount(Money.ofMajor(95_000))),
                adjust("r1", "2026-11-05", new Adjustment.MoveDate(d("2026-12-19"))),
                adjust("r1", "2027-01-05", new Adjustment.Replace(Money.ofMajor(70_000), d("2027-01-09"))),
                adjust("r1", "2027-02-05", new Adjustment.Skip())));
        Forecast f = run(p);

        ForecastRow sept = f.findRow("r1@2026-09-05").orElseThrow();
        assertEquals(d("2026-09-04"), sept.date(), "суббота сдвинута на пятницу");
        assertTrue(sept.flags().shifted());

        ForecastRow oct = f.findRow("r1@2026-10-05").orElseThrow();
        assertEquals(Money.ofMajor(95_000), oct.amount());
        assertTrue(oct.flags().amountChanged());
        assertFalse(oct.flags().moved());

        ForecastRow nov = f.findRow("r1@2026-11-05").orElseThrow();
        assertEquals(d("2026-12-19"), nov.date(), "суббота, но дата точная - без сдвига");
        assertEquals(DayOfWeek.SATURDAY, nov.date().getDayOfWeek());
        assertEquals(d("2026-11-05"), nov.originalDate());
        assertTrue(nov.flags().moved());
        assertFalse(nov.flags().shifted());
        assertEquals(Money.ofMajor(80_000), nov.amount());

        ForecastRow jan = f.findRow("r1@2027-01-05").orElseThrow();
        assertEquals(d("2027-01-09"), jan.date());
        assertEquals(Money.ofMajor(70_000), jan.amount());
        assertTrue(jan.flags().moved());
        assertTrue(jan.flags().amountChanged());

        assertTrue(f.findRow("r1@2027-02-05").isEmpty(), "пропущенное событие не показывается");
        assertEquals(11, ruleRows(f).size());
        assertEquals(Money.ofMajor(885_000), f.endBalance());
        assertTrue(f.warnings().isEmpty(), f.warnings().toString());

        // Строки упорядочены по фактической дате: перенесённая ноябрьская — после декабрьской.
        List<LocalDate> datesInOrder = f.rows().stream().map(ForecastRow::date).toList();
        List<LocalDate> sorted = new ArrayList<>(datesInOrder);
        sorted.sort(null);
        assertEquals(sorted, datesInOrder);
    }

    /** Заметка корректировки заменяет заметку правила. */
    @Test
    void adjustmentNoteOverridesRuleNote() {
        RecurringRule r = new RecurringRule(new RuleId("r1"), "Зарплата", Kind.INCOME, Money.ofMajor(1), "", new Recurrence.Monthly(5, 1),
                null, null, WeekendPolicy.NONE, true, "заметка правила");
        Plan p = plan(0, new Horizon.Months(2), List.of(r), List.of(), List.of(
                new Adjustment(new OccurrenceKey(r.id(), d("2026-10-05")), new Adjustment.ChangeAmount(Money.ofMajor(2)), "бонус")));
        Forecast f = run(p);
        assertEquals("заметка правила", f.findRow("r1@2026-09-05").orElseThrow().note());
        assertEquals("бонус", f.findRow("r1@2026-10-05").orElseThrow().note());
    }

    /** «Пропустить» с includeSkipped=false и true. */
    @Test
    void skipWithAndWithoutIncludeSkipped() {
        Plan p = plan(1000, new Horizon.Months(3), List.of(monthly("r1", "Кофе", Kind.EXPENSE, 100, 10)), List.of(),
                List.of(adjust("r1", "2026-10-10", new Adjustment.Skip())));
        Forecast hidden = ForecastEngine.forecast(p, WhatIf.NONE, START, false);
        assertEquals(2, ruleRows(hidden).size());

        Forecast shown = ForecastEngine.forecast(p, WhatIf.NONE, START, true);
        List<ForecastRow> rows = ruleRows(shown);
        assertEquals(3, rows.size());
        ForecastRow skipped = rows.get(1);
        assertTrue(skipped.flags().skipped());
        assertEquals(Money.ofMajor(-100), skipped.amount(), "сумма, которая была бы без пропуска");
        assertEquals(Money.ofMajor(900), skipped.balanceAfter(), "баланс не меняется");
        assertFalse(skipped.affectsBalance());
        assertEquals(Money.ofMajor(800), rows.get(2).balanceAfter());
        assertEquals(Money.ofMajor(900), shown.balanceAt(d("2026-10-10")));
        assertEquals(hidden.endBalance(), shown.endBalance());
        assertEquals(hidden.summary().totalExpense(), shown.summary().totalExpense(), "пропущенные не входят в итоги");
    }

    /** Перенос за горизонт убирает строку и даёт предупреждение. */
    @Test
    void moveOutOfHorizonWarns() {
        Plan p = plan(0, new Horizon.Months(12), List.of(monthly("r1", "Зарплата", Kind.INCOME, 100, 5)), List.of(),
                List.of(adjust("r1", "2027-08-05", new Adjustment.MoveDate(d("2027-09-15")))));
        Forecast f = run(p);
        assertEquals(11, ruleRows(f).size());
        List<Warning> warnings = warningsOf(f, WarningType.MOVED_OUT_OF_HORIZON);
        assertEquals(1, warnings.size());
        assertEquals(d("2027-08-05"), warnings.get(0).date());
    }

    /** Корректировки-сироты: нет правила или нет события в эту дату; вне горизонта и у выключенных правил — молчим. */
    @Test
    void orphanAdjustments() {
        RecurringRule r1 = monthly("r1", "Зарплата", Kind.INCOME, 100, 5);
        RecurringRule r2 = monthly("r2", "Отключено", Kind.EXPENSE, 10, 7).withEnabled(false);
        RecurringRule r3 = rule("r3", "Позже", Kind.EXPENSE, Money.ofMajor(10), new Recurrence.Monthly(5, 1),
                d("2027-01-01"), null, WeekendPolicy.NONE);
        Plan p = plan(0, new Horizon.Months(12), List.of(r1, r2, r3), List.of(), List.of(
                adjust("r9", "2026-10-05", new Adjustment.Skip()),
                adjust("r1", "2026-10-06", new Adjustment.ChangeAmount(Money.ofMajor(1))),
                adjust("r1", "2025-10-05", new Adjustment.Skip()),
                adjust("r2", "2026-10-06", new Adjustment.Skip()),
                adjust("r1", "2026-11-05", new Adjustment.Skip()),
                adjust("r3", "2026-10-05", new Adjustment.Skip())));
        Forecast f = run(p);
        List<Warning> orphans = warningsOf(f, WarningType.ORPHAN_ADJUSTMENT);
        assertEquals(List.of(d("2026-10-05"), d("2026-10-06")), orphans.stream().map(Warning::date).toList(), orphans.toString());
        assertTrue(orphans.get(0).message().contains("r9"));
        assertTrue(ruleRows(f).stream().noneMatch(r -> r.ruleId().value().equals("r2")), "выключенное правило не даёт строк");
    }

    /** Несколько корректировок одного события: действует последняя, одно предупреждение. */
    @Test
    void duplicateAdjustmentsLastWins() {
        Plan p = plan(0, new Horizon.Months(12), List.of(monthly("r1", "Зарплата", Kind.INCOME, 80_000, 5)), List.of(), List.of(
                adjust("r1", "2026-10-05", new Adjustment.ChangeAmount(Money.ofMajor(90_000))),
                adjust("r1", "2026-10-05", new Adjustment.ChangeAmount(Money.ofMajor(95_000)))));
        Forecast f = run(p);
        assertEquals(Money.ofMajor(95_000), f.findRow("r1@2026-10-05").orElseThrow().amount());
        List<Warning> duplicates = warningsOf(f, WarningType.DUPLICATE_ADJUSTMENT);
        assertEquals(1, duplicates.size());
        assertEquals(d("2026-10-05"), duplicates.get(0).date());
        assertTrue(warningsOf(f, WarningType.ORPHAN_ADJUSTMENT).isEmpty());
    }

    /** Отметка сдвига с выходного. */
    @Test
    void shiftedFlag() {
        Forecast f = run(plan(0, rule("r1", "Зарплата", Kind.INCOME, Money.ofMajor(1), new Recurrence.Monthly(5, 1),
                null, null, WeekendPolicy.NEXT_BUSINESS_DAY)));
        ForecastRow sept = f.findRow("r1@2026-09-05").orElseThrow();
        assertEquals(d("2026-09-07"), sept.date());
        assertEquals(d("2026-09-05"), sept.originalDate());
        assertTrue(sept.flags().shifted());
        assertFalse(f.findRow("r1@2026-10-05").orElseThrow().flags().shifted(), "понедельник не сдвигается");
    }

    /** Длина и ступеньки ежедневной серии; прижатие balanceAt. */
    @Test
    void dailyBalanceLengthAndSteps() {
        Forecast f = run(plan(1000, monthly("r1", "Доход", Kind.INCOME, 500, 5)));
        assertEquals(365, f.dayCount());
        long[] daily = f.dailyBalance();
        assertEquals(365, daily.length);
        for (int i = 0; i < 4; i++) {
            assertEquals(100_000, daily[i], "до 5-го числа");
        }
        assertEquals(150_000, daily[4], "05.09.2026 на конец дня");
        assertEquals(Money.ofMajor(1500), f.balanceAt(d("2026-10-04")));
        assertEquals(Money.ofMajor(2000), f.balanceAt(d("2026-10-05")));
        assertEquals(Money.ofMajor(1000), f.balanceAt(d("2026-08-31")), "до начала - начальный баланс");
        assertEquals(Money.ofMajor(7000), f.balanceAt(d("2030-01-01")), "после конца - конечный");
        assertEquals(d("2027-08-31"), f.endDate());
        assertEquals(f.endBalance(), f.rows().get(f.rows().size() - 1).balanceAfter());

        daily[0] = 42;
        assertEquals(100_000, f.balanceMinorAt(0), "защитная копия");

        Plan leap = new Plan("Високосный", "", "₽", d("2027-09-01"), Money.ZERO, new Horizon.Months(12), Money.ZERO, null,
                List.of(), List.of(), List.of(), List.of());
        assertEquals(366, run(leap).dayCount(), "горизонт включает 29.02.2028");
    }

    /** Опорная дата и отметки «прошедшая», когда сегодня позже начала плана. */
    @Test
    void anchorAndPastFlags() {
        Plan p = plan(0, monthly("r1", "Доход", Kind.INCOME, 100, 5));
        Forecast f = ForecastEngine.forecast(p, WhatIf.NONE, d("2026-10-15"), false);
        assertEquals(d("2026-10-15"), f.anchor());
        assertEquals(d("2026-10-15"), f.summary().anchor());
        assertTrue(f.rows().get(0).flags().past());
        assertTrue(f.findRow("r1@2026-09-05").orElseThrow().flags().past());
        assertTrue(f.findRow("r1@2026-10-05").orElseThrow().flags().past());
        assertFalse(f.findRow("r1@2026-11-05").orElseThrow().flags().past());

        Forecast early = ForecastEngine.forecast(p, WhatIf.NONE, d("2026-08-01"), false);
        assertEquals(START, early.anchor());
        assertTrue(early.rows().stream().noneMatch(r -> r.flags().past()));
    }

    /** Ключи «через k месяцев» присутствуют, только если дата в горизонте. */
    @Test
    void summaryMonthKeysPresence() {
        RecurringRule r = monthly("r1", "Доход", Kind.INCOME, 100, 5);
        Forecast m12 = run(plan(0, new Horizon.Months(12), List.of(r), List.of(), List.of()));
        assertEquals(List.of(1, 3, 6), List.copyOf(m12.summary().balanceAfterMonths().keySet()));
        assertEquals(m12.balanceAt(d("2026-10-01")), m12.summary().balanceAfter(1).orElseThrow());
        assertEquals(Money.ofMajor(100), m12.summary().balanceAfterMonths().get(1));
        assertTrue(m12.summary().balanceAfter(12).isEmpty());

        assertEquals(List.of(1, 3, 6, 12),
                List.copyOf(run(plan(0, new Horizon.Months(24), List.of(r), List.of(), List.of())).summary().balanceAfterMonths().keySet()));
        assertEquals(List.of(1, 3, 6, 12, 24),
                List.copyOf(run(plan(0, new Horizon.Months(25), List.of(r), List.of(), List.of())).summary().balanceAfterMonths().keySet()));
        Forecast later = ForecastEngine.forecast(plan(0, new Horizon.Months(24), List.of(r), List.of(), List.of()),
                WhatIf.NONE, d("2026-10-15"), false);
        assertEquals(List.of(1, 3, 6, 12), List.copyOf(later.summary().balanceAfterMonths().keySet()));
        assertEquals(later.balanceAt(d("2027-10-15")), later.summary().balanceAfterMonths().get(12));
    }

    /** Итоги по месяцам покрывают каждый месяц горизонта, включая пустые. */
    @Test
    void byMonthCoversEmptyMonths() {
        Forecast f = run(plan(0, new Horizon.Months(12), List.of(), List.of(oneTime("t1", "2026-11-20", "Премия", Kind.INCOME, 1000)), List.of()));
        List<YearMonth> months = List.copyOf(f.summary().byMonth().keySet());
        assertEquals(12, months.size());
        assertEquals(YearMonth.of(2026, 9), months.get(0));
        assertEquals(YearMonth.of(2027, 8), months.get(11));
        MonthTotals oct = f.summary().byMonth().get(YearMonth.of(2026, 10));
        assertEquals(new MonthTotals(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO), oct);
        assertEquals(new MonthTotals(Money.ofMajor(1000), Money.ZERO, Money.ofMajor(1000), Money.ofMajor(1000)),
                f.summary().byMonth().get(YearMonth.of(2026, 11)));
        assertEquals(Money.ofMajor(1000), f.summary().byMonth().get(YearMonth.of(2027, 8)).closingBalance());

        Plan until = plan(0, new Horizon.Until(d("2026-11-10")), List.of(monthly("r1", "Расход", Kind.EXPENSE, 10, 15)), List.of(), List.of());
        Forecast u = run(until);
        assertEquals(List.of(YearMonth.of(2026, 9), YearMonth.of(2026, 10), YearMonth.of(2026, 11)),
                List.copyOf(u.summary().byMonth().keySet()));
        MonthTotals nov = u.summary().byMonth().get(YearMonth.of(2026, 11));
        assertEquals(Money.ofMajor(-20), nov.closingBalance(), "баланс на конец горизонта, а не месяца");
        assertEquals(new MonthTotals(Money.ZERO, Money.ofMajor(10), Money.ofMajor(-10), Money.ofMajor(-10)),
                u.summary().byMonth().get(YearMonth.of(2026, 9)));
    }

    /** Первый минус и первый провал ниже подушки, с предупреждениями. */
    @Test
    void firstNegativeAndBelowCushion() {
        Plan base = plan(10_000, monthly("r1", "Аренда", Kind.EXPENSE, 3000, 10)).withCushion(Money.ofMajor(5000));
        Forecast f = run(base);
        assertEquals(d("2026-10-10"), f.summary().firstBelowCushionDate().orElseThrow());
        assertEquals(d("2026-12-10"), f.summary().firstNegativeDate().orElseThrow());
        assertEquals(List.of(d("2026-12-10")), warningsOf(f, WarningType.NEGATIVE_BALANCE).stream().map(Warning::date).toList());
        assertEquals(List.of(d("2026-10-10")), warningsOf(f, WarningType.BELOW_CUSHION).stream().map(Warning::date).toList());
        assertEquals(Money.ofMajor(-26_000), f.summary().minBalance());
        assertEquals(d("2027-08-10"), f.summary().minBalanceDate());

        Forecast noCushion = run(base.withCushion(Money.ZERO));
        assertTrue(noCushion.summary().firstBelowCushionDate().isEmpty());
        assertTrue(warningsOf(noCushion, WarningType.BELOW_CUSHION).isEmpty());

        Forecast later = ForecastEngine.forecast(base, WhatIf.NONE, d("2026-12-15"), false);
        assertEquals(d("2026-12-15"), later.summary().firstNegativeDate().orElseThrow(), "поиск от anchor");
        assertEquals(d("2026-12-15"), later.summary().firstBelowCushionDate().orElseThrow());
    }

    /** Коэффициенты «что-если» и отметка whatIf. */
    @Test
    void whatIfFactors() {
        Plan p = plan(0, new Horizon.Months(1),
                List.of(monthly("r1", "Зарплата", Kind.INCOME, 1000, 5), monthly("r2", "Аренда", Kind.EXPENSE, 500, 6)),
                List.of(oneTime("t1", "2026-09-20", "Премия", Kind.INCOME, 200)), List.of());
        Forecast f = ForecastEngine.forecast(p, new WhatIf(new BigDecimal("1.1"), new BigDecimal("0.9"), Money.ZERO), START, false);
        ForecastRow income = f.findRow("r1@2026-09-05").orElseThrow();
        ForecastRow expense = f.findRow("r2@2026-09-06").orElseThrow();
        ForecastRow bonus = f.findRow("t1").orElseThrow();
        assertEquals(Money.ofMajor(1100), income.amount());
        assertEquals(Money.ofMajor(-450), expense.amount());
        assertEquals(Money.ofMajor(220), bonus.amount());
        assertTrue(income.flags().whatIf() && expense.flags().whatIf() && bonus.flags().whatIf());

        Forecast incomeOnly = ForecastEngine.forecast(p, WhatIf.ofPercent(10, 0, Money.ZERO), START, false);
        assertEquals(Money.ofMajor(-500), incomeOnly.findRow("r2@2026-09-06").orElseThrow().amount());
        assertFalse(incomeOnly.findRow("r2@2026-09-06").orElseThrow().flags().whatIf(), "коэффициент 1 - без отметки");

        assertTrue(WhatIf.NONE.isNone());
        assertTrue(new WhatIf(new BigDecimal("1.00"), BigDecimal.ONE, Money.ZERO).isNone());
        assertFalse(WhatIf.ofPercent(0, 0, Money.ofMajor(1)).isNone());
        assertEquals(0, new BigDecimal("0.90").compareTo(WhatIf.ofPercent(0, -10, Money.ZERO).expenseFactor()));
    }

    /** Дополнительная экономия — строки в последний день каждого месяца от anchor. */
    @Test
    void whatIfExtraSavingRows() {
        WhatIf saving = new WhatIf(BigDecimal.ONE, BigDecimal.ONE, Money.ofMajor(5000));
        Plan p = plan(0, new Horizon.Months(12),
                List.of(monthly("r1", "Кредит", Kind.EXPENSE, 100, 31)),
                List.of(oneTime("t1", "2026-10-31", "Возврат", Kind.INCOME, 10)), List.of());
        Forecast f = ForecastEngine.forecast(p, saving, d("2026-10-15"), false);
        List<ForecastRow> extra = f.rows().stream().filter(r -> r.origin() == Origin.WHAT_IF).toList();
        assertEquals(11, extra.size());
        ForecastRow first = extra.get(0);
        assertEquals(d("2026-10-31"), first.date());
        assertEquals("whatif@2026-10-31", first.rowId());
        assertEquals("Доп. экономия (что-если)", first.title());
        assertEquals(Kind.INCOME, first.kind());
        assertEquals(Money.ofMajor(5000), first.amount());
        assertTrue(first.flags().whatIf());
        assertEquals(d("2027-08-31"), extra.get(10).date());
        List<String> oct31 = f.rows().stream().filter(r -> r.date().equals(d("2026-10-31"))).map(ForecastRow::rowId).toList();
        assertEquals(List.of("t1", "whatif@2026-10-31", "r1@2026-10-31"), oct31);

        assertEquals(12, ForecastEngine.forecast(p, saving, START, false).rows().stream()
                .filter(r -> r.origin() == Origin.WHAT_IF).count(), "с начала плана - включая 30.09");
    }

    /** Разовые операции вне горизонта исключаются с предупреждением. */
    @Test
    void oneTimeOutsideHorizon() {
        Plan p = plan(0, new Horizon.Months(12), List.of(),
                List.of(oneTime("t1", "2027-09-01", "Позже", Kind.INCOME, 1), oneTime("t2", "2026-08-31", "Раньше", Kind.EXPENSE, 1),
                        oneTime("t3", "2027-08-31", "Последний день", Kind.INCOME, 1)), List.of());
        Forecast f = run(p);
        assertEquals(List.of("start", "t3"), f.rows().stream().map(ForecastRow::rowId).toList());
        assertEquals(2, warningsOf(f, WarningType.ONE_TIME_OUTSIDE_HORIZON).size());
    }

    /** Правило вне горизонта — предупреждение; выключенное — ни строк, ни предупреждений. */
    @Test
    void ruleOutsideHorizonAndDisabledRules() {
        RecurringRule outside = rule("r1", "Потом", Kind.INCOME, Money.ofMajor(1), new Recurrence.Monthly(5, 1),
                d("2028-01-01"), null, WeekendPolicy.NONE);
        Forecast f = run(plan(0, outside, outside.withId(new RuleId("r2")).withEnabled(false),
                monthly("r3", "Выкл", Kind.INCOME, 1, 5).withEnabled(false)));
        assertEquals(1, f.rows().size());
        assertEquals(1, warningsOf(f, WarningType.RULE_OUTSIDE_HORIZON).size());
        assertTrue(f.warnings().get(0).message().contains("r1"));
    }

    /** Цель: дата достижения в сводке и предупреждение, если не достигается или достигается поздно. */
    @Test
    void goalInSummaryAndWarning() {
        Plan base = plan(0, monthly("r1", "Доход", Kind.INCOME, 1000, 5));
        Forecast reached = run(base.withGoal(new Goal("Отпуск", Money.ofMajor(2500), null)));
        assertEquals(d("2026-11-05"), reached.summary().goalReachDate().orElseThrow());
        assertTrue(warningsOf(reached, WarningType.GOAL_NOT_REACHED).isEmpty());

        Forecast late = run(base.withGoal(new Goal("Отпуск", Money.ofMajor(2500), d("2026-10-01"))));
        assertEquals(1, warningsOf(late, WarningType.GOAL_NOT_REACHED).size());

        Forecast never = run(base.withGoal(new Goal("Дом", Money.ofMajor(1_000_000), null)));
        assertTrue(never.summary().goalReachDate().isEmpty());
        assertEquals(1, warningsOf(never, WarningType.GOAL_NOT_REACHED).size());
        assertTrue(run(base).summary().goalReachDate().isEmpty(), "без цели - пусто");
    }

    /** Итоги согласованы с балансом; средний итог месяца. */
    @Test
    void totalsAndAverageMonthlyNet() {
        Plan p = plan(500, monthly("r1", "Доход", Kind.INCOME, 1000, 5), monthly("r2", "Расход", Kind.EXPENSE, 300, 20));
        Forecast f = run(p);
        assertEquals(f.endBalance().minus(f.startBalance()), f.summary().totalIncome().minus(f.summary().totalExpense()));
        assertEquals(Money.ofMajor(3600), f.summary().totalExpense());

        Forecast oneMonth = run(plan(0, new Horizon.Months(1), List.of(monthly("r1", "Доход", Kind.INCOME, 1000, 5)), List.of(), List.of()));
        assertEquals(Money.ofMajor(1000), oneMonth.summary().averageMonthlyNet(), "30 дней < 30,4375: делитель 1");

        Plan year = new Plan("Год", "", "₽", d("2027-01-01"), Money.ZERO, new Horizon.Years(1), Money.ZERO, null, List.of(),
                List.of(new ru.cashprediction.core.model.OneTimeTransaction(new ru.cashprediction.core.model.TxId("t1"),
                        d("2027-06-01"), "Премия", Kind.INCOME, Money.ofMajor(3000), "", "")), List.of(), List.of());
        // 300 000 коп. × 30,4375 / 365 = 25 017,12… → 25 017 коп.
        assertEquals(Money.ofMinor(25_017), run(year).summary().averageMonthlyNet());
    }

    /** rowsBetween: строка начального баланса входит, когда from не позже начала плана. */
    @Test
    void rowsBetweenIncludesStartRow() {
        Forecast f = run(plan(0, monthly("r1", "Доход", Kind.INCOME, 1, 5)));
        assertEquals(List.of("start", "r1@2026-09-05"), f.rowsBetween(START, d("2026-09-30")).stream().map(ForecastRow::rowId).toList());
        assertEquals(List.of("r1@2026-10-05"), f.rowsBetween(d("2026-10-01"), d("2026-10-31")).stream().map(ForecastRow::rowId).toList());
        assertEquals(List.of("start"), f.rowsBetween(d("2026-08-01"), d("2026-08-15")).stream().map(ForecastRow::rowId).toList());
    }

    /** 50 лет, 10 правил (включая еженедельное и «каждые 3 дня») — быстрее 1,5 с. */
    @Test
    void fiftyYearsTenRulesIsFast() {
        List<RecurringRule> rules = List.of(
                rule("r1", "Зарплата", Kind.INCOME, Money.ofMajor(80_000), new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.PREVIOUS_BUSINESS_DAY),
                rule("r2", "Аванс", Kind.INCOME, Money.ofMajor(40_000), new Recurrence.Monthly(20, 1), null, null, WeekendPolicy.PREVIOUS_BUSINESS_DAY),
                rule("r3", "Аренда", Kind.EXPENSE, Money.ofMajor(45_000), new Recurrence.Monthly(1, 1), null, null, WeekendPolicy.NONE),
                rule("r4", "Продукты", Kind.EXPENSE, Money.ofMajor(4_000), new Recurrence.Weekly(DayOfWeek.SATURDAY, 1), null, null, WeekendPolicy.NONE),
                rule("r5", "Страховка", Kind.EXPENSE, Money.ofMajor(30_000), new Recurrence.Yearly(java.time.MonthDay.of(3, 15)), null, null, WeekendPolicy.NEXT_BUSINESS_DAY),
                rule("r6", "Абонемент", Kind.EXPENSE, Money.ofMajor(3_500), new Recurrence.Monthly(10, 2), d("2026-10-01"), null, WeekendPolicy.NONE),
                rule("r7", "Кофе", Kind.EXPENSE, Money.ofMajor(300), new Recurrence.EveryNDays(3), d("2026-09-02"), null, WeekendPolicy.NONE),
                rule("r8", "Кредит", Kind.EXPENSE, Money.parse("12 345,67"), new Recurrence.Monthly(31, 1), null, d("2036-03-31"), WeekendPolicy.PREVIOUS_BUSINESS_DAY),
                rule("r9", "Бассейн", Kind.EXPENSE, Money.ofMajor(1_000), new Recurrence.Weekly(DayOfWeek.WEDNESDAY, 2), null, null, WeekendPolicy.NONE),
                rule("r10", "Подработка", Kind.INCOME, Money.ofMajor(15_000), new Recurrence.Monthly(25, 1), null, null, WeekendPolicy.NEXT_BUSINESS_DAY));
        List<Adjustment> adjustments = new ArrayList<>();
        for (int year = 2027; year < 2076; year++) {
            adjustments.add(adjust("r1", year + "-12-05", new Adjustment.ChangeAmount(Money.ofMajor(160_000))));
        }
        Plan p = plan(150_000, new Horizon.Years(50), rules, List.of(), adjustments);
        long t0 = System.nanoTime();
        Forecast f = ForecastEngine.forecast(p, WhatIf.ofPercent(5, -5, Money.ofMajor(1000)), START, true);
        long millis = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(millis < 1500, "прогноз занял " + millis + " мс");
        // 50 × 365 дней + 13 високосных дней (29.02.2028 … 29.02.2076).
        assertEquals(18_263, f.dayCount());
        assertTrue(f.rows().size() > 18_263 / 3, "строк: " + f.rows().size());
        assertTrue(warningsOf(f, WarningType.ORPHAN_ADJUSTMENT).isEmpty(), f.warnings().toString());
        assertEquals(f.endBalance(), f.rows().get(f.rows().size() - 1).balanceAfter());
    }

    /** План с произвольной датой начала: краевые случаи горизонта. */
    private static Plan planFrom(LocalDate start, Horizon horizon, List<RecurringRule> rules, List<Adjustment> adjustments) {
        return new Plan("Тест", "", "₽", start, Money.ZERO, horizon, Money.ZERO, null, rules, List.of(), adjustments, List.of());
    }

    /**
     * Регрессия: событие с номинальной датой в выходной перед началом плана, сдвинутое «позже» на день начала,
     * попадает в прогноз (итоговая дата внутри горизонта).
     */
    @Test
    void weekendShiftFromBeforeStartLandsInHorizon() {
        LocalDate monday = d("2026-09-07");
        Plan p = planFrom(monday, new Horizon.Months(1), List.of(rule("r1", "Зарплата", Kind.INCOME, Money.ofMajor(100),
                new Recurrence.Monthly(6, 1), null, null, WeekendPolicy.NEXT_BUSINESS_DAY)), List.of());
        Forecast f = ForecastEngine.forecast(p, WhatIf.NONE, monday, false);
        assertEquals(List.of("start", "r1@2026-09-06", "r1@2026-10-06"), f.rows().stream().map(ForecastRow::rowId).toList());
        ForecastRow row = f.rows().get(1);
        assertEquals(monday, row.date());
        assertTrue(row.flags().shifted());
        assertEquals(Money.ofMajor(100), f.balanceAt(monday));
        assertTrue(f.warnings().isEmpty(), f.warnings().toString());
    }

    /** Регрессия: суббота сразу после конца горизонта, сдвинутая «раньше» на последний день, попадает в прогноз. */
    @Test
    void weekendShiftFromAfterEndLandsInHorizon() {
        // 03.09.2026 — четверг; горизонт в 1 месяц заканчивается в пятницу 02.10.2026, а 03.10 — суббота.
        LocalDate start = d("2026-09-03");
        Plan p = planFrom(start, new Horizon.Months(1), List.of(rule("r1", "Аванс", Kind.INCOME, Money.ofMajor(10),
                new Recurrence.Monthly(3, 1), null, null, WeekendPolicy.PREVIOUS_BUSINESS_DAY)), List.of());
        Forecast f = ForecastEngine.forecast(p, WhatIf.NONE, start, false);
        assertEquals(d("2026-10-02"), f.endDate());
        assertEquals(List.of("start", "r1@2026-09-03", "r1@2026-10-03"), f.rows().stream().map(ForecastRow::rowId).toList());
        assertEquals(d("2026-10-02"), f.rows().get(2).date());
        assertEquals(Money.ofMajor(20), f.endBalance());
    }

    /**
     * Сдвиг раньше начала плана убирает событие: оно состоялось до начала и уже входит в начальный баланс
     * (после «Актуализировать» иначе учлось бы дважды). Сдвиг за конец горизонта по-прежнему не применяется.
     */
    @Test
    void weekendShiftBeforeStartDropsEventButShiftAfterEndKeepsNominal() {
        LocalDate saturday = d("2026-09-05");
        Plan p = planFrom(saturday, new Horizon.Months(1), List.of(rule("r1", "Зарплата", Kind.INCOME, Money.ofMajor(100),
                new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.PREVIOUS_BUSINESS_DAY)), List.of());
        Forecast f = ForecastEngine.forecast(p, WhatIf.NONE, saturday, false);
        assertEquals(List.of("start"), f.rows().stream().map(ForecastRow::rowId).toList(), "выплачено в пятницу до начала плана");

        // 04.10.2026 — воскресенье и последний день горизонта; «позже» вывел бы событие за горизонт.
        LocalDate start = d("2026-09-05");
        Plan end = planFrom(start, new Horizon.Months(1), List.of(rule("r2", "Аванс", Kind.INCOME, Money.ofMajor(10),
                new Recurrence.Monthly(4, 1), null, null, WeekendPolicy.NEXT_BUSINESS_DAY)), List.of());
        Forecast g = ForecastEngine.forecast(end, WhatIf.NONE, start, false);
        assertEquals(d("2026-10-04"), g.endDate());
        ForecastRow last = g.findRow("r2@2026-10-04").orElseThrow();
        assertEquals(d("2026-10-04"), last.date(), "остаётся номинальная дата");
        assertFalse(last.flags().shifted());
    }

    /**
     * Регрессия: «перенести»/«заменить» переносит событие из-за пределов горизонта внутрь него; корректировка
     * на дату, в которую правило события не создаёт, ни на что не влияет и (вне горизонта) не предупреждает.
     */
    @Test
    void adjustmentMovesEventIntoHorizonFromOutside() {
        Plan p = plan(0, new Horizon.Months(12), List.of(monthly("r1", "Зарплата", Kind.INCOME, 100, 5)), List.of(), List.of(
                adjust("r1", "2026-08-05", new Adjustment.MoveDate(d("2026-09-10"))),
                adjust("r1", "2026-08-06", new Adjustment.MoveDate(d("2026-09-11"))),
                adjust("r1", "2027-09-05", new Adjustment.Replace(Money.ofMajor(70), d("2027-08-20"))),
                adjust("r1", "2026-07-05", new Adjustment.MoveDate(d("2026-08-31")))));
        Forecast f = run(p);
        ForecastRow fromPast = f.findRow("r1@2026-08-05").orElseThrow();
        assertEquals(d("2026-09-10"), fromPast.date());
        assertTrue(fromPast.flags().moved());
        ForecastRow fromFuture = f.findRow("r1@2027-09-05").orElseThrow();
        assertEquals(d("2027-08-20"), fromFuture.date());
        assertEquals(Money.ofMajor(70), fromFuture.amount());
        assertTrue(f.findRow("r1@2026-08-06").isEmpty(), "06.08 - не дата правила");
        assertTrue(f.findRow("r1@2026-07-05").isEmpty(), "перенос в прошлое за горизонт");
        assertEquals(14, ruleRows(f).size());
        assertEquals(Money.ofMajor(1_370), f.endBalance());
        assertTrue(f.warnings().isEmpty(), f.warnings().toString());
    }

    /** Правило вне горизонта, чьё событие перенесено в горизонт, даёт строку и не считается «не действующим». */
    @Test
    void ruleOutsideHorizonWithEventMovedInsideIsNotReported() {
        RecurringRule past = rule("r1", "Старое", Kind.EXPENSE, Money.ofMajor(5), new Recurrence.Monthly(5, 1),
                null, d("2026-08-31"), WeekendPolicy.NONE);
        Plan p = plan(0, new Horizon.Months(12), List.of(past), List.of(),
                List.of(adjust("r1", "2026-08-05", new Adjustment.MoveDate(d("2026-09-02")))));
        Forecast f = run(p);
        assertEquals(List.of("start", "r1@2026-08-05"), f.rows().stream().map(ForecastRow::rowId).toList());
        assertTrue(warningsOf(f, WarningType.RULE_OUTSIDE_HORIZON).isEmpty(), f.warnings().toString());
    }
}
