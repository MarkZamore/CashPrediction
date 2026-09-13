package ru.cashprediction.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Тесты настроек приложения и перечислений вида.
 */
class AppSettingsTest {

    @Test
    void defaultsMatchSpecification() {
        AppSettings s = AppSettings.defaults();
        assertEquals("", s.lastPlan());
        assertEquals(List.of(), s.recentPlans());
        assertEquals(RecoveryStoreKind.REGISTRY, s.recoveryStore());
        assertFalse(s.autosave());
        assertEquals(ViewMode.TABLE, s.view());
        assertEquals(PeriodChoice.M12, s.period());
        assertTrue(s.showIncome());
        assertTrue(s.showExpense());
        assertTrue(s.showOneTime());
        assertFalse(s.showSkipped());
        assertTrue(s.monthTotals());
        assertTrue(s.chartMarkers());
        assertFalse(s.chartBars());
        assertTrue(s.summaryPanel());
    }

    @Test
    void nullsAreReplacedWithDefaults() {
        AppSettings s = new AppSettings(null, null, null, false, null, null, true, true, true, false, true, true, false, true);
        assertEquals(AppSettings.defaults(), s);
    }

    @Test
    void recentPlansAreNormalized() {
        List<String> source = new ArrayList<>(Arrays.asList(" a.md ", "A.MD", "", null, "b.md"));
        for (int i = 0; i < 20; i++) {
            source.add("p" + i + ".md");
        }
        AppSettings s = AppSettings.defaults().withRecentPlans(source);
        assertEquals(AppSettings.MAX_RECENT, s.recentPlans().size());
        assertEquals(List.of("a.md", "b.md", "p0.md"), s.recentPlans().subList(0, 3));
    }

    @Test
    void openingPlanMovesItToFront() {
        AppSettings s = AppSettings.defaults().withPlanOpened("a.md").withPlanOpened("b.md").withPlanOpened("A.md");
        assertEquals("A.md", s.lastPlan());
        assertEquals(List.of("A.md", "b.md"), s.recentPlans());
        assertEquals(s, s.withPlanOpened(" "));

        AppSettings removed = s.withRecentPlanRemoved("a.MD");
        assertEquals(List.of("b.md"), removed.recentPlans());
        assertEquals("", removed.lastPlan());
    }

    @Test
    void withMethodsChangeOnlyOneComponent() {
        AppSettings d = AppSettings.defaults();
        assertEquals(RecoveryStoreKind.XML, d.withRecoveryStore(RecoveryStoreKind.XML).recoveryStore());
        assertTrue(d.withAutosave(true).autosave());
        assertEquals(ViewMode.CHART, d.withView(ViewMode.CHART).view());
        assertEquals(PeriodChoice.M3, d.withPeriod(PeriodChoice.M3).period());
        assertFalse(d.withShowIncome(false).showIncome());
        assertFalse(d.withShowExpense(false).showExpense());
        assertFalse(d.withShowOneTime(false).showOneTime());
        assertTrue(d.withShowSkipped(true).showSkipped());
        assertFalse(d.withMonthTotals(false).monthTotals());
        assertFalse(d.withChartMarkers(false).chartMarkers());
        assertTrue(d.withChartBars(true).chartBars());
        assertFalse(d.withSummaryPanel(false).summaryPanel());
        assertEquals("x.md", d.withLastPlan(" x.md ").lastPlan());
        assertEquals(d, d.withChartBars(true).withChartBars(false));
    }

    @Test
    void periodChoices() {
        assertEquals(List.of(3, 6, 12, 24, 0), Arrays.stream(PeriodChoice.values()).map(PeriodChoice::months).toList());
        assertEquals(List.of("3 месяца", "6 месяцев", "12 месяцев", "24 месяца", "Весь горизонт"),
                Arrays.stream(PeriodChoice.values()).map(PeriodChoice::label).toList());
        for (PeriodChoice p : PeriodChoice.values()) {
            assertEquals(Optional.of(p), PeriodChoice.parse(p.label()));
            assertEquals(Optional.of(p), PeriodChoice.parse(p.name()));
        }
        assertEquals(Optional.of(PeriodChoice.M24), PeriodChoice.parse("24"));
        assertEquals(Optional.of(PeriodChoice.M6), PeriodChoice.parse("6 мес"));
        assertEquals(Optional.of(PeriodChoice.ALL), PeriodChoice.parse("весь"));
        assertEquals(Optional.empty(), PeriodChoice.parse("5 месяцев"));
        assertEquals(Optional.empty(), PeriodChoice.parse("0"));
        assertEquals(Optional.empty(), PeriodChoice.parse(null));
    }

    @Test
    void viewModesAndStores() {
        assertEquals("таблица", ViewMode.TABLE.label());
        assertEquals("график", ViewMode.CHART.label());
        assertEquals(Optional.of(ViewMode.CHART), ViewMode.parse(" ГРАФИК "));
        assertEquals(Optional.of(ViewMode.TABLE), ViewMode.parse("table"));
        assertEquals(Optional.empty(), ViewMode.parse("список"));
        assertEquals("реестр", RecoveryStoreKind.REGISTRY.label());
        assertEquals("XML", RecoveryStoreKind.XML.label());
        assertEquals(Optional.of(RecoveryStoreKind.XML), RecoveryStoreKind.parse("xml"));
        assertEquals(Optional.of(RecoveryStoreKind.REGISTRY), RecoveryStoreKind.parse("Реестр"));
        assertEquals(Optional.empty(), RecoveryStoreKind.parse("облако"));
    }

    /**
     * Слова {@code settings.md} читаются обратно из собственной подписи при любом регистре и «ё»: разбор нормализует
     * и текст файла, и слово из грамматики формата.
     */
    @Test
    void settingsWordsRoundTripInAnyCase() {
        for (ViewMode mode : ViewMode.values()) {
            assertEquals(Optional.of(mode), ViewMode.parse(mode.label()), mode.name());
            assertEquals(Optional.of(mode), ViewMode.parse(" " + mode.label().toUpperCase(java.util.Locale.ROOT) + " "));
        }
        for (PeriodChoice p : PeriodChoice.values()) {
            assertEquals(Optional.of(p), PeriodChoice.parse(p.label().toUpperCase(java.util.Locale.ROOT)), p.name());
        }
        for (RecoveryStoreKind kind : RecoveryStoreKind.values()) {
            assertEquals(Optional.of(kind), RecoveryStoreKind.parse(kind.label()), kind.name());
            assertEquals(Optional.of(kind), RecoveryStoreKind.parse(kind.label().toLowerCase(java.util.Locale.ROOT)));
        }
        assertEquals(Optional.of(PeriodChoice.ALL), PeriodChoice.parse("ВСЁ"));
        assertEquals(Optional.of(PeriodChoice.ALL), PeriodChoice.parse("Весь"));
        assertEquals(Optional.of(PeriodChoice.ALL), PeriodChoice.parse("весь  ГОРИЗОНТ"));
    }
}
