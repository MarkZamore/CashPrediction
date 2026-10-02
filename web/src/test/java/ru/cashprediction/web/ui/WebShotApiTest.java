package ru.cashprediction.web.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.ui.selftest.SelfTestScript;

/** Проверяет закрытый HTTP-приём PNG; синтетический снимок проверяет протокол, а не UI-паритет. */
class WebShotApiTest {
    @TempDir Path output;
    private final ControllerThread thread = new ControllerThread();
    private final HttpClient http = HttpClient.newHttpClient();
    private HttpServer server;
    private WebSelfTestBridge bridge;

    @BeforeEach void start() throws Exception {
        var log = new EffectLog();
        bridge = new WebSelfTestBridge(log, SelfTestScript.parse("shots", "shot actual\nmenu file.sample\nshot second\n"),
                output, () -> LocalDate.of(2026, 9, 13));
        bridge.connected("browser");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/", new UiApi(thread, null, null, log, "secret", () -> server.getAddress().getPort(), bridge));
        server.start();
    }

    @AfterEach void close() {
        if (server != null) server.stop(0);
        bridge.close(); thread.close(); http.close();
    }

    @Test void storesExactBytesOnlyOnceAndRequiresShotBeforeSuccess() throws Exception {
        byte[] bytes = png(1200, 800);
        assertEquals(400, post("/api/test/result", Map.of("n", 1L, "ok", true)));
        assertEquals(200, post("/api/test/shot", shot(1, bytes)));
        assertArrayEquals(bytes, Files.readAllBytes(output.resolve("shots/actual.png")));
        String raw = Files.readString(output.resolve("shots/actual.raw.json"));
        assertTrue(raw.contains("\"x\":1113")); assertTrue(raw.contains("\"width\":87"));
        assertTrue(raw.contains("\"scenario\":\"shots\"")); assertTrue(raw.contains("\"step\":\"actual\""));
        assertEquals(400, post("/api/test/shot", shot(1, png(1200, 800))));
        assertEquals(200, post("/api/test/result", Map.of("n", 1L, "ok", true)));
        assertEquals(400, post("/api/test/shot", shot(2, bytes)), "A menu step cannot accept a screenshot");
        assertEquals(200, post("/api/test/result", Map.of("n", 2L, "ok", true)));
        assertEquals(400, post("/api/test/result", Map.of("n", 3L, "ok", true)), "Receipt resets for each shot");
        assertEquals(200, post("/api/test/shot", shot(3, bytes)));
        assertEquals(200, post("/api/test/result", Map.of("n", 3L, "ok", true)));
        assertEquals(400, post("/api/test/shot", shot(3, bytes)));
        assertTrue(bridge.completion().isDone());
        try (var paths = Files.walk(output)) {
            assertEquals(Set.of("shots/actual.png", "shots/second.png", "shots/actual.raw.json", "shots/second.raw.json", "selftest.log"),
                    paths.filter(Files::isRegularFile).map(path -> output.relativize(path).toString().replace('\\', '/'))
                            .collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test void malformedAndWrongStepRequestsCannotWriteOrAdvance() throws Exception {
        byte[] bytes = png(1200, 800);
        var invalid = new ArrayList<Map<String, Object>>();
        invalid.add(shot(0, bytes)); invalid.add(shot(2, bytes));
        invalid.add(Map.of("n", 1.5, "png", Base64.getEncoder().encodeToString(bytes)));
        invalid.add(Map.of("n", 1L, "png", "%%%"));
        invalid.add(Map.of("n", 1L, "png", ""));
        invalid.add(Map.of("n", 1L, "png", 42));
        invalid.add(Map.of("n", 1L, "png", Base64.getEncoder().encodeToString(bytes), "path", "../outside.png"));
        invalid.add(shot(1, new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'}));
        invalid.add(shot(1, Arrays.copyOf(bytes, bytes.length / 2)));
        invalid.add(shot(1, png(1201, 800)));
        invalid.add(shot(1, png(1200, 799)));
        invalid.add(Map.of("n", 1L, "png", "A".repeat(((4 * 1024 * 1024 + 2) / 3) * 4 + 4)));
        for (var body : invalid) {
            var complete = new java.util.LinkedHashMap<>(body);
            complete.putIfAbsent("dump", shot(1, bytes).get("dump"));
            assertEquals(400, post("/api/test/shot", complete));
        }
        assertEquals(400, post("/api/test/shot", Map.of("n", 1L, "png", Base64.getEncoder().encodeToString(bytes))));
        for (var invalidDump : List.of(Map.of("client", "fx", "schema", 1), Map.of("client", "web", "schema", 2))) {
            var complete = new java.util.LinkedHashMap<>(shot(1, bytes)); complete.put("dump", invalidDump);
            assertEquals(400, post("/api/test/shot", complete));
        }
        assertFalse(Files.exists(output.resolve("shots")));
        assertEquals(400, post("/api/test/result", Map.of("n", 1L, "ok", true)));
        assertEquals(200, post("/api/test/shot", shot(1, bytes)), "Invalid uploads leave the step retryable");
    }

    @Test void disabledEndpointAndMissingOrWrongTokenRejectBeforeParsing() throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/test/shot");
        for (String token : List.of("", "wrong")) {
            var request = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.ofString("not JSON"));
            if (!token.isEmpty()) request.header("X-Token", token);
            assertEquals(403, http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        server.removeContext("/api/");
        server.createContext("/api/", new UiApi(thread, null, null, new EffectLog(), "secret", () -> server.getAddress().getPort(), null));
        assertEquals(404, http.send(HttpRequest.newBuilder(uri).header("X-Token", "secret")
                .POST(HttpRequest.BodyPublishers.ofString("not JSON")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertFalse(Files.exists(output.resolve("shots")));
    }

    @Test void unsafeScenarioAndStepNamesCannotEscapeOutput() throws Exception {
        for (var script : List.of(SelfTestScript.parse("../escape", "shot actual\n"),
                SelfTestScript.parse("shots", "shot ../escape\n"))) {
            try (var unsafe = new WebSelfTestBridge(new EffectLog(), script, output, () -> LocalDate.of(2026, 9, 13))) {
                unsafe.connected("browser");
                assertThrows(IllegalArgumentException.class, () -> unsafe.receive("/api/test/shot", shot(1, png(1200, 800))));
            }
        }
        assertFalse(Files.exists(output.resolveSibling("escape.png")));
    }

    private int post(String route, Map<String, Object> body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + route))
                .header("X-Token", "secret").POST(HttpRequest.BodyPublishers.ofString(JsonWriter.write(body))).build(),
                HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private static Map<String, Object> shot(long n, byte[] bytes) {
        return Map.of("n", n, "png", Base64.getEncoder().encodeToString(bytes),
                "dump", Map.of("client", "web", "schema", 1,
                        "frame", Map.of("regions", Map.of("edge", Map.of("x", 1113, "width", 87)))));
    }

    private static byte[] png(int width, int height) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "PNG", bytes);
        return bytes.toByteArray();
    }
}
