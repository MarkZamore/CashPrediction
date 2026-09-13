package ru.cashprediction.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import ru.cashprediction.core.session.RestoreReport;
import ru.cashprediction.core.session.WindowBounds;

/**
 * Маршруты сессии: окна браузера, главное окно, решение о восстановлении, снимки, остановка сервера.
 *
 * <p>Это web-реализация раздела 5.5 плана: браузер регистрирует каждое восстанавливаемое окно
 * ({@code POST /api/session/windows}), отправляет каждое изменение поля ({@code PUT /api/session/windows/{id}}),
 * при закрытии удаляет окно ({@code DELETE}). Вид, период, фильтры и выделение строки приходят в
 * {@code PUT /api/session/main}. Всё это попадает в снимок {@code CashMemory/web-session.md}.</p>
 *
 * <p>Класс без изменяемого состояния; маршруты выполняются под монитором {@link ServerState}.</p>
 */
public final class SessionApi {

    private final ServerState state;
    private final Runnable shutdown;
    private final Runnable crash;

    /**
     * Создаёт маршруты.
     *
     * @param state    серверное состояние
     * @param shutdown корректная остановка сервера (выполняется в отдельном потоке после ответа)
     * @param crash    аварийная остановка без сохранения (выполняется в отдельном потоке после ответа)
     */
    public SessionApi(ServerState state, Runnable shutdown, Runnable crash) {
        this.state = Objects.requireNonNull(state, "state");
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
        this.crash = Objects.requireNonNull(crash, "crash");
    }

    /**
     * Регистрирует маршруты.
     *
     * @param router маршрутизатор
     */
    public void register(Router router) {
        router.add("GET", "/api/session", request -> {
            Map<String, Object> m = StateJson.sessionJson(state);
            m.put("windows", StateJson.windowsJson(state));
            m.put("selectedRowId", state.main().selectedRowId());
            return ApiResponse.json(m);
        });
        // Баннер «Восстановить с сервера / Начать заново» — web-аналог диалога 17 (JavaFX Alert с тремя ButtonType).
        router.add("POST", "/api/session/start", request -> {
            Map<String, Object> m;
            if (request.bool("restore", false)) {
                RestoreReport report = state.restore();
                m = StateJson.build(state);
                m.put("restored", true);
                m.put("warnings", new java.util.ArrayList<Object>(report.warnings()));
            } else {
                state.startFresh();
                m = StateJson.build(state);
                m.put("restored", false);
            }
            return ApiResponse.json(m);
        });
        router.add("POST", "/api/session/record", request -> {
            state.startRecording();
            return ApiResponse.json(StateJson.build(state));
        });
        router.add("GET", "/api/session/unrestored-plan", request -> {
            String text = state.unrestoredPlanMarkdown();
            if (text == null) {
                throw new NoSuchElementException("Несохранённого плана из снимка нет");
            }
            return ApiResponse.download("text/markdown; charset=utf-8", text, "Восстановленный план.md");
        });
        router.add("PUT", "/api/session/main", request -> {
            Map<String, Object> json = request.json();
            String selected = json.containsKey("selectedRowId") ? request.string("selectedRowId", "") : null;
            Boolean maximized = json.containsKey("maximized") ? request.bool("maximized", false) : null;
            state.updateMain(WebWindow.boundsFrom(json.get("bounds")), maximized, selected);
            if (json.get("view") instanceof Map<?, ?>) {
                ViewApi.applyView(state, ru.cashprediction.core.json.Json.asObject(json.get("view"), "view"));
                return ApiResponse.json(StateJson.build(state));
            }
            return ok();
        });
        router.add("POST", "/api/session/windows", request -> {
            Map<String, Object> json = request.json();
            Boolean modal = json.containsKey("modal") ? request.bool("modal", true) : null;
            WebWindow window = state.openWindow(request.string("type", ""), modal, request.string("ownerId", ""),
                    ApiRequest.stringMap(ru.cashprediction.core.json.Json.object(json, "context")),
                    ApiRequest.stringMap(ru.cashprediction.core.json.Json.object(json, "fields")),
                    WebWindow.boundsFrom(json.get("bounds")));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", window.windowId());
            m.put("window", window.toJson());
            return ApiResponse.json(m);
        });
        Router.Handler update = request -> {
            Map<String, Object> json = request.json();
            boolean structured = json.containsKey("fields") || json.containsKey("context") || json.containsKey("bounds");
            state.updateWindow(request.pathParam("id"),
                    structured ? ApiRequest.stringMap(ru.cashprediction.core.json.Json.object(json, "fields")) : ApiRequest.stringMap(json),
                    structured && json.containsKey("context")
                            ? ApiRequest.stringMap(ru.cashprediction.core.json.Json.object(json, "context")) : null,
                    WebWindow.boundsFrom(json.get("bounds")));
            return ok();
        };
        router.add("PUT", "/api/session/windows/{id}", update);
        router.add("PUT", "/api/session/windows/{id}/fields", update);
        router.add("DELETE", "/api/session/windows/{id}", request -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("closed", new java.util.ArrayList<Object>(state.closeWindow(request.pathParam("id"))));
            return ApiResponse.json(m);
        });
        // navigator.sendBeacon при закрытии вкладки: окна остаются на сервере, снимок пишется сразу.
        router.add("POST", "/api/session/exit", request -> {
            state.saveNow();
            state.log().info("Вкладка браузера закрыта; состояние окон сохранено на сервере");
            return ok();
        });
        // «Восстановление → Сделать снимок сейчас».
        router.add("POST", "/api/session/snapshot", request -> {
            state.saveNow();
            return ApiResponse.json(StateJson.sessionJson(state));
        });
        // «Восстановление → Показать последний снимок…» — web-аналог Alert с expandableContent: текст web-session.md.
        Router.Handler lastSnapshot = request -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", state.store().sessionFile().toString());
            try {
                m.put("text", state.lastSnapshotText().orElse(null));
                m.put("problem", null);
            } catch (IOException e) {
                // Файл занят или повреждён: это не ошибка запроса, браузер покажет причину в том же Alert.
                m.put("text", null);
                m.put("problem", e.getMessage());
            }
            return ApiResponse.json(m);
        };
        router.add("GET", "/api/session/last", lastSnapshot);
        // Прежнее имя маршрута оставлено для совместимости со страницами, открытыми до обновления.
        router.add("GET", "/api/session/snapshot-text", lastSnapshot);
        // «Восстановление → Очистить снимки»: только через рекордер, маркер текущего сеанса остаётся.
        router.add("POST", "/api/session/clear", request -> {
            state.clearSnapshots();
            return ApiResponse.json(StateJson.sessionJson(state));
        });
        // Необработанная ошибка JavaScript — аналог необработанного исключения в UI-потоке desktop-клиентов:
        // снимок сохраняется, браузер показывает Alert(ERROR) со стеком.
        router.add("POST", "/api/debug/client-error", request -> {
            state.log().info("Необработанная ошибка в браузере: " + request.string("message", "?"));
            state.saveNow();
            return ok();
        });
        router.add("POST", "/api/shutdown", request -> {
            state.log().info("Остановка сервера по команде из браузера");
            startThread("cashprediction-shutdown", shutdown);
            return ok();
        });
        // «Симулировать сбой → Остановить сервер аварийно»: Runtime.halt(3) без снимка и без маркера closed.
        router.add("POST", "/api/debug/crash", request -> {
            state.log().info("Аварийная остановка сервера (симуляция сбоя)");
            startThread("cashprediction-crash", crash);
            return ok();
        });
    }

    /** @return ответ {@code {"ok": true}} */
    static ApiResponse ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return ApiResponse.json(m);
    }

    /**
     * Запускает действие в отдельном потоке с небольшой задержкой, чтобы ответ успел уйти в браузер.
     *
     * @param name   имя потока
     * @param action действие
     */
    private static void startThread(String name, Runnable action) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            action.run();
        }, name);
        thread.start();
    }

    /**
     * Границы окна из JSON (для тестов и повторного использования).
     *
     * @param value объект или {@code null}
     * @return границы или {@code null}
     */
    static WindowBounds bounds(Object value) {
        return WebWindow.boundsFrom(value);
    }
}
