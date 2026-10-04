package ru.cashprediction.web.ui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.web.ServerLog;
import ru.cashprediction.web.WebServer;
import static org.junit.jupiter.api.Assertions.*;

/** Сохраняет транспортные проверки прежнего API через реальные маршруты нового UiApi. */
class UiApiTransportMigrationTest {
    @TempDir Path home;
    private WebServer server;
    private HttpClient client;
    private URI base;

    /** Создаёт отдельный сервер, порт и CashMemory с реестром в памяти; браузер не запускается. */
    @BeforeEach void startIsolatedServer() throws Exception {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var options = LaunchOptions.parse(List.of("--home", home.toString(),
                "--registry", "memory", "--today", "2026-09-13"), new Properties());
        server = WebServer.startCore(AppEnvironment.from(options), new ServerLog(false), 0, true);
        base = URI.create("http://127.0.0.1:" + server.port());
        // Свежий мастер закрывается настоящими lifecycle-intent, чтобы модальность не скрыла тест мутации.
        var bootstrap = bootstrap();
        for (Object raw : Json.list(bootstrap, "windows")) {
            var window = Json.asObject(Json.asObject(raw, "entry").get("window"), "window");
            String id = Json.requireString(window, "id");
            sendIntent(Map.of("type", "formShown", "windowId", id));
            sendIntent(Map.of("type", "formClose", "windowId", id));
        }
    }

    /** Закрывает HTTP-клиент и сервер даже после ошибки утверждения; другие серверы не затрагиваются. */
    @AfterEach void closeIsolatedServerAndClient() {
        try { if (client != null) client.close(); }
        finally { if (server != null) server.stop(); }
    }

    /** Обычный bootstrap принимает текущий query-token t без X-Token. */
    @Test void ordinaryBootstrapAcceptsQueryToken() throws Exception {
        var response = request("GET", "/api/ui/bootstrap?tab=migration&t=" + server.token(), null, null);
        assertEquals(200, response.statusCode());
        var value = object(response);
        assertNotNull(value.get("screen")); assertTrue(value.containsKey("seq"));
        assertEquals(bootstrap().get("screen"), value.get("screen"));
    }

    /** Отсутствующий, неправильный query-token и неправильный заголовок возвращают локализованную ошибку. */
    @Test void missingAndWrongTokensAreRejected() throws Exception {
        requireError(request("GET", "/api/ui/bootstrap?tab=migration", null, null), 403, "token");
        requireError(request("GET", "/api/ui/bootstrap?tab=migration&t=wrong", null, null), 403, "token");
        requireError(request("GET", "/api/ui/bootstrap?tab=migration", null, "wrong"), 403, "token");
    }

    /** Неправильный явный заголовок не получает полномочия от правильного query-token. */
    @Test void wrongHeaderDoesNotFallBackToValidQueryToken() throws Exception {
        requireError(request("GET", "/api/ui/bootstrap?tab=migration&t=" + server.token(), null, "wrong"), 403, "token");
    }

    /** Неизвестный маршрут и неверный метод следуют текущему JSON-контракту 404, не старому 405. */
    @Test void unknownRoutesAndWrongMethodsReturnLocalizedJson404() throws Exception {
        requireError(request("GET", "/api/ui/not-a-route", null, server.token()), 404, "route");
        requireError(request("POST", "/api/ui/not-a-route", "{}", server.token()), 404, "route");
        requireError(request("POST", "/api/ui/bootstrap?tab=migration", "{}", server.token()), 404, "route");
        requireError(request("GET", "/api/ui/intent", null, server.token()), 404, "route");
    }

    /** Незавершённый JSON мутации даёт 400 и не изменяет экран, окна или последовательность эффектов. */
    @Test void malformedMutatingJsonReturns400WithoutChangingState() throws Exception {
        var before = bootstrap();
        String malformed = UiJson.write(envelope(before, Map.of("type", "filterText", "text", "never-applied")));
        malformed = malformed.substring(0, malformed.length() - 1);
        requireError(request("POST", "/api/ui/intent", malformed, server.token()), 400, "request");
        requireSameState(before, bootstrap());
    }

    /** Отказ авторизации происходит до выполнения валидной мутации; разрешённый контроль меняет seq. */
    @Test void rejectedMutationsCannotChangeScreenOrEffectSequence() throws Exception {
        var before = bootstrap();
        var intent = Map.<String, Object>of("type", "filterText", "text", "transport-migration-filter");
        String payload = UiJson.write(envelope(before, intent));
        requireError(request("POST", "/api/ui/intent", payload, "wrong"), 403, "token");
        requireSameState(before, bootstrap());
        requireError(request("POST", "/api/ui/intent", payload, null), 403, "token");
        requireSameState(before, bootstrap());
        requireError(request("POST", "/api/ui/not-a-route", payload, server.token()), 404, "route");
        requireSameState(before, bootstrap());
        var accepted = request("POST", "/api/ui/intent", payload, server.token());
        assertEquals(200, accepted.statusCode());
        assertTrue(Json.requireLong(object(accepted), "seq") > Json.requireLong(before, "seq"),
                "Control intent must actually execute, rather than being blocked by startup modality");
    }

    /** Читает текущее состояние через обычный авторизованный HTTP bootstrap. */
    private Map<String, Object> bootstrap() throws Exception {
        var response = request("GET", "/api/ui/bootstrap?tab=migration", null, server.token());
        assertEquals(200, response.statusCode()); return object(response);
    }

    /** Отправляет lifecycle-intent с актуальным курсором, не обращаясь к контроллеру напрямую. */
    private void sendIntent(Map<String, Object> intent) throws Exception {
        var response = request("POST", "/api/ui/intent", UiJson.write(envelope(bootstrap(), intent)), server.token());
        assertEquals(200, response.statusCode());
    }

    /** Формирует текущий транспортный конверт из реально прочитанного курсора. */
    private Map<String, Object> envelope(Map<String, Object> state, Map<String, Object> intent) {
        return Map.of("tab", "migration", "afterSeq", Json.requireLong(state, "seq"), "intent", intent);
    }

    /** Выполняет ограниченный по времени настоящий HTTP-запрос с необязательным X-Token. */
    private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(8));
        if (token != null) builder.header("X-Token", token);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** Разбирает ответ как JSON-объект, не принимая HTML или пустой ответ за успешную ошибку. */
    private Map<String, Object> object(HttpResponse<String> response) {
        return Json.asObject(JsonParser.parse(response.body()), "response");
    }

    /** Проверяет точный статус и локализованное поле error нового API. */
    private void requireError(HttpResponse<String> response, int status, String field) {
        assertEquals(status, response.statusCode());
        assertEquals(Map.of("error", UiText.get("json.error.uiField", field)), object(response));
    }

    /** Не сравнивает секреты reconnect, но требует неизменность всех наблюдаемых UI-состояний. */
    private void requireSameState(Map<String, Object> before, Map<String, Object> after) {
        for (String key : List.of("screen", "seq", "windows", "overlay")) assertEquals(before.get(key), after.get(key), key);
    }
}
