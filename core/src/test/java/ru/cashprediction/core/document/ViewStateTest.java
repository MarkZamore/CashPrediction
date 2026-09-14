package ru.cashprediction.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.forecast.Flags;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;

/**
 * Тесты параметров отображения: связь с настройками, фильтры строк и границы периода.
 */
class ViewStateTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final Flags SKIPPED = new Flags(false, false, false, true, false, false);

    /** Строка прогноза с заданным источником, типом и текстами. */
    private static ForecastRow row(Origin origin, Kind kind, String title, String category, String note, Flags flags) {
        LocalDate date = LocalDate.of(2026, 10, 5);
        return new ForecastRow(date, date, title, kind, category, kind.signed(Money.ofMajor(100)), Money.ofMajor(1_000),
                origin, origin == Origin.RULE ? new RuleId("r1") : null, origin == Origin.ONE_TIME ? new TxId("t1") : null,
                flags, note);
    }

    private static final ForecastRow START_ROW = row(Origin.START, Kind.INCOME, "Начальный баланс", "", "", Flags.NONE);
    private static final ForecastRow SALARY = row(Origin.RULE, Kind.INCOME, "Зарплата", "Работа", "", Flags.NONE);
    private static final ForecastRow RENT = row(Origin.RULE, Kind.EXPENSE, "Аренда", "Жильё", "до переезда", Flags.NONE);
    private static final ForecastRow BONUS = row(Origin.ONE_TIME, Kind.INCOME, "Премия", "Работа", "", Flags.NONE);
    private static final ForecastRow SAVING = row(Origin.WHAT_IF, Kind.INCOME, "Доп. экономия (что-если)", "", "", Flags.NONE);
    private static final ForecastRow SKIPPED_FOOD = row(Origin.RULE, Kind.EXPENSE, "Продукты", "Еда", "", SKIPPED);

    @Test
    void defaultsMatchAppSettingsDefaults() {
        ViewState d = ViewState.defaults();
        assertEquals(ViewMode.TABLE, d.mode());
        assertEquals(PeriodChoice.M12, d.period());
        assertTrue(d.showIncome());
        assertTrue(d.showExpense());
        assertTrue(d.showOneTime());
        assertFalse(d.showSkipped());
        assertTrue(d.monthTotals());
        assertTrue(d.chartMarkers());
        assertFalse(d.chartBars());
        assertTrue(d.summaryPanel());
        assertEquals("", d.filterText());
        assertEquals(WhatIf.NONE, d.whatIf());
        assertEquals(d, new ViewState(null, null, true, true, true, false, true, true, false, true, null, null));
        assertEquals(AppSettings.defaults(), d.applyTo(AppSettings.defaults()));
    }

    @Test
    void settingsRoundTripKeepsNonViewSettings() {
        AppSettings custom = AppSettings.defaults()
                .withPlanOpened("Бюджет.md")
                .withRecoveryStore(RecoveryStoreKind.XML)
                .withAutosave(true)
                .withView(ViewMode.CHART)
                .withPeriod(PeriodChoice.M3)
                .withShowIncome(false)
                .withShowExpense(false)
                .withShowOneTime(false)
                .withShowSkipped(true)
                .withMonthTotals(false)
                .withChartMarkers(false)
                .withChartBars(true)
                .withSummaryPanel(false);
        ViewState view = ViewState.fromSettings(custom);
        assertEquals(ViewMode.CHART, view.mode());
        assertEquals(PeriodChoice.M3, view.period());
        assertTrue(view.showSkipped());
        assertTrue(view.chartBars());
        assertEquals("", view.filterText());
        assertEquals(WhatIf.NONE, view.whatIf());

        // Вид переносится в настройки целиком, а последний план, хранилище и автосохранение цели не трогаются.
        assertEquals(custom, view.applyTo(custom.withView(ViewMode.TABLE).withShowIncome(true)));
        AppSettings other = AppSettings.defaults().withPlanOpened("Другой.md");
        AppSettings applied = view.applyTo(other);
        assertEquals("Другой.md", applied.lastPlan());
        assertEquals(RecoveryStoreKind.REGISTRY, applied.recoveryStore());
        assertFalse(applied.autosave());
        assertEquals(view.withFilterText("поиск").withWhatIf(WhatIf.ofPercent(10, 0, Money.ZERO)).applyTo(other), applied);
        assertEquals(view, ViewState.fromSettings(applied));
    }

    @Test
    void withMethodsChangeOnlyOneComponent() {
        ViewState d = ViewState.defaults();
        WhatIf whatIf = WhatIf.ofPercent(10, -10, Money.ofMajor(5_000));
        assertEquals(ViewMode.CHART, d.withMode(ViewMode.CHART).mode());
        assertEquals(PeriodChoice.ALL, d.withPeriod(PeriodChoice.ALL).period());
        assertFalse(d.withShowIncome(false).showIncome());
        assertFalse(d.withShowExpense(false).showExpense());
        assertFalse(d.withShowOneTime(false).showOneTime());
        assertTrue(d.withShowSkipped(true).showSkipped());
        assertFalse(d.withMonthTotals(false).monthTotals());
        assertFalse(d.withChartMarkers(false).chartMarkers());
        assertTrue(d.withChartBars(true).chartBars());
        assertFalse(d.withSummaryPanel(false).summaryPanel());
        assertEquals("аренда", d.withFilterText("аренда").filterText());
        assertEquals("", d.withFilterText(null).filterText());
        assertEquals(whatIf, d.withWhatIf(whatIf).whatIf());
        assertEquals(WhatIf.NONE, d.withWhatIf(whatIf).withWhatIf(null).whatIf());
        assertEquals(d, d.withMode(ViewMode.CHART).withMode(ViewMode.TABLE));
        assertEquals(d.withShowIncome(false), d.withShowIncome(false).withPeriod(PeriodChoice.M12));
    }

    @Test
    void startRowIsAlwaysVisible() {
        ViewState hideAll = ViewState.defaults().withShowIncome(false).withShowExpense(false).withShowOneTime(false)
                .withFilterText("ничего такого нет");
        assertTrue(hideAll.accepts(START_ROW));
        assertFalse(hideAll.accepts(SALARY));
        assertFalse(hideAll.accepts(RENT));
    }

    @Test
    void kindAndOriginFilters() {
        ViewState d = ViewState.defaults();
        assertTrue(d.accepts(SALARY));
        assertTrue(d.accepts(RENT));
        assertTrue(d.accepts(BONUS));
        assertTrue(d.accepts(SAVING));

        ViewState noIncome = d.withShowIncome(false);
        assertFalse(noIncome.accepts(SALARY));
        assertFalse(noIncome.accepts(BONUS));
        assertFalse(noIncome.accepts(SAVING), "строка «что-если» - это доход");
        assertTrue(noIncome.accepts(RENT));

        ViewState noExpense = d.withShowExpense(false);
        assertTrue(noExpense.accepts(SALARY));
        assertFalse(noExpense.accepts(RENT));

        ViewState noOneTime = d.withShowOneTime(false);
        assertFalse(noOneTime.accepts(BONUS));
        assertTrue(noOneTime.accepts(SALARY));
    }

    @Test
    void skippedRowsNeedShowSkipped() {
        assertFalse(ViewState.defaults().accepts(SKIPPED_FOOD));
        assertTrue(ViewState.defaults().withShowSkipped(true).accepts(SKIPPED_FOOD));
        assertFalse(ViewState.defaults().withShowSkipped(true).withShowExpense(false).accepts(SKIPPED_FOOD));
    }

    @Test
    void textFilterMatchesTitleCategoryAndNoteIgnoringCase() {
        ViewState byTitle = ViewState.defaults().withFilterText("  АРЕНДА ");
        assertTrue(byTitle.accepts(RENT));
        assertFalse(byTitle.accepts(SALARY));

        ViewState byCategory = ViewState.defaults().withFilterText("жилье");
        assertTrue(byCategory.accepts(RENT), "«ё» и «е» не различаются");
        assertFalse(byCategory.accepts(BONUS));

        ViewState byNote = ViewState.defaults().withFilterText("Переезда");
        assertTrue(byNote.accepts(RENT));

        ViewState byWork = ViewState.defaults().withFilterText("раб");
        assertTrue(byWork.accepts(SALARY));
        assertTrue(byWork.accepts(BONUS));
        assertFalse(byWork.accepts(RENT));
        assertFalse(byWork.withShowOneTime(false).accepts(BONUS), "фильтр по тексту не отменяет остальные фильтры");
    }

    @Test
    void periodEndIsCountedFromAnchorAndClampedToPlanEnd() {
        Plan plan = Plan.empty("Тест", START);
        LocalDate planEnd = LocalDate.of(2027, 8, 31);
        assertEquals(planEnd, plan.endDate());
        LocalDate anchor = LocalDate.of(2026, 9, 13);
        ViewState d = ViewState.defaults();
        assertEquals(LocalDate.of(2026, 12, 12), d.withPeriod(PeriodChoice.M3).periodEnd(plan, anchor));
        assertEquals(LocalDate.of(2027, 3, 12), d.withPeriod(PeriodChoice.M6).periodEnd(plan, anchor));
        assertEquals(planEnd, d.withPeriod(PeriodChoice.M12).periodEnd(plan, anchor), "2027-09-12 позже конца плана");
        assertEquals(planEnd, d.withPeriod(PeriodChoice.M24).periodEnd(plan, anchor));
        assertEquals(planEnd, d.withPeriod(PeriodChoice.ALL).periodEnd(plan, anchor));
        assertEquals(LocalDate.of(2026, 11, 30), d.withPeriod(PeriodChoice.M3).periodEnd(plan, START));

        Plan longPlan = plan.withHorizon(new Horizon.Years(5));
        assertEquals(LocalDate.of(2028, 9, 12), d.withPeriod(PeriodChoice.M24).periodEnd(longPlan, anchor));
        assertEquals(LocalDate.of(2031, 8, 31), d.withPeriod(PeriodChoice.ALL).periodEnd(longPlan, anchor));
        // «Сейчас» уже после конца горизонта: видна вся таблица.
        assertEquals(planEnd, d.withPeriod(PeriodChoice.M3).periodEnd(plan, LocalDate.of(2030, 1, 1)));
    }

    @Test
    void onlyWhatIfAndSkippedAffectForecast() {
        ViewState d = ViewState.defaults();
        assertFalse(d.affectsForecast(d.withMode(ViewMode.CHART).withPeriod(PeriodChoice.M3).withShowIncome(false)
                .withFilterText("x").withChartBars(true)));
        assertTrue(d.affectsForecast(d.withShowSkipped(true)));
        assertTrue(d.affectsForecast(d.withWhatIf(WhatIf.ofPercent(0, 0, Money.ofMajor(1)))));
    }
}
