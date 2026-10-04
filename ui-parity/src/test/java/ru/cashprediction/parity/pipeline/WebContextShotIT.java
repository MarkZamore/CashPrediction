package ru.cashprediction.parity.pipeline;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.parity.browser.BrowserSession;
import ru.cashprediction.parity.browser.CdpClient;
import ru.cashprediction.parity.browser.EdgeLauncher;
import ru.cashprediction.parity.driver.CdpTestApi;
import ru.cashprediction.parity.driver.TestApiBridge;
import ru.cashprediction.parity.driver.UiTestDriver;
import ru.cashprediction.parity.launch.ClientLauncher;
import ru.cashprediction.parity.launch.ClientTarget;
import ru.cashprediction.parity.launch.LaunchRequest;
import ru.cashprediction.parity.launch.ReactorLayout;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Регрессия согласованности настоящего DOM-дампа и PNG контекстного меню Web.
 *
 * <p>Включается только свойством {@code parity.web.contextShot=true}. При включении отсутствие
 * браузера, собранных production JAR или результата сценария считается ошибкой, а не пропуском.
 * Использует внешний CPS и штатный мост test.step; модели и синтетические страницы не подменяют UI.
 * Контрольный PNG со скрытым меню сравнивается в измеренной DOM-области без заданных цветов и координат.
 * Журналы, исходный сценарий, замороженные JAR, PNG и raw JSON остаются в target/parity для аудита.</p>
 */
@EnabledIfSystemProperty(named = "parity.web.contextShot", matches = "true")
class WebContextShotIT {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(25);
    private static final Duration RUN_TIMEOUT = Duration.ofSeconds(90);
    private static final String NAME = "web-context-shot";
    // Esc закрывает мастер первого запуска; следующие три команды воспроизводят исходный дефект.
    private static final String SCRIPT = "key Esc\nsample\ncontext row:start\nshot visible\n";

    /** Требует непустое меню в raw JSON, сохранённый фокус/DOM и видимое меню в штатном PNG. */
    @Test
    @Timeout(180)
    void contextMenuSurvivesDumpAndIsPaintedInSavedShot() throws Exception {
        var layout = ReactorLayout.fromSystemProperties();
        Path run = Files.createDirectories(layout.parityRoot().resolve(NAME + "-" + UUID.randomUUID()));
        Path script = run.resolve(NAME + ".cps");
        Files.writeString(script, SCRIPT);
        String node = RegistryNodeCleaner.newSelftestNode();
        var sessions = RegistryNodeCleaner.snapshotRealSessionNodes();
        Path profile = Files.createTempDirectory("cp-context-shot-");
        var request = new LaunchRequest("web", NAME, run.resolve("home"), node,
                LaunchRequest.PARITY_TODAY, script.toString(), run.resolve("out"), List.of(), List.of());
        Path log = request.selftestOut().resolve("selftest.log");
        try {
            var target = ModuleSnapshot.capture(ClientTarget.web(layout), run.resolve("module-snapshot"), layout.root());
            try (var server = ClientLauncher.launch(target, request)) {
                server.waitUntil(() -> WebScenarioSession.address(server.stdout()).isPresent(),
                        START_TIMEOUT, "PARITY_URL context-shot handshake");
                URI url = WebScenarioSession.address(server.stdout()).orElseThrow();
                var sender = TestApiBridge.http(url);
                Path executable = EdgeLauncher.findBrowser().orElseThrow(() -> new IllegalStateException(
                        "Real browser required: " + EdgeLauncher.searchedLocations()));
                try (BrowserSession browser = EdgeLauncher.startWithViewport(executable, profile,
                        1200, 800, url.toString(), START_TIMEOUT);
                     CdpClient cdp = CdpClient.connectToFirstPage(browser.port(), START_TIMEOUT)) {
                    cdp.waitFor("document.readyState === 'complete' && !!window.cpParityTestApi", START_TIMEOUT);
                    assertEquals(Boolean.TRUE, cdp.evaluate(
                            "innerWidth === 1200 && innerHeight === 800 && devicePixelRatio === 1"));
                    var api = new CdpTestApi(cdp);
                    var probe = new ShotProbe(api, cdp);
                    var bridge = new TestApiBridge(new UiTestDriver(ClientKind.WEB, probe), sender);
                    long deadline = System.nanoTime() + RUN_TIMEOUT.toNanos();
                    while (!done(log)) {
                        assertTrue(server.process().isAlive(), () -> "Server exited: " + server.stderrTail());
                        assertTrue(System.nanoTime() < deadline, "No SELFTEST DONE within context-shot deadline");
                        Map<String, Object> effect = api.takeStep();
                        if (effect == null) Thread.sleep(20);
                        else bridge.accept(effect);
                    }
                    assertJournal(log);
                    assertEquals(1, probe.dumps, "Exactly one actual Shot dump must be observed");
                    assertNotNull(probe.png, "Shot must use the actual CDP screenshot path");
                    Path artifacts = request.selftestOut().resolve(NAME);
                    UiDump raw = DumpTrees.read(Files.readString(artifacts.resolve("visible.raw.json")));
                    assertEquals("web", raw.client());
                    assertEquals(NAME, raw.scenario());
                    assertEquals("visible", raw.step());
                    assertEquals(probe.dump.contextMenus(), raw.contextMenus(), "Persisted raw menu must match DOM dump");
                    assertArrayEquals(probe.png, Files.readAllBytes(artifacts.resolve("visible.png")),
                            "Persisted PNG must be the screenshot taken after the actual dump");
                    probe.requireMenuPainted(run);
                }
            }
        } finally {
            // BrowserSession удаляет профиль и требует завершения всего дерева браузера.
            try {
                if (Files.isDirectory(profile)) Files.delete(profile);
            } finally {
                RegistryNodeCleaner.delete(node);
                assertFalse(RegistryNodeCleaner.exists(node), "Isolated registry node survived");
                assertEquals(sessions, RegistryNodeCleaner.snapshotRealSessionNodes(), "Real session registry changed");
            }
        }
    }

    /** Читает только опубликованный журнал; строгая проверка всех строк выполняется после DONE. */
    private static boolean done(Path log) throws IOException {
        return Files.isRegularFile(log) && Files.readString(log).lines().anyMatch("SELFTEST DONE"::equals);
    }

    /** Не позволяет DONE скрыть FAIL, пропущенную или дублированную команду внешнего сценария. */
    private static void assertJournal(Path log) throws IOException {
        var expected = SelfTestScript.parse(NAME, SCRIPT).lines();
        var actual = Files.readString(log).lines().toList();
        assertEquals(expected.size() + 1, actual.size());
        for (int i = 0; i < expected.size(); i++) {
            var line = expected.get(i);
            assertEquals("SELFTEST " + line.number() + " OK " + line.text(), actual.get(i));
        }
        assertEquals("SELFTEST DONE", actual.getLast());
    }

    /** Наблюдает штатный Shot, не заменяя операции, дамп или снимок рендерера. */
    private static final class ShotProbe implements UiTestDriver.TestApi {
        private final CdpTestApi delegate;
        private final CdpClient cdp;
        private Map<String, Object> rect;
        private UiDump dump;
        private byte[] png;
        private int dumps;

        /** Связывает наблюдатель с тем же production DOM, который использует штатный мост. */
        private ShotProbe(CdpTestApi delegate, CdpClient cdp) { this.delegate = delegate; this.cdp = cdp; }

        /** Возвращает фактически объявленные возможности страницы. */
        @Override public Set<Class<? extends SelfTestCommand>> supported() { return delegate.supported(); }

        /** Передаёт исходную команду настоящим виджетам без изменений. */
        @Override public void execute(SelfTestCommand command) { delegate.execute(command); }

        /** Ждёт штатную очередь событий страницы. */
        @Override public void awaitIdle(Duration timeout) { delegate.awaitIdle(timeout); }

        /** Сохраняет ссылки на меню и фокус до чтения дампа и проверяет их сразу после чтения. */
        @Override public UiDump dump(String step) {
            assertEquals("visible", step);
            rect = Json.asObject(cdp.evaluate("""
                    (() => {
                      const menus = [...document.querySelectorAll('.context-menu')]
                        .filter(n => !n.hidden && n.getClientRects().length && getComputedStyle(n).visibility !== 'hidden');
                      if (menus.length !== 1) throw new Error('Expected one visible context menu');
                      const menu = menus[0], focus = document.activeElement, r = menu.getBoundingClientRect();
                      if (menu.dataset.target !== 'row:start' || !menu.contains(focus))
                        throw new Error('Expected focused row:start menu');
                      const f = focus.getBoundingClientRect();
                      if (!focus.contains(document.elementFromPoint(f.x + f.width / 2, f.y + f.height / 2)))
                        throw new Error('Focused menu item is occluded');
                      window.__contextShotProof = {menu, focus, nodes: [...menu.querySelectorAll('*')]};
                      return {x:r.x, y:r.y, width:r.width, height:r.height};
                    })()
                    """), "actual menu bounds");
            dump = delegate.dump(step);
            dumps++;
            assertEquals(1, dump.contextMenus().size(), "Raw dump must retain the actual context menu");
            assertEquals("row:start", dump.contextMenus().getFirst().target());
            assertFalse(dump.contextMenus().getFirst().items().isEmpty(), "Menu items must come from actual DOM");
            requireIdentity();
            return dump;
        }

        /** Снимает штатный PNG только пока те же меню и фокус ещё видимы. */
        @Override public byte[] screenshot(String step) throws IOException {
            requireIdentity();
            png = delegate.screenshot(step);
            requireIdentity();
            return png;
        }

        /** Не принимает пересозданное, скрытое, удалённое меню или смену фокуса после dump. */
        private void requireIdentity() {
            assertEquals(Boolean.TRUE, cdp.evaluate("""
                    (() => {
                      const p = window.__contextShotProof;
                      if (!p || !p.menu.isConnected || p.menu.hidden || document.activeElement !== p.focus
                        || getComputedStyle(p.menu).visibility === 'hidden') return false;
                      const nodes = [...p.menu.querySelectorAll('*')], r = p.menu.getBoundingClientRect();
                      return r.width > 0 && r.height > 0 && nodes.length === p.nodes.length
                        && nodes.every((n,i) => n === p.nodes[i]);
                    })()
                    """), "Dump/screenshot must preserve original menu nodes and focus");
        }

        /** Доказывает вклад меню в PNG сравнением с контрольным скрытием в измеренной области. */
        private void requireMenuPainted(Path run) throws IOException {
            requireIdentity();
            BufferedImage actual = image(png);
            byte[] hidden;
            cdp.evaluate("window.__contextShotProof.visibility = window.__contextShotProof.menu.style.visibility;"
                    + " window.__contextShotProof.menu.style.visibility = 'hidden'; true");
            try { hidden = cdp.captureScreenshot(); }
            finally {
                // Контрольное скрытие вправе сбросить фокус; это не часть проверяемого штатного Shot.
                cdp.evaluate("window.__contextShotProof.menu.style.visibility = window.__contextShotProof.visibility;"
                        + " window.__contextShotProof.focus.focus({preventScroll:true}); true");
            }
            requireIdentity();
            Files.write(run.resolve("control-menu-hidden.png"), hidden);
            BufferedImage control = image(hidden);
            BufferedImage restored = image(cdp.captureScreenshot());
            assertEquals(actual.getWidth(), control.getWidth());
            assertEquals(actual.getHeight(), control.getHeight());
            assertEquals(actual.getWidth(), restored.getWidth());
            assertEquals(actual.getHeight(), restored.getHeight());
            int left = (int) Math.floor(number("x")), top = (int) Math.floor(number("y"));
            int right = (int) Math.ceil(number("x") + number("width"));
            int bottom = (int) Math.ceil(number("y") + number("height"));
            assertTrue(left >= 0 && top >= 0 && right <= actual.getWidth() && bottom <= actual.getHeight(),
                    "Actual context menu must lie inside screenshot");
            long changed = 0;
            for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
                assertEquals(actual.getRGB(x, y), restored.getRGB(x, y),
                        "Restored menu pixels must match the original Shot, excluding incidental repaint differences");
                if (actual.getRGB(x, y) != control.getRGB(x, y)) changed++;
            }
            assertTrue(changed > 0, "Saved PNG must visibly differ from the same DOM without the context menu");
            Files.writeString(run.resolve("paint-proof.json"),
                    ru.cashprediction.core.ui.json.UiJson.write(Map.of("bounds", rect, "changedPixels", changed)));
            cdp.evaluate("delete window.__contextShotProof; true");
        }

        /** Читает измерение из CDP без округления или подстановки токена. */
        private double number(String name) { return ((Number) rect.get(name)).doubleValue(); }

        /** Требует декодируемый настоящий PNG, а не только непустой массив байтов. */
        private static BufferedImage image(byte[] bytes) throws IOException {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            assertNotNull(image, "Actual browser screenshot must decode as PNG");
            return image;
        }
    }
}
