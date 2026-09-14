package ru.cashprediction.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Решение пользователя от 2026-09-14: в интерфейсе всех клиентов нет длинного тире (U+2014) и среднего тире
 * (U+2013), только дефис-минус {@code -}. Для web-клиента это проверяется двумя способами.
 *
 * <ul>
 *   <li><b>Ресурсы страницы</b> {@code src/main/resources/web/**} (JS, HTML, CSS вместе с комментариями) и тестовые
 *   ресурсы модуля просматриваются побайтно: нет последовательностей UTF-8 {@code E2 80 94} и {@code E2 80 93}, а
 *   также записей тех же знаков через escape-последовательности JS и CSS и HTML-сущности.</li>
 *   <li><b>Основной Java-код модуля</b> (ответы API, заголовок окна статуса сервера, журнал): нет тире в строковых и
 *   символьных литералах и текстовых блоках, ни самим знаком, ни unicode-escape. Комментарии и Javadoc не интерфейс
 *   и не проверяются.</li>
 * </ul>
 *
 * <p>Сообщение теста перечисляет {@code файл:строка: текст строки}. Папка модуля приходит из свойства
 * {@value #PROPERTY} ({@code web/pom.xml}); без него (запуск из IDE) ищется от рабочей папки.</p>
 */
class NoDashesInWebUiTest {

    /** Системное свойство с абсолютной папкой модуля web. */
    static final String PROPERTY = "web.basedir";

    /** Папка, по которой узнаётся модуль web. */
    private static final String MARKER = "src/main/resources/web";

    /**
     * Тире в байтах файла, прочитанных как ISO-8859-1 (байт в символ один к одному): сам знак в UTF-8, escape JS
     * ({@code \}{@code u2014}, {@code \}{@code u{2014}}), escape CSS ({@code \}{@code 2014}) и HTML-сущности.
     */
    private static final Pattern DASH_BYTES = Pattern.compile(
            "\u00E2\u0080[\u0093\u0094]"
                    + "|\\\\u\\{?0*201[34]\\}?"
                    + "|\\\\0{0,2}201[34](?![0-9a-f])"
                    + "|&(?:mdash|ndash);"
                    + "|&#0*(?:8212|8211);"
                    + "|&#x0*201[34];",
            Pattern.CASE_INSENSITIVE);

    /** Тире в тексте литерала Java: сам знак или unicode-escape (нечётное число обратных косых черт перед u). */
    private static final Pattern DASH_IN_LITERAL = Pattern.compile(
            "[\u2013\u2014]|(?<!\\\\)(?:\\\\\\\\)*\\\\u+201[34]", Pattern.CASE_INSENSITIVE);

    @Test
    void webResourcesAndTestResourcesContainNoDashBytes() {
        List<String> found = new ArrayList<>(dashBytes(moduleDir().resolve(MARKER)));
        Path testResources = moduleDir().resolve("src/test/resources");
        if (Files.isDirectory(testResources)) {
            found.addAll(dashBytes(testResources));
        }
        assertEquals(List.of(), found,
                "в ресурсах web-клиента (JS, HTML, CSS, комментарии тоже) вместо длинного и среднего тире дефис-минус");
    }

    @Test
    void webMainJavaLiteralsContainNoDashes() {
        List<String> found = javaFiles(moduleDir().resolve("src/main/java")).stream()
                .flatMap(file -> dashesInLiterals(file, read(file)).stream())
                .toList();
        assertEquals(List.of(), found,
                "строки web-сервера, которые видит пользователь, пишутся с дефисом-минусом, а не с тире");
    }

    @Test
    void scannedFoldersExistSoTheCheckIsNotEmpty() {
        Path resources = moduleDir().resolve(MARKER);
        assertTrue(Files.isRegularFile(resources.resolve("index.html")), resources.toString());
        assertTrue(Files.isRegularFile(resources.resolve("app.js")), resources.toString());
        assertTrue(files(resources).size() > 10, "проверяются все ресурсы страницы, а не один файл");
        List<Path> java = javaFiles(moduleDir().resolve("src/main/java"));
        assertTrue(java.size() > 10, "проверяется весь основной Java-код модуля");
        long literals = java.stream().mapToLong(file -> literals(read(file)).size()).sum();
        assertTrue(literals > 100, "сканер видит литералы web-сервера: " + literals);
    }

    @Test
    void byteScanFindsRawAndEscapedDashesButNotOtherCharacters() {
        String text = String.join("\n",
                "const a = 'Период - март';",
                "const b = 'Период \u2014 март';",
                "const c = '1\u20133';",
                "const d = '\\u2014' + '\\u{2013}';",
                "p::after { content: \"\\2014\"; }",
                "<span>&mdash; &#8211; &#x2014;</span>",
                "const e = 'Доходы \u221210 %'; // U+2212 не тире из решения",
                "const f = '\\u20145';");
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        assertEquals(List.of(2, 3, 4, 5, 6, 8), dashByteLines(bytes), "строки с тире");
    }

    @Test
    void javaScanSkipsCommentsAndFindsEveryLiteralKind() {
        String source = String.join("\n",
                "/** Javadoc \u2014 не интерфейс. */",
                "class A {",
                "    // комментарий \u2014 тоже",
                "    String ok = \"CashPrediction - сервер\"; /* \"\u2014\" в комментарии */",
                "    String dash = \"CashPrediction \u2014 сервер\";",
                "    char c = '\u2013';",
                "    String escaped = \"\\u2014\";",
                "    String notEscape = \"\\\\u2014\";",
                "    String block = \"\"\"",
                "        первая строка",
                "        вторая \u2014 строка",
                "        \"\"\";",
                "    char quote = '\"'; String after = \"\u2014\";",
                "}");
        List<String> found = dashesInLiterals(Path.of("A.java"), source);
        List<Integer> lines = found.stream().map(s -> Integer.parseInt(s.split(":")[1])).toList();
        assertEquals(List.of(5, 6, 7, 11, 13), lines, found.toString());
        assertTrue(found.getFirst().startsWith("A.java:5: String dash"), found.getFirst());
        assertEquals(List.of("CashPrediction - сервер", "CashPrediction \u2014 сервер", "\u2013", "\\u2014",
                        "\\\\u2014"),
                literals(source).subList(0, 5).stream().map(Literal::text).toList());
        assertTrue(literals(source).get(5).text().contains("вторая \u2014 строка"));
    }

    /**
     * Папка модуля web.
     *
     * @return абсолютный нормализованный путь
     */
    private static Path moduleDir() {
        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank()) {
            return Path.of(property.strip()).toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve(MARKER))) {
            return cwd;
        }
        Path fromRoot = cwd.resolve("web");
        return Files.isDirectory(fromRoot.resolve(MARKER)) ? fromRoot : cwd;
    }

    /**
     * Побайтная проверка всех файлов папки.
     *
     * @param dir папка
     * @return найденные строки {@code файл:строка: текст}
     */
    private static List<String> dashBytes(Path dir) {
        List<String> found = new ArrayList<>();
        for (Path file : files(dir)) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            List<String> lines = List.of(new String(bytes, StandardCharsets.UTF_8).split("\n", -1));
            for (int line : dashByteLines(bytes)) {
                found.add(file + ":" + line + ": " + lines.get(line - 1).strip());
            }
        }
        return found;
    }

    /**
     * Номера строк (с 1), в которых есть тире в любом виде из {@link #DASH_BYTES}.
     *
     * @param bytes содержимое файла
     * @return номера строк по возрастанию
     */
    private static List<Integer> dashByteLines(byte[] bytes) {
        String latin = new String(bytes, StandardCharsets.ISO_8859_1);
        List<Integer> lines = new ArrayList<>();
        String[] split = latin.split("\n", -1);
        for (int i = 0; i < split.length; i++) {
            if (DASH_BYTES.matcher(split[i]).find()) {
                lines.add(i + 1);
            }
        }
        return lines;
    }

    /**
     * Тире в литералах одного исходника Java.
     *
     * @param file   файл (для сообщения)
     * @param source текст исходника
     * @return найденные строки {@code файл:строка: текст}
     */
    private static List<String> dashesInLiterals(Path file, String source) {
        String[] lines = source.split("\n", -1);
        List<String> found = new ArrayList<>();
        int lastLine = 0;
        for (Literal literal : literals(source)) {
            Matcher m = DASH_IN_LITERAL.matcher(literal.text());
            while (m.find()) {
                int line = lineOf(source, literal.start() + m.start());
                if (line != lastLine) {
                    found.add(file + ":" + line + ": " + lines[line - 1].strip());
                    lastLine = line;
                }
            }
        }
        return found;
    }

    /**
     * Строковые и символьные литералы и текстовые блоки Java без кавычек; комментарии пропускаются.
     *
     * @param source текст исходника
     * @return литералы в порядке появления
     */
    private static List<Literal> literals(String source) {
        List<Literal> result = new ArrayList<>();
        int n = source.length();
        int i = 0;
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? n : end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int start = i + 3;
                int j = start;
                while (j < n && !source.startsWith("\"\"\"", j)) {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                int end = Math.min(j, n);
                result.add(new Literal(start, source.substring(start, end)));
                i = end + 3;
            } else if (c == '"' || c == '\'') {
                int start = i + 1;
                int j = start;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                int end = Math.min(j, n);
                result.add(new Literal(start, source.substring(start, end)));
                i = end + 1;
            } else {
                i++;
            }
        }
        return result;
    }

    /**
     * Номер строки (с 1) для позиции в тексте.
     *
     * @param source текст
     * @param offset позиция
     * @return номер строки
     */
    private static int lineOf(String source, int offset) {
        int line = 1;
        for (int k = 0; k < offset && k < source.length(); k++) {
            if (source.charAt(k) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * Все файлы папки рекурсивно, в устойчивом порядке.
     *
     * @param dir папка
     * @return файлы
     */
    private static List<Path> files(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Исходники Java папки рекурсивно.
     *
     * @param dir папка
     * @return файлы {@code *.java}
     */
    private static List<Path> javaFiles(Path dir) {
        return files(dir).stream().filter(p -> p.getFileName().toString().endsWith(".java")).toList();
    }

    /**
     * Читает исходник в UTF-8.
     *
     * @param file файл
     * @return текст
     */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Литерал исходника.
     *
     * @param start позиция первого символа содержимого
     * @param text  содержимое без кавычек, как записано в исходнике
     */
    private record Literal(int start, String text) {
    }
}
