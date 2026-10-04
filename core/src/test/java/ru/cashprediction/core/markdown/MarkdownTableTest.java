package ru.cashprediction.core.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Тесты разбора и выравнивания таблиц Markdown.
 */
class MarkdownTableTest {

    @Test
    void parsesCellsWithEscapesAndMissingBorders() {
        assertEquals(List.of("a", "b|c", ""), MarkdownTable.parseRow("| a | b\\|c |  |"));
        assertEquals(List.of("a", "b"), MarkdownTable.parseRow("|a|b"));
        assertEquals(List.of("a", "b"), MarkdownTable.parseRow("   | a | b |   "));
        assertEquals(List.of("C:\\dir\\", "x"), MarkdownTable.parseRow("| C:\\dir\\ | x |"));
        assertEquals(List.of(""), MarkdownTable.parseRow("||"));
        assertEquals(List.of(), MarkdownTable.parseRow("|"));
    }

    @Test
    void detectsRows() {
        assertTrue(MarkdownTable.isTableRow("  | a |"));
        assertFalse(MarkdownTable.isTableRow("a | b"));
        assertFalse(MarkdownTable.isTableRow(null));
        assertTrue(MarkdownTable.isSeparatorRow("|---|:--:|"));
        assertTrue(MarkdownTable.isSeparatorRow("| --- | ---: |"));
        assertTrue(MarkdownTable.isSeparatorRow("|----|----"));
        assertFalse(MarkdownTable.isSeparatorRow("| a | --- |"));
        assertFalse(MarkdownTable.isSeparatorRow("---"));
        assertFalse(MarkdownTable.isSeparatorRow("||"));
        assertFalse(MarkdownTable.isSeparatorRow("| |"));
    }

    @Test
    void formatsAlignedTable() {
        List<String> lines = MarkdownTable.format(List.of("ID", "Название"),
                List.of(List.of("r1", "Страховка авто"), List.of("r10", "a|b")));
        assertEquals(List.of(
                "| ID  | Название       |",
                "|-----|----------------|",
                "| r1  | Страховка авто |",
                "| r10 | a\\|b           |"), lines);
    }

    @Test
    void shortRowsArePaddedAndLongRowsTrimmed() {
        List<String> lines = MarkdownTable.format(List.of("A", "B"), List.of(List.of("1"), List.of("1", "2", "3")));
        assertEquals(List.of("| A | B |", "|---|---|", "| 1 |   |", "| 1 | 2 |"), lines);
    }

    @Test
    void widthCountsCodePoints() {
        // Символ вне BMP занимает два char, но одну позицию.
        List<String> lines = MarkdownTable.format(List.of("Эмодзи"), List.of(List.of("\uD83D\uDCB0\uD83D\uDCB0\uD83D\uDCB0\uD83D\uDCB0\uD83D\uDCB0\uD83D\uDCB0\uD83D\uDCB0")));
        assertEquals("| Эмодзи  |", lines.get(0));
    }

    @Test
    void escapeCellPreservesLineBreaksWithReadableTokens() {
        assertEquals("a<br>b&#13;<br>c \\| d", MarkdownTable.escapeCell("a\nb\r\nc | d"));
        assertEquals("", MarkdownTable.escapeCell(null));
    }

    /** Переносы и буквальные сущности проходят один цикл без рекурсивного HTML-декодирования. */
    @Test void multilineAndLiteralTokensRoundTripExactly() {
        for (String value : List.of("a\nb", "a\r\nb", "a\rb", "\na\r\n", "&lt; &amp; &#13; <br> <script>",
                "C:\\Users\\Oscar\\file | \\| \\n", "a\r\n\rb\n\nc")) {
            String encoded = MarkdownTable.escapeCell(value);
            assertFalse(encoded.contains("\n")); assertFalse(encoded.contains("\r"));
            assertEquals(List.of(value), MarkdownTable.parseRow("| " + encoded + " |"));
        }
        assertEquals("&amp;lt; &lt;br&gt; &amp;#13;", MarkdownTable.escapeCell("&lt; <br> &#13;"));
    }

    /** Декодер распознаёт только точные токены этого протокола, не все HTML-сущности и теги. */
    @Test void decoderDoesNotExecuteOrRecursivelyInterpretText() {
        assertEquals(List.of("&lt;", "<br>", "&#13;", "&unknown; <br/> <BR>"),
                MarkdownTable.parseRow("| &amp;lt; | &lt;br&gt; | &amp;#13; | &unknown; <br/> <BR> |"));
    }

    @Test
    void formatThenParseRoundTrip() {
        List<String> row = List.of("r1", "Кафе | бар", "", "C:\\путь\\", "  ");
        List<String> lines = MarkdownTable.format(List.of("1", "2", "3", "4", "5"), List.of(row));
        assertTrue(MarkdownTable.isSeparatorRow(lines.get(1)));
        assertEquals(List.of("r1", "Кафе | бар", "", "C:\\путь\\", ""), MarkdownTable.parseRow(lines.get(2)));
    }
}
