package ru.cashprediction.fx.ui;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет общий каталог текстов во всём клиенте; русский текст комментариев разрешён. */
class NoCyrillicLiteralsTest {
    /** Проверяет реальные исходники, включая точку входа, модуль и рендерер. */
    @Test void rendererUsesSharedTexts() throws Exception {
        String basedir = System.getProperty("fx.basedir");
        assertNotNull(basedir, "путь модуля должен быть задан");
        Path root = Path.of(basedir, "src/main/java");
        for (String required : List.of("module-info.java", "ru/cashprediction/fx/FxMain.java",
                "ru/cashprediction/fx/ui/FxUiPort.java"))
            assertTrue(Files.isRegularFile(root.resolve(required)), required);
        List<Path> sources;
        try (var paths = Files.walk(root)) {
            sources = paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertTrue(sources.size() >= 3, "обход должен включать реальные исходники");
        int literalCount = 0;
        for (Path path : sources) {
            Scan scan = scan(Files.readString(path));
            literalCount += scan.literals().size();
            for (String literal : scan.literals())
                assertFalse(hasCyrillic(literal), path + ": " + literal);
            assertFalse(scan.code().contains("ModelUiDriver") || scan.code().contains("ModelDump"), path.toString());
        }
        assertTrue(literalCount > 0, "сканер должен найти литералы настоящего клиента");
    }

    /** Результат обхода сохраняет литералы и код без комментариев независимо. */
    private record Scan(String code, List<String> literals) { }

    /** Распознаёт кириллицу, включая расширенные блоки Unicode. */
    private static boolean hasCyrillic(String text) {
        return text.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC);
    }

    /**
     * Однократно раскрывает Unicode-escape до распознавания комментариев и кавычек.
     * Несколько букв u допустимы. Для защитного сканера даже escape после экранированного
     * слеша проверяется консервативно: это не позволяет спрятать русскую строку в шаблоне.
     */
    private static String unicode(String raw) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length();) {
            if (raw.charAt(i) == '\\' && i + 1 < raw.length() && raw.charAt(i + 1) == 'u') {
                int digits = i + 1;
                while (digits < raw.length() && raw.charAt(digits) == 'u') digits++;
                if (digits + 4 <= raw.length()) {
                    int value = 0;
                    boolean valid = true;
                    for (int p = digits; p < digits + 4; p++) {
                        int digit = Character.digit(raw.charAt(p), 16);
                        if (digit < 0) { valid = false; break; }
                        value = value * 16 + digit;
                    }
                    if (valid) { out.append((char) value); i = digits + 4; continue; }
                }
            }
            out.append(raw.charAt(i++));
        }
        return out.toString();
    }

    /** Лексически пропускает комментарии, сохраняя URL, символьные литералы и текстовые блоки. */
    private static Scan scan(String raw) {
        String source = unicode(raw);
        StringBuilder code = new StringBuilder();
        List<String> literals = new ArrayList<>();
        for (int i = 0; i < source.length();) {
            if (source.startsWith("//", i)) {
                int end = i + 2;
                while (end < source.length() && source.charAt(end) != '\n' && source.charAt(end) != '\r') end++;
                i = end;
                code.append(' ');
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                if (end < 0) throw new IllegalArgumentException("Unterminated comment");
                i = end + 2;
                code.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                int start = i;
                i += 3;
                int content = i;
                while (i < source.length() && !source.startsWith("\"\"\"", i))
                    i += source.charAt(i) == '\\' ? Math.min(2, source.length() - i) : 1;
                if (i >= source.length()) throw new IllegalArgumentException("Unterminated text block");
                literals.add(source.substring(content, i));
                i += 3;
                code.append(source, start, i);
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                int start = i;
                char quote = source.charAt(i++);
                int content = i;
                boolean closed = false;
                while (i < source.length()) {
                    char c = source.charAt(i++);
                    if (c == '\\' && i < source.length()) i++;
                    else if (c == quote) { closed = true; break; }
                    else if (c == '\n' || c == '\r') throw new IllegalArgumentException("Newline in literal");
                }
                if (!closed) throw new IllegalArgumentException("Unterminated literal");
                literals.add(source.substring(content, i - 1));
                code.append(source, start, i);
            } else code.append(source.charAt(i++));
        }
        return new Scan(code.toString(), List.copyOf(literals));
    }

    @Test void urlAndCommentMarkersCannotHideCyrillic() {
        String letter = String.valueOf((char) 0x410);
        Scan result = scan("String a=\"https://host/" + letter + "\"; String b=\"/*" + letter + "*/\";");
        assertEquals(2, result.literals().size());
        assertTrue(result.literals().stream().allMatch(NoCyrillicLiteralsTest::hasCyrillic));
    }

    @Test void unicodeEscapesAndMultipleUAreDetectedInStringsAndChars() {
        String escape = "\\" + "u";
        Scan result = scan("String a=\"" + escape + "0410\"; char b='" + escape + "uuu044f';");
        assertEquals(2, result.literals().size());
        assertTrue(result.literals().stream().allMatch(NoCyrillicLiteralsTest::hasCyrillic));
        assertEquals("A", unicode(escape + "0041"));
    }

    @Test void unicodeTranslationPrecedesCommentsAndDoesNotRecurse() {
        String escape = "\\" + "u";
        String letter = String.valueOf((char) 0x410);
        assertTrue(scan("// comment" + escape + "000a\"" + letter + "\"").literals().stream()
                .anyMatch(NoCyrillicLiteralsTest::hasCyrillic));
        assertTrue(scan(escape + "002f" + escape + "002f " + letter + "\r\"safe\"").literals()
                .stream().noneMatch(NoCyrillicLiteralsTest::hasCyrillic));
        assertEquals("\\" + "u0410", unicode(escape + "005cu0410"));
    }

    @Test void textBlocksPreserveUrlCommentMarkersAndEscapedDelimiter() {
        String letter = String.valueOf((char) 0x410);
        String block = "\"\"\"\nhttps://host/" + letter + "\n/* literal */ // literal\n\\\"\"\" remains\n\"\"\"";
        Scan result = scan(block);
        assertEquals(1, result.literals().size());
        assertTrue(hasCyrillic(result.literals().getFirst()));
        assertTrue(result.literals().getFirst().contains("/* literal */"));
        assertTrue(result.literals().getFirst().contains("// literal"));
    }

    @Test void commentsMayContainCyrillicAndModelNamesWithoutHidingFollowingLiterals() {
        String letter = String.valueOf((char) 0x410);
        Scan result = scan("// " + letter + " ModelDump\r\n/* " + letter + " ModelUiDriver */ \"safe\" '\\''");
        assertEquals(List.of("safe", "\\'"), result.literals());
        assertFalse(result.code().contains("ModelDump") || result.code().contains("ModelUiDriver"));
        assertTrue(result.literals().stream().noneMatch(NoCyrillicLiteralsTest::hasCyrillic));
    }

    @Test void escapedQuotesAndBackslashesDoNotEndLiteralEarly() {
        String letter = String.valueOf((char) 0x410);
        Scan result = scan("\"escaped \\\" // " + letter + " \\\\ tail\"");
        assertEquals(1, result.literals().size());
        assertTrue(hasCyrillic(result.literals().getFirst()));
    }

    @Test void malformedInputFailsClosedRatherThanSilentlyPassing() {
        for (String source : List.of("\"unterminated", "/* unterminated", "\"\"\"\nunterminated", "\"line\nbreak\""))
            assertThrows(IllegalArgumentException.class, () -> scan(source), source);
    }
}
