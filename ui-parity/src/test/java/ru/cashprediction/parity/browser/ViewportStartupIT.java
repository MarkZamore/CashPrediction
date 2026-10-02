package ru.cashprediction.parity.browser;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Реальная калибровка браузера не должна запускать bootstrap в брошенной вкладке. */
class ViewportStartupIT {
    @TempDir Path root;

    /** Приложение посещается ровно один раз, уже в окончательном viewport. */
    @Test void calibratesBlankPageBeforeSingleApplicationVisit() throws Exception {
        var executable = EdgeLauncher.findBrowser();
        Assumptions.assumeTrue(executable.isPresent(), "Chromium browser is required");
        var visits = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/application", exchange -> {
            visits.incrementAndGet();
            byte[] body = "<!doctype html><title>viewport-ready</title>".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            // Удаляется только кэш измерений стенда, не пользовательские настройки или профиль.
            var cache = EdgeLauncher.class.getDeclaredField("FRAME_INSETS");
            cache.setAccessible(true);
            ((java.util.Map<?, ?>) cache.get(null)).clear();
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/application";
            try (var browser = EdgeLauncher.startWithViewport(executable.orElseThrow(), root.resolve("profile"),
                    1200, 800, url, Duration.ofSeconds(20));
                 var cdp = CdpClient.connectToFirstPage(browser.port(), Duration.ofSeconds(10))) {
                cdp.waitFor("document.readyState === 'complete' && document.title === 'viewport-ready'", Duration.ofSeconds(10));
                assertEquals(1200L, cdp.evaluate("innerWidth"));
                assertEquals(800L, cdp.evaluate("innerHeight"));
                assertEquals(1, visits.get(), "Viewport correction must not abandon an already started application tab");
            }
        } finally { server.stop(0); }
        assertFalse(java.nio.file.Files.exists(root.resolve("profile")));
    }
}
