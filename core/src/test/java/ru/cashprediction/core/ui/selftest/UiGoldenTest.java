package ru.cashprediction.core.ui.selftest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.session.CrashDetector;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.ui.dump.DumpDiff;
import static org.junit.jupiter.api.Assertions.*;

/** Прогон всех сценариев через реальный контроллер; ни один FAIL не превращается в эталон. */
class UiGoldenTest {
    @TempDir Path temporary;
    static List<String> scenarios() { return SelfTestScript.SCENARIOS; }

    private AppEnvironment environment(String name) {
        var options = LaunchOptions.parse("--home", temporary.resolve(name).toString(), "--registry", "memory", "--today", "2026-09-13", "--selftest", name);
        var env = AppEnvironment.from(options);
        var zone = ZoneId.systemDefault();
        Instant noon = LocalDate.of(2026, 9, 13).atTime(12, 0).atZone(zone).toInstant();
        return new AppEnvironment(options, env.appHome(), env.cashMemory(), AppClock.of(Clock.fixed(noon, zone), options.today()));
    }

    /** Создаёт исходный снимок пользовательскими действиями предыдущего реального контроллера. */
    private void prepareSnapshot(AppEnvironment env) throws Exception {
        var previous = new ModelUiDriver(env, ClientProfile.fx("25"));
        try {
            previous.execute(new SelfTestCommand.Key(ru.cashprediction.core.ui.command.KeyChord.parse("Esc")));
            previous.execute(new SelfTestCommand.Sample());
            previous.execute(new SelfTestCommand.Save());
            if (env.options().selftest().equals("s17-recovery-dialog")) {
                previous.execute(new SelfTestCommand.Menu("edit.planSettings"));
                previous.execute(new SelfTestCommand.Fill("last", Map.of("startBalance", "bad")));
            }
            // Пользовательское ожидание даёт периодическому рекордеру записать реальный снимок.
            previous.advance(java.time.Duration.ofSeconds(5));
            var store = env.xmlStore("fx");
            assertTrue(store.load().isPresent(), "previous controller must produce snapshot");
            store.save(store.load().orElseThrow().withSavedAt(env.clock().now()));
        } finally { previous.stopTimers(); }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void realControllerEqualsReviewedGolden(String name) throws Exception {
        AppEnvironment env = environment(name);
        Process held = null;
        if (name.equals("s17-recovery-dialog") || name.equals("s18-already-running")) prepareSnapshot(env);
        if (name.equals("s18-already-running")) {
            String classpath = Path.of(SelfTestProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
            held = new ProcessBuilder(java.toString(), "-cp", classpath, SelfTestProcess.class.getName()).start();
            assertTrue(held.isAlive());
            env.xmlStore("fx").markDirty(SessionMarker.running(held.pid(), held.toHandle().info().startInstant().orElseThrow(), "fx"));
            assertEquals(CrashDetector.Status.ALREADY_RUNNING, CrashDetector.detect(List.of(env.xmlStore("fx")), "fx").status());
        }
        var driver = new ModelUiDriver(env, ClientProfile.fx("25"));
        Path output = temporary.resolve("output");
        CompletableFuture<Void> external = null;
        if (name.equals("s14-save-conflicts")) {
            external = CompletableFuture.runAsync(() -> {
                Path signal = output.resolve("external-change");
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                    while (!Files.exists(signal)) {
                        if (System.nanoTime() > deadline) throw new AssertionError("external-change signal absent");
                        Thread.sleep(10);
                    }
                    Path file = env.cashMemory().resolve(SamplePlan.name() + ".md");
                    Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2000));
                    Files.delete(signal);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
        try {
            var script = SelfTestScript.load(name);
            var report = new SelfTestRunner(driver, output).run(script);
            assertTrue(report.ok(), () -> report.steps().stream().filter(s -> !s.ok())
                    .map(s -> s.number() + " " + s.text() + ": " + s.message()).collect(java.util.stream.Collectors.joining("\n")));
            if (external != null) external.get(5, TimeUnit.SECONDS);
            assertScenarioEvidence(name, output.resolve(name));
            Path goldenRoot = Path.of("src/test/resources/ui-golden");
            if (!Files.isDirectory(Path.of("src/main/java"))) goldenRoot = Path.of("core/src/test/resources/ui-golden");
            for (var line : script.lines()) {
                if (!(line.command() instanceof SelfTestCommand.Dump dump)) continue;
                Path actual = output.resolve(name).resolve(dump.step() + ".json");
                String json = Files.readString(actual);
                Path golden = goldenRoot.resolve(name).resolve(dump.step() + ".json");
                if (Boolean.getBoolean("cashprediction.golden.update")) {
                    // Порядок ключей Map не является изменением интерфейса: не создаём ложный diff между JVM.
                    if (!Files.isRegularFile(golden) || !DumpDiff.diff(
                            JsonParser.parse(Files.readString(golden)), JsonParser.parse(json), 0).isEmpty()) {
                        Files.createDirectories(golden.getParent());
                        Files.writeString(golden, json);
                    }
                } else {
                    assertTrue(Files.isRegularFile(golden), "missing reviewed golden: " + golden);
                    var differences = DumpDiff.diff(JsonParser.parse(Files.readString(golden)), JsonParser.parse(json), 0);
                    assertTrue(differences.isEmpty(), () -> name + "/" + dump.step() + ": " + differences);
                }
            }
        } finally {
            driver.stopTimers();
            if (held != null) {
                held.getOutputStream().close();
                if (!held.waitFor(5, TimeUnit.SECONDS)) { held.destroyForcibly(); assertTrue(held.waitFor(5, TimeUnit.SECONDS)); }
            }
        }
    }

    /** Проверяет пользовательские результаты независимо от наличия или обновления эталонов. */
    private static void assertScenarioEvidence(String name, Path output) throws Exception {
        switch (name) {
            case "s01-first-run" -> {
                assertEquals("NEW_PLAN_WIZARD", first(dump(output, "wizard"), "windows").get("type"));
                invalid(output, "invalid-name");
                assertTrue(Json.list(dump(output, "created"), "windows").isEmpty());
                assertTrue(Json.string(Json.object(dump(output, "created"), "frame"), "title", "").contains("Первый план"));
            }
            case "s02-sample-table" -> {
                assertTrue(rows(output, "table") > 0); assertTrue(rows(output, "expenses-only") < rows(output, "three-months"));
                assertTrue(rows(output, "no-totals") < rows(output, "three-months"));
            }
            case "s03-chart" -> {
                assertTrue(Json.longValue(Json.object(dump(output, "chart"), "chart"), "markerCount", 0) > 0);
                assertEquals(0, Json.longValue(Json.object(dump(output, "no-markers"), "chart"), "markerCount", -1));
                assertTrue(Json.longValue(Json.object(dump(output, "month-bars"), "chart"), "barCount", 0) > 0);
                assertEquals("dayCard", first(dump(output, "day-card"), "popups").get("kind"));
            }
            case "s04-context-menus" -> {
                for (String step : List.of("rule-context", "start-context", "total-context", "past-context", "card-context", "chart-context", "chart-outside"))
                    assertFalse(Json.list(first(dump(output, step), "contextMenus"), "items").isEmpty(), step);
                var outside = first(dump(output, "chart-outside"), "contextMenus");
                assertFalse(Json.bool(Json.asObject(Json.list(outside, "items").getFirst(), "item"), "enabled", true));
            }
            case "s05-forms-plan" -> { invalid(output, "invalid-balance"); invalid(output, "invalid-target"); assertTrue(visibleStatus(dump(output, "goal-whatif"), "whatIf")); }
            case "s06-forms-ops" -> {
                invalid(output, "income-invalid"); invalid(output, "onetime-invalid"); invalid(output, "adjustment-invalid");
                assertTrue(Files.readString(output.resolve("income-added.json")).contains("Новый доход"));
                assertTrue(Json.list(dump(output, "adjusted"), "windows").isEmpty());
                var nested = Json.list(dump(output, "nested-adjustment"), "windows"); assertEquals(2, nested.size());
                assertEquals(Json.asObject(nested.getFirst(), "parent").get("id"), Json.asObject(nested.getLast(), "child").get("ownerId"));
                var parent = first(dump(output, "parent-editor"), "windows");
                assertTrue(Json.list(parent, "preview").get(1).toString().contains(ru.cashprediction.core.ui.text.UiText.get("rule.preview.adjusted")),
                        "saved child adjustment must refresh parent preview marker");
            }
            case "s07-forms-misc" -> {
                for (String step : List.of("rename-invalid", "reconcile-invalid", "horizon-invalid")) invalid(output, step);
                assertEquals("customCurrency", first(dump(output, "custom-currency"), "windows").get("purpose"));
                assertTrue(Json.list(dump(output, "open-file-cancelled"), "alerts").isEmpty());
                assertEquals("info.actualizeNothing", first(dump(output, "actualize-unavailable"), "alerts").get("purpose"));
                assertEquals("info.reconcileUnavailable", first(dump(output, "reconcile-unavailable"), "alerts").get("purpose"));
            }
            case "s08-alerts" -> {
                assertEquals("deleteRule", first(dump(output, "delete-rule"), "alerts").get("purpose"));
                assertEquals("deleteOneTime", first(dump(output, "delete-onetime"), "alerts").get("purpose"));
                assertEquals("actualize", first(dump(output, "actualize"), "alerts").get("purpose"));
                assertEquals("applyWhatIf", first(dump(output, "apply-whatif"), "alerts").get("purpose"));
                assertStatusMessage(dump(output, "snapshots-cleared"), "status.msg.snapshotsCleared");
                assertStatusMessage(dump(output, "snapshot-request"), "status.msg.snapshot");
            }
            case "s09-whatif" -> {
                assertTrue(visibleStatus(dump(output, "whatif-table"), "whatIf"));
                assertFalse(visibleStatus(dump(output, "applied"), "whatIf")); assertFalse(visibleStatus(dump(output, "reset"), "whatIf"));
                assertEquals(1, Json.longValue(Json.object(dump(output, "applied"), "counters"), "whatIf.apply", 0));
            }
            case "s10-filter-empty-states" -> { assertEquals(0, rows(output, "no-match")); assertTrue(rows(output, "salary") > 0); assertTrue(rows(output, "filter-cleared") > rows(output, "salary")); }
            case "s11-past-group-reveal" -> {
                assertTrue(rows(output, "expanded") > rows(output, "collapsed")); assertEquals(rows(output, "collapsed"), rows(output, "collapsed-again"));
                assertFalse(Json.string(Json.object(dump(output, "revealed"), "table"), "selectedRowId", "").isEmpty());
            }
            case "s12-quick-edit" -> {
                invalid(output, "invalid-quick"); assertEquals("QUICK_EDIT_POPUP", first(dump(output, "invalid-quick"), "windows").get("type"));
                assertTrue(Json.list(dump(output, "quick-applied"), "windows").isEmpty());
                assertEquals(Json.object(dump(output, "quick-applied"), "table").get("rowsDigest"), Json.object(dump(output, "quick-cancelled"), "table").get("rowsDigest"));
                assertNotEquals(Json.object(dump(output, "quick-applied"), "table").get("rowsDigest"), Json.object(dump(output, "correction-removed"), "table").get("rowsDigest"));
            }
            case "s13-undo-redo" -> {
                var counters = Json.object(dump(output, "redo-settings"), "counters");
                assertTrue(Json.longValue(counters, "edit.undo", 0) >= 3); assertTrue(Json.longValue(counters, "edit.redo", 0) >= 2);
                assertEquals(Json.object(dump(output, "skipped"), "table").get("rowsDigest"), Json.object(dump(output, "redo-skip"), "table").get("rowsDigest"));
            }
            case "s14-save-conflicts" -> {
                assertFalse(Json.list(dump(output, "overwrite"), "alerts").isEmpty());
                assertTrue(Json.list(dump(output, "overwritten"), "alerts").isEmpty());
                assertEquals("externalChange", first(dump(output, "external-conflict"), "alerts").get("purpose"));
                assertTrue(Json.list(dump(output, "reloaded"), "alerts").isEmpty());
            }
            case "s15-keyboard" -> {
                assertEquals("RULE_EDITOR", first(dump(output, "keyboard-income"), "windows").get("type"));
                assertEquals("ADJUSTMENT_EDITOR", first(dump(output, "keyboard-adjustment"), "windows").get("type"));
                assertEquals(1, Json.longValue(Json.object(dump(output, "keyboard-about"), "counters"), "help.about", 0));
            }
            case "s16-exit-dirty" -> { assertEquals(3, Json.list(first(dump(output, "discard-question"), "alerts"), "buttons").size()); assertTrue(Json.list(dump(output, "exit-cancelled"), "alerts").isEmpty()); }
            case "s17-recovery-dialog" -> {
                var before = dump(output, "recovery-before-main"); assertNull(before.get("table")); assertEquals("crashRecovery", first(before, "alerts").get("purpose"));
                assertTrue(rows(output, "restored") > 0); invalid(output, "restored");
                assertEquals("PLAN_SETTINGS", first(dump(output, "restored"), "windows").get("type"));
            }
            case "s18-already-running" -> {
                assertEquals("alreadyRunning", first(dump(output, "already-running"), "alerts").get("purpose"));
                assertTrue(Json.list(dump(output, "recording-off"), "alerts").isEmpty());
                assertEquals("info.recordingOff", first(dump(output, "recording-off-info"), "alerts").get("purpose"));
            }
            default -> throw new AssertionError(name);
        }
    }
    private static Map<String, Object> dump(Path output, String step) throws Exception { return Json.asObject(JsonParser.parse(Files.readString(output.resolve(step + ".json"))), "dump"); }
    private static Map<String, Object> first(Map<String, Object> tree, String key) { return Json.asObject(Json.list(tree, key).getFirst(), key); }
    private static long rows(Path output, String step) throws Exception { return Json.longValue(Json.object(dump(output, step), "table"), "rowCount", -1); }
    private static void invalid(Path output, String step) throws Exception {
        var window = first(dump(output, step), "windows"); assertFalse(Json.string(window, "problem", "").isEmpty(), step);
        Json.list(window, "buttons").stream().map(b -> Json.asObject(b, "button")).filter(b -> Json.bool(b, "isDefault", false))
                .forEach(b -> assertFalse(Json.bool(b, "enabled", true), step));
    }
    private static boolean visibleStatus(Map<String, Object> tree, String id) {
        return Json.list(tree, "status").stream().map(s -> Json.asObject(s, "status")).anyMatch(s -> id.equals(s.get("id")) && Json.bool(s, "visible", false));
    }
    private static void assertStatusMessage(Map<String, Object> tree, String key) {
        var message = Json.list(tree, "status").stream().map(s -> Json.asObject(s, "status"))
                .filter(s -> "message".equals(s.get("id"))).findFirst().orElseThrow();
        assertEquals(ru.cashprediction.core.ui.text.UiText.get(key), message.get("text"));
    }
}
