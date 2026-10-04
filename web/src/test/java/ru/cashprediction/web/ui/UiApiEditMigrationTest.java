package ru.cashprediction.web.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.web.ServerLog;
import ru.cashprediction.web.WebServer;

/**
 * Переносит проверки редактирования legacy API на настоящий HTTP-протокол единого интерфейса.
 * Все изменения выполняются commands/form intents; Java читает только JSON и записанный план.
 * Изолированные CashMemory, порт 0 и реестр в памяти не затрагивают пользовательские данные.
 */
class UiApiEditMigrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 13);
    private static final String TAB = "migration";
    private static final String ADJUSTED_ROW = "r1@2026-10-05";
    private static final String ADDED_ROW = "r6@2026-09-20";

    @TempDir Path home;
    private WebServer server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private long seq;
    private long fieldRev;

    /** Запускает новый сервер и закрывает первый мастер тем же handshake, что отправляет браузер. */
    @BeforeEach void start() throws Exception {
        var environment = AppEnvironment.from(LaunchOptions.parse(List.of("--home",
                home.toString(), "--registry", "memory", "--today", TODAY.toString()), new java.util.Properties()));
        server = WebServer.startCore(environment, new ServerLog(false), 0, true);
        var windows = windows();
        assertEquals(1, windows.size(), "Isolated first launch must show exactly the wizard");
        String id = Json.requireString(obj(windows.getFirst().get("window")), "id");
        intent(Map.of("type", "formShown", "windowId", id));
        intent(Map.of("type", "formClose", "windowId", id));
        assertTrue(windows().isEmpty());
    }

    /** Закрывает HTTP и его исполнитель даже при неудаче assertion. */
    @AfterEach void close() {
        if (server != null) server.stop();
    }

    /** Пример получает dirty-заголовок, реальные строки и тот же состав данных в записанном Markdown. */
    @Test void sampleCommandPublishesDirtyTitleAndRealForecast() throws Exception {
        sample();
        var screen = screen();
        String title = Json.requireString(screen, "windowTitle");
        assertTrue(title.contains("Пример"), title);
        assertTrue(title.contains("*"), title);
        assertEquals(UiText.get("status.dirty"), status(screen, "dirty").get("text"));
        assertFalse(rows().isEmpty());
        assertNotNull(screen.get("summary"));
        assertNotNull(screen.get("chart"));
        Plan plan = saveAndRead();
        assertEquals("Пример", plan.name());
        assertEquals(LocalDate.of(2026, 9, 1), plan.startDate());
        assertEquals(5, plan.rules().size());
        assertEquals(1, plan.oneTimes().size());
        assertEquals("Отпуск", plan.goal().title());
        assertFalse(Json.requireString(screen(), "windowTitle").contains("*"));
        assertEquals(UiText.get("status.saved"), status(screen(), "dirty").get("text"));
    }

    /** CRUD, отмена и повтор проходят HTTP; сумма восстановленной операции проверяется и на диске, и в строке. */
    @Test void ruleCrudUndoRedoRoundTripsHttpAndSavedPlan() throws Exception {
        sample();
        String editor = openForm("edit.addIncome", "", "RULE_EDITOR");
        field(editor, "title", "HTTP income");
        field(editor, "amount", "10000");
        field(editor, "dayOfMonth", "20");
        button(editor, "ok");
        assertTrue(windows().isEmpty());
        Plan added = saveAndRead();
        assertEquals(6, added.rules().size());
        assertEquals(Money.ofMajor(10000), added.findRule(new RuleId("r6")).orElseThrow().amount());
        assertEquals(true, menu(screen(), "edit.undo").get("enabled"));
        assertIncome(ADDED_ROW, 10000);

        editor = openForm("row.edit", ADDED_ROW, "RULE_EDITOR");
        assertEquals(Money.ofMajor(10000).format(), fieldValue(editor, "amount"));
        field(editor, "amount", "12000");
        button(editor, "ok");
        Plan edited = saveAndRead();
        assertEquals(Money.ofMajor(12000), edited.findRule(new RuleId("r6")).orElseThrow().amount());
        assertIncome(ADDED_ROW, 12000);

        deleteRule(ADDED_ROW);
        Plan deleted = saveAndRead();
        assertEquals(5, deleted.rules().size());
        assertTrue(deleted.findRule(new RuleId("r6")).isEmpty());
        assertTrue(rows().stream().noneMatch(row -> Json.requireString(row, "rowId").startsWith("r6@")));
        command("edit.undo", "");
        assertEquals(edited, saveAndRead());
        assertEquals(true, menu(screen(), "edit.redo").get("enabled"));
        assertIncome(ADDED_ROW, 12000);
        command("edit.redo", "");
        assertEquals(deleted, saveAndRead());
        assertEquals(false, menu(screen(), "edit.redo").get("enabled"));
    }

    /** Невалидная сумма не закрывает редактор, не записывает файл и не добавляет шаг истории. */
    @Test void invalidRuleSumKeepsRawFieldPlanFileAndHistory() throws Exception {
        sample();
        Plan before = saveAndRead();
        byte[] original = Files.readAllBytes(planFile());
        var history = history();
        String editor = openForm("row.edit", ADJUSTED_ROW, "RULE_EDITOR");
        field(editor, "amount", "12,3,4");
        var view = obj(form(editor).get("view"));
        assertEquals("12,3,4", fieldValue(editor, "amount"));
        assertEquals("ERROR", obj(view.get("problem")).get("severity"));
        assertTrue(Json.requireString(obj(view.get("problem")), "text").contains(UiText.get("rule.amount")));
        assertEquals(false, obj(obj(view.get("buttons")).get("ok")).get("enabled"));
        button(editor, "ok");
        assertEquals("12,3,4", fieldValue(editor, "amount"));
        assertArrayEquals(original, Files.readAllBytes(planFile()));
        intent(Map.of("type", "formClose", "windowId", editor));
        assertEquals(history, history());
        assertEquals(before, readPlan());
        assertEquals(UiText.get("status.saved"), status(screen(), "dirty").get("text"));
    }

    /** Неизвестная строка и пакеты уже закрытого редактора удалённого правила не воскрешают объект или историю. */
    @Test void missingTargetAndStaleEditorPacketsCannotResurrectDeletedRule() throws Exception {
        sample();
        saveAndRead();
        var history = history();
        byte[] original = Files.readAllBytes(planFile());
        command("row.edit", "r999@2026-10-05");
        assertTrue(windows().isEmpty());
        assertEquals(history, history());
        assertArrayEquals(original, Files.readAllBytes(planFile()));

        String stale = openForm("row.edit", ADJUSTED_ROW, "RULE_EDITOR");
        intent(Map.of("type", "formClose", "windowId", stale));
        deleteRule(ADJUSTED_ROW);
        Plan deleted = saveAndRead();
        assertTrue(deleted.findRule(new RuleId("r1")).isEmpty());
        byte[] deletedBytes = Files.readAllBytes(planFile());
        var deletedHistory = history();
        intent(Map.of("type", "formShown", "windowId", stale));
        field(stale, "amount", "999999");
        button(stale, "ok");
        command("row.edit", ADJUSTED_ROW);
        assertTrue(windows().isEmpty());
        assertEquals(deletedHistory, history());
        assertArrayEquals(deletedBytes, Files.readAllBytes(planFile()));
        assertEquals(deleted, readPlan());
        assertEquals(UiText.get("status.saved"), status(screen(), "dirty").get("text"));
        assertTrue(rows().stream().noneMatch(row -> Json.requireString(row, "rowId").startsWith("r1@")));
        command("edit.undo", "");
        Plan restored = saveAndRead();
        assertEquals(Money.ofMajor(80000), restored.findRule(new RuleId("r1")).orElseThrow().amount());
        assertIncome(ADJUSTED_ROW, 80000);
    }

    /** Корректировка сохраняет сумму и заметку; повторный reset не создаёт скрытого шага отмены. */
    @Test void adjustmentAmountNoteResetAndRepeatedResetPreserveHistory() throws Exception {
        sample();
        Plan original = saveAndRead();
        String editor = openForm("row.adjust", ADJUSTED_ROW, "ADJUSTMENT_EDITOR");
        field(editor, "action", "CHANGE_AMOUNT");
        field(editor, "amount", "90000");
        field(editor, "note", "HTTP note\nsecond line");
        button(editor, "ok");
        Plan adjusted = saveAndRead();
        var key = OccurrenceKey.parseRowId(ADJUSTED_ROW);
        assertEquals(1, adjusted.adjustments().size());
        var adjustment = adjusted.findAdjustment(key).orElseThrow();
        assertInstanceOf(Adjustment.ChangeAmount.class, adjustment.action());
        assertEquals(Money.ofMajor(90000), adjustment.action().newAmount().orElseThrow());
        assertEquals("HTTP note\nsecond line", adjustment.note());
        assertIncome(ADJUSTED_ROW, 90000);
        assertTrue(cells(row(ADJUSTED_ROW)).get(7).contains("✎"));
        editor = openForm("row.adjust", ADJUSTED_ROW, "ADJUSTMENT_EDITOR");
        assertEquals(Money.ofMajor(90000).format(), fieldValue(editor, "amount"));
        assertEquals(adjustment.note(), fieldValue(editor, "note"));
        button(editor, "reset");
        assertEquals(original, saveAndRead());
        assertIncome(ADJUSTED_ROW, 80000);
        assertFalse(cells(row(ADJUSTED_ROW)).get(7).contains("✎"));
        var resetHistory = history();
        byte[] resetBytes = Files.readAllBytes(planFile());
        command("row.reset", ADJUSTED_ROW);
        assertEquals(resetHistory, history());
        assertArrayEquals(resetBytes, Files.readAllBytes(planFile()));
        command("edit.undo", "");
        assertEquals(adjusted, saveAndRead(), "One undo after repeated reset must restore the adjustment");
        command("edit.redo", "");
        assertEquals(original, saveAndRead());
    }

    /** Фильтр не сбрасывает выбранный график, период и флаг; смена вида не загрязняет план или историю. */
    @Test void chartPeriodAndIncomeFlagSurviveFilterWithoutPlanEdits() throws Exception {
        sample();
        saveAndRead();
        byte[] original = Files.readAllBytes(planFile());
        var history = history();
        command("view.chart", "");
        command("view.period.M6", "");
        command("view.flag.showIncome", "");
        assertViewSelection();
        intent(Map.of("type", "filterText", "text", "Аренда"));
        assertViewSelection();
        var filtered = rows();
        assertTrue(filtered.stream().anyMatch(row -> Json.requireString(row, "rowId").startsWith("r3@")));
        assertTrue(filtered.stream().filter(row -> "RULE".equals(row.get("kind")))
                .allMatch(row -> cells(row).get(2).contains("Аренда")));
        assertEquals(history, history());
        assertEquals(UiText.get("status.saved"), status(screen(), "dirty").get("text"));
        assertArrayEquals(original, Files.readAllBytes(planFile()));
    }

    /** Проверяет радиопункты и флажок непосредственно в сериализованной модели экрана. */
    private void assertViewSelection() throws Exception {
        var screen = screen();
        assertEquals("CHART", screen.get("mode"));
        assertEquals(true, menu(screen, "view.chart").get("selected"));
        assertEquals(false, menu(screen, "view.table").get("selected"));
        assertEquals(true, menu(screen, "view.period.M6").get("selected"));
        assertEquals(false, menu(screen, "view.flag.showIncome").get("checked"));
    }

    /** Открывает пример и проверяет отсутствие непредвиденных сообщений поверх главного окна. */
    private void sample() throws Exception {
        command("file.sample", "");
        assertTrue(windows().isEmpty());
    }

    /** Показывает форму с обязательным подтверждением показа, без прямого доступа к контроллеру. */
    private String openForm(String command, String row, String type) throws Exception {
        command(command, row);
        var windows = windows();
        assertEquals(1, windows.size(), command);
        var form = obj(windows.getFirst().get("window"));
        assertEquals(type, obj(form.get("spec")).get("windowType"));
        String id = Json.requireString(form, "id");
        intent(Map.of("type", "formShown", "windowId", id));
        return id;
    }

    /** Подтверждает удаление через настоящий сериализованный Alert и его HTTP-ответ. */
    private void deleteRule(String row) throws Exception {
        command("row.delete", row);
        var windows = windows();
        assertEquals(1, windows.size());
        var alert = windows.getFirst();
        assertEquals("deleteRule", obj(alert.get("spec")).get("purpose"));
        String id = Json.requireString(alert, "alertId");
        intent(Map.of("type", "alertShown", "windowId", id));
        intent(Map.of("type", "alertButton", "alertId", id, "buttonId", "delete"));
        assertTrue(windows().isEmpty());
    }

    /** Сохраняет через HTTP и читает фактический Markdown, не захватывая бизнес-модель сервера. */
    private Plan saveAndRead() throws Exception {
        command("file.save", "");
        assertTrue(windows().isEmpty(), "Save must finish without an unexpected conflict/chooser");
        assertTrue(Files.isRegularFile(planFile()));
        return readPlan();
    }

    /** Декодирует только записанный файл для проверки точных значений и полной структуры плана. */
    private Plan readPlan() throws Exception {
        return PlanMarkdownReader.read(Files.readString(planFile()), "HTTP migration", TODAY).plan();
    }

    /** Возвращает путь единственного плана данного изолированного сценария. */
    private Path planFile() { return home.resolve("CashMemory/Пример.md"); }

    /** Сравнивает не только доступность, но и описание undo/redo, не завися от ревизий экрана. */
    private List<Object> history() throws Exception {
        var screen = screen();
        var undo = menu(screen, "edit.undo");
        var redo = menu(screen, "edit.redo");
        return List.of(undo.get("enabled"), undo.get("text"), redo.get("enabled"), redo.get("text"));
    }

    /** Проверяет видимую сумму дохода, полученную через реальный запрос строк. */
    private void assertIncome(String id, long major) throws Exception {
        assertEquals(Money.ofMajor(major).format(), cells(row(id)).get(4));
    }

    /** Ищет конкретную строку в полном HTTP-списке, не полагаясь на количество строк примера. */
    private Map<String, Object> row(String id) throws Exception {
        return rows().stream().filter(row -> id.equals(row.get("rowId"))).findFirst().orElseThrow();
    }

    /** Читает все страницы по одной ревизии таблицы; stale не скрывается автоматическим повтором. */
    private List<Map<String, Object>> rows() throws Exception {
        var table = obj(screen().get("table"));
        long rev = Json.requireLong(table, "revision");
        int size = Math.toIntExact(Json.requireLong(table, "rowCount"));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int from = 0; from < size; from += 300) {
            var result = post("/api/ui/query", Map.of("type", "rows", "rev", rev, "from", from,
                    "count", Math.min(300, size - from)));
            assertNotEquals(true, result.get("stale"));
            var page = list(result.get("result"));
            assertEquals(Math.min(300, size - from), page.size());
            page.forEach(value -> rows.add(obj(value)));
        }
        return rows;
    }

    /** Находит окно по настоящему JSON-id; неизвестное окно не подменяется внутренней моделью. */
    private Map<String, Object> form(String id) throws Exception {
        return windows().stream().filter(effect -> effect.containsKey("window"))
                .map(effect -> obj(effect.get("window"))).filter(window -> id.equals(window.get("id")))
                .findFirst().orElseThrow();
    }

    /** Читает сырое поле из сериализованного FormView. */
    private String fieldValue(String id, String field) throws Exception {
        return Json.requireString(obj(obj(obj(form(id).get("view")).get("fields")).get(field)), "value");
    }

    /** Передаёт новый текст с монотонной клиентской ревизией. */
    private void field(String id, String field, String raw) throws Exception {
        intent(Map.of("type", "formField", "windowId", id, "fieldId", field, "raw", raw,
                "committed", false, "clientRev", ++fieldRev));
    }

    /** Нажимает кнопку формы через протокол, включая намеренно запоздалые пакеты. */
    private void button(String id, String button) throws Exception {
        intent(Map.of("type", "formButton", "windowId", id, "buttonId", button));
    }

    /** Посылает обычную UI-команду; selftest и прямой вызов бизнес-потока не используются. */
    private void command(String command, String row) throws Exception {
        intent(Map.of("type", "command", "command", command, "source", "MENU", "args", Map.of("rowId", row)));
    }

    /** Возвращает опубликованные открытые окна bootstrap. */
    private List<Map<String, Object>> windows() throws Exception {
        return list(bootstrap().get("windows")).stream().map(UiApiEditMigrationTest::obj).toList();
    }

    /** Возвращает реальный главный экран новой HTTP-модели. */
    private Map<String, Object> screen() throws Exception { return obj(bootstrap().get("screen")); }

    /** Синхронизирует курсор эффектов перед следующим намерением. */
    private Map<String, Object> bootstrap() throws Exception {
        var response = http.send(request("/api/ui/bootstrap?tab=" + TAB).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var result = obj(JsonParser.parse(response.body()));
        seq = Json.requireLong(result, "seq");
        return result;
    }

    /** Оборачивает намерение браузера и принимает новый курсор после выполнения. */
    private void intent(Map<String, Object> value) throws Exception {
        var result = post("/api/ui/intent", Map.of("tab", TAB, "afterSeq", seq, "intent", value));
        seq = Json.requireLong(result, "seq");
    }

    /** Выполняет настоящий HTTP POST и требует успешный транспортный ответ. */
    private Map<String, Object> post(String path, Map<String, Object> value) throws Exception {
        var response = http.send(request(path).POST(HttpRequest.BodyPublishers.ofString(JsonWriter.write(value))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        return obj(JsonParser.parse(response.body()));
    }

    /** Создаёт запрос с текущим boot-токеном и ограниченным временем ожидания. */
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(8)).header("X-Token", server.token());
    }

    /** Находит опубликованный пункт меню на любой глубине. */
    private static Map<String, Object> menu(Map<String, Object> screen, String id) {
        var found = findMenu(list(obj(screen.get("menuBar")).get("menus")), id);
        assertNotNull(found, "Missing serialized menu node: " + id);
        return found;
    }

    /** Обходит только дерево сериализованных меню. */
    private static Map<String, Object> findMenu(List<Object> nodes, String id) {
        for (Object value : nodes) {
            var node = obj(value);
            if (id.equals(node.get("id"))) return node;
            if (node.get("children") instanceof List<?> children) {
                var found = findMenu(new ArrayList<>(children), id);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Читает сегмент состояния, в том числе независимый признак dirty. */
    private static Map<String, Object> status(Map<String, Object> screen, String id) {
        return list(obj(screen.get("status")).get("segments")).stream().map(UiApiEditMigrationTest::obj)
                .filter(segment -> id.equals(segment.get("id"))).findFirst().orElseThrow();
    }

    /** Возвращает тексты ячеек без нормализации сумм и отметок. */
    private static List<String> cells(Map<String, Object> row) {
        return list(row.get("cells")).stream().map(String.class::cast).toList();
    }

    /** Требует JSON-объект вместо небезопасного приведения структуры. */
    private static Map<String, Object> obj(Object value) { return Json.asObject(value, "HTTP migration"); }

    /** Копирует JSON-массив без unchecked cast. */
    private static List<Object> list(Object value) {
        assertInstanceOf(List.class, value);
        return new ArrayList<>((List<?>) value);
    }
}
