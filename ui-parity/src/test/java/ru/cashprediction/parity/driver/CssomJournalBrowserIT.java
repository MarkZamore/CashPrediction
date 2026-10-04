package ru.cashprediction.parity.driver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.parity.browser.CdpClient;
import ru.cashprediction.parity.browser.EdgeLauncher;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверяет frozen CSSOM-журнал на настоящей localhost-странице Edge.
 * Это отдельная проверка native CSSOM/событий, а не whole-app, PNG или S5 signoff.
 * Шрифт намеренно невалиден: проверяется реальная HTTP-загрузка и native отказ декодирования,
 * а не успешная растеризация текста. Условия media изменяются через CDP viewport.
 */
class CssomJournalBrowserIT {
    @TempDir Path temporary;

    /** Реальный CSSOM сохраняет ABA, native события и чужую замену после dispose, не заявляя полноту. */
    @Test void realCssomAbaAndEnvironmentStayFailClosed() throws Exception {
        Path root = Path.of(System.getProperty("parity.reactor.root", "..")).toAbsolutePath().normalize();
        AtomicInteger moduleRequests = new AtomicInteger(), fontRequests = new AtomicInteger();
        Map<String, byte[]> scripts = Map.of(
                "/app/paint-cssom-journal.js", Files.readAllBytes(root.resolve(
                        "web/src/main/resources/web/app/paint-cssom-journal.js")),
                "/probe.js", probe().getBytes(StandardCharsets.UTF_8));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Обработчик обслуживает только собственную страницу, frozen модуль и bounded font fixture.
        server.createContext("/", exchange -> serve(exchange, scripts, moduleRequests, fontRequests));
        server.start();
        try {
            Path edge = EdgeLauncher.findBrowser().orElseThrow();
            assertEquals("msedge.exe", edge.getFileName().toString().toLowerCase(Locale.ROOT),
                    "This test requires actual Microsoft Edge, not the Chrome fallback");
            try (var browser = EdgeLauncher.startWithViewport(edge, temporary.resolve("edge-cssom"),
                    1200, 800, "http://127.0.0.1:" + server.getAddress().getPort() + "/", Duration.ofSeconds(15));
                 var cdp = CdpClient.connectToFirstPage(browser.port(), Duration.ofSeconds(10))) {
                cdp.waitFor("window.probeReady === true", Duration.ofSeconds(10));
                Map<?, ?> initial = result(cdp.evaluate("window.startJournalProbe()", Duration.ofSeconds(15)));
                assertEquals(Boolean.FALSE, initial.get("complete"));
                assertEquals(Boolean.TRUE, initial.get("styleAba"));
                assertEquals(Boolean.TRUE, initial.get("ruleAba"));
                assertEquals(Boolean.TRUE, initial.get("setterAba"));
                assertEquals(Boolean.TRUE, initial.get("subscriberIsolated"));
                List<?> unsupported = (List<?>) initial.get("unsupported");
                for (String property : List.of("escaped-native-references", "named-css-setters", "external-renderer-timing"))
                    assertTrue(unsupported.contains(property), property);

                if (Boolean.TRUE.equals(initial.get("fontsSupported"))) {
                    assertEquals(Boolean.TRUE, initial.get("fontRejected"));
                    assertEquals(Boolean.TRUE, initial.get("trustedFontLoading"));
                    assertEquals(Boolean.TRUE, initial.get("trustedFontError"));
                    assertEquals(Boolean.TRUE, initial.get("journalFontEvents"));
                    assertEquals(1, fontRequests.get(), "A real local font response must reach the decoder");
                } else assertEquals(0, fontRequests.get());

                if (Boolean.TRUE.equals(initial.get("mediaSupported"))) {
                    assertEquals(Boolean.TRUE, initial.get("mediaInitiallyMatches"));
                    cdp.setViewport(1000, 800, Duration.ofSeconds(10));
                    cdp.waitFor("innerWidth === 1000 && window.mediaProbeState.events.length === 1", Duration.ofSeconds(10));
                    cdp.setViewport(1200, 800, Duration.ofSeconds(10));
                    cdp.waitFor("innerWidth === 1200 && window.mediaProbeState.events.length === 2", Duration.ofSeconds(10));
                }

                Map<?, ?> finished = result(cdp.evaluate("window.finishJournalProbe()", Duration.ofSeconds(10)));
                assertEquals(Boolean.FALSE, finished.get("complete"));
                assertEquals(Boolean.TRUE, finished.get("overrideReported"));
                assertEquals(Boolean.TRUE, finished.get("overridePreserved"));
                assertEquals(Boolean.TRUE, finished.get("otherDescriptorsRestored"));
                assertEquals(Boolean.TRUE, finished.get("noNotificationsAfterDispose"));
                assertEquals(Boolean.TRUE, finished.get("newJournalAfterDispose"));
                if (Boolean.TRUE.equals(initial.get("mediaSupported"))) {
                    assertEquals(List.of(Boolean.FALSE, Boolean.TRUE), finished.get("mediaMatches"));
                    assertEquals(Boolean.TRUE, finished.get("trustedMediaEvents"));
                    assertEquals(Boolean.TRUE, finished.get("journalMediaEvents"));
                    assertEquals(Boolean.TRUE, finished.get("viewportEvents"));
                    assertEquals(Boolean.TRUE, finished.get("mediaRevisionAdvanced"));
                }
                assertEquals(1, moduleRequests.get(), "Serve the actual frozen journal without a test reimplementation");
            }
        } finally { server.stop(0); }
    }

    /** Проверяет результат Runtime.evaluate перед чтением его типизированных полей. */
    private static Map<?, ?> result(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<?, ?>) value;
    }

    /** Отдаёт ограниченный fixture с CSP и намеренно невалидным настоящим HTTP-ответом шрифта. */
    private static void serve(HttpExchange exchange, Map<String, byte[]> scripts,
                              AtomicInteger moduleRequests, AtomicInteger fontRequests) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            byte[] bytes = scripts.get(path); String type = "text/javascript; charset=utf-8";
            if (path.equals("/")) {
                bytes = "<!doctype html><html><head></head><body><script type=module src=/probe.js></script></body></html>"
                        .getBytes(StandardCharsets.UTF_8);
                type = "text/html; charset=utf-8";
                exchange.getResponseHeaders().set("Content-Security-Policy",
                        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                                + "font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'");
            } else if (path.equals("/probe-font.woff2")) {
                fontRequests.incrementAndGet(); bytes = "invalid bounded WOFF2 fixture".getBytes(StandardCharsets.US_ASCII);
                type = "font/woff2";
            } else if (path.equals("/app/paint-cssom-journal.js")) moduleRequests.incrementAndGet();
            if (bytes == null) { exchange.sendResponseHeaders(404, -1); return; }
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        } finally { exchange.close(); }
    }

    /** Возвращает самостоятельную runtime-пробу без модификации frozen JS и без синтетических media/font событий. */
    private static String probe() {
        return """
                import {createCssomJournal} from '/app/paint-cssom-journal.js';
                let journal = null, sheet = null, fixture = null, style = null, face = null, media = null;
                let originalSet = null, originalRemove = null, originalCssText = null, override = null;
                const reasons = [], fontEvents = [], mediaEvents = [], viewportEvents = [];
                let unsubscribe = null, mediaRevision = 0;
                const query = '(min-width: 1100px)';

                /** Проверяет native runtime-условие с диагностикой для CDP. */
                function check(value, message) { if (!value) throw new Error(message); }
                /** Сравнивает полные дескрипторы после освобождения собственных обёрток. */
                function same(a, b) {
                  return !!a && !!b && a.value === b.value && a.get === b.get && a.set === b.set
                    && a.writable === b.writable && a.configurable === b.configurable && a.enumerable === b.enumerable;
                }
                /** Записывает только реально доставленное браузером font-событие и его происхождение. */
                function fontEvent(event) { fontEvents.push({type: event.type, trusted: event.isTrusted}); }
                /** Записывает фактическое изменение media-query при внешнем изменении viewport. */
                function mediaEvent(event) { mediaEvents.push({matches: event.matches, trusted: event.isTrusted}); }
                /** Записывает native resize, не вызывая dispatchEvent. */
                function viewportEvent(event) { viewportEvents.push({width: innerWidth, trusted: event.isTrusted}); }
                /** Проверяет наличие диагностической причины, не подменяя complete разрешающим флагом. */
                function hasIssue(property) {
                  return journal.unsupported().some(/** Находит отдельную диагностику непокрытого пути. */ issue => issue.property === property);
                }
                /** Освобождает все собственные ресурсы пробы, сохраняя более поздние сторонние изменения. */
                function cleanup() {
                  unsubscribe?.(); journal?.dispose();
                  if (override && Object.getOwnPropertyDescriptor(CSSStyleDeclaration.prototype, 'setProperty')?.value === override)
                    Object.defineProperty(CSSStyleDeclaration.prototype, 'setProperty', originalSet);
                  if (face) document.fonts.delete(face);
                  if (document.fonts) for (const type of ['loading', 'loadingdone', 'loadingerror']) document.fonts.removeEventListener(type, fontEvent);
                  media?.removeEventListener('change', mediaEvent); window.removeEventListener('resize', viewportEvent);
                  fixture?.remove(); style?.remove();
                }

                window.startJournalProbe = /** Создаёт настоящий CSSOM и проверяет синхронные ABA до внешнего изменения viewport. */ async () => {
                  try {
                    await document.fonts?.ready;
                    style = document.createElement('style'); style.textContent = '@media ' + query + ' { .cssom-journal-fixture { padding: 0; } }';
                    fixture = document.createElement('div'); fixture.className = 'cssom-journal-fixture';
                    document.head.append(style); document.body.append(fixture); sheet = style.sheet;
                    originalSet = Object.getOwnPropertyDescriptor(CSSStyleDeclaration.prototype, 'setProperty');
                    originalRemove = Object.getOwnPropertyDescriptor(CSSStyleDeclaration.prototype, 'removeProperty');
                    originalCssText = Object.getOwnPropertyDescriptor(CSSStyleDeclaration.prototype, 'cssText');
                    journal = createCssomJournal();
                    check(journal.complete === false, 'Incomplete journal must not certify this page');
                    unsubscribe = journal.subscribe(/** Сохраняет реальные уведомления production-журнала. */ reason => reasons.push(reason));
                    const declaration = sheet.cssRules[0].cssRules[0].style;
                    let before = journal.revision(), beforeReasons = reasons.length;
                    const initialText = declaration.cssText;
                    declaration.setProperty('--cssom-probe', 'first', 'important');
                    check(declaration.getPropertyValue('--cssom-probe') === 'first' && declaration.getPropertyPriority('--cssom-probe') === 'important', 'Native arguments/priority');
                    check(declaration.removeProperty('--cssom-probe') === 'first', 'Native remove result');
                    const styleAba = journal.revision() >= before + 2 && declaration.cssText === initialText
                      && reasons.slice(beforeReasons).includes('method:CSSStyleDeclaration.setProperty')
                      && reasons.slice(beforeReasons).includes('method:CSSStyleDeclaration.removeProperty');
                    check(styleAba, 'Declaration ABA was lost');

                    before = journal.revision(); beforeReasons = reasons.length;
                    const originalRules = sheet.cssRules.length, initialRule = sheet.cssRules[0];
                    const index = sheet.insertRule('.cssom-journal-fixture { margin: 1px; }', originalRules);
                    check(index === originalRules, 'Native insertRule return value'); sheet.deleteRule(index);
                    const ruleAba = journal.revision() >= before + 2 && sheet.cssRules.length === originalRules && sheet.cssRules[0] === initialRule
                      && reasons.slice(beforeReasons).includes('method:CSSStyleSheet.insertRule') && reasons.slice(beforeReasons).includes('method:CSSStyleSheet.deleteRule');
                    check(ruleAba, 'Rule ABA was lost');

                    before = journal.revision(); beforeReasons = reasons.length;
                    declaration.cssText = 'padding: 1px;'; declaration.cssText = initialText;
                    const setterAba = journal.revision() >= before + 2 && declaration.cssText === initialText
                      && reasons.slice(beforeReasons).filter(/** Считает уведомления конкретного native setter. */ reason => reason === 'setter:CSSStyleDeclaration.cssText').length >= 2;
                    check(setterAba, 'Native cssText setter ABA was lost');
                    const stopFaulty = journal.subscribe(/** Имитирует ошибочного подписчика, который не должен изменить native операцию. */ () => { throw new Error('subscriber fixture'); });
                    let subscriberIsolated = false;
                    try { declaration.setProperty('--cssom-probe', 'second'); subscriberIsolated = declaration.removeProperty('--cssom-probe') === 'second'; }
                    finally { stopFaulty(); }
                    check(subscriberIsolated, 'Subscriber changed native behavior');

                    const fontsSupported = typeof FontFace === 'function' && !!document.fonts?.add && !!document.fonts?.load;
                    let fontRejected = false;
                    if (fontsSupported) {
                      for (const type of ['loading', 'loadingdone', 'loadingerror']) document.fonts.addEventListener(type, fontEvent);
                      face = new FontFace('CssomJournalFixture', 'url(/probe-font.woff2)'); document.fonts.add(face);
                      try { await document.fonts.load('16px CssomJournalFixture', 'X'); } catch { fontRejected = face.status === 'error'; }
                      await document.fonts.ready;
                      await new Promise(/** Ждёт доставки native font events на следующем кадре. */ resolve => requestAnimationFrame(resolve));
                    }
                    const mediaSupported = typeof matchMedia === 'function';
                    if (mediaSupported) { media = matchMedia(query); media.addEventListener('change', mediaEvent); }
                    window.addEventListener('resize', viewportEvent);
                    window.mediaProbeState = {events: mediaEvents}; mediaRevision = journal.revision();
                    return {complete: journal.complete, styleAba, ruleAba, setterAba, subscriberIsolated, fontsSupported, fontRejected,
                      trustedFontLoading: fontEvents.some(/** Проверяет действительно native начало загрузки шрифта. */ event => event.type === 'loading' && event.trusted),
                      trustedFontError: fontEvents.some(/** Проверяет действительно native отказ декодирования HTTP-шрифта. */ event => event.type === 'loadingerror' && event.trusted),
                      journalFontEvents: reasons.includes('event:fonts:loading') && reasons.includes('event:fonts:loadingerror'),
                      mediaSupported, mediaInitiallyMatches: media?.matches ?? null,
                      unsupported: journal.unsupported().map(/** Передаёт Java конкретные причины неполноты. */ issue => issue.property)};
                  } catch (error) { cleanup(); throw error; }
                };

                window.finishJournalProbe = /** Проверяет настоящий media/viewport ABA и уважение сторонней замены при dispose. */ () => {
                  try {
                    const complete = journal.complete, mediaRevisionAdvanced = journal.revision() > mediaRevision;
                    const mediaMatches = mediaEvents.map(/** Сохраняет последовательность реальных media-состояний. */ event => event.matches);
                    const trustedMediaEvents = mediaEvents.length === 2 && mediaEvents.every(/** Проверяет происхождение каждого media-события. */ event => event.trusted);
                    const journalMediaEvents = reasons.filter(/** Считает production-уведомления конкретного media-query. */ reason => reason === 'event:media:' + query).length >= 2;
                    const actualViewportEvents = viewportEvents.some(/** Проверяет первый измеренный внешний resize. */ event => event.width === 1000 && event.trusted)
                      && viewportEvents.some(/** Проверяет возврат внешнего viewport к исходному размеру. */ event => event.width === 1200 && event.trusted);
                    override = /** Представляет стороннюю обёртку, установленную после production-журнала. */ function (...args) {
                      return Reflect.apply(originalSet.value, this, args);
                    };
                    Object.defineProperty(CSSStyleDeclaration.prototype, 'setProperty', {...originalSet, value: override});
                    const overrideReported = hasIssue('override:CSSStyleDeclaration.setProperty');
                    journal.dispose(); journal.dispose();
                    const overridePreserved = CSSStyleDeclaration.prototype.setProperty === override;
                    const otherDescriptorsRestored = same(Object.getOwnPropertyDescriptor(CSSStyleDeclaration.prototype, 'removeProperty'), originalRemove)
                      && same(Object.getOwnPropertyDescriptor(CSSStyleDeclaration.prototype, 'cssText'), originalCssText);
                    const before = reasons.length;
                    declarationAfterDispose();
                    const noNotificationsAfterDispose = reasons.length === before;
                    const fresh = createCssomJournal(); let newJournalAfterDispose;
                    try { newJournalAfterDispose = fresh.complete === false; } finally { fresh.dispose(); }
                    return {complete, mediaMatches, trustedMediaEvents, journalMediaEvents, viewportEvents: actualViewportEvents,
                      mediaRevisionAdvanced, overrideReported, overridePreserved, otherDescriptorsRestored, noNotificationsAfterDispose, newJournalAfterDispose};
                  } finally { cleanup(); }
                };
                /** Вызывает настоящую декларацию после dispose для обнаружения оставшихся активных подписок. */
                function declarationAfterDispose() { fixture.style.setProperty('--after-dispose', 'value'); fixture.style.removeProperty('--after-dispose'); }
                window.probeReady = true;
                """;
    }
}
