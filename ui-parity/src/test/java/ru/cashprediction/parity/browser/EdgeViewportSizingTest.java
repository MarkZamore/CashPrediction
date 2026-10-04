package ru.cashprediction.parity.browser;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет точный viewport, независимые измерения и настоящий PNG без изменения продуктового протокола. */
class EdgeViewportSizingTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    @TempDir Path home;

    /** Единственная новая команда задаёт настольный viewport с масштабом 1 и не подменяет измерения JS. */
    @Test void viewportOverrideSendsExactDesktopMetrics() throws Exception {
        try (Wire wire = new Wire()) {
            wire.client.setViewport(400, 800, TIMEOUT);
            assertEquals(1, wire.requests.size());
            Map<String, Object> request = wire.requests.getFirst();
            assertEquals("Emulation.setDeviceMetricsOverride", request.get("method"));
            assertEquals(Map.of("width", 400L, "height", 800L, "deviceScaleFactor", 1L, "mobile", false),
                    Json.object(request, "params"));
            assertFalse(wire.closed.get(), "Metrics connection must stay attached until browser shutdown");
        }
    }

    /** Неверные размеры отвергаются до отправки команды; ошибка CDP не становится успехом запуска. */
    @Test void invalidMetricsAndProtocolFailureAreNotAccepted() throws Exception {
        try (Wire wire = new Wire()) {
            assertThrows(IllegalArgumentException.class, () -> wire.client.setViewport(0, 800, TIMEOUT));
            assertThrows(IllegalArgumentException.class, () -> wire.client.setViewport(400, -1, TIMEOUT));
            assertThrows(IllegalArgumentException.class, () -> wire.client.setViewport(400, 800, Duration.ZERO));
            assertTrue(wire.requests.isEmpty());
            wire.refuseMetrics = true;
            assertThrows(CdpClient.CdpException.class, () -> wire.client.setViewport(400, 800, TIMEOUT));
            assertEquals(1, wire.requests.size());
        }
    }

    /** Даже успешное ожидание проверяется отдельным чтением фактических размеров и масштаба. */
    @Test void independentObservationRejectsMinimumWindowAndWrongScale() throws Exception {
        for (List<Long> measured : List.of(List.of(492L, 800L, 1L), List.of(400L, 799L, 1L), List.of(400L, 800L, 2L))) {
            try (Wire wire = new Wire()) {
                wire.evaluations.add(true);
                wire.evaluations.add(measured);
                assertThrows(IllegalStateException.class,
                        () -> EdgeLauncher.validateViewport(wire.client, 400, 800, Duration.ofMillis(100)));
                assertEquals(2, wire.requests.size());
                assertTrue(wire.requests.stream().allMatch(request -> "Runtime.evaluate".equals(request.get("method"))));
            }
        }
    }

    /** При отсутствии точного фактического viewport ожидание завершается ошибкой, без перехода к приложению. */
    @Test void unchangedViewportTimesOutInsteadOfAcceptingOverrideAcknowledgement() throws Exception {
        try (Wire wire = new Wire()) {
            wire.evaluations.add(false);
            assertThrows(CdpClient.CdpException.class,
                    () -> EdgeLauncher.validateViewport(wire.client, 400, 800, Duration.ofNanos(1)));
            assertTrue(wire.requests.stream().noneMatch(request -> "Page.navigate".equals(request.get("method"))));
        }
    }

    /** Точный viewport подтверждается двумя чтениями, сохраняющими исходный Runtime.evaluate. */
    @Test void exactViewportIsReturnedFromObservedValues() throws Exception {
        try (Wire wire = new Wire()) {
            wire.evaluations.add(true);
            wire.evaluations.add(List.of(400L, 800L, 1L));
            assertArrayEquals(new long[]{400, 800}, EdgeLauncher.validateViewport(wire.client, 400, 800, TIMEOUT));
            assertEquals("window.innerWidth === 400 && window.innerHeight === 800 && window.devicePixelRatio === 1",
                    Json.object(wire.requests.getFirst(), "params").get("expression"));
            assertEquals("[window.innerWidth, window.innerHeight, window.devicePixelRatio]",
                    Json.object(wire.requests.getLast(), "params").get("expression"));
        }
    }

    /** Реальный браузер доказывает размеры до первого скрипта страницы, после повторного подключения и в PNG. */
    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    void realBrowserPreserves400And1200ViewportsAcrossNavigationAndScreenshots() throws Exception {
        Path executable = EdgeLauncher.findBrowser().orElseThrow(() -> new IllegalStateException(
                "Real Chromium required for viewport sizing evidence: " + EdgeLauncher.searchedLocations()));
        String html = "<!doctype html><html><head><script>window.initialViewport=[innerWidth,innerHeight,devicePixelRatio]</script>"
                + "</head><body style='margin:0;background:rgb(30,111,217)'><div style='height:100px;background:rgb(250,200,20)'>"
                + "viewport</div></body></html>";
        String url = "data:text/html;base64," + Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
        // Узкий запуск не должен отравлять кэш рамки для 1200; повторный узкий запуск проверяет обратный переход.
        int run = 0;
        for (int width : new int[]{400, 1200, 400}) {
            try (BrowserSession browser = EdgeLauncher.startWithViewport(executable, home.resolve("profile-" + run++),
                    width, 800, url, TIMEOUT)) {
                try (CdpClient cdp = CdpClient.connectToFirstPage(browser.port(), TIMEOUT)) {
                    cdp.waitFor("document.readyState === 'complete' && !!window.initialViewport", TIMEOUT);
                    assertEquals(List.of((long) width, 800L, 1L), cdp.evaluate("window.initialViewport"));
                    assertEquals(width + "x800", browser.viewportSize());
                    assertViewportAndPng(cdp, width);
                }
                // Закрытие независимого соединения не должно сбрасывать override удерживающего соединения.
                try (CdpClient reconnected = CdpClient.connectToFirstPage(browser.port(), TIMEOUT)) {
                    reconnected.navigate(url);
                    reconnected.waitFor("document.readyState === 'complete' && !!window.initialViewport", TIMEOUT);
                    assertEquals(List.of((long) width, 800L, 1L), reconnected.evaluate("window.initialViewport"));
                    assertViewportAndPng(reconnected, width);
                }
            }
        }
    }

    /** Декодирует настоящий screenshot без изменения размера и проверяет два цветных участка страницы. */
    private static void assertViewportAndPng(CdpClient cdp, int width) throws Exception {
        assertArrayEquals(new long[]{width, 800}, EdgeLauncher.validateViewport(cdp, width, 800, TIMEOUT));
        byte[] png = cdp.captureScreenshot();
        assertEquals(new PngHeader(width, 800), PngHeader.read(png));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(width, image.getWidth());
        assertEquals(800, image.getHeight());
        assertEquals(0xFAC814, image.getRGB(width / 2, 50) & 0xFFFFFF);
        assertEquals(0x1E6FD9, image.getRGB(width / 2, 500) & 0xFFFFFF);
    }

    /** Подменяет только транспорт CDP для проверок сообщений, не выдавая эти проверки за браузерные измерения. */
    private static final class Wire implements AutoCloseable {
        private final List<Map<String, Object>> requests = new ArrayList<>();
        private final Deque<Object> evaluations = new ArrayDeque<>();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CdpClient client;
        private boolean refuseMetrics;

        /** Использует закрытый конструктор тестового клиента, сохраняя его настоящий путь JSON и ответов. */
        private Wire() throws Exception {
            WebSocket socket = (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(), new Class<?>[]{WebSocket.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "sendText" -> { respond(args[0].toString()); yield CompletableFuture.completedFuture(proxy); }
                        case "sendClose" -> { closed.set(true); yield CompletableFuture.completedFuture(proxy); }
                        case "abort" -> { closed.set(true); yield null; }
                        case "isInputClosed", "isOutputClosed" -> closed.get();
                        default -> throw new AssertionError(method.getName());
                    });
            Constructor<CdpClient> constructor = CdpClient.class.getDeclaredConstructor(HttpClient.class, WebSocket.class);
            constructor.setAccessible(true);
            client = constructor.newInstance(HttpClient.newHttpClient(), socket);
        }

        /** Отвечает через настоящий разборщик клиента с тем же id и управляемыми наблюдаемыми значениями. */
        private void respond(String text) throws Exception {
            Map<String, Object> request = JsonParser.parseObject(text);
            requests.add(request);
            String command = (String) request.get("method");
            Map<String, Object> response;
            if (command.equals("Emulation.setDeviceMetricsOverride") && refuseMetrics)
                response = Map.of("id", request.get("id"), "error", Map.of("code", -32601, "message", "unsupported"));
            else {
                Object result = switch (command) {
                    case "Emulation.setDeviceMetricsOverride" -> Map.of();
                    case "Runtime.evaluate" -> Map.of("result", Map.of("value", evaluations.removeFirst()));
                    default -> throw new AssertionError(command);
                };
                response = Map.of("id", request.get("id"), "result", result);
            }
            Method receiver = CdpClient.class.getDeclaredMethod("onMessage", String.class);
            receiver.setAccessible(true);
            receiver.invoke(client, JsonWriter.write(response));
        }

        /** Закрывает настоящий клиент и завершает ожидание транспорта в тесте override. */
        @Override public void close() { client.close(); }
    }
}
