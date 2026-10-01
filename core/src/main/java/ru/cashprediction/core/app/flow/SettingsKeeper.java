package ru.cashprediction.core.app.flow;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import ru.cashprediction.core.markdown.SettingsMarkdown;
import ru.cashprediction.core.session.Scheduler;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.DesignTokens;

/** Записывает настройки через 700 мс после последнего изменения, сохраняя ошибку до успешной записи. */
public final class SettingsKeeper {
    private final FlowContext context;
    private Scheduler.Task pending;
    private long generation;

    /** @param context контекст контроллера, доступный только в его потоке */
    public SettingsKeeper(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() { return context; }

    /** Перезапускает задержку записи; уже переданный исполнителю старый вызов также отменяется. */
    public void changed() {
        cancel();
        long expected = generation;
        pending = context.port().scheduler().schedule(() -> context.port().executor().execute(() -> {
            if (expected != generation) return;
            pending = null;
            write();
        }), Duration.ofMillis(DesignTokens.SETTINGS_DELAY_MS));
    }

    /** Отменяет отложенную запись и записывает актуальные настройки немедленно, в том числе при выходе. */
    public void flushNow() {
        cancel();
        write();
    }

    /** Отменяет таймер и инвалидирует уже поставленный в очередь обратный вызов. */
    private void cancel() {
        generation++;
        if (pending != null) pending.cancel();
        pending = null;
    }

    /** Успех снимает постоянную ошибку; неудача не мешает чистому завершению сеанса. */
    private void write() {
        try {
            SettingsMarkdown.save(context.environment().cashMemory().resolve("settings.md"), context.state().settings());
            context.persistentStatus("settingsFailed", null);
        } catch (IOException | RuntimeException error) {
            context.persistentStatus("settingsFailed", UiText.get("status.msg.settingsFailed", error.getMessage()));
        }
    }
}
