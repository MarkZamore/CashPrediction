package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Новый тонкий рендерер не добавляет кириллические литералы и обходы модельного дампа. */
class NoCyrillicLiteralsTest {
    private Path root() {
        Path path = Path.of("src/main/java");
        Path root = Files.isDirectory(path) ? path : Path.of("ui-swing").resolve(path);
        assertTrue(Files.isRegularFile(root.resolve("ru/cashprediction/swing/SwingMain.java")), "точка входа должна сканироваться");
        assertTrue(Files.isRegularFile(root.resolve("ru/cashprediction/swing/ui/SwingUiPort.java")), "рендерер должен сканироваться");
        return root;
    }

    /** Проверяет реальный набор файлов, чтобы отсутствие исходников не означало успех. */
    private List<Path> sources() throws Exception {
        try (var files = Files.walk(root())) {
            List<Path> result = files.filter(p -> p.toString().endsWith(".java")).toList();
            assertTrue(result.size() >= 3, "нужны модуль, точка входа и рендерер");
            return result;
        }
    }
    @Test void visibleTextsDoNotUseCyrillicLiterals() throws Exception {
        for (Path file : sources()) {
            String source = withoutComments(Files.readString(file));
            assertFalse(Pattern.compile("[\\u0400-\\u04ff]|\\\\u+04[0-9a-f]{2}", Pattern.CASE_INSENSITIVE)
                    .matcher(source).find(), file.toString());
        }
    }
    @Test void noBusinessOrModelDumpReferences() throws Exception {
        for (Path file : sources()) {
            String source = withoutComments(Files.readString(file));
            for (String forbidden : List.of("PlanDocument", "ForecastEngine", "OccurrenceGenerator", "GoalCalculator", "PlanRepository", "PlanMarkdownReader", "PlanMarkdownWriter", "ModelUiDriver", "ModelDump"))
                assertFalse(Pattern.compile("\\b" + forbidden + "\\b").matcher(source).find(), file + ": " + forbidden);
        }
    }

    /** Убирает комментарии лексически, не принимая URL и маркеры внутри литералов за комментарии. */
    private static String withoutComments(String source) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < source.length();) {
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i + 2);
                i = end < 0 ? source.length() : end;
                result.append(' ');
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                result.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                int start = i;
                i += 3;
                while (i < source.length() && !source.startsWith("\"\"\"", i))
                    i += source.charAt(i) == '\\' ? Math.min(2, source.length() - i) : 1;
                i = Math.min(source.length(), i + 3);
                result.append(source, start, i);
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                int start = i;
                char quote = source.charAt(i++);
                while (i < source.length()) {
                    char c = source.charAt(i++);
                    if (c == '\\' && i < source.length()) i++;
                    else if (c == quote) break;
                }
                result.append(source, start, i);
            } else result.append(source.charAt(i++));
        }
        return result.toString();
    }

    @Test void commentScannerPreservesLiteralContents() {
        assertEquals("String x = \"https://host/" + (char) 0x410 + "\";  \n",
                withoutComments("String x = \"https://host/" + (char) 0x410 + "\"; // comment\n"));
        assertEquals("\"/* literal */\" '\\''  ", withoutComments("\"/* literal */\" '\\'' /* comment */"));
        assertEquals("\"\"\"\n// literal\n\"\"\"", withoutComments("\"\"\"\n// literal\n\"\"\""));
    }
}
