package ru.cashprediction.web;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.SamplePlan;
import ru.cashprediction.core.json.*;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.store.MarkdownSessionStore;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Настоящий WebMain и его зарегистрированный shutdown hook в отдельной JVM.
 * Внешний stdin-командный канал вызывает System.exit, а не HTTP-выход или Windows force-kill.
 * Удаление уже записанных снимков и контроль отсутствия фоновой перезаписи исключают
 * ложный успех от одного только debounce: завершение обязано создать файлы заново.
 */
public class WebShutdownHookTest {
    @TempDir Path temporary;

    /** Внешний orderly JVM exit сохраняет сырой редактор и несохранённый план, не ставя closed. */
    @Test void externalOrderlyExitRunsRealHookAndRewritesRunningSnapshot() throws Exception {
        Path home = Files.createDirectories(temporary.resolve("home"));
        Path log = temporary.resolve("child.log");
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "java.exe" : "java";
        String cp = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "-Djava.awt.headless=true", "-XX:-UsePerfData", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
                "-Dcashprediction.web.port=0", "-Dcashprediction.ui.strictText=true", "-cp", cp,
                WebShutdownHookTest.class.getName(), "--home", home.toString(), "--registry", "memory",
                "--registry-node", "ru/cashprediction/selftest/" + UUID.randomUUID(), "--today", "2026-09-13",
                "--no-window", "--no-browser", "--test-api")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try (var control = new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8);
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            await(() -> address(log).isPresent() || !child.isAlive(), 10, "WebMain address");
            assertTrue(address(log).isPresent(), Files.readString(log));
            URI address = address(log).orElseThrow();
            String token = java.net.URLDecoder.decode(address.getRawQuery().substring(2), StandardCharsets.UTF_8);
            Client client = new Client(http, address, token);
            var initial = client.bootstrap();
            var wizard = forms(initial).getLast();
            String wizardId = Json.requireString(wizard, "id");
            client.intent(Map.of("type", "formShown", "windowId", wizardId));
            client.intent(Map.of("type", "formClose", "windowId", wizardId));
            client.intent(Map.of("type", "command", "command", "file.sample", "source", "MENU"));
            client.intent(Map.of("type", "selectRow", "rowId", "r1@2026-10-05"));
            client.intent(Map.of("type", "command", "command", "edit.edit", "source", "MENU"));
            String id = Json.requireString(forms(client.bootstrap()).getLast(), "id");
            client.intent(Map.of("type", "formShown", "windowId", id));
            String raw = "12,3,4";
            String note = "uncommitted\nsecond | line <br> & text";
            client.intent(Map.of("type", "formField", "windowId", id, "fieldId", "amount", "raw", raw, "committed", false, "clientRev", 1));
            client.intent(Map.of("type", "formField", "windowId", id, "fieldId", "note", "raw", note, "committed", false, "clientRev", 2));
            MarkdownSessionStore store = MarkdownSessionStore.inCashMemory(home.resolve("CashMemory"));
            await(() -> {
                var saved = store.load();
                return saved.isPresent() && saved.get().window(id).isPresent()
                        && note.equals(saved.get().window(id).orElseThrow().fields().get("note"));
            }, 5, "raw input reaches the real recorder");
            SessionSnapshot before = store.load().orElseThrow();
            String expected = PlanMarkdownWriter.write(SamplePlan.create(LocalDate.of(2026, 9, 13)));
            assertTrue(before.plan().dirty()); assertEquals(expected, before.plan().markdown());
            assertEquals(expected, Files.readString(store.planFile()));
            assertEquals(raw, before.window(id).orElseThrow().fields().get("amount"));
            assertTrue(store.readMarker().orElseThrow().isRunning());
            // Завершаем debounce до удаления: обычные записи одинакового состояния пропускаются
            // SessionRecorder.write(force=false); shutdown hook вызывает настоящий saveNow(force=true).
            Thread.sleep(650);
            control.write("REMOVE_SNAPSHOTS\n"); control.flush();
            await(() -> Files.readString(log).contains("SNAPSHOTS_REMOVED"), 3, "negative control boundary");
            Thread.sleep(1100);
            assertTrue(child.isAlive());
            assertFalse(Files.exists(store.sessionFile()), "Background recording must not supply the proof");
            assertFalse(Files.exists(store.planFile()), "Old payload must not supply the proof");
            control.write("ORDERLY_EXIT\n"); control.flush();
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Shutdown hook must finish within a bounded interval");
            assertEquals(23, child.exitValue(), Files.readString(log));
            var independent = MarkdownSessionStore.inCashMemory(home.resolve("CashMemory"));
            SessionSnapshot after = independent.load().orElseThrow();
            assertTrue(independent.readMarker().orElseThrow().isRunning(), "External exit must not claim clean application close");
            assertEquals(child.pid(), independent.readMarker().orElseThrow().pid());
            assertEquals(before.plan(), after.plan()); assertEquals(before.windows(), after.windows());
            assertEquals(raw, after.window(id).orElseThrow().fields().get("amount"));
            assertEquals(note, after.window(id).orElseThrow().fields().get("note"));
            assertEquals(expected, Files.readString(independent.planFile()));
            assertTrue(Files.readString(independent.sessionFile()).contains("running"));
            assertFalse(Files.readString(log).contains("Exception"), Files.readString(log));
        } finally {
            // Только аварийная уборка неуспешного теста: её результат не считается доказательством hooks.
            if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(15, TimeUnit.SECONDS)); }
        }
    }

    /** Обёртка запускает неизменённый WebMain; внешний stdin управляет только тестовой JVM. */
    public static void main(String[] args) throws Exception {
        WebMain.main(args);
        Path home = Path.of(args[1]).toAbsolutePath().normalize();
        var store = MarkdownSessionStore.inCashMemory(home.resolve("CashMemory"));
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String command;
            while ((command = input.readLine()) != null) {
                if (command.equals("REMOVE_SNAPSHOTS")) {
                    Files.delete(store.sessionFile()); Files.delete(store.planFile());
                    System.out.println("SNAPSHOTS_REMOVED"); System.out.flush();
                } else if (command.equals("ORDERLY_EXIT")) {
                    System.exit(23);
                } else throw new IllegalArgumentException("Unknown test control command");
            }
        }
        throw new IllegalStateException("External control closed without exit command");
    }

    /** Читает адрес только из вывода настоящего WebMain, без обращения к внутреннему серверу. */
    private static Optional<URI> address(Path log) throws Exception {
        return Files.readAllLines(log).stream().filter(line -> line.startsWith("PARITY_URL "))
                .map(line -> URI.create(line.substring(11))).findFirst();
    }

    /** Ожидание ограничено временем, фиксированная задержка не заменяет проверку готовности. */
    private static void await(Callable<Boolean> ready, int seconds, String reason) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!ready.call()) { assertTrue(System.nanoTime() < end, reason); Thread.sleep(20); }
    }

    /** Извлекает окна формы из настоящего HTTP bootstrap. */
    private static List<Map<String, Object>> forms(Map<String, Object> bootstrap) {
        return Json.list(bootstrap, "windows").stream().map(value -> Json.asObject(value, "effect"))
                .filter(value -> "form.open".equals(value.get("type")))
                .map(value -> Json.asObject(value.get("window"), "window")).toList();
    }

    /** Минимальный HTTP-клиент без direct controller access и без команды сохранения снимка. */
    private static final class Client {
        private final HttpClient http;
        private final URI base;
        private final String token;
        private long seq;
        /** Сохраняет адрес и полномочия из реально опубликованного URL. */
        Client(HttpClient http, URI base, String token) { this.http = http; this.base = base; this.token = token; }
        /** Читает актуальное состояние и курсор эффектов. */
        Map<String, Object> bootstrap() throws Exception { return request("/api/ui/bootstrap?tab=hook", null); }
        /** Отправляет пользовательское намерение с последним курсором. */
        void intent(Map<String, Object> intent) throws Exception {
            request("/api/ui/intent", Map.of("tab", "hook", "afterSeq", seq, "intent", intent));
        }
        /** Требует полный успешный JSON-ответ настоящего сервера. */
        private Map<String, Object> request(String route, Map<String, Object> body) throws Exception {
            var request = HttpRequest.newBuilder(base.resolve(route)).timeout(Duration.ofSeconds(5)).header("X-Token", token);
            if (body == null) request.GET(); else request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JsonWriter.write(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, response.statusCode(), response.body());
            var value = JsonParser.parseObject(response.body());
            if (value.containsKey("seq")) seq = Json.requireLong(value, "seq");
            return value;
        }
    }
}
