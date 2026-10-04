package ru.cashprediction.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.markdown.RuFormats;

/** Независимые примеры точной нормализации, поиска подстроки и ограниченного времени жизни кэша. */
class SearchTextTest {
    /** Фиксирует прежнюю семантику кодовых точек; grammar и поиск сверяются с заданными ответами. */
    @Test
    void codePointNormalizationPreservesExistingSemantics() {
        List<Example> examples = List.of(
                new Example(null, ""), new Example("", ""),
                new Example(" \t\r\n\f\u000b\u00a0\u202f\u2007\u1680\u2003 ", ""),
                new Example(" \tЁЖ\u00a0\u202f  MiXeD\n Note  ", "еж mixed note"),
                new Example("ЕЛКА ёлка ЁЛКА", "елка елка елка"),
                new Example("A\u200bB\ufeffC", "a\u200bb\ufeffc"),
                new Example("Ё Е\u0308", "е е\u0308"),
                new Example("ΟΣ", "οσ"), new Example("I İ ẞ SS", "i i ß ss"),
                new Example("\ud801\udc00 \ud83d\ude00", "\ud801\udc28 \ud83d\ude00"),
                new Example("A\ud800B", "a\ud800b"));
        for (Example example : examples) {
            assertEquals(example.expected(), SearchText.normalize(example.input()));
            assertEquals(example.expected(), RuFormats.normalize(example.input()), "File grammar normalization stays compatible");
        }
    }

    /** Проверяет подстроку, пробелы, отсутствие NFC и буквального слияния разных слов. */
    @Test
    void preparedSearchUsesNormalizedSubstringWithoutExtraFolding() {
        var search = SearchText.prepare("  ЕЖ   mixedNote  ");
        assertTrue(search.test("prefix Ёж\u202f MiXeDnOtE suffix"));
        assertFalse(search.test("ёж mixed other note"));
        assertFalse(search.test("ежmixedNote"));
        assertFalse(search.test(null));
        assertFalse(search.test(""));
        assertFalse(SearchText.prepare("ёж").test("Е\u0308ж"));
        assertTrue(SearchText.prepare("ΟΣ").test("οσ"));
        assertFalse(SearchText.prepare("ΟΣ").test("ος"));
        assertTrue(SearchText.prepare("İ").test("i"));
    }

    /** Пустой запрос не накапливает даже большие поля; ответы разных запросов не смешиваются. */
    @Test
    void blankAndIndependentQueriesKeepTheirOwnAnswers() {
        SearchText.Prepared blank = prepared("\u00a0 \t");
        assertTrue(blank.test(null));
        assertTrue(blank.test(""));
        assertTrue(blank.test("x".repeat(SearchText.MAX_CACHED_CHARACTERS + 1)));
        assertEquals(0, blank.cachedTextCount());
        assertEquals(0, blank.cachedCharacterCount());
        var first = SearchText.prepare("needle");
        var second = SearchText.prepare("different");
        assertTrue(first.test("A Needle"));
        assertFalse(second.test("A Needle"));
        assertTrue(second.test("DIFFERENT"));
        assertFalse(first.test("DIFFERENT"));
    }

    /** Повторяющиеся поля с разной идентичностью String удерживаются один раз по фактическому содержимому. */
    @Test
    void repeatedTextMemoizesBothMatchesAndMissesByValue() {
        SearchText.Prepared search = prepared("needle");
        for (int i = 0; i < 2_600; i++) {
            assertTrue(search.test(new String("same NEEDLE")));
            assertFalse(search.test(new String("same miss")));
        }
        assertEquals(2, search.cachedTextCount());
        assertEquals("same NEEDLE".length() + "same miss".length(), search.cachedCharacterCount());
    }

    /** Уникальные короткие тексты вызывают вытеснение, но число ключей не растёт без ограничений. */
    @Test
    void uniqueTextStreamStaysBoundedAndEvictionDoesNotChangeResults() {
        SearchText.Prepared search = prepared("needle");
        for (int i = 0; i < SearchText.MAX_CACHED_TEXTS + 53; i++) {
            String text = "field-" + i + (i % 3 == 0 ? " NEEDLE" : " miss");
            assertEquals(i % 3 == 0, search.test(text));
            assertTrue(search.cachedTextCount() <= SearchText.MAX_CACHED_TEXTS);
            assertTrue(search.cachedCharacterCount() <= SearchText.MAX_CACHED_CHARACTERS);
        }
        assertEquals(SearchText.MAX_CACHED_TEXTS, search.cachedTextCount());
        assertTrue(search.test("field-0 NEEDLE"));
        assertFalse(search.test("field-1 miss"));
        assertEquals(SearchText.MAX_CACHED_TEXTS, search.cachedTextCount());
    }

    /** Суммарный лимит удерживаемых символов действует также для длинных заметок и слишком больших полей. */
    @Test
    void longNotesStayWithinCharacterBudgetAndOversizedFieldsAreNotRetained() {
        SearchText.Prepared search = prepared("needle");
        for (int i = 0; i < 90; i++) {
            String text = "x".repeat(1_024) + i + (i % 2 == 0 ? " NEEDLE" : " miss");
            assertEquals(i % 2 == 0, search.test(text));
            assertTrue(search.cachedCharacterCount() <= SearchText.MAX_CACHED_CHARACTERS);
        }
        assertTrue(search.cachedTextCount() < 90);
        int entries = search.cachedTextCount();
        int characters = search.cachedCharacterCount();
        assertTrue(search.test("x".repeat(SearchText.MAX_CACHED_CHARACTERS + 1) + " NEEDLE"));
        assertFalse(search.test("z".repeat(SearchText.MAX_CACHED_CHARACTERS + 1)));
        assertEquals(entries, search.cachedTextCount());
        assertEquals(characters, search.cachedCharacterCount());
    }

    /** Открывает только внутренние счётчики фактически удерживаемой памяти, без reflection. */
    private static SearchText.Prepared prepared(String query) {
        return (SearchText.Prepared) SearchText.prepare(query);
    }

    /** Независимо заданные исходная строка и ожидаемый результат, включая null и Unicode-границы. */
    private record Example(String input, String expected) { }
}
