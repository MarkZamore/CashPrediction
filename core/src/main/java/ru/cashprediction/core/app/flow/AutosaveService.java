package ru.cashprediction.core.app.flow;

import java.time.Duration;
import java.nio.file.Path;
import java.util.Objects;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.session.Scheduler;
import ru.cashprediction.core.ui.token.DesignTokens;

/** Сохраняет изменённый план через секунду; один и тот же пропуск или отказ не повторяет сообщение. */
public final class AutosaveService {
    private final FlowContext context;
    private Scheduler.Task pending;
    private long generation;
    private Boolean enabled;
    private boolean stopped;
    private Plan attemptedPlan;
    private Path attemptedFile;

    /** @param context контекст контроллера, доступный только в его потоке */
    public AutosaveService(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() { return context; }

    /** Перезапускает секундную задержку после изменения документа, если автосохранение включено. */
    public void documentChanged() {
        cancel();
        if (stopped || !isEnabled() || !context.document().isDirty()) return;
        long expected = generation;
        pending = context.port().scheduler().schedule(() -> context.port().executor().execute(() -> {
            if (expected != generation || stopped || !isEnabled()) return;
            pending = null;
            if (!context.document().isDirty()) {
                context.setAutosaveProblem("");
                return;
            }
            Plan plan = context.document().plan();
            Path file = context.document().file().orElse(null);
            if (plan.equals(attemptedPlan) && Objects.equals(file, attemptedFile)) return;
            if (context.state().windows().modalOpen() || context.files().saving()) {
                documentChanged();
                return;
            }
            attemptedPlan = plan;
            attemptedFile = file;
            context.files().saveAutomatically();
        }), Duration.ofMillis(DesignTokens.AUTOSAVE_DELAY_MS));
    }

    /**
     * Включает автосохранение с новой попыткой либо отменяет таймер и снимает прежнюю проблему.
     * @param enabled включено ли автосохранение
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled && !stopped) {
            attemptedPlan = null;
            attemptedFile = null;
            documentChanged();
        } else {
            cancel();
            context.setAutosaveProblem("");
        }
    }

    /** Окончательно останавливает службу при выходе, включая уже поставленные в очередь вызовы. */
    public void stop() {
        stopped = true;
        cancel();
    }

    /** Отменяет таймер и инвалидирует поколение отложенного вызова. */
    private void cancel() {
        generation++;
        if (pending != null) pending.cancel();
        pending = null;
    }

    /** До первого переключения использует настройку, прочитанную StartupFlow, без обращения к контексту в конструкторе. */
    private boolean isEnabled() {
        return enabled != null ? enabled : context.state().settings().autosave();
    }
}
