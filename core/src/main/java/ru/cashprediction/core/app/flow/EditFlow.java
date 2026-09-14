package ru.cashprediction.core.app.flow;

import java.time.LocalDate;
import java.util.Objects;
import java.util.function.UnaryOperator;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Plan;

/**
 * Меню «Правка» и действия строк таблицы (спецификация v2, §3.2, §5.2 «Контекстное меню строки», §5.6.1, §6.2–§6.5,
 * §6.7, §6.11, §6.23, §6.25; тексты отмены §8.4, статусы §8.2).
 *
 * <p><b>Единая точка правки:</b> {@link #edit(String, String, UnaryOperator)} — {@code document.edit(undoText, change)};
 * исключение модели показывается в строке проблем открытой формы или сообщением {@code err.editFailed}; после
 * успеха — статус. <b>{@code edit.edit} по видам строк:</b> RULE → редактор правила; ONE_TIME → разовая; START →
 * «Параметры плана»; WHAT_IF → {@code status.hint.whatIfRow}.</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class EditFlow {

    private final FlowContext context;

    /**
     * Создаёт поток.
     *
     * @param context контекст контроллера
     */
    public EditFlow(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /**
     * Применяет изменение плана одним шагом отмены.
     *
     * @param undoText  описание для «Отменить: {0}» (готовый текст {@code undo.*})
     * @param statusKey статус после успеха ({@code status.msg.*}) или пустая строка
     * @param change    изменение плана
     * @return {@code true}, если изменение применено
     */
    public boolean edit(String undoText, String statusKey, UnaryOperator<Plan> change) {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.edit");
    }

    /**
     * {@code edit.addIncome}/{@code edit.addExpense}: редактор правила с видом.
     *
     * @param kind вид по умолчанию
     */
    public void addRule(Kind kind) {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.addRule");
    }

    /**
     * {@code edit.addOneTime}, {@code row.addOneTime}, {@code chart.addOneTime}: разовая операция с видом «Расход».
     *
     * @param date дата по умолчанию или {@code null}
     */
    public void addOneTime(LocalDate date) {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.addOneTime");
    }

    /**
     * {@code edit.edit}, {@code row.edit}, двойной щелчок: по виду строки.
     *
     * @param rowId id строки
     */
    public void editRow(String rowId) {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.editRow");
    }

    /**
     * {@code edit.delete}, {@code row.delete}: подтверждение §6.11.
     *
     * @param rowId id строки
     */
    public void deleteRow(String rowId) {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.deleteRow");
    }

    /**
     * {@code edit.adjust}, {@code row.adjust}, «Скорректировать выбранное событие…» тулбара: форма §6.5.
     *
     * @param rowId id строки RULE
     */
    public void adjust(String rowId) {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.adjust");
    }

    /**
     * {@code edit.skip}, {@code row.skip}: {@code undo.skip}, {@code status.msg.skipped} или {@code skippedHidden}.
     *
     * @param rowId id строки RULE
     */
    public void skip(String rowId) {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.skip");
    }

    /**
     * {@code edit.reset}, {@code row.reset}: {@code undo.reset}, {@code status.msg.adjustReset}.
     *
     * @param rowId id строки RULE
     */
    public void reset(String rowId) {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.reset");
    }

    /**
     * {@code row.quickEdit}, двойной щелчок по сумме: всплывающее окно §5.6.1 под ячейкой
     * ({@code context.openForm(FormRequest.fresh(new QuickEditForm(), QUICK_EDIT_POPUP, false, …),
     * Placement.underCell(rowId, columnId), …)}); уже открытая быстрая правка сначала закрывается.
     *
     * @param rowId    id строки RULE
     * @param columnId {@code income} или {@code expense}
     */
    public void quickEdit(String rowId, String columnId) {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.quickEdit");
    }

    /**
     * {@code row.goToRule}: редактор правила строки.
     *
     * @param rowId id строки RULE
     */
    public void goToRule(String rowId) {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.goToRule");
    }

    /**
     * {@code row.disableRule}: без подтверждения, {@code undo.ruleDisable}, {@code status.msg.ruleDisabled}.
     *
     * @param rowId id строки RULE
     */
    public void disableRule(String rowId) {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.disableRule");
    }

    /**
     * {@code row.copy}: тексты ячеек через табуляцию, {@code status.msg.rowCopied}.
     *
     * @param rowId id строки
     */
    public void copyRow(String rowId) {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.copyRow");
    }

    /**
     * {@code total.copy}: «Октябрь 2026⇥Доход⇥Расход⇥Баланс», {@code status.msg.totalCopied}.
     *
     * @param rowId id строки итога
     */
    public void copyTotal(String rowId) {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.copyTotal");
    }

    /** {@code edit.undo}: {@code status.msg.undone}. */
    public void undo() {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.undo");
    }

    /** {@code edit.redo}: {@code status.msg.redone}. */
    public void redo() {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.redo");
    }

    /** {@code edit.planSettings}: форма §6.2. */
    public void planSettings() {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.planSettings");
    }

    /** {@code edit.actualize}: §6.25. */
    public void actualize() {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.actualize");
    }

    /** {@code edit.reconcile}: §6.7 (иначе {@code info.reconcileUnavailable}). */
    public void reconcile() {
        throw new UnsupportedOperationException("S2: core-app-edit - EditFlow.reconcile");
    }
}
