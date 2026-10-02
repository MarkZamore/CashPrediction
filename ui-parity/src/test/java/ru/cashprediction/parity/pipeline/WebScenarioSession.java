package ru.cashprediction.parity.pipeline;

import java.net.URI;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.parity.browser.*;
import ru.cashprediction.parity.driver.*;
import ru.cashprediction.parity.launch.LaunchedClient;

/** Связывает локальный сервер с настоящей страницей Edge через JDK CDP, без чтения моделей сервера. */
public final class WebScenarioSession implements AutoCloseable {
    private final BrowserSession browser;
    private final CdpClient cdp;
    private final CdpTestApi api;
    private final TestApiBridge bridge;

    private WebScenarioSession(BrowserSession browser, CdpClient cdp, CdpTestApi api, TestApiBridge bridge) {
        this.browser = browser; this.cdp = cdp; this.api = api; this.bridge = bridge;
    }

    /** Дожидается явного адреса сервера и доступного только в test-api порта страницы. */
    public static WebScenarioSession attach(LaunchedClient server, Path profile, Duration timeout) throws Exception {
        server.waitUntil(() -> address(server.stdout()).isPresent(), timeout, "PARITY_URL loopback handshake");
        URI url = address(server.stdout()).orElseThrow();
        // Проверяем origin и токен до передачи адреса браузеру.
        var sender = TestApiBridge.http(url);
        Path executable = EdgeLauncher.findBrowser().orElseThrow(() ->
                new IllegalStateException("Browser absent: " + EdgeLauncher.searchedLocations()));
        BrowserSession browser = EdgeLauncher.startWithViewport(executable, profile, 1200, 800, url.toString(), timeout);
        CdpClient cdp = null;
        try {
            cdp = CdpClient.connectToFirstPage(browser.port(), timeout);
            cdp.waitFor("document.readyState === 'complete' && !!window.cpParityTestApi", timeout);
            var api = new CdpTestApi(cdp);
            var driver = new UiTestDriver(ClientKind.WEB, api);
            return new WebScenarioSession(browser, cdp, api, new TestApiBridge(driver, sender));
        } catch (Exception e) {
            if (cdp != null) cdp.close();
            browser.close();
            throw e;
        }
    }

    /** Разбирает единственный явный handshake, не выбирая случайную ссылку из журнала. */
    static Optional<URI> address(Path stdout) {
        try {
            String text = Files.readString(stdout);
            int end = text.lastIndexOf('\n');
            if (end < 0) return Optional.empty();
            List<String> lines = text.substring(0, end).lines().filter(s -> s.startsWith("PARITY_URL ")).toList();
            if (lines.isEmpty()) return Optional.empty();
            if (lines.size() != 1) throw new IllegalStateException("Duplicate PARITY_URL handshake");
            URI uri = URI.create(lines.getFirst().substring("PARITY_URL ".length()).strip());
            TestApiBridge.http(uri);
            return Optional.of(uri);
        } catch (java.io.IOException e) { return Optional.empty(); }
    }

    /** Исполняет следующий эффект в реальном DOM, сохраняя строгий порядок test.step и ответов. */
    public boolean pump() throws Exception {
        Map<String, Object> step = api.takeStep();
        if (step == null) return false;
        bridge.accept(step);
        return true;
    }

    /** Закрывает соединение, дерево Edge и его собственный профиль. */
    @Override public void close() {
        try { cdp.close(); }
        finally { browser.close(); }
    }
}
