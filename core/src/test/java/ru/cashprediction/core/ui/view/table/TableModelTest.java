package ru.cashprediction.core.ui.view.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.cashprediction.core.ui.view.table.ViewStates.TODAY;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.SamplePlan;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.token.ColorToken;

/**
 * Таблица прогноза (спецификация v2, §5.2): колонки, тексты каждого вида строки, порядок отметок, зачёркивание,
 * курсив, цвет прошедших, приоритет фона, подсказки (строки 1-13, итог месяца, группа прошедших), группа прошедших в
 * обоих состояниях, итоги внутри свёрнутой группы, пустые состояния и прокрутка к сегодня.
 *
 * <p>Опорный план - «Пример» (§6.24) на 13.09.2026: начало 01.09.2026, баланс 150 000; до сегодня прошли аренда
 * 01.09, зарплата 05.09 (сдвинута на пт 04.09), продукты 05.09 и 12.09, поэтому «Сейчас» = 177 000.</p>
 */
class TableModelTest {

    private static final RowStyle PLAIN = new RowStyle(null, ColorToken.TEXT_PRIMARY, false, false);
    private static final RowStyle PAST = new RowStyle(null, ColorToken.TEXT_PAST, false, false);

    @Test
    void columnsFollowSpec() {
        List<ColumnSpec> columns = LazyTableModel.build(ViewStates.sample(), 1).columns();
        assertEquals(List.of(
                new ColumnSpec("date", "Дата", 92, false, ColumnSpec.Align.LEFT, false, "Фактическая дата события"),
                new ColumnSpec("day", "День", 44, false, ColumnSpec.Align.CENTER, false, "День недели"),
                new ColumnSpec("title", "Операция", 220, true, ColumnSpec.Align.LEFT, false, "Название операции"),
                new ColumnSpec("category", "Категория", 130, false, ColumnSpec.Align.LEFT, false, "Категория операции"),
                new ColumnSpec("income", "Доход", 120, false, ColumnSpec.Align.RIGHT, false, "Поступление денег"),
                new ColumnSpec("expense", "Расход", 120, false, ColumnSpec.Align.RIGHT, false, "Трата денег"),
                new ColumnSpec("balance", "Баланс", 130, false, ColumnSpec.Align.RIGHT, true,
                        "Сколько денег останется после события"),
                new ColumnSpec("marks", "Отметки", 90, false, ColumnSpec.Align.CENTER, false,
                        "✎ сумма изменена · → перенесено · ⇄ сдвиг с выходного · ≡ разовая · ✕ пропущено · Δ что-если")),
                columns);
    }

    @Test
    void sampleTableStartsCollapsedAndScrollsToToday() {
        TableModel model = LazyTableModel.build(ViewStates.sample(), 7);
        assertEquals(7, model.revision());
        assertNull(model.placeholder());
        assertEquals("", model.selectedRowId());

        assertEquals(new TableRowView("start", RowKind.START,
                List.of("01.09.2026", "вт", "Начальный баланс", "", "", "", "150 000,00", ""),
                new RowStyle(null, ColorToken.TEXT_PRIMARY, false, true), Map.of(), 1, false), model.row(0));
        assertEquals(new TableRowView("past@group", RowKind.PAST_HEADER,
                List.of("▸ Прошедшие события (4) - показать", "", "", "", "", "", "", ""),
                new RowStyle(ColorToken.BG_ALT, ColorToken.TEXT_MUTED, false, true), Map.of(), 4, false), model.row(1));
        assertEquals(new TableRowView("r2@2026-09-20", RowKind.RULE,
                List.of("18.09.2026", "пт", "Аванс", "", "40 000,00", "", "217 000,00", "⇄"), PLAIN,
                Map.of("income", new CellStyle(ColorToken.INCOME, false, false, false)), 1, true), model.row(2));
        assertEquals(new TableRowView("r4@2026-09-19", RowKind.RULE,
                List.of("19.09.2026", "сб", "Продукты", "", "", "4 000,00", "213 000,00", ""), PLAIN,
                Map.of("expense", new CellStyle(ColorToken.EXPENSE, false, false, false)), 1, true), model.row(3));
        assertEquals("r4@2026-09-26", model.row(4).rowId());
        assertEquals(List.of("30.09.2026", "ср", "Кредит", "", "", "12 345,67", "196 654,33", ""), model.row(5).cells());
        assertEquals(new TableRowView("total@2026-09", RowKind.MONTH_TOTAL,
                List.of("", "", "Сентябрь 2026 - итог", "за месяц +46 654,33", "120 000,00", "73 345,67", "196 654,33", ""),
                new RowStyle(ColorToken.TOTAL_BG, ColorToken.TEXT_PRIMARY, true, false),
                Map.of("income", new CellStyle(ColorToken.INCOME, true, false, false),
                        "expense", new CellStyle(ColorToken.EXPENSE, true, false, false)), 1, false), model.row(6));
        assertEquals(List.of("01.10.2026", "чт", "Аренда", "", "", "45 000,00", "151 654,33", ""), model.row(7).cells());

        assertEquals("r2@2026-09-20", model.scrollToRowId(), "первая строка с датой не раньше 13.09.2026");
        assertEquals(0, model.indexOf("start"));
        assertEquals(1, model.indexOf("past@group"));
        assertEquals(6, model.indexOf("total@2026-09"));
        assertEquals(-1, model.indexOf("r1@2026-09-05"), "прошедшая строка скрыта в свёрнутой группе");
        assertEquals("t1", model.row(model.indexOf("t1")).rowId());
        assertEquals("События раньше сегодняшнего дня. Щёлкните, чтобы показать их", model.tooltip(1, "date"));
    }

    @Test
    void expandedPastGroupShowsGreyRowsWithTooltips() {
        TableModel model = LazyTableModel.build(ViewStates.of(SamplePlan.create(TODAY), TODAY, ViewState.defaults(), true,
                "r3@2026-10-01"), 1);
        assertEquals("r3@2026-10-01", model.selectedRowId());
        assertEquals(List.of("▾ Прошедшие события (4) - свернуть", "", "", "", "", "", "", ""), model.row(1).cells());
        assertEquals("События раньше сегодняшнего дня. Щёлкните, чтобы свернуть их", model.tooltip(1, "title"));

        assertEquals(new TableRowView("r3@2026-09-01", RowKind.RULE,
                List.of("01.09.2026", "вт", "Аренда", "", "", "45 000,00", "105 000,00", ""), PAST, Map.of(), 1, true),
                model.row(2));
        assertEquals(new TableRowView("r1@2026-09-05", RowKind.RULE,
                List.of("04.09.2026", "пт", "Зарплата", "", "80 000,00", "", "185 000,00", "⇄"), PAST, Map.of(), 1, true),
                model.row(3));
        assertEquals(3, model.indexOf("r1@2026-09-05"));
        assertEquals(List.of("r4@2026-09-05", "r4@2026-09-12", "r2@2026-09-20"),
                List.of(model.row(4).rowId(), model.row(5).rowId(), model.row(6).rowId()));
        assertEquals("r2@2026-09-20", model.scrollToRowId());

        String tooltip = """
                Зарплата
                Регулярная операция: ежемесячно 5
                По правилу: 05.09.2026, фактически: 04.09.2026
                ⇄ сдвинуто с выходного дня
                Событие уже в прошлом
                Сумма: +80 000,00 ₽
                Баланс после: 185 000,00 ₽""";
        assertEquals(tooltip, model.tooltip(3, "title"));
        assertEquals(tooltip, model.tooltip(3, "balance"), "одна подсказка на строку во всех колонках");
        assertEquals(tooltip + "\n\nДвойной щелчок по сумме - быстрая правка", model.tooltip(3, "income"));
        assertEquals(tooltip, model.tooltip(3, "expense"), "строка 13 - только в колонке своей суммы");
    }

    @Test
    void monthTotalsHiddenInsideCollapsedGroup() {
        LocalDate today = LocalDate.of(2026, 10, 15);
        Plan plan = SamplePlan.create(TODAY);

        TableModel collapsed = LazyTableModel.build(ViewStates.of(plan, today, ViewState.defaults(), false, ""), 1);
        assertEquals("▸ Прошедшие события (12) - показать", collapsed.row(1).cells().getFirst());
        assertEquals(List.of("start", "past@group", "r4@2026-10-17", "r2@2026-10-20", "r4@2026-10-24", "r4@2026-10-31",
                "r5@2026-10-31", "total@2026-10"), rowIds(collapsed, 8));
        assertEquals(-1, collapsed.indexOf("total@2026-09"), "все строки сентября скрыты в свёрнутой группе");
        assertEquals(7, collapsed.indexOf("total@2026-10"), "у октября есть видимые строки после сегодня");

        TableModel expanded = LazyTableModel.build(ViewStates.of(plan, today, ViewState.defaults(), true, ""), 1);
        assertEquals(10, expanded.indexOf("total@2026-09"));
        TableRowView septemberTotal = expanded.row(10);
        assertEquals(new RowStyle(ColorToken.TOTAL_BG, ColorToken.TEXT_PAST, true, false), septemberTotal.rowStyle(),
                "итог внутри раскрытой группы - прошедший");
        assertEquals(Map.of(), septemberTotal.cellStyles());
        assertEquals("r3@2026-10-01", expanded.row(11).rowId(), "за итогом сентября - прошедшие строки октября");
        assertEquals(20, expanded.indexOf("total@2026-10"));
        assertEquals(ColorToken.TEXT_PRIMARY, expanded.row(20).rowStyle().text());
        assertEquals("r4@2026-10-17", expanded.scrollToRowId());
    }

    @Test
    void rowKindsMarksStrikeAndItalic() {
        Plan plan = SamplePlan.create(TODAY)
                .withAdjustmentPut(new Adjustment(key("r1", 2026, 10, 5),
                        new Adjustment.Replace(Money.ofMajor(95_000), LocalDate.of(2026, 10, 15)), "премия"))
                .withAdjustmentPut(new Adjustment(key("r4", 2026, 9, 19), new Adjustment.Skip(), ""))
                .withAdjustmentPut(new Adjustment(key("r1", 2026, 12, 5),
                        new Adjustment.ChangeAmount(Money.ofMajor(90_000)), ""))
                .withAdjustmentPut(new Adjustment(key("r2", 2026, 12, 20), new Adjustment.Skip(), ""));
        ViewState view = ViewState.defaults().withShowSkipped(true).withPeriod(PeriodChoice.ALL)
                .withWhatIf(new WhatIf(new BigDecimal("0.90"), BigDecimal.ONE, Money.ofMajor(5_000)));
        AppState state = ViewStates.of(plan, view);
        TableModel model = LazyTableModel.build(state, 1);

        // Замена суммы и даты: ✎ → и Δ (доходы × 0,90); строка найдена по новой дате корректировки.
        TableRowView replaced = row(model, "r1@2026-10-05");
        assertEquals(List.of("15.10.2026", "чт", "Зарплата", "", "85 500,00", "", balance(state, "r1@2026-10-05"),
                "✎ → Δ"), replaced.cells());
        assertTrue(replaced.quickEditable());
        assertEquals("""
                Зарплата
                Регулярная операция: ежемесячно 5
                По правилу: 05.10.2026, фактически: 15.10.2026
                ✎ сумма изменена корректировкой
                → событие перенесено
                Δ сумма изменена режимом «что-если»
                Сумма: +85 500,00 ₽
                Баланс после: %s ₽
                Заметка: премия""".formatted(balance(state, "r1@2026-10-05")),
                model.tooltip(model.indexOf("r1@2026-10-05"), "title"));

        // Изменение суммы у события, сдвинутого с выходного: ✎ ⇄ Δ.
        assertEquals(List.of("04.12.2026", "пт", "Зарплата", "", "81 000,00", "", balance(state, "r1@2026-12-05"),
                "✎ ⇄ Δ"), row(model, "r1@2026-12-05").cells());

        // Пропущенное событие: сумма «-», название и сумма зачёркнуты, быстрой правки нет.
        TableRowView skipped = row(model, "r4@2026-09-19");
        assertEquals(List.of("19.09.2026", "сб", "Продукты", "", "", "-", balance(state, "r4@2026-09-19"), "✕"),
                skipped.cells());
        assertEquals(Map.of("title", new CellStyle(null, false, false, true),
                "expense", new CellStyle(ColorToken.EXPENSE, false, false, true)), skipped.cellStyles());
        assertFalse(skipped.quickEditable());
        String skippedTooltip = model.tooltip(model.indexOf("r4@2026-09-19"), "expense");
        assertTrue(skippedTooltip.contains("\n✕ событие пропущено\n"), skippedTooltip);
        assertTrue(skippedTooltip.contains("\nСумма: -4 000,00 ₽\n"), skippedTooltip);
        assertFalse(skippedTooltip.contains("быстрая правка"), skippedTooltip);
        assertEquals("⇄ ✕ Δ", row(model, "r2@2026-12-20").cells().get(7), "пропущенное сдвинутое событие дохода");

        // Разовая операция: ≡ и Δ; быстрой правки нет даже в колонке суммы.
        TableRowView oneTime = row(model, "t1");
        assertEquals(RowKind.ONE_TIME, oneTime.kind());
        assertEquals(List.of("20.12.2026", "вс", "Премия", "", "54 000,00", "", balance(state, "t1"), "≡ Δ"),
                oneTime.cells());
        assertEquals("""
                Премия
                Разовая операция
                Δ сумма изменена режимом «что-если»
                Сумма: +54 000,00 ₽
                Баланс после: %s ₽""".formatted(balance(state, "t1")), model.tooltip(model.indexOf("t1"), "income"));

        // Доп. экономия «что-если»: сумма в колонке «Доход», отметка Δ, курсив.
        TableRowView whatIf = row(model, "whatif@2026-09-30");
        assertEquals(new TableRowView("whatif@2026-09-30", RowKind.WHAT_IF,
                List.of("30.09.2026", "ср", "Доп. экономия (что-если)", "", "5 000,00", "",
                        balance(state, "whatif@2026-09-30"), "Δ"),
                new RowStyle(null, ColorToken.TEXT_PRIMARY, false, true),
                Map.of("income", new CellStyle(ColorToken.INCOME, false, true, false)), 1, false), whatIf);
        assertEquals(model.indexOf("whatif@2026-09-30") + 1, model.indexOf("r5@2026-09-30"), "доход раньше расхода");
        assertEquals("""
                Доп. экономия (что-если)
                Сумма режима «что-если»
                Сумма: +5 000,00 ₽
                Баланс после: %s ₽""".formatted(balance(state, "whatif@2026-09-30")),
                model.tooltip(model.indexOf("whatif@2026-09-30"), "income"));

        assertEquals("Начальный баланс\nНачальный баланс плана\nБаланс после: 150 000,00 ₽", model.tooltip(0, "balance"));
    }

    @Test
    void backgroundPriorityAndBalanceTooltipVariants() {
        Plan plan = ViewStates.plan(TODAY, 60_000, new Horizon.Months(3), 50_000, List.of(), List.of(
                ViewStates.oneTime("t1", LocalDate.of(2026, 9, 15), "Ремонт", Kind.EXPENSE, 15_000),
                ViewStates.oneTime("t2", LocalDate.of(2026, 9, 20), "Отпуск", Kind.EXPENSE, 50_000),
                ViewStates.oneTime("t3", LocalDate.of(2026, 9, 25), "Премия", Kind.INCOME, 100_000),
                ViewStates.oneTime("t4", LocalDate.of(2026, 10, 10), "Машина", Kind.EXPENSE, 200_000)));
        TableModel model = LazyTableModel.build(ViewStates.of(plan, ViewState.defaults()), 1);

        assertEquals(List.of("start", "t1", "t2", "t3", "total@2026-09", "t4", "total@2026-10"), rowIds(model, 7));
        assertEquals(7, model.rowCount(), "ноябрь и декабрь без событий - без итогов");
        assertEquals("start", model.scrollToRowId(), "план начинается сегодня");
        assertEquals(-1, model.indexOf(LazyTableModel.PAST_HEADER_ROW_ID));

        assertEquals(new TableRowView("t1", RowKind.ONE_TIME,
                List.of("15.09.2026", "вт", "Ремонт", "", "", "15 000,00", "45 000,00", "≡"),
                new RowStyle(ColorToken.CUSHION_BG, ColorToken.TEXT_PRIMARY, false, false),
                Map.of("expense", new CellStyle(ColorToken.EXPENSE, false, false, false)), 1, false), model.row(1));
        assertEquals("""
                Ремонт
                Разовая операция
                Сумма: -15 000,00 ₽
                Баланс после: 45 000,00 ₽ - ниже подушки 50 000,00 ₽""", model.tooltip(1, "expense"));

        assertEquals(new RowStyle(ColorToken.NEGATIVE_BG, ColorToken.TEXT_PRIMARY, false, false), model.row(2).rowStyle(),
                "минус важнее подушки");
        assertEquals(Map.of("expense", new CellStyle(ColorToken.EXPENSE, false, false, false),
                "balance", new CellStyle(ColorToken.EXPENSE, false, false, false)), model.row(2).cellStyles());
        assertTrue(model.tooltip(2, "title").endsWith("\nБаланс после: -5 000,00 ₽ - ниже нуля!"));
        assertEquals(PLAIN, model.row(3).rowStyle());
        assertTrue(model.tooltip(3, "title").endsWith("\nБаланс после: 95 000,00 ₽"));

        assertEquals(new TableRowView("total@2026-09", RowKind.MONTH_TOTAL,
                List.of("", "", "Сентябрь 2026 - итог", "за месяц +35 000,00", "100 000,00", "65 000,00", "95 000,00", ""),
                new RowStyle(ColorToken.TOTAL_BG, ColorToken.TEXT_PRIMARY, true, false),
                Map.of("income", new CellStyle(ColorToken.INCOME, true, false, false),
                        "expense", new CellStyle(ColorToken.EXPENSE, true, false, false)), 1, false), model.row(4));
        assertEquals("""
                Сентябрь 2026
                Доходы: 100 000,00 ₽
                Расходы: 65 000,00 ₽
                Итог месяца: +35 000,00 ₽
                Баланс на конец месяца: 95 000,00 ₽""", model.tooltip(4, "category"));

        TableRowView octoberTotal = model.row(6);
        assertEquals(List.of("", "", "Октябрь 2026 - итог", "за месяц -200 000,00", "0,00", "200 000,00", "-105 000,00", ""),
                octoberTotal.cells());
        assertEquals(new RowStyle(ColorToken.NEGATIVE_BG, ColorToken.TEXT_PRIMARY, true, false), octoberTotal.rowStyle(),
                "минус важнее фона итога");
        assertEquals(new CellStyle(ColorToken.EXPENSE, true, false, false), octoberTotal.cellStyles().get("balance"));
    }

    @Test
    void filterKeepsStartAndDropsTotalsOfHiddenMonths() {
        AppState state = ViewStates.of(SamplePlan.create(TODAY), ViewState.defaults().withFilterText("АРЕНДА"));
        TableModel model = LazyTableModel.build(state, 1);
        assertEquals(List.of("start", "past@group", "r3@2026-10-01", "total@2026-10"), rowIds(model, 4));
        assertEquals("▸ Прошедшие события (1) - показать", model.row(1).cells().getFirst());
        assertEquals("r3@2026-10-01", model.scrollToRowId());

        TableModel noTotals = LazyTableModel.build(ViewStates.of(SamplePlan.create(TODAY),
                ViewState.defaults().withFilterText("аренда").withMonthTotals(false)), 1);
        assertEquals(-1, noTotals.indexOf("total@2026-10"));
        assertEquals("r3@2026-11-01", noTotals.row(3).rowId());
    }

    @Test
    void periodLimitsRows() {
        TableModel m3 = LazyTableModel.build(ViewStates.of(SamplePlan.create(TODAY),
                ViewState.defaults().withPeriod(PeriodChoice.M3)), 1);
        TableRowView last = m3.row(m3.rowCount() - 1);
        assertEquals("total@2026-12", last.rowId(), "период 3 месяца: с 13.09.2026 по 12.12.2026");
        assertEquals("r4@2026-12-12", m3.row(m3.rowCount() - 2).rowId());
        assertEquals(-1, m3.indexOf("t1"), "премия 20.12.2026 за концом периода");
    }

    @Test
    void emptyStates() {
        TableModel failed = LazyTableModel.build(ViewStates.failed(SamplePlan.create(TODAY), "Горизонт слишком длинный",
                ViewState.defaults()), 3);
        assertEquals(new Placeholder(Placeholder.Kind.FORECAST_ERROR, "Прогноз не рассчитан: Горизонт слишком длинный",
                ColorToken.EXPENSE, List.of()), failed.placeholder());
        assertEquals(0, failed.rowCount());
        assertEquals(3, failed.revision());
        assertEquals(8, failed.columns().size());
        assertEquals(-1, failed.indexOf("start"));
        assertEquals("", failed.scrollToRowId());
        assertThrows(IndexOutOfBoundsException.class, () -> failed.row(0));

        TableModel newPlan = LazyTableModel.build(ViewStates.of(Plan.empty("Мой план", TODAY), ViewState.defaults()), 1);
        assertEquals(new Placeholder(Placeholder.Kind.NEW_PLAN,
                "В плане «Мой план» пока нет операций. Добавьте зарплату, аренду и другие регулярные платежи - "
                        + "прогноз построится сразу.", ColorToken.TEXT_MUTED,
                List.of(new Placeholder.Button("empty.addIncome", CommandId.EDIT_ADD_INCOME, "Добавить доход…"),
                        new Placeholder.Button("empty.addExpense", CommandId.EDIT_ADD_EXPENSE, "Добавить расход…"),
                        new Placeholder.Button("empty.sample", CommandId.FILE_SAMPLE, "Открыть пример"))),
                newPlan.placeholder());
        assertEquals(0, newPlan.rowCount());

        TableModel filtered = LazyTableModel.build(ViewStates.of(SamplePlan.create(TODAY),
                ViewState.defaults().withFilterText("нет такой операции")), 1);
        assertEquals(new Placeholder(Placeholder.Kind.FILTERED, "Нет строк: измените фильтр, период или флажки меню «Вид»",
                ColorToken.TEXT_MUTED,
                List.of(new Placeholder.Button("empty.clearFilter", CommandId.FILTER_CLEAR, "Очистить фильтр"))),
                filtered.placeholder());
        assertEquals(0, filtered.rowCount());

        TableModel flags = LazyTableModel.build(ViewStates.of(SamplePlan.create(TODAY),
                ViewState.defaults().withShowIncome(false).withShowExpense(false)), 1);
        assertEquals(new Placeholder(Placeholder.Kind.FILTERED, "Нет строк: измените фильтр, период или флажки меню «Вид»",
                ColorToken.TEXT_MUTED, List.of()), flags.placeholder(), "без фильтра кнопки «Очистить фильтр» нет");
    }

    @Test
    void indexOfToleratesUnknownIds() {
        TableModel model = LazyTableModel.build(ViewStates.sample(), 1);
        assertEquals(-1, model.indexOf(null));
        assertEquals(-1, model.indexOf(""));
        assertEquals(-1, model.indexOf("nope"));
        assertEquals(-1, model.indexOf("r1@bad"));
        assertEquals(-1, model.indexOf("total@2026-13"));
        assertEquals(-1, model.indexOf("total@2030-01"));
        assertEquals(-1, model.indexOf("r9@2026-10-05"));
        assertEquals(-1, model.indexOf("|@2026-10-05"));
        assertThrows(IndexOutOfBoundsException.class, () -> model.tooltip(model.rowCount(), "date"));
        for (int i = 0; i < model.rowCount(); i++) {
            assertEquals(i, model.indexOf(model.row(i).rowId()), "indexOf(row(i).rowId) = i");
        }
    }

    /** @return ключ события правила */
    private static OccurrenceKey key(String rule, int year, int month, int day) {
        return new OccurrenceKey(new RuleId(rule), LocalDate.of(year, month, day));
    }

    /** @return строка по id с проверкой, что она видна */
    private static TableRowView row(TableModel model, String rowId) {
        int index = model.indexOf(rowId);
        assertTrue(index >= 0, rowId);
        TableRowView row = model.row(index);
        assertEquals(rowId, row.rowId());
        return row;
    }

    /** @return баланс после строки прогноза без валюты */
    private static String balance(AppState state, String rowId) {
        return ViewStates.forecast(state).findRow(rowId).orElseThrow().balanceAfter().format();
    }

    /** @return id первых строк модели */
    private static List<String> rowIds(TableModel model, int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> model.row(i).rowId()).toList();
    }
}
