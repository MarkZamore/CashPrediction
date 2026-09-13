package ru.cashprediction.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Решение L13: новый код ядра не содержит русских строковых литералов — всё, что видит пользователь, берётся из
 * каталога текстов. Проверяются пакеты, созданные для единого интерфейса ({@code core.text}, {@code core.app},
 * {@code core.ui}) и {@code core.io.FolderListing}; прежние литералы остальных пакетов переносит этап S0.5, после
 * чего список {@link #NEW_CODE} расширяется на всё ядро.
 *
 * <p>Комментарии и Javadoc не проверяются. Папка модуля берётся из {@link CoreModuleDir}, поэтому тест одинаково
 * работает из Maven и из IDE с любой рабочей папкой.</p>
 */
class NoCyrillicLiteralsInNewCodeTest {

    /** Проверяемые исходники в папке модуля. */
    private static final List<Path> NEW_CODE = List.of(
            CoreModuleDir.resolve("src/main/java/ru/cashprediction/core/text"),
            CoreModuleDir.resolve("src/main/java/ru/cashprediction/core/app"),
            CoreModuleDir.resolve("src/main/java/ru/cashprediction/core/ui"),
            CoreModuleDir.resolve("src/main/java/ru/cashprediction/core/io/FolderListing.java"));

    @Test
    void newCoreCodeHasNoCyrillicLiterals() {
        List<String> found = NEW_CODE.stream()
                .flatMap(root -> JavaSourceScanner.literals(root).stream())
                .filter(literal -> JavaSourceScanner.hasCyrillic(literal.raw()))
                .map(literal -> literal.file() + ":" + literal.line() + " \"" + literal.raw() + "\"")
                .toList();
        assertEquals(List.of(), found, "русские строки берутся из каталога текстов (UiText/Texts)");
    }

    @Test
    void scannedFoldersExistSoTheCheckIsNotEmpty() {
        for (Path root : NEW_CODE) {
            assertTrue(root.toFile().exists(), root.toString());
        }
        assertTrue(JavaSourceScanner.literals(NEW_CODE.get(2)).size() > 50, "в core.ui есть литералы — сканер их видит");
    }

    @Test
    void scannerSkipsCommentsAndFindsEveryLiteralKind() {
        String source = """
                // "Комментарий" не литерал
                /* "И этот" тоже
                   нет */
                class A {
                    String a = "латиница";
                    String b = "кириллица: Да";
                    char c = 'ё';
                    char q = '"';
                    String e = "\\u0416";
                    String t = \"""
                        текстовый блок
                        \""";
                    String k = UiText.get("menu.file.new");
                }
                """;
        List<JavaSourceScanner.Literal> literals = JavaSourceScanner.scan(Path.of("A.java"), source);
        assertEquals(List.of("латиница", "кириллица: Да", "ё", "\"", "\\u0416", "menu.file.new"),
                literals.stream().map(JavaSourceScanner.Literal::raw).filter(r -> !r.contains("текстовый")).toList());
        assertTrue(literals.stream().anyMatch(l -> l.raw().contains("текстовый блок")));
        assertFalse(JavaSourceScanner.hasCyrillic("латиница".replace("латиница", "latin")));
        assertTrue(JavaSourceScanner.hasCyrillic("\\u0416"));
        assertTrue(literals.getLast().codeBefore().endsWith("UiText.get("));
        assertEquals(5, literals.getFirst().line());
    }
}
