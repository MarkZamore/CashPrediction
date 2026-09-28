package ru.cashprediction.core.ui.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.app.fake.FakeWindowHandle;
import ru.cashprediction.core.session.MainWindowState;
import ru.cashprediction.core.session.PlanState;
import ru.cashprediction.core.session.SessionSnapshot;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.session.codec.JsonSnapshotCodec;
import ru.cashprediction.core.session.codec.MarkdownSnapshotCodec;
import ru.cashprediction.core.session.codec.SnapshotCodec;
import ru.cashprediction.core.session.codec.XmlSnapshotCodec;

/**
 * Сеанс окна формы (архитектура §3.5): жизненный цикл и запись сеанса, правило эха, строка проблем, результаты
 * логики и восстановление через три кодека снимка.
 */
class FormSessionTest {

    private static final FormContext CONTEXT = new FormContext("w1", "main", Map.of("mode", "create"),
            FakeStates.empty(ClientProfile.fx("25"), Path.of("CashMemory")));

    @Test
    void initialViewUsesDefaultsAndSpecIsComputedOnce() {
        TestLogic logic = oneTime();
        Recorder host = new Recorder();
        FormSession session = new FormSession(WindowType.ONE_TIME_EDITOR, true, logic, CONTEXT, host);
        assertEquals(0, logic.specCalls, "логика не вызывается до spec()/view()");
        FormView view = session.view();
        session.spec();
        session.spec();
        assertEquals(1, logic.specCalls);
        assertEquals(1, view.revision());
        assertEquals("13.09.2026", view.fields().get("date").value(), "дата показывается по-русски");
        assertEquals("2026-09-13", session.state().value("date"), "в состоянии - ISO");
        assertEquals("Заполните поле «Название»", view.problem().text());
        assertFalse(view.buttons().get("ok").enabled(), "OK отключена, пока есть ошибка");
        assertTrue(view.fields().containsKey("note") && view.fields().containsKey("dates"), "запись есть у каждого поля");
        assertEquals(List.of(), host.events);
    }

    @Test
    void typingKeepsWidgetTextAndCommitReformats() {
        FakeWindowHandle handle = new FakeWindowHandle("form1", null);
        FormSession session = open(oneTime(), new Recorder(), handle);
        FormView typing = session.fieldChanged("amount", "80000", false, 5);
        assertNull(typing.fields().get("amount").value(), "текст под курсором не перезаписывается");
        assertEquals("80000,00", session.state().value("amount"));
        assertEquals(5, session.lastClientRev());
        assertEquals(List.of(typing), handle.updates());
        FormView committed = session.fieldChanged("amount", "80000", true, 6);
        assertEquals("80 000,00", committed.fields().get("amount").value());
        assertTrue(committed.revision() > typing.revision(), "ревизии монотонны");
    }

    @Test
    void invalidInputIsKeptAndBlocksOk() {
        Recorder host = new Recorder();
        FormSession session = open(oneTime(), host, new FakeWindowHandle("form1", null));
        session.fieldChanged("title", "Премия", true, 1);
        FormView view = session.fieldChanged("amount", "1,234", true, 2);
        assertEquals("1,234", session.state().value("amount"));
        assertEquals("1,234", view.fields().get("amount").value());
        assertEquals("✖ Поле «Сумма»: в сумме «1,234» больше двух цифр после запятой или точки. "
                + "Если это тысячи, разделяйте разряды пробелом: «1 234»", view.problem().display());
        session.buttonPressed("ok");
        assertFalse(session.isClosed(), "отключённая кнопка не нажимается");
        session.fieldChanged("amount", "1 234", true, 3);
        assertTrue(session.view().buttons().get("ok").enabled());
        session.buttonPressed("unknown");
        assertFalse(session.isClosed());
    }

    @Test
    void registerAndUnregisterExactlyOnce() {
        Recorder host = new Recorder();
        FakeWindowHandle handle = new FakeWindowHandle("form1", null);
        FormSession session = open(oneTime(), host, handle);
        session.fieldChanged("title", "до показа", true, 1);
        session.shown();
        session.shown();
        session.fieldChanged("title", "Премия", true, 2);
        session.boundsChanged(new WindowBounds(10, 20, 560, 400));
        session.fieldChanged("amount", "500", true, 3);
        session.buttonPressed("ok");
        session.closed();
        session.buttonPressed("ok");
        session.closeRequested();
        assertEquals(List.of("registered", "touched", "touched", "touched", "closed:saved Премия", "unregistered"), host.events);
        assertTrue(handle.closed());
        assertTrue(session.isClosed());
    }

    @Test
    void notRestorableFormIsNeverRegistered() {
        Recorder host = new Recorder();
        TestLogic logic = oneTime();
        logic.restorable = false;
        FormSession session = open(logic, host, new FakeWindowHandle("form1", null));
        session.shown();
        session.fieldChanged("title", "x", true, 1);
        session.closeRequested();
        assertEquals(List.of("closed:null"), host.events);
    }

    @Test
    void escapeUsesCancelAndClientCloseDoesNotCloseHandleAgain() {
        Recorder host = new Recorder();
        FakeWindowHandle handle = new FakeWindowHandle("form1", null);
        FormSession cancelled = open(oneTime(), host, handle);
        cancelled.shown();
        cancelled.closeRequested();
        assertEquals(List.of("registered", "closed:null", "unregistered"), host.events);
        assertTrue(handle.closed());

        Recorder second = new Recorder();
        FakeWindowHandle secondHandle = new FakeWindowHandle("form2", null);
        FormSession systemClosed = open(oneTime(), second, secondHandle);
        systemClosed.shown();
        systemClosed.closed();
        systemClosed.closed();
        assertEquals(List.of("registered", "closed:null", "unregistered"), second.events);
        assertFalse(secondHandle.closed(), "окно уже закрыто клиентом");
    }

    @Test
    void failedApplicationKeepsFormOpenWithProblem() {
        Recorder host = new Recorder();
        host.failClose = "Не удалось сохранить операцию";
        FakeWindowHandle handle = new FakeWindowHandle("form1", null);
        FormSession session = open(oneTime(), host, handle);
        session.shown();
        session.fieldChanged("title", "Премия", true, 1);
        session.fieldChanged("amount", "500", true, 2);
        session.buttonPressed("ok");
        assertFalse(session.isClosed());
        assertFalse(handle.closed());
        assertEquals(Problem.error("Не удалось сохранить операцию"), session.view().problem());
        assertFalse(session.view().buttons().get("ok").enabled());
        session.fieldChanged("note", "исправлено", true, 3);
        assertEquals(Problem.NONE, session.view().problem(), "проблема держится до изменения поля");
        host.failClose = null;
        session.buttonPressed("ok");
        assertTrue(session.isClosed());
        assertEquals(1, host.events.stream().filter("unregistered"::equals).count());
    }

    @Test
    void outcomesStaySetFieldsApplyOpenChild() {
        Recorder host = new Recorder();
        TestLogic logic = oneTime();
        FormSession session = open(logic, host, new FakeWindowHandle("form1", null));
        session.shown();

        session.apply(new FormOutcome.Stay(Problem.warning("Проверьте дату")));
        assertEquals("⚠ Проверьте дату", session.view().problem().display());
        session.apply(FormOutcome.stay());
        assertEquals("⚠ Проверьте дату", session.view().problem().display(), "пустой Stay не снимает проблему");

        session.apply(new FormOutcome.SetFields(Map.of("title", "Премия", "amount", "60000")));
        assertEquals("Премия", session.view().fields().get("title").value());
        assertEquals("60000,00", session.state().value("amount"));
        assertEquals("60 000,00", session.view().fields().get("amount").value());
        assertEquals(Problem.NONE, session.view().problem(), "SetFields сбрасывает проблему Stay");

        session.apply(new FormOutcome.Apply("action", Map.of("note", "после действия")));
        assertEquals("после действия", session.state().value("note"));
        host.failApply = "Цель не записана";
        session.apply(new FormOutcome.Apply("action", Map.of("note", "не применится")));
        assertEquals("после действия", session.state().value("note"));
        assertEquals("✖ Цель не записана", session.view().problem().display());

        WindowState child = new WindowState(FormOutcome.OpenChild.PENDING_ID, WindowType.ADJUSTMENT_EDITOR, true, "main",
                null, Map.of("ruleId", "r1"), Map.of());
        session.apply(new FormOutcome.OpenChild(child));
        assertEquals(List.of("registered", "touched", "applied:action", "touched", "openChild:ADJUSTMENT_EDITOR"),
                host.events);
        assertFalse(session.isClosed());
    }

    @Test
    void previewSelectionIsValidatedAndClearedWhenListChanges() {
        Recorder host = new Recorder();
        TestLogic logic = oneTime();
        logic.preview = (id, state) -> null;
        FormSession session = open(logic, host, new FakeWindowHandle("form1", null));
        session.previewSelected(0, false);
        assertEquals(FormState.NO_PREVIEW, session.state().previewIndex(), "заглушку выбрать нельзя");
        assertFalse(session.view().buttons().get("adjust").enabled());

        session.fieldChanged("title", "Премия", true, 1);
        session.previewSelected(1, false);
        assertEquals(1, session.state().previewIndex());
        assertTrue(session.view().buttons().get("adjust").enabled());
        session.previewSelected(7, false);
        assertEquals(FormState.NO_PREVIEW, session.state().previewIndex(), "индекс вне списка");

        session.previewSelected(1, false);
        session.fieldChanged("title", "Бонус", false, 2);
        assertEquals(FormState.NO_PREVIEW, session.state().previewIndex(), "новый список - выбор снят");
        assertFalse(session.view().buttons().get("adjust").enabled());

        logic.preview = (id, state) -> new FormOutcome.OpenChild(new WindowState(FormOutcome.OpenChild.PENDING_ID,
                WindowType.ADJUSTMENT_EDITOR, true, "main", null, Map.of(), Map.of()));
        session.previewSelected(0, true);
        assertEquals(List.of("openChild:ADJUSTMENT_EDITOR"), host.events);
        assertEquals(0, session.state().previewIndex());
    }

    @Test
    void innerButtonAndDefaultButtonViaEnter() {
        Recorder host = new Recorder();
        TestLogic logic = oneTime();
        logic.buttons.put("adjust", (id, state) -> new FormOutcome.Stay(Problem.warning("нажата")));
        FormSession session = open(logic, host, new FakeWindowHandle("form1", null));
        session.buttonPressed("adjust");
        assertFalse(session.view().problem().text().equals("нажата"), "отключённая кнопка колонки не нажимается");
        session.fieldChanged("title", "Премия", true, 0);
        session.previewSelected(0, false);
        session.buttonPressed("adjust");
        assertEquals("нажата", session.view().problem().text(), "доступная кнопка колонки нажимается");
        session.fieldChanged("title", "Премия", true, 1);
        session.fieldChanged("amount", "10", true, 2);
        session.fieldSubmitted("amount");
        assertEquals(List.of("closed:saved Премия"), host.events, "Enter нажимает кнопку по умолчанию");
    }

    @Test
    void popupPressesHiddenDefaultButtonAndOwnSubmitWins() {
        Recorder host = new Recorder();
        TestLogic popup = new TestLogic(WindowType.QUICK_EDIT_POPUP, Presentation.POPUP,
                List.of(new FormPage("main", List.of(new FormRow.Field(FieldSpecs.money("amount", "Сумма"))))),
                List.of(ButtonSpecs.ok(""), ButtonSpecs.cancel()), "ok", true, Map.of("amount", "95000,00"));
        popup.evaluate = (state, context) -> new FormView(0, 0, "", Map.of(), Problem.NONE,
                Map.of("ok", new ButtonView(true, false, null, null), "cancel", new ButtonView(true, false, null, null)),
                List.of(), List.of(), "", false);
        popup.buttons.put("ok", (id, state) -> new FormOutcome.Close(state.value("amount")));
        FormSession session = open(popup, host, new FakeWindowHandle("popup", null));
        session.fieldChanged("amount", "96 000", true, 1);
        session.fieldSubmitted("amount");
        assertEquals(List.of("closed:96000,00"), host.events);

        Recorder second = new Recorder();
        TestLogic own = oneTime();
        own.submitted = fieldId -> Optional.of(new FormOutcome.Stay(Problem.warning("свой Enter")));
        FormSession other = open(own, second, new FakeWindowHandle("form", null));
        other.fieldSubmitted("title");
        assertEquals("свой Enter", other.view().problem().text());
        assertEquals(List.of(), second.events);
    }

    @Test
    void wizardPagesAndDocumentChange() {
        TestLogic wizard = wizard();
        Recorder host = new Recorder();
        FormSession session = new FormSession(WindowType.NEW_PLAN_WIZARD, true, wizard,
                new FormContext("w2", "main", Map.of(), CONTEXT.app()), host);
        session.apply(new FormOutcome.Page(2));
        assertEquals(2, session.view().page());
        session.apply(new FormOutcome.Page(9));
        assertEquals(2, session.state().page(), "страница ограничена числом страниц");
        int before = wizard.evaluations;
        session.documentChanged(CONTEXT.withApp(CONTEXT.app()));
        assertEquals(before, wizard.evaluations, "форма без пересчёта по документу не пересчитывается");
        wizard.reevaluate = true;
        session.documentChanged(CONTEXT.withApp(CONTEXT.app()));
        assertEquals(before + 1, wizard.evaluations);
    }

    @Test
    void logicExceptionBecomesProblemLine() {
        TestLogic logic = oneTime();
        logic.evaluate = (state, context) -> {
            throw new IllegalStateException("сломалось");
        };
        logic.buttons.put("ok", (id, state) -> {
            throw new IllegalArgumentException();
        });
        FormSession session = open(logic, new Recorder(), new FakeWindowHandle("form1", null));
        assertEquals("✖ сломалось", session.view().problem().display());
        session.fieldSubmitted("title");
        assertFalse(session.isClosed());
    }

    static Stream<SnapshotCodec<String>> codecs() {
        return Stream.of(new JsonSnapshotCodec(), new XmlSnapshotCodec(), new MarkdownSnapshotCodec());
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void captureCodecApplyRestoresFieldsPageAndBounds(SnapshotCodec<String> codec) throws Exception {
        Recorder host = new Recorder();
        FakeWindowHandle handle = new FakeWindowHandle("form1", new WindowBounds(340, 120, 600, 520));
        FormSession original = new FormSession(WindowType.NEW_PLAN_WIZARD, true, wizard(),
                new FormContext("w3", "main", Map.of(), CONTEXT.app()), host);
        original.attach(handle);
        original.fieldChanged("name", "Семейный бюджет «2026»", true, 1);
        original.fieldChanged("startBalance", "1,234", true, 2);
        original.fieldChanged("startDate", "31.02.2026", false, 3);
        original.fieldChanged("quickIncomeAmount", "80 000,5", true, 4);
        original.fieldChanged("quickIncomeTitle", "  строка\nс переводом ", true, 5);
        original.apply(new FormOutcome.Page(1));
        WindowState captured = original.captureState();
        assertEquals(Map.of("page", "1"), captured.context());
        assertEquals("80000,50", captured.field("quickIncomeAmount"));
        assertEquals("1,234", captured.field("startBalance"), "некорректный ввод хранится как есть");

        SessionSnapshot snapshot = SessionSnapshot.of(Instant.parse("2026-09-13T10:15:30Z"), "fx", MainWindowState.empty(),
                PlanState.CLEAN, List.of(captured));
        WindowState decoded = codec.decode(codec.encode(snapshot)).windows().getFirst();

        Recorder restoredHost = new Recorder();
        FormSession restored = new FormSession(WindowType.NEW_PLAN_WIZARD, true, wizard(),
                new FormContext(decoded.id(), decoded.ownerId(), decoded.context(), CONTEXT.app()), restoredHost);
        restored.applyState(decoded);
        assertEquals(original.state().values(), restored.state().values(), codec.formatName());
        assertEquals(1, restored.state().page(), codec.formatName());
        assertEquals(FormState.NO_PREVIEW, restored.state().previewIndex());
        assertEquals(captured, restored.captureState(), codec.formatName());
        assertEquals(new WindowBounds(340, 120, 600, 520), restored.captureState().bounds());
        assertEquals("31.02.2026", restored.view().fields().get("startDate").value());
        assertEquals("80 000,50", restored.view().fields().get("quickIncomeAmount").value());
        assertEquals(List.of(), restoredHost.events, "applyState не регистрирует окно: это делает shown()");
    }

    @Test
    void captureWritesFieldIdsInDictionaryOrderAndContext() {
        FormSession session = open(oneTime(), new Recorder(), new FakeWindowHandle("form1", null));
        session.boundsChanged(new WindowBounds(1, 2, 3, 4));
        WindowState state = session.captureState();
        assertEquals("w1", state.id());
        assertEquals(WindowType.ONE_TIME_EDITOR, state.type());
        assertEquals(List.of("date", "title", "kind", "amount", "category", "note"), List.copyOf(state.fields().keySet()));
        assertEquals(Map.of("mode", "create"), state.context());
        assertEquals(new WindowBounds(1, 2, 3, 4), state.bounds(), "без границ ручки - последние известные");
        assertEquals(WindowState.MAIN_OWNER, state.ownerId());
        assertSame(session.state(), session.state(), "состояние не пересоздаётся без событий");
    }

    // ------------------------------------------------------------------ вспомогательное

    private static FormSession open(TestLogic logic, Recorder host, FakeWindowHandle handle) {
        FormSession session = new FormSession(logic.type, logic.type.defaultModal(), logic, CONTEXT, host);
        session.spec();
        session.attach(handle);
        return session;
    }

    /** Форма разовой операции: проверки даты, названия и суммы, колонка предпросмотра с кнопкой. */
    private static TestLogic oneTime() {
        FormPage page = new FormPage("main", List.of(
                new FormRow.Field(FieldSpecs.date("date", "Дата")),
                new FormRow.Field(FieldSpecs.text("title", "Название", "")),
                new FormRow.Field(FieldSpecs.radio("kind", "Тип", Orientation.HORIZONTAL,
                        List.of(Option.of("INCOME", "Доход"), Option.of("EXPENSE", "Расход")))),
                new FormRow.Field(FieldSpecs.money("amount", "Сумма")),
                new FormRow.Field(FieldSpecs.editableChoice("category", "Категория", List.of())),
                new FormRow.Field(FieldSpecs.multiline("note", "Заметка", 2)),
                new FormRow.SideColumn("Ближайшие даты", FieldSpecs.preview("dates", ""),
                        List.of(new FormRow.FormButtonSpec("adjust", "Скорректировать выбранную дату…", "")), true)));
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("date", "2026-09-13");
        defaults.put("title", "");
        defaults.put("kind", "EXPENSE");
        defaults.put("amount", "");
        defaults.put("category", "");
        defaults.put("note", "");
        TestLogic logic = new TestLogic(WindowType.ONE_TIME_EDITOR, Presentation.DIALOG, List.of(page),
                List.of(ButtonSpecs.ok("Сохранить"), ButtonSpecs.cancel()), "ok", true, defaults);
        logic.evaluate = (state, context) -> {
            Optional<String> error = FieldChecks.first(FieldChecks.date("Дата", state.value("date"), true),
                    FieldChecks.requiredText("Название", state.value("title")),
                    FieldChecks.money("Сумма", state.value("amount"), FieldChecks.MoneyRule.REQUIRED_POSITIVE));
            List<PreviewItem> preview = state.value("title").isBlank()
                    ? List.of(new PreviewItem("Заполните форму - здесь появятся даты", false))
                    : List.of(new PreviewItem(state.value("title") + " 1", true), new PreviewItem(state.value("title") + " 2", true));
            return new FormView(0, 0, "Новая разовая операция", Map.of(),
                    error.map(Problem::error).orElse(Problem.NONE),
                    Map.of("adjust", state.hasPreviewSelection() ? ButtonView.ENABLED : ButtonView.DISABLED),
                    List.of(), preview, "", false);
        };
        logic.buttons.put("ok", (id, state) -> new FormOutcome.Close("saved " + state.value("title")));
        logic.buttons.put("cancel", (id, state) -> new FormOutcome.Close(null));
        return logic;
    }

    /** Мастер из трёх страниц без проверок. */
    private static TestLogic wizard() {
        List<FormPage> pages = List.of(
                new FormPage("page1", List.of(new FormRow.Field(FieldSpecs.text("name", "Название плана", "")))),
                new FormPage("page2", List.of(new FormRow.Field(FieldSpecs.date("startDate", "Дата начала")),
                        new FormRow.Field(FieldSpecs.money("startBalance", "Сколько денег на эту дату")))),
                new FormPage("page3", List.of(new FormRow.Section("Ежемесячный доход"),
                        new FormRow.Field(FieldSpecs.text("quickIncomeTitle", "Название", "")),
                        new FormRow.Field(FieldSpecs.money("quickIncomeAmount", "Сумма")))));
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("name", "Мой план");
        defaults.put("startDate", "2026-09-13");
        defaults.put("startBalance", "0,00");
        defaults.put("quickIncomeTitle", "Зарплата");
        defaults.put("quickIncomeAmount", "");
        return new TestLogic(WindowType.NEW_PLAN_WIZARD, Presentation.WIZARD, pages,
                List.of(ButtonSpecs.of("finish", "Готово", ButtonRole.FINISH), ButtonSpecs.cancel()), "finish", true, defaults);
    }

    /** Настраиваемая логика формы для тестов сеанса. */
    private static final class TestLogic implements FormLogic {
        final WindowType type;
        final Presentation presentation;
        final List<FormPage> pages;
        final List<ButtonSpec> buttonSpecs;
        final String defaultButton;
        final Map<String, String> defaults;
        final Map<String, BiFunction<String, FormState, FormOutcome>> buttons = new HashMap<>();
        boolean restorable;
        boolean reevaluate;
        int specCalls;
        int evaluations;
        BiFunction<FormState, FormContext, FormView> evaluate = (state, context) -> new FormView(0, 0, "", Map.of(),
                Problem.NONE, Map.of(), List.of(), List.of(), "", false);
        BiFunction<Integer, FormState, FormOutcome> preview = (index, state) -> null;
        Function<String, Optional<FormOutcome>> submitted = fieldId -> Optional.empty();

        TestLogic(WindowType type, Presentation presentation, List<FormPage> pages, List<ButtonSpec> buttonSpecs,
                  String defaultButton, boolean restorable, Map<String, String> defaults) {
            this.type = type;
            this.presentation = presentation;
            this.pages = pages;
            this.buttonSpecs = buttonSpecs;
            this.defaultButton = defaultButton;
            this.restorable = restorable;
            this.defaults = defaults;
        }

        @Override
        public FormSpec spec(FormContext context) {
            specCalls++;
            return new FormSpec("test", type, "", presentation, type.title(), "", 560, type.defaultModal(), true,
                    restorable, pages, buttonSpecs, defaultButton);
        }

        @Override
        public Map<String, String> defaults(FormContext context) {
            return defaults;
        }

        @Override
        public FormView evaluate(FormState state, FormContext context) {
            evaluations++;
            return evaluate.apply(state, context);
        }

        @Override
        public FormOutcome onButton(String buttonId, FormState state, FormContext context) {
            BiFunction<String, FormState, FormOutcome> handler = buttons.get(buttonId);
            return handler == null ? FormOutcome.stay() : handler.apply(buttonId, state);
        }

        @Override
        public FormOutcome onPreview(int index, boolean activated, FormState state, FormContext context) {
            return activated ? preview.apply(index, state) : FormOutcome.stay();
        }

        @Override
        public Optional<FormOutcome> onFieldSubmitted(String fieldId, FormState state, FormContext context) {
            return submitted.apply(fieldId);
        }

        @Override
        public boolean reevaluateOnDocumentChange() {
            return reevaluate;
        }
    }

    /** Контроллер, записывающий события сеанса. */
    private static final class Recorder implements FormSession.Host {
        final List<String> events = new ArrayList<>();
        String failClose;
        String failApply;

        @Override
        public void registered(FormSession session) {
            events.add("registered");
        }

        @Override
        public void unregistered(FormSession session) {
            events.add("unregistered");
        }

        @Override
        public void touched(FormSession session) {
            events.add("touched");
        }

        @Override
        public void closed(FormSession session, Object result) {
            if (result != null && failClose != null) {
                throw new IllegalArgumentException(failClose);
            }
            events.add("closed:" + result);
        }

        @Override
        public void openChild(FormSession parent, WindowState child) {
            events.add("openChild:" + child.type());
        }

        @Override
        public void applied(FormSession session, Object action) {
            if (failApply != null) {
                throw new IllegalStateException(failApply);
            }
            events.add("applied:" + action);
        }
    }
}
