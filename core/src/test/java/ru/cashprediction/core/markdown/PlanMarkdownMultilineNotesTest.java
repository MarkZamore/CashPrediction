package ru.cashprediction.core.markdown;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.cashprediction.core.model.*;
import static org.junit.jupiter.api.Assertions.*;

/** Примечания правил, разовых операций и корректировок сохраняются без потери переносов и литералов. */
class PlanMarkdownMultilineNotesTest {
    /** Отдельные LF, CRLF и CR, смешанные переносы и конфликтующие с протоколом литералы. */
    static Stream<String> notes() {
        return Stream.of("first\nsecond", "first\r\nsecond", "first\rsecond",
                "first\r\nsecond\rthird\nfourth",
                "first | \\| C:\\Users\\Oscar\\notes\\n\n&lt; &amp; &#13; <br> <tag> > last",
                "first\n\nsecond\r\n\r\nthird\r\rlast");
    }

    /** Полный писатель/читатель плана сохраняет три независимых вида примечаний и стабильные байты. */
    @ParameterizedTest @MethodSource("notes")
    void allTransactionNotesRoundTripExactly(String note) {
        Plan base = PlanMarkdownReader.read(PlanSamples.FAMILY_BUDGET, "unused", PlanSamples.TODAY).plan();
        var rule = base.rules().getFirst();
        var amended = new RecurringRule(rule.id(), rule.title(), rule.kind(), rule.amount(), rule.category(),
                rule.recurrence(), rule.from(), rule.until(), rule.weekendPolicy(), rule.enabled(), note);
        var oneTime = new OneTimeTransaction(new TxId("t1"), LocalDate.of(2026, 12, 20), "Bonus", Kind.INCOME,
                Money.ofMajor(100), "Category", note);
        var adjustment = new Adjustment(new OccurrenceKey(rule.id(), LocalDate.of(2026, 12, 5)),
                new Adjustment.ChangeAmount(Money.ofMajor(200)), note);
        Plan original = base.withRules(List.of(amended)).withOneTimes(List.of(oneTime)).withAdjustments(List.of(adjustment));
        String encoded = PlanMarkdownWriter.write(original);
        var read = PlanMarkdownReader.read(encoded, "unused", PlanSamples.TODAY);
        assertTrue(read.diagnostics().isEmpty(), () -> read.diagnostics().toString());
        assertEquals(note, read.plan().rules().getFirst().note());
        assertEquals(note, read.plan().oneTimes().getFirst().note());
        assertEquals(note, read.plan().adjustments().getFirst().note());
        assertEquals(original, read.plan());
        assertEquals(encoded, PlanMarkdownWriter.write(read.plan()), "Second encoding must remain byte-identical");
        long firstRows = encoded.lines().filter(line -> line.startsWith("| ") && line.contains("first")).count();
        assertEquals(3, firstRows, "Each multiline note must occupy exactly one physical table row");
    }
}
