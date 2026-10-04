package ru.cashprediction.parity.driver;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
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

/** Проверяет реальные Web-декодирования общих PNG; отдельная страница не заменяет паритет приложения. */
class RetainedIconSourcesBrowserIT {
    @TempDir Path temporary;

    /** Первоначальный HTTP-ответ устанавливается как blob, повторные узлы не вызывают повторной сети. */
    @Test void originalResponseOwnsImageAndBackgroundBindings() throws Exception {
        Path root = Path.of(System.getProperty("parity.reactor.root", ".." )).toAbsolutePath().normalize();
        Map<String, byte[]> scripts = new HashMap<>();
        for (String file : new String[]{"icon.js", "paint-observation.js", "retained-icon-sources.js"})
            scripts.put("/app/" + file, Files.readAllBytes(root.resolve("web/src/main/resources/web/app/" + file)));
        scripts.put("/app/icons.js", ("export const icons = Object.freeze(" + UiJson.write(UiIcons.manifest())
                + ");").getBytes(StandardCharsets.UTF_8));
        scripts.put("/probe.js", """
                import {enableRetainedIconCapture, iconText, controlIcon} from '/app/icon.js';
                window.probe = async () => {
                  const capture = await enableRetainedIconCapture();
                  const first = iconText(document.createElement('div'), '\\u2713');
                  const second = iconText(document.createElement('div'), '\\u2713');
                  document.body.append(first, second);
                  const image = first.querySelector('img'), peer = second.querySelector('img');
                  const background = document.createElement('div'); document.body.append(background);
                  controlIcon(background, '\\u2713');
                  await capture.sources.ready();
                  const installed = capture.registry.lookup(image, 'image-node', image.currentSrc);
                  const peerSource = capture.registry.lookup(peer, 'image-node', peer.currentSrc);
                  const backgroundUri = /^url\\(["']?([^"')]+)["']?\\)$/.exec(getComputedStyle(background).backgroundImage)[1];
                  const backgroundSource = capture.registry.lookup(background, 'css-background', backgroundUri);
                  const clone = image.cloneNode(); document.body.append(clone); await clone.decode();
                  const copiedMarkerIsNotProvenance = capture.registry.lookup(clone, 'image-node', clone.currentSrc) === null;
                  image.src = 'data:image/png;base64,' + installed.base64; await image.decode();
                  const replacedSourceIsNotProvenance = capture.registry.lookup(image, 'image-node', image.currentSrc) === null;
                  return {installed, peerSource, backgroundSource, copiedMarkerIsNotProvenance,
                    replacedSourceIsNotProvenance, peerUrl: peer.currentSrc, width: peer.naturalWidth,
                    height: peer.naturalHeight, pending: capture.sources.pending()};
                };
                window.probeReady = true;
                """.getBytes(StandardCharsets.UTF_8));
        Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            counts.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            byte[] bytes = scripts.get(path); String type = "text/javascript; charset=utf-8";
            if (path.equals("/")) {
                bytes = "<!doctype html><html><body><script type=module src=/probe.js></script></body></html>"
                        .getBytes(StandardCharsets.UTF_8); type = "text/html; charset=utf-8";
                exchange.getResponseHeaders().set("Content-Security-Policy",
                        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; connect-src 'self'");
            } else if (path.startsWith("/app/icons/")) {
                bytes = UiIcons.resource(path.substring("/app/icons/".length())).orElse(null); type = "image/png";
            }
            if (bytes == null) { exchange.sendResponseHeaders(404, -1); exchange.close(); return; }
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try (var browser = EdgeLauncher.startWithViewport(EdgeLauncher.findBrowser().orElseThrow(),
                temporary.resolve("edge"), 1200, 800,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", Duration.ofSeconds(15));
             var cdp = CdpClient.connectToFirstPage(browser.port(), Duration.ofSeconds(10))) {
            cdp.waitFor("window.probeReady === true", Duration.ofSeconds(10));
            Object value = cdp.evaluate("window.probe()", Duration.ofSeconds(15));
            assertInstanceOf(Map.class, value);
            Map<?, ?> result = (Map<?, ?>) value;
            Map<?, ?> image = (Map<?, ?>) result.get("installed");
            Map<?, ?> peer = (Map<?, ?>) result.get("peerSource");
            Map<?, ?> background = (Map<?, ?>) result.get("backgroundSource");
            assertNotNull(image); assertNotNull(peer); assertNotNull(background);
            String resource = new java.net.URI((String) image.get("source")).getPath();
            byte[] expected = UiIcons.resource(resource.substring("/app/icons/".length())).orElseThrow();
            assertArrayEquals(expected, Base64.getDecoder().decode((String) image.get("base64")));
            assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected)), image.get("sha256"));
            assertEquals(expected.length, ((Number) image.get("byteLength")).intValue());
            assertEquals(image.get("sha256"), peer.get("sha256"));
            assertEquals(image.get("sha256"), background.get("sha256"));
            assertNotEquals(image.get("sourceObjectIdentity"), peer.get("sourceObjectIdentity"));
            assertNotEquals(image.get("sourceObjectIdentity"), background.get("sourceObjectIdentity"));
            assertTrue(((String) result.get("peerUrl")).startsWith("blob:"));
            assertEquals(Boolean.TRUE, result.get("copiedMarkerIsNotProvenance"));
            assertEquals(Boolean.TRUE, result.get("replacedSourceIsNotProvenance"));
            assertTrue(((Number) result.get("width")).intValue() > 0);
            assertTrue(((Number) result.get("height")).intValue() > 0);
            assertEquals(0, ((Number) result.get("pending")).intValue());
            assertEquals(1, counts.get(resource).get(), "one original response, separate real decodings");
        } finally { server.stop(0); }
    }
}
