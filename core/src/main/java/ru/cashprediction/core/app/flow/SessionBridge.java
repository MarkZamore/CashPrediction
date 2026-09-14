package ru.cashprediction.core.app.flow;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.RestoreTarget;
import ru.cashprediction.core.session.SnapshotSource;

/**
 * Мост между контроллером и записью/восстановлением сеанса (архитектура §3.8).
 *
 * <p><b>{@link #captureMain()}:</b> границы и развёрнутость — {@code port.mainGeometry()}; вид ({@code TABLE}/
 * {@code CHART}); период; флажки вида; дополнительные ключи {@code filters}: {@code pastExpanded},
 * {@code whatIfIncome}, {@code whatIfExpense}; текст фильтра; выделение; {@code whatIfExtra} (каноническая сумма или
 * пусто). <b>{@link #capturePlan()}:</b> несохранённый план — markdown; сохранённый без изменений —
 * {@code PlanState.CLEAN}; после «Не сохранять» при выходе — {@code CLEAN} (решение L3).
 * <b>{@link #applyMain(MainWindowState)}</b> восстанавливает всё перечисленное; старые снимки без новых ключей —
 * значения по умолчанию.</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class SessionBridge implements SnapshotSource, RestoreTarget {

    private final FlowContext context;

    /**
     * Создаёт мост.
     *
     * @param context контекст контроллера
     */
    public SessionBridge(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    @Override
    public MainWindowState captureMain() {
        throw new UnsupportedOperationException("S2: core-app-session - SessionBridge.captureMain");
    }

    @Override
    public PlanState capturePlan() {
        throw new UnsupportedOperationException("S2: core-app-session - SessionBridge.capturePlan");
    }

    @Override
    public void loadPlan(PlanState plan, String planPath, Consumer<String> warn) {
        throw new UnsupportedOperationException("S2: core-app-session - SessionBridge.loadPlan");
    }

    @Override
    public void applyMain(MainWindowState main) {
        throw new UnsupportedOperationException("S2: core-app-session - SessionBridge.applyMain");
    }

    @Override
    public void showMainWindow() {
        throw new UnsupportedOperationException("S2: core-app-session - SessionBridge.showMainWindow");
    }

    @Override
    public void selectRow(String rowId) {
        throw new UnsupportedOperationException("S2: core-app-session - SessionBridge.selectRow");
    }

    @Override
    public Set<String> existingTargetIds() {
        throw new UnsupportedOperationException("S2: core-app-session - SessionBridge.existingTargetIds");
    }
}
