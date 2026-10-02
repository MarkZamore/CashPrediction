package ru.cashprediction.fx.ui;

import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет отсутствие русских литералов в новом адаптере; комментарии разрешены. */
class NoCyrillicLiteralsTest {
    /** Проверяет каждый строковый литерал после удаления комментариев. */
    @Test void rendererUsesSharedTexts() throws Exception {
        Path root = Path.of(System.getProperty("fx.basedir"), "src/main/java/ru/cashprediction/fx/ui");
        Pattern literals = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path).replaceAll("(?s)/\\*.*?\\*/|(?m)//[^\\r\\n]*", "");
                var matcher = literals.matcher(source);
                while (matcher.find()) assertFalse(matcher.group().codePoints().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC), path + ": " + matcher.group());
                assertFalse(source.contains("ModelUiDriver") || source.contains("ModelDump"), path.toString());
            }
        }
    }
}
