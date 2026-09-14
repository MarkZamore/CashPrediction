package ru.cashprediction.fx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Решение пользователя от 2026-09-14: в интерфейсе JavaFX-клиента нет длинного (U+2014) и среднего (U+2013) тире,
 * только дефис-минус {@code -}.
 *
 * <p><b>Что проверяется.</b> Строковые и символьные литералы (включая текстовые блоки) основного кода ui-fx не содержат
 * этих знаков ни как есть, ни escape-последовательностью {@code \}{@code u2014}/{@code \}{@code u2013}; комментарии и
 * Javadoc не проверяются. Ресурсы модуля (основные и тестовые, например {@code styles.css}) проверяются побайтно
 * целиком, вместе с комментариями, а также на escape-формы CSS, Java и HTML. Сообщение теста перечисляет
 * {@code файл:строка: текст}.</p>
 *
 * <p>Сами знаки в этом файле не пишутся ни буквально, ни escape-последовательностью: они собираются из кодов,
 * чтобы файл не попадал в побайтные проверки репозитория.</p>
 */
class NoDashesInFxUiTest {

    /** Системное свойство с абсолютной папкой модуля ui-fx (задаёт surefire в {@code ui-fx/pom.xml}). */
    static final String BASEDIR_PROPERTY = "fx.basedir";

    /** Папка, по которой узнаётся модуль ui-fx. */
    private static final String MARKER = "src/main/java/ru/cashprediction/fx";

    /** Длинное тире U+2014. */
    private static final char EM_DASH = (char) 0x2014;

    /** Среднее тире U+2013. */
    private static final char EN_DASH = (char) 0x2013;

    /** Обратная косая черта: собирается из кода, чтобы в исходнике не было escape-последовательностей тире. */
    private static final String BACKSLASH = String.valueOf((char) 0x5C);

    /** Escape-последовательность Java внутри литерала: обратная косая черта, одна или несколько {@code u}, код тире. */
    private static final Pattern JAVA_ESCAPE = Pattern.compile("u+201[34]", Pattern.CASE_INSENSITIVE);

    /**
     * Escape-формы тире в ресурсах: Java/JSON ({@code \}{@code u2014}), CSS ({@code \}{@code 2014}, {@code \}{@code 002013})
     * и HTML-сущности ({@code &mdash;}, {@code &#8211;}, {@code &#x2014;}).
     */
    private static final Pattern RESOURCE_ESCAPE = Pattern.compile(
            Pattern.quote(BACKSLASH) + "u+201[34]"
                    + "|" + Pattern.quote(BACKSLASH) + "0{0,2}201[34](?![0-9a-f])"
                    + "|&[mn]dash;|&#0*821[12];|&#x0*201[34];",
            Pattern.CASE_INSENSITIVE);

    /** Двоичные ресурсы: их байты не текст, случайная последовательность байтов тире в них не ошибка. */
    private static final Set<String> BINARY_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "ico", "bmp", "ttf", "otf", "woff", "woff2");

    @Test
    void mainCodeLiteralsHaveNoDashes() {
        List<String> found = javaFiles(moduleDir().resolve("src/main/java")).stream()
                .flatMap(file -> dashesInLiterals(moduleDir().relativize(file), read(file)).stream())
                .map(Finding::toString)
                .toList();
        assertTrue(found.isEmpty(), () -> "в текстах интерфейса только дефис-минус «-» (решение от 2026-09-14):\n"
                + String.join("\n", found));
    }

    @Test
    void resourcesHaveNoDashes() {
        List<String> found = new ArrayList<>();
        for (Path root : List.of(moduleDir().resolve("src/main/resources"), moduleDir().resolve("src/test/resources"))) {
            for (Path file : files(root)) {
                if (!BINARY_EXTENSIONS.contains(extension(file))) {
                    dashesInText(moduleDir().relativize(file), read(file)).forEach(f -> found.add(f.toString()));
                }
            }
        }
        assertTrue(found.isEmpty(), () -> "ресурсы ui-fx без тире целиком, комментарии тоже (решение от 2026-09-14):\n"
                + String.join("\n", found));
    }

    @Test
    void scannedFoldersExistSoTheCheckIsNotEmpty() {
        Path main = moduleDir().resolve("src/main/java");
        assertTrue(Files.isDirectory(main.resolve("ru/cashprediction/fx")), main.toString());
        assertTrue(Files.isRegularFile(moduleDir().resolve("src/main/resources/ru/cashprediction/fx/styles.css")),
                "styles.css проверяется");
        int literals = javaFiles(main).stream().mapToInt(file -> literalCount(read(file))).sum();
        assertTrue(literals > 500, "в ui-fx много литералов, сканер их видит: " + literals);
    }

    @Test
    void scannerFindsDashesInLiteralsButNotInComments() {
        String dash = String.valueOf(EM_DASH);
        String source = String.join("\n",
                "// comment " + dash,
                "/* block " + dash,
                "   still comment " + EN_DASH + " */",
                "class A {",
                "  String a = \"a " + dash + " b\";",
                "  char c = '" + EN_DASH + "';",
                "  String e = \"" + BACKSLASH + "u2014\";",
                "  String notEscape = \"" + BACKSLASH + BACKSLASH + "u2014\";",
                "  String block = \"\"\"",
                "      text " + BACKSLASH + "\"\"\" still text",
                "      " + dash + " line",
                "      \"\"\";",
                "  String url = \"http://x/*\"; // " + dash,
                "  char quote = '\"'; String upper = \"" + BACKSLASH + "uu2013\";",
                "}");
        List<Finding> found = dashesInLiterals(Path.of("A.java"), source);
        assertEquals(List.of(5, 6, 7, 11, 14), found.stream().map(Finding::line).toList(), found.toString());
        assertTrue(found.getFirst().toString().startsWith("A.java:5: String a"), found.getFirst().toString());
        assertEquals(8, literalCount(source));
    }

    @Test
    void resourceScanFindsRawAndEscapedDashes() {
        String css = String.join("\n",
                "/* comment " + EM_DASH + " */",
                ".a { -fx-padding: 0 4 0 4; }",
                ".b:after { content: \"" + BACKSLASH + "2014\"; }",
                "x = \"" + BACKSLASH + "u2013\"",
                "<b>&mdash;</b> &#8211; &#x2014;",
                "range 1-31, " + EN_DASH,
                ".c { content: \"" + BACKSLASH + "20145\"; }");
        List<Finding> found = dashesInText(Path.of("styles.css"), css);
        assertEquals(List.of(1, 3, 4, 5, 6), found.stream().map(Finding::line).toList(), found.toString());
    }

    // ================================================================== сканер

    /**
     * Нарушение: файл, номер строки и текст строки.
     *
     * @param file файл относительно папки модуля
     * @param line номер строки с 1
     * @param text строка без отступов
     */
    record Finding(Path file, int line, String text) {

        /** @return {@code файл:строка: текст} с прямыми косыми чертами в пути */
        @Override
        public String toString() {
            return file.toString().replace('\\', '/') + ":" + line + ": " + text;
        }
    }

    /**
     * Находит тире в строковых и символьных литералах Java, включая текстовые блоки; комментарии пропускаются.
     *
     * @param file   имя файла для сообщения
     * @param source исходный текст
     * @return нарушения, не больше одного на строку
     */
    static List<Finding> dashesInLiterals(Path file, String source) {
        String[] lines = source.split("\n", -1);
        List<Integer> hits = new ArrayList<>();
        new LiteralWalker(source) {
            @Override
            void literalChar(int index, int line) {
                char c = source.charAt(index);
                boolean escaped = c == '\\' && JAVA_ESCAPE.matcher(source).region(index + 1, source.length()).lookingAt();
                if ((c == EM_DASH || c == EN_DASH || escaped) && (hits.isEmpty() || hits.getLast() != line)) {
                    hits.add(line);
                }
            }
        }.walk();
        return hits.stream().map(line -> new Finding(file, line, lines[line - 1].strip())).toList();
    }

    /**
     * Находит тире в тексте ресурса целиком: сами знаки и их escape-формы.
     *
     * @param file имя файла для сообщения
     * @param text содержимое
     * @return нарушения по строкам
     */
    static List<Finding> dashesInText(Path file, String text) {
        List<Finding> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.indexOf(EM_DASH) >= 0 || line.indexOf(EN_DASH) >= 0 || RESOURCE_ESCAPE.matcher(line).find()) {
                found.add(new Finding(file, i + 1, line.strip()));
            }
        }
        return found;
    }

    /**
     * Число литералов в исходнике: показывает, что сканер действительно видит код.
     *
     * @param source исходный текст
     * @return число строковых, символьных литералов и текстовых блоков
     */
    static int literalCount(String source) {
        int[] count = {0};
        new LiteralWalker(source) {
            @Override
            void literalStart() {
                count[0]++;
            }
        }.walk();
        return count[0];
    }

    /**
     * Проход по исходнику Java с различением комментариев и литералов. Наследник получает начало каждого литерала и
     * каждый символ внутри него (включая обратную косую черту escape-последовательности, после которой символ
     * пропускается).
     */
    private abstract static class LiteralWalker {

        /** Исходный текст. */
        private final String source;

        /**
         * @param source исходный текст
         */
        LiteralWalker(String source) {
            this.source = source;
        }

        /** Начался литерал. */
        void literalStart() {
        }

        /**
         * Символ внутри литерала.
         *
         * @param index позиция в исходнике
         * @param line  номер строки с 1
         */
        void literalChar(int index, int line) {
        }

        /** Проходит весь исходник. */
        final void walk() {
            int line = 1;
            int i = 0;
            int n = source.length();
            while (i < n) {
                char c = source.charAt(i);
                if (c == '\n') {
                    line++;
                    i++;
                } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                    while (i < n && source.charAt(i) != '\n') {
                        i++;
                    }
                } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                    int end = source.indexOf("*/", i + 2);
                    end = end < 0 ? n : end + 2;
                    for (int k = i; k < end; k++) {
                        if (source.charAt(k) == '\n') {
                            line++;
                        }
                    }
                    i = end;
                } else if (source.startsWith("\"\"\"", i)) {
                    literalStart();
                    i += 3;
                    while (i < n && !source.startsWith("\"\"\"", i)) {
                        if (source.charAt(i) == '\n') {
                            line++;
                        } else {
                            literalChar(i, line);
                        }
                        i += source.charAt(i) == '\\' && i + 1 < n && source.charAt(i + 1) != '\n' ? 2 : 1;
                    }
                    i += 3;
                } else if (c == '"' || c == '\'') {
                    literalStart();
                    i++;
                    while (i < n && source.charAt(i) != c && source.charAt(i) != '\n') {
                        literalChar(i, line);
                        i += source.charAt(i) == '\\' ? 2 : 1;
                    }
                    i++;
                } else {
                    i++;
                }
            }
        }
    }

    // ================================================================== файлы

    /**
     * Папка модуля ui-fx: из свойства {@value #BASEDIR_PROPERTY}, иначе рабочая папка или её подпапка {@code ui-fx}.
     *
     * @return абсолютный нормализованный путь
     */
    static Path moduleDir() {
        String property = System.getProperty(BASEDIR_PROPERTY);
        if (property != null && !property.isBlank()) {
            return Path.of(property.strip()).toAbsolutePath().normalize();
        }
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.isDirectory(cwd.resolve(MARKER))) {
            return cwd;
        }
        Path fromRoot = cwd.resolve("ui-fx");
        return Files.isDirectory(fromRoot.resolve(MARKER)) ? fromRoot : cwd;
    }

    private static List<Path> javaFiles(Path root) {
        return files(root).stream().filter(file -> file.getFileName().toString().endsWith(".java")).toList();
    }

    private static List<Path> files(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            // Строки считаются по \n: \r перед ним остаётся в тексте строки и убирается strip().
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
