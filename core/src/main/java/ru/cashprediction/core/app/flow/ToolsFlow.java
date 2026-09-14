package ru.cashprediction.core.app.flow;

import java.util.Objects;

/**
 * Меню «Инструменты» и действия карточек (спецификация v2, §3.4, §5.1, §6.6, §6.8, §6.17, §6.26).
 *
 * <p>Калькулятор цели — один экземпляр: повторный вызов поднимает окно
 * ({@code context.singleInstance("GOAL_CALCULATOR")} → {@code FormSession.handle().toFront()}). «Что-если»
 * не пишется в settings, но попадает в снимок (флажки — ключи {@code whatIfIncome}/{@code whatIfExpense} карты
 * {@code filters}, доп. экономия — {@code MainWindowState.whatIfExtra}).</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class ToolsFlow {

    private final FlowContext context;

    /**
     * Создаёт поток.
     *
     * @param context контекст контроллера
     */
    public ToolsFlow(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /** {@code tools.goal}: открыть или поднять калькулятор цели §6.6. */
    public void goalCalculator() {
        throw new UnsupportedOperationException("S2: core-app-edit - ToolsFlow.goalCalculator");
    }

    /** {@code whatIf.income}: доходы × 0,90 вкл/выкл. */
    public void toggleWhatIfIncome() {
        throw new UnsupportedOperationException("S2: core-app-edit - ToolsFlow.toggleWhatIfIncome");
    }

    /** {@code whatIf.expense}: расходы × 1,10 вкл/выкл. */
    public void toggleWhatIfExpense() {
        throw new UnsupportedOperationException("S2: core-app-edit - ToolsFlow.toggleWhatIfExpense");
    }

    /**
     * {@code whatIf.extra}: доп. экономия в месяц (целые рубли 0..10 000 000).
     *
     * @param amountMajor сумма в целых единицах валюты
     */
    public void setWhatIfExtra(long amountMajor) {
        throw new UnsupportedOperationException("S2: core-app-edit - ToolsFlow.setWhatIfExtra");
    }

    /** {@code whatIf.apply}: подтверждение §6.26, {@code status.msg.whatIfApplied}. */
    public void applyWhatIf() {
        throw new UnsupportedOperationException("S2: core-app-edit - ToolsFlow.applyWhatIf");
    }

    /** {@code whatIf.reset}: выключить «что-если», {@code status.msg.whatIfReset}. */
    public void resetWhatIf() {
        throw new UnsupportedOperationException("S2: core-app-edit - ToolsFlow.resetWhatIf");
    }

    /** {@code tools.validate}: §6.17 «Проверка плана». */
    public void validate() {
        throw new UnsupportedOperationException("S2: core-app-edit - ToolsFlow.validate");
    }

    /** {@code tools.cleanup}: удалить неиспользуемые корректировки, §6.17. */
    public void cleanup() {
        throw new UnsupportedOperationException("S2: core-app-edit - ToolsFlow.cleanup");
    }

    /** {@code tools.currency}: CHOICE currency §6.8, затем при «другая…» TEXT_INPUT customCurrency. */
    public void currency() {
        throw new UnsupportedOperationException("S2: core-app-edit - ToolsFlow.currency");
    }

    /**
     * {@code card.copyValue}: «{Заголовок}: {значение}», {@code status.msg.valueCopied}.
     *
     * @param cardId id карточки
     */
    public void copyCardValue(String cardId) {
        throw new UnsupportedOperationException("S2: core-app-edit - ToolsFlow.copyCardValue");
    }
}
