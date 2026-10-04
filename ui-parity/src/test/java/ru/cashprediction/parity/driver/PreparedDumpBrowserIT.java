package ru.cashprediction.parity.driver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.token.UiIcons;
import ru.cashprediction.parity.browser.CdpClient;
import ru.cashprediction.parity.browser.EdgeLauncher;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверяет frozen подготовку дампа и синхронное DOM чтение на localhost в настоящем Edge.
 * Production Table, CSS и импортируемые модули обслуживаются без замены исходников.
 * Ответы строк и каркас app являются изолированными fixtures, не запуском приложения;
 * тест не доказывает complete CSSOM, PNG/CDP bracket или whole-app паритет.
 */
class PreparedDumpBrowserIT {
    @TempDir Path temporary;

    /** Реальное чтение сохраняет схему 1, а stale/identity/native DOM/CSS ошибки остаются отказами. */
    @Test void actualPreparedReadAndFailures() throws Exception {
        Path root = Path.of(System.getProperty("parity.reactor.root", "..")).toAbsolutePath().normalize();
        Map<String, byte[]> assets = new HashMap<>();
        for (String name : List.of("dump.js", "dom.js", "render-form.js", "render-alert.js", "icon.js",
                "render-table.js", "layout.css"))
            assets.put("/app/" + name, Files.readAllBytes(root.resolve("web/src/main/resources/web/app/" + name)));
        assets.put("/app/icons.js", ("export const icons = Object.freeze(" + UiJson.write(UiIcons.manifest())
                + ");\n").getBytes(StandardCharsets.UTF_8));
        assets.put("/prepared-dump-probe.js", Files.readAllBytes(root.resolve(
                "web/src/test/resources/ui/prepared-dump-probe.js")));
        assets.put("/probe.js", Files.readAllBytes(root.resolve(
                "ui-parity/src/test/resources/driver/prepared-dump-browser-probe.js")));
        assets.put("/fixture/rows", rowResponse());
        Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        AtomicInteger foreignRequests = new AtomicInteger();
        HttpServer foreign = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Второй порт даёт настоящий другой origin без CORS и без подмены cssRules getter.
        foreign.createContext("/foreign.css", exchange -> {
            foreignRequests.incrementAndGet();
            respond(exchange, ".foreign-unused-fixture { color: rgb(11, 12, 13); }".getBytes(StandardCharsets.UTF_8),
                    "text/css; charset=utf-8");
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String foreignUrl = "http://127.0.0.1:" + foreign.getAddress().getPort() + "/foreign.css";
        server.createContext("/", exchange -> serve(exchange, assets, counts, foreignUrl));
        try {
            foreign.start(); server.start();
            Path edge = EdgeLauncher.findBrowser().orElseThrow();
            assertEquals("msedge.exe", edge.getFileName().toString().toLowerCase(Locale.ROOT),
                    "Actual Microsoft Edge is required, not the Chrome fallback");
            try (var browser = EdgeLauncher.startWithViewport(edge, temporary.resolve("edge-prepared-dump"),
                    1200, 800, "http://127.0.0.1:" + server.getAddress().getPort() + "/", Duration.ofSeconds(15));
                 var cdp = CdpClient.connectToFirstPage(browser.port(), Duration.ofSeconds(10))) {
                cdp.waitFor("window.probeReady === true", Duration.ofSeconds(10));
                Map<?, ?> result = object(cdp.evaluate("window.runPreparedDumpBrowserProbe()", Duration.ofSeconds(30)));
                for (String key : List.of("ordinarySchemaEqual", "frozenIdentity", "liveDom", "modelNotSerialized",
                        "singleUse", "forgedTicket", "captureIdentity", "attemptIdentity", "intentIdentity",
                        "staleGeneration", "staleSequence", "staleEpoch", "staleModel", "pendingRequest",
                        "mutationPendingAba", "mutationDeliveredAba", "cssChanged", "nativeForeignSecurityError",
                        "foreignCssUnsupported", "explicitDiscard"))
                    assertEquals(Boolean.TRUE, result.get(key), key);
                assertEquals(0, ((Number) result.get("mutationCount")).intValue());
                assertTrue(((Number) result.get("scrollChecked")).intValue() > 0);
                assertEquals(0, ((Number) result.get("requestsDuringRead")).intValue());
                Map<?, ?> raw = object(result.get("raw")), table = object(raw.get("table"));
                assertEquals(1, ((Number) raw.get("schema")).intValue());
                assertEquals("web", raw.get("client"));
                assertEquals("prepared-browser", raw.get("scenario"));
                assertEquals("read", raw.get("step"));
                Map<?, ?> frame = object(raw.get("frame"));
                assertEquals(1200, ((Number) frame.get("contentWidth")).intValue());
                assertEquals(800, ((Number) frame.get("contentHeight")).intValue());
                assertTrue(object(frame.get("regions")).containsKey("toolbar.baseline"));
                assertTrue(object(frame.get("regions")).containsKey("status.baseline"));
                assertEquals(List.of("schema", "client", "scenario", "step", "frame", "menuBar", "toolbar",
                        "summary", "table", "chart", "status", "contextMenus", "windows", "alerts", "popups",
                        "screens", "chooserRequests", "classCensus", "counters"), result.get("schemaKeys"));
                assertEquals(84, ((Number) table.get("rowCount")).intValue());
                assertEquals(List.of("Date", "Amount"), table.get("columns"));
                assertEquals("row65", table.get("selectedRowId"));
                List<?> rows = (List<?>) table.get("rows"); assertEquals(61, rows.size());
                assertEquals(List.of("DOM row 0", "Value 0"), object(rows.getFirst()).get("cells"));
                assertEquals(List.of("DOM row 65", "Value 65"), object(rows.getLast()).get("cells"));
                assertEquals(65, ((Number) object(rows.getLast()).get("index")).intValue());
                assertEquals(expectedDigest(), table.get("rowsDigest"));
                assertEquals(Boolean.TRUE, result.get("scrollRestored"));
                assertEquals(1, foreignRequests.get(), "The unreadable stylesheet must be loaded over real HTTP");
                assertEquals(1, counts.get("/fixture/rows").get(), "Actual Table page cache should need one fixture response");
                for (String path : List.of("/app/dump.js", "/app/render-table.js", "/app/dom.js",
                        "/app/render-form.js", "/app/render-alert.js", "/app/icon.js", "/app/icons.js",
                        "/app/layout.css", "/prepared-dump-probe.js", "/probe.js"))
                    assertEquals(1, counts.get(path).get(), "Actual resource: " + path);
                assertFalse(counts.keySet().stream().anyMatch(path -> path.startsWith("/api/")),
                        "The isolated fixture must not contact an application API");
            }
        } finally { server.stop(0); foreign.stop(0); }
    }

    /** Проверяет тип результата CDP перед чтением вложенного JSON объекта. */
    private static Map<?, ?> object(Object value) {
        assertInstanceOf(Map.class, value); return (Map<?, ?>) value;
    }

    /** Создаёт единственный bounded ответ строк для production Table.page, не заменяя DOM renderer. */
    private static byte[] rowResponse() {
        List<Object> rows = new ArrayList<>();
        for (int index = 0; index < 84; index++) rows.add(Map.of("rowId", "row" + index, "kind", "DATA",
                "cells", List.of("DOM row " + index, "Value " + index), "leadingSpan", 1,
                "rowStyle", Map.of("text", "TEXT_PRIMARY", "background", "BG_SURFACE"), "cellStyles", Map.of()));
        return UiJson.write(Map.of("stale", false, "result", rows)).getBytes(StandardCharsets.UTF_8);
    }

    /** Независимо считает SHA256 прежнего length-prefixed формата текстов fixture, не paint токенов. */
    private static String expectedDigest() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (int index = 0; index < 84; index++) {
            byte[] cells = UiJson.write(List.of("DOM row " + index, "Value " + index)).getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(cells.length).array()); digest.update(cells);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Обслуживает только разрешённые ресурсы fixture с CSP, без произвольного доступа к файловой системе. */
    private static void serve(HttpExchange exchange, Map<String, byte[]> assets,
                              Map<String, AtomicInteger> counts, String foreignUrl) throws IOException {
        String path = exchange.getRequestURI().getPath();
        counts.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
        byte[] bytes = assets.get(path);
        String type = path.endsWith(".css") ? "text/css; charset=utf-8" : "text/javascript; charset=utf-8";
        if (path.equals("/fixture/rows")) type = "application/json; charset=utf-8";
        if (path.equals("/")) {
            bytes = page(foreignUrl).getBytes(StandardCharsets.UTF_8); type = "text/html; charset=utf-8";
            String foreignOrigin = foreignUrl.substring(0, foreignUrl.lastIndexOf('/'));
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' " + foreignOrigin
                            + "; connect-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'");
        }
        respond(exchange, bytes, type);
    }

    /** Закрывает HTTP exchange и отдаёт точные bytes без кеширования и CORS разрешения. */
    private static void respond(HttpExchange exchange, byte[] bytes, String type) throws IOException {
        try {
            if (bytes == null) { exchange.sendResponseHeaders(404, -1); return; }
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        } finally { exchange.close(); }
    }

    /** Создаёт отдельный HTML каркас без bootstrap/main/test-driver и без запуска пользовательского приложения. */
    private static String page(String foreignUrl) {
        return """
                <!doctype html><html><head><meta charset=utf-8><title>Prepared dump browser fixture</title>
                <link rel=stylesheet href=/app/layout.css></head><body data-foreign-css="%s">
                <main id=main><nav id=menuBar></nav><div id=toolbar>
                <div class=toolbar-node data-cp-id=fixture.button data-kind=Button><button><span class=toolbar-label>Fixture</span></button></div>
                </div><section id=summary><div class=card data-cp-id=balance>
                <div class=card-title>Balance</div><div class=card-value>DOM before</div><div class=card-caption>Fixture caption</div>
                </div></section><section id=center><div id=table role=grid tabindex=0></div><div id=chart hidden></div></section>
                <footer id=status><span class=status-segment data-cp-id=fixture.status>Ready</span></footer></main>
                <!-- JavaFX: Alert → Swing: SwingAlerts → Web: скрытый dialog каркаса fixture. -->
                <dialog id=screens hidden></dialog><script type=module src=/probe.js></script></body></html>
                """.formatted(foreignUrl);
    }
}
