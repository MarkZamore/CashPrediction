package ru.cashprediction.core.service.plan;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.app.OpenWindows;
import ru.cashprediction.core.app.RecorderStatus;
import ru.cashprediction.core.app.StatusMessages;
import ru.cashprediction.core.app.flow.EditFlow;
import ru.cashprediction.core.app.flow.FlowContext;
import ru.cashprediction.core.app.flow.ToolsFlow;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.text.UiText;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет настоящих потребителей службы: потоки не меняют план в обход валидирующих команд. */
class PlanCommandsFlowsTest {
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate TODAY = START.plusDays(4);

    /** Редактор и его undo/redo действительно вызывают службу и сохраняют локализованные статусы. */
    @Test void editorCallsCommandsForChangeAndHistory() {
        Harness harness = new Harness(Plan.empty("test", START));
        harness.edits.addRule(Kind.INCOME);
        harness.formResult.accept(rule());
        assertEquals(1, harness.executions);
        assertEquals(List.of(rule()), harness.document.plan().rules());
        assertEquals("status.msg.ruleAdded", harness.statusKey);
        assertEquals(UiText.get("undo.ruleAdd", rule().title()), harness.document.undoDescription().orElseThrow());
        harness.edits.undo();
        assertEquals(2, harness.executions);
        assertTrue(harness.document.plan().rules().isEmpty());
        assertEquals("status.msg.undone", harness.statusKey);
        harness.edits.redo();
        assertEquals(3, harness.executions);
        assertEquals(List.of(rule()), harness.document.plan().rules());
        assertEquals("status.msg.redone", harness.statusKey);
    }

    /** Ошибка бизнес-команды в результате формы остаётся исключением для FormSession без изменения плана. */
    @Test void invalidResultOutsideFormChecksStillFailsAtServiceBoundary() {
        Harness harness = new Harness(Plan.empty("test", START));
        harness.edits.addRule(Kind.INCOME);
        assertThrows(IllegalArgumentException.class, () -> harness.formResult.accept(rule().withAmount(Money.ZERO)));
        assertTrue(harness.document.plan().rules().isEmpty());
        assertFalse(harness.document.canUndo());
        assertEquals(0, harness.commands.snapshot().revision());
        harness.formResult.accept(rule());
        assertEquals(2, harness.executions);
        assertEquals(List.of(rule()), harness.document.plan().rules());
    }

    /** Устаревшая форма не перезаписывает изменения; повторная явная попытка использует обновлённую ревизию. */
    @Test void staleFormResultIsRejectedAndCanBeExplicitlyRetried() {
        Harness harness = new Harness(Plan.empty("test", START).withRuleAdded(rule()));
        harness.edits.editRow("r1@2026-10-05");
        Consumer<Object> form = harness.formResult;
        harness.edits.edit(UiText.get("undo.currency", "USD"), "", new PlanCommand.SetCurrency("USD"));
        PlanCommandSnapshot before = harness.commands.snapshot();
        RecurringRule changed = rule().withAmount(Money.ofMajor(250));
        assertThrows(IllegalArgumentException.class, () -> form.accept(changed));
        assertEquals(before, harness.commands.snapshot());
        form.accept(changed);
        assertEquals(List.of(changed), harness.document.plan().rules());
        assertEquals("USD", harness.document.plan().currency());
        assertEquals(before.revision() + 1, harness.commands.snapshot().revision());
    }

    /** Подтверждение актуализации использует служебный предпросмотр, а stale revision отклоняется. */
    @Test void actualizeConfirmationCannotApplyToAnotherRevision() {
        Harness harness = new Harness(Plan.empty("test", START).withRuleAdded(rule()));
        harness.edits.actualize();
        assertEquals(1, harness.previews);
        assertEquals(0, harness.executions);
        Consumer<String> confirmed = harness.alertResult;
        harness.edits.edit(UiText.get("undo.currency", "USD"), "", new PlanCommand.SetCurrency("USD"));
        PlanCommandSnapshot before = harness.commands.snapshot();
        confirmed.accept("actualize");
        assertEquals(before, harness.commands.snapshot());
        assertEquals("err.editFailed", harness.alert.purpose());
        assertEquals(START, harness.document.plan().startDate());
    }

    /** Реальный поток инструментов применяет сценарий службой, выключает вид и сохраняет дневные балансы. */
    @Test void toolsWhatIfCallsCommandsAndResetsViewOnlyAfterAcceptance() {
        Harness harness = new Harness(Plan.empty("test", START).withRuleAdded(rule()));
        WhatIf scenario = WhatIf.ofPercent(-10, 0, Money.ofMajor(50));
        harness.document.setViewState(ViewState.defaults().withWhatIf(scenario));
        long[] simulated = harness.document.forecast().dailyBalance();
        harness.tools.applyWhatIf();
        harness.alertResult.accept("apply");
        assertEquals(1, harness.executions);
        assertTrue(harness.document.viewState().whatIf().isNone());
        assertArrayEquals(simulated, harness.document.forecast().dailyBalance());
        assertEquals("status.msg.whatIfApplied", harness.statusKey);
        harness.edits.undo();
        assertEquals(List.of(rule()), harness.document.plan().rules());
        assertTrue(harness.document.viewState().whatIf().isNone());
    }

    /** Принятый равный сценарий сбрасывает вид без истории; устаревший сценарий сохраняет вид и план. */
    @Test void equalAndStaleWhatIfHaveDifferentViewOutcomes() {
        Harness equal = new Harness(Plan.empty("test", START));
        equal.document.setViewState(ViewState.defaults().withWhatIf(WhatIf.ofPercent(-10, 0, Money.ZERO)));
        equal.tools.applyWhatIf();
        equal.alertResult.accept("apply");
        assertTrue(equal.document.viewState().whatIf().isNone());
        assertFalse(equal.document.canUndo());
        assertEquals(0, equal.commands.snapshot().revision());
        Harness stale = new Harness(Plan.empty("test", START).withRuleAdded(rule()));
        WhatIf scenario = WhatIf.ofPercent(-10, 0, Money.ZERO);
        stale.document.setViewState(ViewState.defaults().withWhatIf(scenario));
        stale.tools.applyWhatIf();
        Consumer<String> confirmed = stale.alertResult;
        stale.edits.edit(UiText.get("undo.currency", "USD"), "", new PlanCommand.SetCurrency("USD"));
        PlanCommandSnapshot before = stale.commands.snapshot();
        confirmed.accept("apply");
        assertEquals(before, stale.commands.snapshot());
        assertEquals(scenario, stale.document.viewState().whatIf());
        assertEquals("err.editFailed", stale.alert.purpose());
    }

    /** Очистка реального потока использует типизированный счётчик и один шаг отмены службы. */
    @Test void toolsCleanupUsesCommandCountAndRetainsOutOfHorizonOrphan() {
        Adjustment orphan = new Adjustment(new OccurrenceKey(new RuleId("r9"), TODAY), new Adjustment.Skip(), "");
        Adjustment outside = new Adjustment(new OccurrenceKey(new RuleId("r8"), TODAY.plusYears(2)), new Adjustment.Skip(), "");
        Plan initial = Plan.empty("test", START).withRuleAdded(rule()).withAdjustments(List.of(orphan, outside));
        Harness harness = new Harness(initial);
        harness.tools.cleanup();
        assertEquals(1, harness.executions);
        assertEquals(List.of(outside), harness.document.plan().adjustments());
        assertEquals("cleanup", harness.alert.purpose());
        assertTrue(harness.alert.header().contains("1"));
        harness.edits.undo();
        assertEquals(initial, harness.document.plan());
        assertFalse(harness.document.canUndo());
    }

    /** Создаёт правило дохода с номинальной датой на сегодняшний день теста. */
    private static RecurringRule rule() {
        return new RecurringRule(new RuleId("r1"), "income", Kind.INCOME, Money.ofMajor(200), "",
                new Recurrence.Monthly(5, 1), null, null, WeekendPolicy.NONE, true, "");
    }

    /** Контекст без клиентов: только документ, локальная служба, сообщения и обработчики настоящих потоков. */
    private static final class Harness {
        private final PlanDocument document;
        private final PlanCommands commands;
        private final EditFlow edits;
        private final ToolsFlow tools;
        private int executions;
        private int previews;
        private Consumer<Object> formResult;
        private Consumer<String> alertResult;
        private AlertSpec alert;
        private String statusKey = "";

        /** Создаёт службу до подписчиков, как требуется сборке приложения. */
        private Harness(Plan plan) {
            document = new PlanDocument(plan, null, () -> TODAY);
            LocalPlanCommands local = new LocalPlanCommands(document);
            commands = new PlanCommands() {
                /** {@inheritDoc} */
                @Override public PlanCommandSnapshot snapshot() { return local.snapshot(); }
                /** {@inheritDoc} */
                @Override public PlanCommandResult execute(PlanCommandRequest request) { executions++; return local.execute(request); }
                /** {@inheritDoc} */
                @Override public PlanCommandResult preview(PlanCommandRequest request) { previews++; return local.preview(request); }
            };
            FlowContext context = (FlowContext) Proxy.newProxyInstance(FlowContext.class.getClassLoader(), new Class<?>[]{FlowContext.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "document" -> document;
                        case "planCommands" -> commands;
                        case "state" -> state();
                        case "edits" -> edits();
                        case "tools" -> tools();
                        case "singleInstance" -> Optional.empty();
                        case "refresh" -> null;
                        case "status" -> { statusKey = (String) arguments[1]; yield null; }
                        case "showAlert" -> {
                            alert = (AlertSpec) arguments[0];
                            @SuppressWarnings("unchecked") Consumer<String> answer = (Consumer<String>) arguments[1];
                            alertResult = answer;
                            yield null;
                        }
                        case "openForm" -> {
                            @SuppressWarnings("unchecked") Consumer<Object> result = (Consumer<Object>) arguments[2];
                            formResult = result;
                            yield null;
                        }
                        case "updateView" -> {
                            @SuppressWarnings("unchecked") UnaryOperator<ViewState> change = (UnaryOperator<ViewState>) arguments[0];
                            document.setViewState(change.apply(document.viewState()));
                            yield null;
                        }
                        default -> throw new AssertionError(method.getName());
                    });
            edits = new EditFlow(context);
            tools = new ToolsFlow(context);
        }

        /** Откладывает чтение final-поля до завершения конструктора. */
        private EditFlow edits() { return edits; }

        /** Откладывает чтение final-поля до завершения конструктора. */
        private ToolsFlow tools() { return tools; }

        /** Строит реальный срез, который читают потоки и спецификации сообщений. */
        private AppState state() {
            DocumentView view = new DocumentView(document.plan(), null, document.isDirty(), document.canUndo(),
                    document.undoDescription().orElse(""), document.canRedo(), document.redoDescription().orElse(""),
                    document.forecast(), "", document.loadDiagnostics());
            return new AppState(0, ClientProfile.swing(), TODAY, Path.of("CashMemory"), null, view,
                    document.viewState(), "", true, AppSettings.defaults(), RecorderStatus.NOT_STARTED,
                    List.of(), OpenWindows.NONE, StatusMessages.EMPTY, "");
        }
    }
}
