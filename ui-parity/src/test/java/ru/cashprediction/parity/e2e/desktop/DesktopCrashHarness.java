package ru.cashprediction.parity.e2e.desktop;

import java.awt.GraphicsEnvironment;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.prefs.Preferences;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.codec.JsonSnapshotCodec;
import ru.cashprediction.core.session.store.RegistrySessionStore;
import ru.cashprediction.core.session.store.XmlSessionStore;
import ru.cashprediction.core.ui.selftest.SelfTestScript;
import ru.cashprediction.parity.launch.*;
import ru.cashprediction.parity.registry.RegistryNodeCleaner;
import static org.junit.jupiter.api.Assertions.*;

/** Изолированный стенд: ввод через CPS, наблюдение штатных хранилищ и уничтожение только своих процессов. */
final class DesktopCrashHarness implements AutoCloseable {
    /** Модальные окна разделены на наборы, которые реально можно открыть одновременно. */
    enum Cohort { NESTED, QUICK, WIZARD }

    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private static final String SENTINEL = "CrashSentinel731";
    private final String client;
    private final String node = RegistryNodeCleaner.newSelftestNode();
    private final Path root;
    private final Path home;
    private final AppEnvironment environment;
    private final RegistrySessionStore registry;
    private final XmlSessionStore xml;
    private final Object realRegistry;
    private final List<LaunchedClient> processes = new ArrayList<>();

    /** Создаёт UUID-папку и узел; не меняет пользовательские session-ветки. */
    DesktopCrashHarness(String client) throws Exception {
        assertTrue(System.getProperty("os.name").startsWith("Windows"), "E2E requires Windows");
        assertFalse(GraphicsEnvironment.isHeadless(), "Enabled desktop gate requires an interactive desktop");
        this.client = client;
        realRegistry = RegistryNodeCleaner.snapshotRealSessionNodes();
        // CI собирает parityRoot: UUID сохраняет независимость запусков, журналы остаются после cleanup.
        root = Files.createDirectories(ReactorLayout.fromSystemProperties().parityRoot()
                .resolve("s4-desktop-crash-" + client + "-" + UUID.randomUUID()));
        home = root.resolve("application");
        environment = AppEnvironment.from(LaunchOptions.parse("--home", home.toString(), "--registry-node", node,
                "--today", LaunchRequest.PARITY_TODAY.toString()));
        registry = environment.registryStore(client);
        xml = environment.xmlStore(client);
        if (!registry.isAvailable()) {
            RegistryNodeCleaner.delete(node);
            fail("Isolated registry unavailable: " + registry.unavailableReason());
        }
    }

    /** Два запуска используют один home/node, но различные сценарии, журналы и папки наблюдений. */
    void run(String source, Cohort cohort) throws Exception {
        Files.writeString(root.resolve("case.txt"), client + "/" + source + "/" + cohort + "\n");
        String beforeScript = setup(cohort) + "shot before\nsignal committed\n";
        LaunchedClient first = launch("before", beforeScript, null);
        Path beforeOut = root.resolve("before-out");
        first.waitForFile(beforeOut.resolve("committed"), TIMEOUT);
        Map<String, Object> beforeRaw = raw(beforeOut, "before", "before");
        assertCohort(beforeRaw, cohort);
        // Ошибку настоящего ввода показываем до ожидания снимков: не выдаём
        // неуспешный CPS fill за неисправность хранилища через 45 секунд.
        assertPrefix(beforeOut, beforeScript);
        first.waitUntil(() -> committed(cohort), TIMEOUT, "both committed snapshots with sentinel fields");
        SessionSnapshot before = registry.load().orElseThrow();
        assertEquals(before, xml.load().orElseThrow(), "Stores must contain the same committed snapshot");
        Files.writeString(root.resolve("before.snapshot.json"), new JsonSnapshotCodec().encode(before));
        assertMainConfigured(before.main());
        assertEquals(cohort == Cohort.QUICK ? "TABLE" : "CHART", before.main().view());
        assertTrue(first.process().isAlive(), "First launch must die externally, not finish itself");
        first.kill().requireClean();
        preserveLogs(first, "first-launch");
        assertTrue(registry.readMarker().isPresent(), "Forced death must preserve registry marker");
        assertTrue(xml.readMarker().isPresent(), "Forced death must preserve XML marker");
        if (source.equals("xml")) corruptRegistryOnly();
        if (source.equals("registry")) corruptXmlOnly(before);

        String afterScript = "shot restored\nsignal inspected\n";
        LaunchedClient second = launch("after", afterScript, source);
        Path afterOut = root.resolve("after-out");
        second.waitForFile(afterOut.resolve("inspected"), TIMEOUT);
        Map<String, Object> afterRaw = raw(afterOut, "after", "restored");
        assertPrefix(afterOut, afterScript);
        if (source.equals("none")) {
            assertNone(afterRaw);
            second.waitUntil(() -> oldPayloadGone(before), TIMEOUT, "old payload cleared in both stores");
        } else {
            second.waitUntil(() -> freshCommitted(cohort, before),
                    TIMEOUT, "new restored state committed, not the pre-crash payload");
            SessionSnapshot after = registry.load().orElseThrow();
            compareSnapshots(before, after);
            compareRaw(beforeRaw, afterRaw);
        }
        second.kill().requireClean();
        preserveLogs(second, "second-launch");
    }

    /** Отдельно подтверждает штатный halt при доступном главном меню и отсутствие корректного завершения. */
    void confirmedHalt() throws Exception {
        String prefix = "today 2026-09-13\nkey Esc\nsample\nshot accessible\nsignal armed\n";
        String script = prefix + "crash\nanswer \"" + ru.cashprediction.core.ui.text.UiText.get("button.halt") + "\"\n";
        LaunchedClient process = launch("halt", script, null);
        Path out = root.resolve("halt-out");
        process.waitForFile(out.resolve("armed"), TIMEOUT);
        assertPrefix(out, prefix);
        assertTrue(windows(raw(out, "halt", "accessible")).isEmpty(), "Halt menu must not be behind a modal form");
        process.waitUntil(() -> {
            try { return registry.load().isPresent() && xml.load().isPresent(); }
            catch (Exception transientRead) { return false; }
        }, TIMEOUT, "halt snapshot committed");
        var marker = registry.readMarker().orElseThrow();
        Files.delete(out.resolve("armed"));
        assertTrue(process.process().waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Confirmed halt must exit");
        assertEquals(3, process.process().exitValue());
        String log = Files.readString(out.resolve("selftest.log"));
        assertFalse(log.contains(" FAIL ")); assertFalse(log.contains("DONE"));
        assertTrue(log.contains(" OK crash\n"), "Crash confirmation must be opened through the real menu");
        assertEquals(marker, registry.readMarker().orElseThrow(), "Halt must not mark session clean");
        process.kill().requireClean(); preserveLogs(process, "halt-launch");
    }

    /** Настраивает главное окно до открытия модальных форм; никакие команды за модальным окном не вызываются. */
    private static String setup(Cohort cohort) {
        String base = "today 2026-09-13\nkey Esc\nsample\nsize 1200 800\nrowclick past@group\n"
                + "select r1@2026-10-05\nfiltertype \"Зарплата\"\nfilter showOneTime=false\nfilter chartMarkers=false\nfilter chartBars=true\n"
                + "menu whatIf.income\nmenu whatIf.expense\nspinner whatIf.extra 7319\nperiod ALL\nview CHART\n";
        return base + switch (cohort) {
            case NESTED -> "menu tools.goal\nfill last target=\"450731\" extraSaving=\"bad731\"\n"
                    + "menu edit.edit\nfill last title=\"" + SENTINEL + "\" amount=\"25731\"\n"
                    + "context preview:last:1\nmenu ctx.preview.adjust\nfill last amount=\"bad732\"\n";
            case QUICK -> "menu tools.goal\nfill last target=\"450731\" extraSaving=\"bad731\"\n"
                    + "view TABLE\ndblclick r1@2026-10-05 income\nfill last amount=\"bad733\"\n";
            case WIZARD -> "menu file.new\nanswer \"Не сохранять\"\nfill last name=\"" + SENTINEL + "\"\n"
                    + "button \"Новый план\" \"Далее ›\"\nfill last startBalance=\"bad734\"\n";
        };
    }

    /** Запускает настоящий модуль; журналы первого запуска копируются до повторного старта. */
    private LaunchedClient launch(String name, String text, String recovery) throws Exception {
        Path script = root.resolve(name + ".cps");
        SelfTestScript.parse(name, text);
        Files.writeString(script, text);
        var request = new LaunchRequest(client, name, home, node, LaunchRequest.PARITY_TODAY,
                script.toString(), root.resolve(name + "-out"),
                recovery == null ? List.of() : List.of("--selftest-recovery", recovery), List.of());
        var layout = ReactorLayout.fromSystemProperties();
        LaunchedClient process = ClientLauncher.launch(client.equals("fx") ? ClientTarget.fx(layout) : ClientTarget.swing(layout), request);
        processes.add(process);
        return process;
    }

    /** Ждёт содержимое committed snapshot, а не произвольную задержку или один факт существования файла. */
    private boolean committed(Cohort cohort) {
        try {
            SessionSnapshot a = registry.load().orElseThrow(), b = xml.load().orElseThrow();
            int expected = cohort == Cohort.NESTED ? 3 : cohort == Cohort.QUICK ? 2 : 1;
            String payload = a.windows().toString();
            boolean typed = switch (cohort) {
                case NESTED -> payload.contains(SENTINEL) && payload.contains("25731")
                        && payload.contains("450731") && payload.contains("bad731") && payload.contains("bad732");
                case QUICK -> payload.contains("450731") && payload.contains("bad731") && payload.contains("bad733");
                case WIZARD -> payload.contains(SENTINEL) && payload.contains("bad734")
                        && a.windows().getFirst().contextValue("page").equals("1");
            };
            return a.equals(b) && a.windows().size() == expected && typed;
        } catch (Exception transientRead) { return false; }
    }

    /** Не принимает оставшийся снимок первого процесса за результат записи после восстановления. */
    private boolean freshCommitted(Cohort cohort, SessionSnapshot before) {
        try { return committed(cohort) && registry.load().orElseThrow().savedAt().isAfter(before.savedAt()); }
        catch (Exception transientRead) { return false; }
    }

    /** Повреждает только один кусок snapshot; остальные значения, включая running marker, неизменны. */
    private void corruptRegistryOnly() throws Exception {
        var marker = registry.readMarker();
        SessionSnapshot independent = xml.load().orElseThrow();
        Preferences prefs = Preferences.userRoot().node(registry.nodePath());
        prefs.put("snapshot.0", "corrupt"); prefs.flush();
        assertThrows(SessionStoreException.class, registry::load);
        assertEquals(marker, registry.readMarker());
        assertEquals(independent, xml.load().orElseThrow());
    }

    /** После гибели процесса повреждает только альтернативный XML, сохраняя снимок и running-маркер реестра. */
    private void corruptXmlOnly(SessionSnapshot before) throws Exception {
        SessionMarker marker = registry.readMarker().orElseThrow();
        assertTrue(marker.isRunning(), "Registry marker must still indicate a crashed session");
        assertEquals(before, registry.load().orElseThrow(), "Chosen committed snapshot must survive process death");
        assertEquals(before, xml.load().orElseThrow(), "Alternative must be valid before corruption");
        // Файл берётся из штатного хранилища; после kill писателей уже нет, реестр не изменяется.
        Files.writeString(xml.file(), "corrupt");
        assertThrows(SessionStoreException.class, xml::load, "Corrupt alternative XML must be unreadable");
        assertEquals(marker, registry.readMarker().orElseThrow(), "XML corruption must preserve registry marker");
        assertEquals(before, registry.load().orElseThrow(), "XML corruption must preserve chosen committed snapshot");
        // Маркер XML читается вместе со снимком, поэтому предложение восстановления опирается на реестр.
        CrashDetector.Detection detection = CrashDetector.detect(List.of(registry, xml), client);
        assertEquals(CrashDetector.Status.CRASHED, detection.status());
        assertEquals(marker, detection.findMarker().orElseThrow());
        assertTrue(detection.stores().get("registry").restorable(), "Registry recovery must remain offered");
        assertEquals(Optional.of(before.savedAt()), detection.stores().get("registry").snapshotAt());
        assertFalse(detection.stores().get("xml").restorable(), "Corrupt XML recovery must be disabled");
        assertFalse(detection.stores().get("xml").problem().isEmpty(), "Corrupt XML must report a read problem");
    }

    /** Проверяет сохранённые параметры главного окна, включая дополнительную экономию. */
    private static void assertMainConfigured(MainWindowState main) {
        assertEquals("ALL", main.period()); assertEquals("Зарплата", main.filterText());
        assertEquals("r1@2026-10-05", main.selectedRowId());
        assertEquals(Boolean.TRUE, main.filters().get("pastExpanded"));
        assertEquals(Boolean.TRUE, main.filters().get("whatIfIncome"));
        assertEquals(Boolean.TRUE, main.filters().get("whatIfExpense"));
        assertEquals(Boolean.FALSE, main.filters().get("showOneTime"));
        assertEquals(Boolean.FALSE, main.filters().get("chartMarkers"));
        assertEquals(Boolean.TRUE, main.filters().get("chartBars"));
        assertEquals("7319,00", main.whatIfExtra());
    }

    /** Строит явную биекцию по типу и контексту, проверяет поля, модальность и граф владельцев. */
    private static void compareSnapshots(SessionSnapshot before, SessionSnapshot after) {
        MainWindowState a = before.main(), b = after.main();
        assertEquals(a.view(), b.view()); assertEquals(a.period(), b.period()); assertEquals(a.filters(), b.filters());
        assertEquals(a.filterText(), b.filterText()); assertEquals(a.selectedRowId(), b.selectedRowId());
        assertEquals(a.whatIfExtra(), b.whatIfExtra()); assertEquals(a.maximized(), b.maximized());
        assertEquals(a.planPath(), b.planPath()); assertEquals(before.plan(), after.plan()); bounds(a.bounds(), b.bounds());
        assertEquals(before.windows().size(), after.windows().size());
        Map<String, String> ids = new HashMap<>(); ids.put("main", "main"); ids.put("", "");
        for (WindowState old : before.windows()) {
            List<WindowState> matches = after.windows().stream().filter(w -> w.type() == old.type()
                    && w.context().equals(old.context())).toList();
            assertEquals(1, matches.size(), "Ambiguous window identity: " + old);
            WindowState restored = matches.getFirst();
            assertFalse(ids.containsValue(restored.id()), "Window mapping must be bijective");
            ids.put(old.id(), restored.id());
            assertEquals(old.fields(), restored.fields()); assertEquals(old.modal(), restored.modal());
            bounds(old.bounds(), restored.bounds());
        }
        for (WindowState old : before.windows()) {
            WindowState restored = after.windows().stream().filter(w -> w.id().equals(ids.get(old.id()))).findFirst().orElseThrow();
            assertEquals(ids.get(old.ownerId()), restored.ownerId(), "Owner graph changed");
        }
    }

    /** Измеренные content bounds проверяются отдельно от persisted screen bounds без нормализации. */
    private static void compareRaw(Map<String, Object> before, Map<String, Object> after) {
        var beforeFrame = (Map<?, ?>) before.get("frame"); var afterFrame = (Map<?, ?>) after.get("frame");
        for (String key : List.of("contentWidth", "contentHeight"))
            assertEquals(((Number) beforeFrame.get(key)).doubleValue(), ((Number) afterFrame.get(key)).doubleValue(), 4,
                    "Actual main content " + key);
        assertEquals(before.get("chart"), after.get("chart"), "Actual chart presentation changed");
        var beforeTable = (Map<?, ?>) before.get("table"); var afterTable = (Map<?, ?>) after.get("table");
        for (String key : List.of("selectedRowId", "rowCount", "rowsDigest")) {
            assertTrue(beforeTable.containsKey(key), "Missing raw table property " + key);
            assertEquals(beforeTable.get(key), afterTable.get(key), "Actual table " + key);
        }
        List<Map<String, Object>> old = windows(before), current = windows(after);
        assertEquals(old.size(), current.size());
        Map<String, String> ids = new HashMap<>(); ids.put("main", "main"); ids.put("", "");
        for (var window : old) {
            var matches = current.stream().filter(w -> Objects.equals(w.get("type"), window.get("type"))
                    && Objects.equals(w.get("purpose"), window.get("purpose"))).toList();
            assertEquals(1, matches.size()); var restored = matches.getFirst();
            assertFalse(ids.containsValue((String) restored.get("id")));
            ids.put((String) window.get("id"), (String) restored.get("id"));
            for (String key : List.of("type", "purpose", "page", "modal", "fields", "problem"))
                assertEquals(window.get(key), restored.get(key), "Raw window " + key);
            box(window.get("bounds"), restored.get("bounds"));
            assertEquals(-1, ((Number) restored.get("previewSelected")).intValue(), "Preview selection resets intentionally");
        }
        for (var w : old) {
            var restored = current.stream().filter(v -> Objects.equals(v.get("id"), ids.get(w.get("id")))).findFirst().orElseThrow();
            assertEquals(ids.get(w.get("ownerId")), restored.get("ownerId"));
        }
    }

    /** Требует настоящие окна соответствующего набора и ненулевую страницу мастера. */
    private static void assertCohort(Map<String, Object> raw, Cohort cohort) {
        Set<String> expected = switch (cohort) {
            case NESTED -> Set.of("GOAL_CALCULATOR", "RULE_EDITOR", "ADJUSTMENT_EDITOR");
            case QUICK -> Set.of("GOAL_CALCULATOR", "QUICK_EDIT_POPUP");
            case WIZARD -> Set.of("NEW_PLAN_WIZARD");
        };
        assertEquals(expected, new HashSet<>(windows(raw).stream().map(w -> (String) w.get("type")).toList()));
        if (cohort == Cohort.WIZARD) assertEquals(1, ((Number) windows(raw).getFirst().get("page")).intValue());
        if (cohort == Cohort.NESTED) {
            var rule = windows(raw).stream().filter(w -> w.get("type").equals("RULE_EDITOR")).findFirst().orElseThrow();
            assertEquals(1, ((Number) rule.get("previewSelected")).intValue(), "Reset proof requires a real pre-crash selection");
        }
    }

    /** Отказ не сохраняет прежние формы; новый стартовый мастер разрешён. */
    private static void assertNone(Map<String, Object> raw) {
        assertFalse(raw.toString().contains(SENTINEL));
        assertFalse(raw.toString().contains("bad73"));
        assertTrue(windows(raw).stream().allMatch(w -> w.get("type").equals("NEW_PLAN_WIZARD")));
        for (var window : windows(raw)) assertEquals(0, ((Number) window.get("page")).intValue(),
                "None may open a fresh wizard, not resume the old nonzero page");
    }

    /** Новые маркеры разрешены, но оба старых payload должны исчезнуть. */
    private boolean oldPayloadGone(SessionSnapshot before) {
        try {
            for (SessionStore store : List.of(registry, xml)) {
                var snapshot = store.load();
                if (snapshot.isPresent()) {
                    String text = snapshot.get().toString();
                    if (text.contains(SENTINEL) || text.contains("bad73") || text.contains("7319,00")) return false;
                    SessionSnapshot current = snapshot.get();
                    // Новый документ без файла штатно сохраняется как dirty; запрещён прежний текст, а не dirty как таковой.
                    if (before.plan().dirty() && current.plan().markdown().equals(before.plan().markdown())) return false;
                    if (!current.main().whatIfExtra().isEmpty() || !current.main().selectedRowId().isEmpty()
                            || !current.main().filterText().isEmpty()) return false;
                    if (Boolean.TRUE.equals(current.main().filters().get("pastExpanded"))
                            || Boolean.TRUE.equals(current.main().filters().get("whatIfIncome"))
                            || Boolean.TRUE.equals(current.main().filters().get("whatIfExpense"))) return false;
                    if (current.windows().stream().anyMatch(w -> w.type() != WindowType.NEW_PLAN_WIZARD
                            || !w.contextValue("page").equals("0"))) return false;
                }
            }
            return true;
        } catch (Exception unreadable) { return false; }
    }

    /** Читает raw sidecar реального Shot, без модельного драйвера и округления координат. */
    private static Map<String, Object> raw(Path out, String scenario, String name) throws Exception {
        return JsonParser.parseObject(Files.readString(out.resolve(scenario).resolve(name + ".raw.json")));
    }

    /** Типизированный список реальных окон; неверная схема падает, а не превращается в пустой список. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> windows(Map<String, Object> raw) {
        assertInstanceOf(List.class, raw.get("windows"));
        return (List<Map<String, Object>>) raw.get("windows");
    }

    /** Требует точный успешный prefix до заблокированного signal, без DONE и FAIL. */
    private static void assertPrefix(Path out, String source) throws Exception {
        StringBuilder expected = new StringBuilder();
        var lines = SelfTestScript.parse("proof", source).lines();
        for (var line : lines.subList(0, lines.size() - 1))
            expected.append("SELFTEST ").append(line.number()).append(" OK ").append(line.text()).append('\n');
        assertEquals(expected.toString(), Files.readString(out.resolve("selftest.log")));
    }

    /** Сохраняет логи до повторного запуска, который штатно использует те же имена stdout/stderr. */
    private void preserveLogs(LaunchedClient process, String name) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.copy(process.stdout(), dir.resolve("stdout.log")); Files.copy(process.stderr(), dir.resolve("stderr.log"));
    }

    /** Сравнивает экранную геометрию с допуском спецификации, не смешивая её с content bounds. */
    private static void bounds(WindowBounds a, WindowBounds b) {
        assertNotNull(a); assertNotNull(b);
        assertEquals(a.x(), b.x(), 4); assertEquals(a.y(), b.y(), 4);
        assertEquals(a.width(), b.width(), 4); assertEquals(a.height(), b.height(), 4);
    }

    /** Сравнивает четыре реально измеренные координаты JSON-прямоугольника. */
    private static void box(Object a, Object b) {
        assertInstanceOf(Map.class, a); assertInstanceOf(Map.class, b);
        for (String key : List.of("x", "y", "width", "height"))
            assertEquals(((Number) ((Map<?, ?>) a).get(key)).doubleValue(),
                    ((Number) ((Map<?, ?>) b).get(key)).doubleValue(), 4, "Raw bounds " + key);
    }

    /** Завершает свои деревья и удаляет UUID-узел; audit-файлы оставляет для CI и разбора ошибок. */
    @Override public void close() {
        Throwable failure = null;
        try {
            for (LaunchedClient process : processes) {
                try { process.close(); }
                catch (RuntimeException | AssertionError error) {
                    if (failure == null) failure = error; else failure.addSuppressed(error);
                }
            }
        } finally {
            try { RegistryNodeCleaner.delete(node); assertFalse(RegistryNodeCleaner.exists(node)); }
            finally { assertEquals(realRegistry, RegistryNodeCleaner.snapshotRealSessionNodes()); }
        }
        if (failure != null) throw new AssertionError("Owned process cleanup failed", failure);
    }
}
