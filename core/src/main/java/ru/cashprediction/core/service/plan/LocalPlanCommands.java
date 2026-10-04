package ru.cashprediction.core.service.plan;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import ru.cashprediction.core.diagnostics.PlanValidator;
import ru.cashprediction.core.diagnostics.Severity;
import ru.cashprediction.core.document.EventKind;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.recurrence.OccurrenceGenerator;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.util.DateFormats;

/**
 * Локальный владелец команд и истории: проверяет проект, затем записывает его в документ одним шагом.
 * Документ и его стеки являются внутренней реализацией; расчёт специальных операций выполняется на черновике
 * с тем же внедрённым исполнителем прогноза. Лямбда записи никогда не выходит за эту реализацию.
 *
 * <p>Загрузка и восстановление продолжают использовать терпимый PlanDocument.replace. Его событие даже при
 * равном плане повышает ревизию и очищает окно повторов. Сохранение, дата и вид ревизию не меняют.
 * Интегратор создаёт ровно один экземпляр на документ до остальных слушателей; обычные изменения и история
 * проходят только через execute. Класс, как документ, рассчитан на один поток владельца.</p>
 *
 * <p>Дедупликация хранит последние {@value #RETRY_WINDOW} завершённых запросов, включая отказы проверки данных
 * и ревизии. Отказы повторного входа и коллизии идентификатора не занимают окно. Идентичный повтор возвращает
 * исходный снимок результата, а не текущий снимок. После вытеснения действуют обычные проверки expectedRevision.
 * Это локальное окно повторов в памяти процесса, не гарантия доставки или сохранения после перезапуска.</p>
 */
public final class LocalPlanCommands implements PlanCommands {
    /** Число запросов, для которых гарантируется возврат исходного результата. */
    public static final int RETRY_WINDOW = 256;

    private final PlanDocument document;
    private final Map<UUID, Remembered> requests = new LinkedHashMap<>();
    private long revision;
    private boolean executing;

    /** Сохранённый неизменяемый запрос и его первоначальный результат. */
    private record Remembered(PlanCommandRequest request, PlanCommandResult result) { }

    /** Проверенный проект записи и данные для статуса. */
    private record Prepared(Plan plan, String description, PlanCommandEffect effect) { }

    /** Внутренний отказ проверки, не раскрываемый контрактом службы. */
    private static final class Rejected extends RuntimeException {
        private final List<PlanCommandProblem> problems;

        /** Запоминает структурированные проблемы без пользовательского исключения в результате. */
        private Rejected(List<PlanCommandProblem> problems) {
            this.problems = List.copyOf(problems);
        }
    }

    /**
     * Принимает внутренний документ без проверки загруженных данных и без изменения прежней истории.
     *
     * @param document локальный документ, доступный только сборке приложения и инфраструктуре загрузки
     */
    public LocalPlanCommands(PlanDocument document) {
        this.document = Objects.requireNonNull(document, "document");
        document.addListener(event -> {
            if (!event.has(EventKind.PLAN)) return;
            revision++;
            if (event.has(EventKind.FILE)) requests.clear();
        });
    }

    /** {@inheritDoc} */
    @Override
    public PlanCommandSnapshot snapshot() {
        return new PlanCommandSnapshot(revision, document.plan(), document.undoDescription(), document.redoDescription());
    }

    /** {@inheritDoc} */
    @Override
    public PlanCommandResult execute(PlanCommandRequest request) {
        Objects.requireNonNull(request, "request");
        if (executing) return refused(request, generic(PlanCommandError.COMMAND_IN_PROGRESS, Map.of()));
        Remembered remembered = requests.get(request.requestId());
        if (remembered != null) {
            return remembered.request().equals(request) ? remembered.result()
                    : refused(request, generic(PlanCommandError.REQUEST_ID_REUSED, Map.of("requestId", request.requestId().toString())));
        }
        PlanCommandResult result;
        executing = true;
        try {
            checkRevision(request);
            if (request.command() instanceof PlanCommand.Undo || request.command() instanceof PlanCommand.Redo) {
                result = history(request);
            } else {
                Prepared prepared = prepare(request);
                validate(prepared.plan());
                if (prepared.plan().equals(document.plan())) {
                    result = result(request, PlanCommandResult.Status.UNCHANGED, snapshot(), prepared.effect(), List.of());
                } else {
                    List<PlanCommandProblem> notifications = new ArrayList<>();
                    try {
                        document.edit(prepared.description(), ignored -> prepared.plan());
                    } catch (RuntimeException failure) {
                        // Валидация уже завершена; исключения подписчиков возникают после фиксации плана и истории.
                        if (!document.plan().equals(prepared.plan())) throw failure;
                        notifications.add(calculationProblem(PlanCommandError.NOTIFICATION_FAILED, failure));
                    }
                    result = result(request, PlanCommandResult.Status.APPLIED, snapshot(), prepared.effect(), notifications);
                }
            }
        } catch (Rejected failure) {
            result = result(request, PlanCommandResult.Status.REJECTED, snapshot(), PlanCommandEffect.NONE, failure.problems);
        } catch (RuntimeException failure) {
            result = refused(request, calculationProblem(PlanCommandError.CALCULATION_FAILED, failure));
        } finally {
            executing = false;
        }
        requests.put(request.requestId(), new Remembered(request, result));
        while (requests.size() > RETRY_WINDOW) requests.remove(requests.keySet().iterator().next());
        return result;
    }

    /** {@inheritDoc} */
    @Override
    public PlanCommandResult preview(PlanCommandRequest request) {
        Objects.requireNonNull(request, "request");
        if (executing) return refused(request, generic(PlanCommandError.COMMAND_IN_PROGRESS, Map.of()));
        executing = true;
        try {
            checkRevision(request);
            if (request.command() instanceof PlanCommand.Undo || request.command() instanceof PlanCommand.Redo)
                reject(generic(PlanCommandError.INVALID_COMMAND, Map.of("command", request.command().getClass().getSimpleName())));
            Prepared prepared = prepare(request);
            validate(prepared.plan());
            PlanCommandSnapshot projected = new PlanCommandSnapshot(revision, prepared.plan(),
                    document.undoDescription(), document.redoDescription());
            return result(request, PlanCommandResult.Status.PREVIEW, projected, prepared.effect(), List.of());
        } catch (Rejected failure) {
            return result(request, PlanCommandResult.Status.REJECTED, snapshot(), PlanCommandEffect.NONE, failure.problems);
        } catch (RuntimeException failure) {
            return refused(request, calculationProblem(PlanCommandError.CALCULATION_FAILED, failure));
        } finally {
            executing = false;
        }
    }

    /** Проверяет предметную ревизию до любых вычислений. */
    private void checkRevision(PlanCommandRequest request) {
        if (request.expectedRevision() != revision) reject(generic(PlanCommandError.STALE_REVISION,
                Map.of("expectedRevision", Long.toString(request.expectedRevision()), "actualRevision", Long.toString(revision))));
    }

    /** Строит проект исключительно по типизированным данным команды. */
    private Prepared prepare(PlanCommandRequest request) {
        Plan plan = document.plan();
        Plan next;
        String description = request.description();
        PlanCommandEffect effect = PlanCommandEffect.NONE;
        switch (request.command()) {
            case PlanCommand.AddRule(var rule) -> {
                required(rule);
                if (plan.findRule(rule.id()).isPresent() || !plan.adjustmentsOf(rule.id()).isEmpty())
                    reject(duplicate(rule.id(), null));
                next = plan.withRuleAdded(rule);
            }
            case PlanCommand.ReplaceRule(var rule) -> {
                required(rule);
                requireRule(plan, rule.id());
                next = plan.withRuleReplaced(rule);
            }
            case PlanCommand.RemoveRule(var id) -> {
                required(id);
                requireRule(plan, id);
                next = plan.withRuleRemoved(id);
            }
            case PlanCommand.AddOneTime(var transaction) -> {
                required(transaction);
                if (plan.findOneTime(transaction.id()).isPresent()) reject(duplicate(null, transaction.id()));
                next = plan.withOneTimeAdded(transaction);
            }
            case PlanCommand.ReplaceOneTime(var transaction) -> {
                required(transaction);
                requireTransaction(plan, transaction.id());
                next = plan.withOneTimeReplaced(transaction);
            }
            case PlanCommand.RemoveOneTime(var id) -> {
                required(id);
                requireTransaction(plan, id);
                next = plan.withOneTimeRemoved(id);
            }
            case PlanCommand.PutAdjustment(var adjustment) -> {
                required(adjustment);
                RecurringRule rule = requireRule(plan, adjustment.key().ruleId());
                if (!OccurrenceGenerator.isNominalDate(rule, plan.startDate(), adjustment.key().originalDate()))
                    reject(new PlanCommandProblem(PlanCommandError.INVALID_ADJUSTMENT, rule.id(), null, adjustment.key(),
                            Map.of(), Texts.get("adjustment.error.action")));
                // Равная корректировка не переставляется в конец списка и не порождает фиктивный шаг истории.
                next = plan.findAdjustment(adjustment.key()).filter(adjustment::equals).isPresent()
                        && plan.adjustments().stream().filter(a -> a.key().equals(adjustment.key())).count() == 1
                        ? plan : plan.withAdjustmentPut(adjustment);
            }
            case PlanCommand.RemoveAdjustment(var key) -> {
                required(key);
                // Удаление допускает исправление сироты из терпимо прочитанного файла.
                next = plan.withAdjustmentRemoved(key);
            }
            case PlanCommand.UpdateSettings(var value) -> {
                required(value);
                required(value.name()); required(value.startDate()); required(value.horizon());
                checkBalance(value.startBalance());
                if (value.cushion() != null) {
                    checkBalance(value.cushion());
                    if (value.cushion().isNegative()) reject(new PlanCommandProblem(PlanCommandError.INVALID_AMOUNT,
                            null, null, null, Map.of("field", "cushion"), Texts.get("diagnostic.plan.cushionNegative")));
                }
                checkGoal(value.goal());
                next = new Plan(value.name(), value.note(), value.currency(), value.startDate(), value.startBalance(),
                        value.horizon(), value.cushion(), value.goal(), plan.rules(), plan.oneTimes(), plan.adjustments(), plan.rawBlocks());
            }
            case PlanCommand.SetGoal(var goal) -> { checkGoal(goal); next = plan.withGoal(goal); }
            case PlanCommand.SetCurrency(var currency) -> { required(currency); next = plan.withCurrency(currency); }
            case PlanCommand.SetHorizon(var horizon) -> { required(horizon); next = plan.withHorizon(horizon); }
            case PlanCommand.RenamePlan(var name) -> { required(name); next = plan.withName(name); }
            case PlanCommand.Actualize(var date, var balance) -> {
                required(date);
                checkBalance(balance);
                PlanDocument draft = draft(plan, date);
                draft.actualize(date, balance);
                next = draft.plan();
                description = draft.undoDescription().orElse(description);
            }
            case PlanCommand.Reconcile(var date, var actual) -> {
                required(date); required(actual);
                checkBalance(actual);
                PlanDocument draft = draft(plan, date);
                draft.reconcile(date, actual);
                next = draft.plan();
                Money difference = Money.ZERO;
                if (!next.equals(plan)) {
                    var transaction = next.oneTimes().getLast();
                    difference = transaction.kind() == Kind.INCOME ? transaction.amount() : transaction.amount().negate();
                }
                effect = new PlanCommandEffect(difference, 0);
                description = draft.undoDescription().orElse(description);
            }
            case PlanCommand.ApplyWhatIf(var whatIf, var today) -> {
                required(whatIf); required(today);
                checkBalance(whatIf.extraMonthlySaving());
                PlanDocument draft = draft(plan, today);
                draft.setViewState(draft.viewState().withWhatIf(whatIf));
                draft.applyWhatIfToPlan();
                next = draft.plan();
                description = draft.undoDescription().orElse(description);
                // Сброс сценария в настройках вида выполняет потребитель принятого результата.
            }
            case PlanCommand.Cleanup(var today, var whatIf, var showSkipped) -> {
                required(today); required(whatIf);
                PlanDocument draft = draft(plan, today);
                draft.setViewState(draft.viewState().withWhatIf(whatIf).withShowSkipped(showSkipped));
                int removed = draft.removeOrphanAdjustments();
                next = draft.plan();
                effect = new PlanCommandEffect(Money.ZERO, removed);
                description = draft.undoDescription().orElse(description);
            }
            case PlanCommand.Undo ignored -> throw new IllegalStateException("History command requires execute");
            case PlanCommand.Redo ignored -> throw new IllegalStateException("History command requires execute");
        }
        return new Prepared(next, description, effect);
    }

    /** Использует прежнюю семантику отмены и возврата к сохранённому состоянию, включая повреждённый исходник. */
    private PlanCommandResult history(PlanCommandRequest request) {
        boolean undo = request.command() instanceof PlanCommand.Undo;
        if (undo ? !document.canUndo() : !document.canRedo())
            return result(request, PlanCommandResult.Status.UNCHANGED, snapshot(), PlanCommandEffect.NONE, List.of());
        List<PlanCommandProblem> notifications = new ArrayList<>();
        long before = revision;
        try {
            if (undo) document.undo(); else document.redo();
        } catch (RuntimeException failure) {
            if (before == revision) throw failure;
            notifications.add(calculationProblem(PlanCommandError.NOTIFICATION_FAILED, failure));
        }
        return result(request, PlanCommandResult.Status.APPLIED, snapshot(), PlanCommandEffect.NONE, notifications);
    }

    /** Создаёт внутренний черновик с тем же исполнителем расчёта, без живых подписчиков. */
    private PlanDocument draft(Plan plan, LocalDate today) {
        return new PlanDocument(plan, null, () -> today, document.forecastService());
    }

    /** Дополняет общий валидатор кодами и адресами; предупреждения и сироты загруженных данных не запрещаются. */
    private static void validate(Plan plan) {
        List<PlanCommandProblem> problems = new ArrayList<>();
        Set<RuleId> ruleIds = new HashSet<>();
        for (RecurringRule rule : plan.rules()) {
            if (!ruleIds.add(rule.id())) problems.add(duplicate(rule.id(), null));
            amount(rule.amount(), rule.id(), null, null, problems);
            if (rule.from() != null && rule.until() != null && rule.until().isBefore(rule.from()))
                problems.add(new PlanCommandProblem(PlanCommandError.INVALID_RECURRENCE, rule.id(), null, null, Map.of(),
                        Texts.get("diagnostic.rule.untilBeforeFrom", DateFormats.ru(rule.until()), DateFormats.ru(rule.from()))));
        }
        Set<TxId> transactionIds = new HashSet<>();
        plan.oneTimes().forEach(transaction -> {
            if (!transactionIds.add(transaction.id())) problems.add(duplicate(null, transaction.id()));
            amount(transaction.amount(), null, transaction.id(), null, problems);
        });
        plan.adjustments().forEach(adjustment -> adjustment.action().newAmount().ifPresent(value ->
                amount(value, adjustment.key().ruleId(), null, adjustment.key(), problems)));
        if (!problems.isEmpty()) throw new Rejected(problems);
        PlanValidator.validate(plan).stream().filter(diagnostic -> diagnostic.severity() == Severity.ERROR)
                .forEach(diagnostic -> problems.add(new PlanCommandProblem(PlanCommandError.INVALID_PLAN,
                        null, null, null, Map.of(), diagnostic.message())));
        if (!problems.isEmpty()) throw new Rejected(problems);
    }

    /** Проверяет положительную сумму операции без повторного разбора пользовательского текста. */
    private static void amount(Money value, RuleId ruleId, TxId transactionId, OccurrenceKey key,
                               List<PlanCommandProblem> problems) {
        String message = !value.isPositive() ? Texts.get(key == null
                ? "diagnostic.item.amountNotPositive" : "diagnostic.adjustment.amountNotPositive")
                : value.compareTo(PlanValidator.MAX_AMOUNT) > 0 ? Texts.get(key == null
                ? "diagnostic.item.amountTooLarge" : "diagnostic.adjustment.amountTooLarge", PlanValidator.MAX_AMOUNT.format()) : null;
        if (message != null) problems.add(new PlanCommandProblem(PlanCommandError.INVALID_AMOUNT, ruleId,
                transactionId, key, Map.of("minorUnits", Long.toString(value.minor())), message));
    }

    /** Проверяет величину баланса, сохраняя допустимость отрицательного долга и нулевого остатка. */
    private static void checkBalance(Money value) {
        if (value != null && value.abs().compareTo(PlanValidator.MAX_AMOUNT) > 0)
            reject(new PlanCommandProblem(PlanCommandError.INVALID_AMOUNT, null, null, null,
                    Map.of("minorUnits", Long.toString(value.minor())), Texts.get("money.error.outOfRange")));
    }

    /** Проверяет новую цель отдельно от предупреждений о цели в терпимо прочитанном файле. */
    private static void checkGoal(ru.cashprediction.core.model.Goal goal) {
        if (goal == null) return;
        List<PlanCommandProblem> problems = new ArrayList<>();
        amount(goal.target(), null, null, null, problems);
        if (!problems.isEmpty()) throw new Rejected(problems);
    }

    /** Требует существующее правило вместо неявного добавления модельным withRuleReplaced. */
    private static RecurringRule requireRule(Plan plan, RuleId id) {
        return plan.findRule(id).orElseThrow(() -> new Rejected(List.of(new PlanCommandProblem(
                PlanCommandError.NOT_FOUND, id, null, null, Map.of(), Texts.get("err.notFound.content", id)))));
    }

    /** Требует существующую разовую операцию вместо неявного добавления. */
    private static void requireTransaction(Plan plan, TxId id) {
        if (plan.findOneTime(id).isEmpty()) reject(new PlanCommandProblem(PlanCommandError.NOT_FOUND, null,
                id, null, Map.of(), Texts.get("err.notFound.content", id)));
    }

    /** Обязательное поле команды: null становится типизированным отказом. */
    private static void required(Object value) {
        if (value == null) reject(generic(PlanCommandError.INVALID_COMMAND, Map.of()));
    }

    /** Структурированное объяснение занятого идентификатора. */
    private static PlanCommandProblem duplicate(RuleId ruleId, TxId transactionId) {
        Object id = ruleId == null ? transactionId : ruleId;
        return new PlanCommandProblem(PlanCommandError.DUPLICATE_ID, ruleId, transactionId, null, Map.of(),
                Texts.get("diagnostic.item.duplicateId", id));
    }

    /** Общий локализованный отказ с машинными аргументами для вызывающего кода. */
    private static PlanCommandProblem generic(PlanCommandError code, Map<String, String> arguments) {
        return new PlanCommandProblem(code, null, null, null, arguments, Texts.get("err.editFailed"));
    }

    /** Сохраняет прежнее объяснение ошибки расчёта, не передавая наружу исключение или документ. */
    private static PlanCommandProblem calculationProblem(PlanCommandError code, RuntimeException failure) {
        String message = failure.getMessage();
        return new PlanCommandProblem(code, null, null, null, Map.of(),
                message == null || message.isBlank() ? Texts.get("err.forecast") : message);
    }

    /** Прерывает подготовку до изменения документа. */
    private static void reject(PlanCommandProblem problem) { throw new Rejected(List.of(problem)); }

    /** Формирует отказ с текущим неизменяемым снимком. */
    private PlanCommandResult refused(PlanCommandRequest request, PlanCommandProblem problem) {
        return result(request, PlanCommandResult.Status.REJECTED, snapshot(), PlanCommandEffect.NONE, List.of(problem));
    }

    /** Собирает неизменяемый ответ из подготовленных данных. */
    private static PlanCommandResult result(PlanCommandRequest request, PlanCommandResult.Status status,
            PlanCommandSnapshot snapshot, PlanCommandEffect effect, List<PlanCommandProblem> problems) {
        return new PlanCommandResult(request.requestId(), status, snapshot, effect, problems);
    }
}
