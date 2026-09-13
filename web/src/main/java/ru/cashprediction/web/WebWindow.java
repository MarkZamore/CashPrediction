package ru.cashprediction.web;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.json.Json;
import ru.cashprediction.core.json.JsonException;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/**
 * Серверный «прокси» окна, открытого в браузере: диалога, калькулятора цели, всплывающей правки суммы.
 *
 * <p>Сам диалог живёт в браузере ({@code <dialog>}), но его состояние (контекст и введённые значения)
 * браузер отправляет на сервер при каждом вводе. Поэтому окно участвует в снимке сессии как обычный
 * {@link StatefulWindow}, а после перезагрузки страницы или сбоя сервера браузер открывает окна заново
 * с теми же значениями — это и есть «состояние хранится на сервере».</p>
 *
 * <p>JavaFX: {@code Dialog<R>} → Swing: {@code JDialog} ({@code SwingDialog<R>}) → Web: {@code <dialog>} + этот прокси.</p>
 *
 * <p><b>Потоки.</b> Идентификатор, тип, модальность и владелец неизменяемы и безопасны из любого потока.
 * Изменяемые части (границы, контекст, поля) читаются и меняются только под монитором {@link ServerState}
 * — это «UI-поток» web-сервера (см. {@link ServerUiExecutor}).</p>
 */
public final class WebWindow implements StatefulWindow {

    private final String id;
    private final WindowType type;
    private final boolean modal;
    private final String ownerId;
    private WindowBounds bounds;
    private final Map<String, String> context = new LinkedHashMap<>();
    private final Map<String, String> fields = new LinkedHashMap<>();

    /**
     * Создаёт окно по состоянию.
     *
     * @param state состояние с уже назначенным идентификатором текущего сеанса
     */
    public WebWindow(WindowState state) {
        Objects.requireNonNull(state, "state");
        this.id = state.id();
        this.type = Objects.requireNonNull(state.type(), "Тип окна не задан");
        this.modal = state.modal();
        this.ownerId = state.ownerId();
        applyState(state);
    }

    @Override
    public String windowId() {
        return id;
    }

    @Override
    public WindowType windowType() {
        return type;
    }

    @Override
    public boolean modal() {
        return modal;
    }

    @Override
    public String ownerId() {
        return ownerId;
    }

    @Override
    public WindowState captureState() {
        return new WindowState(id, type, modal, ownerId, bounds, context, fields);
    }

    /**
     * Переносит контекст, поля и границы из состояния (полная замена, как при восстановлении).
     *
     * @param state состояние
     */
    @Override
    public void applyState(WindowState state) {
        bounds = state.bounds();
        context.clear();
        context.putAll(state.context());
        fields.clear();
        fields.putAll(state.fields());
    }

    /**
     * Сливает изменения, пришедшие из браузера: переданные ключи перезаписываются, остальные остаются.
     * Значения хранятся как есть, в том числе недописанные («95 0»), — чтобы восстановить ввод буква в букву.
     *
     * @param newFields  изменённые поля или {@code null}
     * @param newContext изменённый контекст или {@code null} (например, номер страницы мастера)
     * @param newBounds  новые границы или {@code null} — не менять
     */
    public void merge(Map<String, String> newFields, Map<String, String> newContext, WindowBounds newBounds) {
        if (newFields != null) {
            newFields.forEach((key, value) -> fields.put(key, Objects.requireNonNullElse(value, "")));
        }
        if (newContext != null) {
            newContext.forEach((key, value) -> context.put(key, Objects.requireNonNullElse(value, "")));
        }
        if (newBounds != null) {
            bounds = newBounds;
        }
    }

    /**
     * JSON-представление окна для браузера.
     *
     * @return {@code {id, type, title, modal, ownerId, bounds, context, fields}}
     */
    public Map<String, Object> toJson() {
        return toJson(captureState());
    }

    /**
     * JSON-представление состояния окна (в том числе окна из снимка, ожидающего восстановления).
     *
     * @param state состояние
     * @return объект окна
     */
    public static Map<String, Object> toJson(WindowState state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", state.id());
        m.put("type", state.type() == null ? null : state.type().name());
        m.put("title", state.type() == null ? "Неизвестное окно" : state.type().title());
        m.put("modal", state.modal());
        m.put("ownerId", state.ownerId());
        m.put("bounds", boundsJson(state.bounds()));
        m.put("context", new LinkedHashMap<String, Object>(state.context()));
        m.put("fields", new LinkedHashMap<String, Object>(state.fields()));
        return m;
    }

    /**
     * Границы окна в JSON.
     *
     * @param bounds границы или {@code null}
     * @return {@code {x, y, width, height}} или {@code null}
     */
    public static Map<String, Object> boundsJson(WindowBounds bounds) {
        if (bounds == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", bounds.x());
        m.put("y", bounds.y());
        m.put("width", bounds.width());
        m.put("height", bounds.height());
        return m;
    }

    /**
     * Границы окна из JSON.
     *
     * @param value объект {@code {x, y, width, height}} или {@code null}
     * @return границы или {@code null}
     * @throws ApiException 400, если объект некорректен
     */
    public static WindowBounds boundsFrom(Object value) {
        if (value == null) {
            return null;
        }
        try {
            Map<String, Object> m = Json.asObject(value, "bounds");
            return new WindowBounds(Json.doubleValue(m, "x", 0), Json.doubleValue(m, "y", 0),
                    Json.doubleValue(m, "width", 0), Json.doubleValue(m, "height", 0));
        } catch (JsonException | IllegalArgumentException e) {
            throw ApiException.badRequest("Некорректные границы окна: " + e.getMessage());
        }
    }
}
