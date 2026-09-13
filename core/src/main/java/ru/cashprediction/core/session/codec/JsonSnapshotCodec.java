package ru.cashprediction.core.session.codec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.SessionMarker;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/**
 * JSON-представление снимка: используется реестром (текст режется на куски) и web-API.
 *
 * <p>Форма документа (компактная запись, здесь с отступами для наглядности):</p>
 * <pre>{@code
 * {
 *   "schemaVersion": 1, "savedAt": "2026-09-13T10:15:30.123Z", "client": "fx",
 *   "main": {"bounds": {"x": 100, "y": 80, "width": 1200, "height": 800}, "maximized": false,
 *            "view": "TABLE", "planPath": "Семейный бюджет 2026.md", "period": "12m",
 *            "filters": {"showIncome": true}, "filterText": "", "selectedRowId": "r2@2026-10-01",
 *            "whatIfExtra": "5000,00"},                          // необязательное: только если задано
 *   "plan": {"dirty": true, "markdown": "# План: ..."},
 *   "windows": [{"id": "w1", "type": "RULE_EDITOR", "modal": true, "ownerId": "main", "bounds": null,
 *                "context": {"mode": "edit", "ruleId": "r3"}, "fields": {"title": "Аренда"}}]
 * }
 * }</pre>
 *
 * <p>Окно неизвестного типа ({@code type = null} в записи) пишется как {@code "type": null}; при
 * чтении неизвестное имя типа тоже превращается в {@code null}, а не в ошибку всего снимка.</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class JsonSnapshotCodec implements SnapshotCodec<String> {

    /** Создаёт кодек (состояния нет, экземпляры взаимозаменяемы). */
    public JsonSnapshotCodec() {
    }

    @Override
    public String formatName() {
        return "JSON";
    }

    @Override
    public String encode(SessionSnapshot snapshot) {
        return JsonWriter.write(toJsonObject(snapshot));
    }

    @Override
    public SessionSnapshot decode(String encoded) throws SnapshotFormatException {
        Map<String, Object> root;
        try {
            root = JsonParser.parseObject(encoded);
        } catch (RuntimeException e) {
            throw new SnapshotFormatException("Снимок в формате JSON повреждён: " + e.getMessage(), e);
        }
        return fromJsonObject(root);
    }

    /**
     * Строит JSON-объект снимка (для встраивания в ответ web-API).
     *
     * @param snapshot снимок
     * @return объект с сохранённым порядком ключей
     */
    public Map<String, Object> toJsonObject(SessionSnapshot snapshot) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", (long) snapshot.schemaVersion());
        root.put("savedAt", snapshot.savedAt().toString());
        root.put("client", snapshot.client());
        root.put("main", mainToJson(snapshot.main()));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("dirty", snapshot.plan().dirty());
        plan.put("markdown", snapshot.plan().markdown());
        root.put("plan", plan);
        List<Object> windows = new ArrayList<>();
        for (WindowState window : snapshot.windows()) {
            windows.add(windowToJson(window));
        }
        root.put("windows", windows);
        return root;
    }

    /**
     * Разбирает снимок из уже разобранного JSON-объекта (например, из тела HTTP-запроса).
     *
     * @param root JSON-объект снимка
     * @return снимок
     * @throws SnapshotFormatException если структура или значения некорректны
     */
    public SessionSnapshot fromJsonObject(Map<String, Object> root) throws SnapshotFormatException {
        try {
            long schema = Json.requireLong(root, "schemaVersion");
            if (schema < 1 || schema > Integer.MAX_VALUE) {
                throw new SnapshotFormatException("Некорректная версия схемы снимка: " + schema);
            }
            int schemaVersion = CodecText.checkSchema((int) schema);
            var savedAt = CodecText.parseInstant(Json.requireString(root, "savedAt"), "savedAt");
            String client = Json.requireString(root, "client");
            MainWindowState main = mainFromJson(Json.object(root, "main"));
            Map<String, Object> planJson = Json.object(root, "plan");
            PlanState plan = new PlanState(Json.bool(planJson, "dirty", false), Json.string(planJson, "markdown", ""));
            List<WindowState> windows = new ArrayList<>();
            int index = 0;
            for (Object item : Json.list(root, "windows")) {
                windows.add(windowFromJson(Json.asObject(item, "windows[" + index + "]")));
                index++;
            }
            return new SessionSnapshot(schemaVersion, savedAt, client, main, plan, windows);
        } catch (SnapshotFormatException e) {
            throw e;
        } catch (RuntimeException e) {
            // Сюда попадают и неверные типы полей (JsonException), и нарушения инвариантов записей.
            throw new SnapshotFormatException("Снимок в формате JSON повреждён: " + e.getMessage(), e);
        }
    }

    /**
     * Строит JSON-объект маркера сеанса (для web-API {@code GET /api/session}).
     *
     * @param marker маркер
     * @return объект {@code {"state", "pid", "startedAt", "client"}}
     */
    public Map<String, Object> markerToJsonObject(SessionMarker marker) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("state", marker.state());
        json.put("pid", marker.pid());
        json.put("startedAt", marker.startedAt().toString());
        json.put("client", marker.client());
        return json;
    }

    /**
     * Разбирает маркер сеанса из JSON-объекта.
     *
     * @param json объект маркера
     * @return маркер
     * @throws SnapshotFormatException если поля отсутствуют или некорректны
     */
    public SessionMarker markerFromJsonObject(Map<String, Object> json) throws SnapshotFormatException {
        try {
            return new SessionMarker(Json.requireString(json, "state"), Json.requireLong(json, "pid"),
                    CodecText.parseInstant(Json.requireString(json, "startedAt"), "startedAt"),
                    Json.requireString(json, "client"));
        } catch (SnapshotFormatException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new SnapshotFormatException("Маркер сеанса в формате JSON повреждён: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> mainToJson(MainWindowState main) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("bounds", boundsToJson(main.bounds()));
        json.put("maximized", main.maximized());
        json.put("view", main.view());
        json.put("planPath", main.planPath());
        json.put("period", main.period());
        json.put("filters", new LinkedHashMap<>(main.filters()));
        json.put("filterText", main.filterText());
        json.put("selectedRowId", main.selectedRowId());
        // Необязательное поле схемы 1: пишется только при значении, чтобы снимки без «что-если» не менялись.
        if (!main.whatIfExtra().isEmpty()) {
            json.put("whatIfExtra", main.whatIfExtra());
        }
        return json;
    }

    private static MainWindowState mainFromJson(Map<String, Object> json) throws SnapshotFormatException {
        Map<String, Boolean> filters = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : Json.object(json, "filters").entrySet()) {
            if (!(entry.getValue() instanceof Boolean value)) {
                throw new SnapshotFormatException("Фильтр «" + entry.getKey() + "» должен быть true или false");
            }
            filters.put(entry.getKey(), value);
        }
        return new MainWindowState(boundsFromJson(json), Json.bool(json, "maximized", false),
                Json.string(json, "view", ""), Json.string(json, "planPath", ""), Json.string(json, "period", ""),
                filters, Json.string(json, "filterText", ""), Json.string(json, "selectedRowId", ""),
                Json.string(json, "whatIfExtra", ""));
    }

    private static Map<String, Object> windowToJson(WindowState window) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", window.id());
        json.put("type", window.type() == null ? null : window.type().name());
        json.put("modal", window.modal());
        json.put("ownerId", window.ownerId());
        json.put("bounds", boundsToJson(window.bounds()));
        json.put("context", new LinkedHashMap<>(window.context()));
        json.put("fields", new LinkedHashMap<>(window.fields()));
        return json;
    }

    private static WindowState windowFromJson(Map<String, Object> json) throws SnapshotFormatException {
        WindowType type = WindowType.fromName(Json.string(json, "type", null)).orElse(null);
        return new WindowState(Json.requireString(json, "id"), type, Json.bool(json, "modal", true),
                Json.string(json, "ownerId", WindowState.MAIN_OWNER), boundsFromJson(json),
                stringMap(json, "context"), stringMap(json, "fields"));
    }

    private static Map<String, Object> boundsToJson(WindowBounds bounds) {
        if (bounds == null) {
            return null;
        }
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("x", CodecText.jsonNumber(bounds.x()));
        json.put("y", CodecText.jsonNumber(bounds.y()));
        json.put("width", CodecText.jsonNumber(bounds.width()));
        json.put("height", CodecText.jsonNumber(bounds.height()));
        return json;
    }

    private static WindowBounds boundsFromJson(Map<String, Object> owner) {
        return Json.optionalObject(owner, "bounds")
                .map(b -> new WindowBounds(Json.doubleValue(b, "x", 0), Json.doubleValue(b, "y", 0),
                        Json.doubleValue(b, "width", 0), Json.doubleValue(b, "height", 0)))
                .orElse(null);
    }

    private static Map<String, String> stringMap(Map<String, Object> owner, String key) throws SnapshotFormatException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : Json.object(owner, key).entrySet()) {
            if (!(entry.getValue() instanceof String value)) {
                throw new SnapshotFormatException("Значение «" + key + "." + entry.getKey() + "» должно быть строкой");
            }
            result.put(entry.getKey(), value);
        }
        return result;
    }
}
