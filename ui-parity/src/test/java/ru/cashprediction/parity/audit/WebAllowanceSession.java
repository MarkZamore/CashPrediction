package ru.cashprediction.parity.audit;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.parity.browser.*;
import ru.cashprediction.parity.check.census.ClientJarSnapshot;
import ru.cashprediction.parity.driver.CdpTestApi;
import ru.cashprediction.parity.driver.TestApiBridge;
import ru.cashprediction.parity.launch.*;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;
import static org.junit.jupiter.api.Assertions.*;

/** Изолированная настоящая Web-страница: действия через test API, post-exit экран только из DOM. */
final class WebAllowanceSession implements AutoCloseable {
    static final Duration TIMEOUT = Duration.ofSeconds(20);
    final Path output;
    final LaunchedClient server;
    final BrowserSession browser;
    final CdpClient cdp;
    final CdpTestApi api;
    private final String node;
    private final ru.cashprediction.parity.registry.RegistryTreeSnapshot realSessions;

    private WebAllowanceSession(Path output, LaunchedClient server, BrowserSession browser, CdpClient cdp,
                                String node, ru.cashprediction.parity.registry.RegistryTreeSnapshot realSessions) {
        this.output = output; this.server = server; this.browser = browser; this.cdp = cdp;
        this.node = node; this.realSessions = realSessions; this.api = new CdpTestApi(cdp);
    }

    /** Создаёт отдельные CashMemory, UUID-узел и headless-профиль; при ошибке завершает только их процессы. */
    static WebAllowanceSession open(ReactorLayout layout, String name, int width) throws Exception {
        Files.createDirectories(layout.parityRoot());
        Path output = Files.createTempDirectory(layout.parityRoot(), "allowance-" + name + "-");
        String node = RegistryNodeCleaner.newSelftestNode();
        var sessions = RegistryNodeCleaner.snapshotRealSessionNodes();
        LaunchedClient server = null; BrowserSession browser = null; CdpClient cdp = null;
        try {
            var target = ClientJarSnapshot.copy(ClientTarget.web(layout), output);
            var request = new LaunchRequest("web", "allowance-" + name, output.resolve("home"), node,
                    LaunchRequest.PARITY_TODAY, null, null, "core", List.of(), List.of());
            server = ClientLauncher.launch(target, request);
            final LaunchedClient launched = server;
            server.waitUntil(() -> {
                try { return Files.readString(launched.stdout()).contains("PARITY_URL "); }
                catch (java.io.IOException failure) { return false; }
            }, TIMEOUT, "allowance PARITY_URL");
            URI url = address(Files.readAllLines(server.stdout()));
            browser = EdgeLauncher.startWithViewport(EdgeLauncher.findBrowser().orElseThrow(),
                    output.resolve("chrome-profile"), width, 800, url.toString(), TIMEOUT);
            cdp = CdpClient.connectToFirstPage(browser.port(), TIMEOUT);
            cdp.waitFor("document.readyState==='complete' && !!window.cpParityTestApi", TIMEOUT);
            // test API устанавливается до восстановления окон bootstrap; ждём настоящий мастер свежего сеанса.
            cdp.waitFor("!!document.querySelector('dialog[data-kind=NEW_PLAN_WIZARD][open]')", TIMEOUT);
            var result = new WebAllowanceSession(output, server, browser, cdp, node, sessions);
            Files.writeString(output.resolve("probe.json"), UiJson.write(Map.of("name", name, "width", width,
                    "node", node, "command", server.command(), "observation", "real-web-test-api")));
            return result;
        } catch (Exception | AssertionError failure) {
            if (cdp != null) cdp.close();
            if (browser != null) browser.close();
            if (server != null) server.close();
            RegistryNodeCleaner.delete(node);
            throw new AssertionError("Web allowance startup artifacts: " + output, failure);
        }
    }

    /** Разбирает ровно один безопасный handshake, не отрезая первый символ схемы HTTP. */
    static URI address(List<String> lines) {
        var addresses = lines.stream().filter(line -> line.startsWith("PARITY_URL ")).toList();
        if (addresses.size() != 1) throw new IllegalArgumentException("Missing/duplicate PARITY_URL");
        URI url = URI.create(addresses.getFirst().substring("PARITY_URL ".length()).strip());
        TestApiBridge.http(url);
        return url;
    }

    /** Исполняет одно реальное действие и сохраняет подтверждённую команду. */
    void execute(SelfTestCommand command) throws Exception {
        api.execute(command);
        Files.writeString(output.resolve("actions.jsonl"), UiJson.write(command) + "\n",
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    /** Читает настоящий полный дамп только пока сервер отвечает на запрос телеметрии. */
    UiDump dump(String step) throws Exception {
        UiDump dump = api.dump(step);
        assertEquals("web", dump.client()); assertEquals(step, dump.step());
        Files.writeString(output.resolve(step + ".json"), UiJson.write(dump));
        return dump;
    }

    /** После остановки собирает отдельное наблюдение экрана, не добавляя выдуманные counters или UiDump. */
    @SuppressWarnings("unchecked")
    Map<String, Object> screen(String kind) throws Exception {
        cdp.waitFor("(() => {const s=document.getElementById('screens');return s.open&&!s.hidden&&s.dataset.kind==="
                + UiJson.write(kind) + ";})()", TIMEOUT);
        Object observed = cdp.evaluate("(() => {const s=document.getElementById('screens');return {kind:s.dataset.kind,"
                + "title:s.querySelector('.screen-title').textContent,text:s.querySelector('.screen-text').textContent,"
                + "buttons:[...s.querySelectorAll('button')].map(b=>({id:b.dataset.cpId,text:b.textContent,enabled:!b.disabled})),"
                + "open:s.open,mainInert:document.getElementById('main').inert};})()");
        assertInstanceOf(Map.class, observed);
        Map<String, Object> value = (Map<String, Object>) observed;
        Files.writeString(output.resolve("screen-" + kind + ".json"), UiJson.write(value));
        return value;
    }

    /** Закрывает только собственные подключения/процессы и удаляет ровно собственный selftest-узел. */
    @Override public void close() {
        try { cdp.close(); }
        finally {
            try { browser.close(); }
            finally {
                try { server.close(); }
                finally {
                    RegistryNodeCleaner.delete(node);
                    assertFalse(RegistryNodeCleaner.exists(node));
                    assertEquals(realSessions, RegistryNodeCleaner.snapshotRealSessionNodes());
                }
            }
        }
    }
}
