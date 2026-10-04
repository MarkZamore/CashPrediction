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
        verifyPassiveOptIn(main, driver);
    }

    /** Доказывает, что проверка отвергает потерю opt-in, отзыв API и активное исполнение шага. */
    @Test void passiveOptInGuardRejectsRegressions() throws Exception {
        String main = Files.readString(RESOURCES.resolve("app/main.js"));
        String driver = Files.readString(RESOURCES.resolve("app/test-driver.js"));
        assertThrows(AssertionError.class, () -> verifyPassiveOptIn(main.replace("data.testApi && !this.testDriver", "true"), driver));
        assertThrows(AssertionError.class, () -> verifyPassiveOptIn(main.replace("if (this.testApi)", "if (true)"), driver));
        assertThrows(AssertionError.class, () -> verifyPassiveOptIn(main.replace("delete window.cpParityTestApi", "void window.cpParityTestApi"), driver));
        assertThrows(AssertionError.class, () -> verifyPassiveOptIn(main, driver.replace("steps.push({", "app.send({")));
        assertThrows(AssertionError.class, () -> verifyPassiveOptIn(main, driver + "\nfetch('/api/test/result');"));
    }

    /** Проверяет реальные блоки методов, не привязываясь к аргументам renderMain или форматированию. */
    private static void verifyPassiveOptIn(String main, String driver) {
        String install = methodBody(main, "installDriver");
        String bootstrap = methodBody(main, "bootstrap");
        String effect = methodBody(main, "effect");
        matches(install, "this\\.testDriverReady\\s*\\|\\|=\\s*import\\(\\s*['\"]\\./test-driver\\.js['\"]\\s*\\)", "One memoized dynamic import");
        assertFalse(Pattern.compile("(?m)^\\s*import\\s+[^\\n]*test-driver").matcher(main).find(), "No unconditional static driver import");
        matches(bootstrap, "if\\s*\\(\\s*data\\.testApi\\s*&&\\s*!this\\.testDriver\\s*\\)\\s*\\{\\s*await\\s+this\\.installDriver\\(\\)", "Bootstrap server opt-in before import");
        matches(bootstrap, "this\\.testApi\\s*=\\s*data\\.testApi", "Server owns test flag");
        matches(bootstrap, "if\\s*\\(\\s*!data\\.testApi\\s*&&\\s*this\\.testDriver\\s*\\)\\s*\\{\\s*delete\\s+window\\.cpParityTestApi", "Revoke exposed API after reconnect without opt-in");
        matches(bootstrap, "this\\.testDriver\\s*=\\s*null;\\s*this\\.testDriverReady\\s*=\\s*null", "Revocation clears driver and import cache");
        var installCall = Pattern.compile("await\\s+this\\.installDriver\\(\\)").matcher(bootstrap);
        var renderCall = Pattern.compile("await\\s+this\\.renderMain\\(").matcher(bootstrap);
        assertTrue(installCall.find() && renderCall.find() && installCall.start() < renderCall.start(), "Driver is ready before rendering, independent of render arguments");
        matches(effect, "case\\s+['\"]test\\.step['\"]\\s*:\\s*if\\s*\\(\\s*this\\.testApi\\s*\\)\\s*\\{", "Incoming test steps require opt-in");
        assertEquals(2L, Pattern.compile("this\\.installDriver\\(\\)").matcher(main).results().count(), "Only guarded bootstrap and test-step call sites");
        String step = methodBody(driver, "step");
        matches(step, "steps\\.push\\(", "Step is queued for external collector");
        assertFalse(Pattern.compile("\\b(?:fetch|execute|setTimeout|setInterval)\\s*\\(|app\\.(?:send|command|transport)\\b|\\bawait\\b").matcher(step).find(), "Step must not execute commands or perform network work");
        assertTrue(driver.contains("takeStep()"), "External collector consumes steps");
        assertFalse(driver.contains("/api/test/result") || driver.contains("/api/test/dump") || driver.contains("ModelUiDriver"), "No self-submission or model-driver shortcut");
    }

    /** Проверяет структурное выражение в пределах уже извлечённого метода. */
    private static void matches(String text, String regex, String reason) {
        assertTrue(Pattern.compile(regex, Pattern.DOTALL).matcher(text).find(), reason);
    }

    /** Извлекает сбалансированный блок метода, пропуская строки и комментарии JavaScript. */
    private static String methodBody(String source, String name) {
        var matcher = Pattern.compile("\\b(?:async\\s+)?" + Pattern.quote(name) + "\\s*\\([^)]*\\)\\s*\\{").matcher(source);
        assertTrue(matcher.find(), "Method exists: " + name);
        int start = matcher.end(), depth = 1;
        char quote = 0;
        boolean lineComment = false, blockComment = false;
        for (int index = start; index < source.length(); index++) {
            char current = source.charAt(index), next = index + 1 < source.length() ? source.charAt(index + 1) : 0;
            if (lineComment) { if (current == '\n') lineComment = false; continue; }
            if (blockComment) { if (current == '*' && next == '/') { blockComment = false; index++; } continue; }
            if (quote != 0) { if (current == '\\') index++; else if (current == quote) quote = 0; continue; }
            if (current == '/' && next == '/') { lineComment = true; index++; continue; }
            if (current == '/' && next == '*') { blockComment = true; index++; continue; }
            if (current == '\'' || current == '"' || current == '`') { quote = current; continue; }
            if (current == '{') depth++;
            if (current == '}' && --depth == 0) return source.substring(start, index);
        }
        throw new AssertionError("Unclosed method: " + name);
    }

    /** Проверяет все текстовые ресурсы клиента, включая единственную корневую страницу. */
    private static List<Path> files() throws Exception {
        try (var stream = Files.walk(RESOURCES)) {
            var files = stream.filter(Files::isRegularFile).filter(file -> {
                String name = file.getFileName().toString();
                return name.endsWith(".js") || name.endsWith(".html") || name.endsWith(".css");
            }).toList();
            assertTrue(files.contains(RESOURCES.resolve("index.html")), "Core-only root page must exist");
            assertTrue(files.contains(RESOURCES.resolve("app/main.js")), "Core-only renderer must exist");
            return files;
        }
    }
}
