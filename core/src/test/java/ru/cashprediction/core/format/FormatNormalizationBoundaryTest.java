package ru.cashprediction.core.format;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.markdown.RuFormats;

/** Регрессия нейтральной нормализации и устранения обратной зависимости настройки вида от Markdown. */
class FormatNormalizationBoundaryTest {
    /** Настройка вида использует независимый формат, а не адаптер разбора файлов плана. */
    @Test void documentPeriodDoesNotDependOnMarkdown() throws Exception {
        Path root = Path.of(System.getProperty("review.source.root", "")).toAbsolutePath();
        while (!Files.isDirectory(root.resolve("core/src/main/java"))) {
            root = root.getParent();
            assertNotNull(root, "repository root required");
        }
        String text = Files.readString(root.resolve(
                "core/src/main/java/ru/cashprediction/core/document/PeriodChoice.java"));
        assertFalse(text.contains("ru.cashprediction.core.markdown."), "document -> markdown edge");
        assertTrue(text.contains("FormatNormalization.normalize"), "neutral format must be consumed");
    }

    /** Существующая публичная нормализация сохраняет пробелы, кодовые точки, null и слова грамматики. */
    @Test void normalizationSemanticsStayStable() {
        String[] input = {null, "", "  A\t B\n", "\u00a0A\u202fB\u00a0", "\u0401\u0436", "\ud801\udc00 X"};
        String[] expected = {"", "", "a b", "a b", "\u0435\u0436", "\ud801\udc28 x"};
        for (int i = 0; i < input.length; i++) assertEquals(expected[i], RuFormats.normalize(input[i]));
    }

    /** Ручные периоды продолжают читаться, неизвестное значение не превращается в настройку по умолчанию. */
    @Test void periodParsingStaysStable() {
        assertEquals(Optional.of(PeriodChoice.M12), PeriodChoice.parse("\u00a0M12\u202f"));
        assertEquals(Optional.of(PeriodChoice.M3), PeriodChoice.parse("3 months"));
        assertEquals(Optional.of(PeriodChoice.ALL), PeriodChoice.parse("ALL"));
        for (PeriodChoice value : PeriodChoice.values()) assertEquals(Optional.of(value), PeriodChoice.parse(value.label()));
        assertEquals(Optional.empty(), PeriodChoice.parse(null));
        assertEquals(Optional.empty(), PeriodChoice.parse("11"));
    }
}
