package ru.cashprediction.web.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppController;
import ru.cashprediction.core.json.*;
import ru.cashprediction.web.WebMain;

/** Проверяет настоящий процесс сервера: последний HTTP-ответ приходит до самостоятельного выхода JVM. */
class WebExitResponseTest {
    @TempDir Path temporary;

    /** Позднее подтверждение последнего шага удерживает сервер, затем процесс завершается без принудительной остановки. */
    @Test void lastResultIsAcknowledgedBeforeServerProcessExits() throws Exception {
        Path modules = Files.createDirectories(temporary.resolve("modules"));
        Path web = snapshot(WebMain.class, modules.resolve("web"));
        Path core = snapshot(AppController.class, modules.resolve("core"));
        Path script = temporary.resolve("exit-response.cps");
        try (var input = getClass().getResourceAsStream("/ui/exit-response.cps")) {
            Files.copy(java.util.Objects.requireNonNull(input), script);
        }
        Path journal = temporary.resolve("out/selftest.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dcashprediction.web.port=0", "-classpath",
                web + java.io.File.pathSeparator + core, WebMain.class.getName(),
                "--home", temporary.resolve("home").toString(), "--registry", "memory", "--today", "2026-09-13",
                "--selftest", script.toString(), "--selftest-out", temporary.resolve("out").toString(),
                "--ui", "core", "--no-browser", "--no-window", "--test-api")
                .redirectError(temporary.resolve("stderr.log").toFile()).start();
        var address = new CompletableFuture<URI>();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var input = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (line.startsWith("PARITY_URL ")) address.complete(URI.create(line.substring(11)));
                }
                if (!address.isDone()) address.completeExceptionally(new IllegalStateException("No PARITY_URL"));
            } catch (Exception error) { address.completeExceptionally(error); }
        });
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            URI base = address.get(15, TimeUnit.SECONDS);
            String token = java.net.URLDecoder.decode(base.getRawQuery().substring(2), StandardCharsets.UTF_8);
            var bootstrap = request(http, base, token, "/api/ui/bootstrap?tab=driver", null);
            var windows = Json.list(bootstrap, "windows");
            var effect = Json.asObject(windows.getLast(), "effect");
            String id = Json.requireString(Json.asObject(effect.get("window"), "window"), "id");
            long cursor = Json.requireLong(bootstrap, "seq");
            var closed = request(http, base, token, "/api/ui/intent", Map.of("tab", "driver", "afterSeq", cursor,
                    "intent", Map.of("type", "formClose", "windowId", id)));
            request(http, base, token, "/api/test/result", Map.of("n", 1, "ok", true));
            var exit = request(http, base, token, "/api/ui/intent", Map.of("tab", "driver", "afterSeq",
                    Json.requireLong(closed, "seq"), "intent", Map.of("type", "closeMain")));
            assertTrue(Json.list(exit, "effects").stream()
                    .map(value -> Json.asObject(value, "effect")).anyMatch(value -> "exit".equals(value.get("type"))));
            // Превышаем обычный запас 500 мс: завершение сценария должно ждать отдельного последнего POST.
            Thread.sleep(750);
            assertTrue(process.isAlive(), "Pending result must retain the server process");
            assertEquals(true, request(http, base, token, "/api/test/result", Map.of("n", 2, "ok", true)).get("ok"));
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Server must stop after the final response");
            assertEquals(0, process.exitValue(), Files.readString(temporary.resolve("stderr.log")));
            assertTrue(Files.readString(journal).contains("SELFTEST DONE"));
            assertThrows(java.io.IOException.class, () -> request(http, base, token, "/api/ui/bootstrap?tab=late", null));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS);
            }
            reader.join(1000);
        }
    }

    /** Копирует весь runtime до запуска JVM, чтобы процесс не удерживал общие артефакты сборки Windows. */
    private static Path snapshot(Class<?> type, Path destination) throws Exception {
        Path source = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isRegularFile(source)) return Files.copy(source, destination.resolveSibling(destination.getFileName() + ".jar"));
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path target = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else Files.copy(path, target);
            }
        }
        return destination;
    }

    /** Выполняет настоящий HTTP-запрос и проверяет полное JSON-подтверждение сервера. */
    private static Map<String, Object> request(HttpClient http, URI base, String token, String route,
            Map<String, Object> body) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(route)).timeout(Duration.ofSeconds(5)).header("X-Token", token);
        if (body == null) builder.GET(); else builder.POST(HttpRequest.BodyPublishers.ofString(JsonWriter.write(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
        return Json.asObject(JsonParser.parse(response.body()), "response");
    }
}
