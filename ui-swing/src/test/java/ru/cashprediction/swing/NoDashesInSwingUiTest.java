package ru.cashprediction.swing;

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
 * Решение пользователя от 2026-09-14: в интерфейсе Swing-клиента нет длинного тире (U+2014) и среднего тире (U+2013),
 * только дефис-минус {@code -}.
 *
 * <p><b>Что проверяется.</b> Все строковые и символьные литералы и текстовые блоки основного кода модуля
 * ({@code src/main/java}): именно они попадают в заголовки, меню, подсказки, сообщения, таблицу, график и строку
 * состояния. Комментарии и Javadoc интерфейсом не являются и не проверяются. Кроме того, проверяется каждый текстовый
 * файл ресурсов {@code src/main/resources} и {@code src/test/resources} целиком. Тире ищется в любом виде: сам символ,
 * escape-последовательность {@code \}{@code u2014} и HTML-сущность ({@code &mdash;}, {@code &#8212;},
 * {@code &#x2014;} и то же для среднего тире). Исключений нет.</p>
 *
 * <p><b>Минус и прочерк.</b> Решение пользователя от того же дня: типографского знака минуса (U+2212) в текстах
 * клиента тоже нет (он ищется в тех же литералах и ресурсах: символ, escape-последовательность и сущность
 * {@code &minus;}), а отсутствующее значение пишется словами («за горизонтом», «пропущено», «нет данных»,
 * «ещё не записан»), поэтому литерал, состоящий из одного дефиса ({@code "-"} или {@code '-'}), запрещён. Дефис
 * внутри настоящего значения (отрицательная сумма, диапазон дат «01.09.2026 - 31.10.2026», разделитель
 * {@code " - "}) не проверяется. Разбор введённой суммы с U+2212 живёт в ядре ({@code Money.parse}), в Swing-клиенте
 * исключений нет.</p>
 *
 * <p>Сообщение теста перечисляет {@code файл:строка: текст}. Папка модуля берётся из рабочей папки: сама папка
 * {@code ui-swing} (Maven) или её подпапка в корне репозитория (IDE); если она не найдена, тест падает, а не проходит
 * молча.</p>
 */
class NoDashesInSwingUiTest {

    /** Длинное тире. */
    private static final char EM_DASH = (char) 0x2014;
    /** Среднее тире. */
    private static final char EN_DASH = (char) 0x2013;

    /** Тире в виде escape-последовательности Java или JS (в том числе {@code \}{@code uu2014}). */
    private static final Pattern ESCAPED = Pattern.compile("\\\\u+201[34]", Pattern.CASE_INSENSITIVE);
    /** Тире в виде HTML-сущности. */
    private static final Pattern ENTITY = Pattern.compile("&(mdash|ndash|#821[12]|#x201[34]);", Pattern.CASE_INSENSITIVE);

    /** Типографский знак минуса. */
    private static final char MINUS = (char) 0x2212;
    /** Знак минуса в виде escape-последовательности Java или JS. */
    private static final Pattern ESCAPED_MINUS = Pattern.compile("\\\\u+2212", Pattern.CASE_INSENSITIVE);
    /** Знак минуса в виде HTML-сущности. */
    private static final Pattern ENTITY_MINUS = Pattern.compile("&(minus|#8722|#x2212);", Pattern.CASE_INSENSITIVE);

    /** Двоичные ресурсы: в них случайные байты могут совпасть с кодом тире в UTF-8. */
    private static final Set<String> BINARY = Set.of("png", "jpg", "jpeg", "gif", "ico", "bmp", "class", "jar", "zip");

    /** Файл, по которому узнаётся папка модуля ui-swing. */
    private static final String MARKER = "src/main/java/ru/cashprediction/swing";

    @Test
    void mainCodeLiteralsHaveNoDashes() {
        List<String> found = new ArrayList<>();
        for (Path file : files(moduleDir().resolve("src/main/java"))) {
            if (!file.toString().endsWith(".java")) {
                continue;
            }
            for (Literal literal : literals(file, read(file))) {
                if (hasDash(literal.text())) {
                    found.add(literal.toString());
                }
            }
        }
        assertEquals(List.of(), found, "в текстах Swing-клиента вместо тире пишется дефис-минус \"-\"");
    }

    @Test
    void resourcesHaveNoDashes() {
        List<String> found = new ArrayList<>();
        for (String folder : List.of("src/main/resources", "src/test/resources")) {
            for (Path file : files(moduleDir().resolve(folder))) {
                if (BINARY.contains(extension(file))) {
                    continue;
                }
                String[] lines = read(file).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (hasDash(lines[i])) {
                        found.add(moduleDir().relativize(file) + ":" + (i + 1) + ": " + lines[i].strip());
                    }
                }
            }
        }
        assertEquals(List.of(), found, "в ресурсах Swing-клиента вместо тире пишется дефис-минус \"-\"");
    }

    @Test
    void mainCodeLiteralsHaveNoTypographicMinus() {
        List<String> found = new ArrayList<>();
        for (Path file : files(moduleDir().resolve("src/main/java"))) {
            if (!file.toString().endsWith(".java")) {
                continue;
            }
            for (Literal literal : literals(file, read(file))) {
                if (hasMinus(literal.text())) {
                    found.add(literal.toString());
                }
            }
        }
        assertEquals(List.of(), found, "в текстах Swing-клиента вместо знака минуса U+2212 пишется дефис-минус \"-\"");
    }

    @Test
    void resourcesHaveNoTypographicMinus() {
        List<String> found = new ArrayList<>();
        for (String folder : List.of("src/main/resources", "src/test/resources")) {
            for (Path file : files(moduleDir().resolve(folder))) {
                if (BINARY.contains(extension(file))) {
                    continue;
                }
                String[] lines = read(file).split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (hasMinus(lines[i])) {
                        found.add(moduleDir().relativize(file) + ":" + (i + 1) + ": " + lines[i].strip());
                    }
                }
            }
        }
        assertEquals(List.of(), found, "в ресурсах Swing-клиента вместо знака минуса U+2212 пишется дефис-минус \"-\"");
    }

    @Test
    void mainCodeHasNoLoneHyphenPlaceholders() {
        List<String> found = new ArrayList<>();
        for (Path file : files(moduleDir().resolve("src/main/java"))) {
            if (!file.toString().endsWith(".java")) {
                continue;
            }
            for (Literal literal : literals(file, read(file))) {
                if (isLoneHyphen(literal.text())) {
                    found.add(literal.toString());
                }
            }
        }
        assertEquals(List.of(), found,
                "отсутствующее значение в Swing-клиенте пишется словами («за горизонтом», «пропущено», «нет данных»), а не прочерком \"-\"");
    }

    @Test
    void minusAndLoneHyphenChecksFindEveryForm() {
        String minus = String.valueOf(MINUS);
        String source = "// \"Доходы " + minus + "10 %\" в комментарии не литерал\n"
                + "class B {\n"
                + "    String a = \"Доходы " + minus + "10 %\";\n"
                + "    String e = \"\\u2212\";\n"
                + "    String h = \"<html>&minus;10 %</html>\";\n"
                + "    String dash = \"-\";\n"
                + "    char sign = '-';\n"
                + "    String amount = \"-45 000,00\";\n"
                + "    String range = \" - \";\n"
                + "    String words = \"за горизонтом\";\n"
                + "}\n";
        List<Literal> literals = literals(Path.of("B.java"), source);
        assertEquals(8, literals.size(), literals.toString());
        assertEquals(List.of(3, 4, 5), literals.stream().filter(l -> hasMinus(l.text())).map(Literal::line).toList(),
                literals.toString());
        assertEquals(List.of(6, 7), literals.stream().filter(l -> isLoneHyphen(l.text())).map(Literal::line).toList(),
                literals.toString());
        assertTrue(hasMinus("&#8722;") && hasMinus("&#X2212;") && hasMinus("\\uu2212") && !hasMinus("-10 %"));
        assertTrue(!isLoneHyphen("- ") && !isLoneHyphen("--") && !isLoneHyphen(""), "только дефис целиком");
    }

    @Test
    void scannedCodeExistsSoTheCheckIsNotEmpty() {
        Path main = moduleDir().resolve("src/main/java");
        assertTrue(Files.isDirectory(main.resolve("ru/cashprediction/swing")), main.toString());
        long count = files(main).stream().filter(f -> f.toString().endsWith(".java"))
                .mapToLong(f -> literals(f, read(f)).size()).sum();
        assertTrue(count > 0, "сканер должен находить литералы реального клиента");
        Path entry = main.resolve("ru/cashprediction/swing/SwingMain.java");
        Path port = main.resolve("ru/cashprediction/swing/ui/SwingUiPort.java");
        assertTrue(Files.isRegularFile(entry) && Files.isRegularFile(port), "точка входа и рендерер должны существовать");
        assertTrue(literals(entry, read(entry)).stream().anyMatch(l -> l.text().equals("java.util.prefs")));
        assertTrue(!literals(port, read(port)).isEmpty(), "сканируется настоящий рендерер");
    }

    @Test
    void scannerSkipsCommentsAndFindsEveryLiteralKind() {
        String em = String.valueOf(EM_DASH);
        String en = String.valueOf(EN_DASH);
        String source = "// \"комментарий " + em + "\" не литерал\n"
                + "/* \"и этот " + en + "\"\n   тоже нет */\n"
                + "/** Javadoc " + em + " */\n"
                + "class A {\n"
                + "    String a = \"CashPrediction " + em + " ошибка\";\n"
                + "    char c = '" + en + "';\n"
                + "    char q = '\"';\n"
                + "    String e = \"\\u2014\";\n"
                + "    String h = \"<html>1&ndash;31</html>\";\n"
                + "    String slashes = \"/* не комментарий */\"; // \"после " + em + "\"\n"
                + "    String t = \"\"\"\n        блок " + em + " с \\\"\"\" внутри\n        \"\"\";\n"
                + "    String ok = \"Enter - применить\";\n"
                + "}\n";
        List<Literal> literals = literals(Path.of("A.java"), source);
        List<String> texts = literals.stream().map(Literal::text).toList();
        assertEquals(8, texts.size(), texts.toString());
        assertEquals(List.of(6, 7, 9, 10, 12), literals.stream().filter(l -> hasDash(l.text())).map(Literal::line).toList(),
                texts.toString());
        assertTrue(texts.contains("\""), "символ кавычки в символьном литерале");
        assertTrue(texts.contains("/* не комментарий */"), texts.toString());
        assertTrue(texts.contains("Enter - применить"), texts.toString());
        assertTrue(hasDash("&#x2014;") && hasDash("&MDASH;") && hasDash("\\uu2013") && !hasDash("a - b"));
    }

    /**
     * Есть ли в тексте тире в любом виде.
     *
     * @param text текст литерала или строки ресурса
     * @return {@code true}, если есть символ, escape-последовательность или HTML-сущность тире
     */
    private static boolean hasDash(String text) {
        return text.indexOf(EM_DASH) >= 0 || text.indexOf(EN_DASH) >= 0
                || ESCAPED.matcher(text).find() || ENTITY.matcher(text).find();
    }

    /**
     * Есть ли в тексте типографский знак минуса в любом виде.
     *
     * @param text текст литерала или строки ресурса
     * @return {@code true}, если есть символ U+2212, его escape-последовательность или HTML-сущность
     */
    private static boolean hasMinus(String text) {
        return text.indexOf(MINUS) >= 0 || ESCAPED_MINUS.matcher(text).find() || ENTITY_MINUS.matcher(text).find();
    }

    /**
     * Состоит ли литерал из одного дефиса: так раньше показывалось отсутствующее значение (прочерк).
     *
     * @param text текст строкового или символьного литерала
     * @return {@code true} для {@code "-"} и {@code '-'}; дефис внутри значения или разделитель {@code " - "} не в счёт
     */
    private static boolean isLoneHyphen(String text) {
        return text.equals("-");
    }

    /**
     * Литерал исходника: файл, строка начала и текст между кавычками (escape-последовательности не раскрываются).
     *
     * @param file файл
     * @param line номер строки, с которой начинается литерал
     * @param text текст литерала
     */
    private record Literal(Path file, int line, String text) {

        /** {@inheritDoc} */
        @Override
        public String toString() {
            return file.getFileName() + ":" + line + ": " + text.replace("\n", "\\n");
        }
    }

    /**
     * Находит строковые и символьные литералы и текстовые блоки Java, пропуская комментарии.
     *
     * @param file   файл для сообщений
     * @param source текст исходника
     * @return литералы в порядке появления
     */
    private static List<Literal> literals(Path file, String source) {
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
                int j = i + 3;
                while (j < n && !source.startsWith("\"\"\"", j)) {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                result.add(new Literal(file, lineOf(source, i), source.substring(i + 3, Math.min(j, n))));
                i = j + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                result.add(new Literal(file, lineOf(source, i), source.substring(i + 1, Math.min(j, n))));
                i = j + 1;
            } else {
                i++;
            }
        }
        return result;
    }

    /**
     * Номер строки позиции.
     *
     * @param source текст
     * @param index  позиция
     * @return номер строки, начиная с 1
     */
    private static int lineOf(String source, int index) {
        int line = 1;
        for (int k = source.indexOf('\n'); k >= 0 && k < index; k = source.indexOf('\n', k + 1)) {
            line++;
        }
        return line;
    }

    /**
     * Папка модуля ui-swing: рабочая папка (Maven) или её подпапка {@code ui-swing} (запуск из корня).
     *
     * @return абсолютный путь
     */
    private static Path moduleDir() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (Path candidate : List.of(cwd, cwd.resolve("ui-swing"))) {
            if (Files.isDirectory(candidate.resolve(MARKER))) {
                return candidate;
            }
        }
        throw new IllegalStateException("не найдена папка модуля ui-swing от " + cwd);
    }

    /**
     * Все файлы папки рекурсивно; отсутствующая папка даёт пустой список.
     *
     * @param dir папка
     * @return файлы в порядке путей
     */
    private static List<Path> files(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Текст файла в UTF-8 с переводами строк {@code \n}.
     *
     * @param file файл
     * @return текст
     */
    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Расширение файла в нижнем регистре.
     *
     * @param file файл
     * @return расширение без точки или пустая строка
     */
    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
