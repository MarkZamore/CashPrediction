package ru.cashprediction.core.ui.view.table;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.cashprediction.core.ui.view.table.ViewStates.TODAY;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.WeekendPolicy;

/**
 * Бюджет ленивой таблицы (архитектура §3.4, этап S1 core-views): план на 600 месяцев с еженедельными правилами -
 * около 200 000 строк - строит индекс быстрее 300 мс, строка в среднем отдаётся быстрее 1 мс; LRU-кэш держит
 * {@value LazyTableModel#CACHE_SIZE} последних строк.
 *
 * <p>Время меряется лучшим из нескольких прогонов после разогрева: так проверка не зависит от JIT и случайной
 * нагрузки машины, но честно ловит алгоритм, который материализует тексты всех строк.</p>
 */
class TableModelPerformanceTest {

    /** Бюджет построения индекса. */
    private static final long INDEX_BUDGET_NANOS = 300_000_000L;
    /** Бюджет средней выдачи строки. */
    private static final long ROW_BUDGET_NANOS = 1_000_000L;
    /** Еженедельных правил: 77 × ≈2 609 недель ≈ 200 900 строк. */
    private static final int WEEKLY_RULES = 77;

    private static AppState state;
    private static int forecastRows;

    @BeforeAll
    static void bigPlan() {
        List<RecurringRule> rules = new ArrayList<>();
        for (int i = 0; i < WEEKLY_RULES; i++) {
            rules.add(new RecurringRule(new RuleId("r" + (i + 1)), "Правило " + (i + 1),
                    i % 2 == 0 ? Kind.INCOME : Kind.EXPENSE, Money.ofMajor(100 + i), "",
                    new Recurrence.Weekly(DayOfWeek.of(i % 7 + 1), 1), null, null, WeekendPolicy.NONE, true, ""));
        }
        // Начало в прошлом: индекс строит и группу прошедших, и итоги всех 600 месяцев.
        Plan plan = new Plan("Большой план", "", Plan.DEFAULT_CURRENCY, TODAY.minusMonths(6), Money.ofMajor(1_000_000),
                new Horizon.Months(Horizon.MAX_MONTHS), Money.ZERO, null, rules, List.of(), List.of(), List.of());
        state = ViewStates.of(plan, TODAY, ViewState.defaults().withPeriod(PeriodChoice.ALL), true, "");
        forecastRows = ViewStates.forecast(state).rows().size();
    }

    @Test
    void indexOfAbout200kRowsBuildsUnder300ms() {
        assertTrue(forecastRows > 190_000 && forecastRows < 210_000, "строк прогноза: " + forecastRows);
        long best = Long.MAX_VALUE;
        TableModel model = null;
        for (int run = 0; run < 6; run++) {
            long start = System.nanoTime();
            model = LazyTableModel.build(state, run);
            best = Math.min(best, System.nanoTime() - start);
        }
        // Все строки прогноза видны (группа раскрыта), плюс группа прошедших и 601 итог:
        // начальный неполный месяц и 600 месяцев горизонта.
        assertEquals(forecastRows + 1 + Horizon.MAX_MONTHS + 1, model.rowCount());
        assertTrue(best < INDEX_BUDGET_NANOS, "индекс строится " + best / 1_000_000 + " мс");
    }

    @Test
    void averageRowFetchUnder1ms() {
        TableModel warm = LazyTableModel.build(state, 1);
        for (int i = 0; i < warm.rowCount(); i += 7) {
            warm.row(i);
        }
        TableModel model = LazyTableModel.build(state, 2);
        int fetched = 0;
        long start = System.nanoTime();
        for (int i = 0; i < model.rowCount(); i += 10) {
            model.row(i);
            fetched++;
        }
        long average = (System.nanoTime() - start) / fetched;
        assertTrue(average < ROW_BUDGET_NANOS, "строка в среднем за " + average + " нс");
    }

    @Test
    void lastRowsAreFoundByIdInBigPlan() {
        TableModel model = LazyTableModel.build(state, 3);
        int last = model.rowCount() - 1;
        assertEquals(RowKind.MONTH_TOTAL, model.row(last).kind());
        for (int index : new int[] {0, 1, 2, last / 2, last - 1, last}) {
            assertEquals(index, model.indexOf(model.row(index).rowId()), "строка " + index);
        }
    }

    @Test
    void lruCacheKeepsRecentRows() {
        TableModel model = LazyTableModel.build(state, 4);
        TableRowView first = model.row(0);
        assertSame(first, model.row(0), "повторная выдача берётся из кэша");
        for (int i = 1; i <= LazyTableModel.CACHE_SIZE; i++) {
            model.row(i);
        }
        TableRowView again = model.row(0);
        assertNotSame(first, again, "самая давняя строка вытеснена после " + LazyTableModel.CACHE_SIZE + " других");
        assertEquals(first, again);
        assertSame(model.row(LazyTableModel.CACHE_SIZE), model.row(LazyTableModel.CACHE_SIZE));
    }
}
