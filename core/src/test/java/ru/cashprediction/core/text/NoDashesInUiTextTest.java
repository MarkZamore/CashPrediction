package ru.cashprediction.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.token.DesignTokens;

/**
 * Решение пользователя от 2026-09-14: в интерфейсе всех клиентов и в файлах, которые программа пишет в CashMemory,
 * нет длинного тире (U+2014) и среднего тире (U+2013), только дефис-минус {@code -}. Обратной совместимости нет: старых
 * файлов с тире программа не ждёт.
 *
 * <p><b>Что проверяется.</b> Каждый файл в {@code core/src/main/resources} (и {@code resources-filtered}) и в
 * {@code core/src/test/resources}, если такая папка есть: тексты локализации, справка, словарь формата. Каждый строковый,
 * символьный литерал и текстовый блок основного кода ядра ({@link JavaSourceScanner}): комментарии и Javadoc не
 * интерфейс и не проверяются. Исключений нет.</p>
 *
 * <p><b>Тесты и документы репозитория.</b> Обратной совместимости нет, поэтому образцы файлов в тестах тоже с
 * дефисом: проверяются литералы и текстовые блоки тестового кода всех модулей ({@code core}, {@code ui-fx},
 * {@code ui-swing}, {@code web}, {@code ui-parity}), кроме самих проверок {@code NoDashesIn*Test.java}, которые
 * записывают формы тире нарочно. Целиком проверяются спецификация ({@code docs/ui-spec.md} и её копия
 * {@code docs/design/ui-spec-v2.md}), {@code docs/FORMAT.md}, {@code docs/ui-protocol.md}, {@code README.md} и
 * ресурсы тестов {@code ui-parity}.</p>
 *
 * <p><b>Какие формы тире ищутся.</b> Сам символ, escape-последовательность {@code \}{@code u2014} (в том числе с
 * несколькими {@code u}, как в properties и Java) и HTML-сущности {@code &}{@code mdash;}, {@code &}{@code ndash;},
 * {@code &}{@code #8212;}, {@code &}{@code #x2014;} и их пары для U+2013. Другие похожие символы (минус U+2212,
 * горизонтальная черта U+2015 и т. п.) решение не затрагивает. Сообщение теста перечисляет
 * {@code файл:строка: текст}.</p>
 *
 * <p>Сам тест тоже без тире: символы собираются из кодов, чтобы поиск по репозиторию находил только настоящие
 * остатки.</p>
 */
class NoDashesInUiTextTest {

    /** Длинное тире U+2014. */
    private static final char EM_DASH = (char) 0x2014;

    /** Среднее тире U+2013. */
    private static final char EN_DASH = (char) 0x2013;

    /** Любая форма длинного или среднего тире: символ, escape-последовательность или HTML-сущность. */
    private static final Pattern DASH = Pattern.compile(
            "[" + EN_DASH + EM_DASH + "]"
                    + "|\\\\u+201[34]"
                    + "|&(?:mdash|ndash);"
                    + "|&#0*821[12];"
                    + "|&#x0*201[34];",
            Pattern.CASE_INSENSITIVE);

    /** Основные ресурсы ядра. */
    private static final Path MAIN_RESOURCES = CoreModuleDir.resolve("src/main/resources");

    /** Ресурсы ядра, в которые Maven подставляет свойства сборки. */
    private static final Path MAIN_RESOURCES_FILTERED = CoreModuleDir.resolve("src/main/resources-filtered");

    /** Ресурсы тестов ядра (папки может не быть). */
    private static final Path TEST_RESOURCES = CoreModuleDir.resolve("src/test/resources");

    /** Основные исходники ядра. */
    private static final Path CORE_MAIN = CoreModuleDir.resolve("src/main/java");

    /** Тестовый код всех модулей: образцы файлов и тексты проверок. */
    private static final List<Path> TEST_SOURCES = List.of(
            CoreModuleDir.resolve("src/test/java"),
            CoreModuleDir.resolve("../ui-fx/src/test/java"),
            CoreModuleDir.resolve("../ui-swing/src/test/java"),
            CoreModuleDir.resolve("../web/src/test/java"),
            CoreModuleDir.resolve("../ui-parity/src/test/java"));

    /** Проверки тире в модулях: записывают формы тире нарочно, чтобы проверить поиск. */
    private static final Pattern DASH_GUARD_FILE = Pattern.compile("NoDashesIn\\w*Test\\.java");

    /** Документы репозитория без тире: спецификация, её копия, формат файлов, web-протокол, README. */
    private static final List<Path> DOCUMENTS = List.of(
            CoreModuleDir.resolve("../docs/ui-spec.md"),
            CoreModuleDir.resolve("../docs/design/ui-spec-v2.md"),
            CoreModuleDir.resolve("../docs/FORMAT.md"),
            CoreModuleDir.resolve("../docs/ui-protocol.md"),
            CoreModuleDir.resolve("../README.md"));

    /** Ресурсы тестов ui-parity (страницы и образцы стенда). */
    private static final Path PARITY_TEST_RESOURCES = CoreModuleDir.resolve("../ui-parity/src/test/resources");

    /** Сообщение теста: что требуется и откуда решение. */
    private static final String RULE = "длинное (U+2014) и среднее (U+2013) тире заменяются дефисом-минусом \"-\" "
            + "(решение пользователя от 2026-09-14, CLAUDE.md)";

    /**
     * Находка: строка файла или литерала с тире.
     *
     * @param file файл
     * @param line номер строки (с 1)
     * @param text строка с тире, обрезанная по краям
     */
    private record Finding(Path file, int line, String text) {

        /** @return {@code путь:строка: текст}; путь внутри модуля core пишется относительно него */
        @Override
        public String toString() {
            Path core = CoreModuleDir.get();
            Path shown = file.isAbsolute() && file.startsWith(core) ? core.relativize(file) : file;
            return shown + ":" + line + ": " + text;
        }
    }

    /** В ресурсах ядра (тексты, справка, словарь формата) и в ресурсах тестов нет тире ни в какой форме. */
    @Test
    void coreResourcesHaveNoDashes() {
        assertTrue(Files.isDirectory(MAIN_RESOURCES), MAIN_RESOURCES.toString());
        assertTrue(files(MAIN_RESOURCES).size() > 10, "ресурсы ядра найдены, проверка не пустая");
        List<String> found = new ArrayList<>();
        for (Path root : List.of(MAIN_RESOURCES, MAIN_RESOURCES_FILTERED, TEST_RESOURCES)) {
            for (Path file : files(root)) {
                inText(file, read(file)).forEach(finding -> found.add(finding.toString()));
            }
        }
        assertEquals(List.of(), found, RULE);
    }

    /** В строковых и символьных литералах и текстовых блоках основного кода ядра нет тире ни в какой форме. */
    @Test
    void coreMainLiteralsHaveNoDashes() {
        List<JavaSourceScanner.Literal> literals = JavaSourceScanner.literals(CORE_MAIN);
        assertTrue(literals.size() > 100, "литералы ядра найдены, проверка не пустая");
        List<String> found = inLiterals(literals).stream().map(Finding::toString).toList();
        assertEquals(List.of(), found, RULE);
    }

    /** В литералах и текстовых блоках тестового кода всех модулей (образцы файлов, ожидаемые тексты) нет тире. */
    @Test
    void testLiteralsOfAllModulesHaveNoDashes() {
        List<JavaSourceScanner.Literal> literals = new ArrayList<>();
        for (Path root : TEST_SOURCES) {
            literals.addAll(JavaSourceScanner.literals(root));
        }
        assertTrue(literals.stream().map(JavaSourceScanner.Literal::file).distinct().count() > 50,
                "тестовые исходники найдены, проверка не пустая");
        List<JavaSourceScanner.Literal> checked = literals.stream()
                .filter(literal -> !DASH_GUARD_FILE.matcher(literal.file().getFileName().toString()).matches())
                .toList();
        List<String> found = inLiterals(checked).stream().map(Finding::toString).toList();
        assertEquals(List.of(), found, RULE);
    }

    /** Спецификация, её копия, описание формата, web-протокол, README и ресурсы тестов ui-parity без тире. */
    @Test
    void repositoryDocumentsAndParityResourcesHaveNoDashes() {
        List<String> found = new ArrayList<>();
        List<Path> checked = new ArrayList<>(DOCUMENTS);
        checked.addAll(files(PARITY_TEST_RESOURCES));
        for (Path file : checked) {
            assertTrue(Files.isRegularFile(file), file.toString());
            inText(file, read(file)).forEach(finding -> found.add(finding.toString()));
        }
        assertEquals(List.of(), found, RULE);
    }

    /** Исключение для проверок тире узкое: только файлы {@code NoDashesIn*Test.java}. */
    @Test
    void onlyDashGuardFilesAreExempt() {
        assertTrue(DASH_GUARD_FILE.matcher("NoDashesInWebUiTest.java").matches());
        assertTrue(DASH_GUARD_FILE.matcher("NoDashesInUiTextTest.java").matches());
        assertFalse(DASH_GUARD_FILE.matcher("PlanSamples.java").matches());
        assertFalse(DASH_GUARD_FILE.matcher("NoDashesInWebUiTest.java.bak").matches());
        assertFalse(DASH_GUARD_FILE.matcher("DashFreeOutput.java").matches());
    }

    /** Каталог допустимых символов текстов больше не пропускает тире: второй рубеж для {@code UiTextCatalogTest}. */
    @Test
    void designTokensDoNotAllowDashesInTexts() {
        assertFalse(DesignTokens.isAllowedInText(EM_DASH));
        assertFalse(DesignTokens.isAllowedInText(EN_DASH));
        assertTrue(DesignTokens.isAllowedInText('-'));
    }

    /** Поиск в тексте находит каждую форму тире и не трогает дефис и другие похожие символы. */
    @Test
    void textCheckFindsEveryFormOfDash() {
        // Escape-последовательности и сущности собраны из частей, чтобы в самом тесте их не было.
        String text = String.join("\n",
                "a=ok - hyphen",
                "b=x " + EM_DASH + " y",
                "c=1" + EN_DASH + "31",
                "d=\\u" + "2014 \\uu" + "2013",
                "e=&" + "mdash; &" + "NDASH;",
                "f=&#" + "8212; &#" + "x2013;",
                "g=minus " + (char) 0x2212 + " bar " + (char) 0x2015 + " figure " + (char) 0x2012,
                "h=\\u" + "2012 &#" + "8213;");
        List<Finding> found = inText(Path.of("x.properties"), text);
        assertEquals(List.of(2, 3, 4, 5, 6), found.stream().map(Finding::line).toList(), found.toString());
        assertEquals("x.properties:3: c=1" + EN_DASH + "31", found.get(1).toString());
    }

    /** Поиск в литералах пропускает комментарии и находит тире в строке, символе и в строке текстового блока. */
    @Test
    void literalCheckSkipsCommentsAndReportsTheLineOfTheDash() {
        String source = String.join("\n",
                "// comment " + EM_DASH + " is not interface",
                "/** Javadoc " + EN_DASH + " too. */",
                "class A {",
                "    String s = \"title " + EM_DASH + " name\";",
                "    char c = '" + EN_DASH + "';",
                "    String b = \"\"\"",
                "        first",
                "        second " + EM_DASH,
                "        \"\"\";",
                "    String e = \"\\u" + "2014\";",
                "    String ok = \"a - b\"; // " + EM_DASH,
                "}");
        List<Finding> found = inLiterals(JavaSourceScanner.scan(Path.of("A.java"), source));
        assertEquals(List.of(4, 5, 8, 10), found.stream().map(Finding::line).toList(), found.toString());
        assertEquals("A.java:8: second " + EM_DASH, found.get(2).toString());
    }

    /**
     * Строки текста с тире.
     *
     * @param file файл (для отчёта)
     * @param text содержимое
     * @return находки по порядку строк
     */
    private static List<Finding> inText(Path file, String text) {
        List<Finding> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (DASH.matcher(lines[i]).find()) {
                found.add(new Finding(file, i + 1, lines[i].strip()));
            }
        }
        return found;
    }

    /**
     * Строки литералов с тире; для текстового блока номер строки указывает на строку с тире, а не на начало блока.
     *
     * @param literals литералы исходников
     * @return находки по порядку литералов
     */
    private static List<Finding> inLiterals(List<JavaSourceScanner.Literal> literals) {
        List<Finding> found = new ArrayList<>();
        for (JavaSourceScanner.Literal literal : literals) {
            for (Finding inLiteral : inText(literal.file(), literal.raw())) {
                found.add(new Finding(literal.file(), literal.line() + inLiteral.line() - 1, inLiteral.text()));
            }
        }
        return found;
    }

    /**
     * Все файлы папки (рекурсивно).
     *
     * @param root папка; отсутствующая папка даёт пустой список
     * @return файлы по алфавиту
     */
    private static List<Path> files(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Содержимое файла как UTF-8; неверные байты заменяются, чтобы проверка не падала на двоичных файлах.
     *
     * @param file файл
     * @return текст
     */
    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
