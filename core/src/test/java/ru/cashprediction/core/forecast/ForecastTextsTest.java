package ru.cashprediction.core.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static ru.cashprediction.core.forecast.TestPlans.START;
import static ru.cashprediction.core.forecast.TestPlans.adjust;
import static ru.cashprediction.core.forecast.TestPlans.d;
import static ru.cashprediction.core.forecast.TestPlans.monthly;
import static ru.cashprediction.core.forecast.TestPlans.oneTime;
import static ru.cashprediction.core.forecast.TestPlans.plan;
import static ru.cashprediction.core.forecast.TestPlans.rule;
import static ru.cashprediction.core.forecast.TestPlans.run;
import static ru.cashprediction.core.forecast.TestPlans.warningsOf;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.WeekendPolicy;

/**
 * Этап S0.5: тексты прогноза (названия строк, источники, виды и сообщения предупреждений, ошибки движка) берутся
 * из каталога ({@code forecast_ru.properties}) и совпадают с прежними русскими строками кода буква в букву.
 */
class ForecastTextsTest {

    private static final Horizon YEAR = new Horizon.Months(12);

    /** Названия синтетических строк, источников и видов предупреждений. */
    @Test
    void titles() {
        assertEquals("Начальный баланс", ForecastEngine.START_TITLE);
        assertEquals("Доп. экономия (что-если)", ForecastEngine.WHAT_IF_TITLE);
        assertEquals(List.of("Начальный баланс", "Регулярная", "Разовая", "Что-если"),
                List.of(Origin.values()).stream().map(Origin::title).toList());
        assertEquals(List.of("Отрицательный баланс", "Ниже подушки безопасности", "Корректировка без события", "Перенос за горизонт",
                        "Правило вне горизонта", "Повторная корректировка", "Цель не достигнута", "Разовая операция вне горизонта"),
                List.of(WarningType.values()).stream().map(WarningType::title).toList());
    }

    /** Предупреждения о корректировках и правилах. */
    @Test
    void adjustmentAndRuleWarnings() {
        RecurringRule salary = monthly("r1", "Зарплата", Kind.INCOME, 1000, 5);
        assertEquals("Для события r1 от 05.10.2026 задано корректировок: 2; действует последняя",
                single(run(plan(0, YEAR, List.of(salary), List.of(), List.of(
                        adjust("r1", "2026-10-05", new Adjustment.Skip()), adjust("r1", "2026-10-05", new Adjustment.Skip())))),
                        WarningType.DUPLICATE_ADJUSTMENT));
        assertEquals("Правило r2 «Потом» не действует в пределах горизонта прогноза (01.09.2026 - 31.08.2027)",
                single(run(plan(0, rule("r2", "Потом", Kind.EXPENSE, Money.ofMajor(1), new Recurrence.Monthly(5, 1),
                        d("2030-01-01"), null, WeekendPolicy.NONE))), WarningType.RULE_OUTSIDE_HORIZON));
        assertEquals("Правило r3 не действует в пределах горизонта прогноза (01.09.2026 - 31.08.2027)",
                single(run(plan(0, rule("r3", "", Kind.EXPENSE, Money.ofMajor(1), new Recurrence.Monthly(5, 1),
                        d("2030-01-01"), null, WeekendPolicy.NONE))), WarningType.RULE_OUTSIDE_HORIZON));
        assertEquals("Событие r1 «Зарплата» от 05.10.2026 перенесено на 10.01.2028 - за пределы горизонта, в прогноз не попало",
                single(run(plan(0, YEAR, List.of(salary), List.of(),
                        List.of(adjust("r1", "2026-10-05", new Adjustment.MoveDate(d("2028-01-10")))))), WarningType.MOVED_OUT_OF_HORIZON));
        assertEquals("Корректировка события r9 от 05.10.2026 ни к чему не относится: правила r9 нет в плане",
                single(run(plan(0, YEAR, List.of(salary), List.of(), List.of(adjust("r9", "2026-10-05", new Adjustment.Skip())))),
                        WarningType.ORPHAN_ADJUSTMENT));
        assertEquals("Корректировка события r1 от 06.10.2026 ни к чему не относится: правило r1 «Зарплата» не создаёт событие в эту дату",
                single(run(plan(0, YEAR, List.of(salary), List.of(), List.of(adjust("r1", "2026-10-06", new Adjustment.Skip())))),
                        WarningType.ORPHAN_ADJUSTMENT));
        assertEquals("Разовая операция t1 «Позже» от 01.09.2027 вне горизонта прогноза и не учитывается",
                single(run(plan(0, YEAR, List.of(), List.of(oneTime("t1", "2027-09-01", "Позже", Kind.INCOME, 1)), List.of())),
                        WarningType.ONE_TIME_OUTSIDE_HORIZON));
    }

    /** Предупреждения о балансе и цели. */
    @Test
    void balanceAndGoalWarnings() {
        assertEquals("Баланс уходит в минус: " + Money.ofMajor(-100).format("₽"),
                single(run(plan(0, monthly("r1", "Аренда", Kind.EXPENSE, 100, 10))), WarningType.NEGATIVE_BALANCE));
        assertEquals("Баланс опускается ниже подушки безопасности (" + Money.ofMajor(500).format("₽") + "): "
                        + Money.ofMajor(400).format("₽"),
                single(run(plan(1000, monthly("r1", "Аренда", Kind.EXPENSE, 600, 10)).withCushion(Money.ofMajor(500))),
                        WarningType.BELOW_CUSHION));

        Plan base = plan(0, monthly("r1", "Доход", Kind.INCOME, 1000, 5));
        String house = Money.ofMajor(1_000_000).format("₽");
        assertEquals("Цель «Дом» (" + house + ") не достигается до конца прогноза (31.08.2027)",
                single(run(base.withGoal(new Goal("Дом", Money.ofMajor(1_000_000), null))), WarningType.GOAL_NOT_REACHED));
        assertEquals("Цель (" + house + ") не достигается до конца прогноза (31.08.2027)",
                single(run(base.withGoal(new Goal("", Money.ofMajor(1_000_000), null))), WarningType.GOAL_NOT_REACHED));
        assertEquals("Цель «Отпуск» (" + Money.ofMajor(2500).format("₽") + ") достигается только 05.11.2026, позже желаемой даты 01.10.2026",
                single(run(base.withGoal(new Goal("Отпуск", Money.ofMajor(2500), d("2026-10-01")))), WarningType.GOAL_NOT_REACHED));
    }

    /** Ошибки, которые пользователь видит как ошибку прогноза или проверки «что-если». */
    @Test
    void errors() {
        Plan tooLong = plan(0, new Horizon.Until(START.plusDays(ForecastEngine.MAX_DAYS)), List.of(), List.of(), List.of());
        assertEquals("Горизонт прогноза слишком длинный: 200001 дней (допустимо не больше 200000)",
                assertThrows(IllegalStateException.class, () -> run(tooLong)).getMessage());
        assertEquals("Коэффициент «что-если» не может быть отрицательным",
                assertThrows(IllegalArgumentException.class, () -> new WhatIf(BigDecimal.valueOf(-1), BigDecimal.ONE, Money.ZERO)).getMessage());
        assertEquals("Дополнительная экономия не может быть отрицательной",
                assertThrows(IllegalArgumentException.class, () -> new WhatIf(BigDecimal.ONE, BigDecimal.ONE, Money.ofMajor(-1))).getMessage());
    }

    /** @return текст единственного предупреждения заданного вида */
    private static String single(Forecast forecast, WarningType type) {
        List<Warning> found = warningsOf(forecast, type);
        assertEquals(1, found.size(), forecast.warnings().toString());
        return found.getFirst().message();
    }
}
