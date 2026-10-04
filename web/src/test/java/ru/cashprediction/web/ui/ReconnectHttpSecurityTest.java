package ru.cashprediction.web.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.web.ServerLog;
import ru.cashprediction.web.WebServer;

/** Проверяет reconnect настоящими HTTP-запросами без браузера и реального реестра. */
class ReconnectHttpSecurityTest {
    @TempDir Path home;
    private WebServer server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final String PREFIX = "/api/ui/reconnect/";

    /** Освобождает сервер даже после неуспешного утверждения теста. */
    @AfterEach void close() { if (server != null) server.stop(); }

    /** Сохраняет секрет установки, но меняет обычный API-токен при каждом запуске. */
    @Test void repeatedRestartKeepsMetadataAndRotatesApiToken() throws Exception {
        start(0);
        Map<String, Object> original = metadata();
        String oldToken = server.token();
        int port = server.port();
        Map<String, Object> firstChallenge = object(challenge(original).body());
        assertEquals(java.util.Set.of("version", "installationId", "serverGeneration", "clientNonce",
                "serverNonce", "challengeId", "serverProof"), firstChallenge.keySet());
        assertEquals(1L, ((Number) firstChallenge.get("version")).longValue());
        for (String name : List.of("serverGeneration", "challengeId"))
            assertTrue(((String) firstChallenge.get(name)).matches("[0-9a-f]{32}"));
        for (String name : List.of("clientNonce", "serverNonce", "serverProof"))
            assertTrue(((String) firstChallenge.get(name)).matches("[0-9a-f]{64}"));
        String oldProof = proof("client", original, firstChallenge);
        for (int i = 0; i < 2; i++) {
            server.stop(); start(port);
            assertEquals(original, metadata());
            assertNotEquals(oldToken, server.token());
            assertEquals(403, bootstrap(oldToken).statusCode());
            var next = challenge(original);
            assertEquals(200, next.statusCode());
            Map<String, Object> nextChallenge = object(next.body());
            assertNotEquals(firstChallenge.get("serverGeneration"), nextChallenge.get("serverGeneration"));
            assertEquals(proof("server", original, nextChallenge), nextChallenge.get("serverProof"));
            var completed = send("complete", Map.of("challengeId", nextChallenge.get("challengeId"),
                    "clientProof", proof("client", original, nextChallenge)), origin(), "1");
            assertEquals(200, completed.statusCode());
            assertEquals(server.token(), object(completed.body()).get("token"));
            var obsolete = send("complete", Map.of("challengeId", firstChallenge.get("challengeId"),
                    "clientProof", oldProof), origin(), "1");
            assertNotEquals(200, obsolete.statusCode());
            assertFalse(obsolete.body().contains(oldToken));
            oldToken = server.token();
        }
    }

    /** Служебные ключ и блокировка не должны становиться планами или ломать следующий обычный запуск. */
    @Test void credentialFilesAreNotPlansAndCleanRestartShowsNormalUi() throws Exception {
        start(0);
        metadata();
        assertTrue(new ru.cashprediction.core.io.PlanRepository(home.resolve("CashMemory")).list().isEmpty(),
                "Reconnect files must be excluded from the financial-plan catalogue");
        int port = server.port(); server.stop(); start(port);
        var data = object(bootstrap(server.token()).body());
        assertNull(data.get("overlay"), "Clean restart must show the actual main screen, not a startup-error overlay");
        assertInstanceOf(List.class, data.get("windows"));
        assertFalse(((List<?>) data.get("windows")).stream().map(ReconnectHttpSecurityTest::object)
                .anyMatch(effect -> effect.containsKey("spec") && "startupError".equals(object(effect.get("spec")).get("purpose"))),
                "A reconnect document must never be read as a financial plan");
    }

    /** Метаданные не выдаются без API-ключа, а reconnect не раскрывает ключ в ошибке. */
    @Test void authenticationAndResponsesDoNotLeakSecrets() throws Exception {
        start(0);
        assertEquals(403, bootstrap(null).statusCode());
        Map<String, Object> metadata = metadata();
        var challenge = challenge(metadata);
        assertEquals(200, challenge.statusCode());
        assertTrue(challenge.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertTrue(challenge.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        assertFalse(challenge.body().contains((String) metadata.get("key")));
        assertFalse(challenge.body().contains(server.token()));
        var invalid = send("complete", Map.of("challengeId", object(challenge.body()).get("challengeId"),
                "clientProof", "0".repeat(64)), origin(), "1");
        assertNotEquals(200, invalid.statusCode());
        assertEquals(java.util.Set.of("error"), object(invalid.body()).keySet());
        assertFalse(invalid.body().contains(server.token()));
        assertFalse(invalid.body().contains("0".repeat(64)));
    }

    /** Независимо вычисляет оба HMAC и доказывает одноразовость действительного запроса. */
    @Test void successfulProofReturnsOnlyCurrentTokenAndRejectsReplay() throws Exception {
        start(0);
        Map<String, Object> metadata = metadata();
        var response = challenge(metadata);
        assertEquals(200, response.statusCode());
        Map<String, Object> challenge = object(response.body());
        assertEquals(proof("server", metadata, challenge), challenge.get("serverProof"));
        Map<String, Object> completion = Map.of("challengeId", challenge.get("challengeId"),
                "clientProof", proof("client", metadata, challenge));
        var completed = send("complete", completion, origin(), "1");
        assertEquals(200, completed.statusCode());
        assertEquals(Map.of("token", server.token(), "serverGeneration", challenge.get("serverGeneration")), object(completed.body()));
        assertTrue(completed.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals(400, send("complete", completion, origin(), "1").statusCode());
        assertEquals(200, bootstrap((String) object(completed.body()).get("token")).statusCode());
        Map<String, Object> spent = object(challenge(metadata).body());
        assertEquals(400, send("complete", Map.of("challengeId", spent.get("challengeId"),
                "clientProof", "0".repeat(64)), origin(), "1").statusCode());
        assertEquals(400, send("complete", Map.of("challengeId", spent.get("challengeId"),
                "clientProof", proof("client", metadata, spent)), origin(), "1").statusCode());
    }

    /** Требует точный origin и специальный заголовок, не допускает CORS и OPTIONS. */
    @Test void sourceAndMethodChecksPrecedeBodyParsing() throws Exception {
        start(0);
        for (String origin : List.of("null", "https://127.0.0.1:" + server.port(),
                "http://evil.example:" + server.port(), origin() + "/", "http://localhost:" + server.port())) {
            assertEquals(403, send("challenge", "not-json", origin, "1").statusCode());
        }
        assertEquals(403, send("challenge", "not-json", null, "1").statusCode());
        assertEquals(403, send("challenge", "not-json", origin(), null).statusCode());
        assertEquals(403, send("challenge", "not-json", origin(), "1,1").statusCode());
        var options = http.send(HttpRequest.newBuilder(uri(PREFIX + "challenge"))
                .header("Origin", origin()).header("X-CP-Reconnect", "1")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, options.statusCode());
        assertTrue(options.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        assertTrue(options.headers().firstValue("Location").isEmpty());
    }

    /** Отвергает повторные заголовки и любые параметры URL до чтения доказательства. */
    @Test void duplicatesAndQueryCannotBypassOriginGate() throws Exception {
        start(0);
        String body = JsonWriter.write(request(metadata()));
        for (String duplicate : List.of("Origin: " + origin(), "Host: 127.0.0.1:" + server.port(),
                "X-CP-Reconnect: 1", "Content-Type: application/json")) {
            int status = raw(PREFIX + "challenge", "127.0.0.1:" + server.port(), duplicate, body);
            assertTrue(status == 400 || status == 403, "Duplicate headers must be rejected by HTTP parser or API");
        }
        assertEquals(403, raw(PREFIX + "challenge", "evil.example:" + server.port(), "", body));
        assertEquals(403, raw(PREFIX + "challenge?t=x&t=y", "127.0.0.1:" + server.port(), "", body));
        assertEquals(403, raw(PREFIX + "challenge?", "127.0.0.1:" + server.port(), "", body));
    }

    /** Проверяет предел в байтах, строгий UTF-8, JSON без повторов и точные поля. */
    @Test void malformedAndOversizedBodiesFailWithoutReflection() throws Exception {
        start(0);
        Map<String, Object> valid = request(metadata());
        var extra = new LinkedHashMap<>(valid); extra.put("token", server.token());
        for (String body : List.of("x".repeat(2049), "[]", "{", JsonWriter.write(extra),
                "{\"installationId\":\"" + valid.get("installationId") + "\",\"installationId\":\""
                        + valid.get("installationId") + "\",\"clientNonce\":\"" + "a".repeat(64) + "\"}",
                JsonWriter.write(Map.of("installationId", valid.get("installationId"), "clientNonce", "A".repeat(64))))) {
            var response = send("challenge", body, origin(), "1");
            assertEquals(400, response.statusCode());
            assertFalse(response.body().contains(server.token()));
            assertEquals(java.util.Set.of("error"), object(response.body()).keySet());
        }
        var badUtf8 = http.send(builder(PREFIX + "challenge", origin(), "1")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] {(byte) 0xc3, (byte) 0x28})).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(400, badUtf8.statusCode());
        String json = JsonWriter.write(valid);
        String boundary = json + " ".repeat(2048 - json.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(200, send("challenge", boundary, origin(), "1").statusCode());
        assertEquals(400, send("challenge", boundary + " ", origin(), "1").statusCode());
        var wrongType = http.send(HttpRequest.newBuilder(uri(PREFIX + "challenge"))
                .header("Origin", origin()).header("X-CP-Reconnect", "1").header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, wrongType.statusCode());
    }

    /** Отключённый reconnect не мешает bootstrap обычного API и не публикует метаданные. */
    @Test void nullableAuthenticatorPreservesOldConstructorContract() throws Exception {
        var httpServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var options = LaunchOptions.parse(List.of("--home", home.toString(), "--registry", "memory"), new Properties());
        var runtime = new CoreWebRuntime(AppEnvironment.from(options));
        try {
            httpServer.createContext("/api/", new UiApi(runtime.thread(), runtime.controller(), runtime.port(), runtime.effects(),
                    "ordinary-token", () -> httpServer.getAddress().getPort(), null));
            runtime.start(); httpServer.start();
            String origin = "http://127.0.0.1:" + httpServer.getAddress().getPort();
            var bootstrap = http.send(HttpRequest.newBuilder(URI.create(origin + "/api/ui/bootstrap?tab=one"))
                    .header("X-Token", "ordinary-token").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, bootstrap.statusCode());
            assertFalse(object(bootstrap.body()).containsKey("reconnect"));
            var response = http.send(HttpRequest.newBuilder(URI.create(origin + PREFIX + "challenge"))
                    .header("Origin", origin).header("X-CP-Reconnect", "1").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
            assertFalse(response.body().contains("ordinary-token"));
        } finally { httpServer.stop(0); runtime.close(); }
    }

    /** Повреждённый файл не регенерируется, но обычная новая сессия остаётся доступной. */
    @Test void malformedCredentialDisablesReconnectWithoutBlockingUi() throws Exception {
        Path memory = java.nio.file.Files.createDirectories(home.resolve("CashMemory"));
        Path credential = memory.resolve("web-reconnect.md");
        java.nio.file.Files.writeString(credential, "malformed credential");
        start(0);
        var bootstrap = bootstrap(server.token());
        assertEquals(200, bootstrap.statusCode());
        assertFalse(object(bootstrap.body()).containsKey("reconnect"));
        assertEquals("malformed credential", java.nio.file.Files.readString(credential));
        assertEquals(403, send("challenge", Map.of("installationId", "a".repeat(32), "clientNonce", "a".repeat(64)),
                origin(), "1").statusCode());
    }

    /** Создаёт изолированный сервер, при повторном запуске использует тот же порт и CashMemory. */
    private void start(int port) throws Exception {
        var options = LaunchOptions.parse(List.of("--home", home.toString(),
                "--registry", "memory", "--today", "2026-09-13"), new Properties());
        server = WebServer.startCore(AppEnvironment.from(options), new ServerLog(false), port, true);
    }
    /** Читает только аутентифицированные bootstrap-метаданные установки. */
    private Map<String, Object> metadata() throws Exception {
        var response = bootstrap(server.token()); assertEquals(200, response.statusCode());
        var data = object(object(response.body()).get("reconnect"));
        assertEquals(java.util.Set.of("version", "installationId", "key"), data.keySet());
        assertTrue(((String) data.get("installationId")).matches("[0-9a-f]{32}"));
        assertTrue(((String) data.get("key")).matches("[0-9a-f]{64}"));
        return data;
    }
    /** Выполняет bootstrap с заданным обычным API-токеном либо без него. */
    private HttpResponse<String> bootstrap(String token) throws Exception {
        var builder = HttpRequest.newBuilder(uri("/api/ui/bootstrap?tab=one")).timeout(Duration.ofSeconds(5));
        if (token != null) builder.header("X-Token", token);
        return http.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    /** Запрашивает одноразовый challenge без обычного API-токена. */
    private HttpResponse<String> challenge(Map<String, Object> metadata) throws Exception {
        return send("challenge", request(metadata), origin(), "1");
    }
    /** Составляет канонический запрос challenge. */
    private Map<String, Object> request(Map<String, Object> metadata) {
        return Map.of("installationId", metadata.get("installationId"), "clientNonce", "a".repeat(64));
    }
    /** Вычисляет доказательство независимо от реализации authenticator по опубликованному протоколу. */
    private String proof(String role, Map<String, Object> metadata, Map<String, Object> challenge) throws Exception {
        String message = "cashprediction-web-reconnect-v1\n" + role + "\n" + origin() + "\n"
                + metadata.get("installationId") + "\n" + challenge.get("serverGeneration") + "\n"
                + challenge.get("clientNonce") + "\n" + challenge.get("serverNonce") + "\n" + challenge.get("challengeId");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HexFormat.of().parseHex((String) metadata.get("key")), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
    }
    /** Отправляет JSON или намеренно некорректное тело. */
    private HttpResponse<String> send(String route, Object body, String origin, String marker) throws Exception {
        String text = body instanceof String s ? s : JsonWriter.write(body);
        return http.send(builder(PREFIX + route, origin, marker).POST(HttpRequest.BodyPublishers.ofString(text)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    /** Создаёт ограниченный по времени запрос с явными заголовками источника. */
    private HttpRequest.Builder builder(String path, String origin, String marker) {
        var builder = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json");
        if (origin != null) builder.header("Origin", origin);
        if (marker != null) builder.header("X-CP-Reconnect", marker);
        return builder;
    }
    /** Проверяет повторные Host/Origin через сокет без ограничений HttpClient на заголовки. */
    private int raw(String path, String host, String extra, String body) throws Exception {
        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String head = "POST " + path + " HTTP/1.1\r\nHost: " + host + "\r\nOrigin: " + origin()
                    + "\r\nX-CP-Reconnect: 1\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                    + "\r\nConnection: close\r\n" + (extra.isEmpty() ? "" : extra + "\r\n") + "\r\n";
            socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes); socket.getOutputStream().flush();
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            return Integer.parseInt(reader.readLine().split(" ")[1]);
        }
    }
    /** Возвращает точный источник сервера. */
    private String origin() { return "http://127.0.0.1:" + server.port(); }
    /** Строит адрес текущего сервера. */
    private URI uri(String path) { return URI.create(origin() + path); }
    /** Разбирает JSON-объект ответа. */
    private static Map<String, Object> object(Object value) {
        return Json.asObject(value instanceof String text ? JsonParser.parse(text) : value, "response");
    }
}
