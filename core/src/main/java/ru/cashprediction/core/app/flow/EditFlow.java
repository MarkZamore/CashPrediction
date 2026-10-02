package ru.cashprediction.core.app.flow;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import ru.cashprediction.core.app.Placement;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.command.RowRef;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.form.FieldCodec;
import ru.cashprediction.core.ui.forms.ops.*;
import ru.cashprediction.core.ui.forms.plan.PlanSettingsForm;
import ru.cashprediction.core.ui.forms.simple.TextInputForms;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.status.StatusLevel;
import ru.cashprediction.core.ui.view.table.LazyTableModel;
import ru.cashprediction.core.ui.view.table.RowKind;
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
    /** Ошибку результата формы показывает сам FormSession, сохраняя поля и окно. */
    private boolean formResult;

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
     * <p>Равный план не создаёт историю и статус. В обработчике результата формы исключение передаётся
     * {@code FormSession}, который сохраняет окно и показывает строку проблем; вне формы показывается
     * {@code err.editFailed}, документ и история при исключении функции изменения остаются прежними.</p>
     *
     * @return {@code true}, если план изменился; {@code false} при равном плане или ошибке вне формы
     */
    public boolean edit(String undoText, String statusKey, UnaryOperator<Plan> change) {
        Plan before = context.document().plan();
        try {
            context.document().edit(undoText, change);
        } catch (RuntimeException error) {
            if (formResult) throw error; // FormSession оставляет окно открытым и показывает строку проблем.
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            context.showAlert(AlertCatalog.error("editFailed", error), null);
            return false;
        }
        boolean changed = !before.equals(context.document().plan());
        if (changed) {
            context.refresh();
            if (statusKey != null && !statusKey.isBlank()) context.status(StatusLevel.INFO, statusKey);
        }
        return changed;
    }

    /**
     * {@code edit.addIncome}/{@code edit.addExpense}: редактор правила с видом.
     *
     * @param kind вид по умолчанию
     */
    public void addRule(Kind kind) {
        openRule(null, Objects.requireNonNull(kind, "kind"));
    }

    /**
     * {@code edit.addOneTime}, {@code row.addOneTime}, {@code chart.addOneTime}: разовая операция с видом «Расход».
     *
     * @param date дата по умолчанию или {@code null}
     */
    public void addOneTime(LocalDate date) {
        Map<String, String> values = new java.util.HashMap<>();
        values.put(WindowType.CONTEXT_MODE, WindowType.MODE_CREATE);
        values.put("kind", Kind.EXPENSE.name());
        if (date != null) values.put(OneTimeForm.CONTEXT_DATE, FieldCodec.date(date));
        open(new OneTimeForm(), WindowType.ONE_TIME_EDITOR, values, result -> {
            if (result instanceof OneTimeTransaction tx)
                edit(UiText.get("undo.oneTimeAdd", tx.title()), "status.msg.oneTimeAdded", p -> p.withOneTimeAdded(tx));
        });
    }

    /**
     * {@code edit.edit}, {@code row.edit}, двойной щелчок: по виду строки.
     *
     * @param rowId id строки
     */
    public void editRow(String rowId) {
        ForecastRow row = row(rowId);
        if (row == null) return;
        switch (row.origin()) {
            case RULE -> openRule(row.ruleId(), row.kind());
            case ONE_TIME -> open(new OneTimeForm(), WindowType.ONE_TIME_EDITOR,
                    Map.of(WindowType.CONTEXT_MODE, WindowType.MODE_EDIT, WindowType.CONTEXT_TX_ID, row.txId().value()),
                    result -> {
                        if (result instanceof OneTimeTransaction tx)
                            edit(UiText.get("undo.oneTimeEdit", tx.title()), "status.msg.oneTimeChanged", p -> {
                                if (p.findOneTime(tx.id()).isEmpty())
                                    throw new IllegalArgumentException(UiText.get("err.notFound.content", tx.id()));
                                return p.withOneTimeReplaced(tx);
                            });
                    });
            case START -> planSettings();
            case WHAT_IF -> hint("status.hint.whatIfRow");
        }
    }

    /**
     * {@code edit.delete}, {@code row.delete}: подтверждение §6.11.
     *
     * @param rowId id строки
     */
    public void deleteRow(String rowId) {
        ForecastRow row = row(rowId);
        if (row == null) return;
        if (row.origin() == Origin.RULE) {
            RecurringRule rule = context.document().plan().findRule(row.ruleId()).orElse(null);
            if (rule == null) return;
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            context.showAlert(AlertCatalog.deleteRule(rule.id().value(), rule.title(), rule.amount(),
                    context.document().plan().currency(), rule.recurrence().toRussian(),
                    context.document().plan().adjustmentsOf(rule.id()).size()), button -> {
                if ("delete".equals(button) && edit(UiText.get("undo.ruleDelete", rule.title()), "", p -> p.withRuleRemoved(rule.id())))
                    context.status(StatusLevel.INFO, "status.msg.ruleDeleted", rule.title());
            });
        } else if (row.origin() == Origin.ONE_TIME) {
            OneTimeTransaction tx = context.document().plan().findOneTime(row.txId()).orElse(null);
            if (tx == null) return;
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            context.showAlert(AlertCatalog.deleteOneTime(tx.id().value(), tx.title(), tx.date(), tx.amount(),
                    context.document().plan().currency()), button -> {
                if ("delete".equals(button) && edit(UiText.get("undo.oneTimeDelete", tx.title()), "", p -> p.withOneTimeRemoved(tx.id())))
                    context.status(StatusLevel.INFO, "status.msg.oneTimeDeleted", tx.title());
            });
        } else hint("status.hint.noOperation");
    }

    /**
     * {@code edit.adjust}, {@code row.adjust}, «Скорректировать выбранное событие…» тулбара: форма §6.5.
     *
     * @param rowId id строки RULE
     */
    public void adjust(String rowId) {
        ForecastRow row = ruleRow(rowId);
        if (row == null) return;
        open(new AdjustmentForm(), WindowType.ADJUSTMENT_EDITOR, occurrenceContext(row),
                result -> applyAdjustment(result, row, false));
    }

    /**
     * {@code edit.skip}, {@code row.skip}: {@code undo.skip}, {@code status.msg.skipped} или {@code skippedHidden}.
     *
     * @param rowId id строки RULE
     */
    public void skip(String rowId) {
        ForecastRow row = ruleRow(rowId);
        if (row == null) return;
        if (row.flags().skipped()) { hint("status.hint.alreadySkipped"); return; }
        OccurrenceKey key = row.occurrenceKey().orElseThrow();
        String note = context.document().plan().findAdjustment(key).map(Adjustment::note).orElse("");
        edit(UiText.get("undo.skip", UiFormats.date(row.originalDate())),
                context.state().view().showSkipped() ? "status.msg.skipped" : "status.msg.skippedHidden",
                p -> p.withAdjustmentPut(new Adjustment(key, new Adjustment.Skip(), note)));
    }

    /**
     * {@code edit.reset}, {@code row.reset}: {@code undo.reset}, {@code status.msg.adjustReset}.
     *
     * @param rowId id строки RULE
     */
    public void reset(String rowId) {
        // Пропуск скрывает строку из прогноза, но корректировка и логический выбор остаются в плане.
        // Используем тот же источник принадлежности событию, что и доступность команды, а не видимую таблицу.
        RowRef reference = RowRef.resolve(context.state(), rowId);
        if (reference.isEmpty() || reference.kind() == null) { hint("status.hint.noRow"); return; }
        if (!reference.is(RowKind.RULE)) { hint("status.hint.noRuleEvent"); return; }
        OccurrenceKey key = OccurrenceKey.parseRowId(reference.rowId());
        if (context.document().plan().findAdjustment(key).isEmpty()) { hint("status.hint.noAdjustment"); return; }
        edit(UiText.get("undo.reset", UiFormats.date(key.originalDate())), "status.msg.adjustReset",
                p -> p.withAdjustmentRemoved(key));
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
        ForecastRow row = row(rowId);
        if (row == null) return;
        var table = LazyTableModel.build(context.state(), context.state().revision());
        boolean amountColumn = row.kind() == Kind.INCOME ? "income".equals(columnId) : "expense".equals(columnId);
        if (row.origin() != Origin.RULE || row.flags().skipped() || !amountColumn || table.indexOf(rowId) < 0) {
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            context.showAlert(AlertCatalog.quickEditUnavailable(table.indexOf(rowId) < 0 ? rowId : ""), null);
            return;
        }
        context.singleInstance(WindowType.QUICK_EDIT_POPUP.name()).ifPresent(FormSession::closeRequested);
        // JavaFX: Popup → Swing: PopupFactory → Web: div.
        context.openForm(FormRequest.fresh(new QuickEditForm(), WindowType.QUICK_EDIT_POPUP, false,
                occurrenceContext(row)), Placement.underCell(rowId, columnId), result ->
                formResult(() -> applyAdjustment(result, row, true)));
    }

    /**
     * {@code row.goToRule}: редактор правила строки.
     *
     * @param rowId id строки RULE
     */
    public void goToRule(String rowId) {
        ForecastRow row = ruleRow(rowId);
        if (row != null) openRule(row.ruleId(), row.kind());
    }

    /**
     * {@code row.disableRule}: без подтверждения, {@code undo.ruleDisable}, {@code status.msg.ruleDisabled}.
     *
     * @param rowId id строки RULE
     */
    public void disableRule(String rowId) {
        ForecastRow row = ruleRow(rowId);
        if (row == null) return;
        RecurringRule rule = context.document().plan().findRule(row.ruleId()).orElse(null);
        if (rule != null && edit(UiText.get("undo.ruleDisable", rule.title()), "",
                p -> p.withRuleReplaced(p.findRule(rule.id()).orElseThrow().withEnabled(false))))
            context.status(StatusLevel.INFO, "status.msg.ruleDisabled", rule.title());
    }

    /**
     * {@code row.copy}: тексты ячеек через табуляцию, {@code status.msg.rowCopied}.
     *
     * @param rowId id строки
     */
    public void copyRow(String rowId) {
        copy(rowId, false);
    }

    /**
     * {@code total.copy}: «Октябрь 2026⇥Доход⇥Расход⇥Баланс», {@code status.msg.totalCopied}.
     *
     * @param rowId id строки итога
     */
    public void copyTotal(String rowId) {
        copy(rowId, true);
    }

    /** {@code edit.undo}: {@code status.msg.undone}. */
    public void undo() {
        var text = context.document().undoDescription();
        if (text.isEmpty()) { hint("status.hint.nothingToUndo"); return; }
        context.document().undo();
        context.refresh();
        context.status(StatusLevel.INFO, "status.msg.undone", text.get());
    }

    /** {@code edit.redo}: {@code status.msg.redone}. */
    public void redo() {
        var text = context.document().redoDescription();
        if (text.isEmpty()) { hint("status.hint.nothingToRedo"); return; }
        context.document().redo();
        context.refresh();
        context.status(StatusLevel.INFO, "status.msg.redone", text.get());
    }

    /** {@code edit.planSettings}: форма §6.2. */
    public void planSettings() {
        open(new PlanSettingsForm(), WindowType.PLAN_SETTINGS, Map.of(), result -> {
            if (result instanceof Plan value) edit(UiText.get("undo.planSettings"), "status.msg.settings",
                    p -> new Plan(value.name(), value.note(), value.currency(), value.startDate(), value.startBalance(),
                            value.horizon(), value.cushion(), value.goal(), p.rules(), p.oneTimes(), p.adjustments(), p.rawBlocks()));
        });
    }

    /** {@code edit.actualize}: §6.25. */
    public void actualize() {
        LocalDate today = context.state().today();
        Plan plan = context.document().plan();
        if (!today.isAfter(plan.startDate())) {
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            context.showAlert(AlertCatalog.info("actualizeNothing", UiFormats.date(plan.startDate())), null);
            return;
        }
        try {
            PlanDocument preview = new PlanDocument(plan, null, () -> today);
            preview.actualize(today, null);
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            context.showAlert(AlertCatalog.actualize(today, preview.plan().startBalance(), plan.currency()), button -> {
                if ("actualize".equals(button)) edit(Texts.get("document.edit.actualize", UiFormats.date(today)),
                        "status.msg.actualized", p -> {
                            PlanDocument draft = new PlanDocument(p, null, () -> today);
                            draft.actualize(today, null);
                            return draft.plan();
                        });
            });
        } catch (RuntimeException error) {
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            context.showAlert(AlertCatalog.error("forecast", error), null);
        }
    }

    /** {@code edit.reconcile}: §6.7 (иначе {@code info.reconcileUnavailable}). */
    public void reconcile() {
        LocalDate today = context.state().today();
        Plan plan = context.document().plan();
        if (!context.state().document().forecastAvailable() || today.isBefore(plan.startDate()) || today.isAfter(plan.endDate())) {
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            context.showAlert(AlertCatalog.info("reconcileUnavailable", UiFormats.date(plan.startDate()), UiFormats.date(plan.endDate())), null);
            return;
        }
        open(TextInputForms.reconcile(), WindowType.TEXT_INPUT,
                Map.of(WindowType.CONTEXT_PURPOSE, TextInputForms.PURPOSE_RECONCILE), result -> {
                    if (!(result instanceof Money actual)) return;
                    Money[] difference = {Money.ZERO};
                    boolean changed = edit(Texts.get("document.reconcile.title"), "", p -> {
                        PlanDocument draft = new PlanDocument(p, null, () -> today);
                        difference[0] = actual.minus(draft.forecast().balanceAt(today));
                        draft.reconcile(today, actual);
                        return draft.plan();
                    });
                    if (changed) context.status(StatusLevel.INFO, "status.msg.reconciled",
                            difference[0].formatSigned() + " " + context.document().plan().currency());
                });
    }
    /** Открывает редактор правила, сохраняя режим и идентификатор в снимке. */
    private void openRule(RuleId id, Kind kind) {
        Map<String, String> values = id == null
                ? Map.of(WindowType.CONTEXT_MODE, WindowType.MODE_CREATE, "kind", kind.name())
                : Map.of(WindowType.CONTEXT_MODE, WindowType.MODE_EDIT, WindowType.CONTEXT_RULE_ID, id.value());
        open(new RuleEditorForm(), WindowType.RULE_EDITOR, values, result -> {
            if (result instanceof RecurringRule rule) edit(UiText.get(id == null ? "undo.ruleAdd" : "undo.ruleEdit", rule.title()),
                    id == null ? "status.msg.ruleAdded" : "status.msg.ruleChanged",
                    p -> {
                        if (id != null && p.findRule(id).isEmpty())
                            throw new IllegalArgumentException(UiText.get("err.notFound.content", id));
                        return id == null ? p.withRuleAdded(rule) : p.withRuleReplaced(rule);
                    });
        });
    }

    /** Открывает модальную форму; исключения применения остаются в строке проблем формы. */
    private void open(FormLogic logic, WindowType type, Map<String, String> values, Consumer<Object> result) {
        // JavaFX: Dialog → Swing: JDialog → Web: dialog.
        context.openForm(FormRequest.fresh(logic, type, true, values), null,
                value -> formResult(() -> result.accept(value)));
    }

    /** Ограничивает режим обработки исключений одним синхронным результатом формы. */
    void formResult(Runnable action) {
        boolean previous = formResult;
        formResult = true;
        try { action.run(); } finally { formResult = previous; }
    }

    /** Находит событие текущего прогноза; служебные строки не являются событиями. */
    private ForecastRow row(String rowId) {
        if (rowId == null || rowId.isBlank()) { hint("status.hint.noRow"); return null; }
        try {
            ForecastRow found = context.document().forecast().rows().stream()
                    .filter(r -> r.rowId().equals(rowId)).findFirst().orElse(null);
            if (found == null) hint("status.hint.noRow");
            return found;
        } catch (RuntimeException error) {
            hint("status.hint.noForecast");
            return null;
        }
    }

    /** Проверяет принадлежность строки регулярному правилу. */
    private ForecastRow ruleRow(String rowId) {
        ForecastRow row = row(rowId);
        if (row != null && row.origin() != Origin.RULE) { hint("status.hint.noRuleEvent"); return null; }
        return row;
    }

    /** Контекст корректировки хранит номинальную дату, даже после переноса события. */
    private Map<String, String> occurrenceContext(ForecastRow row) {
        return Map.of(WindowType.CONTEXT_RULE_ID, row.ruleId().value(),
                WindowType.CONTEXT_ORIGINAL_DATE, FieldCodec.date(row.originalDate()));
    }

    /** Применяет результат обычной либо быстрой корректировки одним шагом истории. */
    private void applyAdjustment(Object value, ForecastRow row, boolean quick) {
        if (!(value instanceof AdjustmentForm.Result result)) return;
        String undo = quick ? UiText.get("undo.quickAmount", UiFormats.date(result.key().originalDate()))
                : UiText.get(result.adjustment() == null ? "undo.adjustReset" : "undo.adjust",
                        row.title(), UiFormats.date(result.key().originalDate()));
        edit(undo, quick ? "status.msg.quickAmount" : result.adjustment() == null ? "status.msg.adjustReset" : "status.msg.adjustSaved",
                p -> {
                    if (p.findRule(result.key().ruleId()).isEmpty())
                        throw new IllegalArgumentException(UiText.get("err.notFound.content", result.key().ruleId()));
                    return result.adjustment() == null ? p.withAdjustmentRemoved(result.key()) : p.withAdjustmentPut(result.adjustment());
                });
    }

    /** Копирует тексты модели таблицы, не воспроизводя форматирование в потоке. */
    private void copy(String rowId, boolean total) {
        var table = LazyTableModel.build(context.state(), context.state().revision());
        int index = table.indexOf(rowId);
        if (index < 0) { hint("status.hint.noRow"); return; }
        var row = table.row(index);
        if (total && row.kind() != RowKind.MONTH_TOTAL) return;
        var cells = new java.util.ArrayList<>(row.cells());
        // Пока модель S1 обозначает пропуск дефисом, в буфер переносится смысловое обозначение.
        if (!total && context.document().forecast().rows().stream()
                .anyMatch(event -> event.rowId().equals(rowId) && event.flags().skipped())) {
            if (!cells.get(4).isEmpty()) cells.set(4, UiText.get("day.skipped"));
            if (!cells.get(5).isEmpty()) cells.set(5, UiText.get("day.skipped"));
        }
        context.port().copyToClipboard(total
                ? String.join("\t", UiFormats.monthTitle(java.time.YearMonth.parse(rowId.substring(LazyTableModel.TOTAL_ROW_ID_PREFIX.length()))),
                        cells.get(4), cells.get(5), cells.get(6))
                : String.join("\t", cells));
        context.status(StatusLevel.INFO, total ? "status.msg.totalCopied" : "status.msg.rowCopied");
    }

    /** Показывает причину недоступного действия. */
    private void hint(String key) { context.status(StatusLevel.INFO, key); }
}
