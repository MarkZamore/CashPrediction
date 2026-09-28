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
 * Решения пользователя от 2026-09-14: в интерфейсе JavaFX-клиента нет длинного (U+2014) и среднего (U+2013) тире и
 * типографского знака минуса (U+2212), только дефис-минус {@code -}; одиночный дефис не заменяет отсутствующее
 * значение, вместо него пишутся слова по смыслу («за горизонтом», «пропущено», «ещё не записан»).
 *
 * <p><b>Что проверяется.</b> Строковые и символьные литералы (включая текстовые блоки) основного кода ui-fx не содержат
 * этих знаков ни как есть, ни escape-последовательностью {@code \}{@code u2014}/{@code \}{@code u2013}/
 * {@code \}{@code u2212}; комментарии и Javadoc не проверяются. Ресурсы модуля (основные и тестовые, например
 * {@code styles.css}) проверяются побайтно целиком, вместе с комментариями, а также на escape-формы CSS, Java и HTML.
 * Строковый литерал, равный ровно {@code "-"}, в основном коде запрещён: это заглушка вместо слов. Исключение одно:
 * литерал сравнивается с текстом ({@code startsWith("-")}, {@code "-".equals(...)} и подобные), то есть проверяет
 * знак настоящего значения и сам на экран не попадает. Сообщение теста перечисляет {@code файл:строка: текст}.</p>
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

    /** Типографский знак минуса U+2212. */
    private static final char MINUS_SIGN = (char) 0x2212;

    /** Оба тире. */
    private static final String DASHES = new String(new char[] {EM_DASH, EN_DASH});

    /** Знак минуса. */
    private static final String MINUS = String.valueOf(MINUS_SIGN);

    /** Обратная косая черта: собирается из кода, чтобы в исходнике не было escape-последовательностей тире. */
    private static final String BACKSLASH = String.valueOf((char) 0x5C);

    /** Escape-последовательность тире в Java-литерале: после обратной косой черты одна или несколько {@code u} и код. */
    private static final Pattern JAVA_DASH_ESCAPE = Pattern.compile("u+201[34]", Pattern.CASE_INSENSITIVE);

    /** Escape-последовательность знака минуса в Java-литерале. */
    private static final Pattern JAVA_MINUS_ESCAPE = Pattern.compile("u+2212", Pattern.CASE_INSENSITIVE);

    /**
     * Escape-формы тире в ресурсах: Java/JSON ({@code \}{@code u2014}), CSS ({@code \}{@code 2014}, {@code \}{@code 002013})
     * и HTML-сущности ({@code &mdash;}, {@code &#8211;}, {@code &#x2014;}).
     */
    private static final Pattern RESOURCE_DASH_ESCAPE = Pattern.compile(
            Pattern.quote(BACKSLASH) + "u+201[34]"
                    + "|" + Pattern.quote(BACKSLASH) + "0{0,2}201[34](?![0-9a-f])"
                    + "|&[mn]dash;|&#0*821[12];|&#x0*201[34];",
            Pattern.CASE_INSENSITIVE);

    /**
     * Escape-формы знака минуса в ресурсах: Java/JSON ({@code \}{@code u2212}), CSS ({@code \}{@code 2212},
     * {@code \}{@code 002212}) и HTML-сущности ({@code &minus;}, {@code &#8722;}, {@code &#x2212;}).
     */
    private static final Pattern RESOURCE_MINUS_ESCAPE = Pattern.compile(
            Pattern.quote(BACKSLASH) + "u+2212"
                    + "|" + Pattern.quote(BACKSLASH) + "0{0,2}2212(?![0-9a-f])"
                    + "|&minus;|&#0*8722;|&#x0*2212;",
            Pattern.CASE_INSENSITIVE);

    /** Содержимое литерала-заглушки: сам дефис или его escape-последовательность {@code \}{@code u002d}. */
    private static final Pattern LONE_HYPHEN = Pattern.compile("-|" + Pattern.quote(BACKSLASH) + "u+002d",
            Pattern.CASE_INSENSITIVE);

    /** Текст перед литералом-аргументом метода сравнения: {@code text.startsWith(}. */
    private static final Pattern COMPARISON_BEFORE = Pattern.compile(
            "\\.(startsWith|endsWith|equals|contains|indexOf|lastIndexOf)\\(\\s*$");

    /** Текст после литерала, у которого вызывается сравнение: {@code .equals(}. */
    private static final Pattern COMPARISON_AFTER = Pattern.compile("^\\s*\\.(equals|equalsIgnoreCase|contentEquals)\\(");

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
    void mainCodeLiteralsHaveNoMinusSign() {
        List<String> found = javaFiles(moduleDir().resolve("src/main/java")).stream()
                .flatMap(file -> minusSignsInLiterals(moduleDir().relativize(file), read(file)).stream())
                .map(Finding::toString)
                .toList();
        assertTrue(found.isEmpty(), () -> "в текстах интерфейса минус пишется дефисом «-», знак U+2212 не нужен"
                + " (решение от 2026-09-14):\n" + String.join("\n", found));
    }

    @Test
    void mainCodeHasNoLoneHyphenPlaceholder() {
        List<String> found = javaFiles(moduleDir().resolve("src/main/java")).stream()
                .flatMap(file -> loneHyphenLiterals(moduleDir().relativize(file), read(file)).stream())
                .map(Finding::toString)
                .toList();
        assertTrue(found.isEmpty(), () -> "вместо одиночного «-» на месте значения пишутся слова по смыслу"
                + " («за горизонтом», «пропущено», «нет»; решение от 2026-09-14):\n" + String.join("\n", found));
    }

    @Test
    void resourcesHaveNoDashes() {
        List<String> found = new ArrayList<>();
        for (Path file : resourceFiles()) {
            dashesInText(moduleDir().relativize(file), read(file)).forEach(f -> found.add(f.toString()));
        }
        assertTrue(found.isEmpty(), () -> "ресурсы ui-fx без тире целиком, комментарии тоже (решение от 2026-09-14):\n"
                + String.join("\n", found));
    }

    @Test
    void resourcesHaveNoMinusSign() {
        List<String> found = new ArrayList<>();
        for (Path file : resourceFiles()) {
            minusSignsInText(moduleDir().relativize(file), read(file)).forEach(f -> found.add(f.toString()));
        }
        assertTrue(found.isEmpty(), () -> "ресурсы ui-fx без знака минуса U+2212 целиком, комментарии тоже"
                + " (решение от 2026-09-14):\n" + String.join("\n", found));
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
    void scannerFindsMinusSignInLiteralsButNotInComments() {
        String source = String.join("\n",
                "// comment " + MINUS,
                "/* block " + MINUS + " */",
                "class A {",
                "  String a = \"Income " + MINUS + "10 %\";",
                "  char c = '" + MINUS_SIGN + "';",
                "  String e = \"" + BACKSLASH + "u2212\";",
                "  String hyphen = \"Income -10 %\";",
                "  String notEscape = \"" + BACKSLASH + BACKSLASH + "u2212\";",
                "  String block = \"\"\"",
                "      " + MINUS + " line",
                "      \"\"\";",
                "  String upper = \"" + BACKSLASH + "uU2212\"; // " + MINUS,
                "}");
        List<Finding> found = minusSignsInLiterals(Path.of("A.java"), source);
        assertEquals(List.of(4, 5, 6, 10, 12), found.stream().map(Finding::line).toList(), found.toString());
        assertTrue(found.getFirst().toString().startsWith("A.java:4: String a"), found.getFirst().toString());
        assertTrue(dashesInLiterals(Path.of("A.java"), source).isEmpty(), "знак минуса не тире: сканеры не смешиваются");
    }

    @Test
    void loneHyphenScannerFindsPlaceholdersButNotSignChecksOrRealValues() {
        String source = String.join("\n",
                "// comment \"-\"",
                "class A {",
                "  String a = \"-\";",
                "  String b = skipped ? \"-\" : amount;",
                "  boolean c = text.startsWith(\"-\") || text.equals( \"-\");",
                "  boolean d = \"-\".equals(text);",
                "  String e = \"-45 000\" + \"1-31\" + \" - \" + \"--\";",
                "  char f = '-';",
                "  String g = \"" + BACKSLASH + "u002D\";",
                "  String h = \"a\" + \"-\";",
                "  /* \"-\" */ String i = \"\"\"",
                "      -",
                "      \"\"\";",
                "}");
        List<Finding> found = loneHyphenLiterals(Path.of("A.java"), source);
        assertEquals(List.of(3, 4, 9, 10), found.stream().map(Finding::line).toList(), found.toString());
        assertTrue(found.get(1).toString().startsWith("A.java:4: String b = skipped"), found.get(1).toString());
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

    @Test
    void resourceScanFindsRawAndEscapedMinusSign() {
        String css = String.join("\n",
                "/* comment " + MINUS + " */",
                ".a { -fx-padding: 0 4 0 4; }",
                ".b:after { content: \"" + BACKSLASH + "2212\"; }",
                "x = \"" + BACKSLASH + "u2212\"",
                "<b>&minus;</b>",
                "&#8722; &#x2212; " + BACKSLASH + "002212",
                "range 1-31, -45 000",
                ".c { content: \"" + BACKSLASH + "22125\"; }");
        List<Finding> found = minusSignsInText(Path.of("styles.css"), css);
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
        return signsInLiterals(file, source, DASHES, JAVA_DASH_ESCAPE);
    }

    /**
     * Находит знак минуса U+2212 в строковых и символьных литералах Java, включая текстовые блоки; комментарии
     * пропускаются.
     *
     * @param file   имя файла для сообщения
     * @param source исходный текст
     * @return нарушения, не больше одного на строку
     */
    static List<Finding> minusSignsInLiterals(Path file, String source) {
        return signsInLiterals(file, source, MINUS, JAVA_MINUS_ESCAPE);
    }

    /**
     * Находит в литералах Java любой из знаков или его escape-последовательность.
     *
     * @param file   имя файла для сообщения
     * @param source исходный текст
     * @param signs  запрещённые знаки
     * @param escape escape-последовательность без обратной косой черты
     * @return нарушения, не больше одного на строку
     */
    private static List<Finding> signsInLiterals(Path file, String source, String signs, Pattern escape) {
        String[] lines = source.split("\n", -1);
        List<Integer> hits = new ArrayList<>();
        new LiteralWalker(source) {
            @Override
            void literalChar(int index, int line) {
                char c = source.charAt(index);
                boolean escaped = c == '\\' && escape.matcher(source).region(index + 1, source.length()).lookingAt();
                if ((signs.indexOf(c) >= 0 || escaped) && (hits.isEmpty() || hits.getLast() != line)) {
                    hits.add(line);
                }
            }
        }.walk();
        return hits.stream().map(line -> new Finding(file, line, lines[line - 1].strip())).toList();
    }

    /**
     * Находит строковые литералы, равные ровно {@code "-"}: заглушку вместо слов. Литерал-аргумент метода сравнения
     * ({@code text.startsWith("-")}) и литерал, у которого вызывается {@code equals}, не нарушение: они проверяют знак
     * значения и на экран не попадают. Символьные литералы и текстовые блоки не проверяются.
     *
     * @param file   имя файла для сообщения
     * @param source исходный текст
     * @return нарушения, по одному на литерал
     */
    static List<Finding> loneHyphenLiterals(Path file, String source) {
        String[] lines = source.split("\n", -1);
        List<Finding> found = new ArrayList<>();
        new LiteralWalker(source) {
            @Override
            void stringLiteral(int contentStart, int contentEnd, int line) {
                if (!LONE_HYPHEN.matcher(source).region(contentStart, contentEnd).matches()) {
                    return;
                }
                int quote = contentStart - 1;
                int lineStart = source.lastIndexOf('\n', quote) + 1;
                String before = source.substring(lineStart, quote);
                String after = source.substring(Math.min(contentEnd + 1, source.length()));
                if (!COMPARISON_BEFORE.matcher(before).find() && !COMPARISON_AFTER.matcher(after).find()) {
                    found.add(new Finding(file, line, lines[line - 1].strip()));
                }
            }
        }.walk();
        return found;
    }

    /**
     * Находит тире в тексте ресурса целиком: сами знаки и их escape-формы.
     *
     * @param file имя файла для сообщения
     * @param text содержимое
     * @return нарушения по строкам
     */
    static List<Finding> dashesInText(Path file, String text) {
        return signsInText(file, text, DASHES, RESOURCE_DASH_ESCAPE);
    }

    /**
     * Находит знак минуса U+2212 в тексте ресурса целиком: сам знак и его escape-формы.
     *
     * @param file имя файла для сообщения
     * @param text содержимое
     * @return нарушения по строкам
     */
    static List<Finding> minusSignsInText(Path file, String text) {
        return signsInText(file, text, MINUS, RESOURCE_MINUS_ESCAPE);
    }

    /**
     * Находит в тексте ресурса любой из знаков или его escape-форму.
     *
     * @param file   имя файла для сообщения
     * @param text   содержимое
     * @param signs  запрещённые знаки
     * @param escape escape-формы
     * @return нарушения по строкам
     */
    private static List<Finding> signsInText(Path file, String text, String signs, Pattern escape) {
        List<Finding> found = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            boolean sign = line.chars().anyMatch(c -> signs.indexOf(c) >= 0);
            if (sign || escape.matcher(line).find()) {
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
     * Проход по исходнику Java с различением комментариев и литералов. Наследник получает начало каждого литерала,
     * каждый символ внутри него (включая обратную косую черту escape-последовательности, после которой символ
     * пропускается) и границы содержимого каждого обычного строкового литерала.
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

        /**
         * Закончился обычный строковый литерал (не символьный и не текстовый блок).
         *
         * @param contentStart позиция первого символа содержимого (сразу после открывающей кавычки)
         * @param contentEnd   позиция закрывающей кавычки (или конца строки, если литерал не закрыт)
         * @param line         номер строки с 1
         */
        void stringLiteral(int contentStart, int contentEnd, int line) {
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
                    int start = i;
                    while (i < n && source.charAt(i) != c && source.charAt(i) != '\n') {
                        literalChar(i, line);
                        i += source.charAt(i) == '\\' ? 2 : 1;
                    }
                    if (c == '"') {
                        stringLiteral(start, Math.min(i, n), line);
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

    /**
     * Текстовые ресурсы модуля: основные и тестовые, без двоичных файлов.
     *
     * @return файлы в порядке путей
     */
    private static List<Path> resourceFiles() {
        List<Path> result = new ArrayList<>();
        for (Path root : List.of(moduleDir().resolve("src/main/resources"), moduleDir().resolve("src/test/resources"))) {
            files(root).stream().filter(file -> !BINARY_EXTENSIONS.contains(extension(file))).forEach(result::add);
        }
        return result;
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
