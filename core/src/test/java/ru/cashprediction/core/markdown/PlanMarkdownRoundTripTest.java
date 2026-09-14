package ru.cashprediction.core.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.format.DashFreeOutput;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
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
 * Круговые тесты формата плана: эталонный файл читается и записывается байт в байт,
 * программно созданный план переживает запись и чтение без потерь, писатель опускает пустые части.
 */
class PlanMarkdownRoundTripTest {

    private static final LocalDate TODAY = PlanSamples.TODAY;

    @Test
    void sampleReadThenWriteIsByteIdentical() {
        ReadResult result = PlanMarkdownReader.read(PlanSamples.FAMILY_BUDGET, "не используется", TODAY);
        assertEquals(List.of(), result.diagnostics(), "эталон читается без замечаний");
        assertEquals(PlanSamples.FAMILY_BUDGET, PlanMarkdownWriter.write(result.plan()));
    }

    @Test
    void sampleParametersAndNote() {
        Plan plan = PlanMarkdownReader.read(PlanSamples.FAMILY_BUDGET, "x", TODAY).plan();
        assertEquals("Семейный бюджет 2026", plan.name());
        assertEquals("₽", plan.currency());
        assertEquals(LocalDate.of(2026, 9, 1), plan.startDate());
        assertEquals(new Horizon.Months(12), plan.horizon());
        assertEquals(Money.ofMajor(150_000), plan.startBalance());
        assertEquals(Money.ofMajor(50_000), plan.cushion());
        assertEquals(new Goal("Отпуск", Money.ofMajor(300_000), LocalDate.of(2027, 6, 1)), plan.goal());
        assertEquals("Основной сценарий. Аренда заканчивается в августе 2027, поэтому у r3 стоит дата окончания.", plan.note());
        assertTrue(plan.rawBlocks().isEmpty());
    }

    @Test
    void sampleRules() {
        Plan plan = PlanMarkdownReader.read(PlanSamples.FAMILY_BUDGET, "x", TODAY).plan();
        assertEquals(8, plan.rules().size());
        assertEquals(List.of("r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8"),
                plan.rules().stream().map(r -> r.id().value()).toList());

        RecurringRule r1 = plan.rules().get(0);
        assertEquals(new RecurringRule(new RuleId("r1"), "Зарплата", Kind.INCOME, Money.ofMajor(80_000), "Зарплата",
                new Recurrence.Monthly(5, 1), LocalDate.of(2026, 9, 1), null, WeekendPolicy.PREVIOUS_BUSINESS_DAY, true, ""), r1);

        RecurringRule r3 = plan.rules().get(2);
        assertEquals(LocalDate.of(2027, 8, 31), r3.until());
        assertEquals("до переезда", r3.note());
        assertEquals("Жильё", r3.category());
        assertEquals(new Recurrence.Weekly(DayOfWeek.SATURDAY, 1), plan.rules().get(3).recurrence());
        assertEquals(new Recurrence.Yearly(MonthDay.of(3, 15)), plan.rules().get(4).recurrence());
        assertEquals(WeekendPolicy.NEXT_BUSINESS_DAY, plan.rules().get(4).weekendPolicy());
        assertEquals(new Recurrence.Monthly(10, 2), plan.rules().get(5).recurrence());
        assertEquals(LocalDate.of(2026, 10, 1), plan.rules().get(5).from());

        RecurringRule r7 = plan.rules().get(6);
        assertFalse(r7.enabled(), "r7 отключено");
        assertEquals(new Recurrence.EveryNDays(3), r7.recurrence());
        assertEquals(Money.ofMajor(300), r7.amount());

        RecurringRule r8 = plan.rules().get(7);
        assertEquals(new Recurrence.Monthly(31, 1), r8.recurrence(), "«ежемесячно 31»");
        assertEquals(LocalDate.of(2027, 3, 31), r8.until());
        assertEquals(Money.ofMinor(1_234_567), r8.amount());
        assertEquals(Kind.EXPENSE, r8.kind());
        assertNull(r8.from());
    }

    @Test
    void sampleOneTimesAndAdjustments() {
        Plan plan = PlanMarkdownReader.read(PlanSamples.FAMILY_BUDGET, "x", TODAY).plan();
        assertEquals(List.of(
                new OneTimeTransaction(new TxId("t1"), LocalDate.of(2026, 12, 20), "Премия", Kind.INCOME, Money.ofMajor(60_000), "Зарплата", ""),
                new OneTimeTransaction(new TxId("t2"), LocalDate.of(2027, 1, 10), "Ноутбук", Kind.EXPENSE, Money.ofMajor(90_000), "Техника", "")),
                plan.oneTimes());

        List<Adjustment> adjustments = plan.adjustments();
        assertEquals(4, adjustments.size());
        assertEquals(new Adjustment(key("r1", 2026, 12, 5), new Adjustment.ChangeAmount(Money.ofMajor(95_000)), "годовой бонус"),
                adjustments.get(0));
        assertEquals(new Adjustment(key("r3", 2027, 1, 1), new Adjustment.MoveDate(LocalDate.of(2027, 1, 9)), "договорились с хозяином"),
                adjustments.get(1));
        assertInstanceOf(Adjustment.Skip.class, adjustments.get(2).action());
        assertEquals(key("r4", 2026, 10, 3), adjustments.get(2).key());
        assertEquals(new Adjustment.Replace(Money.ofMajor(70_000), LocalDate.of(2027, 3, 7)), adjustments.get(3).action());
    }

    @Test
    void programmaticPlanSurvivesWriteAndRead() {
        Plan plan = new Plan("Все виды повторов", "Строка 1\n\nСтрока 3 с отступом:\n    код", "$",
                LocalDate.of(2026, 1, 31), Money.ofMinor(-150_050), new Horizon.Years(3), Money.ZERO,
                new Goal("", Money.ofMajor(1_000_000), null),
                List.of(
                        rule("r1", new Recurrence.Monthly(29, 1), WeekendPolicy.NONE),
                        rule("r2", new Recurrence.Monthly(31, 12), WeekendPolicy.NEXT_BUSINESS_DAY),
                        rule("r3", new Recurrence.Weekly(DayOfWeek.MONDAY, 2), WeekendPolicy.NONE),
                        rule("r10", new Recurrence.Weekly(DayOfWeek.SUNDAY, 21), WeekendPolicy.PREVIOUS_BUSINESS_DAY),
                        rule("аренда", new Recurrence.EveryNDays(1), WeekendPolicy.NONE),
                        rule("r5", new Recurrence.EveryNDays(22), WeekendPolicy.NONE),
                        rule("r6", new Recurrence.Yearly(MonthDay.of(2, 29)), WeekendPolicy.NONE)),
                List.of(new OneTimeTransaction(new TxId("t1"), LocalDate.of(2026, 2, 28), "Возврат налога", Kind.INCOME,
                        Money.ofMinor(1), "", "первая копейка")),
                List.of(new Adjustment(key("r3", 2026, 2, 9), new Adjustment.Skip(), "")),
                List.of());
        String text = PlanMarkdownWriter.write(plan);
        ReadResult back = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(), back.diagnostics());
        assertEquals(plan, back.plan());
        assertEquals(text, PlanMarkdownWriter.write(back.plan()), "повторная запись стабильна");
    }

    /**
     * Решение 2026-09-14: файл плана (и web-session.plan.md, который пишет тот же писатель) со всеми секциями, видами
     * повторов, выходных, корректировок, горизонтов и своей секцией пишется только с дефисом-минусом и читается обратно.
     */
    @Test
    void writtenPlanHasNoDashesAndReadsBack() {
        Plan sample = PlanMarkdownReader.read(PlanSamples.FAMILY_BUDGET, "x", TODAY).plan();
        Plan withOwnSection = PlanMarkdownReader.read(PlanSamples.FAMILY_BUDGET + "\n## Мои заметки\n\nсвой текст\n",
                "x", TODAY).plan();
        for (Plan plan : List.of(sample, withOwnSection,
                sample.withHorizon(new Horizon.Until(LocalDate.of(2030, 12, 31))),
                sample.withHorizon(new Horizon.Years(3)).withNote("Строка 1\n## Не секция\nСтрока 3"))) {
            String text = PlanMarkdownWriter.write(plan);
            DashFreeOutput.assertNoDashes(plan.name(), text);
            ReadResult back = PlanMarkdownReader.read(text, "x", TODAY);
            assertEquals(plan, back.plan(), text);
            assertEquals(text, PlanMarkdownWriter.write(back.plan()));
        }
    }

    @Test
    void untilHorizonRoundTrips() {
        Plan plan = Plan.empty("До даты", LocalDate.of(2026, 9, 1)).withHorizon(new Horizon.Until(LocalDate.of(2030, 12, 31)));
        assertEquals(plan, PlanMarkdownReader.read(PlanMarkdownWriter.write(plan), "x", TODAY).plan());
    }

    @Test
    void writerOmitsEmptyOptionalParts() {
        Plan plan = Plan.empty("Пустой", LocalDate.of(2026, 9, 1));
        String expected = """
                # План: Пустой

                ## Параметры

                - Формат: CashPrediction 1
                - Валюта: ₽
                - Начало: 2026-09-01
                - Горизонт: 12 месяцев
                - Начальный баланс: 0,00

                ## Регулярные операции

                | ID | Название | Тип | Сумма | Категория | Повтор | С | По | Выходные | Активна | Заметка |
                |----|----------|-----|-------|-----------|--------|---|----|----------|---------|---------|
                """;
        assertEquals(expected, PlanMarkdownWriter.write(plan));
        ReadResult back = PlanMarkdownReader.read(expected, "x", TODAY);
        assertEquals(List.of(), back.diagnostics());
        assertEquals(plan, back.plan());
    }

    @Test
    void writerGoalWithoutDateAndTitle() {
        Plan plan = Plan.empty("Цель", LocalDate.of(2026, 9, 1))
                .withCushion(Money.ofMajor(10))
                .withGoal(new Goal("", Money.ofMajor(100_000), null));
        String text = PlanMarkdownWriter.write(plan);
        assertTrue(text.contains("- Подушка безопасности: 10,00\n- Цель: 100 000,00\n\n## Регулярные операции"), text);
        assertFalse(text.contains("Цель к дате"));
        assertFalse(text.contains("Название цели"));
        assertFalse(text.contains("## Заметка"));
        assertFalse(text.contains("## Разовые операции"));
        assertFalse(text.contains("## Корректировки"));
        assertEquals(plan, PlanMarkdownReader.read(text, "x", TODAY).plan());
    }

    @Test
    void writerOutputHasCanonicalWhitespace() {
        String text = PlanMarkdownWriter.write(PlanMarkdownReader.read(PlanSamples.FAMILY_BUDGET, "x", TODAY).plan());
        assertFalse(text.contains("\r"), "только LF");
        assertFalse(text.contains("\n\n\n"), "не больше одной пустой строки подряд");
        assertTrue(text.endsWith("|\n") && !text.endsWith("\n\n"), "ровно один перевод строки в конце");
        assertFalse(text.lines().anyMatch(l -> !l.equals(l.stripTrailing())), "нет пробелов в концах строк");
    }

    @Test
    void noteLineStartingWithHashesRoundTrips() {
        String note = "Первая строка\n## Не секция\n# Тоже не заголовок\n\\# уже с косой\n  ### с отступом";
        Plan plan = Plan.empty("Заметка", LocalDate.of(2026, 9, 1)).withNote(note);
        String text = PlanMarkdownWriter.write(plan);
        assertTrue(text.contains("\n\\## Не секция\n"), text);
        assertTrue(text.contains("\n\\# Тоже не заголовок\n"), text);
        assertTrue(text.contains("\n\\\\# уже с косой\n"), text);
        assertTrue(text.contains("\n  \\### с отступом\n"), text);
        ReadResult back = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(), back.diagnostics());
        assertEquals(note, back.plan().note());
        assertTrue(back.plan().rawBlocks().isEmpty());
    }

    @Test
    void pipeInsideCellsRoundTrips() {
        RecurringRule rule = new RecurringRule(new RuleId("r1"), "Кафе | ресторан", Kind.EXPENSE, Money.ofMajor(1_500),
                "Еда|Досуг", new Recurrence.Monthly(1, 1), null, null, WeekendPolicy.NONE, true, "путь C:\\Users\\ | конец");
        Plan plan = Plan.empty("Трубы", LocalDate.of(2026, 9, 1)).withRuleAdded(rule);
        String text = PlanMarkdownWriter.write(plan);
        assertTrue(text.contains("| Кафе \\| ресторан |"), text);
        ReadResult back = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(), back.diagnostics());
        assertEquals(rule, back.plan().rules().get(0));
    }

    @Test
    void lineBreaksInCellsAreFlattened() {
        RecurringRule rule = new RecurringRule(new RuleId("r1"), "Две\nстроки", Kind.EXPENSE, Money.ofMajor(1),
                "", new Recurrence.Monthly(1, 1), null, null, WeekendPolicy.NONE, true, "a\r\nb");
        Plan plan = Plan.empty("Переводы", LocalDate.of(2026, 9, 1)).withRuleAdded(rule);
        RecurringRule back = PlanMarkdownReader.read(PlanMarkdownWriter.write(plan), "x", TODAY).plan().rules().get(0);
        assertEquals("Две строки", back.title());
        assertEquals("a b", back.note());
    }

    private static OccurrenceKey key(String rule, int y, int m, int d) {
        return new OccurrenceKey(new RuleId(rule), LocalDate.of(y, m, d));
    }

    private static RecurringRule rule(String id, Recurrence recurrence, WeekendPolicy policy) {
        return new RecurringRule(new RuleId(id), "Операция " + id, Kind.EXPENSE, Money.ofMinor(12_345), "Категория",
                recurrence, recurrence.needsAnchor() ? LocalDate.of(2026, 2, 1) : null, null, policy, !id.equals("r5"), "");
    }
}
