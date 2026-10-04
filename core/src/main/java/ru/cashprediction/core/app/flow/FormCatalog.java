package ru.cashprediction.core.app.flow;

import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.session.WindowState;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.nio.file.attribute.FileTime;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.io.PlanFileInfo;
import ru.cashprediction.core.service.plan.PlanCommand;
import ru.cashprediction.core.service.plan.PlanCommandRequest;
import ru.cashprediction.core.service.plan.PlanCommandResult;
import ru.cashprediction.core.service.plan.PlanCommands;
import ru.cashprediction.core.service.storage.FilePlanStorage;
import ru.cashprediction.core.service.storage.PlanStorage;
import ru.cashprediction.core.text.Texts;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.forms.ops.AdjustmentForm;
import ru.cashprediction.core.ui.forms.ops.OneTimeForm;
import ru.cashprediction.core.ui.forms.ops.QuickEditForm;
import ru.cashprediction.core.ui.forms.ops.RuleEditorForm;
import ru.cashprediction.core.ui.forms.plan.GoalCalculatorForm;
import ru.cashprediction.core.ui.forms.plan.NewPlanWizardForm;
import ru.cashprediction.core.ui.forms.plan.PlanSettingsForm;
import ru.cashprediction.core.ui.forms.simple.ChoiceForms;
import ru.cashprediction.core.ui.forms.simple.CsvExportForm;
import ru.cashprediction.core.ui.forms.simple.OpenPlanForm;
import ru.cashprediction.core.ui.forms.simple.TextInputForms;
import ru.cashprediction.core.ui.forms.simple.ConfirmForms;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.form.*;
import java.util.List;
import java.util.Map;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.table.LazyTableModel;

/**
 * Какая форма соответствует окну снимка (архитектура §3.8).
 *
 * <table>
 *   <caption>Тип окна → форма</caption>
 *   <tr><th>WindowType</th><th>Форма</th></tr>
 *   <tr><td>NEW_PLAN_WIZARD</td><td>{@code NewPlanWizardForm}</td></tr>
 *   <tr><td>PLAN_SETTINGS</td><td>{@code PlanSettingsForm}</td></tr>
 *   <tr><td>RULE_EDITOR</td><td>{@code RuleEditorForm} (edit: правило должно существовать)</td></tr>
 *   <tr><td>ONE_TIME_EDITOR</td><td>{@code OneTimeForm}</td></tr>
 *   <tr><td>ADJUSTMENT_EDITOR</td><td>{@code AdjustmentForm} (нужны ruleId и originalDate, иначе {@code restore.warn.noContext})</td></tr>
 *   <tr><td>GOAL_CALCULATOR</td><td>{@code GoalCalculatorForm}</td></tr>
 *   <tr><td>TEXT_INPUT</td><td>{@code FileFlow.RenameForm}, {@code ViewFlow.CustomMonths} или {@code TextInputForms.forPurpose}
 *       (неизвестное — {@code restore.warn.unknownPurpose})</td></tr>
 *   <tr><td>CHOICE</td><td>{@code ChoiceForms.currency} / {@code OpenPlanForm}</td></tr>
 *   <tr><td>ALERT</td><td>{@code ConfirmForms} по назначению (нет цели — {@code restore.warn.targetGone})</td></tr>
 *   <tr><td>CSV_EXPORT</td><td>{@code CsvExportForm}</td></tr>
 *   <tr><td>QUICK_EDIT_POPUP</td><td>{@code QuickEditForm} (строки нет в таблице — {@code restore.warn.rowHidden})</td></tr>
 * </table>
 *
 * <p>Класс не хранит состояния. Службы принадлежат контроллеру; вызов выполняется в его потоке.
 * Предпросмотр использует службу прогноза владельца команд и не создаёт другой документ.</p>
 */
public final class FormCatalog {

    private FormCatalog() {
    }

    /**
     * Форма для восстановления окна.
     *
     * @param state окно из снимка
     * @param app   состояние приложения после загрузки плана
     * @param commands общая служба команд текущего документа, включая внедрённый расчёт прогноза
     * @param storage общая служба сохранённых планов
     * @return запрос открытия
     * @throws IllegalArgumentException если окно восстановить нельзя; сообщение берётся из общего каталога
     */
    public static FormRequest forRestore(WindowState state, AppState app, PlanCommands commands, PlanStorage storage) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(app, "app");
        Objects.requireNonNull(commands, "commands");
        Objects.requireNonNull(storage, "storage");
        if (state.type() == null) throw new IllegalArgumentException("state.type");
        FormLogic logic = switch (state.type()) {
            // JavaFX: Dialog → Swing: JDialog → Web: dialog
            case NEW_PLAN_WIZARD -> new NewPlanWizardForm();
            case PLAN_SETTINGS -> new PlanSettingsForm();
            case RULE_EDITOR -> {
                if (WindowType.MODE_EDIT.equals(state.contextValue(WindowType.CONTEXT_MODE))
                        && app.document().plan().findRule(new RuleId(state.contextValue(WindowType.CONTEXT_RULE_ID))).isEmpty())
                    throw failure("restore.warn.targetGone");
                yield new RuleEditorForm();
            }
            case ONE_TIME_EDITOR -> {
                if (WindowType.MODE_EDIT.equals(state.contextValue(WindowType.CONTEXT_MODE))
                        && app.document().plan().findOneTime(new TxId(state.contextValue(WindowType.CONTEXT_TX_ID))).isEmpty())
                    throw failure("restore.warn.targetGone");
                yield new OneTimeForm();
            }
            case ADJUSTMENT_EDITOR -> {
                requireOccurrence(state);
                yield new AdjustmentForm();
            }
            case QUICK_EDIT_POPUP -> {
                LocalDate date = requireOccurrence(state);
                String rule = state.contextValue(WindowType.CONTEXT_RULE_ID);
                if (LazyTableModel.build(app, app.revision()).indexOf(rule + "@" + date) < 0)
                    throw failure("restore.warn.rowHidden", rule, ru.cashprediction.core.ui.text.UiFormats.date(date));
                // JavaFX: Popup → Swing: JWindow → Web: div.quick-edit
                yield new QuickEditForm();
            }
            case GOAL_CALCULATOR -> new GoalCalculatorForm();
            case TEXT_INPUT -> {
                String purpose = state.contextValue(WindowType.CONTEXT_PURPOSE);
                if (!Set.of(TextInputForms.PURPOSE_RENAME, TextInputForms.PURPOSE_RECONCILE,
                        TextInputForms.PURPOSE_CUSTOM_MONTHS, TextInputForms.PURPOSE_CUSTOM_CURRENCY).contains(purpose))
                    throw failure("restore.warn.unknownPurpose", purpose);
                // JavaFX: TextInputDialog → Swing: JDialog → Web: dialog
                yield switch (purpose) {
                    case TextInputForms.PURPOSE_CUSTOM_MONTHS -> new ViewFlow.CustomMonths();
                    case TextInputForms.PURPOSE_RENAME -> new FileFlow.RenameForm();
                    default -> TextInputForms.forPurpose(purpose);
                };
            }
            case CHOICE -> {
                String purpose = state.contextValue(WindowType.CONTEXT_PURPOSE);
                // JavaFX: ChoiceDialog → Swing: JDialog → Web: dialog
                if (ChoiceForms.PURPOSE_CURRENCY.equals(purpose)) yield ChoiceForms.currency();
                if (OpenPlanForm.PURPOSE.equals(purpose)) yield openPlanChoice(app, storage);
                throw failure("restore.warn.unknownPurpose", purpose);
            }
            case CSV_EXPORT -> new CsvExportForm();
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog
            case ALERT -> new ConfirmationLogic(confirmation(state, app, commands));
        };
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        state.fields().forEach((id, value) -> fields.put(id,
                ru.cashprediction.core.ui.form.FieldCodec.acceptLegacy(state.type(), id, value)));
        return new FormRequest(logic, state.type(), state.modal(), state.context(), state.withFields(fields));
    }

    /** Пересоздаёт восстанавливаемое подтверждение из назначения и текущего плана. */
    private static ConfirmForms.Confirmation confirmation(WindowState state, AppState app, PlanCommands commands) {
        String purpose = state.contextValue(WindowType.CONTEXT_PURPOSE);
        String target = state.contextValue(WindowType.CONTEXT_TARGET_ID);
        return switch (purpose) {
            case "deleteRule" -> {
                if (target.isBlank()) throw failure("restore.warn.targetGone");
                yield ConfirmForms.deleteRule(app, target).orElseThrow(() -> failure("restore.warn.targetGone"));
            }
            case "deleteOneTime" -> {
                if (target.isBlank()) throw failure("restore.warn.targetGone");
                yield ConfirmForms.deleteOneTime(app, target).orElseThrow(() -> failure("restore.warn.targetGone"));
            }
            case "actualize" -> {
                if (!app.document().forecastAvailable() || !app.today().isAfter(app.document().plan().startDate()))
                    throw failure("s2.capture.actualizeUnavailable");
                PlanCommandRequest request;
                PlanCommandResult preview;
                try {
                    request = new PlanCommandRequest(UUID.randomUUID(), commands.snapshot().revision(),
                            Texts.get("document.edit.actualize", UiFormats.date(app.today())), new PlanCommand.Actualize(app.today(), null));
                    preview = commands.preview(request);
                } catch (RuntimeException error) {
                    throw new IllegalArgumentException(UiText.get("s2.capture.actualizeUnavailable"), error);
                }
                if (preview == null || preview.status() != PlanCommandResult.Status.PREVIEW
                        || !request.requestId().equals(preview.requestId())
                        || preview.snapshot().revision() != request.expectedRevision()) {
                    throw failure("s2.capture.actualizeUnavailable");
                }
                // JavaFX: Alert → Swing: JOptionPane → Web: dialog.
                // Подтверждение хранит только описание; фабрика выполняет типизированную команду после ответа.
                yield new ConfirmForms.Confirmation(AlertCatalog.actualize(app.today(), preview.snapshot().plan().startBalance(),
                        app.document().plan().currency()), "actualize", "document.edit.actualize", null, "status.msg.actualized");
            }
            case "applyWhatIf" -> whatIfConfirmation(app);
            case "clearSnapshots" -> ConfirmForms.clearSnapshots();
            default -> throw failure("restore.warn.unknownPurpose", purpose);
        };
    }

    /** Преобразует непрозрачные ссылки только для прежней файловой формы; порядок и метаданные задаёт хранение. */
    private static OpenPlanForm openPlanChoice(AppState app, PlanStorage storage) {
        List<PlanFileInfo> plans;
        try {
            plans = storage.list(FilePlanStorage.collection(app.plansFolder())).requireValue().stream()
                    .map(entry -> new PlanFileInfo(entry.name(), FilePlanStorage.path(entry.reference()),
                            FileTime.from(entry.modifiedAt()))).toList();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(Texts.get("io.error.readPlansFolder", app.plansFolder()), error);
        }
        // JavaFX: ChoiceDialog → Swing: JDialog → Web: dialog.
        return new OpenPlanForm(plans);
    }

    /** Использует те же подписи и порядок частей «что-если», что и поток инструментов. */
    private static ConfirmForms.Confirmation whatIfConfirmation(AppState app) {
        var value = app.view().whatIf();
        List<String> parts = new java.util.ArrayList<>();
        if (value.incomeFactor().compareTo(java.math.BigDecimal.ONE) != 0)
            parts.add(UiText.get("s2.edit.whatIf.income", value.incomeFactor().toPlainString().replace('.', ',')));
        if (value.expenseFactor().compareTo(java.math.BigDecimal.ONE) != 0)
            parts.add(UiText.get("s2.edit.whatIf.expense", value.expenseFactor().toPlainString().replace('.', ',')));
        if (value.extraMonthlySaving().isPositive())
            parts.add(UiText.get("s2.edit.whatIf.extra", value.extraMonthlySaving().format(app.document().plan().currency())));
        var base = AlertCatalog.applyWhatIf(parts);
        var spec = new ru.cashprediction.core.ui.alert.AlertSpec(base.kind(), base.purpose(), base.targetId(),
                UiText.get("s2.edit.whatIf.title"), base.glyph(), UiText.get("s2.edit.whatIf.header"),
                UiText.get("s2.edit.whatIf.content", String.join("; ", parts)), base.details(), base.detailsExpanded(),
                base.minWidth(), base.buttons(), base.defaultButtonId(), base.restorable());
        // Старые снимки не хранили «что-если»: окно существует, но отсутствующие параметры не выдумываются.
        return new ConfirmForms.Confirmation(spec, "apply", "document.edit.applyWhatIf", plan -> plan, "status.msg.whatIfApplied");
    }

    /** Адаптер назначения подтверждения для контракта каталога; фабрика показывает его как AlertSession. */
    static final class ConfirmationLogic implements FormLogic {
        final ConfirmForms.Confirmation confirmation;

        /** Запоминает пересозданное подтверждение. */
        ConfirmationLogic(ConfirmForms.Confirmation confirmation) { this.confirmation = confirmation; }

        /** {@inheritDoc} Представляет кнопки и тексты сообщения без зависимости от клиента. */
        @Override public FormSpec spec(FormContext context) {
            var alert = confirmation.spec();
            return new FormSpec(alert.purpose(), WindowType.ALERT, alert.purpose(), Presentation.CONFIRM,
                    alert.windowTitle(), alert.glyph(), alert.minWidth(), true, false, true,
                    List.of(new FormPage("main", List.of(new FormRow.Hint("content", alert.content())))),
                    alert.buttons().stream().map(button -> new ButtonSpec(button.id(), button.text(), button.role(), button.tooltip())).toList(),
                    alert.defaultButtonId());
        }

        /** {@inheritDoc} У подтверждения нет полей ввода. */
        @Override public Map<String, String> defaults(FormContext context) { return Map.of(); }

        /** {@inheritDoc} Возвращает неизменяемые тексты и подробности сообщения. */
        @Override public FormView evaluate(FormState state, FormContext context) {
            var alert = confirmation.spec();
            return new FormView(0, 0, alert.header(), Map.of(), Problem.NONE, Map.of(), List.of(), List.of(),
                    alert.details(), alert.detailsExpanded());
        }

        /** {@inheritDoc} Результат адаптера хранит id кнопки; обычный путь использует callback AlertSession. */
        @Override public FormOutcome onButton(String id, FormState state, FormContext context) {
            return new FormOutcome.Close(id);
        }
    }

    /** Проверяет наличие исходной даты; отсутствие правила допускает форму с предупреждением. */
    private static LocalDate requireOccurrence(WindowState state) {
        String rule = state.contextValue(WindowType.CONTEXT_RULE_ID);
        String date = state.contextValue(WindowType.CONTEXT_ORIGINAL_DATE);
        if (rule.isBlank() || date.isBlank()) throw failure("restore.warn.noContext");
        try { return LocalDate.parse(date); }
        catch (java.time.format.DateTimeParseException e) { throw failure("restore.warn.noContext"); }
    }

    /** Формирует готовое предупреждение восстановления из общего каталога. */
    private static IllegalArgumentException failure(String key, Object... args) {
        return new IllegalArgumentException(UiText.get(key, args));
    }
}
