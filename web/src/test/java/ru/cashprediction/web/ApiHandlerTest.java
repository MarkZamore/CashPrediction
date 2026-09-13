package ru.cashprediction.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.cashprediction.web.ApiTestClient.list;
import static ru.cashprediction.web.ApiTestClient.obj;
import static ru.cashprediction.web.ApiTestClient.q;
import static ru.cashprediction.web.ApiTestClient.str;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JSON API web-клиента через настоящий HTTP: токен, план-пример и прогноз, правка правил с отменой,
 * корректировки, конфликты сохранения, вид, предпросмотр дат, экспорт и вспомогательные маршруты.
 */
class ApiHandlerTest {

    @TempDir
    Path temp;

    private ApiTestClient api;

    @BeforeEach
    void startServer() throws IOException {
        api = ApiTestClient.start(temp.resolve("CashMemory"));
    }

    @AfterEach
    void stopServer() {
        api.stop();
    }

    @Test
    void apiRequiresToken() {
        // Без заголовка и с чужим токеном — 403 с русским сообщением в JSON.
        ApiTestClient.Response missing = api.send("GET", "/api/state", null, null);
        assertEquals(403, missing.status());
        assertTrue(str(missing.json(), "error").contains("Нет доступа"), missing.body());
        // Значение заголовка HTTP — только ASCII, поэтому «чужой» токен латиницей.
        assertEquals(403, api.send("GET", "/api/state", null, "wrong-token").status());
        // Ссылки скачивания и sendBeacon передают токен параметром t.
        assertEquals(200, api.send("GET", "/api/state?t=" + q(api.token()), null, null).status());
        // Статика токена не требует: страница и скрипты не секретны.
        ApiTestClient.Response page = api.send("GET", "/", null, null);
        assertEquals(200, page.status());
        assertTrue(page.contentType().startsWith("text/html"), page.contentType());
    }

    @Test
    void unknownRouteAndWrongMethodAreJsonErrors() {
        assertNotNull(str(api.fail(404, "GET", "/api/nope", null), "error"));
        Map<String, Object> wrongMethod = api.fail(405, "DELETE", "/api/state", null);
        assertTrue(str(wrongMethod, "error").contains("GET"), wrongMethod.toString());
        // Тело, которое не является JSON, — 400, а не 500.
        ApiTestClient.Response broken = api.call("POST", "/api/rules", "не json");
        assertEquals(400, broken.status(), broken.body());
    }

    @Test
    void samplePlanHasForecastAndChart() {
        Map<String, Object> state = api.ok("POST", "/api/plans/sample", null);
        Map<String, Object> plan = obj(state, "plan");
        assertEquals("Пример", str(plan, "name"));
        assertEquals(5, list(plan, "rules").size());
        assertEquals(1, list(plan, "oneTimes").size());
        assertEquals("Отпуск", str(obj(plan, "goal"), "title"));
        assertEquals(LocalDate.now().withDayOfMonth(1).toString(), str(plan, "startDate"));
        assertEquals(Boolean.TRUE, state.get("dirty"));
        assertEquals("CashPrediction — Пример *", str(state, "title"));
        Map<String, Object> forecast = obj(state, "forecast");
        assertFalse(list(forecast, "rows").isEmpty());
        assertFalse(list(forecast, "chart").isEmpty());
        assertNotNull(forecast.get("summary"));
        assertEquals(state.get("view"), state.get("viewState"));

        Map<String, Object> range = api.ok("GET", "/api/forecast?maxPoints=10", null);
        int points = list(range, "chart").size();
        assertTrue(points >= 2 && points <= 10, "точек графика: " + points);
        assertFalse(list(range, "rows").isEmpty());
        String from = LocalDate.now().withDayOfMonth(1).plusMonths(2).toString();
        String to = LocalDate.now().withDayOfMonth(1).toString();
        assertNotNull(str(api.fail(400, "GET", "/api/forecast?from=" + from + "&to=" + to, null), "error"));
    }

    @Test
    void ruleCrudWithUndoRedo() {
        api.ok("POST", "/api/plans/sample", null);
        Map<String, Object> fields = ruleFields("10000,00");
        Map<String, Object> created = api.ok("POST", "/api/rules", Map.of("fields", fields));
        assertEquals("r6", str(created, "createdId"));
        assertEquals(6, list(obj(created, "plan"), "rules").size());
        assertEquals(Boolean.TRUE, created.get("canUndo"));

        Map<String, Object> updated = api.ok("PUT", "/api/rules/r6", Map.of("fields", ruleFields("12000,00")));
        assertEquals("12000,00", str(findById(list(obj(updated, "plan"), "rules"), "r6"), "amount"));

        Map<String, Object> deleted = api.ok("DELETE", "/api/rules/r6", null);
        assertEquals(5, list(obj(deleted, "plan"), "rules").size());
        Map<String, Object> undone = api.ok("POST", "/api/undo", null);
        assertEquals("12000,00", str(findById(list(obj(undone, "plan"), "rules"), "r6"), "amount"));
        assertEquals(Boolean.TRUE, undone.get("canRedo"));
        Map<String, Object> redone = api.ok("POST", "/api/redo", null);
        assertEquals(5, list(obj(redone, "plan"), "rules").size());

        // Ошибки: неизвестное правило — 404, неразбираемая сумма — 400 с названием поля.
        assertNotNull(str(api.fail(404, "PUT", "/api/rules/r999", Map.of("fields", ruleFields("1,00"))), "error"));
        Map<String, Object> bad = api.fail(400, "POST", "/api/rules", Map.of("fields", ruleFields("много")));
        assertTrue(str(bad, "error").contains("Сумма"), bad.toString());
    }

    @Test
    void adjustmentPutAndReset() {
        api.ok("POST", "/api/plans/sample", null);
        String original = LocalDate.now().withDayOfMonth(5).toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleId", "r1");
        body.put("originalDate", original);
        body.put("action", "CHANGE_AMOUNT");
        body.put("amount", "90000,00");
        body.put("date", "");
        body.put("note", "премия к зарплате");
        Map<String, Object> state = api.ok("PUT", "/api/adjustments", body);
        List<Object> adjustments = list(obj(state, "plan"), "adjustments");
        assertEquals(1, adjustments.size());
        assertEquals("CHANGE_AMOUNT", str(obj(adjustments, 0), "action"));
        Map<String, Object> row = list(obj(state, "forecast"), "rows").stream()
                .map(r -> ApiTestClient.obj(List.of(r), 0))
                .filter(r -> "r1".equals(str(r, "ruleId")) && original.equals(str(r, "originalDate")))
                .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, obj(row, "flags").get("amountChanged"));

        String query = "/api/adjustments?ruleId=r1&originalDate=" + original;
        assertTrue(list(obj(api.ok("DELETE", query, null), "plan"), "adjustments").isEmpty());
        assertNotNull(str(api.fail(404, "DELETE", query, null), "error"));
        body.put("ruleId", "r999");
        api.fail(404, "PUT", "/api/adjustments", body);
        assertEquals(0L, api.ok("POST", "/api/cleanup-orphans", null).get("removed"));
    }

    @Test
    void saveConflictsAndReload() throws IOException {
        api.ok("POST", "/api/plans/sample", null);
        Map<String, Object> saved = api.ok("POST", "/api/plans/save", null);
        Path file = Path.of(str(saved, "savedPath"));
        assertEquals("Пример.md", file.getFileName().toString());
        assertTrue(Files.isRegularFile(file));
        assertEquals(Boolean.FALSE, saved.get("dirty"));

        // Новый план с тем же именем не затирает чужой файл молча.
        api.ok("POST", "/api/plans/sample", null);
        assertEquals("exists", str(api.fail(409, "POST", "/api/plans/save", null), "conflict"));
        api.ok("POST", "/api/plans/save", Map.of("overwrite", true));

        // Файл изменён снаружи после сохранения: «перезаписать / перечитать».
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(3600)));
        api.ok("PUT", "/api/plan/settings", Map.of("fields", Map.of("cushion", "1000,00")));
        assertEquals("externalChange", str(api.fail(409, "POST", "/api/plans/save", null), "conflict"));
        Map<String, Object> reloaded = api.ok("POST", "/api/plans/reload", null);
        assertEquals(Boolean.FALSE, reloaded.get("dirty"));
        assertEquals("50000,00", str(obj(reloaded, "plan"), "cushion"));
    }

    @Test
    void viewChangesAreStoredInSettings() {
        Map<String, Object> state = api.ok("PUT", "/api/view",
                Map.of("viewState", Map.of("mode", "CHART", "period", "M6", "showIncome", false)));
        Map<String, Object> view = obj(state, "view");
        assertEquals("CHART", str(view, "mode"));
        assertEquals("M6", str(view, "period"));
        assertEquals(Boolean.FALSE, view.get("showIncome"));
        assertEquals("CHART", str(obj(state, "settings"), "view"));
        // Неуказанные поля вида не меняются.
        Map<String, Object> next = api.ok("PUT", "/api/view", Map.of("filterText", "аренда"));
        assertEquals("CHART", str(obj(next, "view"), "mode"));
        assertEquals("аренда", str(obj(next, "view"), "filterText"));
    }

    @Test
    void previewExportAndHelpers() {
        api.ok("POST", "/api/plans/sample", null);
        String preview = "/api/preview-dates?title=" + q("Подработка") + "&kind=INCOME&amount=" + q("100,00")
                + "&recurrenceKind=MONTHLY&dayOfMonth=10";
        Map<String, Object> dates = api.ok("GET", preview, null);
        assertNull(dates.get("error"), dates.toString());
        assertEquals(PlanEditApi.PREVIEW_COUNT, list(dates, "dates").size());
        // Недописанная форма — не ошибка запроса: причина приходит в поле error.
        Map<String, Object> half = api.ok("POST", "/api/preview-dates",
                Map.of("fields", Map.of("title", "x", "kind", "INCOME", "amount", "100", "recurrenceKind", "MONTHLY",
                        "dayOfMonth", "тридцать")));
        assertNotNull(half.get("error"));

        // Экспорт CSV: токен в параметре, файл для скачивания.
        ApiTestClient.Response csv = api.send("GET", "/api/export.csv?separator=TAB&bom=false&range=ALL&t=" + q(api.token()),
                null, null);
        assertEquals(200, csv.status(), csv.body());
        assertTrue(csv.contentType().startsWith("text/csv"), csv.contentType());
        assertTrue(csv.disposition().startsWith("attachment"), csv.disposition());
        assertTrue(csv.body().lines().findFirst().orElse("").contains("\t"));

        String help = str(api.ok("GET", "/api/format-help", null), "text");
        assertFalse(help.isBlank());
        assertFalse(help.startsWith("Справка о формате файла недоступна"), help);

        Map<String, Object> fs = api.ok("GET", "/api/fs?mode=dirs", null);
        assertEquals(api.dir().toAbsolutePath().normalize().toString(), str(fs, "path"));
        assertFalse(list(fs, "roots").isEmpty());
        api.fail(404, "GET", "/api/fs?path=" + q(api.dir().resolve("нет такой папки").toString()), null);

        Map<String, Object> goal = api.ok("GET", "/api/goal?target=" + q("300000,00"), null);
        assertNull(goal.get("error"), goal.toString());
        assertTrue(api.ok("GET", "/api/diagnostics", null).containsKey("validation"));
        assertFalse(list(api.ok("GET", "/api/plans", null), "plans").size() > 0);
    }

    /** Поля редактора регулярной операции в канонической форме. */
    private static Map<String, Object> ruleFields(String amount) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", "Подработка");
        fields.put("kind", "INCOME");
        fields.put("amount", amount);
        fields.put("category", "Работа");
        fields.put("recurrenceKind", "MONTHLY");
        fields.put("dayOfMonth", "15");
        fields.put("everyN", "1");
        fields.put("fromEnabled", "false");
        fields.put("from", "");
        fields.put("untilEnabled", "false");
        fields.put("until", "");
        fields.put("weekendPolicy", "NONE");
        fields.put("enabled", "true");
        fields.put("note", "");
        return fields;
    }

    /** Объект с заданным {@code id} из массива. */
    private static Map<String, Object> findById(List<Object> items, String id) {
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = obj(items, i);
            if (id.equals(str(item, "id"))) {
                return item;
            }
        }
        throw new AssertionError("Нет элемента " + id + " в " + items);
    }
}
