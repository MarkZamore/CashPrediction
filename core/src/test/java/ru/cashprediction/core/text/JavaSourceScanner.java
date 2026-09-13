package ru.cashprediction.core.text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Мини-лексер исходников для тестов каталога текстов: находит строковые литералы и код без комментариев.
 *
 * <p><b>Что умеет.</b> Для Java ({@link Syntax#JAVA}) — строки, символьные литералы и текстовые блоки; для
 * JavaScript ({@link Syntax#JAVASCRIPT}, web-клиент этапа S3) — строки в одинарных и двойных кавычках, шаблонные
 * строки с {@code ${…}} и литералы регулярных выражений. Комментарии {@code //} и {@code /* … * /} (в том числе
 * Javadoc и JSDoc) пропускаются с учётом границ строк: {@code "//"} внутри строки комментарием не считается.</p>
 *
 * <p><b>Кто пользуется.</b> Проверки решения L13: в основном коде нет кириллицы вне комментариев
 * ({@link #cyrillicInCode(Path, Syntax)}, {@code NoCyrillicLiteralsTest}); ключи каталога, переданные литералом,
 * существуют и используются, а число аргументов совпадает с подстановками ({@link Literal#codeAfter()},
 * {@link #argumentsAfter(String)}, {@code TextKeyUsage}); слова формата используются ({@code FormatWordsTest}).
 * Этап S3 применяет те же методы к исходникам ui-fx, ui-swing и web (Java и JS).</p>
 *
 * <p>Это не полный лексер: юникод-escape вне литералов не раскрываются, а литерал регулярного выражения в JS
 * определяется по предыдущему символу кода. Для исходников проекта этого достаточно; самопроверка — в
 * {@code NoCyrillicLiteralsTest}.</p>
 */
public final class JavaSourceScanner {

    /** Сколько символов кода до литерала запоминается в {@link Literal#codeBefore()}. */
    private static final int BEFORE = 80;

    /** Сколько символов кода после литерала запоминается в {@link Literal#codeAfter()}. */
    private static final int AFTER = 600;

    /** Слова JS, после которых {@code /} начинает регулярное выражение, а не деление. */
    private static final Pattern JS_REGEX_KEYWORD =
            Pattern.compile("(?:^|[^A-Za-z0-9_$])(?:return|typeof|case|do|else|in|of|new|delete|void|throw|yield|await)$");

    /** Экранированная кириллица: {@code \}{@code u04XX} (Java и JS) или {@code \}{@code u{4XX}} (JS). */
    private static final Pattern ESCAPED_CYRILLIC =
            Pattern.compile("\\\\u+04[0-9A-Fa-f]{2}|\\\\u\\{0*4[0-9A-Fa-f]{2}\\}");

    /** Синтаксис исходника: какие литералы бывают и какие файлы относятся к языку. */
    public enum Syntax {
        /** Java: {@code "…"}, {@code '…'}, текстовые блоки {@code """…"""}. */
        JAVA(".java"),
        /** JavaScript: {@code "…"}, {@code '…'}, шаблонные строки {@code `…`}, регулярные выражения {@code /…/}. */
        JAVASCRIPT(".js");

        private final String extension;

        Syntax(String extension) {
            this.extension = extension;
        }

        /**
         * Относится ли файл к языку.
         *
         * @param file путь
         * @return {@code true}, если расширение совпадает
         */
        public boolean matches(Path file) {
            return file.getFileName() != null
                    && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extension);
        }
    }

    /**
     * Литерал исходника.
     *
     * @param file       файл
     * @param line       номер строки начала литерала (с 1)
     * @param raw        содержимое между кавычками как в исходнике (escape-последовательности не раскрыты)
     * @param codeBefore до 80 символов кода непосредственно перед литералом: без комментариев, другие литералы
     *                   сжаты до пустых кавычек
     * @param codeAfter  до 600 символов кода сразу после закрывающей кавычки в том же виде (для подсчёта аргументов
     *                   вызова, {@link #argumentsAfter(String)})
     */
    public record Literal(Path file, int line, String raw, String codeBefore, String codeAfter) {
    }

    /**
     * Строка исходника с кириллицей вне комментариев.
     *
     * @param file файл
     * @param line номер строки (с 1)
     * @param code строка без комментариев, обрезанная по краям
     */
    public record CodeLine(Path file, int line, String code) {

        /** @return {@code путь:строка: код} для сообщения теста */
        @Override
        public String toString() {
            return file + ":" + line + ": " + code;
        }
    }

    private JavaSourceScanner() {
    }

    /**
     * Все литералы файлов {@code .java} в папке (рекурсивно) или одного файла.
     *
     * @param root папка или файл; отсутствующий путь даёт пустой список
     * @return литералы по порядку файлов и позиций
     */
    public static List<Literal> literals(Path root) {
        return literals(root, Syntax.JAVA);
    }

    /**
     * Все литералы файлов языка в папке (рекурсивно) или одного файла.
     *
     * @param root   папка или файл; отсутствующий путь даёт пустой список
     * @param syntax язык исходников
     * @return литералы по порядку файлов и позиций
     */
    public static List<Literal> literals(Path root, Syntax syntax) {
        List<Literal> result = new ArrayList<>();
        for (Path file : files(root, syntax)) {
            result.addAll(parse(file, read(file), syntax).literals());
        }
        return result;
    }

    /**
     * Литералы одного текста исходника Java.
     *
     * @param file   файл (для отчёта)
     * @param source текст
     * @return литералы
     */
    public static List<Literal> scan(Path file, String source) {
        return scan(file, source, Syntax.JAVA);
    }

    /**
     * Литералы одного текста исходника заданного языка.
     *
     * @param file   файл (для отчёта)
     * @param source текст
     * @param syntax язык
     * @return литералы
     */
    public static List<Literal> scan(Path file, String source, Syntax syntax) {
        return parse(file, source, syntax).literals();
    }

    /**
     * Строки кода с кириллицей вне комментариев во всех файлах языка папки (рекурсивно) или в одном файле: буквы
     * U+0400–U+04FF в литералах, идентификаторах и где угодно ещё, а также их escape-последовательности.
     *
     * @param root   папка или файл; отсутствующий путь даёт пустой список
     * @param syntax язык исходников
     * @return найденные строки по порядку файлов
     */
    public static List<CodeLine> cyrillicInCode(Path root, Syntax syntax) {
        List<CodeLine> result = new ArrayList<>();
        for (Path file : files(root, syntax)) {
            result.addAll(cyrillicInCode(file, read(file), syntax));
        }
        return result;
    }

    /**
     * Строки кода с кириллицей вне комментариев в одном тексте исходника.
     *
     * @param file   файл (для отчёта)
     * @param source текст
     * @param syntax язык
     * @return найденные строки
     */
    public static List<CodeLine> cyrillicInCode(Path file, String source, Syntax syntax) {
        List<CodeLine> result = new ArrayList<>();
        String[] lines = stripComments(source, syntax).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (hasCyrillic(lines[i])) {
                result.add(new CodeLine(file, i + 1, lines[i].strip()));
            }
        }
        return result;
    }

    /**
     * Текст исходника без комментариев: символы комментариев заменены пробелами, переводы строк сохранены, поэтому
     * номера строк и позиции совпадают с исходником. Литералы остаются как есть.
     *
     * @param source текст
     * @param syntax язык
     * @return текст той же длины
     */
    public static String stripComments(String source, Syntax syntax) {
        return parse(Path.of(""), source, syntax).stripped();
    }

    /**
     * Есть ли в тексте кириллица (буквы U+0400–U+04FF или их escape-последовательности {@code \}{@code u04XX}).
     *
     * @param raw содержимое литерала или строка кода
     * @return {@code true}, если есть
     */
    public static boolean hasCyrillic(String raw) {
        for (int k = 0; k < raw.length(); k++) {
            char ch = raw.charAt(k);
            if (ch >= 0x0400 && ch <= 0x04FF) {
                return true;
            }
        }
        return ESCAPED_CYRILLIC.matcher(raw).find();
    }

    /**
     * Сколько аргументов идёт в вызове после литерала, если литерал — аргумент вызова и это видно статически.
     *
     * <p>Пример: для {@code Texts.get("k", a, f(b, c))} после литерала {@code "k"} идёт код {@code , a, f(b, c))} —
     * результат 2; для {@code Texts.get("k")} — 0. Запятые во вложенных скобках не считаются. Пусто, если за литералом
     * не запятая и не закрывающая скобка (например {@code "k" + x}), если вызов не помещается в {@link Literal#codeAfter()}
     * или если аргументы передаются готовым массивом ({@code new Object[]…}, {@code args} из varargs вызывающего).</p>
     *
     * @param codeAfter код после литерала ({@link Literal#codeAfter()})
     * @return число аргументов после литерала или пусто
     */
    public static OptionalInt argumentsAfter(String codeAfter) {
        String code = codeAfter.stripLeading();
        if (code.startsWith(")")) {
            return OptionalInt.of(0);
        }
        if (!code.startsWith(",")) {
            return OptionalInt.empty();
        }
        int depth = 0;
        int count = 1;
        int argumentStart = 1;
        for (int i = 1; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    if (c != ')') {
                        return OptionalInt.empty();
                    }
                    // Один аргумент-массив раскрывается varargs: число подстановок статически неизвестно.
                    String last = code.substring(argumentStart, i).strip();
                    if (count == 1 && (last.startsWith("new Object[") || last.equals("args"))) {
                        return OptionalInt.empty();
                    }
                    return OptionalInt.of(count);
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
                argumentStart = i + 1;
            } else if (c == ';' && depth == 0) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.empty();
    }

    /** Результат разбора одного файла: литералы и текст без комментариев. */
    private record Scan(List<Literal> literals, String stripped) {
    }

    /** Литерал до того, как известен код после него. */
    private record Pending(int line, String raw, String codeBefore, int afterIndex) {
    }

    /**
     * Разбирает текст исходника за один проход.
     *
     * @param file   файл (для отчёта)
     * @param source текст
     * @param syntax язык
     * @return литералы и текст без комментариев
     */
    private static Scan parse(Path file, String source, Syntax syntax) {
        List<Pending> pending = new ArrayList<>();
        // code — сжатый код для контекста литералов; stripped — исходник той же длины без комментариев.
        StringBuilder code = new StringBuilder();
        StringBuilder stripped = new StringBuilder(source.length());
        boolean js = syntax == Syntax.JAVASCRIPT;
        int line = 1;
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            int end;
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                end = i;
                while (end < n && source.charAt(end) != '\n') {
                    end++;
                }
                blank(stripped, source, i, end);
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int close = source.indexOf("*/", i + 2);
                end = close < 0 ? n : close + 2;
                blank(stripped, source, i, end);
                code.append(' ');
            } else if (!js && source.startsWith("\"\"\"", i)) {
                end = textBlockEnd(source, i + 3);
                pending.add(literal(source, line, i + 3, Math.max(i + 3, end - 3), code));
                stripped.append(source, i, end);
                code.append("\"\"");
            } else if (c == '"' || c == '\'') {
                end = quotedEnd(source, i + 1, c);
                pending.add(literal(source, line, i + 1, closingStart(source, end, c), code));
                stripped.append(source, i, end);
                code.append(c).append(c);
            } else if (js && c == '`') {
                end = templateEnd(source, i + 1);
                pending.add(literal(source, line, i + 1, closingStart(source, end, '`'), code));
                stripped.append(source, i, end);
                code.append("``");
            } else if (js && c == '/' && regexAllowed(code)) {
                end = regexEnd(source, i + 1);
                pending.add(literal(source, line, i + 1, closingStart(source, end, '/'), code));
                stripped.append(source, i, end);
                code.append("//");
                while (end < n && Character.isLetter(source.charAt(end))) {
                    stripped.append(source.charAt(end));
                    code.append(source.charAt(end));
                    end++;
                }
            } else {
                end = i + 1;
                stripped.append(c);
                code.append(c);
            }
            if (!pending.isEmpty() && pending.getLast().afterIndex() < 0) {
                Pending last = pending.removeLast();
                pending.add(new Pending(last.line(), last.raw(), last.codeBefore(), code.length()));
            }
            line += count(source, i, end);
            i = end;
        }
        List<Literal> literals = new ArrayList<>(pending.size());
        for (Pending p : pending) {
            literals.add(new Literal(file, p.line(), p.raw(), p.codeBefore(),
                    code.substring(p.afterIndex(), Math.min(code.length(), p.afterIndex() + AFTER))));
        }
        return new Scan(List.copyOf(literals), stripped.toString());
    }

    /** @return незавершённый литерал: позиция кода после него заполняется, когда закрывающая кавычка записана */
    private static Pending literal(String source, int line, int from, int to, StringBuilder code) {
        return new Pending(line, source.substring(from, Math.max(from, to)), tail(code), -1);
    }

    /** Заменяет символы комментария пробелами, сохраняя переводы строк. */
    private static void blank(StringBuilder stripped, String source, int from, int to) {
        for (int k = from; k < to; k++) {
            stripped.append(source.charAt(k) == '\n' ? '\n' : ' ');
        }
    }

    /** @return индекс за закрывающими {@code """} текстового блока (или конец текста) */
    private static int textBlockEnd(String source, int from) {
        int j = from;
        while (j < source.length()) {
            if (source.charAt(j) == '\\') {
                j += 2;
            } else if (source.startsWith("\"\"\"", j)) {
                return j + 3;
            } else {
                j++;
            }
        }
        return source.length();
    }

    /** @return индекс за закрывающей кавычкой строки; незакрытая строка заканчивается на переводе строки */
    private static int quotedEnd(String source, int from, char quote) {
        int j = from;
        while (j < source.length() && source.charAt(j) != quote && source.charAt(j) != '\n') {
            j += source.charAt(j) == '\\' ? 2 : 1;
        }
        return j < source.length() && source.charAt(j) == quote ? j + 1 : Math.min(j, source.length());
    }

    /** @return начало закрывающей кавычки (если литерал закрыт) — конец содержимого литерала */
    private static int closingStart(String source, int end, char quote) {
        return end > 0 && end <= source.length() && source.charAt(end - 1) == quote ? end - 1 : end;
    }

    /** @return индекс за закрывающей обратной кавычкой шаблонной строки JS с учётом вложенных {@code ${…}} */
    private static int templateEnd(String source, int from) {
        int j = from;
        int n = source.length();
        while (j < n) {
            char c = source.charAt(j);
            if (c == '\\') {
                j += 2;
            } else if (c == '`') {
                return j + 1;
            } else if (c == '$' && j + 1 < n && source.charAt(j + 1) == '{') {
                j = expressionEnd(source, j + 2);
            } else {
                j++;
            }
        }
        return n;
    }

    /** @return индекс за закрывающей {@code }} выражения {@code ${…}}; строки внутри выражения пропускаются */
    private static int expressionEnd(String source, int from) {
        int depth = 1;
        int j = from;
        int n = source.length();
        while (j < n) {
            char c = source.charAt(j);
            if (c == '{') {
                depth++;
                j++;
            } else if (c == '}') {
                depth--;
                j++;
                if (depth == 0) {
                    return j;
                }
            } else if (c == '"' || c == '\'') {
                j = quotedEnd(source, j + 1, c);
            } else if (c == '`') {
                j = templateEnd(source, j + 1);
            } else {
                j++;
            }
        }
        return n;
    }

    /** @return индекс за закрывающей {@code /} регулярного выражения JS (без флагов) */
    private static int regexEnd(String source, int from) {
        int j = from;
        boolean inClass = false;
        while (j < source.length() && source.charAt(j) != '\n') {
            char c = source.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '/' && !inClass) {
                return j + 1;
            }
            j++;
        }
        return Math.min(j, source.length());
    }

    /** @return может ли {@code /} в этом месте JS-кода начинать регулярное выражение (а не деление) */
    private static boolean regexAllowed(StringBuilder code) {
        int k = code.length() - 1;
        while (k >= 0 && Character.isWhitespace(code.charAt(k))) {
            k--;
        }
        if (k < 0) {
            return true;
        }
        char prev = code.charAt(k);
        if ("(,=:[!&|?{};+-*%<>~^".indexOf(prev) >= 0) {
            return true;
        }
        return JS_REGEX_KEYWORD.matcher(code.substring(Math.max(0, k - 10), k + 1)).find();
    }

    /** @return файлы языка в папке (отсортированы) или сам файл; отсутствующий путь — пусто */
    private static List<Path> files(Path root, Syntax syntax) {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.isDirectory(root) ? Files.walk(root) : Stream.of(root)) {
            return files.filter(Files::isRegularFile).filter(syntax::matches).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int count(String text, int from, int to) {
        int lines = 0;
        for (int k = from; k < to && k < text.length(); k++) {
            if (text.charAt(k) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static String tail(StringBuilder code) {
        int from = Math.max(0, code.length() - BEFORE);
        return code.substring(from);
    }
}
