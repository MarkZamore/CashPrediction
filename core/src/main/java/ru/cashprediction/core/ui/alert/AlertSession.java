package ru.cashprediction.core.ui.alert;

import java.util.Objects;
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
 * {@link #applyState(WindowState)} ничего не меняет (сообщение пересоздаётся {@code CoreWindowFactory} по
 * назначению и цели; если цели уже нет — {@code restore.warn.targetGone}).</p>
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

    /** Клиент показал сообщение. */
    public void shown() {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertSession.shown");
    }

    /** Сообщение закрыто (любой кнопкой или крестиком). */
    public void closed() {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertSession.closed");
    }

    @Override
    public WindowState captureState() {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertSession.captureState");
    }

    @Override
    public void applyState(WindowState state) {
        throw new UnsupportedOperationException("S1: core-forms-framework - AlertSession.applyState");
    }
}
