package ru.cashprediction.web.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.json.*;
import ru.cashprediction.core.ui.alert.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.json.*;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.web.*;

/** Проверяет серверный контракт настоящими HTTP-запросами с изолированным CashMemory. */
class UiApiTest {
    @TempDir Path home;
    private WebServer server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private long seq;

    @BeforeEach void start() throws Exception {
        System.setProperty("cashprediction.ui.strictText", "true");
        server = WebServer.startCore(AppEnvironment.from(LaunchOptions.parse(List.of("--home", home.toString(),
                "--registry", "memory", "--today", "2026-09-13"), new java.util.Properties())), new ServerLog(false), 0, true);
        Map<String, Object> bootstrap = bootstrap("one");
        List<Object> windows = list(bootstrap.get("windows"));
        if (!windows.isEmpty()) {
            String id = Json.requireString(obj(obj(windows.getLast()).get("window")), "id");
            intent("one", Map.of("type", "formShown", "windowId", id));
            intent("one", Map.of("type", "formClose", "windowId", id));
        }
    }
    @AfterEach void close() { if (server != null) server.stop(); System.clearProperty("cashprediction.ui.strictText"); }

    @Test void bootstrapAndInlineEffectsUseOneState() throws Exception {
        command("file.sample");
        Map<String, Object> left = bootstrap("one"), right = bootstrap("two");
        assertEquals(left.get("screen"), right.get("screen"));
        assertEquals(false, left.get("testApi"));
        assertEquals("SERVER_BROWSER", obj(left.get("profile")).get("chooser"));
        assertTrue(((Number) obj(obj(left.get("screen")).get("table")).get("rowCount")).intValue() > 0);
        Map<String, Object> result = intent("one", Map.of("type", "filterText", "text", "test"));
        assertFalse(list(result.get("effects")).isEmpty());
        assertOrdered(result);
    }
    /** Реальный HTTP сохраняет исходный запрос выбора и передаёт его повторно новой вкладке. */
    @Test void fileBrowserPublishesChooserRequestWithoutConsumingDialogIds() throws Exception {
        command("file.openFile");
        var opened = obj(obj(list(bootstrap("one").get("windows")).getLast()).get("window"));
        assertEquals("chooser1", opened.get("id"));
        var request = obj(opened.get("chooserRequest"));
        assertEquals(java.util.Set.of("kind", "mode", "title", "filter", "folder", "name"), request.keySet());
        assertEquals("file", request.get("kind"));
        assertEquals("OPEN", request.get("mode"));
        assertEquals(home.resolve("CashMemory").toString(), request.get("folder"));
        assertEquals("", request.get("name"));
        assertTrue(Json.requireString(request, "filter").contains("*.md"));
        assertFalse(Json.requireString(request, "title").isBlank());
        var repeated = obj(obj(list(bootstrap("two").get("windows")).getLast()).get("window"));
        assertEquals(request, repeated.get("chooserRequest"));
        intent("one", Map.of("type", "formShown", "windowId", "chooser1"));
        intent("one", Map.of("type", "formClose", "windowId", "chooser1"));
        assertTrue(list(bootstrap("one").get("windows")).isEmpty());
        command("file.openFile");
        assertEquals("chooser2", lastForm());
    }
    /** Синтетическая модель bootstrap не означает показ главного окна во время выбора восстановления. */
    @Test void recoveryPendingPersistsUntilMainIsShownAndReleasesInertOnlyOnce() throws Exception {
        server.stop();
        var environment = AppEnvironment.from(LaunchOptions.parse(List.of("--home", home.toString(),
                "--registry", "memory", "--today", "2026-09-13"), new java.util.Properties()));
        environment.webStore().markDirty(ru.cashprediction.core.session.SessionMarker.running(0,
                environment.clock().now(), "web"));
        server = WebServer.startCore(environment, new ServerLog(false), 0, true);
        var pending = bootstrap("one");
        assertEquals("RECOVERY_PENDING", pending.get("overlay"));
        assertNotNull(pending.get("screen"));
        assertNull(server.coreRuntime().thread().submit(() -> server.coreRuntime().port().screen()).get());
        var alert = obj(list(pending.get("windows")).getLast());
        String id = Json.requireString(alert, "alertId");
        assertEquals("crashRecovery", obj(alert.get("spec")).get("purpose"));
        assertAlertPlacement(alert, "main", null);
        var bounds = Map.of("x", 30, "y", 40, "width", 720, "height", 460);
        intent("one", Map.of("type", "formBounds", "windowId", id, "bounds", bounds));
        intent("one", Map.of("type", "alertShown", "windowId", id));
        intent("one", Map.of("type", "filterText", "text", "blocked"));
        for (String tab : List.of("one", "two")) {
            var reloaded = bootstrap(tab);
            assertEquals("RECOVERY_PENDING", reloaded.get("overlay"));
            assertAlertPlacement(obj(list(reloaded.get("windows")).getLast()), "main", bounds);
        }
        assertNull(server.coreRuntime().thread().submit(() -> server.coreRuntime().port().screen()).get());
        var initial = list(getJson("/api/ui/events?tab=two&after=0").get("effects")).stream().map(UiApiTest::obj).toList();
        assertTrue(initial.stream().noneMatch(effect -> "screen".equals(effect.get("type"))));
        assertEquals(1, initial.stream().filter(effect -> "inert".equals(effect.get("type"))
                && Boolean.TRUE.equals(effect.get("value"))).count());
        var answered = intent("two", Map.of("type", "alertButton", "alertId", id, "buttonId", AlertCatalog.BUTTON_NO_RESTORE));
        var effects = list(answered.get("effects")).stream().map(UiApiTest::obj).toList();
        var released = effects.stream().filter(effect -> "inert".equals(effect.get("type"))
                && Boolean.FALSE.equals(effect.get("value"))).toList();
        assertEquals(1, released.size());
        var shown = effects.stream().filter(effect -> "screen".equals(effect.get("type"))).findFirst().orElseThrow();
        assertTrue(Json.requireLong(released.getFirst(), "seq") < Json.requireLong(shown, "seq"));
        assertNotNull(server.coreRuntime().thread().submit(() -> server.coreRuntime().port().screen()).get());
        long afterShown = seq;
        for (String tab : List.of("one", "two")) assertNull(bootstrap(tab).get("overlay"));
        var duplicate = intent("one", Map.of("type", "alertButton", "alertId", id, "buttonId", AlertCatalog.BUTTON_NO_RESTORE));
        assertTrue(list(duplicate.get("effects")).isEmpty());
        assertEquals(afterShown, seq, "Reload and a duplicate answer must not publish main again");
    }
    @Test void reloadIncludesTypedFieldsOwnersAndLiveBounds() throws Exception {
        command("file.new"); String id = lastForm();
        intent("one", Map.of("type", "formField", "windowId", id, "fieldId", "name", "raw", "Draft", "committed", false, "clientRev", 10));
        var bounds = Map.of("x", 15, "y", 20, "width", 600, "height", 500);
        intent("one", Map.of("type", "formBounds", "windowId", id, "bounds", bounds));
        Map<String, Object> window = obj(obj(list(bootstrap("two").get("windows")).getLast()).get("window"));
        assertEquals("main", window.get("ownerId"));
        assertEquals("Draft", obj(obj(obj(window.get("view")).get("fields")).get("name")).get("value"));
        assertEquals(600L, Json.requireLong(obj(obj(window.get("placement")).get("bounds")), "width"));
    }
    @Test void fieldRevisionsAreDeduplicatedPerTabAndEchoIsExact() throws Exception {
        command("file.new"); String id = lastForm();
        Map<String, Object> first = intent("one", field(id, "name", "First", 8));
        Map<String, Object> echo = list(first.get("effects")).stream().map(UiApiTest::obj)
                .filter(effect -> effect.get("type").equals("form.view")).findFirst().orElseThrow();
        assertEquals("one", obj(echo.get("echoOf")).get("tab"));
        assertEquals(8L, Json.requireLong(obj(echo.get("echoOf")), "clientRev"));
        intent("one", field(id, "name", "Late", 7));
        assertEquals("First", fieldValue(id, "name"));
        intent("two", field(id, "name", "Other", 1));
        assertEquals("Other", fieldValue(id, "name"));
        intent("one", field(id, "name", "Duplicate", 8));
        assertEquals("Other", fieldValue(id, "name"));
    }
    @Test void modalChildBlocksParentFieldsButLifecycleStillAcknowledges() throws Exception {
        command("file.new"); String parent = lastForm();
        server.coreRuntime().thread().submit(() -> {
            server.coreRuntime().controller().showAlert(AlertCatalog.about("1", ClientProfile.web(), "25", home), ignored -> { });
            return null;
        }).get(5, TimeUnit.SECONDS);
        var nested = obj(list(bootstrap("two").get("windows")).getLast());
        String alertId = Json.requireString(nested, "alertId");
        assertAlertPlacement(nested, parent, null);
        var alertBounds = Map.of("x", 80, "y", 90, "width", 620, "height", 420);
        intent("two", Map.of("type", "formBounds", "windowId", alertId, "bounds", alertBounds));
        assertAlertPlacement(obj(list(bootstrap("one").get("windows")).getLast()), parent, alertBounds);
        String before = fieldValue(parent, "name");
        intent("two", field(parent, "name", "Blocked", 1));
        assertEquals(before, fieldValue(parent, "name"));
        intent("two", Map.of("type", "formShown", "windowId", parent));
        intent("two", Map.of("type", "formBounds", "windowId", parent, "bounds", Map.of("x", 1, "y", 2, "width", 600, "height", 400)));
        assertEquals(600, server.coreRuntime().thread().submit(() -> server.coreRuntime().port().form(parent).orElseThrow().handle().bounds().width()).get());
    }
    @Test void twoTabsAnswerOnlyOnceAndCloseIsBroadcast() throws Exception {
        command("help.about");
        Map<String, Object> alert = obj(list(bootstrap("one").get("windows")).getLast());
        String id = Json.requireString(alert, "alertId");
        String button = Json.requireString(obj(list(obj(alert.get("spec")).get("buttons")).getFirst()), "id");
        assertEquals(400, post("/api/ui/intent", envelope("one", Map.of("type", "alertButton", "alertId", id, "buttonId", "fake"))).statusCode());
        long before = seq;
        Map<String, Object> first = intent("one", Map.of("type", "alertButton", "alertId", id, "buttonId", button));
        assertEquals(1, list(first.get("effects")).stream().map(UiApiTest::obj).filter(effect -> effect.get("type").equals("alert.close")).count());
        intent("two", Map.of("type", "alertButton", "alertId", id, "buttonId", button));
        Map<String, Object> events = getJson("/api/ui/events?tab=two&after=" + before);
        assertEquals(1, list(events.get("effects")).stream().map(UiApiTest::obj).filter(effect -> effect.get("type").equals("alert.close")).count());
        assertTrue(list(bootstrap("two").get("windows")).isEmpty());
    }
    @Test void shownCallbacksWaitForBrowserAndUnknownOrLateIdsAreIgnored() throws Exception {
        int[] shown = {0}, registered = {0}, unregistered = {0};
        AlertSession[] session = {null};
        long before = seq;
        server.coreRuntime().thread().submit(() -> {
            var base = AlertCatalog.info("recordingOff");
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog
            var spec = new AlertSpec(base.kind(), base.purpose(), base.targetId(), base.windowTitle(), base.glyph(),
                    base.header(), base.content(), base.details(), base.detailsExpanded(), base.minWidth(), base.buttons(), base.defaultButtonId(), true);
            session[0] = new AlertSession("restore-alert", "owner-form", spec, new AlertSession.Host() {
                @Override public void registered(AlertSession value) { registered[0]++; }
                @Override public void unregistered(AlertSession value) { unregistered[0]++; }
            });
            session[0].applyState(new ru.cashprediction.core.session.WindowState("restore-alert",
                    ru.cashprediction.core.session.WindowType.ALERT, true, "owner-form",
                    new ru.cashprediction.core.session.WindowBounds(11, 22, 640, 480), Map.of(), Map.of()));
            session[0].whenShown(value -> shown[0]++);
            var handle = server.coreRuntime().port().showAlert(spec, session[0], ignored -> { }); session[0].attach(handle);
            assertFalse(handle.showing());
            assertEquals("owner-form", session[0].captureState().ownerId());
            assertEquals(new ru.cashprediction.core.session.WindowBounds(11, 22, 640, 480), handle.bounds());
            return null;
        }).get();
        assertEquals(0, shown[0]);
        var restoredBounds = Map.of("x", 11, "y", 22, "width", 640, "height", 480);
        var opened = list(getJson("/api/ui/events?tab=one&after=" + before).get("effects")).stream().map(UiApiTest::obj)
                .filter(effect -> "alert.open".equals(effect.get("type"))).findFirst().orElseThrow();
        assertEquals("restore-alert", opened.get("alertId"));
        assertAlertPlacement(opened, "owner-form", restoredBounds);
        for (String id : List.of("unknown", "main")) intent("one", Map.of("type", "alertShown", "windowId", id));
        assertEquals(0, shown[0]);
        assertAlertPlacement(obj(list(bootstrap("two").get("windows")).getLast()), "owner-form", restoredBounds);
        assertEquals(0, shown[0]);
        intent("two", Map.of("type", "formBounds", "windowId", "restore-alert", "bounds",
                Map.of("x", 33, "y", 44, "width", 660, "height", 500)));
        assertAlertPlacement(obj(list(bootstrap("one").get("windows")).getLast()), "owner-form",
                Map.of("x", 33, "y", 44, "width", 660, "height", 500));
        server.coreRuntime().thread().submit(() -> {
            assertEquals("owner-form", session[0].captureState().ownerId());
            assertEquals(new ru.cashprediction.core.session.WindowBounds(33, 44, 660, 500), session[0].captureState().bounds());
            return null;
        }).get();
        for (int n = 0; n < 2; n++) intent("one", Map.of("type", "alertShown", "windowId", "restore-alert"));
        assertEquals(1, shown[0]); assertEquals(1, registered[0]);
        intent("one", Map.of("type", "alertButton", "alertId", "restore-alert", "buttonId", "ok"));
        intent("one", Map.of("type", "alertShown", "windowId", "restore-alert"));
        assertEquals(1, shown[0]);
        intent("two", Map.of("type", "alertButton", "alertId", "restore-alert", "buttonId", "ok"));
        assertEquals(1, unregistered[0]);
        assertTrue(list(bootstrap("two").get("windows")).isEmpty());
    }
    @Test void formShownIsAcknowledgedOnlyOnceAndNeverByPublicationOrBootstrap() throws Exception {
        command("file.new"); String id = lastForm(); int[] count = {0};
        server.coreRuntime().thread().submit(() -> {
            var form = server.coreRuntime().port().form(id).orElseThrow();
            form.whenShown(value -> count[0]++); assertFalse(form.handle().showing()); return null;
        }).get();
        bootstrap("two"); assertEquals(0, count[0]);
        intent("one", Map.of("type", "alertShown", "windowId", id)); assertEquals(0, count[0]);
        intent("one", Map.of("type", "formShown", "windowId", id));
        intent("two", Map.of("type", "formShown", "windowId", id)); assertEquals(1, count[0]);
        intent("one", Map.of("type", "formClose", "windowId", id));
        intent("one", Map.of("type", "formShown", "windowId", id)); assertEquals(1, count[0]);
    }
    @Test void enabledTestApiRoutesValidatePendingStepAndRequireToken() throws Exception {
        server.stop();
        server = WebServer.startCore(AppEnvironment.from(LaunchOptions.parse(List.of("--home", home.toString(),
                "--registry", "memory", "--today", "2026-09-13", "--test-api", "--selftest", "s01-first-run",
                "--selftest-out", home.resolve("selftest").toString()), new java.util.Properties())), new ServerLog(false), 0, true);
        var boot = bootstrap("one"); assertEquals(true, boot.get("testApi"));
        var result = getJson("/api/ui/events?tab=one&after=" + seq);
        var step = list(result.get("effects")).stream().map(UiApiTest::obj).filter(effect -> effect.get("type").equals("test.step")).findFirst().orElseThrow();
        long n = Json.requireLong(step, "n");
        assertEquals(403, http.send(HttpRequest.newBuilder(uri("/api/test/result")).POST(HttpRequest.BodyPublishers.ofString("{\"n\":2,\"ok\":true}")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, post("/api/test/result", Map.of("n", n - 1, "ok", true)).statusCode());
        assertEquals(400, post("/api/test/dump", Map.of("n", n, "dump", Map.of("client", "model", "schema", 1))).statusCode());
        // Здесь синтетический ответ проверяет HTTP-мост, а не успешность исполнения DOM-команды сценария.
        assertEquals(200, post("/api/test/result", Map.of("n", n, "ok", false, "message", "protocol probe")).statusCode());
        assertEquals(400, post("/api/test/result", Map.of("n", n, "ok", true)).statusCode());
        var stopped = new java.util.concurrent.CountDownLatch(1); server.addStopListener(stopped::countDown);
        var lines = ru.cashprediction.core.ui.selftest.SelfTestScript.load("s01-first-run").lines().stream()
                .filter(line -> !(line.command() instanceof ru.cashprediction.core.ui.selftest.SelfTestCommand.Today)).toList();
        // Завершаем только протокольную проверку явными FAIL, не выдавая её за исполнение сценария в DOM.
        for (int i = 1; i < lines.size(); i++) {
            if (i == lines.size() - 1) server.coreRuntime().thread().submit(() -> {
                server.coreRuntime().controller().port().exit(ExitKind.WEB_STOPPED, 0); return null;
            }).get();
            var response = post("/api/test/result", Map.of("n", lines.get(i).number(), "ok", false, "message", "protocol probe"));
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(true, obj(JsonParser.parse(response.body())).get("ok"));
        }
        assertTrue(server.coreRuntime().tests().completion().isDone());
        assertTrue(stopped.await(3, TimeUnit.SECONDS));
    }
    @Test void rowsPagingAndAllQueriesFollowCoreRevisions() throws Exception {
        command("file.sample"); var screen = obj(bootstrap("one").get("screen"));
        long rev = Json.requireLong(obj(screen.get("table")), "revision");
        assertEquals(Math.min(300, Json.requireLong(obj(screen.get("table")), "rowCount")),
                list(query(Map.of("type", "rows", "rev", rev, "from", 0, "count", 300)).get("result")).size());
        assertEquals(true, query(Map.of("type", "rows", "rev", rev - 1, "from", 0, "count", 300)).get("stale"));
        assertEquals(400, post("/api/ui/query", Map.of("type", "rows", "rev", rev, "from", 0, "count", 301)).statusCode());
        Map<String, Object> tooltip = obj(query(Map.of("type", "tooltip", "rev", rev, "index", 0,
                "columnId", "balance")).get("result"));
        assertTrue(tooltip.get("text") instanceof String);
        assertEquals(List.of(), tooltip.get("iconPositions"));
        long chartRev = Json.requireLong(obj(screen.get("chart")), "revision");
        assertNotNull(query(Map.of("type", "chartScene", "rev", chartRev, "w", 1200, "h", 700)).get("result"));
        assertNull(query(Map.of("type", "chartHover", "rev", chartRev, "x", -1, "y", -1, "w", 1200, "h", 700)).get("result"));
        assertNotNull(query(Map.of("type", "dayCard", "date", "2026-09-13")).get("result"));
        assertNotNull(query(Map.of("type", "sparkline", "cardId", "m3")).get("result"));
        assertNotNull(query(Map.of("type", "calendar", "month", "2026-09")).get("result"));
        assertNotNull(query(Map.of("type", "contextMenu", "target", Map.of("kind", "card", "cardId", "m3"))).get("result"));
    }
    @Test void todayMismatchFailsRealBootstrapWithoutPublishingBrowserStep() throws Exception {
        server.stop();
        Path output = home.resolve("wrong-date");
        server = WebServer.startCore(AppEnvironment.from(LaunchOptions.parse(List.of("--home", home.toString(),
                "--registry", "memory", "--today", "2026-09-14", "--test-api", "--selftest", "s01-first-run",
                "--selftest-out", output.toString()), new java.util.Properties())), new ServerLog(false), 0, true);
        var response = get("/api/ui/bootstrap?tab=one");
        assertEquals(500, response.statusCode());
        assertEquals(UiText.get("err.generic"), obj(JsonParser.parse(response.body())).get("error"));
        assertTrue(server.coreRuntime().tests().completion().isCompletedExceptionally());
        assertTrue(java.nio.file.Files.readString(output.resolve("selftest.log"))
                .startsWith("SELFTEST 2 FAIL today 2026-09-13: today expected 2026-09-13 but was 2026-09-14"));
        assertTrue(list(server.coreRuntime().effects().after(0).get("effects")).stream().map(UiApiTest::obj)
                .noneMatch(effect -> effect.get("type").equals("test.step")));
    }
    @Test void firstBootstrapPublishesStepBeyondCursorAndLaterBootstrapCannotReplayIt() throws Exception {
        server.stop();
        server = WebServer.startCore(AppEnvironment.from(LaunchOptions.parse(List.of("--home", home.toString(),
                "--registry", "memory", "--today", "2026-09-13", "--test-api", "--selftest", "s02-sample-table",
                "--selftest-out", home.resolve("selftest").toString()), new java.util.Properties())), new ServerLog(false), 0, true);
        var first = bootstrap("collector");
        long beforeRestart = Json.requireLong(first, "seq");
        var original = list(getJson("/api/ui/events?tab=collector&after=" + beforeRestart).get("effects"))
                .stream().map(UiApiTest::obj).filter(effect -> effect.get("type").equals("test.step")).findFirst().orElseThrow();
        long n = Json.requireLong(original, "n");
        assertEquals("key Esc", original.get("command"));
        var replacement = bootstrap("other-tab");
        var later = server.coreRuntime().thread().submit(() -> server.coreRuntime().effects()
                .after(Json.requireLong(replacement, "seq"))).get();
        assertTrue(list(later.get("effects")).stream().map(UiApiTest::obj)
                .noneMatch(effect -> effect.get("type").equals("test.step")));
        assertEquals(200, post("/api/test/result", Map.of("n", n, "ok", true)).statusCode());
        var next = list(getJson("/api/ui/events?tab=collector&after=" + Json.requireLong(original, "seq")).get("effects"))
                .stream().map(UiApiTest::obj).filter(effect -> effect.get("type").equals("test.step")).findFirst().orElseThrow();
        assertEquals("sample", next.get("command"));
        assertEquals(200, post("/api/test/result", Map.of("n", Json.requireLong(next, "n"), "ok", true)).statusCode());
        var size = list(getJson("/api/ui/events?tab=collector&after=" + Json.requireLong(next, "seq")).get("effects"))
                .stream().map(UiApiTest::obj).filter(effect -> effect.get("type").equals("test.step")).findFirst().orElseThrow();
        assertEquals("size 1200 800", size.get("command"));
        assertEquals(400, post("/api/test/result", Map.of("n", n, "ok", true)).statusCode());
    }
    @Test void longPollWakesInOrderAndOverrunResyncs() throws Exception {
        long after = seq;
        var pending = http.sendAsync(request("/api/ui/events?tab=two&after=" + after).GET().build(), HttpResponse.BodyHandlers.ofString());
        server.coreRuntime().thread().submit(() -> { server.coreRuntime().effects().append(new WebEffect.Clipboard("a")); return null; }).get();
        Map<String, Object> response = obj(JsonParser.parse(pending.get(5, TimeUnit.SECONDS).body()));
        assertOrdered(response); assertFalse(list(response.get("effects")).isEmpty());
        server.coreRuntime().thread().submit(() -> {
            for (int i = 0; i < 1001; i++) server.coreRuntime().effects().append(new WebEffect.Clipboard("x")); return null;
        }).get();
        assertEquals(true, getJson("/api/ui/events?tab=two&after=" + after).get("resync"));
    }
    @Test void testEndpointsAreDisabledAndOrdinaryApiCannotForgeSelftest() throws Exception {
        assertEquals(404, get("/api/test/counters").statusCode());
        for (String route : List.of("/api/test/result", "/api/test/dump")) assertEquals(404, post(route, Map.of("n", 1, "ok", true)).statusCode());
        assertEquals(404, http.send(request("/api/test/result").POST(HttpRequest.BodyPublishers.ofString("not JSON")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, post("/api/ui/intent", envelope("one", Map.of("type", "command", "command", "file.sample", "source", "SELFTEST"))).statusCode());
    }
    @Test void testCountersAreProtectedReadOnlyAndContainActualPositiveCommandIds() throws Exception {
        server.stop();
        server = WebServer.startCore(AppEnvironment.from(LaunchOptions.parse(List.of("--home", home.toString(),
                "--registry", "memory", "--today", "2026-09-13", "--test-api"), new java.util.Properties())), new ServerLog(false), 0, true);
        bootstrap("one");
        assertTrue(obj(getJson("/api/test/counters").get("counters")).isEmpty());
        command("file.sample");
        assertTrue(obj(getJson("/api/test/counters").get("counters")).isEmpty(), "Modal blocks execution, not only rendering");
        for (Object value : list(bootstrap("one").get("windows"))) {
            Map<String, Object> effect = obj(value);
            if (effect.get("type").equals("form.open")) intent("one", Map.of("type", "formClose", "windowId", obj(effect.get("window")).get("id")));
        }
        command("file.sample"); command("view.chart"); command("view.table"); command("view.chart");
        var counters = obj(getJson("/api/test/counters").get("counters"));
        assertEquals(1, Json.requireLong(counters, "file.sample"));
        assertEquals(2, Json.requireLong(counters, "view.chart"));
        assertEquals(1, Json.requireLong(counters, "view.table"));
        assertTrue(counters.values().stream().allMatch(value -> ((Number) value).intValue() > 0));
        assertTrue(counters.keySet().stream().allMatch(id -> ru.cashprediction.core.ui.command.CommandId.byId(id) != null));
        assertEquals(403, http.send(HttpRequest.newBuilder(uri("/api/test/counters")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(404, post("/api/test/counters", Map.of("command", "file.sample")).statusCode());
        assertEquals(400, http.send(request("/api/test/counters").method("GET", HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, get("/api/test/counters?command=file.sample").statusCode());
        assertEquals(counters, obj(getJson("/api/test/counters").get("counters")));
    }
    @Test void clientErrorKeepsServerAliveAndReloadTargetsOriginEvenWhenOtherTabAnswers() throws Exception {
        String stack = "render.js:12\n  at paint\n";
        intent("one", Map.of("type", "clientError", "message", "TypeError: missing", "stack", stack));
        var alert = obj(list(bootstrap("two").get("windows")).getLast());
        String id = Json.requireString(alert, "alertId");
        assertEquals(stack, obj(alert.get("spec")).get("details"));
        assertEquals("TypeError: missing", obj(alert.get("spec")).get("content"));
        intent("two", Map.of("type", "clientError", "message", "repeat", "stack", "repeat stack"));
        assertEquals(1, list(bootstrap("one").get("windows")).size());
        var continued = intent("two", Map.of("type", "alertButton", "alertId", id, "buttonId", "continueWork"));
        assertTrue(list(continued.get("effects")).stream().map(UiApiTest::obj).noneMatch(effect -> effect.get("type").equals("reload") || effect.get("type").equals("exit")));
        assertNull(bootstrap("one").get("overlay"));
        intent("one", Map.of("type", "clientError", "message", "next", "stack", stack));
        id = Json.requireString(obj(list(bootstrap("two").get("windows")).getLast()), "alertId");
        var reloaded = intent("two", Map.of("type", "alertButton", "alertId", id, "buttonId", "reloadPage"));
        var reload = list(reloaded.get("effects")).stream().map(UiApiTest::obj).filter(effect -> effect.get("type").equals("reload")).toList();
        assertEquals(1, reload.size()); assertEquals("one", reload.getFirst().get("tab"));
        var duplicate = intent("one", Map.of("type", "alertButton", "alertId", id, "buttonId", "reloadPage"));
        assertTrue(list(duplicate.get("effects")).isEmpty());
        assertNull(bootstrap("two").get("overlay"));
        assertFalse(server.coreRuntime().controller().recorder().isClosed());
        assertTrue(server.coreRuntime().controller().environment().webStore().load().isPresent());
    }
    @Test void malformedRequestsAndWrongTokenAreRejected() throws Exception {
        assertEquals(403, http.send(HttpRequest.newBuilder(uri("/api/ui/bootstrap?tab=one")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(403, http.send(HttpRequest.newBuilder(uri("/api/ui/bootstrap?tab=one")).header("X-Token", "wrong").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(400, post("/api/ui/intent", envelope("one", Map.of("type", "alertShown", "alertId", "w1"))).statusCode());
        assertEquals(400, post("/api/ui/intent", envelope("one", Map.of("type", "unknown"))).statusCode());
        assertEquals(400, get("/api/ui/events?tab=one&after=" + Long.MAX_VALUE).statusCode());
    }
    @Test void securityRejectsRebindingHostOverRawHttp() throws Exception {
        try (var socket = new java.net.Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /api/ui/bootstrap?tab=one HTTP/1.1\r\nHost: evil.example:" + server.port()
                    + "\r\nX-Token: " + server.token() + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertTrue(new String(socket.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).startsWith("HTTP/1.1 403"));
        }
    }
    @Test void unexpectedFailureReturnsLocalized500EvenWithStrictCatalog() throws Exception {
        HttpServer faulty = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        faulty.createContext("/api/", new UiApi(server.coreRuntime().thread(), null, server.coreRuntime().port(), server.coreRuntime().effects(),
                server.token(), () -> faulty.getAddress().getPort(), null)); faulty.start();
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + faulty.getAddress().getPort() + "/api/ui/query"))
                    .header("X-Token", server.token()).POST(HttpRequest.BodyPublishers.ofString("{\"type\":\"calendar\",\"month\":\"2026-09\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(500, response.statusCode()); assertEquals(UiText.get("err.generic"), obj(JsonParser.parse(response.body())).get("error"));
        } finally { faulty.stop(0); }
    }
    @Test void tokenCssIsGeneratedByCoreAndRootServesUnifiedPage() throws Exception {
        assertEquals(200, get("/app/tokens.css").statusCode());
        assertEquals(ru.cashprediction.core.ui.token.TokenCss.webCss(), get("/app/tokens.css").body());
        assertEquals(200, get("/").statusCode());
        assertTrue(server.browserUri().getPath().equals("/"));
    }
    @Test void exitPublishesScreenBlocksEveryLateFormMutationAndStopsHttp() throws Exception {
        command("file.new"); String id = lastForm();
        intent("one", field(id, "name", "Draft", 1));
        intent("one", Map.of("type", "formBounds", "windowId", id, "bounds",
                Map.of("x", 1, "y", 2, "width", 600, "height", 400)));
        int[] shown = {0};
        server.coreRuntime().thread().submit(() -> {
            server.coreRuntime().port().form(id).orElseThrow().whenShown(value -> shown[0]++); return null;
        }).get();
        var stopped = new java.util.concurrent.CountDownLatch(1); server.addStopListener(stopped::countDown);
        long before = seq;
        server.coreRuntime().thread().submit(() -> {
            server.coreRuntime().controller().port().exit(ExitKind.WEB_CRASHED, 3); return null;
        }).get();
        Map<String, Object> events = getJson("/api/ui/events?tab=one&after=" + before);
        assertTrue(list(events.get("effects")).stream().map(UiApiTest::obj).anyMatch(effect -> effect.get("type").equals("exit")));
        intent("two", field(id, "name", "Late", 2));
        intent("two", Map.of("type", "formButton", "windowId", id, "buttonId", "finish"));
        intent("two", Map.of("type", "formClose", "windowId", id));
        intent("two", Map.of("type", "formBounds", "windowId", id, "bounds",
                Map.of("x", 3, "y", 4, "width", 800, "height", 700)));
        intent("two", Map.of("type", "formShown", "windowId", id));
        assertEquals("Draft", fieldValue(id, "name"));
        assertEquals(0, shown[0]);
        assertEquals(new ru.cashprediction.core.session.WindowBounds(1, 2, 600, 400), server.coreRuntime().thread()
                .submit(() -> server.coreRuntime().port().form(id).orElseThrow().handle().bounds()).get());
        assertEquals("CRASHED", bootstrap("two").get("overlay"));
        assertTrue(stopped.await(3, TimeUnit.SECONDS));
        assertTrue(AppEnvironment.from(LaunchOptions.parse(List.of("--home", home.toString(), "--registry", "memory"), new java.util.Properties()))
                .webStore().readMarker().orElseThrow().isRunning());
        assertThrows(java.io.IOException.class, () -> get("/api/ui/bootstrap?tab=one"));
    }
    @Test void cleanExitHasSharedOfflineScreenAndStopsHttp() throws Exception {
        var stopped = new java.util.concurrent.CountDownLatch(1); server.addStopListener(stopped::countDown);
        Map<String, Object> result = intent("one", Map.of("type", "closeMain"));
        Map<String, Object> exit = list(result.get("effects")).stream().map(UiApiTest::obj)
                .filter(effect -> effect.get("type").equals("exit")).findFirst().orElseThrow();
        assertEquals(UiText.get("offline.stopped.title"), exit.get("title"));
        assertEquals(UiText.get("offline.stopped.text"), exit.get("text"));
        assertEquals("STOPPED", bootstrap("two").get("overlay"));
        assertTrue(stopped.await(3, TimeUnit.SECONDS));
        assertFalse(AppEnvironment.from(LaunchOptions.parse(List.of("--home", home.toString(), "--registry", "memory"), new java.util.Properties()))
                .webStore().readMarker().orElseThrow().isRunning());
    }
    @Test void busyReplyDoesNotCancelQueuedActionAndEffectsArriveLater() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocker = server.coreRuntime().thread().submit(() -> { entered.countDown(); release.await(8, TimeUnit.SECONDS); return null; });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        long after = seq;
        try {
            var response = post("/api/ui/intent", envelope("one", Map.of("type", "filterText", "text", "busy")));
            assertEquals(503, response.statusCode()); assertEquals(true, obj(JsonParser.parse(response.body())).get("busy"));
        } finally { release.countDown(); }
        blocker.get();
        var response = getJson("/api/ui/events?tab=one&after=" + after);
        assertFalse(list(response.get("effects")).isEmpty());
        assertOrdered(response);
    }
    private Map<String, Object> bootstrap(String tab) throws Exception { var value = getJson("/api/ui/bootstrap?tab=" + tab); seq = Json.requireLong(value, "seq"); return value; }
    private Map<String, Object> intent(String tab, Map<String, Object> value) throws Exception {
        var response = post("/api/ui/intent", envelope(tab, value)); assertEquals(200, response.statusCode(), response.body());
        var result = obj(JsonParser.parse(response.body())); if (result.containsKey("seq")) seq = Json.requireLong(result, "seq"); return result;
    }
    private Map<String, Object> envelope(String tab, Map<String, Object> intent) { return Map.of("tab", tab, "afterSeq", seq, "intent", intent); }
    private void command(String command) throws Exception { intent("one", Map.of("type", "command", "command", command, "source", "MENU")); }
    private String lastForm() throws Exception { return Json.requireString(obj(obj(list(bootstrap("one").get("windows")).getLast()).get("window")), "id"); }
    private String fieldValue(String id, String field) throws Exception {
        return server.coreRuntime().thread().submit(() -> server.coreRuntime().port().form(id).orElseThrow().state().value(field)).get();
    }
    private static Map<String, Object> field(String id, String field, String raw, long rev) {
        return Map.of("type", "formField", "windowId", id, "fieldId", field, "raw", raw, "committed", false, "clientRev", rev);
    }
    private Map<String, Object> query(Map<String, Object> query) throws Exception {
        var response = post("/api/ui/query", query); assertEquals(200, response.statusCode(), response.body()); return obj(JsonParser.parse(response.body()));
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + server.port() + path); }
    private HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(8)).header("X-Token", server.token()); }
    private HttpResponse<String> get(String path) throws Exception { return http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString()); }
    private Map<String, Object> getJson(String path) throws Exception { var response = get(path); assertEquals(200, response.statusCode(), response.body()); return obj(JsonParser.parse(response.body())); }
    private HttpResponse<String> post(String path, Map<String, Object> value) throws Exception { return http.send(request(path).POST(HttpRequest.BodyPublishers.ofString(JsonWriter.write(value))).build(), HttpResponse.BodyHandlers.ofString()); }
    private static Map<String, Object> obj(Object value) { return Json.asObject(value, "test"); }
    /** Проверяет владельца и актуальные границы сообщения в реальном JSON, включая отсутствие границ нового окна. */
    private static void assertAlertPlacement(Map<String, Object> effect, String owner, Map<String, Integer> bounds) {
        assertEquals("alert.open", effect.get("type"));
        var placement = obj(effect.get("placement"));
        assertEquals(owner, placement.get("ownerId"));
        assertNull(placement.get("anchor"));
        if (bounds == null) assertNull(placement.get("bounds"));
        else {
            var actual = obj(placement.get("bounds"));
            bounds.forEach((key, value) -> assertEquals(value.doubleValue(), ((Number) actual.get(key)).doubleValue(), key));
        }
    }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>) value; }
    private static void assertOrdered(Map<String, Object> response) {
        long previous = -1;
        for (Object effect : list(response.get("effects"))) { long current = Json.requireLong(obj(effect), "seq"); assertTrue(current > previous); previous = current; }
    }
}
