package ru.cashprediction.core.ui.forms.simple;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.alert.AlertSpec;

/** Подтверждения изменений плана и их чистые функции редактирования. */
public final class ConfirmForms {
    public record Confirmation(AlertSpec spec, String confirmButtonId, String undoText, UnaryOperator<Plan> edit, String statusKey) {
        public Confirmation { Objects.requireNonNull(spec, "spec"); Objects.requireNonNull(confirmButtonId, "confirmButtonId"); undoText = Objects.requireNonNullElse(undoText, ""); statusKey = Objects.requireNonNullElse(statusKey, ""); }
    }
    private ConfirmForms() { }
    public static Optional<Confirmation> deleteRule(AppState state, String ruleId) {
        Objects.requireNonNull(state, "state");
        return state.document().plan().findRule(new RuleId(ruleId)).map(rule -> new Confirmation(AlertCatalog.deleteRule(ruleId, rule.title(), rule.amount(), state.document().plan().currency(), rule.recurrence().toRussian(), state.document().plan().adjustmentsOf(rule.id()).size()), "delete", "undo.ruleDelete", plan -> plan.withRuleRemoved(rule.id()), "status.msg.ruleDeleted"));
    }
    public static Optional<Confirmation> deleteOneTime(AppState state, String txId) {
        Objects.requireNonNull(state, "state");
        return state.document().plan().findOneTime(new TxId(txId)).map(tx -> new Confirmation(AlertCatalog.deleteOneTime(txId, tx.title(), tx.date(), tx.amount(), state.document().plan().currency()), "delete", "undo.oneTimeDelete", plan -> plan.withOneTimeRemoved(tx.id()), "status.msg.oneTimeDeleted"));
    }
    public static Optional<Confirmation> actualize(AppState state) {
        Objects.requireNonNull(state, "state");
        if (!state.document().forecastAvailable() || !state.today().isAfter(state.document().plan().startDate())) return Optional.empty();
        Money balance = state.document().forecast().balanceAt(state.today().minusDays(1));
        return Optional.of(new Confirmation(AlertCatalog.actualize(state.today(), balance, state.document().plan().currency()), "actualize", "document.edit.actualize", plan -> plan.withStart(state.today(), balance).withOneTimesRemovedIf(tx -> tx.date().isBefore(state.today())), "status.msg.actualized"));
    }
    public static Optional<Confirmation> applyWhatIf(AppState state) {
        Objects.requireNonNull(state, "state"); WhatIf value = state.view().whatIf(); if (value.isNone()) return Optional.empty();
        List<String> parts = List.of(value.incomeFactor().toPlainString(), value.expenseFactor().toPlainString(), value.extraMonthlySaving().format(state.document().plan().currency()));
        return Optional.of(new Confirmation(AlertCatalog.applyWhatIf(parts), "apply", "document.edit.applyWhatIf", plan -> scale(plan, value), "status.msg.whatIfApplied"));
    }
    public static Confirmation clearSnapshots() { return new Confirmation(AlertCatalog.clearSnapshots(), "clear", "", null, "status.msg.snapshotsCleared"); }
    private static Plan scale(Plan plan, WhatIf whatIf) {
        List<RecurringRule> rules = plan.rules().stream().map(rule -> { Money amount = rule.amount().times(rule.kind() == Kind.INCOME ? whatIf.incomeFactor() : whatIf.expenseFactor()); return amount.isPositive() ? rule.withAmount(amount) : rule.withEnabled(false); }).toList();
        List<OneTimeTransaction> oneTimes = plan.oneTimes().stream().map(tx -> { Money amount = tx.amount().times(tx.kind() == Kind.INCOME ? whatIf.incomeFactor() : whatIf.expenseFactor()); return amount.isPositive() ? new OneTimeTransaction(tx.id(), tx.date(), tx.title(), tx.kind(), amount, tx.category(), tx.note()) : null; }).filter(Objects::nonNull).toList();
        return plan.withRules(rules).withOneTimes(oneTimes);
    }
}
