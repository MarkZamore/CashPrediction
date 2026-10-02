package ru.cashprediction.parity.driver;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет авторизацию и строгие подтверждения закрытого локального тестового API. */
class TestApiBridgeHttpTest {
    /** Сервер получает тот же токен; подтверждение 200 требует ok:true. */
    @Test void sendsTokenAndRequiresAcknowledgement() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> tokens = new ArrayList<>();
        server.createContext("/api/test/result", exchange -> {
            tokens.add(exchange.getRequestHeaders().getFirst("X-Token"));
            exchange.getRequestBody().readAllBytes();
            byte[] body = (tokens.size() == 1 ? "{\"ok\":true}" : "{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var sender = TestApiBridge.http(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/app.html?t=secret"));
            sender.post("/api/test/result", Map.of("n", 1, "ok", true));
            assertThrows(IllegalStateException.class, () -> sender.post("/api/test/result", Map.of()));
            assertEquals(List.of("secret", "secret"), tokens);
            assertThrows(IllegalArgumentException.class, () -> sender.post("/api/intent", Map.of()));
        } finally { server.stop(0); }
    }

    /** Перенаправление не пересылает токен на другой маршрут. */
    @Test void refusesRedirect() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/test/result", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Location", "/other");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        server.start();
        try {
            var sender = TestApiBridge.http(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/?t=secret"));
            assertThrows(IllegalStateException.class, () -> sender.post("/api/test/result", Map.of()));
        } finally { server.stop(0); }
    }

    /** Адреса вне loopback, пользователь в URI и отсутствие ключа отклоняются до соединения. */
    @Test void rejectsUnsafeOrigins() {
        for (String url : List.of("https://127.0.0.1:12/?t=x", "http://example.com:12/?t=x",
                "http://127.0.0.1:12/", "http://user@127.0.0.1:12/?t=x", "http://127.0.0.1/?t=x"))
            assertThrows(IllegalArgumentException.class, () -> TestApiBridge.http(URI.create(url)), url);
    }
}
