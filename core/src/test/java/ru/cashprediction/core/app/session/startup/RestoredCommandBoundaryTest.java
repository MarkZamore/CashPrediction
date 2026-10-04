package ru.cashprediction.core.app.flow;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.forecast.WhatIf;
import ru.cashprediction.core.model.Adjustment;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.model.Money;
import ru.cashprediction.core.model.OccurrenceKey;
import ru.cashprediction.core.model.OneTimeTransaction;
import ru.cashprediction.core.model.Plan;
import ru.cashprediction.core.model.Recurrence;
import ru.cashprediction.core.model.RecurringRule;
import ru.cashprediction.core.model.RuleId;
import ru.cashprediction.core.model.TxId;
import ru.cashprediction.core.model.WeekendPolicy;
import ru.cashprediction.core.service.plan.PlanCommand;
import ru.cashprediction.core.service.plan.PlanCommandRequest;
import ru.cashprediction.core.service.plan.PlanCommandResult;
import ru.cashprediction.core.service.plan.PlanCommandSnapshot;
import ru.cashprediction.core.service.plan.PlanCommands;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.alert.AlertSession;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.form.Problem;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет границу команд восстановленных окон на настоящих фабрике, потоках и сеансах без клиентов. */
class RestoredCommandBoundaryTest {
    @TempDir Path home;

    /** Сохранение восстановленного редактора вызывает типизированную команду и создаёт ровно один шаг отмены. */
    @Test void restoredFormsCommitOnceThroughCommands() {
        for (FormCase example : forms()) {
            Harness harness = new Harness(home);
            Plan initial = harness.base.document.plan();
            long revision = harness.commands.snapshot().revision();
            FormSession session = harness.restoreForm(example);
            assertFalse(session.isClosed(), example.name());
            assertNotEquals(Problem.Severity.ERROR, session.view().problem().severity(), example.name());
            example.fields().forEach((field, value) -> assertEquals(value, session.state().value(field), example.name()));
            session.buttonPressed(session.spec().defaultButtonId());
            assertTrue(session.isClosed(), example.name());
            assertEquals(1, harness.executed.size(), example.name());
            assertEquals(example.command(), harness.executed.getFirst().command(), example.name());
            assertEquals(revision, harness.executed.getFirst().expectedRevision(), example.name());
            assertEquals(revision + 1, harness.commands.snapshot().revision(), example.name());
            if (example.command() instanceof PlanCommand.Reconcile)
                assertEquals(Money.ofMajor(900), harness.base.document.forecast().balanceAt(FlowHarness.TODAY));
            PlanCommandSnapshot committed = harness.commands.snapshot();
            session.buttonPressed(session.spec().defaultButtonId());
            assertEquals(1, harness.executed.size(), example.name());
            assertEquals(committed, harness.commands.snapshot(), example.name());
            harness.assertSingleUndo(initial);
        }
    }

    /** Ревизия фиксируется до открытия; отказ службы оставляет восстановленные и допечатанные поля в форме. */
    @Test void staleRestoredFormsKeepTypedFieldsAndHistory() {
        for (FormCase example : forms()) {
            Harness harness = new Harness(home);
            long openedAt = harness.commands.snapshot().revision();
            FormSession session = harness.restoreForm(example);
            String field = example.fields().containsKey("amount") ? "amount"
                    : example.fields().containsKey("note") ? "note" : "value";
            String input = field.equals("amount") ? "275,00" : field.equals("note") ? "typed-after-restore"
                    : example.command() instanceof PlanCommand.Reconcile ? "925,00" : "$";
            session.fieldChanged(field, input, false, 17);
            assertEquals(input, session.state().value(field), example.name());
            Map<String, String> typed = session.captureState().fields();
            harness.changeCurrency();
            PlanCommandSnapshot before = harness.commands.snapshot();
            session.buttonPressed(session.spec().defaultButtonId());
            assertFalse(session.isClosed(), example.name());
            assertEquals(Problem.Severity.ERROR, session.view().problem().severity(), example.name());
            assertEquals(typed, session.captureState().fields(), example.name());
            assertEquals(17, session.lastClientRev(), example.name());
            assertEquals(before, harness.commands.snapshot(), example.name());
            assertEquals(1, harness.executed.size(), example.name());
            assertEquals(openedAt, harness.executed.getFirst().expectedRevision(), example.name());
            assertInstanceOf(example.command().getClass(), harness.executed.getFirst().command(), example.name());
        }
    }

    /** Восстановленные подтверждения используют команды, захваченный сценарий и защиту от повторного ответа. */
    @Test void restoredConfirmationsCommitOnceThroughCommands() {
        for (String purpose : List.of("deleteRule", "deleteOneTime", "actualize", "applyWhatIf")) {
            Harness harness = new Harness(home);
            WhatIf scenario = WhatIf.ofPercent(-10, 0, Money.ZERO);
            harness.base.document.setViewState(harness.base.document.viewState().withWhatIf(scenario));
            Plan initial = harness.base.document.plan();
            long revision = harness.commands.snapshot().revision();
            harness.restoreConfirmation(purpose);
            Consumer<String> answer = harness.confirmed;
            // После открытия вид может измениться, но подтверждение относится к исходному сценарию.
            if (purpose.equals("applyWhatIf"))
                harness.base.document.setViewState(harness.base.document.viewState().withWhatIf(WhatIf.ofPercent(-30, 0, Money.ZERO)));
            answer.accept(harness.confirmButton);
            assertEquals(1, harness.executed.size(), purpose);
            assertEquals(confirmationCommand(purpose, scenario), harness.executed.getFirst().command(), purpose);
            assertEquals(revision, harness.executed.getFirst().expectedRevision(), purpose);
            assertEquals(revision + 1, harness.commands.snapshot().revision(), purpose);
            if (purpose.equals("actualize")) assertEquals(FlowHarness.TODAY, harness.base.document.plan().startDate());
            if (purpose.equals("applyWhatIf")) {
                assertTrue(harness.base.document.viewState().whatIf().isNone());
                assertEquals(Money.ofMajor(180), harness.base.document.plan().rules().getFirst().amount());
            }
            PlanCommandSnapshot committed = harness.commands.snapshot();
            answer.accept(harness.confirmButton);
            assertEquals(1, harness.executed.size(), purpose);
            assertEquals(committed, harness.commands.snapshot(), purpose);
            harness.assertSingleUndo(initial);
        }
    }

    /** Устаревшее подтверждение не меняет план и вид и не выполняется повторно после первого отказа. */
    @Test void staleRestoredConfirmationsRejectCapturedRevision() {
        for (String purpose : List.of("deleteRule", "deleteOneTime", "actualize", "applyWhatIf")) {
            Harness harness = new Harness(home);
            WhatIf scenario = WhatIf.ofPercent(-10, 0, Money.ZERO);
            harness.base.document.setViewState(harness.base.document.viewState().withWhatIf(scenario));
            long openedAt = harness.commands.snapshot().revision();
            harness.restoreConfirmation(purpose);
            harness.changeCurrency();
            PlanCommandSnapshot before = harness.commands.snapshot();
            harness.confirmed.accept(harness.confirmButton);
            assertEquals(before, harness.commands.snapshot(), purpose);
            assertEquals(scenario, harness.base.document.viewState().whatIf(), purpose);
            assertEquals(1, harness.executed.size(), purpose);
            assertEquals(openedAt, harness.executed.getFirst().expectedRevision(), purpose);
            assertEquals(confirmationCommand(purpose, scenario), harness.executed.getFirst().command(), purpose);
            assertEquals("err.editFailed", harness.base.alerts.getLast().purpose(), purpose);
            harness.confirmed.accept(harness.confirmButton);
            assertEquals(1, harness.executed.size(), purpose);
            assertEquals(1, harness.base.alerts.size(), purpose);
            assertEquals(before, harness.commands.snapshot(), purpose);
        }
    }

    /** Предпросмотр восстановленной актуализации обязан пройти службу до подтверждения, не меняя документ. */
    @Test void restoredActualizeUsesCommandPreviewBeforeAnswer() {
        Harness harness = new Harness(home);
        PlanCommandSnapshot before = harness.commands.snapshot();
        harness.restoreConfirmation("actualize");
        assertEquals(before, harness.commands.snapshot());
        assertTrue(harness.executed.isEmpty());
        // Регрессия: FormCatalog.confirmation пока вызывает actualize на отдельном PlanDocument.
        assertEquals(1, harness.previewed.size(), "Restored actualize must preview through PlanCommands");
        assertEquals(new PlanCommand.Actualize(FlowHarness.TODAY, null), harness.previewed.getFirst().command());
        assertEquals(before.revision(), harness.previewed.getFirst().expectedRevision());
    }

    /** Даёт компактные примеры именно восстановленных результатов, не повторяя проверки свежих потоков. */
    private static List<FormCase> forms() {
        RecurringRule changedRule = rule().withAmount(Money.ofMajor(250));
        OneTimeTransaction changedTx = new OneTimeTransaction(new TxId("t1"), FlowHarness.TODAY.plusDays(1),
                "expense", Kind.EXPENSE, Money.ofMajor(75), "", "");
        Adjustment adjustment = new Adjustment(new OccurrenceKey(new RuleId("r1"), FlowHarness.TODAY),
                new Adjustment.ChangeAmount(Money.ofMajor(250)), "");
        return List.of(
                new FormCase("rule", WindowType.RULE_EDITOR, Map.of("mode", "edit", "ruleId", "r1"),
                        Map.of("amount", "250,00"), new PlanCommand.ReplaceRule(changedRule)),
                new FormCase("oneTime", WindowType.ONE_TIME_EDITOR, Map.of("mode", "edit", "txId", "t1"),
                        Map.of("amount", "75,00"), new PlanCommand.ReplaceOneTime(changedTx)),
                new FormCase("settings", WindowType.PLAN_SETTINGS, Map.of(), Map.of("note", "restored-note"),
                        new PlanCommand.UpdateSettings(PlanCommand.Settings.from(seed().withNote("restored-note")))),
                new FormCase("currency", WindowType.TEXT_INPUT, Map.of("purpose", "customCurrency"),
                        Map.of("value", "USD"), new PlanCommand.SetCurrency("USD")),
                new FormCase("currencyChoice", WindowType.CHOICE, Map.of("purpose", "currency"),
                        Map.of("value", "BYN"), new PlanCommand.SetCurrency("BYN")),
                new FormCase("adjustment", WindowType.ADJUSTMENT_EDITOR,
                        Map.of("ruleId", "r1", "originalDate", FlowHarness.TODAY.toString()),
                        Map.of("action", "CHANGE_AMOUNT", "amount", "250,00"), new PlanCommand.PutAdjustment(adjustment)),
                new FormCase("reconcile", WindowType.TEXT_INPUT, Map.of("purpose", "reconcile"),
                        Map.of("value", "900,00"), new PlanCommand.Reconcile(FlowHarness.TODAY, Money.ofMajor(900))));
    }

    /** Возвращает ожидаемую команду восстановленного подтверждения. */
    private static PlanCommand confirmationCommand(String purpose, WhatIf scenario) {
        return switch (purpose) {
            case "deleteRule" -> new PlanCommand.RemoveRule(new RuleId("r1"));
            case "deleteOneTime" -> new PlanCommand.RemoveOneTime(new TxId("t1"));
            case "actualize" -> new PlanCommand.Actualize(FlowHarness.TODAY, null);
            case "applyWhatIf" -> new PlanCommand.ApplyWhatIf(scenario, FlowHarness.TODAY);
            default -> throw new AssertionError(purpose);
        };
    }

    /** Создаёт валидный план с прошлым началом и двумя редактируемыми целями. */
    private static Plan seed() {
        return Plan.empty("seed", FlowHarness.TODAY.minusDays(4)).withRuleAdded(rule()).withOneTimeAdded(
                new OneTimeTransaction(new TxId("t1"), FlowHarness.TODAY.plusDays(1), "expense", Kind.EXPENSE,
                        Money.ofMajor(50), "", ""));
    }

    /** Создаёт правило с номинальной датой на сегодняшний день стенда. */
    private static RecurringRule rule() {
        return new RecurringRule(new RuleId("r1"), "income", Kind.INCOME, Money.ofMajor(200), "",
                new Recurrence.Monthly(1, 1), null, null, WeekendPolicy.NONE, true, "");
    }

    /** Данные одного восстановленного окна и ожидаемая предметная команда. */
    private record FormCase(String name, WindowType type, Map<String, String> context,
                            Map<String, String> fields, PlanCommand command) { }

    /** Дополняет общий FlowHarness настоящими сеансами и наблюдением службы без изменения общего стенда. */
    private static final class Harness {
        private final FlowHarness base;
        private final List<PlanCommandRequest> executed = new ArrayList<>();
        private final List<PlanCommandRequest> previewed = new ArrayList<>();
        private final List<FormSession> sessions = new ArrayList<>();
        private final PlanCommands commands;
        private final FlowContext context;
        private final EditFlow edits;
        private Consumer<String> confirmed;
        private String confirmButton;

        /** Использует единственную локальную службу общего стенда, записывая все обращения потребителей. */
        private Harness(Path home) {
            base = new FlowHarness(home);
            base.document.replace(seed(), null, false, List.of());
            commands = new PlanCommands() {
                /** Возвращает настоящий снимок службы общего стенда. */
                @Override public PlanCommandSnapshot snapshot() { return base.planCommands.snapshot(); }
                /** Записывает запрос и выполняет его настоящей службой. */
                @Override public PlanCommandResult execute(PlanCommandRequest request) {
                    executed.add(request);
                    return base.planCommands.execute(request);
                }
                /** Записывает запрос предпросмотра без подмены его реализации. */
                @Override public PlanCommandResult preview(PlanCommandRequest request) {
                    previewed.add(request);
                    return base.planCommands.preview(request);
                }
            };
            context = (FlowContext) Proxy.newProxyInstance(FlowContext.class.getClassLoader(), new Class<?>[]{FlowContext.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "planCommands": return commands;
                            case "edits": return edits();
                            case "state": return state();
                            case "singleInstance": return Optional.empty();
                            case "openForm": return openForm((FormRequest) args[0], consumer(args[2]));
                            case "showRestoredAlert":
                                restoredAlert((AlertSpec) args[0], (WindowState) args[1], consumer(args[2]), consumer(args[3]));
                                return null;
                            default:
                                try { return method.invoke(base.context, args); }
                                catch (InvocationTargetException error) { throw error.getCause(); }
                        }
                    });
            edits = new EditFlow(context);
        }

        /** Возвращает поток после завершения конструктора для callback прокси. */
        private EditFlow edits() { return edits; }

        /** Дополняет состояние общего стенда рассчитанным прогнозом и настоящей историей документа. */
        private AppState state() {
            AppState app = base.context.state();
            var document = base.document;
            DocumentView view = new DocumentView(document.plan(), null, document.isDirty(), document.canUndo(),
                    document.undoDescription().orElse(""), document.canRedo(), document.redoDescription().orElse(""),
                    document.forecast(), "", document.loadDiagnostics());
            return new AppState(app.revision(), app.profile(), app.today(), app.cashMemory(), app.plansFolder(), view,
                    document.viewState(), app.selectedRowId(), app.pastExpanded(), app.settings(), app.recorder(),
                    app.stores(), app.windows(), app.status(), app.autosaveProblem());
        }

        /** Восстанавливает окно только через настоящую фабрику и возвращает созданный ею сеанс. */
        private FormSession restoreForm(FormCase example) {
            WindowState saved = new WindowState("restored", example.type(), true, "main", null,
                    example.context(), example.fields());
            new CoreWindowFactory(context).open(saved, "main", shown -> assertSame(sessions.getLast(), shown), reason -> fail(reason));
            return sessions.getLast();
        }

        /** Создаёт настоящий FormSession и связывает результаты кнопок с callback фабрики. */
        private FormSession openForm(FormRequest request, Consumer<Object> result) {
            FormSession.Host host = new FormSession.Host() {
                /** Регистрация не требует клиента или хранилища. */
                @Override public void registered(FormSession session) { }
                /** Снятие регистрации не требует клиента или хранилища. */
                @Override public void unregistered(FormSession session) { }
                /** Правки остаются внутри настоящего сеанса. */
                @Override public void touched(FormSession session) { }
                /** Передаёт настоящий результат кнопки фабрике до закрытия сеанса. */
                @Override public void closed(FormSession session, Object value) { result.accept(value); }
                /** Не допускает неожиданных дочерних окон в этих сценариях. */
                @Override public void openChild(FormSession parent, WindowState child) { throw new AssertionError(child); }
                /** Передаёт действие формы по тому же callback. */
                @Override public void applied(FormSession session, Object value) { result.accept(value); }
            };
            WindowState saved = request.restored();
            // JavaFX: Dialog → Swing: JDialog → Web: dialog
            FormSession session = new FormSession(request.type(), request.modal(), request.logic(),
                    new FormContext(saved.id(), saved.ownerId(), request.context(), state()), host);
            session.applyState(saved);
            sessions.add(session);
            session.shown();
            return session;
        }

        /** Восстанавливает подтверждение через фабрику, сохраняя настоящий обработчик ответа. */
        private void restoreConfirmation(String purpose) {
            String target = purpose.equals("deleteRule") ? "r1" : purpose.equals("deleteOneTime") ? "t1" : "";
            WindowState saved = new WindowState("confirmation", WindowType.ALERT, true, "main", null,
                    Map.of("purpose", purpose, "targetId", target), Map.of());
            new CoreWindowFactory(context).open(saved, "main", shown -> assertEquals(saved.id(), shown.windowId()), reason -> fail(reason));
            assertNotNull(confirmed);
        }

        /** Воспроизводит показ без UI; повторные ответы тестирует непосредственно на callback фабрики. */
        private void restoredAlert(AlertSpec spec, WindowState saved, Consumer<ru.cashprediction.core.session.StatefulWindow> shown,
                                   Consumer<String> answer) {
            // JavaFX: Alert → Swing: JOptionPane → Web: dialog
            AlertSession session = new AlertSession(saved.id(), saved.ownerId(), spec, new AlertSession.Host() {
                /** Показ не обращается к хранилищам. */
                @Override public void registered(AlertSession value) { }
                /** Закрытие не обращается к хранилищам. */
                @Override public void unregistered(AlertSession value) { }
            });
            session.applyState(saved);
            session.whenShown(shown::accept);
            confirmed = answer;
            confirmButton = spec.defaultButtonId();
            session.shown();
        }

        /** Создаёт внешнюю для окна правку через ту же службу, не засчитывая её как команду фабрики. */
        private void changeCurrency() {
            assertTrue(base.planCommands.execute(new PlanCommandRequest(UUID.randomUUID(), commands.snapshot().revision(),
                    "concurrent", new PlanCommand.SetCurrency("EUR"))).changed());
        }

        /** Проверяет возврат к исходному плану одной командой отмены и отсутствие второго шага. */
        private void assertSingleUndo(Plan initial) {
            assertTrue(base.document.canUndo());
            edits.undo();
            assertEquals(initial, base.document.plan());
            assertFalse(base.document.canUndo());
            assertTrue(base.document.canRedo());
            assertInstanceOf(PlanCommand.Undo.class, executed.getLast().command());
        }

        /** Приводит тип callback на границе прокси, не подменяя его поведения. */
        @SuppressWarnings("unchecked")
        private static <T> Consumer<T> consumer(Object value) { return (Consumer<T>) value; }
    }
}
