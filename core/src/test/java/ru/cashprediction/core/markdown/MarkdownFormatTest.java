package ru.cashprediction.core.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * Тесты словаря формата: экранирование строк заметки, пометка неразобранной строки, справка.
 */
class MarkdownFormatTest {

    @Test
    void noteLineEscapingIsReversible() {
        String[] lines = {"", "текст", "# заголовок", "## секция", "###", "\\# косая", "\\\\## две косые",
            "   ## с отступом", "\\ не решётка", "a # b", "#"};
        for (String line : lines) {
            String escaped = MarkdownFormat.escapeNoteLine(line);
            assertFalse(PlanMarkdownReader.isSectionHeading(escaped), "экранированная строка не заголовок: " + escaped);
            assertEquals(line, MarkdownFormat.unescapeNoteLine(escaped), line);
        }
        assertEquals("\\## секция", MarkdownFormat.escapeNoteLine("## секция"));
        assertEquals("  \\# x", MarkdownFormat.escapeNoteLine("  # x"));
        assertEquals("текст", MarkdownFormat.escapeNoteLine("текст"));
        assertEquals("\\ не решётка", MarkdownFormat.unescapeNoteLine("\\ не решётка"));
    }

    @Test
    void unparsedMarkAndFormatValue() {
        assertEquals("(не разобрано, строка 27: | r9 | x |)", MarkdownFormat.unparsedMark(27, "  | r9 | x |  "));
        assertEquals("CashPrediction 1", MarkdownFormat.formatValue());
    }

    @Test
    void userGuideIsNeverBlank() {
        // В изолированной сборке ресурсов на пути классов нет: тогда возвращается понятное сообщение.
        assertFalse(MarkdownFormat.userGuide().isBlank());
    }
}
