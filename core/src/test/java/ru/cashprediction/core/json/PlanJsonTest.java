package ru.cashprediction.core.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.diagnostics.Diagnostic;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.ChartSeries;
import ru.cashprediction.core.forecast.DailyPoint;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.WarningType;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.markdown.ReadResult;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RawBlock;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.util.DateFormats;

/**
 * Тесты JSON-отображения для web-API: точный обратимый перевод плана, форма прогноза, вид, настройки,
 * диагностика и сообщения об ошибках.
 */
class PlanJsonTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);

    /** Эталонный файл плана из раздела 4.2 утверждённого плана проекта. */
    private static final String FAMILY_BUDGET = """
            # План: Семейный бюджет 2026

            ## Параметры

            - Формат: CashPrediction 1
            - Валюта: ₽
            - Начало: 2026-09-01
            - Горизонт: 12 месяцев
            - Начальный баланс: 150 000,00
            - Подушка безопасности: 50 000,00
            - Цель: 300 000,00
            - Цель к дате: 2027-06-01
            - Название цели: Отпуск

            ## Заметка

            Основной сценарий. Аренда заканчивается в августе 2027, поэтому у r3 стоит дата окончания.

            ## Регулярные операции

            | ID | Название       | Тип    | Сумма     | Категория | Повтор             | С          | По         | Выходные | Активна | Заметка             |
            |----|----------------|--------|-----------|-----------|--------------------|------------|------------|----------|---------|---------------------|
            | r1 | Зарплата       | доход  | 80 000,00 | Зарплата  | ежемесячно 5       | 2026-09-01 |            | раньше   | да      |                     |
            | r2 | Аванс          | доход  | 40 000,00 | Зарплата  | ежемесячно 20      |            |            | раньше   | да      |                     |
            | r3 | Аренда         | расход | 45 000,00 | Жильё     | ежемесячно 1       |            | 2027-08-31 | нет      | да      | до переезда         |
            | r4 | Продукты       | расход | 4 000,00  | Еда       | еженедельно сб     |            |            | нет      | да      |                     |
            | r5 | Страховка авто | расход | 30 000,00 | Авто      | ежегодно 03-15     |            |            | позже    | да      |                     |
            | r6 | Абонемент      | расход | 3 500,00  | Спорт     | каждые 2 месяца 10 | 2026-10-01 |            | нет      | да      |                     |
            | r7 | Кофе           | расход | 300,00    | Еда       | каждые 3 дня       | 2026-09-02 |            | нет      | нет     | пока отключено      |
            | r8 | Кредит         | расход | 12 345,67 | Жильё     | ежемесячно 31      |            | 2027-03-31 | раньше   | да      | 31 = последний день |

            ## Разовые операции

            | ID | Дата       | Название | Тип    | Сумма     | Категория | Заметка |
            |----|------------|----------|--------|-----------|-----------|---------|
            | t1 | 2026-12-20 | Премия   | доход  | 60 000,00 | Зарплата  |         |
            | t2 | 2027-01-10 | Ноутбук  | расход | 90 000,00 | Техника   |         |

            ## Корректировки

            | Правило | Исходная дата | Действие   | Новая сумма | Новая дата | Заметка                 |
            |---------|---------------|------------|-------------|------------|-------------------------|
            | r1      | 2026-12-05    | изменить   | 95 000,00   |            | годовой бонус           |
            | r3      | 2027-01-01    | перенести  |             | 2027-01-09 | договорились с хозяином |
            | r4      | 2026-10-03    | пропустить |             |            | в отпуске               |
            | r1      | 2027-03-05    | заменить   | 70 000,00   | 2027-03-07 | новая работа            |
            """;

    // ------------------------------------------------------------------ помощники

    private static RecurringRule rule(String id, Kind kind, String amount, Recurrence recurrence, String from, String until,
                                      WeekendPolicy policy, boolean enabled, String note) {
        return new RecurringRule(new RuleId(id), "Правило " + id, kind, Money.parse(amount), "Категория " + id, recurrence,
                from == null ? null : LocalDate.parse(from), until == null ? null : LocalDate.parse(until), policy, enabled, note);
    }

    private static Adjustment adjust(String ruleId, String date, Adjustment.Action action, String note) {
        return new Adjustment(new OccurrenceKey(new RuleId(ruleId), LocalDate.parse(date)), action, note);
    }

    /** План, в котором есть всё: каждый вид повтора, все четыре действия, цель, нераспознанные фрагменты. */
    private static Plan fullPlan() {
        return new Plan("Полный план", "Строка 1\nСтрока 2 с \"кавычками\" и | трубой\n\\## экранировано", "$",
                LocalDate.of(2026, 9, 1), Money.parse("-1 234,56"), new Horizon.Months(12), Money.ofMajor(50_000),
                new Goal("Отпуск", Money.ofMajor(300_000), LocalDate.of(2027, 6, 1)),
                List.of(rule("r1", Kind.INCOME, "80000", new Recurrence.Monthly(5, 1), "2026-09-01", null,
                                WeekendPolicy.PREVIOUS_BUSINESS_DAY, true, ""),
                        rule("r2", Kind.EXPENSE, "12 345,67", new Recurrence.Monthly(31, 2), "2026-10-01", "2027-03-31",
                                WeekendPolicy.NEXT_BUSINESS_DAY, true, "31 = последний день"),
                        rule("r3", Kind.EXPENSE, "4000", new Recurrence.Weekly(DayOfWeek.SATURDAY, 1), null, null,
                                WeekendPolicy.NONE, true, ""),
                        rule("r4", Kind.EXPENSE, "1500,5", new Recurrence.Weekly(DayOfWeek.MONDAY, 3), "2026-09-07", null,
                                WeekendPolicy.NONE, true, ""),
                        rule("r5", Kind.EXPENSE, "300", new Recurrence.EveryNDays(3), "2026-09-02", null,
                                WeekendPolicy.NONE, false, "пока отключено"),
                        rule("r6", Kind.EXPENSE, "150", new Recurrence.EveryNDays(1), null, null, WeekendPolicy.NONE, true, ""),
                        rule("r7", Kind.EXPENSE, "30000", new Recurrence.Yearly(MonthDay.of(3, 15)), null, null,
                                WeekendPolicy.NEXT_BUSINESS_DAY, true, ""),
                        rule("r8", Kind.INCOME, "1000", new Recurrence.Yearly(MonthDay.of(2, 29)), null, null,
                                WeekendPolicy.NONE, true, "день рождения")),
                List.of(new OneTimeTransaction(new TxId("t1"), LocalDate.of(2026, 12, 20), "Премия", Kind.INCOME,
                                Money.ofMajor(60_000), "Зарплата", ""),
                        new OneTimeTransaction(new TxId("t2"), LocalDate.of(2027, 1, 10), "Ноутбук", Kind.EXPENSE,
                                Money.parse("89 999,99"), "Техника", "в кредит")),
                List.of(adjust("r1", "2026-12-05", new Adjustment.ChangeAmount(Money.ofMajor(95_000)), "годовой бонус"),
                        adjust("r3", "2027-01-02", new Adjustment.MoveDate(LocalDate.of(2027, 1, 9)), ""),
                        adjust("r3", "2026-10-03", new Adjustment.Skip(), "в отпуске"),
                        adjust("r1", "2027-03-05", new Adjustment.Replace(Money.ofMajor(70_000), LocalDate.of(2027, 3, 7)), "новая работа")),
                List.of(new RawBlock("", List.of("Текст до первой секции")),
                        new RawBlock("§Параметры:список", List.of("- Мой ключ: значение")),
                        new RawBlock("Заметка", List.of("", "## Мои заметки", "| a | b |", "  отступ  ")),
                        new RawBlock("Неизвестная секция", List.of())));
    }

    /** Упорядоченная карта из пар ключ-значение (допускает {@code null}, в отличие от {@code Map.of}). */
    private static Map<String, Object> ordered(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    /** Карта через текст JSON: так её получит сервер от браузера. */
    private static Map<String, Object> viaText(Map<String, Object> map) {
        return JsonParser.parseObject(JsonWriter.write(map));
    }

    @SuppressWarnings("unchecked") // тестовый доступ к вложенным значениям карты
    private static <T> T get(Object map, String key) {
        return (T) ((Map<String, Object>) map).get(key);
    }

    // ------------------------------------------------------------------ план

    @Test
    void fullPlanRoundTripIsExact() {
        Plan plan = fullPlan();
        Map<String, Object> map = PlanJson.plan(plan);
        assertEquals(plan, PlanJson.planFrom(map));
        assertEquals(map, JsonParser.parse(JsonWriter.write(map)), "карта переживает запись и разбор JSON без изменений");
        assertEquals(map, JsonParser.parse(JsonWriter.writePretty(map)));
        assertEquals(plan, PlanJson.planFrom(viaText(map)));
    }

    @Test
    void planVariantsRoundTrip() {
        Plan base = fullPlan();
        List<Plan> variants = List.of(
                base.withGoal(null),
                base.withGoal(new Goal("", Money.ofMajor(1), null)),
                base.withHorizon(new Horizon.Years(2)),
                base.withHorizon(new Horizon.Until(LocalDate.of(2027, 12, 31))),
                Plan.empty("Пустой", TODAY));
        for (Plan plan : variants) {
            assertEquals(plan, PlanJson.planFrom(viaText(PlanJson.plan(plan))));
        }
    }

    @Test
    void planUsesCanonicalValues() {
        Map<String, Object> map = PlanJson.plan(fullPlan());
        assertEquals(List.of("name", "note", "currency", "startDate", "startBalance", "startBalanceText", "horizon", "endDate",
                "cushion", "cushionText", "goal", "rules", "oneTimes", "adjustments", "rawBlocks", "categories"),
                new ArrayList<>(map.keySet()));
        assertEquals("2026-09-01", map.get("startDate"));
        assertEquals("-1234,56", map.get("startBalance"));
        assertEquals("-1 234,56", map.get("startBalanceText"));
        assertEquals("2027-08-31", map.get("endDate"));
        assertEquals(ordered("kind", "MONTHS", "count", 12L, "until", null, "label", "12 месяцев"), map.get("horizon"));
        assertEquals(ordered("title", "Отпуск", "target", "300000,00", "targetText", "300 000,00", "wishDate", "2027-06-01"),
                map.get("goal"));

        List<Object> rules = get(map, "rules");
        assertEquals(ordered("id", "r2", "title", "Правило r2", "kind", "EXPENSE", "kindText", "Расход",
                "amount", "12345,67", "amountText", "12 345,67", "category", "Категория r2",
                "recurrence", ordered("kind", "MONTHLY", "dayOfMonth", 31L, "everyN", 2L, "weekday", null, "monthDay", null,
                        "text", "каждые 2 месяца 31"),
                "from", "2026-10-01", "until", "2027-03-31", "weekendPolicy", "NEXT_BUSINESS_DAY",
                "weekendPolicyText", "На понедельник (позже)", "enabled", true, "note", "31 = последний день"), rules.get(1));

        List<Object> adjustments = get(map, "adjustments");
        assertEquals(ordered("ruleId", "r1", "originalDate", "2026-12-05", "rowId", "r1@2026-12-05", "action", "CHANGE_AMOUNT",
                "actionText", "изменить", "amount", "95000,00", "amountText", "95 000,00", "date", null, "note", "годовой бонус"),
                adjustments.get(0));
        assertEquals("MOVE_DATE", get(adjustments.get(1), "action"));
        assertEquals("2027-01-09", get(adjustments.get(1), "date"));
        assertNull(get(adjustments.get(1), "amount"));
        assertEquals("SKIP", get(adjustments.get(2), "action"));
        assertEquals("REPLACE", get(adjustments.get(3), "action"));

        List<Object> oneTimes = get(map, "oneTimes");
        assertEquals("20.12.2026", get(oneTimes.get(0), "dateText"));
        List<Object> rawBlocks = get(map, "rawBlocks");
        assertEquals(ordered("afterSection", "§Параметры:список", "lines", List.of("- Мой ключ: значение")), rawBlocks.get(1));
    }

    @Test
    void everyRecurrenceKindHasCanonicalShape() {
        assertEquals(ordered("kind", "MONTHLY", "dayOfMonth", 5L, "everyN", 1L, "weekday", null, "monthDay", null,
                "text", "ежемесячно 5"), PlanJson.recurrence(new Recurrence.Monthly(5, 1)));
        assertEquals(ordered("kind", "WEEKLY", "dayOfMonth", null, "everyN", 1L, "weekday", "SATURDAY", "monthDay", null,
                "text", "еженедельно сб"), PlanJson.recurrence(new Recurrence.Weekly(DayOfWeek.SATURDAY, 1)));
        assertEquals(ordered("kind", "EVERY_N_DAYS", "dayOfMonth", null, "everyN", 3L, "weekday", null, "monthDay", null,
                "text", "каждые 3 дня"), PlanJson.recurrence(new Recurrence.EveryNDays(3)));
        assertEquals(ordered("kind", "YEARLY", "dayOfMonth", null, "everyN", null, "weekday", null, "monthDay", "03-15",
                "text", "ежегодно 03-15"), PlanJson.recurrence(new Recurrence.Yearly(MonthDay.of(3, 15))));
        for (Recurrence r : List.of(new Recurrence.Monthly(31, 12), new Recurrence.Weekly(DayOfWeek.SUNDAY, 52),
                new Recurrence.EveryNDays(366), new Recurrence.Yearly(MonthDay.of(2, 29)))) {
            assertEquals(r, PlanJson.recurrenceFrom(viaText(PlanJson.recurrence(r))));
        }
        for (Horizon h : List.of(new Horizon.Months(600), new Horizon.Years(50), new Horizon.Until(LocalDate.of(2030, 1, 1)))) {
            assertEquals(h, PlanJson.horizonFrom(viaText(PlanJson.horizon(h))));
        }
    }

    @Test
    void markdownSampleSurvivesJsonRoundTrip() {
        ReadResult read = PlanMarkdownReader.read(FAMILY_BUDGET, "Семейный бюджет 2026", TODAY);
        assertTrue(read.diagnostics().isEmpty(), () -> read.diagnostics().toString());
        Plan fromJson = PlanJson.planFrom(viaText(PlanJson.plan(read.plan())));
        assertEquals(read.plan(), fromJson);
        assertEquals(FAMILY_BUDGET, PlanMarkdownWriter.write(fromJson));
    }

    @Test
    void lenientInputFromBrowser() {
        Map<String, Object> ruleMap = JsonParser.parseObject("""
                {"title": " Продукты ", "kind": "expense", "amount": 4000,
                 "recurrence": {"kind": "weekly", "weekday": "сб", "everyN": "2"},
                 "from": "05.10.2026", "until": "", "category": null}
                """);
        RecurringRule rule = PlanJson.ruleFrom(ruleMap, new RuleId("r9"));
        assertEquals(new RecurringRule(new RuleId("r9"), "Продукты", Kind.EXPENSE, Money.ofMajor(4_000), "",
                new Recurrence.Weekly(DayOfWeek.SATURDAY, 2), LocalDate.of(2026, 10, 5), null, WeekendPolicy.NONE, true, ""), rule);

        Map<String, Object> txMap = JsonParser.parseObject("""
                {"date": "2026-12-20", "kind": "INCOME", "amount": "60 000,5"}
                """);
        OneTimeTransaction tx = PlanJson.oneTimeFrom(txMap, new TxId("t7"));
        assertEquals(new TxId("t7"), tx.id());
        assertEquals(Money.parse("60000,50"), tx.amount());

        Map<String, Object> yearly = JsonParser.parseObject("""
                {"kind": "YEARLY", "monthDay": "--02-29"}
                """);
        assertEquals(new Recurrence.Yearly(MonthDay.of(2, 29)), PlanJson.recurrenceFrom(yearly));
    }

    @Test
    void invalidInputProducesRussianMessages() {
        JsonException noName = assertThrows(JsonException.class, () -> PlanJson.planFrom(JsonParser.parseObject("{}")));
        assertEquals("Отсутствует обязательное поле «name»", noName.getMessage());

        JsonException badDate = assertThrows(JsonException.class, () -> PlanJson.planFrom(JsonParser.parseObject("""
                {"name": "x", "startDate": "2026-13-01", "horizon": {"kind": "MONTHS", "count": 12}}
                """)));
        assertTrue(badDate.getMessage().startsWith("Поле «startDate»: Некорректная дата"), badDate.getMessage());

        JsonException noHorizon = assertThrows(JsonException.class, () -> PlanJson.planFrom(JsonParser.parseObject("""
                {"name": "x", "startDate": "2026-09-01"}
                """)));
        assertEquals("Отсутствует обязательное поле «horizon»", noHorizon.getMessage());

        JsonException badDay = assertThrows(JsonException.class, () -> PlanJson.planFrom(JsonParser.parseObject("""
                {"name": "x", "startDate": "2026-09-01", "horizon": {"kind": "MONTHS", "count": 12},
                 "rules": [{"id": "r1", "kind": "INCOME", "amount": "1", "recurrence": {"kind": "MONTHLY", "dayOfMonth": 32}}]}
                """)));
        assertEquals("Элемент rules[0]: Повтор: День месяца должен быть от 1 до 31", badDay.getMessage());

        JsonException badKind = assertThrows(JsonException.class, () -> PlanJson.oneTimeFrom(JsonParser.parseObject("""
                {"id": "t1", "date": "2026-09-01", "kind": "ДОХОД", "amount": "1"}
                """)));
        assertEquals("Поле «kind»: неизвестное значение «ДОХОД» (ожидается одно из: INCOME, EXPENSE)", badKind.getMessage());

        JsonException noId = assertThrows(JsonException.class, () -> PlanJson.oneTimeFrom(JsonParser.parseObject("""
                {"date": "2026-09-01", "kind": "INCOME", "amount": "1"}
                """)));
        assertEquals("Отсутствует обязательное поле «id»", noId.getMessage());

        JsonException badId = assertThrows(JsonException.class, () -> PlanJson.ruleFrom(JsonParser.parseObject("""
                {"id": "r|1", "kind": "INCOME", "amount": "1", "recurrence": {"kind": "EVERY_N_DAYS", "everyN": 2}}
                """)));
        assertTrue(badId.getMessage().startsWith("Регулярная операция: Некорректный идентификатор"), badId.getMessage());

        JsonException noAmount = assertThrows(JsonException.class, () -> PlanJson.adjustmentFrom(JsonParser.parseObject("""
                {"ruleId": "r1", "originalDate": "2026-10-05", "action": "CHANGE_AMOUNT"}
                """)));
        assertTrue(noAmount.getMessage().contains("«Новая сумма»"), noAmount.getMessage());

        JsonException badMoney = assertThrows(JsonException.class, () -> PlanJson.goalFrom(JsonParser.parseObject("""
                {"target": "8о 000"}
                """)));
        assertTrue(badMoney.getMessage().startsWith("Поле «target»: Некорректная сумма"), badMoney.getMessage());

        JsonException badHorizon = assertThrows(JsonException.class, () -> PlanJson.horizonFrom(JsonParser.parseObject("""
                {"kind": "MONTHS", "count": 601}
                """)));
        assertEquals("Горизонт: Горизонт должен быть от 1 до 600 месяцев", badHorizon.getMessage());

        JsonException badLines = assertThrows(JsonException.class, () -> PlanJson.rawBlockFrom(JsonParser.parseObject("""
                {"afterSection": "", "lines": ["a", 1]}
                """)));
        assertEquals("Элемент lines[1] должен быть строкой, получено: число", badLines.getMessage());

        // Регрессия: незнакомый день недели давал NullPointerException вместо JsonException.
        JsonException badWeekday = assertThrows(JsonException.class, () -> PlanJson.recurrenceFrom(JsonParser.parseObject("""
                {"kind": "WEEKLY", "weekday": "праздник"}
                """)));
        assertEquals("Поле «weekday»: неизвестный день недели «праздник»", badWeekday.getMessage());
        JsonException badRuleWeekday = assertThrows(JsonException.class, () -> PlanJson.ruleFrom(JsonParser.parseObject("""
                {"id": "r1", "kind": "INCOME", "amount": "1", "recurrence": {"kind": "WEEKLY", "weekday": "праздник"}}
                """)));
        assertTrue(badRuleWeekday.getMessage().contains("неизвестный день недели «праздник»"), badRuleWeekday.getMessage());
    }

    // ------------------------------------------------------------------ прогноз

    @Test
    void forecastJsonShape() {
        Plan plan = PlanMarkdownReader.read(FAMILY_BUDGET, "Семейный бюджет 2026", TODAY).plan();
        Forecast forecast = ForecastEngine.forecast(plan, WhatIf.NONE, TODAY, true);
        List<DailyPoint> chart = ChartSeries.sample(forecast, forecast.startDate(), forecast.endDate(), 200);
        Map<String, Object> map = PlanJson.forecast(forecast, forecast.rows(), chart);

        assertEquals(List.of("planName", "currency", "today", "anchor", "startDate", "endDate", "startBalance", "startBalanceText",
                "endBalance", "endBalanceText", "cushion", "cushionText", "goal", "whatIf", "rows", "summary", "warnings", "chart"),
                new ArrayList<>(map.keySet()));
        assertEquals("Семейный бюджет 2026", map.get("planName"));
        assertEquals("2026-09-13", map.get("anchor"));
        assertEquals("2027-08-31", map.get("endDate"));
        assertEquals("50000,00", map.get("cushion"));
        assertEquals(false, get(map.get("whatIf"), "active"));

        List<Object> rows = get(map, "rows");
        assertEquals(forecast.rows().size(), rows.size());
        Map<String, Object> start = Json.asObject(rows.get(0), "строка");
        assertEquals("start", start.get("rowId"));
        assertEquals("START", start.get("origin"));
        assertEquals("0,00", start.get("amount"));
        assertEquals("150000,00", start.get("balanceAfter"));
        assertEquals("150 000,00", start.get("balanceAfterText"));
        assertEquals(List.of("shifted", "amountChanged", "moved", "skipped", "past", "whatIf"),
                new ArrayList<>(Json.asObject(start.get("flags"), "flags").keySet()));
        assertEquals(true, get(start.get("flags"), "past"));

        ForecastRow bonusSource = forecast.findRow("r1@2026-12-05").orElseThrow();
        Map<String, Object> bonus = rowById(rows, "r1@2026-12-05");
        assertEquals(DateFormats.iso(bonusSource.date()), bonus.get("date"));
        assertEquals("2026-12-05", bonus.get("originalDate"));
        assertEquals("95000,00", bonus.get("amount"));
        assertEquals("95 000,00", bonus.get("amountText"));
        assertEquals("+95 000,00", bonus.get("amountSignedText"));
        assertEquals(DateFormats.ru(bonusSource.date()), bonus.get("dateText"));
        assertEquals("r1", bonus.get("ruleId"));
        assertNull(bonus.get("txId"));
        assertEquals(true, get(bonus.get("flags"), "amountChanged"));
        assertEquals("годовой бонус", bonus.get("note"));

        assertEquals(true, get(rowById(rows, "r4@2026-10-03").get("flags"), "skipped"));
        Map<String, Object> laptop = rowById(rows, "t2");
        assertEquals("ONE_TIME", laptop.get("origin"));
        assertEquals("-90000,00", laptop.get("amount"));
        assertEquals("90 000,00", laptop.get("amountText"));
        assertEquals("t2", laptop.get("txId"));
        assertEquals(bonusSource.balanceAfter().isLessThan(Money.ofMajor(50_000)), bonus.get("belowCushion"));

        Map<String, Object> summary = get(map, "summary");
        List<Object> months = get(summary, "byMonth");
        assertEquals(12, months.size());
        assertEquals("2026-09", get(months.get(0), "month"));
        assertEquals(DateFormats.monthTitle(YearMonth.of(2026, 9)), get(months.get(0), "title"));
        assertEquals(forecast.summary().byMonth().get(YearMonth.of(2027, 8)).closingBalance().formatPlain(),
                get(months.get(11), "closingBalance"));
        List<Object> marks = get(summary, "balanceAfterMonths");
        assertEquals(forecast.summary().balanceAfterMonths().keySet().stream().map(Integer::longValue).toList(),
                marks.stream().map(m -> (Long) get(m, "months")).toList());
        assertEquals("2026-10-13", get(marks.get(0), "date"));
        assertEquals(forecast.summary().goalReachDate().map(DateFormats::iso).orElse(null), summary.get("goalReachDate"));

        List<Object> warnings = get(map, "warnings");
        assertEquals(forecast.warnings().size(), warnings.size());
        for (Object w : warnings) {
            WarningType.valueOf(get(w, "type"));
            assertTrue(((String) get(w, "text")).endsWith((String) get(w, "message")));
        }

        List<Object> points = get(map, "chart");
        assertEquals(chart.size(), points.size());
        assertEquals(chart.get(0).balance().minor(), (long) (Long) get(points.get(0), "balanceMinor"));
        assertEquals(DateFormats.iso(chart.get(chart.size() - 1).date()), get(points.get(points.size() - 1), "date"));

        assertEquals(map, JsonParser.parse(JsonWriter.write(map)));
        Map<String, Object> empty = PlanJson.forecast(forecast, null, null);
        assertEquals(List.of(), empty.get("rows"));
        assertEquals(List.of(), empty.get("chart"));
    }

    private static Map<String, Object> rowById(List<Object> rows, String rowId) {
        for (Object row : rows) {
            if (rowId.equals(get(row, "rowId"))) {
                return Json.asObject(row, "строка");
            }
        }
        throw new AssertionError("Нет строки " + rowId);
    }

    // ------------------------------------------------------------------ вид, настройки, диагностика

    @Test
    void viewStateRoundTrip() {
        ViewState view = ViewState.defaults()
                .withMode(ViewMode.CHART)
                .withPeriod(PeriodChoice.M24)
                .withShowIncome(false)
                .withShowSkipped(true)
                .withChartBars(true)
                .withSummaryPanel(false)
                .withFilterText("  аренда \"квартиры\" ")
                .withWhatIf(WhatIf.ofPercent(10, -5, Money.parse("3 000,50")));
        Map<String, Object> map = PlanJson.viewState(view);
        assertEquals("CHART", map.get("mode"));
        assertEquals("M24", map.get("period"));
        assertEquals(24L, map.get("periodMonths"));
        assertEquals(ordered("incomeFactor", "1.10", "expenseFactor", "0.95", "extraMonthlySaving", "3000,50",
                "extraMonthlySavingText", "3 000,50", "active", true), map.get("whatIf"));
        assertEquals(view, PlanJson.viewStateFrom(map));
        assertEquals(view, PlanJson.viewStateFrom(viaText(map)));
        assertEquals(map, JsonParser.parse(JsonWriter.write(map)));
        assertEquals(ViewState.defaults(), PlanJson.viewStateFrom(viaText(PlanJson.viewState(ViewState.defaults()))));
        assertEquals(ViewState.defaults(), PlanJson.viewStateFrom(new LinkedHashMap<>()));
    }

    @Test
    void viewStateAndWhatIfAcceptLabelsAndNumbers() {
        ViewState view = PlanJson.viewStateFrom(JsonParser.parseObject("""
                {"mode": "график", "period": "6 месяцев", "whatIf": {"incomeFactor": 1.1, "expenseFactor": "0,9"}}
                """));
        assertEquals(ViewMode.CHART, view.mode());
        assertEquals(PeriodChoice.M6, view.period());
        assertEquals(new BigDecimal("1.1"), view.whatIf().incomeFactor());
        assertEquals(new BigDecimal("0.9"), view.whatIf().expenseFactor());
        assertEquals(Money.ZERO, view.whatIf().extraMonthlySaving());
        assertEquals(WhatIf.NONE, PlanJson.whatIfFrom(JsonParser.parseObject("{\"incomeFactor\": 1}")));

        JsonException badMode = assertThrows(JsonException.class,
                () -> PlanJson.viewStateFrom(JsonParser.parseObject("{\"mode\": \"список\"}")));
        assertEquals("Поле «mode»: неизвестное значение «список»", badMode.getMessage());
        JsonException negative = assertThrows(JsonException.class,
                () -> PlanJson.whatIfFrom(JsonParser.parseObject("{\"expenseFactor\": \"-1\"}")));
        assertEquals("Что-если: Коэффициент «что-если» не может быть отрицательным", negative.getMessage());
        JsonException notNumber = assertThrows(JsonException.class,
                () -> PlanJson.whatIfFrom(JsonParser.parseObject("{\"incomeFactor\": \"много\"}")));
        assertEquals("Поле «incomeFactor»: некорректное число «много»", notNumber.getMessage());
    }

    @Test
    void settingsRoundTrip() {
        AppSettings settings = AppSettings.defaults()
                .withPlanOpened("Сценарий без аренды.md")
                .withPlanOpened("Семейный бюджет 2026.md")
                .withRecoveryStore(RecoveryStoreKind.XML)
                .withAutosave(true)
                .withView(ViewMode.CHART)
                .withPeriod(PeriodChoice.ALL)
                .withShowOneTime(false)
                .withShowSkipped(true)
                .withMonthTotals(false)
                .withChartMarkers(false)
                .withChartBars(true)
                .withSummaryPanel(false);
        Map<String, Object> map = PlanJson.settings(settings);
        assertEquals(List.of("Семейный бюджет 2026.md", "Сценарий без аренды.md"), map.get("recentPlans"));
        assertEquals("XML", map.get("recoveryStore"));
        assertEquals("ALL", map.get("period"));
        assertEquals(settings, PlanJson.settingsFrom(map));
        assertEquals(settings, PlanJson.settingsFrom(viaText(map)));
        assertEquals(map, JsonParser.parse(JsonWriter.write(map)));
        assertEquals(AppSettings.defaults(), PlanJson.settingsFrom(new LinkedHashMap<>()));
        assertEquals(RecoveryStoreKind.REGISTRY,
                PlanJson.settingsFrom(JsonParser.parseObject("{\"recoveryStore\": \"реестр\"}")).recoveryStore());
    }

    @Test
    void diagnosticsShape() {
        List<Object> list = PlanJson.diagnostics(List.of(
                Diagnostic.error(27, "Некорректная сумма: «8о 000»"),
                Diagnostic.info("Имя плана взято из имени файла")));
        assertEquals(List.of(
                ordered("severity", "ERROR", "severityTitle", "Ошибка", "line", 27L, "message", "Некорректная сумма: «8о 000»",
                        "text", "Ошибка, строка 27: Некорректная сумма: «8о 000»"),
                ordered("severity", "INFO", "severityTitle", "Сведения", "line", 0L, "message", "Имя плана взято из имени файла",
                        "text", "Сведения: Имя плана взято из имени файла")), list);
        assertEquals(list, JsonParser.parse(JsonWriter.write(list)));
    }
}
