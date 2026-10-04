package ru.cashprediction.web;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.text.Texts;
import static org.junit.jupiter.api.Assertions.*;

/** Сохраняет общий HTTP-контракт после удаления прежних API, используя реальный сервер JDK. */
class SharedHttpContractTest {
    /** GET и HEAD отдают одинаковые метаданные страницы, но HEAD не передаёт тело. */
    @Test void publicPageGetAndHeadKeepSecurityHeadersAndLength() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var get = fixture.request("GET", "/index.html");
            var head = fixture.request("HEAD", "/index.html");
            assertEquals(200, get.statusCode()); assertEquals(200, head.statusCode());
            assertTrue(get.body().contains("cp-prebootstrap-texts"));
            assertFalse(get.body().contains("{{offline."));
            assertEquals("", head.body());
            assertEquals(Integer.toString(get.body().getBytes(StandardCharsets.UTF_8).length),
                    head.headers().firstValue("Content-Length").orElseThrow());
            assertEquals(StaticHandler.CONTENT_SECURITY_POLICY,
                    get.headers().firstValue("Content-Security-Policy").orElseThrow());
            assertEquals(get.headers().firstValue("Content-Security-Policy"),
                    head.headers().firstValue("Content-Security-Policy"));
            secure(get); secure(head);
            assertEquals(200, fixture.request("GET", "/").statusCode());
        }
    }

    /** Служебная CSS доступна без токена, а недопустимые пути не выходят за ресурсы jar. */
    @Test void tokensAndForbiddenPathsUseSafeResponses() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var css = fixture.request("GET", "/app/tokens.css");
            assertEquals(200, css.statusCode());
            assertTrue(css.headers().firstValue("Content-Type").orElseThrow().startsWith("text/css"));
            assertFalse(css.body().isBlank()); secure(css);
            for (String path : new String[]{"/%2e%2e/pom.xml", "/.hidden", "/a%5cb", "/a:b", "/app//main.js"}) {
                var missing = fixture.request("GET", path);
                assertEquals(404, missing.statusCode(), path); secure(missing);
            }
            var invalidMethod = fixture.request("POST", "/index.html");
            assertEquals(405, invalidMethod.statusCode());
            assertEquals("GET, HEAD", invalidMethod.headers().firstValue("Allow").orElseThrow());
            assertEquals(Texts.get("app.http.unsupportedMethod"), invalidMethod.body()); secure(invalidMethod);
        }
    }

    /** URL и имя вложения сохраняют UTF-8, пробелы и не позволяют внедрить заголовок. */
    @Test void queryAndAttachmentEncodingRemainUtf8AndSafe() {
        String name = "план + итог.md";
        String encoded = HttpUtil.encode(name);
        assertFalse(encoded.contains("+")); assertTrue(encoded.contains("%20"));
        assertEquals(name, HttpUtil.decode(encoded));
        assertEquals(Map.of("a", "последний", "empty", "", "space", " "),
                HttpUtil.parseQuery(URI.create("http://localhost/?a=first&a=" + HttpUtil.encode("последний") + "&empty&space=+")));
        var invalid = assertThrows(ApiException.class, () -> HttpUtil.decode("%zz"));
        assertEquals(400, invalid.status()); assertEquals(Texts.get("app.http.invalidEncoding"), invalid.getMessage());
        String attachment = HttpUtil.attachment("план\"\r\n.md");
        assertFalse(attachment.contains("\r")); assertFalse(attachment.contains("\n"));
        assertTrue(attachment.contains("filename*=UTF-8''"));
        assertTrue(attachment.endsWith(HttpUtil.encode("план\"\r\n.md")));
    }

    /** Лимит тела проверяется настоящим обработчиком, а превышение остаётся локализованной ошибкой 400. */
    @Test void oversizedBodyIsRejectedWithoutServerError() throws Exception {
        try (Fixture fixture = new Fixture()) {
            byte[] body = new byte[HttpUtil.MAX_BODY_BYTES + 1];
            var response = fixture.client.send(HttpRequest.newBuilder(fixture.base.resolve("/body"))
                    .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(400, response.statusCode());
            assertEquals(Texts.get("app.http.bodyTooLarge", 8), response.body()); secure(response);
        }
    }

    /** Проверяет общие заголовки независимо от конкретного типа ответа. */
    private static void secure(HttpResponse<?> response) {
        assertEquals("no-store, no-cache, must-revalidate", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElseThrow());
    }

    /** Изолированный loopback-сервер без файлов, пользовательского реестра и браузера. */
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        final URI base;
        /** Создаёт общий статический обработчик и ограниченный обработчик тестового тела. */
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", new StaticHandler(new ServerLog(false)));
            server.createContext("/body", exchange -> {
                try (exchange) {
                    try { HttpUtil.send(exchange, 200, "text/plain; charset=utf-8", HttpUtil.readBody(exchange).getBytes(StandardCharsets.UTF_8), false); }
                    catch (ApiException invalid) { HttpUtil.send(exchange, invalid.status(), "text/plain; charset=utf-8", invalid.getMessage().getBytes(StandardCharsets.UTF_8), false); }
                }
            });
            server.start(); base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }
        /** Выполняет настоящий запрос с ограниченным временем ожидания. */
        HttpResponse<String> request(String method, String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create(base.toString() + path)).timeout(Duration.ofSeconds(10))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        /** Останавливает только принадлежащий проверке сервер и клиент. */
        @Override public void close() { server.stop(0); client.close(); }
    }
}
