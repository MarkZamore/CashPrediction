package ru.cashprediction.web.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.io.CashMemoryLayout;
import ru.cashprediction.core.json.*;
import ru.cashprediction.core.markdown.MarkdownFormat;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.web.*;

/** Миграционные сценарии файлов и вычислительных форм через настоящий новый HTTP API. */
class UiApiFileMigrationTest {
    @TempDir Path home;
    private WebServer server;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private long seq;
    private long revision;

    /** Поднимает изолированный новый сервер и отменяет начальный мастер через HTTP. */
    @BeforeEach void start() throws Exception {
        server = WebServer.startCore(AppEnvironment.from(LaunchOptions.parse(List.of("--home",
                home.toString(), "--registry", "memory", "--today", "2026-09-13"), new Properties())),
                new ServerLog(false), 0, true);
        var windows = windows();
        if (!windows.isEmpty()) {
            String id = Json.requireString(obj(windows.getLast().get("window")), "id");
            intent(Map.of("type", "formShown", "windowId", id));
            intent(Map.of("type", "formClose", "windowId", id));
        }
    }

    /** Освобождает сервер и HTTP-клиент даже при ошибке остановки или теста. */
    @AfterEach void close() {
        try { if (server != null) server.stop(); }
        finally { http.close(); }
    }

    /** Отмена конфликта сохраняет внешние байты и отдельный несохранённый баланс в памяти. */
    @Test void externalConflictCancelPreservesDiskAndDirtyDocument() throws Exception {
        Path file = savedSample();
        changeBalance("222333");
        byte[] disk = externalEdition(file);
        command("file.save"); answer("externalChange", "cancel");
        assertArrayEquals(disk, Files.readAllBytes(file));
        assertEquals("987654,32", diskBalance(file));
        assertTrue(dirty());
        command("edit.planSettings");
        assertEquals("222333,00", fieldValue(form(), "startBalance").replace(" ", ""));
    }

    /** Перезапись сохраняет именно правку пользователя, а не внешнюю финансовую правку. */
    @Test void externalConflictOverwriteWritesDirtyDocumentAndClearsDirty() throws Exception {
        Path file = savedSample(); String baseline = Files.readString(file);
        changeBalance("222333"); byte[] external = externalEdition(file);
        command("file.save"); answer("externalChange", "overwrite");
        String written = Files.readString(file);
        assertNotEquals(new String(external, StandardCharsets.UTF_8), written);
        assertNotEquals(baseline, written);
        assertEquals("222333,00", diskBalance(file));
        assertFalse(dirty());
        command("edit.planSettings");
        assertEquals("222333,00", fieldValue(form(), "startBalance").replace(" ", ""));
    }

    /** Перечитывание принимает внешний баланс, не меняет байты диска и снимает dirty. */
    @Test void externalConflictReloadReadsDiskAndDiscardsDirtyChanges() throws Exception {
        Path file = savedSample();
        changeBalance("222333"); byte[] external = externalEdition(file);
        command("file.save"); answer("externalChange", "reload");
        assertArrayEquals(external, Files.readAllBytes(file));
        assertEquals("987654,32", diskBalance(file));
        assertFalse(dirty());
        // Имя файла авторитетно в PlanRepository.load; внешний заголовок не является переименованием.
        assertEquals(UiText.get("main.title", UiText.get("sample.name")), screen().get("windowTitle"));
        command("edit.planSettings");
        assertEquals("987654,32", fieldValue(form(), "startBalance").replace(" ", ""));
    }

    /** Коллизия первой записи требует решения; отмена и перезапись имеют разные финансовые результаты. */
    @Test void initialSaveCollisionRequiresDecisionAndCancelDoesNotOverwrite() throws Exception {
        Path file = savedSample(); byte[] external = externalEdition(file);
        command("file.sample"); assertTrue(dirty());
        command("file.save"); answer("overwriteOnFirstSave", "cancel");
        assertArrayEquals(external, Files.readAllBytes(file)); assertTrue(dirty());
        assertEquals("987654,32", diskBalance(file));
        command("file.save"); answer("overwriteOnFirstSave", "overwrite");
        assertFalse(dirty()); assertEquals("150000,00", diskBalance(file));
        assertEquals(UiText.get("main.title", UiText.get("sample.name")), screen().get("windowTitle"));
    }

    /** CSV экспортирует весь горизонт с TAB и без BOM через новый серверный обозреватель. */
    @Test void csvTabNoBomAllUsesNewServerChooserAndIgnoresVisibleFilterAndPeriod() throws Exception {
        command("file.sample"); command("view.period.M3");
        intent(Map.of("type", "filterText", "text", "no-matching-row-marker"));
        command("file.exportCsv"); String options = form();
        field(options, "separator", "TAB"); field(options, "bom", "false"); field(options, "range", "ALL");
        button(options, "export");
        var chooser = lastWindow();
        assertEquals("FILE_BROWSER", obj(chooser.get("spec")).get("presentation"));
        var request = obj(chooser.get("chooserRequest"));
        assertEquals("SAVE", request.get("mode")); assertTrue(Json.requireString(request, "filter").contains("*.csv"));
        String id = Json.requireString(chooser, "id");
        field(id, "name", "migration-tab.csv"); button(id, "ok");
        Path file = home.resolve("CashMemory/migration-tab.csv");
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(bytes.length > 3);
        assertFalse(bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf);
        String csv = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertEquals(9, lines[0].split("\t", -1).length);
        assertTrue(lines.length > 50, "ALL must export more than the filtered visible rows");
        assertTrue(Arrays.stream(lines).anyMatch(line -> line.startsWith("01.09.2026\t")), "ALL includes history before today");
        assertTrue(Arrays.stream(lines).anyMatch(line -> line.matches("[0-9]{2}\\.[0-9]{2}\\.2027\\t.*")), "ALL extends beyond M3");
        assertTrue(windows().isEmpty());
    }

    /** Ошибка суммы блокирует сохранение и даты; исправление возвращает рабочий предпросмотр. */
    @Test void invalidRulePreviewStaysOpenThenValidFieldsProduceDates() throws Exception {
        command("file.sample"); command("edit.addIncome"); String id = form();
        field(id, "title", "Migration income"); field(id, "amount", "not-money");
        var invalid = view(id);
        assertEquals("ERROR", obj(invalid.get("problem")).get("severity"));
        assertEquals(false, obj(obj(invalid.get("buttons")).get("ok")).get("enabled"));
        var placeholder = obj(list(invalid.get("preview")).getFirst());
        assertEquals(false, placeholder.get("selectable"));
        assertEquals(UiText.get("rule.preview.fill"), placeholder.get("text"));
        button(id, "ok"); assertEquals(id, form());
        field(id, "amount", "1000");
        var valid = view(id);
        assertNotEquals("ERROR", obj(valid.get("problem")).get("severity"));
        assertTrue(list(valid.get("preview")).stream().map(UiApiFileMigrationTest::obj).anyMatch(item -> Boolean.TRUE.equals(item.get("selectable"))));
        button(id, "ok"); assertTrue(windows().isEmpty());
    }

    /** Достижимая цель вычисляется через HTTP и сохраняется после закрытия калькулятора. */
    @Test void goalCalculationSucceedsAndSavedGoalSurvivesFormReload() throws Exception {
        command("file.sample"); command("tools.goal"); String id = form();
        field(id, "target", "1"); field(id, "byDateEnabled", "false"); field(id, "extraSaving", "");
        var result = view(id);
        assertNotEquals("ERROR", obj(result.get("problem")).get("severity"));
        assertTrue(list(result.get("results")).stream().map(UiApiFileMigrationTest::obj)
                .anyMatch(line -> UiText.get("form.goal.reached", "13.09.2026", "").equals(line.get("text"))));
        assertEquals(true, obj(obj(result.get("buttons")).get("saveGoal")).get("enabled"));
        button(id, "saveGoal"); assertEquals(id, form());
        button(id, "close"); command("tools.goal");
        assertEquals("1,00", fieldValue(form(), "target"));
    }

    /** Пустой список не раскрывает служебные документы и позволяет перейти к выбору файла. */
    @Test void emptyUnsavedPlansListContainsOnlyFromFileAndNoServiceFiles() throws Exception {
        command("file.open"); String id = form(); var opened = lastWindow();
        assertEquals("LIST_CHOICE", obj(opened.get("spec")).get("presentation"));
        assertEquals(UiText.get("dialog.openPlan.empty", home.resolve("CashMemory")), obj(opened.get("spec")).get("windowTitle"));
        var options = list(obj(obj(view(id).get("fields")).get("value")).get("options"));
        assertEquals(1, options.size()); assertEquals("fromFile", obj(options.getFirst()).get("value"));
        button(id, "open");
        assertEquals("FILE_BROWSER", obj(lastWindow().get("spec")).get("presentation"));
    }

    private Path savedSample() throws Exception {
        command("file.sample"); command("file.save"); assertFalse(dirty());
        try (var files = Files.list(home.resolve("CashMemory"))) {
            var plans = files.filter(file -> file.getFileName().toString().endsWith(".md")
                    && !CashMemoryLayout.isServiceFileName(file.getFileName().toString())).toList();
            assertEquals(1, plans.size()); return plans.getFirst();
        }
    }

    private byte[] externalEdition(Path file) throws Exception {
        String original = Files.readString(file);
        var previousTime = Files.getLastModifiedTime(file);
        String prefix = "- " + MarkdownFormat.KEY_START_BALANCE + ": ";
        var balanceLines = original.lines().filter(line -> line.startsWith(prefix)).toList();
        assertEquals(1, balanceLines.size(), "Fixture must edit exactly one human-readable financial parameter");
        String edited = original.replace(balanceLines.getFirst(), prefix + "987654,32");
        assertNotEquals(original, edited);
        Files.writeString(file, edited);
        // Проверка конфликта сравнивает время: задаём реальную отличающуюся метку без sleep и гонки таймера.
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(previousTime.toInstant().plusSeconds(2)));
        return Files.readAllBytes(file);
    }

    /** Читает финансовое значение непосредственно из человеческого файла для проверки результата записи. */
    private String diskBalance(Path file) throws Exception {
        String prefix = "- " + MarkdownFormat.KEY_START_BALANCE + ": ";
        var values = Files.readString(file).lines().filter(line -> line.startsWith(prefix)).toList();
        assertEquals(1, values.size());
        return Money.parse(values.getFirst().substring(prefix.length())).formatPlain();
    }

    private void changeBalance(String value) throws Exception {
        command("edit.planSettings"); String id = form(); field(id, "startBalance", value); button(id, "ok"); assertTrue(dirty());
    }

    private boolean dirty() throws Exception { return Json.requireString(screen(), "windowTitle").endsWith(" *"); }
    private Map<String, Object> screen() throws Exception { return obj(bootstrap().get("screen")); }
    private List<Map<String, Object>> windows() throws Exception { return list(bootstrap().get("windows")).stream().map(UiApiFileMigrationTest::obj).toList(); }
    private Map<String, Object> lastWindow() throws Exception { return obj(windows().getLast().get("window")); }
    private String form() throws Exception { return Json.requireString(lastWindow(), "id"); }
    private Map<String, Object> view(String id) throws Exception {
        return obj(windows().stream().filter(effect -> effect.containsKey("window"))
                .map(effect -> obj(effect.get("window"))).filter(window -> id.equals(window.get("id"))).findFirst().orElseThrow().get("view"));
    }
    private String fieldValue(String id, String field) throws Exception { return Json.requireString(obj(obj(view(id).get("fields")).get(field)), "value"); }
    private void field(String id, String field, String value) throws Exception {
        intent(Map.of("type", "formField", "windowId", id, "fieldId", field, "raw", value, "committed", true, "clientRev", ++revision));
    }
    private void button(String id, String button) throws Exception { intent(Map.of("type", "formButton", "windowId", id, "buttonId", button)); }
    private void answer(String purpose, String button) throws Exception {
        var alert = windows().getLast(); assertEquals(purpose, obj(alert.get("spec")).get("purpose"));
        String id = Json.requireString(alert, "alertId");
        intent(Map.of("type", "alertShown", "windowId", id));
        intent(Map.of("type", "alertButton", "alertId", id, "buttonId", button));
    }
    private void command(String command) throws Exception { intent(Map.of("type", "command", "command", command, "source", "MENU")); }
    private void intent(Map<String, Object> intent) throws Exception {
        var response = http.send(request("/api/ui/intent").POST(HttpRequest.BodyPublishers.ofString(
                JsonWriter.write(Map.of("tab", "files", "afterSeq", seq, "intent", intent)))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var data = obj(JsonParser.parse(response.body())); if (data.containsKey("seq")) seq = Json.requireLong(data, "seq");
    }
    private Map<String, Object> bootstrap() throws Exception {
        var response = http.send(request("/api/ui/bootstrap?tab=files").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        var data = obj(JsonParser.parse(response.body())); seq = Json.requireLong(data, "seq"); return data;
    }
    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .timeout(Duration.ofSeconds(8)).header("X-Token", server.token()).header("Content-Type", "application/json");
    }
    private static Map<String, Object> obj(Object value) { return Json.asObject(value, "migration"); }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) { return (List<Object>) value; }
}
