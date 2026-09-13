package ru.cashprediction.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/**
 * Решение L13, итог этапа S0.5: в основном коде ядра нет кириллицы вне комментариев. Всё, что видит пользователь,
 * берётся из каталога текстов ({@code Texts}/{@code UiText}), слова формата файлов — из нелокализуемого ресурса
 * {@code FormatWords}, а сообщения только для разработчика пишутся латиницей.
 *
 * <p><b>Как проверяется.</b> {@link JavaSourceScanner#cyrillicInCode(Path, JavaSourceScanner.Syntax)} убирает
 * комментарии {@code //} и {@code /* … * /} (в том числе Javadoc) с учётом границ строк и текстовых блоков и находит
 * любую кириллическую букву, оставшуюся в коде: в строковых и символьных литералах, в идентификаторах и в
 * escape-последовательностях {@code \}{@code u04XX}. Сообщение теста перечисляет {@code файл:строка: код}.</p>
 *
 * <p>Папка модуля берётся из {@link CoreModuleDir}, поэтому тест одинаково работает из Maven и из IDE. Прежние
 * клиенты (ui-fx, ui-swing, web) удаляются на этапе S4 и здесь не проверяются; этап S3 применяет тот же сканер к
 * новым рендерерам клиентов (Java) и к JS web-клиента ({@link JavaSourceScanner.Syntax#JAVASCRIPT}).</p>
 */
class NoCyrillicLiteralsTest {

    /** Все основные исходники ядра. */
    private static final Path CORE_MAIN = CoreModuleDir.resolve("src/main/java");

    /** Пакет с заведомо большим числом литералов: по нему видно, что сканер действительно работает. */
    private static final Path CORE_UI = CoreModuleDir.resolve("src/main/java/ru/cashprediction/core/ui");

    @Test
    void coreMainCodeHasNoCyrillicOutsideComments() {
        List<String> found = JavaSourceScanner.cyrillicInCode(CORE_MAIN, JavaSourceScanner.Syntax.JAVA).stream()
                .map(JavaSourceScanner.CodeLine::toString)
                .toList();
        assertEquals(List.of(), found,
                "русские строки берутся из каталога текстов (UiText/Texts), слова формата — из FormatWords, "
                        + "сообщения для разработчика пишутся латиницей");
    }

    @Test
    void scannedFoldersExistSoTheCheckIsNotEmpty() {
        assertTrue(CORE_MAIN.toFile().isDirectory(), CORE_MAIN.toString());
        assertTrue(CORE_UI.toFile().isDirectory(), CORE_UI.toString());
        assertTrue(JavaSourceScanner.literals(CORE_UI).size() > 50, "в core.ui есть литералы — сканер их видит");
        assertTrue(JavaSourceScanner.literals(CORE_MAIN).size() > JavaSourceScanner.literals(CORE_UI).size(),
                "проверяется всё ядро, а не один пакет");
        // Javadoc ядра русский: если бы сканер не убирал комментарии, в выводе теста выше была бы почти каждая строка.
        Path texts = CoreModuleDir.resolve("src/main/java/ru/cashprediction/core/text/Texts.java");
        assertTrue(JavaSourceScanner.literals(texts).size() > 0);
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
        assertEquals(");", literals.getLast().codeAfter().substring(0, 2));
        assertEquals(5, literals.getFirst().line());
    }

    @Test
    void codeCheckReportsCyrillicInCodeButNotInComments() {
        String source = """
                /**
                 * Javadoc: «Сумма».
                 */
                class B {
                    String url = "http://example"; // «комментарий» после строки
                    String slashes = "/* не комментарий */ Да";
                    int сумма = 1;
                    String block = \"""
                        /* внутри текстового блока */ Нет
                        \""";
                    char slash = '/'; /* «многострочный
                       комментарий» */ String after = "ok";
                    String escaped = "\\u0416";
                }
                """;
        List<JavaSourceScanner.CodeLine> found =
                JavaSourceScanner.cyrillicInCode(Path.of("B.java"), source, JavaSourceScanner.Syntax.JAVA);
        assertEquals(List.of(6, 7, 9, 13), found.stream().map(JavaSourceScanner.CodeLine::line).toList(), found.toString());
        String stripped = JavaSourceScanner.stripComments(source, JavaSourceScanner.Syntax.JAVA);
        assertEquals(source.length(), stripped.length(), "позиции и строки сохраняются");
        assertTrue(stripped.contains("\"http://example\""));
        assertTrue(stripped.contains("String after = \"ok\""));
        assertFalse(stripped.contains("Javadoc"));
        assertTrue(found.getFirst().toString().startsWith("B.java:6: String slashes"), found.getFirst().toString());
    }

    @Test
    void javascriptSyntaxIsReadyForTheWebClient() {
        String source = """
                // «комментарий» JS
                const re = /["'`]\\/*кот/g; /* «блок» */
                const half = total / 2 / count;
                const t = `шаблон ${items.map(i => '}' + i).join(", ")} конец`;
                const s = 'один' + "два";
                const k = text("menu.file.new", count);
                """;
        List<JavaSourceScanner.CodeLine> found =
                JavaSourceScanner.cyrillicInCode(Path.of("a.js"), source, JavaSourceScanner.Syntax.JAVASCRIPT);
        assertEquals(List.of(2, 4, 5), found.stream().map(JavaSourceScanner.CodeLine::line).toList(), found.toString());
        List<String> raws = JavaSourceScanner.scan(Path.of("a.js"), source, JavaSourceScanner.Syntax.JAVASCRIPT)
                .stream().map(JavaSourceScanner.Literal::raw).toList();
        assertTrue(raws.contains("[\"'`]\\/*кот"), raws.toString());
        assertTrue(raws.contains("один") && raws.contains("два") && raws.contains("menu.file.new"), raws.toString());
        assertTrue(raws.stream().anyMatch(raw -> raw.startsWith("шаблон ${") && raw.endsWith("} конец")), raws.toString());
        assertFalse(raws.contains(" 2 "), "деление не литерал регулярного выражения: " + raws);
        assertTrue(JavaSourceScanner.Syntax.JAVASCRIPT.matches(Path.of("web/app.js")));
        assertFalse(JavaSourceScanner.Syntax.JAVA.matches(Path.of("web/app.js")));
    }

    @Test
    void argumentsAfterCountsTopLevelArgumentsOnly() {
        assertEquals(OptionalInt.of(0), JavaSourceScanner.argumentsAfter(");"));
        assertEquals(OptionalInt.of(1), JavaSourceScanner.argumentsAfter(", value);"));
        assertEquals(OptionalInt.of(2), JavaSourceScanner.argumentsAfter(" , f(a, b), map.get(\"\"))) + x;"));
        assertEquals(OptionalInt.of(2), JavaSourceScanner.argumentsAfter(", list.stream().map((a, b) -> a), new int[] {1, 2})"));
        assertEquals(OptionalInt.empty(), JavaSourceScanner.argumentsAfter(" + suffix);"), "литерал не целиком аргумент");
        assertEquals(OptionalInt.empty(), JavaSourceScanner.argumentsAfter(", new Object[] {a, b});"), "готовый массив");
        assertEquals(OptionalInt.empty(), JavaSourceScanner.argumentsAfter(", args);"), "varargs вызывающего");
        assertEquals(OptionalInt.empty(), JavaSourceScanner.argumentsAfter(", a"), "вызов обрезан");
    }
}
