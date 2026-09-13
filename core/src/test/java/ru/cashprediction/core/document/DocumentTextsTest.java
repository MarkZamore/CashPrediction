package ru.cashprediction.core.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.WeekendPolicy;

/**
 * Этап S0.5: слова файла настроек (период, вид, хранилище) берутся из грамматики формата, а тексты документа
 * (названия добавляемых операций, описания шагов истории, ошибки команд) — из каталога
 * ({@code document_ru.properties}); значения совпадают с прежними русскими строками кода буква в букву.
 */
class DocumentTextsTest {

    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);

    /** Слова settings.md и их распознавание, включая короткие синонимы. */
    @Test
    void settingsWords() {
        assertEquals(List.of("3 месяца", "6 месяцев", "12 месяцев", "24 месяца", "Весь горизонт"),
                List.of(PeriodChoice.values()).stream().map(PeriodChoice::label).toList());
        assertEquals(Optional.of(PeriodChoice.ALL), PeriodChoice.parse("весь"));
        assertEquals(Optional.of(PeriodChoice.ALL), PeriodChoice.parse("Все"));
        assertEquals(Optional.of(PeriodChoice.ALL), PeriodChoice.parse("весь горизонт"));
        assertEquals(Optional.of(PeriodChoice.M12), PeriodChoice.parse("12 месяцев"));
        assertEquals(List.of("таблица", "график"), List.of(ViewMode.values()).stream().map(ViewMode::label).toList());
        assertEquals(Optional.of(ViewMode.CHART), ViewMode.parse("График"));
        assertEquals(List.of("реестр", "XML"), List.of(RecoveryStoreKind.values()).stream().map(RecoveryStoreKind::label).toList());
        assertEquals(Optional.of(RecoveryStoreKind.REGISTRY), RecoveryStoreKind.parse("Реестр"));
    }

    /** Названия добавляемых операций и описания шагов истории. */
    @Test
    void titlesAndHistoryDescriptions() {
        assertEquals("Сверка баланса", PlanDocument.RECONCILE_TITLE);
        assertEquals("Доп. экономия", PlanDocument.EXTRA_SAVING_TITLE);

        PlanDocument actualized = document(List.of());
        actualized.actualize(TODAY, null);
        assertEquals(Optional.of("Актуализация на 13.09.2026"), actualized.undoDescription());

        PlanDocument reconciled = document(List.of());
        reconciled.reconcile(TODAY, Money.ofMajor(2000));
        assertEquals(Optional.of("Сверка баланса"), reconciled.undoDescription());
        assertEquals("Прогноз: " + Money.ofMajor(1100).format("₽") + ", факт: " + Money.ofMajor(2000).format("₽"),
                reconciled.plan().oneTimes().getFirst().note());

        PlanDocument applied = document(List.of());
        applied.setViewState(applied.viewState().withWhatIf(WhatIf.ofPercent(10, 0, Money.ZERO)));
        applied.applyWhatIfToPlan();
        assertEquals(Optional.of("Применение «что-если» к плану"), applied.undoDescription());

        PlanDocument cleaned = document(List.of(new Adjustment(new OccurrenceKey(new RuleId("r1"), LocalDate.of(2026, 10, 6)),
                new Adjustment.Skip(), "")));
        assertEquals(1, cleaned.removeOrphanAdjustments());
        assertEquals(Optional.of("Удаление неиспользуемых корректировок: 1"), cleaned.undoDescription());
    }

    /** Ошибки команд «Сверить баланс» и «Применить что-если». */
    @Test
    void commandErrors() {
        PlanDocument doc = document(List.of());
        assertEquals("Сверить баланс можно только на дату внутри горизонта прогноза (01.09.2026 – 31.08.2027)",
                assertThrows(IllegalArgumentException.class, () -> doc.reconcile(LocalDate.of(2030, 1, 1), Money.ZERO)).getMessage());

        assertEquals("Нельзя применить «что-если»: сумма правила r1 «Копейка» округляется до нуля, а у его корректировки от 05.10.2026 "
                + "сумма остаётся. Выберите другой процент изменения", roundsToZeroMessage("Копейка"));
        assertEquals("Нельзя применить «что-если»: сумма правила r1 округляется до нуля, а у его корректировки от 05.10.2026 "
                + "сумма остаётся. Выберите другой процент изменения", roundsToZeroMessage(""));
    }

    /** @return сообщение об ошибке применения «что-если» к правилу в одну копейку с названием {@code title} */
    private static String roundsToZeroMessage(String title) {
        RecurringRule tiny = new RecurringRule(new RuleId("r1"), title, Kind.INCOME, Money.ofMinor(1), "",
                new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.NONE, true, "");
        Plan plan = new Plan("Тест", "", "₽", START, Money.ZERO, new Horizon.Months(12), Money.ZERO, null, List.of(tiny), List.of(),
                List.of(new Adjustment(new OccurrenceKey(tiny.id(), LocalDate.of(2026, 10, 5)),
                        new Adjustment.ChangeAmount(Money.ofMajor(100)), "")), List.of());
        PlanDocument doc = new PlanDocument(plan, null, () -> TODAY);
        doc.setViewState(doc.viewState().withWhatIf(WhatIf.ofPercent(-51, 0, Money.ZERO)));
        return assertThrows(IllegalArgumentException.class, doc::applyWhatIfToPlan).getMessage();
    }

    /** @return документ с планом: 1000 ₽ на начало, зарплата 100 ₽ пятого числа, заданные корректировки */
    private static PlanDocument document(List<Adjustment> adjustments) {
        RecurringRule salary = new RecurringRule(new RuleId("r1"), "Зарплата", Kind.INCOME, Money.ofMajor(100), "",
                new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.NONE, true, "");
        Plan plan = new Plan("Тест", "", "₽", START, Money.ofMajor(1000), new Horizon.Months(12), Money.ZERO, null,
                List.of(salary), List.of(), adjustments, List.of());
        return new PlanDocument(plan, null, () -> TODAY);
    }
}
