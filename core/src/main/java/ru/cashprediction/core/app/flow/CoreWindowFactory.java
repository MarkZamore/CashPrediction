package ru.cashprediction.core.app.flow;

import java.util.Objects;
import java.util.function.Consumer;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowFactory;
import ru.cashprediction.core.session.WindowState;

/**
 * Открытие восстановленных окон тем же путём, что и из меню (архитектура §3.8).
 *
 * <p><b>Порядок:</b> 1) {@code FormCatalog.forRestore(state, app)} строит {@link FormRequest} (с
 * {@code restored = state}) или сообщает {@code restore.warn.*} через {@code onFailed}; 2)
 * {@code context.openForm(request, Placement.restored(ownerId, state.bounds()), onResult)} — тот же путь, что у меню:
 * он сам вызывает {@code FormSession.applyState(state)} и {@code port.openForm}; 3) {@code onShown} — когда клиент
 * вызвал {@code FormSession.shown()}. Восстанавливаемые сообщения (deleteRule, …) — {@code ConfirmForms} и
 * {@code context.showAlert} (он создаёт {@code AlertSession} для {@code spec.restorable()}).</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class CoreWindowFactory implements WindowFactory {

    private final FlowContext context;

    /**
     * Создаёт фабрику.
     *
     * @param context контекст контроллера
     */
    public CoreWindowFactory(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    @Override
    public void open(WindowState state, String ownerId, Consumer<StatefulWindow> onShown, Consumer<String> onFailed) {
        throw new UnsupportedOperationException("S2: core-app-session - CoreWindowFactory.open");
    }
}
