package ru.cashprediction.core.app.flow;

import java.util.Objects;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.app.ExitKind;
import ru.cashprediction.core.ui.form.FormSession;

/** Завершает сеанс: снимок, подтверждение правок, остановка таймеров, настройки, чистый маркер и выход. */
public final class ExitFlow {
    private final FlowContext context;
    private boolean finishing;

    /** @param context контекст контроллера, доступный только в его потоке */
    public ExitFlow(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() { return context; }

    /** @return снимается ли финальный чистый снимок без отвергнутого несохранённого плана */
    boolean cleanExitSnapshot() { return finishing; }

    /** Запрашивает обычный выход; отказ или ошибка сохранения оставляют работающий сеанс и таймеры. */
    public void requestExit() {
        if (finishing || context.files().saving() || context.state().windows().modalOpen()) return;
        if (context.recorder() != null) context.recorder().saveNow();
        context.files().confirmDiscard(this::finish);
    }

    /** Выполняет порядок чистого выхода после разрешения пользователя. */
    private void finish() {
        if (finishing) return;
        finishing = true;
        context.autosave().stop();
        // После «Не сохранять» финальный снимок не должен содержать отвергнутые правки.
        if (context.document().isDirty()) {
            context.document().replace(context.document().plan(), context.document().file().orElse(null),
                    false, context.document().loadDiagnostics());
        }
        // JavaFX: Dialog → Swing: SwingDialog → Web: dialog.
        for (var window : context.state().windows().windows()) {
            context.session(window.windowId()).ifPresent(FormSession::closeRequested);
        }
        context.settingsKeeper().flushNow();
        if (context.recorder() != null) {
            // Даже окно, закрытие которого уже поставлено в очередь клиента, не входит в финальный снимок.
            for (var window : context.recorder().registeredWindows()) context.recorder().unregister(window);
            context.recorder().shutdownClean();
        }
        context.port().scheduler().shutdown();
        ExitKind kind = context.port().profile().kind() == ClientKind.WEB ? ExitKind.WEB_STOPPED : ExitKind.CLEAN;
        context.port().exit(kind, 0);
    }
}
