package ru.cashprediction.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.format.DashFreeOutput;
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
 * Тесты экспорта CSV: заголовок, BOM, разделители, кавычки RFC 4180, отметки, диапазон.
 */
class CsvExporterTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);

    /** Прогноз: зарплата со сдвигом и корректировкой, аренда с «опасными» символами, разовая покупка. */
    private static Plan plan() {
        RecurringRule salary = new RecurringRule(new RuleId("r1"), "Зарплата", Kind.INCOME, Money.ofMajor(80_000), "Зарплата",
                new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.PREVIOUS_BUSINESS_DAY, true, "");
        RecurringRule rent = new RecurringRule(new RuleId("r2"), "Аренда; \"центр\"", Kind.EXPENSE, Money.ofMajor(45_000), "Жильё",
                new Recurrence.Monthly(1, 1), null, null, WeekendPolicy.NONE, true, "до\nпереезда");
        OneTimeTransaction laptop = new OneTimeTransaction(new TxId("t1"), LocalDate.of(2026, 10, 10), "Ноутбук", Kind.EXPENSE,
                Money.ofMajor(90_000), "Техника", "");
        Adjustment bonus = new Adjustment(new OccurrenceKey(salary.id(), LocalDate.of(2026, 10, 5)),
                new Adjustment.ChangeAmount(Money.ofMajor(95_000)), "годовой бонус");
        return new Plan("CSV", "", "₽", START, Money.ofMajor(150_000), new Horizon.Months(2), Money.ZERO, null,
                List.of(salary, rent), List.of(laptop), List.of(bonus), List.of());
    }

    /** Точка с запятой, BOM, CRLF, кавычки и отметки. */
    @Test
    void semicolonWithBom() {
        Forecast f = ForecastEngine.forecast(plan(), WhatIf.NONE, START, false);
        String csv = CsvExporter.toCsv(f, CsvOptions.DEFAULT);
        assertTrue(csv.startsWith("﻿Дата;"), "BOM и заголовок");
        assertTrue(csv.endsWith("\r\n"));
        List<String> lines = List.of(csv.substring(1).split("\r\n"));
        assertEquals(List.of(
                "Дата;День;Операция;Категория;Доход;Расход;Баланс;Отметки;Заметка",
                "01.09.2026;вт;Начальный баланс;;;;150000,00;;",
                "01.09.2026;вт;\"Аренда; \"\"центр\"\"\";Жильё;;45000,00;105000,00;;\"до\nпереезда\"",
                "04.09.2026;пт;Зарплата;Зарплата;80000,00;;185000,00;сдвиг с выходного;",
                "01.10.2026;чт;\"Аренда; \"\"центр\"\"\";Жильё;;45000,00;140000,00;;\"до\nпереезда\"",
                "05.10.2026;пн;Зарплата;Зарплата;95000,00;;235000,00;изменена сумма;годовой бонус",
                "10.10.2026;сб;Ноутбук;Техника;;90000,00;145000,00;разовая;"), lines);
    }

    /** Запятая без BOM: суммы и списки отметок с запятой берутся в кавычки. */
    @Test
    void commaWithoutBom() {
        Forecast f = ForecastEngine.forecast(plan(), new WhatIf(BigDecimal.ONE, new BigDecimal("1.1"), Money.ZERO), START, false);
        String csv = CsvExporter.toCsv(f, new CsvOptions(',', false, null, null));
        assertFalse(csv.startsWith("﻿"));
        List<String> lines = List.of(csv.split("\r\n"));
        assertEquals("Дата,День,Операция,Категория,Доход,Расход,Баланс,Отметки,Заметка", lines.get(0));
        String laptop = lines.stream().filter(l -> l.contains("Ноутбук")).findFirst().orElseThrow();
        assertEquals("10.10.2026,сб,Ноутбук,Техника,,\"99000,00\",\"127000,00\",\"разовая, что-если\",", laptop);
        assertEquals("01.09.2026,вт,Начальный баланс,,,,\"150000,00\",,", lines.get(1));
    }

    /** Табуляция как разделитель и диапазон дат без начального баланса. */
    @Test
    void tabSeparatorAndRange() {
        Forecast f = ForecastEngine.forecast(plan(), WhatIf.NONE, START, false);
        String csv = CsvExporter.toCsv(f, new CsvOptions('\t', false, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 5)));
        List<String> lines = List.of(csv.split("\r\n"));
        assertEquals(3, lines.size(), lines.toString());
        assertEquals(String.join("\t", CsvExporter.HEADER), lines.get(0));
        assertEquals("01.10.2026\tчт\t\"Аренда; \"\"центр\"\"\"\tЖильё\t\t45000,00\t140000,00\t\t\"до\nпереезда\"", lines.get(1));
        assertTrue(lines.get(2).startsWith("05.10.2026\tпн\tЗарплата"));
    }

    /** Пропущенное событие получает отметку «пропущено». */
    @Test
    void skippedMark() {
        Plan p = plan().withAdjustmentPut(new Adjustment(new OccurrenceKey(new RuleId("r2"), LocalDate.of(2026, 10, 1)), new Adjustment.Skip(), ""));
        Forecast f = ForecastEngine.forecast(p, WhatIf.NONE, START, true);
        String csv = CsvExporter.toCsv(f, CsvOptions.DEFAULT);
        assertTrue(csv.contains(";пропущено;"), csv);
    }

    /** Решение 2026-09-14: CSV со всеми отметками при любом разделителе пишется без длинного и среднего тире. */
    @Test
    void csvHasNoDashes() {
        Plan p = plan()
                .withAdjustmentPut(new Adjustment(new OccurrenceKey(new RuleId("r2"), LocalDate.of(2026, 10, 1)), new Adjustment.Skip(), ""))
                .withAdjustmentPut(new Adjustment(new OccurrenceKey(new RuleId("r2"), LocalDate.of(2026, 9, 1)),
                        new Adjustment.MoveDate(LocalDate.of(2026, 9, 3)), "перенос"));
        for (WhatIf whatIf : List.of(WhatIf.NONE, new WhatIf(BigDecimal.ONE, new BigDecimal("1.1"), Money.ZERO))) {
            Forecast f = ForecastEngine.forecast(p, whatIf, START, true);
            for (CsvOptions options : List.of(CsvOptions.DEFAULT, new CsvOptions(',', false, null, null),
                    new CsvOptions('\t', true, null, null))) {
                DashFreeOutput.assertNoDashes("CSV", CsvExporter.toCsv(f, options));
            }
        }
    }

    /** Экранирование ячеек по RFC 4180. */
    @Test
    void escaping() {
        assertEquals("просто", CsvExporter.escape("просто", ';'));
        assertEquals("a,b", CsvExporter.escape("a,b", ';'));
        assertEquals("\"a;b\"", CsvExporter.escape("a;b", ';'));
        assertEquals("\"say \"\"hi\"\"\"", CsvExporter.escape("say \"hi\"", ';'));
        assertEquals("\"a\rb\"", CsvExporter.escape("a\rb", ';'));
        assertEquals("", CsvExporter.escape(null, ';'));
    }

    /** Некорректные параметры экспорта. */
    @Test
    void invalidOptions() {
        assertThrows(IllegalArgumentException.class, () -> new CsvOptions('"', true, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CsvOptions('\n', true, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CsvOptions(';', true, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 1)));
        assertEquals(LocalDate.of(2026, 10, 1), CsvOptions.DEFAULT.withRange(LocalDate.of(2026, 10, 1), null).from());
    }
}
