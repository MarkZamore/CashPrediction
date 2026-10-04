package ru.cashprediction.core.markdown;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.model.Plan;

/** Версия ячеек определяется грамматикой параметров, а не содержимым таблицы или порядком секций. */
class PlanCellCodecVersionTest {
    /** Старый вход и вход без версии сохраняют литералы; запись обновляет только представление. */
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void oldAndUnmarkedPlansKeepLiteralNotes(boolean unmarked) {
        String text = unmarked ? PlanCellCodecFixtures.V1.replace("- Формат: CashPrediction 1\n", "") : PlanCellCodecFixtures.V1;
        ReadResult old = read(text);
        assertTrue(old.diagnostics().isEmpty(), () -> old.diagnostics().toString());
        assertNotes(old.plan(), PlanCellCodecFixtures.NOTE);
        String migrated = PlanMarkdownWriter.write(old.plan());
        assertTrue(migrated.contains("- Формат: CashPrediction 2"));
        assertTrue(migrated.contains("&lt;br&gt; &amp;lt; &amp;#13; &amp;amp;"));
        assertEquals(old.plan(), read(migrated).plan());
        assertEquals(migrated, PlanMarkdownWriter.write(read(migrated).plan()));
    }

    /** Новые переносы и буквальные токены сохраняются независимо от расположения параметров. */
    @Test void versionTwoAfterTablesAndWithNonCanonicalHeading() {
        String note = PlanCellCodecFixtures.NOTE + "\nLF\rCR\r\nCRLF";
        Plan original = PlanCellCodecFixtures.withNotes(note);
        String encoded = PlanMarkdownWriter.write(original);
        int start = encoded.indexOf("## Параметры");
        int end = encoded.indexOf("## Регулярные операции");
        String reordered = encoded.substring(0, start) + encoded.substring(end)
                + "\n" + encoded.substring(start, end);
        assertEquals(original, read(reordered).plan());
        ReadResult nonCanonical = read(reordered.replace("## Параметры", "### Параметры"));
        assertEquals(original, nonCanonical.plan());
        assertEquals(1, nonCanonical.diagnostics().size());
    }

    /** Последнее значение побеждает, а предупреждение о повторе создаётся только основным проходом. */
    @Test void repeatedFormatUsesLastValueForEveryTable() {
        String before = PlanCellCodecFixtures.V1.replace("CashPrediction 1", "CashPrediction 2");
        ReadResult literal = read(before + "\n## Параметры\n- Формат: CashPrediction 1\n");
        assertNotes(literal.plan(), PlanCellCodecFixtures.NOTE);
        assertEquals(2, literal.diagnostics().size());
        ReadResult decoded = read(PlanCellCodecFixtures.V1 + "\n## Параметры\n- Формат: CashPrediction 2\n");
        assertNotes(decoded.plan(), "literal \n < \r & > | \\| C:\\Users\\Oscar\\notes\\n");
        assertEquals(2, decoded.diagnostics().size());
    }

    /** Некорректное последнее значение также отменяет прежнее разрешение декодировать ячейки. */
    @Test void malformedLastParameterOverridesVersionTwo() {
        String text = PlanCellCodecFixtures.V1.replace("CashPrediction 1", "CashPrediction 2")
                + "\n## Параметры\n- Формат: invalid\n";
        ReadResult result = read(text);
        assertNotes(result.plan(), PlanCellCodecFixtures.NOTE);
        assertEquals(3, result.diagnostics().size());
    }

    /** Маркер в заметке или неизвестной секции не меняет буквальные ячейки. */
    @Test void noteAndUnknownSectionCannotSetCodec() {
        String text = PlanCellCodecFixtures.V1.replace("- Формат: CashPrediction 1\n", "")
                + "\n## Заметка\n- Формат: CashPrediction 2\n\n## Unknown\n- Формат: CashPrediction 2\n";
        ReadResult result = read(text);
        assertNotes(result.plan(), PlanCellCodecFixtures.NOTE);
        assertEquals(1, result.diagnostics().size());
        assertEquals("- Формат: CashPrediction 2", result.plan().note());
        assertEquals(1, result.plan().rawBlocks().size());
    }

    /** Некорректная и будущая версия не разрешают угадывать новый кодек по тексту ячеек. */
    @ParameterizedTest @ValueSource(strings = {"invalid", "CashPrediction 3", "CashPrediction 999999999"})
    void invalidAndFutureVersionsStayLiteral(String version) {
        ReadResult result = read(PlanCellCodecFixtures.V1.replace("CashPrediction 1", version));
        assertNotes(result.plan(), PlanCellCodecFixtures.NOTE);
        assertEquals(1, result.diagnostics().size());
    }

    /** Исторический эталон отдельно читается, актуальная запись соответствует версии 2. */
    @Test void historicalSampleMigratesToCanonicalVersionTwo() {
        assertEquals(PlanSamples.FAMILY_BUDGET,
                PlanMarkdownWriter.write(read(PlanSamples.FAMILY_BUDGET_V1).plan()));
    }

    private static ReadResult read(String text) {
        return PlanMarkdownReader.read(text, "unused", PlanCellCodecFixtures.TODAY);
    }
    private static void assertNotes(Plan plan, String note) {
        assertEquals(note, plan.rules().getFirst().note());
        assertEquals(note, plan.oneTimes().getFirst().note());
        assertEquals(note, plan.adjustments().getFirst().note());
    }
}
