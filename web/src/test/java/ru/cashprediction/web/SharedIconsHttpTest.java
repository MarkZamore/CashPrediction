package ru.cashprediction.web;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.token.UiIcons;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет общий источник значков через loopback HTTP без GUI, файлов и реестра. */
class SharedIconsHttpTest {
    /** Каждое имя манифеста и брендинг отдают исходные байты ядра с прежними правилами GET и HEAD. */
    @Test void allIconsAndBrandingAreExactCoreBytes() throws Exception {
        assertNull(StaticHandler.class.getResource("/web/favicon.png"));
        try (Fixture fixture = new Fixture()) {
            var paths = new LinkedHashSet<>(UiIcons.manifest().values());
            paths.add("/app/icons/application.png"); paths.add("/app/icons/application.ico"); paths.add("/favicon.png");
            for (String path : paths) {
                var get = fixture.request("GET", path);
                var head = fixture.request("HEAD", path);
                byte[] expected = path.equals("/favicon.png") ? UiIcons.applicationPng()
                        : UiIcons.resource(path.substring("/app/icons/".length())).orElseThrow();
                assertEquals(200, get.statusCode(), path); assertEquals(200, head.statusCode(), path);
                assertArrayEquals(expected, get.body(), path); assertEquals(0, head.body().length, path);
                assertEquals(Integer.toString(expected.length), head.headers().firstValue("Content-Length").orElseThrow());
                assertEquals(path.endsWith(".ico") ? "image/x-icon" : "image/png", get.headers().firstValue("Content-Type").orElseThrow());
                assertEquals(get.headers().firstValue("Content-Type"), head.headers().firstValue("Content-Type"));
                secure(get); secure(head);
            }
        }
    }

    /** Заголовок до bootstrap приходит из общего каталога, а не из копии текста в HTML. */
    @Test void initialDocumentTitleUsesSharedCatalog() throws Exception {
        try (var source = StaticHandler.class.getResourceAsStream("/web/index.html")) {
            assertNotNull(source);
            assertTrue(new String(source.readAllBytes(), StandardCharsets.UTF_8)
                    .contains("<title>{{alert.info.title}}</title>"));
        }
        try (Fixture fixture = new Fixture()) {
            var response = fixture.request("GET", "/");
            assertEquals(200, response.statusCode());
            String html = new String(response.body(), StandardCharsets.UTF_8);
            assertTrue(html.contains("<title>" + ru.cashprediction.core.ui.text.UiText.get("alert.info.title") + "</title>"));
            assertFalse(html.contains("{{alert.info.title}}"));
        }
    }

    /** ES-модуль создаётся из общего манифеста, а неизвестные пути и методы не получают доступ к ресурсам. */
    @Test void manifestWhitelistTraversalAndMethodsKeepHttpContract() throws Exception {
        try (Fixture fixture = new Fixture()) {
            var module = fixture.request("GET", "/app/icons.js");
            assertEquals(200, module.statusCode());
            assertEquals("export const icons = Object.freeze(" + UiJson.write(UiIcons.manifest()) + ");\n",
                    new String(module.body(), StandardCharsets.UTF_8));
            assertEquals("text/javascript; charset=utf-8", module.headers().firstValue("Content-Type").orElseThrow());
            var head = fixture.request("HEAD", "/app/icons.js");
            assertEquals(200, head.statusCode()); assertEquals(0, head.body().length);
            assertEquals(Integer.toString(module.body().length), head.headers().firstValue("Content-Length").orElseThrow());
            secure(module); secure(head);
            for (String suffix : new String[]{"unknown.png", "../application.png", "%2e%2e/application.png", "%2fapplication.png",
                    "%5capplication.png", "folder/application.png", "application.PNG", "application.png%00", "%252e%252e/application.png"}) {
                var missing = fixture.request("GET", "/app/icons/" + suffix);
                assertEquals(404, missing.statusCode(), suffix); secure(missing);
            }
            for (String path : new String[]{"/favicon.png", "/app/icons.js", "/app/icons/application.png"}) {
                for (String method : new String[]{"POST", "PUT", "DELETE", "OPTIONS"}) {
                    var response = fixture.request(method, path);
                    assertEquals(405, response.statusCode());
                    assertEquals("GET, HEAD", response.headers().firstValue("Allow").orElseThrow()); secure(response);
                }
            }
        }
    }

    /** Общедоступная статика сохраняет прежнее поведение Host и не подменяет проверки защищённого API. */
    @Test void staticHostBehaviorRemainsPublic() throws Exception {
        try (Fixture fixture = new Fixture(); Socket socket = new Socket("127.0.0.1", fixture.server.getAddress().getPort())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write("GET /app/icons.js HTTP/1.1\r\nHost: public.example\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 200"));
            assertTrue(response.contains(UiJson.write(UiIcons.manifest())));
        }
    }

    /** Проверяет общую политику кэша и безопасности новых ответов. */
    private static void secure(HttpResponse<?> response) {
        assertEquals("no-store, no-cache, must-revalidate", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("no-cache", response.headers().firstValue("Pragma").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals("no-referrer", response.headers().firstValue("Referrer-Policy").orElseThrow());
    }

    /** Изолированный сервер JDK для проверки только статического обработчика. */
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        final URI base;
        /** Запускает только статику, без запуска приложения. */
        Fixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", new StaticHandler(new ServerLog(false)));
            server.start(); base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }
        /** Выполняет запрос и сохраняет двоичное тело без перекодировки. */
        HttpResponse<byte[]> request(String method, String path) throws Exception {
            return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
        }
        /** Останавливает принадлежащие проверке ресурсы. */
        @Override public void close() { server.stop(0); client.close(); }
    }
}
