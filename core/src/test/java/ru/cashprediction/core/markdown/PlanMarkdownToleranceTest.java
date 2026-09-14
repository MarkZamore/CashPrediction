package ru.cashprediction.core.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.diagnostics.Severity;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RawBlock;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.WeekendPolicy;

/**
 * Терпимость читателя к ручной правке файла в Блокноте: ничего понятного не отвергается,
 * ничего непонятного не теряется, обо всём сообщается с номером строки.
 */
class PlanMarkdownToleranceTest {

    private static final LocalDate TODAY = PlanSamples.TODAY;
    private static final String SAMPLE = PlanSamples.FAMILY_BUDGET;

    /** Короткий заголовок с параметрами, к которому тесты дописывают свои таблицы. */
    private static final String HEAD = """
            # План: Тест

            ## Параметры

            - Валюта: ₽
            - Начало: 2026-09-01
            - Горизонт: 12 месяцев
            - Начальный баланс: 0

            """;

    @Test
    void extraSpacesCaseAndYo() {
        String text = """
                #   план :   Пробелы\s\s
                ##  параметры:
                -   валюта :  $
                *  НАЧАЛО: 01.09.2026
                - горизонт:   2   года
                - начальный БАЛАНС :  -1 500,50
                ##   регулярные ОПЕРАЦИИ\s\s
                |  ID|Название|  ТИП |Сумма|Повтор   | Активна | ВЫХОДНЫЕ |
                |:--|--|--|--:|---|---|---|
                |r1|Кофе|  РАСХОД  |  300 |  КАЖДЫЕ   3   ДНЯ | ДА | Раньше |
                | r2 | Отпускные | Доход | 10 000 | ёжегодно 15.07 | yes | ПОЗЖЕ |
                | r3 | Обед | расход | 500 | каждые 2 нЕдели ЧЕТВЕРГ | 1 | нет |
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(), result.diagnostics());
        Plan plan = result.plan();
        assertEquals("Пробелы", plan.name());
        assertEquals("$", plan.currency());
        assertEquals(LocalDate.of(2026, 9, 1), plan.startDate());
        assertEquals(new Horizon.Years(2), plan.horizon());
        assertEquals(Money.ofMinor(-150_050), plan.startBalance());
        assertEquals(3, plan.rules().size());
        RecurringRule coffee = plan.rules().get(0);
        assertEquals(new Recurrence.EveryNDays(3), coffee.recurrence());
        assertEquals(WeekendPolicy.PREVIOUS_BUSINESS_DAY, coffee.weekendPolicy());
        assertEquals(Kind.EXPENSE, coffee.kind());
        assertEquals(new Recurrence.Yearly(MonthDay.of(7, 15)), plan.rules().get(1).recurrence());
        assertEquals(Kind.INCOME, plan.rules().get(1).kind());
        assertEquals(new Recurrence.Weekly(DayOfWeek.THURSDAY, 2), plan.rules().get(2).recurrence());
    }

    /**
     * Синонимы, сокращения и однокоренные слова грамматики формата, которые писатель не выводит, но чтение понимает
     * (format.properties: *.alias, *.abbr, *.stem): план, записанный вручную, читается так же, как до этапа S0.5.
     */
    @Test
    void grammarAliasesAbbreviationsAndStems() {
        String text = HEAD + """
                ## Регулярные операции

                | ID | Название | Тип | Сумма | Повтор | Выходные |
                |----|----------|-----|-------|--------|----------|
                | r1 | Спорт | расход | 500 | каждые 5 нед. чт | не сдвигать |
                | r2 | Кофе | расход | 100 | каждые 7 дн. | на понедельник |
                | r3 | Рынок | расход | 900 | еженедельно вс | На пятницу |

                ## Корректировки

                | Правило | Исходная дата | Действие | Новая сумма | Новая дата |
                |---------|---------------|----------|-------------|------------|
                | r1 | 2026-09-03 | изменение | 700 | |
                | r3 | 2026-09-06 | Перенос | | 2026-09-07 |
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(), result.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).toList());
        Plan plan = result.plan();
        assertEquals(3, plan.rules().size(), plan.rules().toString());
        assertEquals(new Recurrence.Weekly(DayOfWeek.THURSDAY, 5), plan.rules().get(0).recurrence());
        assertEquals(WeekendPolicy.NONE, plan.rules().get(0).weekendPolicy());
        assertEquals(new Recurrence.EveryNDays(7), plan.rules().get(1).recurrence());
        assertEquals(WeekendPolicy.NEXT_BUSINESS_DAY, plan.rules().get(1).weekendPolicy());
        assertEquals(new Recurrence.Weekly(DayOfWeek.SUNDAY, 1), plan.rules().get(2).recurrence());
        assertEquals(WeekendPolicy.PREVIOUS_BUSINESS_DAY, plan.rules().get(2).weekendPolicy());
        assertEquals(2, plan.adjustments().size(), plan.adjustments().toString());
        assertEquals(new ru.cashprediction.core.model.Adjustment.ChangeAmount(Money.ofMajor(700)),
                plan.adjustments().get(0).action());
        assertEquals(new ru.cashprediction.core.model.Adjustment.MoveDate(LocalDate.of(2026, 9, 7)),
                plan.adjustments().get(1).action());
    }

    @Test
    void swappedColumnOrderAndExtraColumn() {
        String text = HEAD + """
                ## Регулярные операции

                | Сумма | Тип | Повтор | Лишняя | Название | ID |
                |---|---|---|---|---|---|
                | 1 000 | расход | еженедельно пт | что-то | Обед | r1 |
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        int headerLine = PlanSamples.lineOf(text, "| Сумма |");
        assertEquals(List.of(Diagnostic.warning(headerLine,
                "Колонка «Лишняя» в секции «Регулярные операции» не используется программой и не будет сохранена")),
                result.diagnostics());
        RecurringRule rule = result.plan().rules().get(0);
        assertEquals("r1", rule.id().value());
        assertEquals("Обед", rule.title());
        assertEquals(Money.ofMajor(1_000), rule.amount());
        assertEquals(new Recurrence.Weekly(DayOfWeek.FRIDAY, 1), rule.recurrence());
        String written = PlanMarkdownWriter.write(result.plan());
        assertTrue(written.contains("| r1 | Обед     | расход | 1 000,00 |"), written);
        assertFalse(written.contains("Лишняя"));
    }

    @Test
    void missingOptionalColumnsGetDefaultsAndIds() {
        String text = HEAD + """
                ## Регулярные операции

                | Название | Тип | Сумма | Повтор |
                |---|---|---|---|
                | Зарплата | доход | 80 000 | ежемесячно 5 |
                | Аренда | расход | 45 000 | ежемесячно 1 |
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertFalse(result.hasWarnings());
        assertEquals(2, result.diagnostics().size());
        assertTrue(result.diagnostics().stream().allMatch(d -> d.severity() == Severity.INFO));
        assertEquals(PlanSamples.lineOf(text, "| Зарплата"), result.diagnostics().get(0).line());
        List<RecurringRule> rules = result.plan().rules();
        assertEquals(List.of("r1", "r2"), rules.stream().map(r -> r.id().value()).toList());
        assertTrue(rules.get(0).enabled());
        assertEquals(WeekendPolicy.NONE, rules.get(0).weekendPolicy());
        assertNull(rules.get(0).from());
        assertEquals("", rules.get(0).category());
    }

    @Test
    void russianDatesAndSpecialSpacesInAmounts() {
        String text = """
                # План: Даты

                ## Параметры

                - Валюта: ₽
                - Начало: 01.09.2026
                - Горизонт: до 31.12.2027
                - Начальный баланс: 150 000,00

                ## Регулярные операции

                | ID | Тип | Сумма | Повтор | С | По |
                |---|---|---|---|---|---|
                | r1 | доход | 80 000,00 | ежемесячно 5 | 05.10.2026 | - |

                ## Разовые операции

                | ID | Дата | Название | Тип | Сумма |
                |---|---|---|---|---|
                | t1 | 20.12.2026 | Премия | доход | 60 000,5 |
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(), result.diagnostics());
        Plan plan = result.plan();
        assertEquals(LocalDate.of(2026, 9, 1), plan.startDate());
        assertEquals(new Horizon.Until(LocalDate.of(2027, 12, 31)), plan.horizon());
        assertEquals(Money.ofMajor(150_000), plan.startBalance());
        assertEquals(Money.ofMajor(80_000), plan.rules().get(0).amount());
        assertEquals(LocalDate.of(2026, 10, 5), plan.rules().get(0).from());
        assertNull(plan.rules().get(0).until());
        assertEquals(LocalDate.of(2026, 12, 20), plan.oneTimes().get(0).date());
        assertEquals(Money.ofMinor(6_000_050), plan.oneTimes().get(0).amount());
        String written = PlanMarkdownWriter.write(plan);
        assertTrue(written.contains("- Начало: 2026-09-01"), "писатель всегда пишет ISO");
        assertTrue(written.contains("| 2026-12-20 |"));
    }

    @Test
    void signedAmountsAreTakenAbsoluteWithWarning() {
        String text = HEAD + """
                ## Разовые операции

                | ID | Дата | Название | Тип | Сумма |
                |---|---|---|---|---|
                | t1 | 2026-10-01 | Ноутбук | расход | -90 000 |
                | t2 | 2026-10-02 | Премия | доход | +5 000 |
                | t3 | 2026-10-03 | Кино | расход | −700 |
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(3, result.diagnostics().size());
        assertTrue(result.diagnostics().stream().allMatch(d -> d.severity() == Severity.WARNING));
        assertEquals(PlanSamples.lineOf(text, "| t1"), result.diagnostics().get(0).line());
        assertEquals(PlanSamples.lineOf(text, "| t3"), result.diagnostics().get(2).line());
        assertEquals(List.of(Money.ofMajor(90_000), Money.ofMajor(5_000), Money.ofMajor(700)),
                result.plan().oneTimes().stream().map(t -> t.amount()).toList());
        assertFalse(result.hasErrors());
    }

    @Test
    void bomAndCrlfAreAccepted() {
        String text = "﻿" + SAMPLE.replace("\n", "\r\n");
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(), result.diagnostics());
        assertEquals(PlanMarkdownReader.read(SAMPLE, "x", TODAY).plan(), result.plan());
        assertEquals(SAMPLE, PlanMarkdownWriter.write(result.plan()));
    }

    @Test
    void unknownSectionIsPreservedInPlace() {
        String text = SAMPLE.replace("\n## Разовые операции", "\n## Мои заметки\n\nкупить хлеб\n- и молоко\n\n## Разовые операции");
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(Diagnostic.warning(PlanSamples.lineOf(text, "## Мои заметки"),
                "Неизвестная секция «Мои заметки» сохранена без изменений")), result.diagnostics());
        assertEquals(List.of(new RawBlock(MarkdownFormat.SECTION_RULES, List.of("## Мои заметки", "", "купить хлеб", "- и молоко"))),
                result.plan().rawBlocks());
        assertEquals(text, PlanMarkdownWriter.write(result.plan()), "секция на прежнем месте, остальное без изменений");
    }

    @Test
    void unknownSectionBeforeKnownSectionsAndAtTheEnd() {
        String text = SAMPLE.replace("\n## Параметры", "\n## Вступление\n\nтекст\n\n## Параметры") + "\n## Хвост\n\nпоследний текст\n";
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(2, result.diagnostics().size());
        assertEquals(text, PlanMarkdownWriter.write(result.plan()));
    }

    @Test
    void unknownSectionSurvivesEvenWhenItsAnchorSectionIsEmpty() {
        String text = HEAD + """
                ## Заметка

                ## Своё

                держать здесь
                """;
        Plan plan = PlanMarkdownReader.read(text, "x", TODAY).plan();
        String written = PlanMarkdownWriter.write(plan);
        assertTrue(written.contains("\n## Своё\n\nдержать здесь\n\n## Регулярные операции"), written);
    }

    @Test
    void unknownParameterIsPreservedInsideList() {
        String text = SAMPLE.replace("- Название цели: Отпуск\n", "- Название цели: Отпуск\n- Банк: Надёжный\n");
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(Diagnostic.warning(PlanSamples.lineOf(text, "- Банк"), "Неизвестный параметр «Банк» сохранён без изменений")),
                result.diagnostics());
        assertEquals(text, PlanMarkdownWriter.write(result.plan()));
    }

    @Test
    void unknownParameterInTheMiddleMovesToEndOfList() {
        String text = SAMPLE.replace("- Валюта: ₽\n", "- Банк: Надёжный\n- Валюта: ₽\n");
        String written = PlanMarkdownWriter.write(PlanMarkdownReader.read(text, "x", TODAY).plan());
        assertEquals(SAMPLE.replace("- Название цели: Отпуск\n", "- Название цели: Отпуск\n- Банк: Надёжный\n"), written);
    }

    @Test
    void brokenAmountRowGoesToNoteWithError() {
        String broken = "| r2 | Аванс          | доход  | 40 ООО,00 | Зарплата  | ежемесячно 20      |            |            | раньше   | да      |                     |";
        String text = SAMPLE.replace("| r2 | Аванс          | доход  | 40 000,00 |", "| r2 | Аванс          | доход  | 40 ООО,00 |");
        int line = PlanSamples.lineOf(text, "| r2 |");
        assertEquals(24, line, "номер строки r2 в эталоне");

        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertTrue(result.hasErrors());
        assertEquals(1, result.diagnostics().size());
        Diagnostic error = result.diagnostics().get(0);
        assertEquals(Severity.ERROR, error.severity());
        assertEquals(line, error.line());
        assertTrue(error.message().contains("40 ООО,00"), error.message());
        assertTrue(error.message().contains("Сумма"), error.message());

        Plan plan = result.plan();
        assertEquals(7, plan.rules().size());
        assertTrue(plan.findRule(new ru.cashprediction.core.model.RuleId("r2")).isEmpty());
        assertEquals("Основной сценарий. Аренда заканчивается в августе 2027, поэтому у r3 стоит дата окончания.\n"
                + "(не разобрано, строка 24: " + broken + ")", plan.note());

        // После сохранения строка живёт в заметке и при повторном чтении ошибок уже не даёт.
        ReadResult again = PlanMarkdownReader.read(PlanMarkdownWriter.write(plan), "x", TODAY);
        assertEquals(List.of(), again.diagnostics());
        assertEquals(plan, again.plan());
    }

    @Test
    void brokenCellsOfAllTablesAreReported() {
        String text = HEAD + """
                ## Регулярные операции

                | ID | Тип | Сумма | Повтор |
                |---|---|---|---|
                | r1 | доход | 100 | раз в месяц |
                | r2 | прибыль | 100 | ежемесячно 5 |

                ## Разовые операции

                | ID | Дата | Тип | Сумма |
                |---|---|---|---|
                | t1 | 2026-02-30 | доход | 1 |

                ## Корректировки

                | Правило | Исходная дата | Действие | Новая сумма | Новая дата |
                |---|---|---|---|---|
                | r1 | 2026-10-05 | изменить |  |  |
                | r1 | 2026-10-05 | съесть | 1 |  |
                | r1 | 2026-11-05 | перенести | 100 | 2026-11-06 |
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        List<Integer> errorLines = result.diagnostics().stream().filter(d -> d.severity() == Severity.ERROR).map(Diagnostic::line).toList();
        assertEquals(List.of(PlanSamples.lineOf(text, "| r1 | доход"), PlanSamples.lineOf(text, "| r2 |"),
                PlanSamples.lineOf(text, "| t1 |"), PlanSamples.lineOf(text, "| r1 | 2026-10-05 | изменить"),
                PlanSamples.lineOf(text, "| r1 | 2026-10-05 | съесть")), errorLines);
        assertTrue(result.plan().rules().isEmpty());
        assertTrue(result.plan().oneTimes().isEmpty());
        assertEquals(1, result.plan().adjustments().size(), "перенос с лишней суммой разобран");
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.severity() == Severity.WARNING
                && d.message().contains("Новая сумма")), "лишняя сумма - предупреждение");
        assertEquals(5, result.plan().note().lines().count());
    }

    @Test
    void duplicateAndInvalidIdsAreReplaced() {
        String text = HEAD + """
                ## Регулярные операции

                | ID | Название | Тип | Сумма | Повтор |
                |---|---|---|---|---|
                | r1 | А | доход | 1 | ежемесячно 1 |
                | r1 | Б | доход | 1 | ежемесячно 2 |
                | r2 | В | доход | 1 | ежемесячно 3 |
                | a@b | Г | доход | 1 | ежемесячно 4 |
                |  | Д | доход | 1 | ежемесячно 5 |

                ## Разовые операции

                | ID | Дата | Тип | Сумма |
                |---|---|---|---|
                | t1 | 2026-10-01 | доход | 1 |
                | t1 | 2026-10-02 | доход | 1 |
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of("r1", "r3", "r2", "r4", "r5"), result.plan().rules().stream().map(r -> r.id().value()).toList());
        assertEquals(List.of("t1", "t2"), result.plan().oneTimes().stream().map(t -> t.id().value()).toList());
        List<Diagnostic> d = result.diagnostics();
        assertEquals(List.of(Severity.WARNING, Severity.WARNING, Severity.INFO, Severity.WARNING),
                d.stream().map(Diagnostic::severity).toList());
        assertEquals(PlanSamples.lineOf(text, "| r1 | Б"), d.get(0).line());
        assertTrue(d.get(0).message().contains("«r3»"), d.get(0).message());
        assertEquals(PlanSamples.lineOf(text, "|  | Д"), d.get(2).line());
    }

    @Test
    void missingTitleUsesFallbackName() {
        String text = SAMPLE.substring(SAMPLE.indexOf("## Параметры"));
        ReadResult result = PlanMarkdownReader.read(text, "Имя файла", TODAY);
        assertEquals("Имя файла", result.plan().name());
        assertEquals(1, result.diagnostics().size());
        assertEquals(Severity.INFO, result.diagnostics().get(0).severity());
        assertEquals(SAMPLE.replace("# План: Семейный бюджет 2026", "# План: Имя файла"),
                PlanMarkdownWriter.write(result.plan()));
    }

    @Test
    void emptyTitleUsesFallbackName() {
        ReadResult result = PlanMarkdownReader.read("# План:\n\n## Регулярные операции\n", "Запасное", TODAY);
        assertEquals("Запасное", result.plan().name());
    }

    @Test
    void notAPlanThrows() {
        assertThrows(MarkdownParseException.class,
                () -> PlanMarkdownReader.read("# Настройки CashPrediction\n\n- Вид: таблица\n", "settings", TODAY));
        assertThrows(MarkdownParseException.class, () -> PlanMarkdownReader.read("Просто текст\n## Список покупок\n", "x", TODAY));
        MarkdownParseException e = assertThrows(MarkdownParseException.class, () -> PlanMarkdownReader.read("", "x", TODAY));
        assertTrue(e.getMessage().contains("План"), e.getMessage());
    }

    @Test
    void titleOnlyIsAPlanWithDefaults() {
        ReadResult result = PlanMarkdownReader.read("# План: Только заголовок\n", "x", TODAY);
        Plan plan = result.plan();
        assertEquals("₽", plan.currency());
        assertEquals(TODAY, plan.startDate());
        assertEquals(new Horizon.Months(12), plan.horizon());
        assertEquals(Money.ZERO, plan.startBalance());
        assertEquals(4, result.diagnostics().size());
        assertTrue(result.diagnostics().stream().allMatch(d -> d.severity() == Severity.WARNING));
    }

    @Test
    void invalidRequiredParameterGoesToNoteAndDefaultIsUsed() {
        String text = SAMPLE.replace("- Горизонт: 12 месяцев", "- Горизонт: вечно");
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(new Horizon.Months(12), result.plan().horizon());
        assertEquals(1, result.diagnostics().size());
        assertEquals(Severity.ERROR, result.diagnostics().get(0).severity());
        assertEquals(PlanSamples.lineOf(text, "- Горизонт"), result.diagnostics().get(0).line());
        assertTrue(result.plan().note().endsWith("(не разобрано, строка 8: - Горизонт: вечно)"), result.plan().note());
    }

    @Test
    void newerFormatVersionIsReported() {
        String text = SAMPLE.replace("- Формат: CashPrediction 1", "- Формат: CashPrediction 2");
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(1, result.diagnostics().size());
        assertEquals(Severity.WARNING, result.diagnostics().get(0).severity());
        assertEquals(5, result.diagnostics().get(0).line());
        assertEquals(SAMPLE, PlanMarkdownWriter.write(result.plan()));
    }

    @Test
    void duplicateKnownSectionIsMerged() {
        String text = SAMPLE + """

                ## Регулярные операции

                | ID | Тип | Сумма | Повтор |
                |---|---|---|---|
                | r9 | расход | 1 | ежедневно |

                ## заметка:

                Вторая часть заметки.
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(2, result.diagnostics().size());
        assertTrue(result.diagnostics().stream().allMatch(d -> d.severity() == Severity.WARNING));
        assertEquals(9, result.plan().rules().size());
        assertEquals("r9", result.plan().rules().get(8).id().value());
        assertTrue(result.plan().note().endsWith("окончания.\n\nВторая часть заметки."), result.plan().note());
    }

    @Test
    void adjustmentForUnknownRuleIsKept() {
        String text = SAMPLE.replace("| r4      | 2026-10-03    |", "| r99     | 2026-10-03    |");
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(), result.diagnostics());
        assertEquals("r99", result.plan().adjustments().get(2).key().ruleId().value());
        assertEquals(text, PlanMarkdownWriter.write(result.plan()));
    }

    @Test
    void textInsideTableSectionIsPreserved() {
        String text = HEAD + """
                ## Регулярные операции

                | ID | Название | Тип | Сумма | Категория | Повтор | С | По | Выходные | Активна | Заметка |
                |----|----------|-----|-------|-----------|--------|---|----|----------|---------|---------|

                Пояснение под таблицей.
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(1, result.diagnostics().size());
        assertEquals(Severity.WARNING, result.diagnostics().get(0).severity());
        String written = PlanMarkdownWriter.write(result.plan());
        assertTrue(written.endsWith("|---------|\n\nПояснение под таблицей.\n"), written);
    }

    @Test
    void rowsWithoutHeaderAreNotLost() {
        String text = HEAD + """
                ## Разовые операции

                | t1 | 2026-10-01 | Премия | доход | 1 000 |
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertTrue(result.hasErrors());
        assertTrue(result.plan().note().contains("| t1 | 2026-10-01 | Премия | доход | 1 000 |"), result.plan().note());
    }

    @Test
    void blankLineInsideTableKeepsHeader() {
        String text = HEAD + """
                ## Разовые операции

                | ID | Дата | Тип | Сумма |
                |---|---|---|---|
                | t1 | 2026-10-01 | доход | 1 |

                | t2 | 2026-10-02 | доход | 2 |
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(), result.diagnostics());
        assertEquals(2, result.plan().oneTimes().size());
    }

    @Test
    void goalDetailsWithoutGoalArePreserved() {
        String text = HEAD.replace("- Начальный баланс: 0\n", "- Начальный баланс: 0\n- Цель к дате: 2027-01-01\n- Название цели: Машина\n")
                + "## Регулярные операции\n";
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertNull(result.plan().goal());
        assertEquals(2, result.diagnostics().size());
        String written = PlanMarkdownWriter.write(result.plan());
        assertTrue(written.contains("- Начальный баланс: 0,00\n- Цель к дате: 2027-01-01\n- Название цели: Машина\n"), written);
    }

    @Test
    void escapedNoteLinesAreUnescaped() {
        String text = HEAD + """
                ## Заметка

                \\## не секция
                обычная строка
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(), result.diagnostics());
        assertEquals("## не секция\nобычная строка", result.plan().note());
    }

    @Test
    void textBeforeFirstSectionIsPreserved() {
        String text = SAMPLE.replace("# План: Семейный бюджет 2026\n", "# План: Семейный бюджет 2026\n\nВводный абзац.\n");
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(1, result.diagnostics().size());
        assertEquals(text, PlanMarkdownWriter.write(result.plan()));
    }

    @Test
    void amountRoundingIsReported() {
        String text = HEAD + """
                ## Разовые операции

                | Дата | Тип | Сумма |
                |---|---|---|
                | 2026-10-01 | доход | 10,005 |
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(Money.ofMinor(1_001), result.plan().oneTimes().get(0).amount());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.severity() == Severity.WARNING && d.message().contains("округлена")));
    }

    /** Регрессия: разделитель, скопированный в середину таблицы, не превращает строку данных над ним в заголовок. */
    @Test
    void separatorCopiedIntoMiddleOfTableKeepsAllRows() {
        String[] lines = SAMPLE.split("\n", -1);
        // lineOf возвращает номер строки с единицы, поэтому lines[номер заголовка] — следующая за ним строка-разделитель.
        String separator = lines[PlanSamples.lineOf(SAMPLE, "| ID | Название ")];
        String r2 = lines[PlanSamples.lineOf(SAMPLE, "| r2 ") - 1];
        String text = SAMPLE.replace(r2 + "\n", r2 + "\n" + separator + "\n");
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(), result.diagnostics());
        assertEquals(List.of("r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8"),
                result.plan().rules().stream().map(r -> r.id().value()).toList());
        assertEquals(SAMPLE, PlanMarkdownWriter.write(result.plan()));
    }

    /** Регрессия: известная секция с лишним или недостающим «#» распознаётся с предупреждением, а не уходит в заметку. */
    @Test
    void knownSectionWithWrongHeadingLevelIsRecognized() {
        String text = SAMPLE.replace("## Регулярные операции", "### Регулярные операции")
                .replace("## Корректировки", "# Корректировки");
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(8, result.plan().rules().size());
        assertEquals(4, result.plan().adjustments().size());
        assertEquals(List.of(PlanSamples.lineOf(text, "### Регулярные операции"), PlanSamples.lineOf(text, "# Корректировки")),
                result.diagnostics().stream().map(Diagnostic::line).toList());
        assertTrue(result.diagnostics().stream().allMatch(d -> d.severity() == Severity.WARNING), result.diagnostics().toString());
        assertTrue(result.diagnostics().get(0).message().contains("«###» вместо «##»"), result.diagnostics().get(0).message());
        assertEquals(SAMPLE, PlanMarkdownWriter.write(result.plan()));
    }

    /** Регрессия: текст под пустой секцией сохраняет заголовок секции и после записи читается в тот же план. */
    @Test
    void textUnderEmptySectionKeepsItsHeading() {
        String text = HEAD + """
                ## Регулярные операции

                | ID | Тип | Сумма | Повтор |
                |---|---|---|---|
                | r1 | доход | 1 | ежемесячно 5 |

                ## Разовые операции

                пока пусто

                ## Корректировки

                ## Идеи

                идея 1
                """;
        Plan first = PlanMarkdownReader.read(text, "x", TODAY).plan();
        String written = PlanMarkdownWriter.write(first);
        assertTrue(written.endsWith("|\n\n## Разовые операции\n\nпока пусто\n\n## Корректировки\n\n## Идеи\n\nидея 1\n"), written);
        Plan second = PlanMarkdownReader.read(written, "x", TODAY).plan();
        assertEquals(first, second);
        assertEquals(written, PlanMarkdownWriter.write(second));
    }

    /** Регрессия: неизвестная секция над заголовком плана не лишает план имени и не удваивает заголовок. */
    @Test
    void titleAfterUnknownSectionIsRecognized() {
        String text = "## Мои\n\nтекст\n\n" + SAMPLE;
        ReadResult result = PlanMarkdownReader.read(text, "fallback", TODAY);
        assertEquals("Семейный бюджет 2026", result.plan().name());
        assertEquals(2, result.diagnostics().size(), result.diagnostics().toString());
        String written = PlanMarkdownWriter.write(result.plan());
        assertEquals("# План: Семейный бюджет 2026\n\n## Мои\n\nтекст\n\n" + SAMPLE.substring(SAMPLE.indexOf("## Параметры")), written);
        assertEquals(result.plan(), PlanMarkdownReader.read(written, "fallback", TODAY).plan());
    }

    /** Регрессия: повторная табличная секция продолжает таблицу первой без своего заголовка или со своим. */
    @Test
    void repeatedTableSectionWithoutHeaderContinuesTable() {
        String text = HEAD + """
                ## Разовые операции

                | ID | Дата | Название | Тип | Сумма | Категория | Заметка |
                |---|---|---|---|---|---|---|
                | t1 | 2026-12-20 | Премия | доход | 60 000 | Зарплата | |

                ## Разовые операции

                | t2 | 2027-01-10 | Ноутбук | расход | 90 000 | Техника | |

                ## Разовые операции

                | Дата | ID | Тип | Сумма |
                | 2027-02-01 | t3 | доход | 5 |
                """;
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertFalse(result.hasErrors(), result.diagnostics().toString());
        assertEquals(List.of("t1", "t2", "t3"), result.plan().oneTimes().stream().map(t -> t.id().value()).toList());
        assertEquals(Money.ofMajor(90_000), result.plan().oneTimes().get(1).amount());
        assertEquals(LocalDate.of(2027, 2, 1), result.plan().oneTimes().get(2).date());
        assertEquals("", result.plan().note());
    }

    /** Регрессия: полностью пустая строка таблицы пропускается без ошибки и не оседает в заметке. */
    @Test
    void emptyTableRowIsSkippedSilently() {
        String r8 = SAMPLE.split("\n", -1)[PlanSamples.lineOf(SAMPLE, "| r8 ") - 1];
        String text = SAMPLE.replace(r8 + "\n", r8 + "\n|  |  |  |  |  |  |  |  |  |  |  |\n");
        ReadResult result = PlanMarkdownReader.read(text, "x", TODAY);
        assertEquals(List.of(), result.diagnostics());
        assertEquals(8, result.plan().rules().size());
        assertEquals(SAMPLE, PlanMarkdownWriter.write(result.plan()));
    }
}
