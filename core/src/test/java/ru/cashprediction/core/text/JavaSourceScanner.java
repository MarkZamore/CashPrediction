package ru.cashprediction.core.text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Мини-лексер исходников Java для тестов каталога текстов: находит строковые и символьные литералы (включая текстовые
 * блоки), пропуская комментарии, и запоминает код перед каждым литералом.
 *
 * <p>Нужен двум проверкам решения L13: в новом коде нет русских литералов и все ключи, переданные литералом в
 * {@code UiText.get}/{@code Texts.get}, существуют в каталоге. Этапы S1–S3 используют его для исходников клиентов.</p>
 */
public final class JavaSourceScanner {

    /**
     * Литерал исходника.
     *
     * @param file      файл
     * @param line      номер строки начала литерала (с 1)
     * @param raw       содержимое между кавычками как в исходнике (escape-последовательности не раскрыты)
     * @param codeBefore до 80 символов кода (без комментариев) непосредственно перед литералом
     */
    public record Literal(Path file, int line, String raw, String codeBefore) {
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
        if (!Files.exists(root)) {
            return List.of();
        }
        List<Literal> result = new ArrayList<>();
        try (Stream<Path> files = Files.isDirectory(root) ? Files.walk(root) : Stream.of(root)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                result.addAll(scan(file, Files.readString(file, StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    /**
     * Литералы одного текста исходника.
     *
     * @param file   файл (для отчёта)
     * @param source текст
     * @return литералы
     */
    public static List<Literal> scan(Path file, String source) {
        List<Literal> result = new ArrayList<>();
        StringBuilder code = new StringBuilder();
        int line = 1;
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                code.append(c);
                i++;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                line += count(source, i, end);
                i = end;
                code.append(' ');
            } else if (c == '"' && source.startsWith("\"\"\"", i)) {
                int start = i + 3;
                int j = start;
                while (j < n && !(source.startsWith("\"\"\"", j) && source.charAt(j - 1) != '\\')) {
                    j++;
                }
                result.add(new Literal(file, line, source.substring(start, Math.min(j, n)), tail(code)));
                line += count(source, i, Math.min(j + 3, n));
                i = Math.min(j + 3, n);
                code.append("\"\"");
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                result.add(new Literal(file, line, source.substring(i + 1, Math.min(j, n)), tail(code)));
                i = Math.min(j + 1, n);
                code.append(c).append(c);
            } else {
                code.append(c);
                i++;
            }
        }
        return result;
    }

    /**
     * Есть ли в литерале кириллица (буквы U+0400–U+04FF или их escape-последовательности {@code \}{@code u04XX}).
     *
     * @param raw содержимое литерала
     * @return {@code true}, если есть
     */
    public static boolean hasCyrillic(String raw) {
        for (int k = 0; k < raw.length(); k++) {
            char ch = raw.charAt(k);
            if (ch >= 0x0400 && ch <= 0x04FF) {
                return true;
            }
        }
        return raw.matches("(?s).*\\\\u+04[0-9A-Fa-f]{2}.*");
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
        int from = Math.max(0, code.length() - 80);
        return code.substring(from);
    }
}
