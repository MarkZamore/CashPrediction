package ru.cashprediction.core.app.flow;

import java.util.Objects;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.math.BigDecimal;
import java.time.Duration;
import ru.cashprediction.core.app.Placement;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.session.Scheduler;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import ru.cashprediction.core.ui.forms.simple.ChoiceForms;
import ru.cashprediction.core.ui.forms.simple.TextInputForms;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.view.status.StatusLevel;
import ru.cashprediction.core.ui.view.summary.SummaryBuilder;

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
    /** Отменяемая задача задержки спиннера; само значение хранит документ. */
    private Scheduler.Task pendingExtra;
    /** Защищает от задачи, уже переданной исполнителю перед отменой. */
    private long extraRevision;

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
        var existing = context.singleInstance(WindowType.GOAL_CALCULATOR.name());
        if (existing.isPresent()) { existing.get().handle().toFront(); return; }
        // JavaFX: Dialog → Swing: JDialog → Web: dialog.
        context.openForm(FormRequest.fresh(new GoalCalculatorForm(), WindowType.GOAL_CALCULATOR, false, Map.of()),
                null, action -> context.edits().formResult(() -> {
                    if (action instanceof GoalCalculatorForm.SaveGoal saved)
                        context.edits().edit(UiText.get("undo.goal", saved.goal().title()), "status.msg.goalSaved",
                                p -> p.withGoal(saved.goal()));
                    else if (action instanceof GoalCalculatorForm.AddWhatIfExtra extra) {
                        cancelExtra();
                        context.updateView(v -> v.withWhatIf(v.whatIf().withExtraMonthlySaving(
                                v.whatIf().extraMonthlySaving().plus(extra.amount()))));
                    }
                }));
    }

    /** {@code whatIf.income}: доходы × 0,90 вкл/выкл. */
    public void toggleWhatIfIncome() {
        context.updateView(v -> v.withWhatIf(v.whatIf().withIncomeFactor(
                v.whatIf().incomeFactor().compareTo(BigDecimal.ONE) == 0 ? new BigDecimal("0.90") : BigDecimal.ONE)));
    }

    /** {@code whatIf.expense}: расходы × 1,10 вкл/выкл. */
    public void toggleWhatIfExpense() {
        context.updateView(v -> v.withWhatIf(v.whatIf().withExpenseFactor(
                v.whatIf().expenseFactor().compareTo(BigDecimal.ONE) == 0 ? new BigDecimal("1.10") : BigDecimal.ONE)));
    }

    /**
     * {@code whatIf.extra}: доп. экономия в месяц (целые рубли 0..10 000 000).
     *
     * @param amountMajor сумма в целых единицах валюты
     * @throws IllegalArgumentException если сумма вне диапазона 0..10 000 000
     *
     * <p>Ввод применяется через 600 мс после последнего вызова. Сброс сценария отменяет ожидающую задачу.
     * Вид меняется через контекст, без истории правок плана и без записи сценария в настройки.</p>
     */
    public void setWhatIfExtra(long amountMajor) {
        if (amountMajor < 0 || amountMajor > 10_000_000)
            throw new IllegalArgumentException(UiText.get("s2.edit.extra.range"));
        cancelExtra();
        long version = extraRevision;
        pendingExtra = context.port().scheduler().schedule(() -> context.port().executor().execute(() -> {
            if (version != extraRevision) return;
            pendingExtra = null;
            context.updateView(v -> v.withWhatIf(v.whatIf().withExtraMonthlySaving(Money.ofMajor(amountMajor))));
        }), Duration.ofMillis(600));
    }

    /** {@code whatIf.apply}: подтверждение §6.26, {@code status.msg.whatIfApplied}. */
    public void applyWhatIf() {
        cancelExtra();
        WhatIf value = context.state().view().whatIf();
        if (value.isNone()) { context.status(StatusLevel.INFO, "status.hint.whatIfOff"); return; }
        List<String> parts = new ArrayList<>();
        if (value.incomeFactor().compareTo(BigDecimal.ONE) != 0)
            parts.add(UiText.get("s2.edit.whatIf.income", value.incomeFactor().toPlainString().replace('.', ',')));
        if (value.expenseFactor().compareTo(BigDecimal.ONE) != 0)
            parts.add(UiText.get("s2.edit.whatIf.expense", value.expenseFactor().toPlainString().replace('.', ',')));
        if (value.extraMonthlySaving().isPositive())
            parts.add(UiText.get("s2.edit.whatIf.extra", value.extraMonthlySaving().format(context.document().plan().currency())));
        AlertSpec base = AlertCatalog.applyWhatIf(parts);
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        context.showAlert(new AlertSpec(base.kind(), base.purpose(), base.targetId(), UiText.get("s2.edit.whatIf.title"),
                base.glyph(), UiText.get("s2.edit.whatIf.header"), UiText.get("s2.edit.whatIf.content", String.join("; ", parts)),
                base.details(), base.detailsExpanded(), base.minWidth(), base.buttons(), base.defaultButtonId(), base.restorable()), button -> {
            if (!"apply".equals(button)) return;
            boolean[] computed = {false};
            context.edits().edit(Texts.get("document.edit.applyWhatIf"), "", p -> {
                PlanDocument draft = new PlanDocument(p, null, () -> context.state().today());
                draft.setViewState(context.state().view().withWhatIf(value));
                draft.applyWhatIfToPlan();
                computed[0] = true;
                return draft.plan();
            });
            if (computed[0]) {
                context.updateView(v -> v.withWhatIf(WhatIf.NONE));
                context.status(StatusLevel.INFO, "status.msg.whatIfApplied");
            }
        });
    }

    /** {@code whatIf.reset}: выключить «что-если», {@code status.msg.whatIfReset}. */
    public void resetWhatIf() {
        cancelExtra();
        context.updateView(v -> v.withWhatIf(WhatIf.NONE));
        context.status(StatusLevel.INFO, "status.msg.whatIfReset");
    }

    /** {@code tools.validate}: §6.17 «Проверка плана». */
    public void validate() {
        List<String> lines = new ArrayList<>();
        PlanValidator.validate(context.document().plan()).forEach(d -> lines.add(UiText.get("s2.edit.validate.plan", d.format())));
        context.document().loadDiagnostics().forEach(d -> lines.add(UiText.get("s2.edit.validate.file", d.format())));
        try {
            context.document().forecast().warnings().forEach(w -> lines.add(UiText.get("s2.edit.validate.forecast", w.format())));
        } catch (RuntimeException error) {
            lines.add(UiText.get("s2.edit.validate.failed", message(error)));
        }
        // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
        context.showAlert(AlertCatalog.validation(lines), null);
    }

    /** {@code tools.cleanup}: удалить неиспользуемые корректировки, §6.17. */
    public void cleanup() {
        try {
            PlanDocument draft = new PlanDocument(context.document().plan(), null, () -> context.state().today());
            draft.setViewState(context.state().view());
            int removed = draft.removeOrphanAdjustments();
            if (removed > 0 && !context.edits().edit(Texts.get("document.edit.removeUnusedAdjustments", removed),
                    "", p -> draft.plan())) return;
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            if (removed == 0) context.showAlert(AlertCatalog.cleanup(0), null);
            else {
                // Шаблон alert.cleanup.done требует аргумент и в заголовке окна, и в заголовке содержимого.
                AlertSpec base = AlertCatalog.cleanup(0);
                String count = UiFormats.count(removed, UiText.get("alert.adjustment.one"),
                        UiText.get("alert.adjustment.few"), UiText.get("alert.adjustment.many"));
                String title = UiText.get("alert.cleanup.done", count);
                context.showAlert(new AlertSpec(base.kind(), base.purpose(), base.targetId(), title, base.glyph(),
                        title, UiText.get("alert.undo"), "", false, base.minWidth(), base.buttons(),
                        base.defaultButtonId(), base.restorable()), null);
            }
        } catch (RuntimeException error) {
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
            context.showAlert(AlertCatalog.error("cleanup", error), null);
        }
    }

    /** {@code tools.currency}: CHOICE currency §6.8, затем при «другая…» TEXT_INPUT customCurrency. */
    public void currency() {
        // JavaFX: ChoiceDialog → Swing: JOptionPane → Web: dialog.
        context.openForm(FormRequest.fresh(ChoiceForms.currency(), WindowType.CHOICE, true,
                Map.of(WindowType.CONTEXT_PURPOSE, ChoiceForms.PURPOSE_CURRENCY)), null, result -> {
            if (!(result instanceof String value)) return;
            if (ChoiceForms.CUSTOM.equals(value)) {
                // JavaFX: TextInputDialog → Swing: JOptionPane → Web: dialog.
                context.openForm(FormRequest.fresh(TextInputForms.customCurrency(), WindowType.TEXT_INPUT, true,
                        Map.of(WindowType.CONTEXT_PURPOSE, TextInputForms.PURPOSE_CUSTOM_CURRENCY)),
                        Placement.centered(WindowState.MAIN_OWNER), custom -> {
                    if (custom instanceof String text) setCurrency(text);
                });
            } else setCurrency(value);
        });
    }

    /**
     * {@code card.copyValue}: «{Заголовок}: {значение}», {@code status.msg.valueCopied}.
     *
     * @param cardId id карточки
     */
    public void copyCardValue(String cardId) {
        SummaryBuilder.build(context.state()).cards().stream().filter(card -> card.id().equals(cardId)).findFirst()
                .ifPresent(card -> {
                    String value = card.date() == null && List.of("m1", "m3", "m6", "m12").contains(card.id())
                            ? UiText.get("s2.edit.card.outside") : card.value();
                    context.port().copyToClipboard(card.title() + ": " + value);
                    context.status(StatusLevel.INFO, "status.msg.valueCopied");
                });
    }
    /** Отменяет ещё не применённый ввод спиннера при сбросе и переходе к другим действиям. */
    private void cancelExtra() {
        extraRevision++;
        if (pendingExtra != null) pendingExtra.cancel();
        pendingExtra = null;
    }

    /** Изменяет только обозначение валюты, не пересчитывая суммы. */
    private void setCurrency(String value) {
        context.edits().formResult(() ->
                context.edits().edit(UiText.get("undo.currency", value), "", p -> p.withCurrency(value)));
    }

    /** Короткое объяснение неудачного расчёта. */
    private static String message(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
    }
}
