package ru.cashprediction.web.js;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет границы стека и текстов нового браузерного рендерера. */
class NoCyrillicLiteralsInJsTest {
    private static final Path RESOURCES = Files.isDirectory(Path.of("src/main/resources/web/app"))
            ? Path.of("src/main/resources/web") : Path.of("web/src/main/resources/web");

    /** В исходных ресурсах нет запрещённых тире и знака типографского минуса. */
    @Test void forbiddenGlyphs() throws Exception {
        for (Path file : files()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String forbidden = "[" + Character.toString(8211) + Character.toString(8212) + Character.toString(8722) + "]";
            assertFalse(Pattern.compile(forbidden).matcher(text).find(), file.toString());
        }
    }

    /** Русские комментарии разрешены, русские литералы интерфейса запрещены. */
    @Test void sharedLocalizationOnly() throws Exception {
        for (Path file : files()) {
            if (!file.toString().endsWith(".js")) continue;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            String code = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
            assertFalse(Pattern.compile("[\\u0400-\\u04ff]").matcher(code).find(), file.toString());
        }
    }

    /** Рендерер не зависит от загрузчиков внешних пакетов или CDN. */
    @Test void jdkAndVanillaOnly() throws Exception {
        for (Path file : files()) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(Pattern.compile("(?i)(cdn\\.|unpkg|jsdelivr|require\\(|node:|from ['\"](?:react|vue|jquery))").matcher(text).find(), file.toString());
        }
    }

    /** Тестовые модули доступны только через динамический импорт после флага bootstrap. */
    @Test void testApiIsOptInAndPassive() throws Exception {
        String main = Files.readString(RESOURCES.resolve("app/main.js"));
        String driver = Files.readString(RESOURCES.resolve("app/test-driver.js"));
        assertTrue(main.contains("data.testApi && !this.testDriver"));
        assertTrue(main.contains("import('./test-driver.js')"));
        assertTrue(main.indexOf("await this.installDriver()") < main.indexOf("else await this.renderMain()"));
        assertTrue(driver.contains("takeStep()"));
        assertFalse(driver.contains("'/api/test/result'"));
        assertFalse(driver.contains("'/api/test/dump'"));
        assertFalse(driver.contains("ModelUiDriver"));
    }

    /** Возвращает только принадлежащие этому заданию ресурсы. */
    private static List<Path> files() throws Exception {
        try (var stream = Files.walk(RESOURCES.resolve("app"))) {
            var files = new java.util.ArrayList<>(stream.filter(Files::isRegularFile).toList());
            files.add(RESOURCES.resolve("app.html")); return files;
        }
    }
}
