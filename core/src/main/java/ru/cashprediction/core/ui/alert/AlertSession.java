package ru.cashprediction.core.ui.alert;

import java.util.Objects;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import ru.cashprediction.core.app.WindowHandle;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/**
 * Сеанс восстанавливаемого сообщения (архитектура §3.5): только назначения deleteRule, deleteOneTime, actualize,
 * applyWhatIf, clearSnapshots ({@link AlertCatalog#RESTORABLE_PURPOSES}). Остальные сообщения показываются без
 * сеанса.
 *
 * <p><b>Поведение (этап S1):</b> {@link #shown()} — {@code SessionRecorder.register} ровно один раз;
 * {@link #closed()} — {@code unregister} ровно один раз; {@link #captureState()} —
 * {@code WindowState(windowId, ALERT, modal, ownerId, bounds, {purpose, targetId}, {})};
 * {@link #applyState(WindowState)} запоминает границы; содержимое пересоздаётся {@code CoreWindowFactory} по
 * назначению и цели (если цели уже нет — {@code restore.warn.targetGone}). Ручка {@link #attach(WindowHandle)}
 * возвращает актуальные границы, а до её появления сохраняются границы исходного снимка.</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class AlertSession implements StatefulWindow {

    /** Контроллер, которому сеанс сообщает о показе и закрытии. */
    public interface Host {

        /**
         * Сообщение показано и должно попасть в запись сеанса.
         *
         * @param session сеанс
         */
        void registered(AlertSession session);

        /**
         * Сообщение закрыто и должно уйти из записи.
         *
         * @param session сеанс
         */
        void unregistered(AlertSession session);
    }

    private final String windowId;
    private final String ownerId;
    private final AlertSpec spec;
    private final Host host;
    private boolean registered;
    private boolean unregistered;
    private boolean shown;
    private boolean closed;
    private WindowHandle handle;
    private WindowBounds bounds;
    private final List<Consumer<AlertSession>> shownCallbacks = new ArrayList<>();

    /**
     * Создаёт сеанс.
     *
     * @param windowId id окна
     * @param ownerId  владелец
     * @param spec     описание сообщения ({@code restorable = true})
     * @param host     контроллер
     */
    public AlertSession(String windowId, String ownerId, AlertSpec spec, Host host) {
        this.windowId = Objects.requireNonNull(windowId, "windowId");
        this.ownerId = ownerId == null || ownerId.isBlank() ? WindowState.MAIN_OWNER : ownerId;
        this.spec = Objects.requireNonNull(spec, "spec");
        this.host = Objects.requireNonNull(host, "host");
    }

    @Override
    public String windowId() {
        return windowId;
    }

    @Override
    public WindowType windowType() {
        return WindowType.ALERT;
    }

    @Override
    public boolean modal() {
        return true;
    }

    @Override
    public String ownerId() {
        return ownerId;
    }

    /** @return описание сообщения */
    public AlertSpec spec() {
        return spec;
    }

    /** @return контроллер сеанса */
    public Host host() {
        return host;
    }

    /**
     * Привязывает ручку реального окна для захвата его границ при следующем снимке.
     *
     * @param handle открытое сообщение клиента
     */
    public void attach(WindowHandle handle) {
        this.handle = Objects.requireNonNull(handle, "handle");
    }

    /** Клиент показал сообщение. */
    public void shown() {
        if (shown || closed) return;
        shown = true;
        if (!registered && spec.restorable()) {
            registered = true;
            host.registered(this);
        }
        List<Consumer<AlertSession>> callbacks = List.copyOf(shownCallbacks);
        shownCallbacks.clear();
        for (Consumer<AlertSession> callback : callbacks) callback.accept(this);
    }

    /**
     * Сообщает о реальном показе, даже если подписчик присоединился после синхронного shown.
     *
     * @param callback продолжение, вызываемое один раз
     */
    public void whenShown(Consumer<AlertSession> callback) {
        Objects.requireNonNull(callback, "callback");
        if (closed) return;
        if (shown) callback.accept(this);
        else shownCallbacks.add(callback);
    }

    /** Сообщение закрыто (любой кнопкой или крестиком). */
    public void closed() {
        closed = true;
        shownCallbacks.clear();
        if (registered && !unregistered) {
            unregistered = true;
            host.unregistered(this);
        }
    }

    @Override
    public WindowState captureState() {
        WindowBounds current = handle == null || handle.bounds() == null ? bounds : handle.bounds();
        return new WindowState(windowId, WindowType.ALERT, true, ownerId, current,
                Map.of(WindowType.CONTEXT_PURPOSE, spec.purpose(), WindowType.CONTEXT_TARGET_ID, spec.targetId()), Map.of());
    }

    @Override
    public void applyState(WindowState state) {
        Objects.requireNonNull(state, "state");
        if (state.type() != null && state.type() != WindowType.ALERT) {
            throw new IllegalArgumentException("state.type");
        }
        bounds = state.bounds();
    }
}
