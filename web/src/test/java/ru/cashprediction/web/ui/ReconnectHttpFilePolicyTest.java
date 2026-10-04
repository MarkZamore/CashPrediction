package ru.cashprediction.web.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.io.CashMemoryLayout;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.web.ServerLog;
import ru.cashprediction.web.WebServer;

/** Отрицательные HTTP-регрессии: служебные файлы не становятся планами, выбором или целью записи. */
class ReconnectHttpFilePolicyTest {
    @TempDir Path home;
    private WebServer server;
    private long seq;
    private long revision;
    private String key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /** Создаёт настоящий сервер с изолированными диском и реестром, подтверждает показ стартового мастера. */
    @BeforeEach void start() throws Exception {
        server = WebServer.startCore(AppEnvironment.from(LaunchOptions.parse(List.of("--home",
                home.toString(), "--registry", "memory", "--today", "2026-09-13"), new Properties())),
                new ServerLog(false), 0, true);
        var data = bootstrap();
        key = Json.requireString(object(data.get("reconnect")), "key");
        for (Object effect : Json.list(data, "windows")) {
            var value = object(effect);
            if ("form.open".equals(value.get("type"))) {
                String id = Json.requireString(object(value.get("window")), "id");
                intent(Map.of("type", "formShown", "windowId", id));
                intent(Map.of("type", "formClose", "windowId", id));
            }
        }
        assertTrue(Json.list(bootstrap(), "windows").isEmpty());
    }

    /** Освобождает сервер при любом исходе отрицательного теста. */
    @AfterEach void close() { if (server != null) server.stop(); }

    /** Список реального server browser не публикует ключ, блокировку и временный документ. */
    @Test void openBrowserHidesReconnectFilesAndTheirContents() throws Exception {
        Files.writeString(memory().resolve("web-reconnect-tmp-negative.md"), "Key: " + key);
        command("file.openFile");
        var browser = form(bootstrap());
        String id = Json.requireString(browser, "id");
        intent(Map.of("type", "formShown", "windowId", id));
        var shown = form(bootstrap());
        var fields = object(object(shown.get("view")).get("fields"));
        var options = Json.list(object(fields.get("value")), "options");
        for (Object option : options) {
            String text = JsonWriter.write(option).toLowerCase(java.util.Locale.ROOT);
            assertFalse(text.contains("web-reconnect"), "Service files must not appear in file choices");
            assertFalse(text.contains(key), "Secret contents must not appear in file choices");
        }
        assertFalse(JsonWriter.write(shown).contains(key));
        // Подмена raw выбора не должна обходить скрытие списка или раскрывать содержимое.
        field(id, "value", memory().resolve("web-reconnect.md").toString());
        intent(Map.of("type", "formButton", "windowId", id, "buttonId", "ok"));
        assertFalse(JsonWriter.write(Json.list(bootstrap(), "windows")).contains(key));
        assertTrue(server.coreRuntime().thread().submit(() -> server.coreRuntime().controller().document().file().isEmpty()).get());
    }

    /** Даже согласие на ошибочно предложенную замену не должно уничтожать служебный файл. */
    @ParameterizedTest
    @ValueSource(strings = {"web-reconnect.md", "web-reconnect-lock.md", "WEB-RECONNECT.MD",
            "web-reconnect-tmp-negative.md", "WEB-RECONNECT-TMP-ABSENT.md", "web-reconnect", "WEB-RECONNECT-LOCK"})
    void saveAsCannotOverwriteOrCreateReservedServiceTargets(String name) throws Exception {
        Path temporary = memory().resolve("web-reconnect-tmp-negative.md");
        Files.writeString(temporary, "Key: " + key);
        Map<Path, byte[]> protectedBefore = new LinkedHashMap<>();
        for (String file : List.of("web-reconnect.md", "web-reconnect-lock.md", "web-reconnect-tmp-negative.md")) {
            Path path = memory().resolve(file); protectedBefore.put(path, Files.readAllBytes(path));
        }
        Path target = memory().resolve(name.toLowerCase(java.util.Locale.ROOT).endsWith(".md") ? name : name + ".md");
        boolean existed = Files.exists(target);
        byte[] original = existed ? Files.readAllBytes(target) : null;
        command("file.sample"); command("file.saveAs");
        var browser = form(bootstrap()); String id = Json.requireString(browser, "id");
        intent(Map.of("type", "formShown", "windowId", id));
        field(id, "name", name);
        intent(Map.of("type", "formButton", "windowId", id, "buttonId", "ok"));
        boolean replaceOffered = false;
        for (Object value : Json.list(bootstrap(), "windows")) {
            var effect = object(value);
            if ("alert.open".equals(effect.get("type")) && "replaceFile".equals(object(effect.get("spec")).get("purpose"))) {
                replaceOffered = true;
                String alertId = Json.requireString(effect, "alertId");
                intent(Map.of("type", "alertShown", "windowId", alertId));
                intent(Map.of("type", "alertButton", "alertId", alertId, "buttonId", "replace"));
            }
        }
        for (var entry : protectedBefore.entrySet()) assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()),
                "Protected file must survive even a confirmed save-as attempt");
        if (existed) assertArrayEquals(original, Files.readAllBytes(target));
        else assertFalse(Files.exists(target), "A reserved temporary filename must not become a financial plan");
        assertFalse(replaceOffered, "Protection must run before offering replacement of a service file");
        assertTrue(server.coreRuntime().thread().submit(() -> server.coreRuntime().controller().document().file().isEmpty()).get());
        var windows = Json.list(bootstrap(), "windows");
        assertFalse(JsonWriter.write(windows).contains(key));
        assertTrue(windows.stream().map(ReconnectHttpFilePolicyTest::object).anyMatch(effect -> {
            if ("alert.open".equals(effect.get("type"))) return "ERROR".equals(object(effect.get("spec")).get("kind"));
            if (!"form.open".equals(effect.get("type"))) return false;
            var view = object(object(effect.get("window")).get("view"));
            return Boolean.FALSE.equals(object(object(view.get("buttons")).get("ok")).get("enabled"));
        }), "Rejected save must have an error or a disabled chooser confirmation");
    }

    /** Положительный контроль не допускает исправления запретом всех операций сохранения. */
    @Test void ordinarySaveAsStillWritesReadableFinancialPlan() throws Exception {
        command("file.sample"); command("file.saveAs");
        String id = Json.requireString(form(bootstrap()), "id");
        intent(Map.of("type", "formShown", "windowId", id));
        field(id, "name", "policy-safe-plan.md");
        intent(Map.of("type", "formButton", "windowId", id, "buttonId", "ok"));
        Path path = memory().resolve("policy-safe-plan.md");
        assertTrue(Files.isRegularFile(path));
        assertEquals("policy-safe-plan", new PlanRepository(memory()).load(path, java.time.LocalDate.of(2026, 9, 13)).plan().name());
        assertEquals(path, server.coreRuntime().thread().submit(() -> server.coreRuntime().controller().document().file().orElseThrow()).get());
        assertFalse(Files.readString(path).contains(key));
    }

    /** Проверка домена и имён файлов использует одинаковую политику служебного пространства. */
    @Test void reservedNamesAreRejectedButEscapedPlanFilesRemainVisible() {
        for (String name : List.of("web-reconnect", " WEB-RECONNECT-LOCK ", "web-reconnect-tmp-new")) {
            assertTrue(PlanValidator.checkPlanName(name).isPresent(), "Reserved service name must fail domain validation");
            assertFalse(CashMemoryLayout.isReservedPlanName(PlanRepository.fileBaseName(name)),
                    "An escaped filename must not remain hidden as a reserved service prefix");
        }
        assertTrue(PlanValidator.checkPlanName("ordinary-plan").isEmpty());
    }

    /** Возвращает собственную папку данных теста. */
    private Path memory() { return home.resolve("CashMemory"); }
    /** Читает аутентифицированный bootstrap и обновляет курсор эффектов. */
    private Map<String, Object> bootstrap() throws Exception {
        var response = http.send(request("/api/ui/bootstrap?tab=policy").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var data = object(JsonParser.parse(response.body())); seq = Json.requireLong(data, "seq"); return data;
    }
    /** Выполняет один настоящий HTTP intent без прямого вызова контроллера. */
    private void intent(Map<String, Object> value) throws Exception {
        var body = Map.of("tab", "policy", "afterSeq", seq, "intent", value);
        var response = http.send(request("/api/ui/intent").POST(HttpRequest.BodyPublishers.ofString(JsonWriter.write(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode()); seq = Json.requireLong(object(JsonParser.parse(response.body())), "seq");
    }
    /** Вызывает команду через тот же путь, что меню браузера. */
    private void command(String id) throws Exception { intent(Map.of("type", "command", "command", id, "source", "MENU")); }
    /** Передаёт raw-поле с новой ревизией вкладки. */
    private void field(String id, String field, String raw) throws Exception {
        intent(Map.of("type", "formField", "windowId", id, "fieldId", field, "raw", raw, "committed", false, "clientRev", ++revision));
    }
    /** Находит единственную реальную форму выбора. */
    private static Map<String, Object> form(Map<String, Object> bootstrap) {
        var matches = Json.list(bootstrap, "windows").stream().map(ReconnectHttpFilePolicyTest::object)
                .filter(effect -> "form.open".equals(effect.get("type"))).map(effect -> object(effect.get("window"))).toList();
        assertEquals(1, matches.size()); return matches.getFirst();
    }
    /** Создаёт защищённый HTTP-запрос с ограниченным сроком. */
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(5)).header("X-Token", server.token());
    }
    /** Требует объект вместо молчаливого принятия другой формы JSON. */
    private static Map<String, Object> object(Object value) { return Json.asObject(value, "response"); }
}
