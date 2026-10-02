package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Новый тонкий рендерер не добавляет кириллические литералы и обходы модельного дампа. */
class NoCyrillicLiteralsTest {
    private Path root() { Path path = Path.of("src/main/java/ru/cashprediction/swing/ui"); return Files.isDirectory(path) ? path : Path.of("ui-swing").resolve(path); }
    @Test void visibleTextsDoNotUseCyrillicLiterals() throws Exception {
        Pattern literal = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'", Pattern.DOTALL);
        try (var files = Files.walk(root())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "");
                var matcher = literal.matcher(source); while (matcher.find()) assertFalse(matcher.group().matches("(?s).*[\\u0400-\\u04ff].*"), file + ": " + matcher.group());
            }
        }
    }
    @Test void noBusinessOrModelDumpReferences() throws Exception {
        try (var files = Files.walk(root())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file).replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "");
                for (String forbidden : List.of("PlanDocument", "ForecastEngine", "OccurrenceGenerator", "GoalCalculator", "PlanRepository", "PlanMarkdownReader", "PlanMarkdownWriter", "ModelUiDriver", "ModelDump"))
                    assertFalse(Pattern.compile("\\b" + forbidden + "\\b").matcher(source).find(), file + ": " + forbidden);
            }
        }
    }
}
