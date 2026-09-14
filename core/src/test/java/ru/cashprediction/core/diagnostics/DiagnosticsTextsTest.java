package ru.cashprediction.core.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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
 * Этап S0.5: тексты диагностики берутся из каталога ({@code diagnostics_ru.properties}) и совпадают с прежними
 * русскими строками кода буква в букву. Ожидания записаны строками намеренно: тест ловит и пропавший ключ,
 * и изменённую при переносе формулировку или пунктуацию.
 */
class DiagnosticsTextsTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    /** Подписи важности. */
    @Test
    void severityTitles() {
        assertEquals("Сведения", Severity.INFO.title());
        assertEquals("Предупреждение", Severity.WARNING.title());
        assertEquals("Ошибка", Severity.ERROR.title());
    }

    /** Строка списка диагностики с номером строки файла и без него. */
    @Test
    void diagnosticFormat() {
        assertEquals("Ошибка, строка 27: Некорректная сумма: «8о 000»",
                Diagnostic.error(27, "Некорректная сумма: «8о 000»").format());
        assertEquals("Предупреждение: текст", Diagnostic.warning("текст").toString());
        assertEquals("Сведения, строка 1234: {0} остаётся как есть", Diagnostic.info(1234, "{0} остаётся как есть").format(),
                "номер строки без разделителя разрядов, текст сообщения повторно не разбирается");
    }

    /** Сообщения о недопустимом имени плана. */
    @Test
    void planNameMessages() {
        assertEquals(Optional.of("Имя плана не может быть пустым"), PlanValidator.checkPlanName(" "));
        assertEquals(Optional.of("Имя плана длиннее 80 символов"), PlanValidator.checkPlanName("я".repeat(81)));
        assertEquals(Optional.of("Имя плана не может содержать символы \\ / : * ? \" < > |"), PlanValidator.checkPlanName("a/b"));
        assertEquals(Optional.of("Имя плана не может содержать управляющие символы"), PlanValidator.checkPlanName("ab"));
        assertEquals(Optional.of("Имя плана не может заканчиваться точкой"), PlanValidator.checkPlanName("plan."));
        assertEquals(Optional.of("Имя «Settings» зарезервировано программой для служебного файла"),
                PlanValidator.checkPlanName(" Settings "));
        assertEquals(Optional.of("Имя «con» зарезервировано Windows"), PlanValidator.checkPlanName("con"));
    }

    /** Сообщения о плане целиком: подушка, цель, горизонт, число строк. */
    @Test
    void planLevelMessages() {
        List<String> messages = messages(base(List.of()).withCushion(Money.ofMajor(-1)).withGoal(new Goal("Ничего", Money.ZERO, null)));
        assertTrue(messages.contains("Подушка безопасности не может быть отрицательной"), messages.toString());
        assertTrue(messages.contains("Сумма цели должна быть больше нуля"), messages.toString());

        assertEquals(List.of("Дата окончания горизонта (01.01.2026) раньше даты начала плана (01.09.2026)"),
                messages(base(List.of()).withHorizon(new Horizon.Until(LocalDate.of(2026, 1, 1)))));
        assertEquals(List.of("Горизонт прогноза длиннее 20 лет (300 мес.): на такой срок прогноз малоточен, а график строится медленнее"),
                messages(base(List.of()).withHorizon(new Horizon.Months(300))));
        assertEquals(List.of("Горизонт прогноза длиннее 50 лет (600 месяцев)"),
                messages(base(List.of()).withHorizon(new Horizon.Until(LocalDate.of(2090, 1, 1)))));

        Plan huge = base(List.of(rule("r1", "Каждый день", Money.ofMajor(1), new Recurrence.EveryNDays(1), START, null)))
                .withHorizon(new Horizon.Until(START.plusYears(600)));
        assertTrue(messages(huge).contains("План даёт около " + PlanValidator.estimateRowCount(huge)
                + " строк прогноза (допустимо не больше 200 000): сократите горизонт или период частых операций"), messages(huge).toString());
    }

    /** Сообщения о правилах, разовых операциях и корректировках: «о чём речь: что не так». */
    @Test
    void itemMessages() {
        Money tooMuch = Money.ofMinor(100_000_000_000_000L);
        Plan p = base(List.of(
                rule("r1", "Ноль", Money.ZERO, new Recurrence.Monthly(5, 1), null, null),
                rule("r2", "", Money.ofMajor(1), new Recurrence.Monthly(5, 1), null, null),
                rule("r3", "Много", tooMuch, new Recurrence.Monthly(5, 1), null, null),
                rule("r4", "Наоборот", Money.ofMajor(1), new Recurrence.Monthly(5, 1), LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 1)),
                rule("r5", "Потом", Money.ofMajor(1), new Recurrence.Monthly(5, 1), LocalDate.of(2030, 1, 1), null),
                rule("r6", "Кофе", Money.ofMajor(1), new Recurrence.EveryNDays(3), null, null),
                rule("r1", "Дубль", Money.ofMajor(1), new Recurrence.Monthly(5, 1), null, null)))
                .withOneTimes(List.of(
                        new OneTimeTransaction(new TxId("t1"), START, "Разовая", Kind.INCOME, Money.ZERO, "", ""),
                        new OneTimeTransaction(new TxId("t2"), START, "", Kind.INCOME, Money.ofMajor(1), "", ""),
                        new OneTimeTransaction(new TxId("t1"), START, "Ещё", Kind.INCOME, Money.ofMajor(1), "", "")))
                .withAdjustments(List.of(
                        new Adjustment(new OccurrenceKey(new RuleId("r1"), LocalDate.of(2026, 10, 5)), new Adjustment.ChangeAmount(Money.ZERO), ""),
                        new Adjustment(new OccurrenceKey(new RuleId("r1"), LocalDate.of(2026, 11, 5)),
                                new Adjustment.Replace(tooMuch, LocalDate.of(2026, 11, 6)), "")));
        List<String> messages = messages(p);
        String max = PlanValidator.MAX_AMOUNT.format();
        for (String expected : List.of(
                "Правило r1 «Ноль»: сумма должна быть больше нуля",
                "Правило r2: не указано название",
                "Правило r3 «Много»: сумма больше " + max,
                "Правило r4 «Наоборот»: дата окончания (01.12.2026) раньше даты начала (01.01.2027)",
                "Правило r5 «Потом»: не действует в пределах горизонта прогноза (01.09.2026 - 31.08.2027)",
                "Правило r6 «Кофе»: дата «С» не задана, отсчёт от даты начала плана (01.09.2026)",
                "Правило r1 «Дубль»: идентификатор r1 повторяется",
                "Разовая операция t1 «Разовая»: сумма должна быть больше нуля",
                "Разовая операция t2: не указано название",
                "Разовая операция t1 «Ещё»: идентификатор t1 повторяется",
                "Корректировка r1 от 05.10.2026: новая сумма должна быть больше нуля",
                "Корректировка r1 от 05.11.2026: новая сумма больше " + max)) {
            assertTrue(messages.contains(expected), expected + " не найдено в " + messages);
        }
    }

    private static List<String> messages(Plan plan) {
        return PlanValidator.validate(plan).stream().map(Diagnostic::message).toList();
    }

    private static Plan base(List<RecurringRule> rules) {
        return new Plan("Семейный бюджет 2026", "", "₽", START, Money.ofMajor(150_000), new Horizon.Months(12), Money.ZERO, null,
                rules, List.of(), List.of(), List.of());
    }

    private static RecurringRule rule(String id, String title, Money amount, Recurrence recurrence, LocalDate from, LocalDate until) {
        return new RecurringRule(new RuleId(id), title, Kind.EXPENSE, amount, "", recurrence, from, until, WeekendPolicy.NONE, true, "");
    }
}
