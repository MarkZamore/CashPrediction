package ru.cashprediction.fx.action;

import javafx.scene.control.Alert.AlertType;
import ru.cashprediction.core.forecast.Forecast;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.markdown.RuFormats;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;
import ru.cashprediction.fx.dialog.AdjustmentDialog;
import ru.cashprediction.fx.dialog.AppButtonTypes;
import ru.cashprediction.fx.dialog.OneTimeDialog;
import ru.cashprediction.fx.dialog.OpenRequest;
import ru.cashprediction.fx.dialog.PlanSettingsDialog;
import ru.cashprediction.fx.dialog.RuleDialog;
import ru.cashprediction.fx.dialog.StatefulAlert;
import ru.cashprediction.fx.dialog.StatefulTextInputDialog;
import ru.cashprediction.fx.session.FieldValues;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Команды меню «Правка» и контекстного меню строки таблицы: правила, разовые операции, корректировки
 * событий, удаление, отмена/повтор, параметры плана, актуализация и сверка баланса.
 *
 * <p>Любое изменение плана идёт через {@code PlanDocument.edit} (одним шагом истории), поэтому каждое действие
 * можно отменить Ctrl+Z. Диалоги открываются через {@link ru.cashprediction.fx.dialog.FxDialogHost}
 * без блокировки потока; результат применяется к <i>текущему</i> плану в момент подтверждения — пока диалог был
 * открыт, план мог измениться (например, через немодальный калькулятор цели).</p>
 *
 * <p>Только FX Application Thread.</p>
 */
final class EditCommands {

    private final CommandSupport support;

    EditCommands(CommandSupport support) {
        this.support = support;
    }

    // ------------------------------------------------------------------ регулярные операции

    /**
     * Диалог 3 «Регулярная операция» в режиме создания.
     *
     * @param kind    доход или расход
     * @param request запрос открытия
     */
    void addRule(Kind kind, OpenRequest request) {
        // JavaFX: Dialog<R> → Swing: SwingDialog<R> (RuleDialog) → Web: openDialog('rule'): Promise<R>
        RuleDialog dialog = new RuleDialog(support.plan(), null, kind, support.context().today(), null);
        support.host().open(dialog, request, result -> result.ifPresent(rule ->
                // Идентификатор назначается в момент применения: пока диалог был открыт, мог появиться другой r-номер.
                support.edit("Добавление операции «" + rule.title() + "»",
                        p -> p.withRuleAdded(rule.withId(p.nextRuleId())))));
    }

    /**
     * Диалог 3 «Регулярная операция» в режиме изменения.
     *
     * @param id      идентификатор правила
     * @param request запрос открытия
     */
    void editRule(RuleId id, OpenRequest request) {
        Optional<RecurringRule> rule = support.plan().findRule(id);
        if (rule.isEmpty()) {
            support.cannotOpen(request, "Операция не найдена", "В плане нет регулярной операции " + id.value());
            return;
        }
        // JavaFX: Dialog<R> → Swing: SwingDialog<R> (RuleDialog) → Web: openDialog('rule'): Promise<R>
        RuleDialog dialog = new RuleDialog(support.plan(), rule.get(), rule.get().kind(), support.context().today(),
                // Корректировка события из предпросмотра открывается поверх редактора правила (вложенный модальный диалог).
                (key, ownerId) -> adjustOccurrence(key, OpenRequest.ownedBy(ownerId)));
        support.host().open(dialog, request, result -> result.ifPresent(changed ->
                support.edit("Изменение операции «" + changed.title() + "»", p -> p.withRuleReplaced(changed))));
    }

    /**
     * Диалог 11 «Удаление правила»: {@code Alert(CONFIRMATION)} с кнопкой «Удалить» и числом корректировок.
     *
     * @param id      идентификатор правила
     * @param request запрос открытия
     */
    void deleteRule(RuleId id, OpenRequest request) {
        Optional<RecurringRule> rule = support.plan().findRule(id);
        if (rule.isEmpty()) {
            support.cannotOpen(request, "Операция не найдена", "В плане нет регулярной операции " + id.value());
            return;
        }
        int adjustments = support.plan().adjustmentsOf(id).size();
        String content = adjustments == 0
                ? "У операции нет корректировок событий."
                : "Вместе с ней будут удалены " + RuText.count(adjustments, "корректировка", "корректировки", "корректировок")
                + " её событий.";
        // JavaFX: Alert → Swing: JOptionPane.showConfirmDialog → Web: <dialog class="alert">
        StatefulAlert alert = new StatefulAlert(AlertType.CONFIRMATION, "deleteRule", id.value(), "Удаление операции",
                "Удалить регулярную операцию «" + rule.get().title() + "»?",
                content + "\nДействие можно отменить: Правка → Отменить (Ctrl+Z).",
                AppButtonTypes.DELETE, AppButtonTypes.CANCEL);
        support.host().open(alert, request, result -> {
            if (result.orElse(AppButtonTypes.CANCEL) == AppButtonTypes.DELETE) {
                support.edit("Удаление операции «" + rule.get().title() + "»", p -> p.withRuleRemoved(id));
            }
        });
    }

    /**
     * «Отключить правило» из контекстного меню: правило остаётся в плане, но не участвует в прогнозе.
     *
     * @param id идентификатор правила
     */
    void disableRule(RuleId id) {
        support.plan().findRule(id).filter(RecurringRule::enabled).ifPresent(rule ->
                support.edit("Отключение операции «" + rule.title() + "»", p -> p.withRuleReplaced(rule.withEnabled(false))));
    }

    // ------------------------------------------------------------------ разовые операции

    /**
     * Диалог 4 «Разовая операция» в режиме создания.
     *
     * @param date    дата новой операции ({@code null} — сегодня или дата начала плана)
     * @param kind    доход или расход
     * @param request запрос открытия
     */
    void addOneTime(LocalDate date, Kind kind, OpenRequest request) {
        Plan plan = support.plan();
        LocalDate today = support.context().today();
        LocalDate initial = date != null ? date : (today.isBefore(plan.startDate()) ? plan.startDate() : today);
        // JavaFX: Dialog<R> → Swing: SwingDialog<R> (OneTimeDialog) → Web: openDialog('oneTime'): Promise<R>
        OneTimeDialog dialog = new OneTimeDialog(plan, null, initial, kind);
        support.host().open(dialog, request, result -> result.ifPresent(tx ->
                support.edit("Добавление разовой операции «" + tx.title() + "»",
                        p -> p.withOneTimeAdded(tx.withId(p.nextTxId())))));
    }

    /**
     * Диалог 4 «Разовая операция» в режиме изменения.
     *
     * @param id      идентификатор операции
     * @param request запрос открытия
     */
    void editOneTime(TxId id, OpenRequest request) {
        Optional<OneTimeTransaction> tx = support.plan().findOneTime(id);
        if (tx.isEmpty()) {
            support.cannotOpen(request, "Операция не найдена", "В плане нет разовой операции " + id.value());
            return;
        }
        // JavaFX: Dialog<R> → Swing: SwingDialog<R> (OneTimeDialog) → Web: openDialog('oneTime'): Promise<R>
        OneTimeDialog dialog = new OneTimeDialog(support.plan(), tx.get(), tx.get().date(), tx.get().kind());
        support.host().open(dialog, request, result -> result.ifPresent(changed ->
                support.edit("Изменение разовой операции «" + changed.title() + "»", p -> p.withOneTimeReplaced(changed))));
    }

    /**
     * Диалог 11 «Удаление разовой операции».
     *
     * @param id      идентификатор операции
     * @param request запрос открытия
     */
    void deleteOneTime(TxId id, OpenRequest request) {
        Optional<OneTimeTransaction> tx = support.plan().findOneTime(id);
        if (tx.isEmpty()) {
            support.cannotOpen(request, "Операция не найдена", "В плане нет разовой операции " + id.value());
            return;
        }
        // JavaFX: Alert → Swing: JOptionPane.showConfirmDialog → Web: <dialog class="alert">
        StatefulAlert alert = new StatefulAlert(AlertType.CONFIRMATION, "deleteOneTime", id.value(), "Удаление операции",
                "Удалить разовую операцию «" + tx.get().title() + "» от " + DateFormats.ru(tx.get().date()) + "?",
                "Действие можно отменить: Правка → Отменить (Ctrl+Z).",
                AppButtonTypes.DELETE, AppButtonTypes.CANCEL);
        support.host().open(alert, request, result -> {
            if (result.orElse(AppButtonTypes.CANCEL) == AppButtonTypes.DELETE) {
                support.edit("Удаление разовой операции «" + tx.get().title() + "»", p -> p.withOneTimeRemoved(id));
            }
        });
    }

    // ------------------------------------------------------------------ корректировки событий

    /**
     * Диалог 5 «Корректировка события».
     *
     * @param key     событие: правило и номинальная дата
     * @param request запрос открытия
     */
    void adjustOccurrence(OccurrenceKey key, OpenRequest request) {
        Optional<RecurringRule> rule = support.plan().findRule(key.ruleId());
        if (rule.isEmpty()) {
            support.cannotOpen(request, "Операция не найдена", "В плане нет регулярной операции " + key.ruleId().value());
            return;
        }
        Adjustment existing = support.plan().findAdjustment(key).orElse(null);
        // JavaFX: Dialog<R> → Swing: SwingDialog<R> (AdjustmentDialog) → Web: openDialog('adjustment'): Promise<R>
        AdjustmentDialog dialog = new AdjustmentDialog(support.plan(), rule.get(), key.originalDate(), existing);
        support.host().open(dialog, request, result -> result.ifPresent(outcome -> outcome.adjustmentOptional().ifPresentOrElse(
                adjustment -> support.edit("Корректировка «" + rule.get().title() + "» " + DateFormats.ru(key.originalDate()),
                        p -> p.withAdjustmentPut(adjustment)),
                () -> support.edit("Сброс корректировки «" + rule.get().title() + "» " + DateFormats.ru(key.originalDate()),
                        p -> p.withAdjustmentRemoved(outcome.key())))));
    }

    /**
     * «Пропустить» событие из контекстного меню: корректировка «пропустить» без диалога.
     *
     * @param key событие
     */
    void skipOccurrence(OccurrenceKey key) {
        String note = support.plan().findAdjustment(key).map(Adjustment::note).orElse("");
        support.edit("Пропуск события " + DateFormats.ru(key.originalDate()),
                p -> p.withAdjustmentPut(new Adjustment(key, RuFormats.buildAction(RuFormats.ActionType.SKIP, null, null), note)));
    }

    /**
     * «Вернуть как по правилу»: удаляет корректировку события.
     *
     * @param key событие
     */
    void resetOccurrence(OccurrenceKey key) {
        if (support.plan().findAdjustment(key).isEmpty()) {
            support.info("У события нет корректировки", "Событие " + DateFormats.ru(key.originalDate()) + " и так идёт по правилу.");
            return;
        }
        support.edit("Возврат события " + DateFormats.ru(key.originalDate()) + " к правилу", p -> p.withAdjustmentRemoved(key));
    }

    /**
     * Результат быстрой правки суммы ({@code Popup} у ячейки): корректировка «изменить сумму».
     * Если событие уже перенесено, получается «заменить» (сумма и прежняя новая дата).
     *
     * @param key    событие
     * @param amount новая сумма (больше нуля)
     */
    void quickEditAmount(OccurrenceKey key, Money amount) {
        Optional<Adjustment> existing = support.plan().findAdjustment(key);
        Optional<LocalDate> movedTo = existing.flatMap(a -> a.action().newDate());
        String note = existing.map(Adjustment::note).orElse("");
        Adjustment.Action action = movedTo.isPresent()
                ? RuFormats.buildAction(RuFormats.ActionType.REPLACE, amount, movedTo.get())
                : RuFormats.buildAction(RuFormats.ActionType.CHANGE_AMOUNT, amount, null);
        support.edit("Быстрая правка суммы " + DateFormats.ru(key.originalDate()),
                p -> p.withAdjustmentPut(new Adjustment(key, action, note)));
    }

    // ------------------------------------------------------------------ команды по строке таблицы

    /**
     * «Изменить» (Enter) для строки таблицы: правило, разовая операция или параметры плана для начального баланса.
     *
     * @param rowId идентификатор строки
     */
    void editRow(String rowId) {
        Optional<ForecastRow> row = findRow(rowId);
        if (row.isEmpty()) {
            support.info("Ничего не выбрано", "Выберите строку в таблице прогноза.");
            return;
        }
        switch (row.get().origin()) {
            case RULE -> editRule(row.get().ruleId(), OpenRequest.fromMain());
            case ONE_TIME -> editOneTime(row.get().txId(), OpenRequest.fromMain());
            case START -> planSettings(OpenRequest.fromMain());
            case WHAT_IF -> support.info("Строка режима «что-если»",
                    "Эта строка — гипотетическая экономия. Изменить её можно в меню Инструменты → Что-если.");
        }
    }

    /**
     * «Удалить» (Delete) для строки таблицы.
     *
     * @param rowId идентификатор строки
     */
    void deleteRow(String rowId) {
        Optional<ForecastRow> row = findRow(rowId);
        if (row.isEmpty()) {
            support.info("Ничего не выбрано", "Выберите строку регулярной или разовой операции.");
            return;
        }
        if (row.get().origin() == Origin.RULE) {
            deleteRule(row.get().ruleId(), OpenRequest.fromMain());
        } else if (row.get().origin() == Origin.ONE_TIME) {
            deleteOneTime(row.get().txId(), OpenRequest.fromMain());
        } else {
            support.info("Эту строку удалить нельзя", "Удалять можно регулярные и разовые операции.");
        }
    }

    /**
     * «Скорректировать событие» (Ctrl+J) для строки таблицы.
     *
     * @param rowId идентификатор строки
     */
    void adjustRow(String rowId) {
        Optional<OccurrenceKey> key = findRow(rowId).flatMap(ForecastRow::occurrenceKey);
        if (key.isEmpty()) {
            support.info("Выберите событие регулярной операции",
                    "Корректировать можно конкретное событие правила: например, строку «Зарплата» за октябрь.");
            return;
        }
        adjustOccurrence(key.get(), OpenRequest.fromMain());
    }

    /**
     * «Вернуть как по правилу» для строки таблицы.
     *
     * @param rowId идентификатор строки
     */
    void resetRow(String rowId) {
        findRow(rowId).flatMap(ForecastRow::occurrenceKey).ifPresentOrElse(this::resetOccurrence,
                () -> support.info("Выберите событие регулярной операции", "Вернуть к правилу можно только событие правила."));
    }

    /**
     * «Пропустить» для строки таблицы.
     *
     * @param rowId идентификатор строки
     */
    void skipRow(String rowId) {
        findRow(rowId).flatMap(ForecastRow::occurrenceKey).ifPresent(this::skipOccurrence);
    }

    // ------------------------------------------------------------------ план целиком

    /**
     * Диалог 2 «Параметры плана».
     *
     * @param request запрос открытия
     */
    void planSettings(OpenRequest request) {
        // JavaFX: Dialog<R> → Swing: SwingDialog<R> (PlanSettingsDialog) → Web: openDialog('planSettings'): Promise<R>
        PlanSettingsDialog dialog = new PlanSettingsDialog(support.plan(), support.document().file().isEmpty());
        support.host().open(dialog, request, result -> result.ifPresent(parameters ->
                support.edit("Изменение параметров плана", parameters::applyTo)));
    }

    /**
     * «Актуализировать на сегодня»: подтверждение ({@code Alert}), затем {@code PlanDocument.actualize}.
     *
     * @param request запрос открытия
     */
    void actualize(OpenRequest request) {
        LocalDate today = support.context().today();
        Plan plan = support.plan();
        if (!today.isAfter(plan.startDate())) {
            support.cannotOpen(request, "Актуализировать нечего",
                    "План начинается " + DateFormats.ru(plan.startDate()) + " — это не раньше сегодняшнего дня.");
            return;
        }
        String balance;
        try {
            balance = support.document().forecast().balanceAt(today.minusDays(1)).format(plan.currency());
        } catch (IllegalStateException e) {
            support.cannotOpen(request, "Прогноз не рассчитан", e.getMessage());
            return;
        }
        // JavaFX: Alert → Swing: JOptionPane.showConfirmDialog → Web: <dialog class="alert">
        StatefulAlert alert = new StatefulAlert(AlertType.CONFIRMATION, "actualize", null, "Актуализация плана",
                "Перенести начало плана на сегодня, " + DateFormats.ru(today) + "?",
                "Начальный баланс станет прогнозным на начало дня: " + balance + ".\n"
                        + "Разовые операции до сегодняшнего дня будут удалены. Действие можно отменить (Ctrl+Z).",
                AppButtonTypes.ACTUALIZE, AppButtonTypes.CANCEL);
        support.host().open(alert, request, result -> {
            if (result.orElse(AppButtonTypes.CANCEL) == AppButtonTypes.ACTUALIZE) {
                try {
                    support.document().actualize(support.context().today(), null);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    support.error("Актуализация не выполнена", Objects.requireNonNullElse(e.getMessage(), e.toString()));
                }
            }
        });
    }

    /**
     * Диалог 7 «Сверить баланс» ({@code TextInputDialog}) → {@code PlanDocument.reconcile}.
     *
     * @param request запрос открытия
     */
    void reconcile(OpenRequest request) {
        LocalDate today = support.context().today();
        Plan plan = support.plan();
        String expected;
        try {
            expected = support.document().forecast().balanceAt(today).format(plan.currency());
        } catch (IllegalStateException e) {
            support.cannotOpen(request, "Прогноз не рассчитан", e.getMessage());
            return;
        }
        // JavaFX: TextInputDialog → Swing: JOptionPane.showInputDialog → Web: <dialog> с <input>
        StatefulTextInputDialog dialog = new StatefulTextInputDialog("reconcile", "Сверить баланс",
                "Сколько денег у вас на самом деле на конец дня " + DateFormats.ru(today) + "?\n"
                        + "По прогнозу: " + expected + ". Разница добавится разовой операцией «Сверка баланса».",
                "Фактический баланс:", "", AppButtonTypes.RECONCILE,
                text -> FieldValues.parseMoney(text).isPresent() ? Optional.empty()
                        : Optional.of("Введите сумму, например 95 000,00 (можно отрицательную)"),
                true);
        support.host().open(dialog, request, result -> result.flatMap(FieldValues::parseMoney).ifPresent(actual -> {
            try {
                support.document().reconcile(support.context().today(), actual);
            } catch (IllegalArgumentException e) {
                support.error("Сверка не выполнена", e.getMessage());
            }
        }));
    }

    /**
     * Меняет горизонт плана на заданное число месяцев (слайдер «Горизонт плана» в меню «Вид»).
     *
     * @param months число месяцев
     */
    void setHorizonMonths(int months) {
        support.edit("Горизонт плана: " + RuText.count(months, "месяц", "месяца", "месяцев"),
                p -> p.withHorizon(new ru.cashprediction.core.model.Horizon.Months(months)));
    }

    /**
     * Диалог «Горизонт в месяцах» ({@code TextInputDialog}, назначение {@code customMonths}).
     *
     * @param request запрос открытия
     */
    void customMonths(OpenRequest request) {
        Plan plan = support.plan();
        long current = Math.max(1, java.time.temporal.ChronoUnit.MONTHS.between(plan.startDate(), plan.endDate().plusDays(1)));
        // JavaFX: TextInputDialog → Swing: JOptionPane.showInputDialog → Web: <dialog> с <input>
        StatefulTextInputDialog dialog = new StatefulTextInputDialog("customMonths", "Горизонт плана",
                "На сколько месяцев вперёд считать прогноз? Сейчас: до " + DateFormats.ru(plan.endDate()) + ".",
                "Месяцев (1–600):", Long.toString(current), AppButtonTypes.APPLY,
                text -> FieldValues.parseInt(text).filter(n -> n >= 1 && n <= 600).isPresent()
                        ? Optional.empty() : Optional.of("Целое число от 1 до 600"),
                false);
        support.host().open(dialog, request, result -> result.flatMap(FieldValues::parseInt)
                .filter(n -> n >= 1 && n <= 600).ifPresent(this::setHorizonMonths));
    }

    // ------------------------------------------------------------------ служебное

    private Optional<ForecastRow> findRow(String rowId) {
        if (rowId == null || rowId.isBlank()) {
            return Optional.empty();
        }
        try {
            Forecast forecast = support.document().forecast();
            return forecast.findRow(rowId);
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
    }

    /**
     * Проверка суммы для полей быстрой правки: положительная и не больше предела.
     *
     * @param text текст
     * @return сумма или пусто
     */
    static Optional<Money> positiveAmount(String text) {
        return FieldValues.parseMoney(text).filter(Money::isPositive)
                .filter(m -> m.compareTo(ru.cashprediction.core.diagnostics.PlanValidator.MAX_AMOUNT) <= 0);
    }
}
