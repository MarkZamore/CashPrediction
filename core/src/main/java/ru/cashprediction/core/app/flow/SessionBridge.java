package ru.cashprediction.core.app.flow;

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.nio.file.Path;
import java.nio.file.InvalidPathException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.MainGeometry;
import ru.cashprediction.core.app.RevealMode;
import ru.cashprediction.core.app.FocusTarget;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.PeriodChoice;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.diagnostics.Severity;
import ru.cashprediction.core.service.storage.FilePlanStorage;
import ru.cashprediction.core.service.storage.PlanStorage;
import ru.cashprediction.core.markdown.PlanMarkdownReader;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.markdown.ReadResult;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.RestoreTarget;
import ru.cashprediction.core.session.SnapshotSource;

/**
 * Мост между контроллером и записью/восстановлением сеанса (архитектура §3.8).
 *
 * <p><b>{@link #captureMain()}:</b> границы и развёрнутость — {@code port.mainGeometry()}; вид ({@code TABLE}/
 * {@code CHART}); период; флажки вида; дополнительные ключи {@code filters}: {@code pastExpanded},
 * {@code whatIfIncome}, {@code whatIfExpense}; текст фильтра; выделение; {@code whatIfExtra} (каноническая сумма или
 * пусто). <b>{@link #capturePlan()}:</b> несохранённый план — markdown; сохранённый без изменений —
 * {@code PlanState.CLEAN}; после «Не сохранять» при выходе — {@code CLEAN} (решение L3).
 * <b>{@link #applyMain(MainWindowState)}</b> восстанавливает всё перечисленное; старые снимки без новых ключей —
 * значения по умолчанию.</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class SessionBridge implements SnapshotSource, RestoreTarget {

    private final FlowContext context;
    /** Состояние передаётся порту только после загрузки плана и применения вида. */
    private MainWindowState restoredMain;

    /**
     * Создаёт мост.
     *
     * @param context контекст контроллера
     */
    public SessionBridge(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /** {@inheritDoc} Геометрия читается у клиента в момент захвата. */
    @Override
    public MainWindowState captureMain() {
        AppState app = context.state();
        ViewState view = app.view();
        MainGeometry geometry = context.port().mainGeometry();
        Map<String, Boolean> flags = new LinkedHashMap<>();
        flags.put("showIncome", view.showIncome());
        flags.put("showExpense", view.showExpense());
        flags.put("showOneTime", view.showOneTime());
        flags.put("showSkipped", view.showSkipped());
        flags.put("monthTotals", view.monthTotals());
        flags.put("chartMarkers", view.chartMarkers());
        flags.put("chartBars", view.chartBars());
        flags.put("summaryPanel", view.summaryPanel());
        flags.put(MainWindowState.FILTER_PAST_EXPANDED, app.pastExpanded());
        flags.put(MainWindowState.FILTER_WHAT_IF_INCOME,
                view.whatIf().incomeFactor().compareTo(BigDecimal.ONE) != 0);
        flags.put(MainWindowState.FILTER_WHAT_IF_EXPENSE,
                view.whatIf().expenseFactor().compareTo(BigDecimal.ONE) != 0);
        String path = context.document().file().map(file -> {
            Path absolute = file.toAbsolutePath().normalize();
            Path memory = context.environment().cashMemory();
            return absolute.startsWith(memory) ? memory.relativize(absolute).toString() : absolute.toString();
        }).orElse("");
        Money extra = view.whatIf().extraMonthlySaving();
        return new MainWindowState(geometry.bounds(), geometry.maximized(), view.mode().name(), path,
                view.period().name(), flags, view.filterText(), app.selectedRowId(),
                extra.isZero() ? "" : extra.formatPlain());
    }

    /** {@inheritDoc} План без файла защищается снимком даже при отсутствии правок. */
    @Override
    public PlanState capturePlan() {
        if (context.exit().cleanExitSnapshot()) return PlanState.CLEAN;
        return context.document().isDirty() || context.document().file().isEmpty()
                ? PlanState.dirty(PlanMarkdownWriter.write(context.document().plan())) : PlanState.CLEAN;
    }

    /** {@inheritDoc} Ошибка разбора несохранённого текста не заменяет документ пустым планом. */
    @Override
    public void loadPlan(PlanState plan, String planPath, Consumer<String> warn) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(warn, "warn");
        Path file = null;
        if (planPath != null && !planPath.isBlank()) {
            try {
                file = context.environment().cashMemory().resolve(planPath).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                warn.accept(UiText.get("restore.warn.badPath", planPath));
            }
        }
        if (plan.dirty()) {
            String fallback = file == null ? UiText.get("plan.defaultName")
                    : PlanMarkdownReader.nameWithoutExtension(file);
            ReadResult result = PlanMarkdownReader.read(plan.markdown(), fallback, context.environment().clock().today());
            replace(result, file, true);
            if (result.hasWarnings()) warn.accept(UiText.get("restore.warn.dirtyPlanDiag"));
            return;
        }
        if (file != null) {
            PlanStorage.Reference reference = FilePlanStorage.reference(file);
            var read = context.planStorage().read(reference, context.environment().clock().today());
            if (read.succeeded()) {
                var snapshot = read.value();
                context.document().replace(snapshot.plan(), file, false, snapshot.diagnostics());
                // Запоминается версия прочитанного снимка, а не возможной следующей внешней правки.
                context.externalChanges().remember(snapshot.reference(), snapshot.version());
                if (snapshot.diagnostics().stream().anyMatch(value -> value.severity() != Severity.INFO)) {
                    warn.accept(UiText.get("restore.warn.planDiag", file));
                }
                return;
            }
            warnStorageProblem(read.problem(), file, warn);
            warn.accept(UiText.get("restore.warn.emptyPlan", file));
        }
        context.document().replace(Plan.empty(UiText.get("plan.defaultName"),
                context.environment().clock().today()), null, false, List.of());
        context.externalChanges().forget();
    }

    /** Сохраняет прежние предупреждения чистого снимка, добавляя локализованную причину конфликта версии. */
    private void warnStorageProblem(PlanStorage.Problem problem, Path file, Consumer<String> warn) {
        String detail = problem.detail();
        if (detail.isBlank()) {
            detail = problem.code() == PlanStorage.Code.CONFLICT
                    ? UiText.get("alert.external.header", PlanMarkdownReader.nameWithoutExtension(file))
                    : UiText.get("err.generic");
        }
        switch (problem.code()) {
            case MISSING -> warn.accept(UiText.get("restore.warn.planMissing", file));
            case CORRUPT -> warn.accept(UiText.get("restore.warn.notPlan", file, detail));
            case CONFLICT, IO_ERROR -> warn.accept(UiText.get("restore.warn.readPlan", file, detail));
        }
    }

    /** Устанавливает план из сырого текста снимка; у хранилища только наблюдается версия, без загрузки плана и записи. */
    private void replace(ReadResult result, Path file, boolean dirty) {
        context.document().replace(result.plan(), file, dirty, result.diagnostics());
        context.externalChanges().remember(file);
    }

    /** {@inheritDoc} Новые флажки старого снимка получают значения по умолчанию. */
    @Override
    public void applyMain(MainWindowState main) {
        Objects.requireNonNull(main, "main");
        context.updateView(view -> new ViewState(mode(main.view(), view.mode()),
                PeriodChoice.parse(main.period()).orElse(view.period()),
                main.filter("showIncome", view.showIncome()), main.filter("showExpense", view.showExpense()),
                main.filter("showOneTime", view.showOneTime()), main.filter("showSkipped", view.showSkipped()),
                main.filter("monthTotals", view.monthTotals()), main.filter("chartMarkers", view.chartMarkers()),
                main.filter("chartBars", view.chartBars()), main.filter("summaryPanel", view.summaryPanel()),
                main.filterText(), WhatIf.ofPercent(main.filter(MainWindowState.FILTER_WHAT_IF_INCOME, false) ? -10 : 0,
                        main.filter(MainWindowState.FILTER_WHAT_IF_EXPENSE, false) ? 10 : 0,
                        main.whatIfExtra().isBlank() ? Money.ZERO : Money.parse(main.whatIfExtra()))));
        context.setPastExpanded(main.filter(MainWindowState.FILTER_PAST_EXPANDED, false));
        restoredMain = main;
    }

    /** Незнакомый режим снимка не меняет текущую настройку. */
    private static ViewMode mode(String name, ViewMode fallback) {
        try { return ViewMode.valueOf(name); }
        catch (IllegalArgumentException e) { return fallback; }
    }

    /** {@inheritDoc} Модель и признак показа принадлежат контроллеру. */
    @Override
    public void showMainWindow() {
        context.showMain(restoredMain);
    }

    /** {@inheritDoc} Выделение запрашивается после восстановления окон. */
    @Override
    public void selectRow(String rowId) {
        context.setSelection(rowId);
        if (rowId != null && !rowId.isEmpty()) {
            context.port().revealRow(rowId, RevealMode.SELECT_AND_SCROLL);
            context.port().focus(FocusTarget.TABLE);
        }
    }

    /** {@inheritDoc} Возвращает цели из текущего документа, включая отключённые правила. */
    @Override
    public Set<String> existingTargetIds() {
        Set<String> ids = new LinkedHashSet<>();
        context.document().plan().rules().forEach(rule -> ids.add(rule.id().value()));
        context.document().plan().oneTimes().forEach(tx -> ids.add(tx.id().value()));
        return Set.copyOf(ids);
    }
}
