package ru.cashprediction.core.app.flow;

import java.util.Objects;
import java.util.function.Consumer;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowFactory;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.app.Placement;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.text.UiText;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import ru.cashprediction.core.ui.forms.simple.ConfirmForms;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.app.FileChooserSpec;
import ru.cashprediction.core.service.plan.PlanCommand;
import ru.cashprediction.core.service.plan.PlanCommandRequest;
import java.util.UUID;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.*;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.io.AtomicFiles;
import ru.cashprediction.core.export.CsvExporter;
import ru.cashprediction.core.export.CsvOptions;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.forms.ops.AdjustmentForm;
import ru.cashprediction.core.ui.forms.plan.NewPlanWizardForm;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import ru.cashprediction.core.ui.forms.simple.CsvExportForm;
import ru.cashprediction.core.ui.forms.simple.ChoiceForms;
import ru.cashprediction.core.ui.forms.simple.OpenPlanForm;
import ru.cashprediction.core.ui.forms.simple.TextInputForms;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.view.status.StatusLevel;

/**
 * Открытие восстановленных окон тем же путём, что и из меню (архитектура §3.8).
 *
 * <p><b>Порядок:</b> 1) {@code FormCatalog.forRestore(state, app, commands, storage)} строит {@link FormRequest} (с
 * {@code restored = state}) или сообщает {@code restore.warn.*} через {@code onFailed}; 2)
 * {@code context.openForm(request, Placement.restored(ownerId, state.bounds()), onResult)} — тот же путь, что у меню:
 * он сам вызывает {@code FormSession.applyState(state)} и {@code port.openForm}; 3) {@code onShown} — когда клиент
 * вызвал {@code FormSession.shown()}. Восстанавливаемые сообщения (deleteRule, …) — {@code ConfirmForms} и
 * {@code context.showRestoredAlert}: контроллер сохраняет идентификаторы и сообщает о настоящем показе.</p>
 *
 * <p>Не потокобезопасен: только поток контроллера.</p>
 */
public final class CoreWindowFactory implements WindowFactory {

    private final FlowContext context;

    /**
     * Создаёт фабрику.
     *
     * @param context контекст контроллера
     */
    public CoreWindowFactory(FlowContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    /** @return контекст контроллера */
    public FlowContext context() {
        return context;
    }

    /** {@inheritDoc} Каталог и контекст сохраняют исходные поля, владельца и положение. */
    @Override
    public void open(WindowState state, String ownerId, Consumer<StatefulWindow> onShown, Consumer<String> onFailed) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(onShown, "onShown");
        Objects.requireNonNull(onFailed, "onFailed");
        WindowState prepared = state.withIds(state.id(), ownerId);
        FormRequest request;
        try {
            request = FormCatalog.forRestore(prepared, context.state(), context.planCommands(), context.planStorage());
        } catch (RuntimeException e) {
            onFailed.accept(reason(e));
            return;
        }
        if (prepared.type() == WindowType.ALERT) {
            openAlert(prepared, ((FormCatalog.ConfirmationLogic) request.logic()).confirmation, onShown, onFailed);
            return;
        }
        FormSession session;
        long[] expectedRevision = {context.planCommands().snapshot().revision()};
        try {
            // JavaFX: Dialog → Swing: JDialog → Web: dialog
            session = context.openForm(request, Placement.restored(prepared.ownerId(), prepared.bounds()),
                    result -> {
                        try {
                            context.edits().formResult(expectedRevision[0], () -> applyResult(prepared, result, expectedRevision[0]));
                        } finally {
                            expectedRevision[0] = context.planCommands().snapshot().revision();
                        }
                    });
        } catch (RuntimeException e) {
            onFailed.accept(reason(e));
            return;
        }
        // Подписка работает и после раннего shown внутри openForm; ручка окна не является сигналом показа.
        // Колбэк координатора вызывается вне catch: исключение его продолжения не становится вторым ответом.
        session.whenShown(onShown::accept);
    }

    /** Открывает восстановленное подтверждение, независимо защищая сигнал показа и ответ от повторов. */
    private void openAlert(WindowState state, ConfirmForms.Confirmation confirmation,
                           Consumer<StatefulWindow> onShown, Consumer<String> onFailed) {
        AtomicBoolean reported = new AtomicBoolean();
        AtomicBoolean answered = new AtomicBoolean();
        WhatIf whatIf = context.state().view().whatIf();
        var today = context.state().today();
        long expectedRevision = context.planCommands().snapshot().revision();
        try {
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog
            context.showRestoredAlert(confirmation.spec(), state, window -> {
                if (reported.compareAndSet(false, true)) onShown.accept(window);
            }, button -> {
                if (!answered.compareAndSet(false, true) || !confirmation.confirmButtonId().equals(button)) return;
                switch (confirmation.spec().purpose()) {
                    case "deleteRule" -> {
                        RuleId id = new RuleId(confirmation.spec().targetId());
                        context.document().plan().findRule(id).ifPresent(rule -> {
                            if (context.edits().execute(request(expectedRevision, UiText.get("undo.ruleDelete", rule.title()),
                                    new PlanCommand.RemoveRule(id)), "").changed())
                                context.status(StatusLevel.INFO, "status.msg.ruleDeleted", rule.title());
                        });
                    }
                    case "deleteOneTime" -> {
                        TxId id = new TxId(confirmation.spec().targetId());
                        context.document().plan().findOneTime(id).ifPresent(tx -> {
                            if (context.edits().execute(request(expectedRevision, UiText.get("undo.oneTimeDelete", tx.title()),
                                    new PlanCommand.RemoveOneTime(id)), "").changed())
                                context.status(StatusLevel.INFO, "status.msg.oneTimeDeleted", tx.title());
                        });
                    }
                    case "actualize" -> context.edits().execute(request(expectedRevision,
                            Texts.get("document.edit.actualize", UiFormats.date(today)), new PlanCommand.Actualize(today, null)),
                            "status.msg.actualized");
                    case "applyWhatIf" -> {
                        if (whatIf.isNone()) return; // Старый снимок не позволяет восстановить отсутствующие коэффициенты.
                        var applied = context.edits().execute(request(expectedRevision,
                                Texts.get("document.edit.applyWhatIf"), new PlanCommand.ApplyWhatIf(whatIf, today)), "");
                        // Равный план не создаёт историю, но успешно применённый сценарий всё равно выключается.
                        if (applied.accepted()) {
                            context.updateView(view -> view.withWhatIf(WhatIf.NONE));
                            context.status(StatusLevel.INFO, "status.msg.whatIfApplied");
                        }
                    }
                    case "clearSnapshots" -> {
                        if (context.recorder() != null && context.recorder().isEnabled()) {
                            context.recorder().clearSnapshots();
                            context.status(StatusLevel.INFO, "status.msg.snapshotsCleared");
                        }
                    }
                    default -> throw new IllegalArgumentException(UiText.get("restore.warn.unknownPurpose", confirmation.spec().purpose()));
                }
            });
        } catch (RuntimeException e) {
            // Ошибка продолжения после shown не означает, что окно не открылось; второй callback запрещён.
            if (reported.compareAndSet(false, true)) onFailed.accept(reason(e));
            else throw e;
        }
    }

    /** Возвращает причину ошибки открытия, не подменяя исключения callbacks. */
    private static String reason(RuntimeException e) {
        return Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
    }

    /** Применяет результат восстановленного редактора; отмена ничего не меняет. */
    private void applyResult(WindowState state, Object value, long expectedRevision) {
        if (value == null) return;
        switch (state.type()) {
            case RULE_EDITOR -> {
                RecurringRule rule = (RecurringRule) value;
                boolean edit = WindowType.MODE_EDIT.equals(state.contextValue(WindowType.CONTEXT_MODE));
                context.edits().edit(UiText.get(edit ? "undo.ruleEdit" : "undo.ruleAdd", rule.title()),
                        edit ? "status.msg.ruleChanged" : "status.msg.ruleAdded",
                        edit ? new PlanCommand.ReplaceRule(rule) : new PlanCommand.AddRule(rule));
            }
            case ONE_TIME_EDITOR -> {
                OneTimeTransaction tx = (OneTimeTransaction) value;
                boolean edit = WindowType.MODE_EDIT.equals(state.contextValue(WindowType.CONTEXT_MODE));
                context.edits().edit(UiText.get(edit ? "undo.oneTimeEdit" : "undo.oneTimeAdd", tx.title()),
                        edit ? "status.msg.oneTimeChanged" : "status.msg.oneTimeAdded",
                        edit ? new PlanCommand.ReplaceOneTime(tx) : new PlanCommand.AddOneTime(tx));
            }
            case ADJUSTMENT_EDITOR, QUICK_EDIT_POPUP -> {
                AdjustmentForm.Result result = (AdjustmentForm.Result) value;
                boolean quick = state.type() == WindowType.QUICK_EDIT_POPUP;
                String title = context.document().plan().findRule(result.key().ruleId()).orElseThrow(() ->
                        new IllegalArgumentException(UiText.get("err.notFound.content", result.key().ruleId()))).title();
                String date = UiFormats.date(result.key().originalDate());
                String undo = quick ? UiText.get("undo.quickAmount", date)
                        : UiText.get(result.adjustment() == null ? "undo.adjustReset" : "undo.adjust", title, date);
                context.edits().edit(undo, quick ? "status.msg.quickAmount"
                                : result.adjustment() == null ? "status.msg.adjustReset" : "status.msg.adjustSaved",
                        result.adjustment() == null ? new PlanCommand.RemoveAdjustment(result.key())
                                : new PlanCommand.PutAdjustment(result.adjustment()));
            }
            case PLAN_SETTINGS -> {
                Plan changed = (Plan) value;
                context.edits().edit(UiText.get("undo.planSettings"), "status.msg.settings",
                        new PlanCommand.UpdateSettings(PlanCommand.Settings.from(changed)));
            }
            case GOAL_CALCULATOR -> {
                if (value instanceof GoalCalculatorForm.SaveGoal goal)
                    context.edits().edit(UiText.get("undo.goal", goal.goal().title()), "status.msg.goalSaved", new PlanCommand.SetGoal(goal.goal()));
                else if (value instanceof GoalCalculatorForm.AddWhatIfExtra extra)
                    context.updateView(view -> view.withWhatIf(view.whatIf().withExtraMonthlySaving(
                            view.whatIf().extraMonthlySaving().plus(extra.amount()))));
            }
            case NEW_PLAN_WIZARD -> {
                if (value instanceof NewPlanWizardForm.OpenSample) context.files().openSample();
                else if (value instanceof NewPlanWizardForm.Created created) {
                    context.files().confirmDiscard(() -> {
                        context.document().replace(created.plan(), null, true, List.of());
                        context.externalChanges().forget();
                        context.setSelection("");
                        context.setPastExpanded(false);
                        context.refresh();
                        context.files().save(() -> context.status(StatusLevel.SUCCESS, "status.msg.created", created.plan().name()));
                    });
                }
            }
            case TEXT_INPUT -> textResult(state.contextValue(WindowType.CONTEXT_PURPOSE), value, expectedRevision);
            case CHOICE -> {
                if (OpenPlanForm.PURPOSE.equals(state.contextValue(WindowType.CONTEXT_PURPOSE))) {
                    if (OpenPlanForm.FROM_FILE.equals(value)) context.files().openFile();
                    else context.files().openRecent(((Path) value).toString());
                } else if (ChoiceForms.CUSTOM.equals(value)) {
                    long[] childRevision = {context.planCommands().snapshot().revision()};
                    // JavaFX: TextInputDialog → Swing: JDialog → Web: dialog
                    context.openForm(FormRequest.fresh(TextInputForms.customCurrency(), WindowType.TEXT_INPUT, true,
                            Map.of(WindowType.CONTEXT_PURPOSE, TextInputForms.PURPOSE_CUSTOM_CURRENCY)), null,
                            result -> {
                                try {
                                    context.edits().formResult(childRevision[0], () -> {
                                        if (result != null) textResult(TextInputForms.PURPOSE_CUSTOM_CURRENCY, result, childRevision[0]);
                                    });
                                } finally { childRevision[0] = context.planCommands().snapshot().revision(); }
                            });
                } else textResult(TextInputForms.PURPOSE_CUSTOM_CURRENCY, value, expectedRevision);
            }
            case CSV_EXPORT -> exportCsv((CsvExportForm.Choice) value);
            case ALERT -> throw new IllegalStateException("ALERT result uses onButton");
        }
    }

    /** Применяет назначения однополевых форм, не открывая второй редактор. */
    private void textResult(String purpose, Object value, long expectedRevision) {
        switch (purpose) {
            case TextInputForms.PURPOSE_CUSTOM_CURRENCY -> context.edits().edit(UiText.get("undo.currency", value), "",
                    new PlanCommand.SetCurrency((String) value));
            case TextInputForms.PURPOSE_CUSTOM_MONTHS -> context.views().changeMonths((Integer) value);
            case TextInputForms.PURPOSE_RECONCILE -> {
                var today = context.state().today();
                var reconciled = context.edits().execute(request(expectedRevision, Texts.get("document.reconcile.title"),
                        new PlanCommand.Reconcile(today, (Money) value)), "");
                if (reconciled.changed())
                    context.status(StatusLevel.INFO, "status.msg.reconciled",
                            reconciled.effect().reconciliationDifference().formatSigned() + " " + context.document().plan().currency());
            }
            case TextInputForms.PURPOSE_RENAME -> context.files().renameTo((String) value);
            default -> throw new IllegalArgumentException(UiText.get("restore.warn.unknownPurpose", purpose));
        }
    }

    /** Формирует команду по ревизии, зафиксированной до открытия восстановленного окна. */
    private static PlanCommandRequest request(long revision, String description, PlanCommand command) {
        return new PlanCommandRequest(UUID.randomUUID(), revision, description, command);
    }

    /** Продолжает экспорт из уже выбранных настроек CSV. */
    private void exportCsv(CsvExportForm.Choice choice) {
        var app = context.state();
        var forecast = context.document().forecast();
        CsvOptions options = new CsvOptions("TAB".equals(choice.separator()) ? '\t' : choice.separator().charAt(0),
                choice.bom(), "PERIOD".equals(choice.range()) ? app.today() : null,
                "PERIOD".equals(choice.range()) ? app.view().periodEnd(app.document().plan(), forecast.anchor()) : null);
        String csv = CsvExporter.toCsv(forecast, options);
        // JavaFX: FileChooser → Swing: JFileChooser → Web: dialog
        context.choosers().chooseFile(new FileChooserSpec(FileChooserSpec.Purpose.EXPORT_CSV, FileChooserSpec.Mode.SAVE,
                UiText.get("s2.capture.csvTitle"), UiText.get("s2.capture.csvFilter"), List.of("csv"),
                app.cashMemory(), PlanRepository.fileBaseName(app.document().plan().name()) + ".csv"), result -> {
            if (result.isEmpty()) return;
            try {
                AtomicFiles.writeString(result.get(), csv);
                context.status(StatusLevel.SUCCESS, "status.msg.csv", result.get());
            } catch (IOException | RuntimeException e) {
                // JavaFX: Alert → Swing: JOptionPane → Web: dialog
                context.showAlert(AlertCatalog.error("csv", e), null);
            }
        });
    }
}
