package ru.cashprediction.parity.e2e.web;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.store.MarkdownSessionStore;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.form.FieldCodec;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.parity.browser.BrowserSession;
import ru.cashprediction.parity.browser.CdpClient;
import ru.cashprediction.parity.browser.EdgeLauncher;
import ru.cashprediction.parity.driver.CdpTestApi;
import ru.cashprediction.parity.driver.TestApiBridge;
import ru.cashprediction.parity.driver.UiTestDriver;
import ru.cashprediction.parity.launch.ClientLauncher;
import ru.cashprediction.parity.launch.ClientTarget;
import ru.cashprediction.parity.launch.LaunchedClient;
import ru.cashprediction.parity.launch.LaunchRequest;
import ru.cashprediction.parity.launch.ReactorLayout;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;
import static org.junit.jupiter.api.Assertions.*;

/** Настоящий перезапуск Web с проверкой живого главного окна, документа браузера и снимка форм. */
@EnabledIfSystemProperty(named = "parity.e2e", matches = "true")
class WebCrashRestoreE2ETest {
    private static final Duration WAIT = Duration.ofSeconds(40);
    private static final String SELECTED_ROW = "r1@2026-09-05";
    // Поиск оставляет прошлую зарплату видимой: выделение одновременно доказывает раскрытие группы.
    private static final String FILTER_TEXT = UiText.get("sample.rule.salary");
    private static final String SCRIPT = "key Esc\nsample\nperiod ALL\n"
            + "menu whatIf.income\nmenu whatIf.expense\nspinner whatIf.extra 5731\n"
            + "filter showExpense=false\nfilter showOneTime=false\nfilter showSkipped=true\n"
            + "filter monthTotals=false\nfilter chartMarkers=false\nfilter chartBars=true\n"
            + "filtertype \"" + FILTER_TEXT + "\"\nrowclick past@group\nselect " + SELECTED_ROW + "\n"
            + "view CHART\nmenu tools.goal\n"
            + "fill last target=\"450731\" extraSaving=\"bad731\"\n"
            + "menu edit.addIncome\nfill last title=\"web-restart-sentinel\" amount=\"bad732\"\n"
            + "signal committed\n";

    /** Сервер убивается без штатного shutdown; исходная вкладка сама проходит новый handshake. */
    @Test
    @Timeout(240)
    void originalDocumentReconnectsAndRestoresTypedWindows() throws Exception {
        var layout = ReactorLayout.fromSystemProperties();
        Path run = Files.createDirectories(layout.parityRoot().resolve("s4-web-restart-" + UUID.randomUUID()));
        Path home = Files.createDirectories(run.resolve("home"));
        Path script = run.resolve("before.cps");
        Files.writeString(script, SCRIPT);
        SelfTestScript.parse("before", SCRIPT);
        String node = RegistryNodeCleaner.newSelftestNode();
        var realBefore = RegistryNodeCleaner.snapshotRealSessionNodes();
        List<LaunchedClient> owned = new ArrayList<>();
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        try {
            var first = ClientLauncher.launch(ClientTarget.web(layout),
                    request(home, node, run.resolve("before-out"), "before", script.toString(), port));
            owned.add(first);
            first.waitUntil(() -> address(first) != null, WAIT, "initial Web handshake");
            URI url = address(first);
            Path executable = EdgeLauncher.findBrowser().orElseThrow(() ->
                    new IllegalStateException("Real browser required: " + EdgeLauncher.searchedLocations()));
            try (BrowserSession browser = EdgeLauncher.startWithViewport(executable,
                    Files.createTempDirectory("cp-s4-web-restart-"), 1200, 800, url.toString(), WAIT);
                 CdpClient cdp = CdpClient.connectToFirstPage(browser.port(), WAIT)) {
                cdp.waitFor("document.readyState === 'complete' && !!window.cpParityTestApi", WAIT);
                String identity = UUID.randomUUID().toString();
                cdp.evaluate("window.__s4OriginalDocument = " + UiJson.write(identity));
                var api = new CdpTestApi(cdp);
                var bridge = new TestApiBridge(new UiTestDriver(ClientKind.WEB, api), TestApiBridge.http(url));
                Path barrier = first.request().selftestOut().resolve("committed");
                long deadline = System.nanoTime() + WAIT.toNanos();
                while (!Files.exists(barrier)) {
                    assertTrue(first.process().isAlive(), first.stderrTail());
                    assertTrue(System.nanoTime() < deadline, "Missing pre-crash barrier");
                    var step = api.takeStep();
                    if (step == null) Thread.sleep(20); else bridge.accept(step);
                }
                assertPrefix(first);
                api.awaitIdle(Duration.ofSeconds(10));
                UiDump before = api.dump("before-crash");
                Files.writeString(run.resolve("before.raw.json"), UiJson.write(before));
                assertEquals(2, before.windows().size(), "Both real forms must be open before death");
                assertSeededMain(before);
                assertSourceWindows(before);
                // Сохраняем ссылки на каркас DOM, чтобы новый handshake не маскировал перезагрузку страницы.
                cdp.evaluate("window.__s4MainNodes = ['main','menuBar','toolbar','summary','table','chart','status']"
                        + ".map(id => document.getElementById(id)); true");
                assertOriginalDom(cdp, identity);
                var store = MarkdownSessionStore.inCashMemory(home.resolve("CashMemory"));
                first.waitUntil(() -> committed(store), WAIT, "invalid text committed in server snapshot");
                SessionSnapshot saved = store.load().orElseThrow();
                assertEquals(2, saved.windows().size());
                assertEquals(SELECTED_ROW, saved.main().selectedRowId());
                assertEquals(FILTER_TEXT, saved.main().filterText());
                assertEquals(Boolean.TRUE, saved.main().filters().get("pastExpanded"));
                assertEquals(Boolean.TRUE, saved.main().filters().get("whatIfIncome"));
                assertEquals(Boolean.TRUE, saved.main().filters().get("whatIfExpense"));
                assertEquals("5731,00", saved.main().whatIfExtra());
                first.kill().requireClean();
                assertFalse(first.process().isAlive());
                Files.copy(first.stderr(), run.resolve("before.stderr.log"));
                Files.writeString(run.resolve("before.stdout.redacted.log"),
                        Files.readString(first.stdout()).replaceAll("(?m)^PARITY_URL .*", "PARITY_URL [redacted]"));
                assertEquals(identity, cdp.evaluate("window.__s4OriginalDocument"));
                var second = ClientLauncher.launch(ClientTarget.web(layout),
                        request(home, node, run.resolve("after-out"), "after", null, port));
                owned.add(second);
                second.waitUntil(() -> address(second) != null, WAIT, "restarted Web handshake");
                URI next = address(second);
                assertEquals(url.getPort(), next.getPort());
                assertNotEquals(url.getRawQuery(), next.getRawQuery(), "Each boot must rotate API token");
                // Новый URL не передаётся браузеру: доказательство восстановления исходной страницы.
                try {
                    cdp.waitFor("!!document.querySelector('.alert-window[open][data-purpose=crashRecovery] button[data-cp-id=restoreServer]:enabled')", WAIT);
                } catch (RuntimeException failure) {
                    try {
                        Files.writeString(run.resolve("reconnect-dom-diagnostic.txt"), String.valueOf(cdp.evaluate(
                                "JSON.stringify({title:document.title,dialogs:[...document.querySelectorAll('dialog')].map(n=>({kind:n.dataset.kind,purpose:n.dataset.purpose,open:n.open,buttons:[...n.querySelectorAll('button')].map(b=>({id:b.dataset.cpId,disabled:b.disabled}))})),screens:[...document.querySelectorAll('.offline-screen')].map(n=>n.textContent)})",
                                Duration.ofSeconds(3))));
                    } catch (RuntimeException diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
                    throw failure;
                }
                assertOriginalDom(cdp, identity);
                cdp.evaluate("document.querySelector('.alert-window[open][data-purpose=crashRecovery] button[data-cp-id=restoreServer]').click()");
                cdp.waitFor("document.querySelectorAll('dialog[open]:not(.alert-window)').length === 2", WAIT);
                api.awaitIdle(Duration.ofSeconds(10));
                UiDump restored = api.dump("after-restart");
                Files.writeString(run.resolve("after.raw.json"), UiJson.write(restored));
                assertOriginalDom(cdp, identity);
                assertMain(before, restored);
                assertWindows(before, restored);
                second.waitUntil(() -> committed(store) && fresh(store, saved), WAIT, "restored fields freshly committed again");
                assertEquals(saved.main(), store.load().orElseThrow().main());
                assertEquals(saved.plan(), store.load().orElseThrow().plan());
                assertEquals(saved.windows().stream().map(w -> w.fields()).toList(),
                        store.load().orElseThrow().windows().stream().map(w -> w.fields()).toList());
                // Отдельно проверяется reload: здесь смена документа допустима, но не потеря форм.
                cdp.evaluate("location.reload(); true");
                cdp.waitFor("!window.__s4OriginalDocument && !!window.cpParityTestApi && document.querySelectorAll('dialog[open]:not(.alert-window)').length === 2", WAIT);
                var reloadedApi = new CdpTestApi(cdp);
                reloadedApi.awaitIdle(Duration.ofSeconds(10));
                UiDump reloaded = reloadedApi.dump("after-reload");
                Files.writeString(run.resolve("reload.raw.json"), UiJson.write(reloaded));
                assertMain(before, reloaded);
                assertWindows(restored, reloaded);
            }
        } finally {
            Throwable cleanupFailure = null;
            for (int i = owned.size() - 1; i >= 0; i--) {
                try { owned.get(i).close(); }
                catch (Throwable failure) {
                    if (cleanupFailure == null) cleanupFailure = failure;
                    else cleanupFailure.addSuppressed(failure);
                }
            }
            try {
                RegistryNodeCleaner.delete(node);
                assertFalse(RegistryNodeCleaner.exists(node));
                assertEquals(List.of(), realBefore.differences(RegistryNodeCleaner.snapshotRealSessionNodes()));
            } catch (Throwable failure) {
                if (cleanupFailure == null) cleanupFailure = failure;
                else cleanupFailure.addSuppressed(failure);
            }
            if (cleanupFailure != null) throw new AssertionError("Owned Web test resources leaked; evidence: " + run, cleanupFailure);
        }
    }

    /** Одинаковый порт и домашняя папка, разные собственные журналы каждого запуска. */
    private static LaunchRequest request(Path home, String node, Path out, String name, String script, int port) {
        return new LaunchRequest("web", name, home, node, LaunchRequest.PARITY_TODAY, script, out,
                List.of("--test-api", "--no-browser", "--no-window"),
                List.of("-Dcashprediction.web.port=" + port));
    }

    /** Читает только адрес из штатного журнала запуска, не раскрывая его в артефактах. */
    private static URI address(LaunchedClient server) {
        try {
            var lines = Files.readString(server.stdout()).lines().filter(s -> s.startsWith("PARITY_URL ")).toList();
            if (lines.isEmpty()) return null;
            assertEquals(1, lines.size());
            return URI.create(lines.getFirst().substring("PARITY_URL ".length()).strip());
        } catch (java.io.IOException e) { return null; }
    }

    /** Требует точный успешный префикс; незавершённый signal не выдаётся за завершённый сценарий. */
    private static void assertPrefix(LaunchedClient first) throws Exception {
        StringBuilder expected = new StringBuilder();
        for (var line : SelfTestScript.parse("before", SCRIPT).lines()) {
            if (line.text().equals("signal committed")) break;
            expected.append("SELFTEST ").append(line.number()).append(" OK ").append(line.text()).append('\n');
        }
        assertEquals(expected.toString(), Files.readString(first.request().selftestOut().resolve("selftest.log")));
    }

    /** Проверяет атомарный снимок, а не только существование файла на диске. */
    private static boolean committed(MarkdownSessionStore store) {
        try {
            return store.load().filter(s -> s.windows().size() == 2
                    && s.windows().stream().anyMatch(w -> w.fields().containsValue("bad731"))
                    && s.windows().stream().anyMatch(w -> w.fields().containsValue("bad732"))).isPresent();
        } catch (Exception transientRead) { return false; }
    }

    /** Не принимает сохранившийся снимок убитого процесса за новую запись второго запуска. */
    private static boolean fresh(MarkdownSessionStore store, SessionSnapshot before) {
        try { return store.load().orElseThrow().savedAt().isAfter(before.savedAt()); }
        catch (Exception transientRead) { return false; }
    }

    /** Проверяет сохранение исходного документа и настоящих узлов главного окна при reconnect. */
    private static void assertOriginalDom(CdpClient cdp, String identity) {
        assertEquals(identity, cdp.evaluate("window.__s4OriginalDocument"));
        assertEquals(Boolean.TRUE, cdp.evaluate("window.__s4MainNodes.length === 7 && "
                + "window.__s4MainNodes.every(node => node && node.isConnected && document.getElementById(node.id) === node)"));
    }

    /** Требует нетривиальное исходное состояние из виджетов, исключая сравнение двух пустых дампов. */
    private static void assertSeededMain(UiDump dump) {
        assertNotNull(dump.frame());
        assertNotNull(dump.table());
        assertNotNull(dump.chart());
        assertEquals(SELECTED_ROW, dump.table().selectedRowId());
        assertTrue(dump.table().rows().stream().anyMatch(row -> SELECTED_ROW.equals(row.rowId())),
                "Expanded past group must expose the selected past salary");
        assertTrue(dump.table().rows().stream().anyMatch(row -> "past@group".equals(row.rowId())));
        assertTrue(dump.summary().visible());
        assertFalse(dump.summary().cards().isEmpty());
        for (String id : List.of("view.chart", "view.period.ALL", "view.flag.showIncome",
                "view.flag.showSkipped", "view.flag.chartBars", "view.flag.summaryPanel",
                "whatIf.income", "whatIf.expense")) {
            assertTrue(menuItem(dump.menuBar(), id).checked(), id);
        }
        for (String id : List.of("view.table", "view.flag.showExpense", "view.flag.showOneTime",
                "view.flag.monthTotals", "view.flag.chartMarkers")) {
            assertFalse(menuItem(dump.menuBar(), id).checked(), id);
        }
        assertEquals("5731", menuItem(dump.menuBar(), "whatIf.extra").value());
        assertEquals(FILTER_TEXT, toolbarItem(dump, "tb.filter").text());
        assertTrue(toolbarItem(dump, "tb.chart").selected());
        assertFalse(toolbarItem(dump, "tb.table").selected());
        assertEquals(0, dump.chart().markerCount());
        assertTrue(dump.chart().barCount() > 0, "Monthly bars must actually be rendered");
        assertFalse(dump.chart().xLabels().isEmpty());
        assertEquals(List.of(), dump.screens());
        assertEquals(List.of(), dump.alerts());
    }

    /** Сопоставляет живое главное окно; времена записи, временные сообщения и счётчики команд не входят. */
    private static void assertMain(UiDump before, UiDump after) {
        assertSeededMain(after);
        assertEquals(before.frame(), after.frame(), "Main DOM frame and visible regions");
        // JavaFX: MenuBar → Swing: JMenuBar → Web: nav[role=menubar]
        assertEquals(menuItem(before.menuBar(), "view"), menuItem(after.menuBar(), "view"));
        assertEquals(menuItem(before.menuBar(), "tools.whatIf"), menuItem(after.menuBar(), "tools.whatIf"));
        assertEquals(before.toolbar(), after.toolbar(), "Actual toolbar texts, flags and spinner");
        assertEquals(before.summary(), after.summary(), "Actual forecast summary");
        assertEquals(before.table(), after.table(), "Rows, styles, digest, past expansion and selection");
        assertEquals(before.chart(), after.chart(), "Actual chart labels, markers and monthly bars");
        List<String> stableStatus = List.of("file", "dirty", "rows", "horizon", "whatIf");
        assertEquals(before.status().stream().filter(s -> stableStatus.contains(s.id())).toList(),
                after.status().stream().filter(s -> stableStatus.contains(s.id())).toList());
        assertEquals(before.screens(), after.screens());
        assertEquals(before.alerts(), after.alerts());
        // Восстановление выполняет собственные команды: равенство counters не является равенством интерфейса.
    }

    /** Находит единственный пункт в настоящем дереве меню, не подставляя состояние из SessionSnapshot. */
    private static UiDump.MenuItem menuItem(List<UiDump.MenuItem> items, String id) {
        List<UiDump.MenuItem> matches = new ArrayList<>();
        collectMenuItems(items, id, matches);
        assertEquals(1, matches.size(), "Unique actual menu item: " + id);
        return matches.getFirst();
    }

    /** Собирает совпадения id рекурсивно, сохраняя проверку уникальности в вызывающем методе. */
    private static void collectMenuItems(List<UiDump.MenuItem> items, String id, List<UiDump.MenuItem> matches) {
        for (var item : items) {
            if (id.equals(item.id())) matches.add(item);
            collectMenuItems(item.children(), id, matches);
        }
    }

    /** Находит фактически отрисованный элемент тулбара по уникальному id. */
    private static UiDump.ToolbarItem toolbarItem(UiDump dump, String id) {
        var matches = dump.toolbar().items().stream().filter(item -> id.equals(item.id())).toList();
        assertEquals(1, matches.size(), "Unique actual toolbar item: " + id);
        return matches.getFirst();
    }

    /** Фиксирует вложенную модальную форму и исходные невалидные значения до убийства сервера. */
    private static void assertSourceWindows(UiDump dump) {
        // JavaFX: Dialog → Swing: JDialog → Web: dialog
        var modeless = dump.windows().stream().filter(w -> !w.modal()).toList();
        var modal = dump.windows().stream().filter(UiDump.Window::modal).toList();
        assertEquals(1, modeless.size());
        assertEquals(1, modal.size());
        assertEquals("main", modeless.getFirst().ownerId());
        // modalOwner() ядра привязывает модальную форму к main, когда открыта только немодальная форма.
        assertEquals("main", modal.getFirst().ownerId());
        assertTrue(modeless.getFirst().fields().stream().anyMatch(f -> "target".equals(f.id())
                && FieldCodec.parseMoney(f.text()).map(m -> "450731,00".equals(m.formatPlain())).orElse(false)));
        assertTrue(modeless.getFirst().fields().stream().anyMatch(f -> "extraSaving".equals(f.id()) && "bad731".equals(f.text())));
        assertTrue(modal.getFirst().fields().stream().anyMatch(f -> "title".equals(f.id()) && "web-restart-sentinel".equals(f.text())));
        assertTrue(modal.getFirst().fields().stream().anyMatch(f -> "amount".equals(f.id()) && "bad732".equals(f.text())));
    }

    /** Сопоставляет реальные поля, страницы и связи владельцев при возможной смене идентификаторов. */
    private static void assertWindows(UiDump before, UiDump after) {
        assertEquals(before.windows().size(), after.windows().size());
        Map<String, String> ids = new java.util.HashMap<>();
        ids.put("main", "main");
        for (var old : before.windows()) {
            var matches = after.windows().stream().filter(w -> w.type().equals(old.type())).toList();
            assertEquals(1, matches.size(), "Unique actual window type: " + old.type());
            ids.put(old.id(), matches.getFirst().id());
        }
        for (var old : before.windows()) {
            var now = after.windows().stream().filter(w -> w.id().equals(ids.get(old.id()))).findFirst().orElseThrow();
            assertEquals(old.fields(), now.fields());
            assertEquals(old.page(), now.page());
            assertEquals(old.modal(), now.modal());
            assertEquals(ids.get(old.ownerId()), now.ownerId());
            assertEquals(old.problem(), now.problem());
            assertEquals(old.preview(), now.preview());
            assertEquals(old.previewSelected(), now.previewSelected());
            assertEquals(old.sections(), now.sections());
            assertEquals(old.hints(), now.hints());
            assertEquals(old.results(), now.results());
            assertEquals(old.bounds(), now.bounds(), "Same viewport must restore actual DOM bounds");
        }
    }
}
