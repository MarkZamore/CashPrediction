package ru.cashprediction.swing.action;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import ru.cashprediction.core.forecast.ForecastEngine;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Horizon;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.core.util.RuText;
import ru.cashprediction.swing.dialog.AdjustmentDialog;
import ru.cashprediction.swing.dialog.OneTimeDialog;
import ru.cashprediction.swing.dialog.PlanSettingsDialog;
import ru.cashprediction.swing.dialog.RuleDialog;
import ru.cashprediction.swing.dialog.SwingAlert;
import ru.cashprediction.swing.dialog.SwingTextInputDialog;

/**
 * Команды меню «Правка» и контекстного меню таблицы: правила, разовые операции, корректировки событий,
 * удаление, отмена/повтор, параметры плана, актуализация, сверка баланса, горизонт и быстрая правка суммы.
 *
 * <p>Все изменения плана идут только через {@code PlanDocument.edit(описание, изменение)}: так работают
 * отмена/повтор, признак несохранённых изменений и пересчёт прогноза.</p>
 *
 * <p>Класс используется только в потоке EDT.</p>
 */
final class EditActions extends ActionSupport {

    /**
     * Создаёт команды правки.
     *
     * @param shared общие объекты команд
     */
    public EditActions(ActionShared shared) {
        super(shared);
    }

    // ------------------------------------------------------------------ регулярные операции

    /**
     * «Добавить доход…» (Ctrl+I) и «Добавить расход…» (Ctrl+E).
     *
     * @param kind тип нового правила
     */
    public void addRuleCommand(Kind kind) {
        openRuleEditor(interactive(), "", kind);
    }

    /**
     * Диалог 3 «Регулярная операция».
     *
     * @param request    как открыть окно
     * @param ruleId     правило для изменения; пустая строка — новое правило
     * @param presetKind тип нового правила или {@code null}
     */
    public void openRuleEditor(OpenRequest request, String ruleId, Kind presetKind) {
        Plan plan = plan();
        RecurringRule existing = null;
        if (ruleId != null && !ruleId.isBlank()) {
            existing = plan.findRule(new RuleId(ruleId.strip())).orElse(null);
            if (existing == null) {
                cannotOpen(request, "Правило не найдено", "В плане нет регулярной операции «" + ruleId + "».");
                return;
            }
        }
        RecurringRule edited = existing;
        RuleDialog dialog = new RuleDialog(owner(request), request.ownerId(), recorder(), plan, existing, presetKind, today(),
                edited == null ? null : nominal -> openAdjustment(interactive(), edited.id().value(), nominal));
        dialog.setOnResult(result -> result.ifPresent(rule -> {
            if (edited != null) {
                edit("Изменение правила «" + rule.title() + "»", p -> p.withRuleReplaced(rule));
            } else {
                // Идентификатор назначается на момент сохранения: пока окно было открыто, план мог получить правила.
                edit("Новое правило «" + rule.title() + "»", p -> p.withRuleAdded(
                        p.findRule(rule.id()).isPresent() ? rule.withId(p.nextRuleId()) : rule));
            }
        }));
        show(dialog, request);
    }

    // ------------------------------------------------------------------ разовые операции

    /**
     * «Разовая операция…» (Ctrl+T) или «Добавить разовую на эту дату…».
     *
     * @param presetDate дата новой операции или {@code null}
     */
    public void addOneTimeCommand(LocalDate presetDate) {
        openOneTimeEditor(interactive(), "", presetDate, null);
    }

    /**
     * Диалог 4 «Разовая операция».
     *
     * @param request    как открыть окно
     * @param txId       операция для изменения; пустая строка — новая
     * @param presetDate дата новой операции или {@code null}
     * @param presetKind тип новой операции или {@code null}
     */
    public void openOneTimeEditor(OpenRequest request, String txId, LocalDate presetDate, Kind presetKind) {
        Plan plan = plan();
        OneTimeTransaction existing = null;
        if (txId != null && !txId.isBlank()) {
            existing = plan.findOneTime(new TxId(txId.strip())).orElse(null);
            if (existing == null) {
                cannotOpen(request, "Операция не найдена", "В плане нет разовой операции «" + txId + "».");
                return;
            }
        }
        OneTimeTransaction edited = existing;
        OneTimeDialog dialog = new OneTimeDialog(owner(request), request.ownerId(), recorder(), plan, existing, presetDate,
                presetKind, today());
        dialog.setOnResult(result -> result.ifPresent(tx -> {
            if (edited != null) {
                edit("Изменение операции «" + tx.title() + "»", p -> p.withOneTimeReplaced(tx));
            } else {
                edit("Новая операция «" + tx.title() + "»", p -> p.withOneTimeAdded(
                        p.findOneTime(tx.id()).isPresent() ? tx.withId(p.nextTxId()) : tx));
            }
        }));
        show(dialog, request);
    }

    // ------------------------------------------------------------------ корректировки

    /**
     * «Скорректировать событие…» (Ctrl+J) для выделенной строки.
     */
    public void adjustSelectedCommand() {
        Optional<ForecastRow> row = selectedRow().filter(r -> r.origin() == Origin.RULE && r.ruleId() != null);
        if (row.isEmpty()) {
            alerts().info("Выберите событие регулярной операции", "Корректировка относится к одному событию правила: выделите его строку в таблице.");
            return;
        }
        openAdjustment(interactive(), row.get().ruleId().value(), row.get().originalDate());
    }

    /**
     * Диалог 5 «Корректировка события»: пропустить, изменить сумму, перенести или заменить; «Сбросить» удаляет корректировку.
     *
     * @param request      как открыть окно
     * @param ruleId       правило
     * @param originalDate номинальная дата события
     */
    public void openAdjustment(OpenRequest request, String ruleId, LocalDate originalDate) {
        if (ruleId == null || ruleId.isBlank() || originalDate == null) {
            cannotOpen(request, "Нельзя скорректировать событие", "Не указаны правило или дата события.");
            return;
        }
        AdjustmentDialog dialog = new AdjustmentDialog(owner(request), request.ownerId(), recorder(), plan(),
                new RuleId(ruleId.strip()), originalDate);
        dialog.setOnResult(result -> result.ifPresent(outcome -> {
            if (outcome.isReset()) {
                edit("Сброс корректировки " + DateFormats.ru(outcome.key().originalDate()),
                        p -> p.withAdjustmentRemoved(outcome.key()));
            } else {
                edit("Корректировка " + DateFormats.ru(outcome.key().originalDate()),
                        p -> p.withAdjustmentPut(outcome.adjustment()));
            }
        }));
        show(dialog, request);
    }

    /**
     * «Пропустить» событие строки.
     *
     * @param row строка события правила
     */
    public void skip(ForecastRow row) {
        row.occurrenceKey().ifPresent(key -> edit("Пропуск события " + DateFormats.ru(key.originalDate()),
                p -> p.withAdjustmentPut(new Adjustment(key, new Adjustment.Skip(),
                        p.findAdjustment(key).map(Adjustment::note).orElse("")))));
    }

    /**
     * «Вернуть как по правилу»: удаляет корректировку события.
     *
     * @param row строка события правила
     */
    public void resetToRule(ForecastRow row) {
        row.occurrenceKey().filter(key -> plan().findAdjustment(key).isPresent())
                .ifPresent(key -> edit("Сброс корректировки " + DateFormats.ru(key.originalDate()),
                        p -> p.withAdjustmentRemoved(key)));
    }

    /**
     * «Правка → Вернуть как по правилу» для выделенной строки.
     */
    public void resetSelectedCommand() {
        selectedRow().ifPresent(this::resetToRule);
    }

    // ------------------------------------------------------------------ изменить и удалить выделенное

    /**
     * «Изменить…» (Enter): редактор той сущности, которой принадлежит выделенная строка.
     */
    public void editSelectedCommand() {
        selectedRow().ifPresentOrElse(this::editRow,
                () -> alerts().info("Ничего не выделено", "Выделите строку таблицы, чтобы изменить операцию."));
    }

    /**
     * Открывает редактор для строки прогноза.
     *
     * @param row строка
     */
    public void editRow(ForecastRow row) {
        switch (row.origin()) {
            case RULE -> {
                if (row.ruleId() != null) {
                    openRuleEditor(interactive(), row.ruleId().value(), null);
                }
            }
            case ONE_TIME -> {
                if (row.txId() != null) {
                    openOneTimeEditor(interactive(), row.txId().value(), null, null);
                }
            }
            case START -> planSettings(interactive());
            case WHAT_IF -> alerts().info("Строка режима «что-если»",
                    "Это расчётная строка дополнительной экономии. Её сумма задаётся в «Инструменты → Что-если».");
        }
    }

    /**
     * «Удалить…» (Delete) для выделенной строки.
     */
    public void deleteSelectedCommand() {
        selectedRow().ifPresentOrElse(this::deleteRow,
                () -> alerts().info("Ничего не выделено", "Выделите строку операции, которую нужно удалить."));
    }

    /**
     * Спрашивает подтверждение и удаляет правило или разовую операцию строки.
     *
     * @param row строка
     */
    public void deleteRow(ForecastRow row) {
        if (row.origin() == Origin.RULE && row.ruleId() != null) {
            openDeleteConfirmation(interactive(), Purposes.DELETE_RULE, row.ruleId().value());
        } else if (row.origin() == Origin.ONE_TIME && row.txId() != null) {
            openDeleteConfirmation(interactive(), Purposes.DELETE_ONE_TIME, row.txId().value());
        } else {
            alerts().info("Эту строку удалить нельзя", "Удалить можно регулярную или разовую операцию.");
        }
    }

    /**
     * Диалог 11 «Удаление правила/операции» ({@code Alert(CONFIRMATION)} с кнопкой «Удалить»); восстанавливается
     * после сбоя как окно {@code ALERT} с тем же текстом.
     *
     * @param request  как открыть окно
     * @param purpose  {@link Purposes#DELETE_RULE} или {@link Purposes#DELETE_ONE_TIME}
     * @param targetId идентификатор правила или операции
     */
    public void openDeleteConfirmation(OpenRequest request, String purpose, String targetId) {
        Plan plan = plan();
        String header;
        String content;
        Runnable delete;
        if (Purposes.DELETE_RULE.equals(purpose)) {
            Optional<RecurringRule> rule = targetId.isBlank() ? Optional.empty() : plan.findRule(new RuleId(targetId));
            if (rule.isEmpty()) {
                cannotOpen(request, "Правило не найдено", "В плане нет регулярной операции «" + targetId + "».");
                return;
            }
            int adjustments = plan.adjustmentsOf(rule.get().id()).size();
            header = "Удалить регулярную операцию «" + rule.get().title() + "»?";
            content = (adjustments > 0
                    ? "Вместе с ней будут удалены " + RuText.count(adjustments, "корректировка", "корректировки", "корректировок") + "."
                    : "Корректировок у этой операции нет.")
                    + "\nУдаление можно отменить: Правка → Отменить (Ctrl+Z).";
            RuleId id = rule.get().id();
            String title = rule.get().title();
            delete = () -> edit("Удаление правила «" + title + "»", p -> p.withRuleRemoved(id));
        } else if (Purposes.DELETE_ONE_TIME.equals(purpose)) {
            Optional<OneTimeTransaction> tx = targetId.isBlank() ? Optional.empty() : plan.findOneTime(new TxId(targetId));
            if (tx.isEmpty()) {
                cannotOpen(request, "Операция не найдена", "В плане нет разовой операции «" + targetId + "».");
                return;
            }
            header = "Удалить разовую операцию «" + tx.get().title() + "» от " + DateFormats.ru(tx.get().date()) + "?";
            content = "Корректировки не затрагиваются.\nУдаление можно отменить: Правка → Отменить (Ctrl+Z).";
            TxId id = tx.get().id();
            String title = tx.get().title();
            delete = () -> edit("Удаление операции «" + title + "»", p -> p.withOneTimeRemoved(id));
        } else {
            cannotOpen(request, "Неизвестное подтверждение", "Назначение «" + purpose + "» не поддерживается.");
            return;
        }
        // JavaFX: Alert(CONFIRMATION) + ButtonType("Удалить", OK_DONE) → Swing: SwingAlert + SwingButtonType → Web: <dialog class="alert">
        SwingAlert alert = new SwingAlert(owner(request), SwingAlert.AlertType.CONFIRMATION, "Удаление", header, content,
                AppButtons.DELETE, AppButtons.CANCEL);
        alert.makeRestorable(request.ownerId(), purpose, targetId);
        alert.setOnResult(result -> {
            if (result.filter(AppButtons.DELETE::equals).isPresent()) {
                delete.run();
            }
        });
        show(alert, request);
    }

    /**
     * Включает или выключает правило («Отключить правило» в контекстном меню).
     *
     * @param ruleId  правило
     * @param enabled новое состояние
     */
    public void setRuleEnabled(RuleId ruleId, boolean enabled) {
        plan().findRule(ruleId).ifPresent(rule -> edit(
                (enabled ? "Включение правила «" : "Отключение правила «") + rule.title() + "»",
                p -> p.withRuleReplaced(rule.withEnabled(enabled))));
    }

    /**
     * «Копировать»: строка таблицы в буфер обмена, ячейки через табуляцию.
     *
     * @param row строка
     */
    public void copyRow(ForecastRow row) {
        String currency = plan().currency();
        String text = String.join("\t", DateFormats.ru(row.date()), row.title(), row.category(),
                row.amount().format(currency), row.balanceAfter().format(currency), row.note());
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        status("Строка скопирована в буфер обмена");
    }

    // ------------------------------------------------------------------ отмена и повтор

    /**
     * «Отменить» (Ctrl+Z).
     */
    public void undo() {
        document().undo();
    }

    /**
     * «Повторить» (Ctrl+Y).
     */
    public void redo() {
        document().redo();
    }

    // ------------------------------------------------------------------ параметры плана

    /**
     * «Правка → Параметры плана…».
     */
    public void planSettingsCommand() {
        planSettings(interactive());
    }

    /**
     * Диалог 2 «Параметры плана»; при смене имени у сохранённого плана файл переименовывается.
     *
     * @param request как открыть окно
     */
    public void planSettings(OpenRequest request) {
        Optional<Path> file = document().file();
        PlanSettingsDialog dialog = new PlanSettingsDialog(owner(request), request.ownerId(), recorder(), plan(),
                files()::planFileExists, file.isPresent());
        dialog.setOnResult(result -> result.ifPresent(values -> {
            String oldName = plan().name();
            edit("Параметры плана", values::applyTo);
            Optional<Path> currentFile = document().file();
            if (currentFile.isPresent() && !values.name().equals(oldName)) {
                files().renameFileAndSave(currentFile.get(), values.name());
            }
        }));
        show(dialog, request);
    }

    /**
     * «Актуализировать на сегодня…»: подтверждение ({@code Alert(CONFIRMATION)}), затем
     * {@code PlanDocument.actualize(сегодня, null)} — начало плана переносится на сегодня с прогнозным балансом
     * на начало дня. Подтверждение восстанавливается после сбоя как окно {@code ALERT} с назначением
     * {@code actualize}.
     *
     * @param request как открыть окно
     */
    public void actualize(OpenRequest request) {
        LocalDate now = today();
        Plan plan = plan();
        if (!now.isAfter(plan.startDate())) {
            cannotOpen(request, "Актуализировать нечего", "План начинается " + DateFormats.ru(plan.startDate())
                    + " - не раньше сегодняшнего дня.");
            return;
        }
        Money expected;
        try {
            // Баланс «на начало сегодняшнего дня» — по прогнозу без «что-если»: гипотезы не должны попасть в данные.
            expected = ForecastEngine.forecast(plan, WhatIf.NONE, now, false).balanceAt(now.minusDays(1));
        } catch (IllegalStateException e) {
            cannotOpen(request, "Прогноз не рассчитан", Objects.requireNonNullElse(e.getMessage(), e.toString()));
            return;
        }
        // JavaFX: Alert(CONFIRMATION) + ButtonType("Актуализировать", OK_DONE) → Swing: SwingAlert + SwingButtonType → Web: <dialog class="alert">
        SwingAlert alert = new SwingAlert(owner(request), SwingAlert.AlertType.CONFIRMATION, "Актуализация плана",
                "Перенести начало плана на сегодня, " + DateFormats.ru(now) + "?",
                "Начальный баланс станет прогнозным на начало дня: " + expected.format(plan.currency()) + ".\n"
                        + "Разовые операции до сегодняшнего дня будут удалены. Действие можно отменить (Ctrl+Z).",
                AppButtons.ACTUALIZE, AppButtons.CANCEL);
        alert.makeRestorable(request.ownerId(), Purposes.ACTUALIZE, "");
        alert.setOnResult(result -> {
            if (result.filter(AppButtons.ACTUALIZE::equals).isPresent()) {
                try {
                    // Дата берётся заново: подтверждение могло провисеть открытым до следующих суток.
                    document().actualize(today(), null);
                } catch (IllegalArgumentException | IllegalStateException e) {
                    alerts().error("Актуализация не выполнена", Objects.requireNonNullElse(e.getMessage(), e.toString()));
                }
            }
        });
        show(alert, request);
    }

    /**
     * «Сверить баланс…».
     */
    public void reconcileCommand() {
        Plan plan = plan();
        LocalDate now = today();
        if (now.isBefore(plan.startDate()) || now.isAfter(plan.endDate())) {
            alerts().info("Сверка недоступна", "Сверить баланс можно только на дату внутри горизонта прогноза ("
                    + DateFormats.ru(plan.startDate()) + " - " + DateFormats.ru(plan.endDate()) + ").");
            return;
        }
        reconcile(interactive());
    }

    /**
     * Диалог 7 «Сверить баланс» ({@code TextInputDialog}): разница с прогнозом добавляется разовой операцией.
     *
     * @param request как открыть окно
     */
    public void reconcile(OpenRequest request) {
        Plan plan = plan();
        LocalDate now = today();
        Money expected = ForecastEngine.forecast(plan, WhatIf.NONE, now, false).balanceAt(now);
        // JavaFX: TextInputDialog → Swing: SwingTextInputDialog → Web: <dialog> с <input>
        SwingTextInputDialog dialog = new SwingTextInputDialog(owner(request), request.ownerId(), recorder(), Purposes.RECONCILE,
                true, "Сверить баланс",
                "Сколько денег у вас на самом деле сегодня, " + DateFormats.ru(now) + "?\nПо прогнозу - "
                        + expected.format(plan.currency()) + ". Разница будет добавлена разовой операцией «Сверка баланса».",
                "Фактический баланс", expected.formatPlain(), EditActions::moneyError);
        dialog.setOnResult(result -> result.ifPresent(text -> {
            try {
                document().reconcile(today(), Money.parse(text));
            } catch (IllegalArgumentException e) {
                alerts().error("Не удалось сверить баланс", e.getMessage());
            }
        }));
        show(dialog, request);
    }

    // ------------------------------------------------------------------ горизонт

    /**
     * Меняет горизонт плана на заданное число месяцев (слайдер в меню «Вид»).
     *
     * @param months число месяцев, 1..600
     */
    public void setHorizonMonths(int months) {
        Horizon horizon = new Horizon.Months(months);
        if (!horizon.equals(plan().horizon())) {
            edit("Горизонт плана: " + months + " мес.", p -> p.withHorizon(horizon));
        }
    }

    /**
     * «Горизонт: другое число месяцев…».
     */
    public void customHorizonCommand() {
        customHorizon(interactive());
    }

    /**
     * Ввод произвольного горизонта в месяцах ({@code TextInputDialog}, назначение {@code customMonths}).
     *
     * @param request как открыть окно
     */
    public void customHorizon(OpenRequest request) {
        long current = plan().horizon().approximateMonths(plan().startDate());
        SwingTextInputDialog dialog = new SwingTextInputDialog(owner(request), request.ownerId(), recorder(),
                Purposes.CUSTOM_MONTHS, false, "Горизонт плана",
                "На сколько месяцев вперёд считать прогноз? Сейчас: " + plan().horizon().label() + ".",
                "Месяцев", Long.toString(current), EditActions::monthsError);
        dialog.setOnResult(result -> result.ifPresent(text -> setHorizonMonths(Integer.parseInt(text.strip()))));
        show(dialog, request);
    }

    // ------------------------------------------------------------------ быстрая правка суммы

    /**
     * Двойной щелчок по сумме: всплывающая быстрая правка для события правила.
     *
     * @param row строка
     */
    public void quickEditCommand(ForecastRow row) {
        if (row.origin() == Origin.RULE && row.ruleId() != null) {
            quickEdit(interactive(), row.ruleId().value(), row.originalDate());
        } else {
            editRow(row);
        }
    }

    /**
     * Открывает быструю правку суммы события ({@code QUICK_EDIT_POPUP}).
     *
     * @param request      как открыть окно
     * @param ruleId       правило
     * @param originalDate номинальная дата события
     */
    public void quickEdit(OpenRequest request, String ruleId, LocalDate originalDate) {
        if (ruleId == null || ruleId.isBlank() || originalDate == null
                || plan().findRule(new RuleId(ruleId.strip())).isEmpty()) {
            cannotOpen(request, "Быстрая правка недоступна", "Событие правила «" + ruleId + "» не найдено в плане.");
            return;
        }
        // Само всплывающее окно ставит у ячейки главное окно; здесь готовится только состояние и регистрация.
        WindowState state = request.state() != null ? request.state()
                : new WindowState(recorder().nextWindowId(), WindowType.QUICK_EDIT_POPUP,
                WindowType.QUICK_EDIT_POPUP.defaultModal(), request.ownerId(), null,
                Map.of(WindowType.CONTEXT_RULE_ID, ruleId.strip(),
                        WindowType.CONTEXT_ORIGINAL_DATE, DateFormats.iso(originalDate)), Map.of());
        Consumer<StatefulWindow> onShown = request.isRestore() ? request.onShown() : window -> {
            // Открытое пользователем окно регистрирует фасад; восстановленное — координатор восстановления.
            recorder().register(window);
            request.onShown().accept(window);
        };
        Consumer<String> onFailed = request.isRestore() ? request.onFailed()
                : reason -> cannotOpen(request, "Быстрая правка недоступна", reason);
        context().quickEdit().open(state, onShown, onFailed);
    }

    // ------------------------------------------------------------------ команды по идентификатору строки

    /**
     * «Изменить…» (Enter) для строки таблицы по её идентификатору.
     *
     * @param rowId идентификатор строки
     */
    public void editRow(String rowId) {
        findRow(rowId).ifPresentOrElse(this::editRow,
                () -> alerts().info("Ничего не выбрано", "Выберите строку в таблице прогноза."));
    }

    /**
     * «Удалить…» (Delete) для строки таблицы по её идентификатору.
     *
     * @param rowId идентификатор строки
     */
    public void deleteRow(String rowId) {
        findRow(rowId).ifPresentOrElse(this::deleteRow,
                () -> alerts().info("Ничего не выбрано", "Выберите строку регулярной или разовой операции."));
    }

    /**
     * «Скорректировать событие…» (Ctrl+J) для строки таблицы по её идентификатору.
     *
     * @param rowId идентификатор строки
     */
    public void adjustRow(String rowId) {
        Optional<OccurrenceKey> key = findRow(rowId).flatMap(ForecastRow::occurrenceKey);
        if (key.isEmpty()) {
            alerts().info("Выберите событие регулярной операции",
                    "Корректировать можно конкретное событие правила: например, строку «Зарплата» за октябрь.");
            return;
        }
        openAdjustment(interactive(), key.get().ruleId().value(), key.get().originalDate());
    }

    /**
     * «Вернуть как по правилу» для строки таблицы по её идентификатору.
     *
     * @param rowId идентификатор строки
     */
    public void resetRow(String rowId) {
        findRow(rowId).flatMap(ForecastRow::occurrenceKey).ifPresentOrElse(this::resetOccurrence,
                () -> alerts().info("Выберите событие регулярной операции", "Вернуть к правилу можно только событие правила."));
    }

    /**
     * «Пропустить» для строки таблицы по её идентификатору.
     *
     * @param rowId идентификатор строки
     */
    public void skipRow(String rowId) {
        findRow(rowId).flatMap(ForecastRow::occurrenceKey).ifPresent(this::skipOccurrence);
    }

    /**
     * «Пропустить» событие: корректировка «пропустить» без диалога (заметка прежней корректировки сохраняется).
     *
     * @param key событие
     */
    public void skipOccurrence(OccurrenceKey key) {
        edit("Пропуск события " + DateFormats.ru(key.originalDate()),
                p -> p.withAdjustmentPut(new Adjustment(key, new Adjustment.Skip(),
                        p.findAdjustment(key).map(Adjustment::note).orElse(""))));
    }

    /**
     * «Вернуть как по правилу»: удаляет корректировку события; если её нет, сообщает об этом.
     *
     * @param key событие
     */
    public void resetOccurrence(OccurrenceKey key) {
        if (plan().findAdjustment(key).isEmpty()) {
            alerts().info("У события нет корректировки", "Событие " + DateFormats.ru(key.originalDate()) + " и так идёт по правилу.");
            return;
        }
        edit("Возврат события " + DateFormats.ru(key.originalDate()) + " к правилу", p -> p.withAdjustmentRemoved(key));
    }

    /**
     * Удаление регулярной операции с подтверждением (диалог 11).
     *
     * @param id      правило
     * @param request как открыть окно
     */
    public void deleteRule(RuleId id, OpenRequest request) {
        openDeleteConfirmation(request, Purposes.DELETE_RULE, id.value());
    }

    /**
     * Удаление разовой операции с подтверждением (диалог 11).
     *
     * @param id      операция
     * @param request как открыть окно
     */
    public void deleteOneTime(TxId id, OpenRequest request) {
        openDeleteConfirmation(request, Purposes.DELETE_ONE_TIME, id.value());
    }

    /**
     * Применяет сумму из быстрой правки: корректировка «изменить сумму» (или «заменить», если событие уже перенесено).
     *
     * @param key    событие
     * @param amount новая сумма (больше нуля)
     */
    public void applyQuickAmount(OccurrenceKey key, Money amount) {
        edit("Сумма события " + DateFormats.ru(key.originalDate()), p -> {
            Optional<Adjustment> existing = p.findAdjustment(key);
            Optional<LocalDate> movedTo = existing.flatMap(a -> a.action().newDate());
            boolean sameAsRule = p.findRule(key.ruleId()).map(r -> r.amount().equals(amount)).orElse(false);
            if (sameAsRule && movedTo.isEmpty() && existing.map(a -> a.note().isBlank()).orElse(true)) {
                // Сумма вернулась к правилу: корректировка больше не нужна.
                return p.withAdjustmentRemoved(key);
            }
            Adjustment.Action action = movedTo.<Adjustment.Action>map(date -> new Adjustment.Replace(amount, date))
                    .orElseGet(() -> new Adjustment.ChangeAmount(amount));
            return p.withAdjustmentPut(new Adjustment(key, action, existing.map(Adjustment::note).orElse("")));
        });
    }

    // ------------------------------------------------------------------ проверки ввода

    /**
     * Проверка суммы в поле ввода.
     *
     * @param text текст
     * @return ошибка или {@code null}
     */
    static String moneyError(String text) {
        if (text == null || text.isBlank()) {
            return "Укажите сумму";
        }
        try {
            Money.parse(text);
            return null;
        } catch (IllegalArgumentException e) {
            return "Некорректная сумма";
        }
    }

    private static String monthsError(String text) {
        try {
            int months = Integer.parseInt(text == null ? "" : text.strip());
            return months >= 1 && months <= Horizon.MAX_MONTHS ? null : "Число месяцев должно быть от 1 до " + Horizon.MAX_MONTHS;
        } catch (NumberFormatException e) {
            return "Введите целое число месяцев";
        }
    }

    /**
     * Строки плана, на которые могут ссылаться окна (для проверок в восстановлении).
     *
     * @return идентификаторы правил и разовых операций
     */
    public List<String> targetIds() {
        Plan plan = plan();
        return java.util.stream.Stream.concat(plan.rules().stream().map(r -> r.id().value()),
                plan.oneTimes().stream().map(t -> t.id().value())).toList();
    }
}
