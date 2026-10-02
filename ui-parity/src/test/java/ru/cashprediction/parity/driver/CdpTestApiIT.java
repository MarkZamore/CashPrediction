package ru.cashprediction.parity.driver;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.parity.browser.*;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет мост на настоящем Edge и синтетической странице, не заявляя паритет приложения. */
class CdpTestApiIT {
    @TempDir Path root;

    /** Выполнение меняет настоящий input; отсутствие API, отказ и зависший Promise остаются ошибками. */
    @Test void realDomAndStrictPromiseDeadline() throws Exception {
        Path executable = EdgeLauncher.findBrowser().orElseThrow();
        String html = """
                <!doctype html><html><body><input id="actual"><script>
                const steps = [{type:'test.step', n:1, command:'sample'}];
                window.cpParityTestApi = {
                  supported:['Sample'],
                  takeStep:() => ({ok:true, value:steps.shift() ?? null}),
                  execute:async command => {
                    if (command.kind !== 'Sample') return {ok:false};
                    const input=document.querySelector('input');
                    input.value='through-widget'; input.dispatchEvent(new Event('input',{bubbles:true}));
                    return {ok:true};
                  },
                  awaitIdle:async () => ({ok:true}),
                  dump:async () => ({ok:false, message:'no application dump on synthetic page'})
                };
                </script></body></html>
                """;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try (var browser = EdgeLauncher.startWithViewport(executable, root.resolve("edge"), 1200, 800,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", Duration.ofSeconds(15));
             var cdp = CdpClient.connectToFirstPage(browser.port(), Duration.ofSeconds(10))) {
            cdp.waitFor("!!window.cpParityTestApi", Duration.ofSeconds(5));
            var api = new CdpTestApi(cdp);
            assertEquals("sample", api.takeStep().get("command"));
            assertNull(api.takeStep());
            api.execute(new SelfTestCommand.Sample());
            assertEquals("through-widget", cdp.evaluate("document.querySelector('input').value"));
            assertThrows(IllegalStateException.class, () -> api.dump("test"));
            cdp.evaluate("window.cpParityTestApi.awaitIdle = () => new Promise(() => {})");
            long started = System.nanoTime();
            assertThrows(CdpClient.CdpException.class, () -> api.awaitIdle(Duration.ofMillis(150)));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
            assertEquals("through-widget", cdp.evaluate("document.querySelector('input').value"));
            cdp.evaluate("delete window.cpParityTestApi");
            assertThrows(CdpClient.CdpException.class, () -> new CdpTestApi(cdp));
        } finally { server.stop(0); }
    }
}
