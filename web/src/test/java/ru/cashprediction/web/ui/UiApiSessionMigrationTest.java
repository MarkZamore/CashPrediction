package ru.cashprediction.web.ui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.session.store.MarkdownSessionStore;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.web.ServerLog;
import ru.cashprediction.web.WebServer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Перенос покрытия серверных сессий на настоящий новый UiApi, рекордер и Markdown-хранилище.
 *
 * <p>Действия выполняются через HTTP; прямой доступ к потоку используется для чтения,
 * детерминированного saveNow и закрытия владельца на границе жизненного цикла.
 * Сбой моделируется сохранением реально захваченного
 * снимка и маркером отсутствующего PID после освобождения сервера. Это тест ветвей хранения
 * и восстановления, не доказательство внешнего force-kill или браузерной видимости.</p>
 */
@Timeout(30)
class UiApiSessionMigrationTest {
    @TempDir Path home;
    private WebServer server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private long seq, revision;

    /** Освобождает только сервер и каталог этого теста; настоящий реестр не используется. */
    @AfterEach void close() { if (server != null) server.stop(); http.close(); }

    /** Существующее правило сохраняет контекст, заголовок и исходный невалидный ввод после HTTP reload. */
    @Test void shownExistingRulePreservesContextTitleAndRawInvalidFieldsOnReload() throws Exception {
        freshSample();
        String title = read(() -> server.coreRuntime().controller().document().plan().rules().getFirst().title());
        String id = openRule();
        var before = form(id);
        assertFalse(Json.requireString(obj(before.get("spec")), "windowTitle").isBlank());
        assertTrue(Json.requireString(obj(before.get("view")), "header").contains(title));
        assertEquals(title, fieldValue(before, "title"));
        field(id, "amount", "12,3,4"); field(id, "title", "Migration draft");
        var reload = form(id);
        assertEquals("12,3,4", fieldValue(reload, "amount"));
        assertEquals("Migration draft", fieldValue(reload, "title"));
        WindowState captured = read(() -> server.coreRuntime().port().form(id).orElseThrow().captureState());
        assertEquals("r1", captured.contextValue(WindowType.CONTEXT_RULE_ID));
        assertEquals(WindowType.RULE_EDITOR, captured.type());
        assertEquals("main", captured.ownerId());
        assertTrue(captured.modal());
        assertEquals("12,3,4", captured.fields().get("amount"));
        assertEquals(before.get("spec"), reload.get("spec"));
        assertEquals(obj(before.get("view")).get("header"), obj(reload.get("view")).get("header"));
        assertFalse(Boolean.TRUE.equals(obj(obj(obj(reload.get("view")).get("buttons")).get("ok")).get("enabled")), "Invalid amount cannot be committed");
    }

    /** Реальный preview открывает вложенную корректировку; закрытие родителя закрывает и ребёнка. */
    @Test void shownNestedAdjustmentHasParentOwnerAndClosingParentCascades() throws Exception {
        freshSample(); String parent = openRule();
        intent(Map.of("type", "formPreview", "windowId", parent, "index", 1, "activated", true));
        String child = lastForm(WindowType.ADJUSTMENT_EDITOR);
        shown(child); field(child, "amount", "bad-child");
        assertEquals(parent, form(child).get("ownerId"));
        SessionSnapshot captured = snapshot();
        var childState = captured.window(child).orElseThrow();
        assertEquals(parent, childState.ownerId());
        assertEquals("r1", childState.contextValue(WindowType.CONTEXT_RULE_ID));
        assertFalse(childState.contextValue(WindowType.CONTEXT_ORIGINAL_DATE).isBlank());
        assertEquals("bad-child", childState.fields().get("amount"));
        var blocked = intent(Map.of("type", "formClose", "windowId", parent));
        assertTrue(list(blocked.get("effects")).stream().map(UiApiSessionMigrationTest::obj)
                .noneMatch(effect -> "form.close".equals(effect.get("type"))));
        assertEquals(2, forms().size(), "HTTP cannot close a disabled modal owner");
        // Принудительное закрытие владельца проверяет штатный каскад ядра, не обход HTTP-модальности.
        // Сам HTTP обязан отклонять закрытие родителя, пока активен модальный ребёнок.
        read(() -> { server.coreRuntime().port().form(parent).orElseThrow().closeRequested(); return null; });
        var closed = request("/api/ui/events?tab=migration&after=" + seq, null);
        var effects = list(closed.get("effects")).stream().map(UiApiSessionMigrationTest::obj).toList();
        assertTrue(effects.stream().anyMatch(effect -> "form.close".equals(effect.get("type")) && parent.equals(effect.get("windowId"))));
        assertTrue(effects.stream().anyMatch(effect -> "form.close".equals(effect.get("type")) && child.equals(effect.get("windowId"))));
        assertTrue(forms().isEmpty());
        assertTrue(snapshot().windows().stream().noneMatch(window -> window.id().equals(parent) || window.id().equals(child)));
    }

    /** Подтверждённая форма и несохранённый план записываются настоящим рекордером в два Markdown-файла. */
    @Test void shownFormWritesRawFieldsAndUnsavedPlanToRealMarkdownSnapshot() throws Exception {
        freshSample(); String id = openRule(); field(id, "amount", "raw-invalid");
        SessionSnapshot captured = snapshot(); MarkdownSessionStore store = store();
        assertTrue(Files.isRegularFile(store.sessionFile()));
        assertTrue(Files.isRegularFile(store.planFile()));
        assertTrue(captured.plan().dirty()); assertFalse(captured.plan().markdown().isBlank());
        assertEquals(captured.plan().markdown(), Files.readString(store.planFile()));
        assertEquals("raw-invalid", captured.window(id).orElseThrow().fields().get("amount"));
        assertEquals("r1", captured.window(id).orElseThrow().contextValue(WindowType.CONTEXT_RULE_ID));
        assertTrue(store.readMarker().orElseThrow().isRunning());
        assertTrue(read(() -> server.coreRuntime().controller().document().file().isEmpty()), "Unsaved plan is not an ordinary saved plan file");
        assertEquals(captured, MarkdownSessionStore.inCashMemory(home.resolve("CashMemory")).load().orElseThrow(), "Independent reader must decode the actual files");
    }

    /** Явный HTTP-выход с отказом от сохранения помечает сеанс чистым и не переоткрывает старые окна. */
    @Test void cleanHttpExitStartsWithoutRecoveryOrOldWindows() throws Exception {
        freshSample(); command("tools.goal");
        String goal = lastForm(WindowType.GOAL_CALCULATOR); shown(goal); field(goal, "target", "old-goal-invalid");
        snapshot(); command("file.exit"); answer("unsavedChanges", AlertCatalog.BUTTON_DONT_SAVE);
        await(() -> store().readMarker().isPresent() && !store().readMarker().orElseThrow().isRunning(), "clean close marker");
        server.stop(); server = null;
        start(); var bootstrap = bootstrap();
        assertNull(bootstrap.get("overlay"));
        assertTrue(forms().stream().noneMatch(window -> WindowType.GOAL_CALCULATOR.name().equals(obj(window.get("spec")).get("windowType"))));
        assertTrue(alerts().stream().noneMatch(alert -> "crashRecovery".equals(obj(alert.get("spec")).get("purpose"))));
        assertTrue(read(() -> server.coreRuntime().controller().document().plan().rules().isEmpty()));
    }

    /** Отказ от непустого снимка действительно открывает свежий план, а не скрывает старые окна. */
    @Test void refusingNonemptyCrashSnapshotDoesNotLoadOldPlanOrEditors() throws Exception {
        freshSample(); String id = openRule(); field(id, "title", "Rejected editor draft");
        SessionSnapshot old = snapshot(); assertFalse(old.windows().isEmpty()); assertTrue(old.plan().dirty());
        crashFixture(old); start(); assertEquals("RECOVERY_PENDING", bootstrap().get("overlay"));
        answer("crashRecovery", AlertCatalog.BUTTON_NO_RESTORE);
        assertNull(bootstrap().get("overlay"));
        assertTrue(forms().stream().noneMatch(window -> WindowType.RULE_EDITOR.name().equals(obj(window.get("spec")).get("windowType"))));
        assertTrue(read(() -> server.coreRuntime().controller().document().plan().rules().isEmpty()));
        closeStartupWizard();
        SessionSnapshot fresh = snapshot();
        assertTrue(fresh.windows().stream().noneMatch(window -> window.fields().containsValue("Rejected editor draft")));
        assertNotEquals(old.plan().markdown(), fresh.plan().markdown());
    }

    /** Очистка через Alert, новый снимок и реальная ветвь server-recovery сохраняют дальнейшее восстановление. */
    @Test void clearSnapshotsThenNewSnapshotCanRecoverFromMarkdown() throws Exception {
        freshSample(); command("tools.goal"); String goal = lastForm(WindowType.GOAL_CALCULATOR); shown(goal);
        field(goal, "target", "after-clear-invalid"); snapshot();
        command("recovery.clear"); answer("clearSnapshots", "clear");
        // clearSnapshots вызывает touch: следующий снимок вправе появиться ещё до HTTP-ответа.
        // Поэтому пустоту файла здесь не проверяем: это гонка с штатным рекордером, а не контракт.
        assertTrue(store().readMarker().orElseThrow().isRunning(), "Clear must retain crash detection");
        field(goal, "target", "new-snapshot-invalid");
        SessionSnapshot next = snapshot(); assertEquals("new-snapshot-invalid", next.window(goal).orElseThrow().fields().get("target"));
        crashFixture(next); start(); assertEquals("RECOVERY_PENDING", bootstrap().get("overlay"));
        answer("crashRecovery", AlertCatalog.BUTTON_RESTORE_SERVER);
        String restored = lastForm(WindowType.GOAL_CALCULATOR); shown(restored);
        assertEquals("new-snapshot-invalid", fieldValue(form(restored), "target"));
        assertEquals(next.plan().markdown(), snapshot().plan().markdown());
    }

    /** Главный экран восстанавливает записанные через HTTP геометрию, вид, флаги и выбранную строку. */
    @Test void mainWindowStateRoundTripsThroughRealMarkdownRecovery() throws Exception {
        freshSample();
        intent(Map.of("type", "mainGeometry", "bounds", Map.of("x", 10, "y", 20, "width", 1280, "height", 800), "maximized", true));
        command("view.chart"); command("view.period.M6"); command("view.flag.showSkipped");
        intent(Map.of("type", "selectRow", "rowId", "r1@2026-10-05"));
        SessionSnapshot captured = snapshot();
        assertEquals("CHART", captured.main().view());
        assertEquals("r1@2026-10-05", captured.main().selectedRowId());
        assertTrue(captured.main().maximized());
        assertEquals(new ru.cashprediction.core.session.WindowBounds(10, 20, 1280, 800), captured.main().bounds());
        assertEquals(Boolean.TRUE, captured.main().filters().get("showSkipped"));
        crashFixture(captured); start(); answer("crashRecovery", AlertCatalog.BUTTON_RESTORE_SERVER);
        assertEquals(captured.main(), snapshot().main());
    }

    /** Настройки вида действительно записываются на диск и читаются новым чистым сеансом. */
    @Test void viewSettingsSurviveCleanExitAndIndependentRestart() throws Exception {
        freshSample(); command("view.chart"); command("view.period.M6"); command("view.flag.showIncome");
        command("file.exit"); answer("unsavedChanges", AlertCatalog.BUTTON_DONT_SAVE);
        await(() -> store().readMarker().isPresent() && !store().readMarker().orElseThrow().isRunning(), "clean settings close");
        var settingsFile = home.resolve("CashMemory/settings.md");
        assertTrue(Files.isRegularFile(settingsFile));
        var saved = ru.cashprediction.core.markdown.SettingsMarkdown.load(settingsFile);
        assertEquals(ru.cashprediction.core.document.ViewMode.CHART, saved.view());
        assertEquals(ru.cashprediction.core.document.PeriodChoice.M6, saved.period());
        assertFalse(saved.showIncome());
        server.stop(); server = null; start(); closeStartupWizard();
        var restored = read(() -> server.coreRuntime().controller().document().viewState());
        assertEquals(saved.view(), restored.mode()); assertEquals(saved.period(), restored.period());
        assertEquals(saved.showIncome(), restored.showIncome());
    }

    /** Справка и диагностика доступны через новые команды, а не через удалённые legacy-маршруты. */
    @Test void formatHelpAndDiagnosticsArePublishedByCoreCommands() throws Exception {
        freshSample(); command("help.format");
        var format = alerts().getLast(); var spec = obj(format.get("spec"));
        assertEquals(ru.cashprediction.core.markdown.MarkdownFormat.userGuide(), spec.get("details"));
        assertFalse(Json.requireString(spec, "details").isBlank());
        String id = Json.requireString(format, "alertId");
        intent(Map.of("type", "alertShown", "windowId", id));
        String button = Json.requireString(obj(list(spec.get("buttons")).getFirst()), "id");
        intent(Map.of("type", "alertButton", "alertId", id, "buttonId", button));
        command("tools.validate");
        assertEquals("validation", obj(alerts().getLast().get("spec")).get("purpose"));
    }

    /** Запускает UiApi над изолированным CashMemory без настоящего реестра и selftest autoanswers. */
    private void start() throws Exception {
        seq = 0;
        var options = LaunchOptions.parse(List.of("--home", home.toString(), "--registry", "memory", "--today", "2026-09-13"), new Properties());
        server = WebServer.startCore(AppEnvironment.from(options), new ServerLog(false), 0, true);
    }

    /** Закрывает мастер первого запуска обычным shown/close и загружает план-пример. */
    private void freshSample() throws Exception { start(); closeStartupWizard(); command("file.sample"); }

    /** Закрывает только реально опубликованный мастер; восстановленные редакторы не затрагиваются. */
    private void closeStartupWizard() throws Exception {
        for (var window : forms()) if (WindowType.NEW_PLAN_WIZARD.name().equals(obj(window.get("spec")).get("windowType"))) {
            String id = Json.requireString(window, "id"); shown(id); intent(Map.of("type", "formClose", "windowId", id));
        }
    }

    /** Открывает редактор существующего правила через реальную строку прогноза и команду меню. */
    private String openRule() throws Exception {
        intent(Map.of("type", "selectRow", "rowId", "r1@2026-10-05")); command("edit.edit");
        String id = lastForm(WindowType.RULE_EDITOR); shown(id); return id;
    }

    /** Снимает состояние штатным рекордером на его потоке и читает независимый файл хранилища. */
    private SessionSnapshot snapshot() throws Exception {
        read(() -> { server.coreRuntime().controller().recorder().saveNow(); return null; });
        return store().load().orElseThrow();
    }

    /** Моделирует только persisted-crash после чистого освобождения ресурсов, не выдавая его за force-kill. */
    private void crashFixture(SessionSnapshot captured) throws Exception {
        server.stop(); server = null;
        store().save(captured);
        store().markDirty(SessionMarker.running(0, Instant.now(), "web"));
        assertEquals(captured, store().load().orElseThrow());
    }

    /** Возвращает новое чтение настоящего серверного Markdown-хранилища. */
    private MarkdownSessionStore store() { return MarkdownSessionStore.inCashMemory(home.resolve("CashMemory")); }

    /** Подтверждает опубликованное окно тем же намерением, что и браузер. */
    private void shown(String id) throws Exception { intent(Map.of("type", "formShown", "windowId", id)); }

    /** Передаёт исходный ввод без форматирования и без фиксации в доменной модели. */
    private void field(String id, String key, String raw) throws Exception {
        intent(Map.of("type", "formField", "windowId", id, "fieldId", key, "raw", raw, "committed", false, "clientRev", ++revision));
    }

    /** Нажимает реальную кнопку опубликованного Alert и подтверждает его показ. */
    private void answer(String purpose, String button) throws Exception {
        var alert = alerts().stream().filter(value -> purpose.equals(obj(value.get("spec")).get("purpose"))).findFirst().orElseThrow();
        String id = Json.requireString(alert, "alertId");
        assertTrue(list(obj(alert.get("spec")).get("buttons")).stream().map(UiApiSessionMigrationTest::obj)
                .anyMatch(value -> button.equals(value.get("id")) && !Boolean.FALSE.equals(value.get("enabled"))), "Published button must be enabled");
        intent(Map.of("type", "alertShown", "windowId", id));
        intent(Map.of("type", "alertButton", "alertId", id, "buttonId", button));
    }

    /** Возвращает форму из нового HTTP bootstrap, не из внутреннего состояния порта. */
    private Map<String, Object> form(String id) throws Exception { return forms().stream().filter(value -> id.equals(value.get("id"))).findFirst().orElseThrow(); }
    /** Возвращает последнее окно указанного типа из фактического bootstrap. */
    private String lastForm(WindowType type) throws Exception { return Json.requireString(forms().stream().filter(value -> type.name().equals(obj(value.get("spec")).get("windowType"))).reduce((left, right) -> right).orElseThrow(), "id"); }
    /** Выбирает опубликованные формы, исключая сообщения Alert. */
    private List<Map<String, Object>> forms() throws Exception { return list(bootstrap().get("windows")).stream().map(UiApiSessionMigrationTest::obj).filter(value -> "form.open".equals(value.get("type"))).map(value -> obj(value.get("window"))).toList(); }
    /** Выбирает опубликованные сообщения с назначением и кнопками. */
    private List<Map<String, Object>> alerts() throws Exception { return list(bootstrap().get("windows")).stream().map(UiApiSessionMigrationTest::obj).filter(value -> "alert.open".equals(value.get("type"))).toList(); }
    /** Читает исходное значение поля из ответа UI-протокола. */
    private static String fieldValue(Map<String, Object> form, String key) { return Json.requireString(obj(obj(obj(form.get("view")).get("fields")).get(key)), "value"); }
    /** Отправляет команду через меню нового интерфейса. */
    private void command(String command) throws Exception { intent(Map.of("type", "command", "command", command, "source", "MENU")); }
    /** Выполняет настоящую HTTP-команду без автоматических повторов. */
    private Map<String, Object> intent(Map<String, Object> intent) throws Exception { return request("/api/ui/intent", Map.of("tab", "migration", "afterSeq", seq, "intent", intent)); }
    /** Читает целостное состояние новой вкладки, обновляя фактический курсор. */
    private Map<String, Object> bootstrap() throws Exception { return request("/api/ui/bootstrap?tab=migration", null); }
    /** Выполняет аутентифицированный HTTP-запрос к тестовому серверу. */
    private Map<String, Object> request(String path, Map<String, Object> body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).timeout(Duration.ofSeconds(5)).header("X-Token", server.token());
        if (body == null) builder.GET(); else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JsonWriter.write(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var result = JsonParser.parseObject(response.body()); if (result.containsKey("seq")) seq = Json.requireLong(result, "seq"); return result;
    }
    /** Читает состояние на единственном потоке AppController с ограниченным ожиданием. */
    private <T> T read(Callable<T> work) throws Exception { return server.coreRuntime().thread().submit(work).get(5, TimeUnit.SECONDS); }
    /** Ожидает подтверждённое состояние хранилища, не полагаясь на фиксированную задержку. */
    private static void await(Callable<Boolean> condition, String reason) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.call()) { assertTrue(System.nanoTime() < end, reason); Thread.sleep(10); }
    }
    /** Проверяет объект JSON без unchecked-приведения. */
    private static Map<String, Object> obj(Object value) { return Json.asObject(value, "migration"); }
    /** Проверяет массив JSON без изменения полученных данных. */
    private static List<?> list(Object value) { return assertInstanceOf(List.class, value); }
}
