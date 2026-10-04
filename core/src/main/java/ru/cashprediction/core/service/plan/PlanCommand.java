package ru.cashprediction.core.service.plan;

import java.time.LocalDate;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Goal;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;

/**
 * Неизменяемая предметная команда без функций изменения, документа и сеансов интерфейса.
 * Проверки бизнес-значений выполняет служба, а не форма и не терпимые конструкторы модели.
 * Повторы уже типизированы моделью; текст денег и расписаний здесь не разбирается.
 */
public sealed interface PlanCommand {

    /** Добавляет правило с ещё не занятым идентификатором. */
    record AddRule(RecurringRule rule) implements PlanCommand { }

    /** Заменяет существующее правило, сохраняя позицию и связанные корректировки. */
    record ReplaceRule(RecurringRule rule) implements PlanCommand { }

    /** Удаляет правило вместе с его корректировками. */
    record RemoveRule(RuleId id) implements PlanCommand { }

    /** Добавляет разовую операцию с ещё не занятым идентификатором. */
    record AddOneTime(OneTimeTransaction transaction) implements PlanCommand { }

    /** Заменяет существующую разовую операцию на прежней позиции. */
    record ReplaceOneTime(OneTimeTransaction transaction) implements PlanCommand { }

    /** Удаляет существующую разовую операцию. */
    record RemoveOneTime(TxId id) implements PlanCommand { }

    /** Добавляет либо заменяет корректировку номинального события. */
    record PutAdjustment(Adjustment adjustment) implements PlanCommand { }

    /** Возвращает событие к правилу; отсутствие корректировки означает отсутствие изменения. */
    record RemoveAdjustment(OccurrenceKey key) implements PlanCommand { }

    /** Параметры плана без списков операций и нераспознанных блоков файла. */
    record Settings(String name, String note, String currency, LocalDate startDate, Money startBalance,
                    Horizon horizon, Money cushion, Goal goal) {
        /** @return только параметры переданного неизменяемого плана */
        public static Settings from(Plan plan) {
            return new Settings(plan.name(), plan.note(), plan.currency(), plan.startDate(), plan.startBalance(),
                    plan.horizon(), plan.cushion(), plan.goal());
        }
    }

    /** Меняет параметры, сохраняя операции, корректировки и нераспознанные блоки. */
    record UpdateSettings(Settings settings) implements PlanCommand { }

    /** Меняет цель; null удаляет её. */
    record SetGoal(Goal goal) implements PlanCommand { }

    /** Меняет только обозначение валюты, без конвертации сумм. */
    record SetCurrency(String currency) implements PlanCommand { }

    /** Меняет горизонт плана. */
    record SetHorizon(Horizon horizon) implements PlanCommand { }

    /** Меняет имя плана, не выполняя файловых операций. */
    record RenamePlan(String name) implements PlanCommand { }

    /** Переносит начало, сохраняет фазу правил и удаляет прошлые разовые операции. */
    record Actualize(LocalDate date, Money balanceOverride) implements PlanCommand { }

    /** Сверяет фактический баланс на конец дня с обычным прогнозом. */
    record Reconcile(LocalDate date, Money actualBalance) implements PlanCommand { }

    /** Применяет явный сценарий с округлением существующего документа. */
    record ApplyWhatIf(WhatIf whatIf, LocalDate today) implements PlanCommand { }

    /** Удаляет только сирот по точным правилам прогноза с указанными параметрами. */
    record Cleanup(LocalDate today, WhatIf whatIf, boolean showSkipped) implements PlanCommand { }

    /** Отменяет один шаг принадлежащей службе локальной истории. */
    record Undo() implements PlanCommand { }

    /** Повторяет один шаг принадлежащей службе локальной истории. */
    record Redo() implements PlanCommand { }
}
