package ru.cashprediction.core.ui.view.table;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.SamplePlan;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.ui.token.ColorToken;

/** Проверяет эффективный жирный баланс из метаданных колонки без изменения хранимых стилей (§5.2). */
class BalanceColumnStyleTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    @Test void balanceIsEffectivelyBoldForEveryRowKindAndStoredStylesArePreserved() {
        AppState state = state(false, "");
        TableModel model = LazyTableModel.build(state, 1);
        EnumSet<RowKind> kinds = EnumSet.noneOf(RowKind.class);
        Map<String, ForecastRow> forecast = forecastRows(state);
        TableRows original = new TableRows(state.document().plan(), TODAY);
        for (int i = 0; i < model.rowCount(); i++) {
            TableRowView actual = model.row(i);
            kinds.add(actual.kind());
            TableRowView before;
            if (actual.kind() == RowKind.PAST_HEADER) {
                int count = (int) forecast.values().stream().filter(row -> original.isPast(row)).count();
                before = TableRows.pastHeader(count, true);
            } else if (actual.kind() == RowKind.MONTH_TOTAL) {
                YearMonth month = YearMonth.parse(actual.rowId().substring(LazyTableModel.TOTAL_ROW_ID_PREFIX.length()));
                boolean past = original.isPast(forecast.get(model.row(i - 1).rowId()));
                before = original.total(month, state.document().forecast().summary().byMonth().get(month), past);
            } else before = original.event(forecast.get(actual.rowId()));
            assertEquals(before, actual, "stored row and cell styles remain unchanged");
            assertTrue(effectiveBalanceBold(model, actual), actual.rowId());
            assertSame(actual, model.row(i), "lazy cache");
        }
        assertEquals(EnumSet.allOf(RowKind.class), kinds);
        var balanceColumn = model.columns().stream().filter(column -> column.id().equals("balance")).findFirst().orElseThrow();
        assertEquals(ColumnSpec.Align.RIGHT, balanceColumn.align());
        assertTrue(balanceColumn.bold());
    }

    @Test void negativeAndPastBalancesKeepTheirColorsAndItalics() {
        AppState state = state(true, "");
        TableModel model = LazyTableModel.build(state, 2);
        TableRowView negative = row(model, "r4@2026-10-17");
        assertEquals(ColorToken.NEGATIVE_BG, negative.rowStyle().background());
        assertEquals(new CellStyle(ColorToken.EXPENSE, false, false, false), negative.cellStyles().get("balance"));
        assertTrue(effectiveBalanceBold(model, negative));
        TableRowView past = row(model, "r3@2026-09-01");
        assertNull(past.cellStyles().get("balance"));
        assertEquals(ColorToken.TEXT_PAST, past.rowStyle().text());
        assertTrue(effectiveBalanceBold(model, past));
        TableRowView start = row(model, "start");
        assertTrue(start.cellStyles().get("balance").italic());
        assertEquals(ColorToken.EXPENSE, start.cellStyles().get("balance").text());
        assertTrue(effectiveBalanceBold(model, start));
    }

    @Test void selectionDoesNotChangeModelStylesOrBalanceBoldness() {
        TableModel plain = LazyTableModel.build(state(true, ""), 3);
        TableModel selected = LazyTableModel.build(state(true, "r4@2026-10-17"), 4);
        assertEquals("r4@2026-10-17", selected.selectedRowId());
        assertEquals(plain.rowCount(), selected.rowCount());
        for (int i = 0; i < plain.rowCount(); i++) assertEquals(plain.row(i), selected.row(i));
        assertTrue(effectiveBalanceBold(selected, row(selected, selected.selectedRowId())));
    }

    @Test void ordinaryBackgroundRetainsDocumentedSurfaceFallback() {
        assertNull(RowStyle.PLAIN.background());
        TableRowView ordinary = row(LazyTableModel.build(state(false, ""), 5), "r4@2026-10-17");
        assertNull(ordinary.rowStyle().background(), "null means the table bg.surface, not a new row override");
    }

    private static AppState state(boolean negative, String selected) {
        Plan plan = SamplePlan.create(ViewStates.TODAY).withAdjustmentPut(new Adjustment(
                new OccurrenceKey(new RuleId("r4"), LocalDate.of(2026, 10, 24)), new Adjustment.Skip(), ""));
        if (negative) plan = plan.withStart(plan.startDate(), Money.ofMajor(-2_000_000));
        ViewState view = ViewState.defaults().withShowSkipped(true).withWhatIf(
                new WhatIf(BigDecimal.ONE, BigDecimal.ONE, Money.ofMajor(5_000)));
        return ViewStates.of(plan, TODAY, view, true, selected);
    }

    private static Map<String, ForecastRow> forecastRows(AppState state) {
        Map<String, ForecastRow> rows = new HashMap<>();
        state.document().forecast().rows().forEach(row -> rows.put(row.rowId(), row));
        return rows;
    }

    private static TableRowView row(TableModel model, String id) { return model.row(model.indexOf(id)); }

    private static boolean effectiveBalanceBold(TableModel model, TableRowView row) {
        ColumnSpec column = model.columns().stream().filter(c -> c.id().equals("balance")).findFirst().orElseThrow();
        CellStyle cell = row.cellStyles().get(column.id());
        return column.bold() || row.rowStyle().bold() || cell != null && cell.bold();
    }
}
