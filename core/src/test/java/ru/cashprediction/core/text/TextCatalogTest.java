package ru.cashprediction.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Каталог текстов без привязки к ресурсам ядра: поиск {@code <область>_<язык>} с запасным {@code <область>}, строгий
 * UTF-8, повторы, отсутствующие файлы и строгий режим (решение L13).
 */
class TextCatalogTest {

    private String savedStrict;

    @BeforeEach
    void rememberStrict() {
        savedStrict = System.getProperty(TextCatalog.STRICT_PROPERTY);
    }

    @AfterEach
    void restoreStrict() {
        if (savedStrict == null) {
            System.clearProperty(TextCatalog.STRICT_PROPERTY);
        } else {
            System.setProperty(TextCatalog.STRICT_PROPERTY, savedStrict);
        }
    }

    /** Источник файлов из памяти: имя файла → содержимое в заданной кодировке. */
    private static TextCatalog.ResourceSource source(Map<String, String> files, Charset charset) {
        return name -> files.containsKey(name) ? new ByteArrayInputStream(files.get(name).getBytes(charset)) : null;
    }

    @Test
    void languageFileWinsAndPlainFileIsFallback() {
        Map<String, String> files = new HashMap<>();
        files.put("menu_ru.properties", "menu.file=Файл\n");
        files.put("menu.properties", "menu.file=File\n");
        files.put("status.properties", "status.rows=Строк: {0}\n");
        TextCatalog catalog = TextCatalog.load(List.of("menu", "status"), "ru", source(files, StandardCharsets.UTF_8));

        assertEquals("Файл", catalog.get("menu.file"));
        assertEquals("Строк: 5", catalog.get("status.rows", 5));
        assertEquals(Map.of("menu", "menu_ru.properties", "status", "status.properties"), catalog.loadedFiles());
        assertEquals(List.of(), catalog.loadProblems());
        assertEquals("ru", catalog.language());
        assertEquals(Optional.of("status"), catalog.area("status.rows"));
    }

    @Test
    void emptyLanguageReadsOnlyPlainFiles() {
        Map<String, String> files = Map.of("menu_ru.properties", "a=б\n", "menu.properties", "a=b\n");
        TextCatalog catalog = TextCatalog.load(List.of("menu"), "", source(files, StandardCharsets.UTF_8));
        assertEquals("b", catalog.get("a"));
    }

    @Test
    void nonUtf8FileIsALoadProblemNotGarbage() {
        Map<String, String> files = Map.of("alerts_ru.properties", "alert.x=Удалить операцию?\n");
        TextCatalog catalog = TextCatalog.load(List.of("alerts"), "ru", source(files, Charset.forName("windows-1251")));
        assertFalse(catalog.has("alert.x"), "текст в cp1251 не должен молча превратиться в «кракозябры»");
        assertEquals(1, catalog.loadProblems().size());
        assertTrue(catalog.loadProblems().getFirst().contains("alerts_ru.properties"), catalog.loadProblems().toString());
    }

    @Test
    void missingAreaIsReported() {
        TextCatalog catalog = TextCatalog.load(List.of("menu"), "ru", source(Map.of(), StandardCharsets.UTF_8));
        assertEquals(1, catalog.loadProblems().size());
        assertTrue(catalog.loadProblems().getFirst().contains("menu_ru.properties"));
        assertTrue(catalog.loadProblems().getFirst().contains("menu.properties"));
    }

    @Test
    void duplicatesWithinAndAcrossFilesAreRecordedFirstWins() {
        Map<String, String> files = Map.of(
                "menu_ru.properties", "k.one=первый\nk.one=повтор\n",
                "toolbar_ru.properties", "k.one=другой файл\nk.two=два\n");
        TextCatalog catalog = TextCatalog.load(List.of("menu", "toolbar"), "ru", source(files, StandardCharsets.UTF_8));
        assertEquals(List.of("k.one: menu, menu", "k.one: menu, toolbar"), catalog.duplicates());
        // Внутри одного файла Properties оставляет последнее значение, между файлами побеждает первый файл.
        assertEquals("повтор", catalog.get("k.one"));
        assertEquals(Optional.of("menu"), catalog.area("k.one"));
        assertEquals(List.of("k.one", "k.two"), List.copyOf(catalog.keys()));
    }

    @Test
    void strictAndLenientMissingKey() {
        TextCatalog catalog = TextCatalog.load(List.of("app"), "ru",
                source(Map.of("app_ru.properties", "t={0} и {1}\n"), StandardCharsets.UTF_8));
        System.setProperty(TextCatalog.STRICT_PROPERTY, "true");
        IllegalStateException missing = assertThrows(IllegalStateException.class, () -> catalog.get("absent.key"));
        assertEquals("Missing text key: absent.key", missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> catalog.get("t", "одно"));
        System.setProperty(TextCatalog.STRICT_PROPERTY, "false");
        assertEquals("!absent.key!", catalog.get("absent.key"));
        assertEquals("одно и {1}", catalog.get("t", "одно"));
    }

    @Test
    void substitutionIsSinglePassAndIgnoresNonNumericBraces() {
        assertEquals("a {1} b", TextCatalog.format("{0} b", "a {1}"));
        assertEquals("{x} 5 {}", TextCatalog.format("{x} {0} {}", 5));
        assertEquals("2-1-2", TextCatalog.format("{1}-{0}-{1}", 1, 2));
        assertEquals("null", TextCatalog.format("{0}", (Object) null));
        assertEquals(java.util.Set.of(0, 1), TextCatalog.placeholders("{1}: {0} и снова {0}"));
        assertEquals(java.util.Set.of(), TextCatalog.placeholders("{a} {} {-1} {١}"));
    }

    @Test
    void sharedCatalogLoadsEveryAreaFromLocaleSuffixedFiles() {
        TextCatalog catalog = Texts.catalog();
        assertEquals(List.of(), catalog.loadProblems());
        assertTrue(Texts.AREAS.containsAll(Texts.UI_AREAS));
        for (String area : Texts.AREAS) {
            assertEquals(Texts.fileName(area), catalog.loadedFiles().get(area), area);
            assertTrue(Texts.class.getResource(Texts.RESOURCE_DIR + Texts.fileName(area)) != null, area);
        }
        assertEquals("ru", Texts.LANGUAGE);
    }
}
