package ru.cashprediction.core.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.text.CoreModuleDir;
import ru.cashprediction.core.text.TextCatalog;
import ru.cashprediction.core.text.Texts;

/**
 * Тесты словаря формата: экранирование строк заметки, пометка неразобранной строки, справка о формате.
 *
 * <p>Справка «Формат файла .md» — текст интерфейса (решение L13): она лежит одним документом с суффиксом языка рядом
 * с областями каталога текстов и ищется по языку, как и области.</p>
 */
class MarkdownFormatTest {

    /** Единственное допустимое место справки о формате среди ресурсов ядра. */
    private static final String GUIDE_PATH = "ru/cashprediction/core/ui/text/help-format_ru.md";

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
        assertEquals("ID", MarkdownFormat.COL_ID);
    }

    @Test
    void userGuideIsNeverBlank() {
        assertFalse(MarkdownFormat.userGuide().isBlank());
    }

    /** Справка одна, с суффиксом языка, в папке каталога текстов; прежнего FORMAT.md без суффикса нет. */
    @Test
    void userGuideIsTheSingleLocaleSuffixedCatalogueDocument() throws IOException {
        Path resources = CoreModuleDir.resolve("src/main/resources");
        List<String> documents;
        try (Stream<Path> files = Files.walk(resources)) {
            documents = files.filter(Files::isRegularFile)
                    .map(file -> resources.relativize(file).toString().replace('\\', '/'))
                    .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".md"))
                    .toList();
        }
        assertEquals(List.of(GUIDE_PATH), documents, "справка о формате — один документ с суффиксом языка");
        assertFalse(Files.exists(resources.resolve("ru/cashprediction/core/FORMAT.md")), "FORMAT.md без суффикса удалён");

        assertEquals("help-format_" + Texts.LANGUAGE + ".md",
                Texts.documentFileName(MarkdownFormat.USER_GUIDE_NAME, MarkdownFormat.USER_GUIDE_EXTENSION));
        assertEquals("/" + GUIDE_PATH, MarkdownFormat.USER_GUIDE_RESOURCE);

        byte[] bytes = Files.readAllBytes(resources.resolve(GUIDE_PATH));
        String expected = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        expected = expected.startsWith("﻿") ? expected.substring(1) : expected;
        assertEquals(expected, MarkdownFormat.userGuide(), "справка загружается из документа, а не сообщение о недоступности");
        assertFalse(MarkdownFormat.userGuide().equals(Texts.get("markdown.help.unavailable", MarkdownFormat.USER_GUIDE_RESOURCE)));
    }

    /** Поиск документа: файл языка, затем файл без суффикса; строгий UTF-8; битый файл языка не подменяется. */
    @Test
    void documentLookupFallsBackByLanguageAndIsStrictUtf8() throws IOException {
        assertEquals(List.of("help-format_ru.md", "help-format.md"), TextCatalog.documentCandidates("help-format", "md", "ru"));
        assertEquals(List.of("help-format.md"), TextCatalog.documentCandidates("help-format", "md", ""));

        TextCatalog.ResourceSource both = name -> switch (name) {
            case "guide_ru.md" -> utf8("﻿справка");
            case "guide.md" -> utf8("plain");
            default -> null;
        };
        assertEquals(Optional.of("справка"), TextCatalog.loadDocument("guide", "md", "ru", both), "BOM отброшен");
        assertEquals(Optional.of("plain"), TextCatalog.loadDocument("guide", "md", "", both));

        TextCatalog.ResourceSource plainOnly = name -> "guide.md".equals(name) ? utf8("plain") : null;
        assertEquals(Optional.of("plain"), TextCatalog.loadDocument("guide", "md", "ru", plainOnly), "запасной файл");
        assertEquals(Optional.empty(), TextCatalog.loadDocument("guide", "md", "ru", name -> null));

        TextCatalog.ResourceSource cp1251 = name -> switch (name) {
            case "guide_ru.md" -> new ByteArrayInputStream("справка".getBytes(Charset.forName("windows-1251")));
            case "guide.md" -> utf8("plain");
            default -> null;
        };
        assertThrows(IOException.class, () -> TextCatalog.loadDocument("guide", "md", "ru", cp1251),
                "файл языка не в UTF-8 — ошибка, а не молча запасной файл");
        assertTrue(Texts.document("no-such-document", "md").isEmpty());
    }

    private static InputStream utf8(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
