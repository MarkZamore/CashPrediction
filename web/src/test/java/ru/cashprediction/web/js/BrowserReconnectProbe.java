package ru.cashprediction.web.js;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;

/** HTTP-фикстура взаимного HMAC-доказательства для настоящего браузера, не production E2E. */
public final class BrowserReconnectProbe {
    private static final String ID = "12".repeat(16);
    private static final String KEY = "ab".repeat(32);
    private final Path root;
    private final AtomicInteger intents = new AtomicInteger(), challenges = new AtomicInteger(), completions = new AtomicInteger();
    private final AtomicInteger traps = new AtomicInteger(), rawKeyLeaks = new AtomicInteger(), badHeaders = new AtomicInteger();
    private final AtomicInteger badClientProofs = new AtomicInteger(), obsoleteCursors = new AtomicInteger();
    private final AtomicInteger queries = new AtomicInteger();
    private final AtomicInteger bootstraps = new AtomicInteger();
    private final Map<String, Map<String, Object>> pending = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String token = "fixture", generation = "34".repeat(16), mode = "normal";
    private volatile long seq = 900;
    private volatile boolean queryStarted;
    private String origin;

    private BrowserReconnectProbe(Path root) { this.root = root; }

    /** Запускает только явно вызванную проверку с изолированным профилем браузера. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected repository and evidence directories");
        Path output = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(output);
        var probe = new BrowserReconnectProbe(Path.of(args[0]).toAbsolutePath().normalize());
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        probe.origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.setExecutor(pool); server.createContext("/", probe::handle); server.start();
        try (var browser = BrowserFixtureProbe.BrowserBridge.start(output.resolve("edge"), 1200, probe.origin + "/?t=fixture");
             var cdp = browser.connect()) {
            cdp.waitFor("window.reconnectProbeReady === true", Duration.ofSeconds(20));
            Object result = cdp.evaluate("import('/fixture/probe.js').then(module => module.run())");
            Files.writeString(output.resolve("reconnect-result.json"), JsonWriter.write(result), StandardCharsets.UTF_8);
            System.out.println("BROWSER RECONNECT PROBE OK " + output);
        } finally { server.stop(0); pool.close(); }
    }

    /** Обрабатывает маршруты отдельной фикстуры, не открывая файлы по произвольному пути. */
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/")) {
                    send(exchange, 200, "text/html", "<!doctype html><div id=main><div id=center><div id=table style='height:100px;width:400px'></div><div id=chart></div></div><div id=menuBar></div></div><dialog id=screens hidden></dialog><script type=module src='/fixture/probe.js'></script>"); return;
                }
                if (path.equals("/fixture/probe.js")) {
                    send(exchange, 200, "text/javascript", Files.readString(root.resolve("web/src/test/resources/ui/reconnect-probe.js"))); return;
                }
                if (path.matches("/app/(transport|render-table|render-chart|render-menu|render-popups|render-form|dom|keys|icon|screens)\\.js")) {
                    send(exchange, 200, "text/javascript", Files.readString(root.resolve("web/src/main/resources/web" + path))); return;
                }
                if (path.equals("/app/icons.js")) {
                    send(exchange, 200, "text/javascript", "export const icons = Object.freeze(" + JsonWriter.write(ru.cashprediction.core.ui.token.UiIcons.manifest()) + ");\n"); return;
                }
                if (path.startsWith("/app/icons/")) {
                    byte[] bytes = ru.cashprediction.core.ui.token.UiIcons.resource(path.substring("/app/icons/".length())).orElseThrow();
                    exchange.getResponseHeaders().set("Content-Type", "image/png");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); return;
                }
                if (path.equals("/fixture/control")) {
                    String command = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (!command.equals("inspect")) {
                        mode = command;
                        if (command.equals("restart")) { token += "x"; generation = "56".repeat(16); seq = 0; mode = "normal"; pending.clear(); }
                        if (command.equals("disconnect")) { token += "x"; mode = "normal"; }
                        if (command.equals("slow-query")) queryStarted = false;
                    }
                    json(exchange, 200, counters()); return;
                }
                if (path.equals("/trap")) { traps.incrementAndGet(); json(exchange, 500, Map.of()); return; }
                if (path.startsWith("/api/ui/reconnect/")) { reconnect(exchange, path); return; }
                if (!token.equals(exchange.getRequestHeaders().getFirst("X-Token"))) { json(exchange, 403, Map.of()); return; }
                if (path.equals("/api/ui/bootstrap")) {
                    bootstraps.incrementAndGet();
                    if (mode.equals("fail-bootstrap")) { json(exchange, 503, Map.of()); return; }
                    json(exchange, 200, Map.of("seq", seq, "reconnect", Map.of("version", 1, "installationId", ID, "key", KEY))); return;
                }
                if (path.equals("/api/ui/intent")) {
                    intents.incrementAndGet(); exchange.getRequestBody().readAllBytes();
                    if (mode.equals("uncertain")) { token = "rotated"; json(exchange, 500, Map.of()); }
                    else json(exchange, 200, Map.of("effects", java.util.List.of()));
                    return;
                }
                if (path.equals("/api/ui/query")) {
                    queries.incrementAndGet();
                    var queryBody = JsonParser.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    queryStarted = true;
                    if (mode.equals("slow-query")) Thread.sleep(500);
                    Object result = switch (String.valueOf(queryBody.get("type"))) {
                        case "rows" -> java.util.List.of(Map.of("rowId", token, "kind", "START", "cells", java.util.List.of(), "leadingSpan", 1));
                        case "contextMenu" -> java.util.List.of();
                        case "chartScene" -> Map.of("width", 400, "height", 100, "primitives", java.util.List.of(), "legend", java.util.List.of());
                        case "chartHover" -> Map.of();
                        case "calendar" -> Map.of("month", "2026-09", "title", "fixture", "weekdays", java.util.List.of(), "days", java.util.List.of());
                        default -> "fixture";
                    };
                    json(exchange, 200, Map.of("result", result)); return;
                }
                if (path.equals("/api/ui/events")) {
                    String query = exchange.getRequestURI().getQuery();
                    if (query != null && query.contains("after=900") && seq == 0) obsoleteCursors.incrementAndGet();
                    Thread.sleep(100); json(exchange, 200, Map.of("effects", java.util.List.of())); return;
                }
                json(exchange, 404, Map.of());
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            catch (Exception error) { json(exchange, 500, Map.of("error", "fixture failure")); }
        }
    }

    /** Выполняет протокол без приёма исходного ключа или обычного токена. */
    private void reconnect(HttpExchange exchange, String path) throws Exception {
        String wire = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (wire.contains(KEY)) rawKeyLeaks.incrementAndGet();
        if (!"POST".equals(exchange.getRequestMethod()) || !"1".equals(exchange.getRequestHeaders().getFirst("X-CP-Reconnect"))
                || !"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))
                || exchange.getRequestHeaders().getFirst("X-Token") != null) {
            badHeaders.incrementAndGet(); json(exchange, 403, Map.of()); return;
        }
        var body = JsonParser.parseObject(wire);
        if (path.endsWith("challenge")) {
            challenges.incrementAndGet();
            if (!body.keySet().equals(java.util.Set.of("installationId", "clientNonce")) || !ID.equals(body.get("installationId"))) {
                json(exchange, 403, Map.of()); return;
            }
            if (mode.equals("redirect")) { exchange.getResponseHeaders().set("Location", "/trap"); json(exchange, 307, Map.of()); return; }
            String nonce = String.valueOf(body.get("clientNonce"));
            if (!nonce.matches("[0-9a-f]{64}")) { json(exchange, 400, Map.of()); return; }
            String id = java.util.UUID.randomUUID().toString().replace("-", "");
            var challenge = new LinkedHashMap<String, Object>();
            challenge.put("version", 1); challenge.put("installationId", ID); challenge.put("serverGeneration", generation);
            challenge.put("clientNonce", mode.equals("wrong-nonce") ? "00".repeat(32) : nonce);
            challenge.put("serverNonce", "78".repeat(32)); challenge.put("challengeId", id);
            pending.put(id, Map.copyOf(challenge));
            String proofOrigin = mode.equals("wrong-origin-proof") ? "http://127.0.0.1:1" : origin;
            String proofKey = mode.equals("wrong-proof") ? "00".repeat(32) : KEY;
            challenge.put("serverProof", proof(proofKey, "server", proofOrigin, challenge));
            json(exchange, 200, challenge);
        } else if (path.endsWith("complete")) {
            completions.incrementAndGet();
            var challenge = pending.remove(String.valueOf(body.get("challengeId")));
            if (challenge == null || !body.keySet().equals(java.util.Set.of("challengeId", "clientProof"))) {
                badClientProofs.incrementAndGet(); json(exchange, 403, Map.of()); return;
            }
            String expected = proof(KEY, "client", origin, challenge);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), String.valueOf(body.get("clientProof")).getBytes(StandardCharsets.US_ASCII))) {
                badClientProofs.incrementAndGet(); json(exchange, 403, Map.of()); return;
            }
            json(exchange, 200, Map.of("token", token, "serverGeneration", generation));
        } else json(exchange, 404, Map.of());
    }

    /** Вычисляет независимый JDK-эталон канонического WebCrypto-сообщения. */
    private static String proof(String key, String role, String origin, Map<String, Object> challenge) throws Exception {
        String text = String.join("\n", "cashprediction-web-reconnect-v1", role, origin,
                String.valueOf(challenge.get("installationId")), String.valueOf(challenge.get("serverGeneration")),
                String.valueOf(challenge.get("clientNonce")), String.valueOf(challenge.get("serverNonce")), String.valueOf(challenge.get("challengeId")));
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(HexFormat.of().parseHex(key), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** Возвращает только обезличенные счётчики, без токенов и ключей. */
    private Map<String, Object> counters() {
        var values = new LinkedHashMap<String, Object>(Map.of("intents", intents.get(), "challenges", challenges.get(), "completions", completions.get(),
                "traps", traps.get(), "rawKeyLeaks", rawKeyLeaks.get(), "badHeaders", badHeaders.get(),
                "badClientProofs", badClientProofs.get(), "obsoleteCursors", obsoleteCursors.get(), "queryStarted", queryStarted, "queries", queries.get()));
        values.put("bootstraps", bootstraps.get());
        return Map.copyOf(values);
    }

    /** Отправляет JSON с запретом кеширования. */
    private static void json(HttpExchange exchange, int status, Object body) throws IOException { send(exchange, status, "application/json", JsonWriter.write(body)); }
    /** Отправляет ответ фикстуры без записи тела в журнал. */
    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=UTF-8"); exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
}
