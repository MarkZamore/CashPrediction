package ru.cashprediction.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static ru.cashprediction.web.ApiTestClient.list;
import static ru.cashprediction.web.ApiTestClient.obj;
import static ru.cashprediction.web.ApiTestClient.str;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.session.store.MarkdownSessionStore;

/**
 * Серверная сессия web-клиента: окна браузера хранятся на сервере, снимок пишется в {@code web-session.md},
 * после «убитого» сервера следующий запуск предлагает восстановление и возвращает окна с введёнными значениями.
 */
class ServerStateTest {

    @TempDir
    Path temp;

    /** Все запущенные в тесте серверы: останавливаются после теста, даже если проверка упала. */
    private final List<ApiTestClient> servers = new ArrayList<>();

    @AfterEach
    void stopServers() {
        servers.forEach(ApiTestClient::stop);
    }

    @Test
    void windowStateIsKeptOnServer() throws IOException {
        ApiTestClient api = start();
        api.ok("POST", "/api/plans/sample", null);
        String id = openRuleEditor(api);
        api.ok("PUT", "/api/session/windows/" + id, Map.of("fields", Map.of("amount", "45000,00", "kind", "EXPENSE")));

        // «Перезагрузка страницы»: сервер отдаёт те же окна с теми же значениями.
        Map<String, Object> window = obj(list(api.ok("GET", "/api/session", null), "windows"), 0);
        assertEquals(id, str(window, "id"));
        assertEquals("RULE_EDITOR", str(window, "type"));
        assertEquals("Аренда", str(obj(window, "fields"), "title"));
        assertEquals("45000,00", str(obj(window, "fields"), "amount"));
        assertEquals("EXPENSE", str(obj(window, "fields"), "kind"));
        assertEquals("r3", str(obj(window, "context"), "ruleId"));
        assertEquals(1, list(api.ok("GET", "/api/state", null), "windows").size());

        // Вложенный диалог закрывается вместе с владельцем.
        Map<String, Object> child = api.ok("POST", "/api/session/windows",
                Map.of("type", "TEXT_INPUT", "ownerId", id, "context", Map.of("purpose", "rename"),
                        "fields", Map.of("value", "Новое имя")));
        assertEquals(id, str(obj(child, "window"), "ownerId"));
        List<Object> closed = list(api.ok("DELETE", "/api/session/windows/" + id, null), "closed");
        assertEquals(2, closed.size());
        assertTrue(list(api.ok("GET", "/api/session", null), "windows").isEmpty());
        api.fail(404, "DELETE", "/api/session/windows/" + id, null);
        api.fail(400, "POST", "/api/session/windows", Map.of("type", "НЕТ_ТАКОГО"));
    }

    @Test
    void snapshotIsWrittenToMarkdown() throws IOException {
        ApiTestClient api = start();
        api.ok("POST", "/api/plans/sample", null);
        openRuleEditor(api);
        Map<String, Object> session = api.ok("POST", "/api/session/snapshot", null);
        assertEquals("running", str(session, "state"));

        Path sessionFile = api.dir().resolve(MarkdownSessionStore.SESSION_FILE_NAME);
        String text = Files.readString(sessionFile);
        assertTrue(text.contains("Состояние: running"), text);
        assertTrue(text.contains("## Открытые окна"), text);
        assertTrue(text.contains("RULE_EDITOR"), text);
        assertTrue(text.contains("45 0"), text);
        // Несохранённый план целиком лежит в снимке (в самом файле или в соседнем web-session.plan.md).
        Path planFile = api.dir().resolve(MarkdownSessionStore.PLAN_FILE_NAME);
        String planText = text + (Files.isRegularFile(planFile) ? Files.readString(planFile) : "");
        assertTrue(planText.contains("# План: Пример"), planText);

        assertTrue(str(api.ok("GET", "/api/session/last", null), "text").contains("RULE_EDITOR"));
    }

    @Test
    void crashedServerOffersRestoreWithWindowsAndFields() throws IOException {
        ApiTestClient first = start();
        first.ok("POST", "/api/plans/sample", null);
        String id = openRuleEditor(first);
        first.ok("PUT", "/api/session/windows/" + id, Map.of("fields", Map.of("amount", "45000,00")));
        first.ok("POST", "/api/session/snapshot", null);
        first.abandon();

        ApiTestClient second = start();
        Map<String, Object> session = second.ok("GET", "/api/session", null);
        assertEquals("crashed", str(session, "state"));
        assertEquals(Boolean.TRUE, session.get("pendingRestore"));
        Map<String, Object> pendingWindow = obj(list(obj(session, "pendingSnapshot"), "windows"), 0);
        assertEquals("RULE_EDITOR", str(pendingWindow, "type"));
        assertEquals("45000,00", str(obj(pendingWindow, "fields"), "amount"));
        assertEquals(Boolean.TRUE, obj(session, "pendingSnapshot").get("planDirty"));
        // До решения пользователя окна не открыты, а запись нового сеанса не затирает снимок сбоя.
        Map<String, Object> before = second.ok("GET", "/api/state", null);
        assertEquals(Boolean.TRUE, before.get("pendingRestore"));
        assertTrue(list(before, "windows").isEmpty());

        Map<String, Object> restored = second.ok("POST", "/api/session/start", Map.of("restore", true));
        assertEquals(Boolean.TRUE, restored.get("restored"));
        assertEquals("Пример", str(obj(restored, "plan"), "name"));
        assertEquals(Boolean.TRUE, restored.get("dirty"));
        Map<String, Object> window = obj(list(restored, "windows"), 0);
        assertEquals("RULE_EDITOR", str(window, "type"));
        assertEquals("Аренда", str(obj(window, "fields"), "title"));
        assertEquals("45000,00", str(obj(window, "fields"), "amount"));
        assertEquals("edit", str(obj(window, "context"), "mode"));
        assertEquals("running", str(obj(restored, "session"), "state"));
        assertEquals(Boolean.FALSE, restored.get("pendingRestore"));
        // Повторное решение уже не нужно.
        assertEquals("state", str(second.fail(409, "POST", "/api/session/start", Map.of("restore", true)), "conflict"));

        // Корректная остановка → следующий запуск чистый.
        second.stop();
        ApiTestClient third = start();
        Map<String, Object> clean = third.ok("GET", "/api/session", null);
        assertEquals("running", str(clean, "state"));
        assertEquals(Boolean.FALSE, clean.get("pendingRestore"));
        assertTrue(list(clean, "windows").isEmpty());
    }

    @Test
    void crashedServerCanStartFresh() throws IOException {
        ApiTestClient first = start();
        openRuleEditor(first);
        first.ok("POST", "/api/session/snapshot", null);
        first.abandon();

        ApiTestClient second = start();
        assertTrue(second.state().isRestorePending());
        Map<String, Object> fresh = second.ok("POST", "/api/session/start", Map.of("restore", false));
        assertEquals(Boolean.FALSE, fresh.get("restored"));
        assertTrue(list(fresh, "windows").isEmpty());
        assertEquals("running", str(obj(fresh, "session"), "state"));
        assertThrows(ConflictException.class, () -> second.state().restore());
    }

    @Test
    void clearSnapshotsKeepsCrashDetection() throws IOException {
        ApiTestClient first = start();
        openRuleEditor(first);
        first.ok("POST", "/api/session/snapshot", null);
        Map<String, Object> cleared = first.ok("POST", "/api/session/clear", null);
        assertEquals("running", str(cleared, "state"));
        // Маркер текущего сеанса записан заново, поэтому сбой после очистки всё равно обнаруживается.
        String text = Files.readString(first.dir().resolve(MarkdownSessionStore.SESSION_FILE_NAME));
        assertTrue(text.contains("Состояние: running"), text);
        first.ok("POST", "/api/session/snapshot", null);
        first.abandon();

        ApiTestClient second = start();
        assertEquals("crashed", str(second.ok("GET", "/api/session", null), "state"));
    }

    @Test
    void mainWindowStateGoesToSnapshot() throws IOException {
        ApiTestClient api = start();
        api.ok("POST", "/api/plans/sample", null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bounds", Map.of("x", 10, "y", 20, "width", 1280, "height", 800));
        body.put("maximized", true);
        body.put("selectedRowId", "r3@" + java.time.LocalDate.now().withDayOfMonth(1));
        api.ok("PUT", "/api/session/main", body);
        api.ok("PUT", "/api/view", Map.of("mode", "CHART", "showSkipped", true));
        api.ok("POST", "/api/session/snapshot", null);
        api.abandon();

        ApiTestClient second = start();
        Map<String, Object> main = obj(obj(second.ok("GET", "/api/session", null), "pendingSnapshot"), "main");
        assertEquals("CHART", str(main, "view"));
        assertEquals(Boolean.TRUE, obj(main, "filters").get("showSkipped"));
        assertEquals(str(body, "selectedRowId"), str(main, "selectedRowId"));
        Map<String, Object> restored = second.ok("POST", "/api/session/start", Map.of("restore", true));
        assertEquals("CHART", str(obj(restored, "view"), "mode"));
        assertFalse(list(restored, "notices").size() > 0, restored.get("notices").toString());
    }

    /** Запускает сервер над общей для теста папкой CashMemory. */
    private ApiTestClient start() throws IOException {
        ApiTestClient api = ApiTestClient.start(temp.resolve("CashMemory"));
        servers.add(api);
        return api;
    }

    /** Открывает редактор правила «Аренда» с недописанной суммой и возвращает идентификатор окна. */
    private static String openRuleEditor(ApiTestClient api) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "RULE_EDITOR");
        body.put("modal", true);
        body.put("ownerId", "main");
        body.put("context", Map.of("mode", "edit", "ruleId", "r3"));
        body.put("fields", Map.of("title", "Аренда", "kind", "EXPENSE", "amount", "45 0", "recurrenceKind", "MONTHLY"));
        Map<String, Object> response = api.ok("POST", "/api/session/windows", body);
        String id = str(response, "id");
        assertTrue(id != null && id.startsWith("w"), response.toString());
        return id;
    }
}
