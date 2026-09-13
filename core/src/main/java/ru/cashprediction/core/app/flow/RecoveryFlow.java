package ru.cashprediction.core.app.flow;

import java.util.Objects;
import ru.cashprediction.core.document.RecoveryStoreKind;

/**
 * Меню «Восстановление» и необработанные ошибки (спецификация v2, §3.5, §6.27, §6.33).
 *
 * <p><b>Необработанная ошибка:</b> снимок сохраняется ({@code recorder.saveNow}); сообщение §6.33 с кнопкой
 * [Закрыть программу] → {@code port.exit(HALT, 2)}; повторная ошибка при открытом сообщении пишется только в stderr;
 * web — ошибка JS не роняет сервер (кнопки [Перезагрузить страницу] [Продолжить работу]).</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class RecoveryFlow {

    private final FlowContext context;

    /**
     * Создаёт поток.
     *
     * @param context контекст контроллера
     */
    public RecoveryFlow(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /**
     * {@code recovery.store.registry}/{@code xml}: хранилище по умолчанию для диалога восстановления.
     *
     * @param store хранилище
     */
    public void setDefaultStore(RecoveryStoreKind store) {
        throw new UnsupportedOperationException("S2: core-app-session — RecoveryFlow.setDefaultStore");
    }

    /** {@code recovery.snapshotNow}: {@code status.msg.snapshot} или {@code info.recordingOff}. */
    public void snapshotNow() {
        throw new UnsupportedOperationException("S2: core-app-session — RecoveryFlow.snapshotNow");
    }

    /** {@code recovery.showLast}: §6.27 «Последний снимок», хранилище по умолчанию первым. */
    public void showLast() {
        throw new UnsupportedOperationException("S2: core-app-session — RecoveryFlow.showLast");
    }

    /** {@code recovery.clear}: подтверждение §6.27, {@code recorder.clearSnapshots}. */
    public void clear() {
        throw new UnsupportedOperationException("S2: core-app-session — RecoveryFlow.clear");
    }

    /** {@code recovery.simulate.halt}: подтверждение, затем {@code port.exit(HALT, 3)} (web — {@code WEB_CRASHED}). */
    public void simulateHalt() {
        throw new UnsupportedOperationException("S2: core-app-session — RecoveryFlow.simulateHalt");
    }

    /** {@code recovery.simulate.exception}: бросает исключение в потоке интерфейса → §6.33. */
    public void simulateException() {
        throw new UnsupportedOperationException("S2: core-app-session — RecoveryFlow.simulateException");
    }

    /**
     * Необработанное исключение (§6.33).
     *
     * @param thread поток
     * @param error  исключение
     */
    public void uncaught(Thread thread, Throwable error) {
        throw new UnsupportedOperationException("S2: core-app-session — RecoveryFlow.uncaught");
    }
}
