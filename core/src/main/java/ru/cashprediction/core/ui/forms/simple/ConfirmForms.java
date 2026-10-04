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
    /**
     * Описание подтверждения и отложенного изменения плана; создание записи само ничего не изменяет.
     * @param spec модель сообщения подтверждения, не {@code null}
     * @param confirmButtonId идентификатор кнопки подтверждения, не {@code null}
     * @param undoText ключ текста действия в истории отмены; {@code null} заменяется пустой строкой
     * @param edit чистая функция изменения плана либо {@code null} для действия вне плана
     * @param statusKey ключ сообщения строки состояния; {@code null} заменяется пустой строкой
     */
    public record Confirmation(AlertSpec spec, String confirmButtonId, String undoText, UnaryOperator<Plan> edit, String statusKey) {
        /**
         * Проверяет обязательные модель и кнопку, приводит отсутствующие текстовые ключи к пустым строкам.
         * Функция редактирования может отсутствовать и при создании не вызывается.
         * @throws NullPointerException если {@code spec} или {@code confirmButtonId} равен {@code null}
         */
        public Confirmation { Objects.requireNonNull(spec, "spec"); Objects.requireNonNull(confirmButtonId, "confirmButtonId"); undoText = Objects.requireNonNullElse(undoText, ""); statusKey = Objects.requireNonNullElse(statusKey, ""); }
    }
    private ConfirmForms() { }
    /**
     * Готовит подтверждение удаления регулярного правила вместе с его корректировками.
     * @param state состояние приложения с текущим планом
     * @param ruleId идентификатор удаляемого правила
     * @return подтверждение с функцией удаления либо пустое значение, если правило не найдено
     * @throws NullPointerException если состояние равно {@code null}
     */
    public static Optional<Confirmation> deleteRule(AppState state, String ruleId) {
        Objects.requireNonNull(state, "state");
        return state.document().plan().findRule(new RuleId(ruleId)).map(rule -> new Confirmation(AlertCatalog.deleteRule(ruleId, rule.title(), rule.amount(), state.document().plan().currency(), rule.recurrence().toRussian(), state.document().plan().adjustmentsOf(rule.id()).size()), "delete", "undo.ruleDelete", plan -> plan.withRuleRemoved(rule.id()), "status.msg.ruleDeleted"));
    }
    /**
     * Готовит подтверждение удаления разовой операции без изменения исходного плана.
     * @param state состояние приложения с текущим планом
     * @param txId идентификатор удаляемой операции
     * @return подтверждение с функцией удаления либо пустое значение, если операция не найдена
     * @throws NullPointerException если состояние равно {@code null}
     */
    public static Optional<Confirmation> deleteOneTime(AppState state, String txId) {
        Objects.requireNonNull(state, "state");
        return state.document().plan().findOneTime(new TxId(txId)).map(tx -> new Confirmation(AlertCatalog.deleteOneTime(txId, tx.title(), tx.date(), tx.amount(), state.document().plan().currency()), "delete", "undo.oneTimeDelete", plan -> plan.withOneTimeRemoved(tx.id()), "status.msg.oneTimeDeleted"));
    }
    /**
     * Готовит перенос начала плана на сегодня с прогнозным балансом на конец вчерашнего дня.
     * Отложенное изменение также удаляет разовые операции до сегодняшней даты.
     * @param state состояние приложения с датой, планом и прогнозом
     * @return подтверждение либо пустое значение, если прогноз недоступен или сегодня не позже начала плана
     * @throws NullPointerException если состояние равно {@code null}
     */
    public static Optional<Confirmation> actualize(AppState state) {
        Objects.requireNonNull(state, "state");
        if (!state.document().forecastAvailable() || !state.today().isAfter(state.document().plan().startDate())) return Optional.empty();
        Money balance = state.document().forecast().balanceAt(state.today().minusDays(1));
        return Optional.of(new Confirmation(AlertCatalog.actualize(state.today(), balance, state.document().plan().currency()), "actualize", "document.edit.actualize", plan -> plan.withStart(state.today(), balance).withOneTimesRemovedIf(tx -> tx.date().isBefore(state.today())), "status.msg.actualized"));
    }
    /**
     * Готовит применение коэффициентов доходов и расходов сценария к суммам операций плана.
     * При неположительной новой сумме регулярное правило выключается, а разовая операция удаляется.
     * Дополнительная ежемесячная экономия входит в сообщение, но этой функцией в план не записывается.
     * @param state состояние приложения с текущим сценарием
     * @return подтверждение с функцией масштабирования либо пустое значение для нейтрального сценария
     * @throws NullPointerException если состояние равно {@code null}
     */
    public static Optional<Confirmation> applyWhatIf(AppState state) {
        Objects.requireNonNull(state, "state"); WhatIf value = state.view().whatIf(); if (value.isNone()) return Optional.empty();
        List<String> parts = List.of(value.incomeFactor().toPlainString(), value.expenseFactor().toPlainString(), value.extraMonthlySaving().format(state.document().plan().currency()));
        return Optional.of(new Confirmation(AlertCatalog.applyWhatIf(parts), "apply", "document.edit.applyWhatIf", plan -> scale(plan, value), "status.msg.whatIfApplied"));
    }
    /**
     * Готовит подтверждение очистки снимков сеанса; саму очистку выполняет вызывающий код.
     * @return подтверждение без функции изменения плана и без ключа действия отмены
     */
    public static Confirmation clearSnapshots() { return new Confirmation(AlertCatalog.clearSnapshots(), "clear", "", null, "status.msg.snapshotsCleared"); }
    private static Plan scale(Plan plan, WhatIf whatIf) {
        List<RecurringRule> rules = plan.rules().stream().map(rule -> { Money amount = rule.amount().times(rule.kind() == Kind.INCOME ? whatIf.incomeFactor() : whatIf.expenseFactor()); return amount.isPositive() ? rule.withAmount(amount) : rule.withEnabled(false); }).toList();
        List<OneTimeTransaction> oneTimes = plan.oneTimes().stream().map(tx -> { Money amount = tx.amount().times(tx.kind() == Kind.INCOME ? whatIf.incomeFactor() : whatIf.expenseFactor()); return amount.isPositive() ? new OneTimeTransaction(tx.id(), tx.date(), tx.title(), tx.kind(), amount, tx.category(), tx.note()) : null; }).filter(Objects::nonNull).toList();
        return plan.withRules(rules).withOneTimes(oneTimes);
    }
}
