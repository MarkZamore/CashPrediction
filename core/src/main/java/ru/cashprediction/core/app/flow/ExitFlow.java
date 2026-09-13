package ru.cashprediction.core.app.flow;

import java.util.Objects;

/**
 * Выход (спецификация v2, §6.32): крестик, Alt+F4 и «Файл → Выход» идут одним путём.
 *
 * <p><b>Порядок:</b> 1) снимок сеанса немедленно ({@code recorder.saveNow}); 2) если есть изменения — §6.12, «Отмена»
 * или неудачное сохранение прерывают выход; 3) остановить автосохранение и таймеры, записать настройки
 * ({@code SettingsKeeper.flushNow}), закрыть сеанс чисто ({@code recorder.shutdownClean}: после «Не сохранять»
 * финальный снимок без отброшенного плана — {@code PlanState.CLEAN} — и без окон, решение L3); 4)
 * {@code port.exit(CLEAN, 0)}; web — {@code WEB_STOPPED} и экран §6.30.</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class ExitFlow {

    private final FlowContext context;

    /**
     * Создаёт поток.
     *
     * @param context контекст контроллера
     */
    public ExitFlow(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /** Запрос выхода от пользователя (порядок — в описании класса). */
    public void requestExit() {
        throw new UnsupportedOperationException("S2: core-app-file — ExitFlow.requestExit");
    }
}
