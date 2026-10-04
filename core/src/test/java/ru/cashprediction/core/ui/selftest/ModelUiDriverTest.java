package ru.cashprediction.core.ui.selftest;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.view.MainScreenModel;
import ru.cashprediction.core.ui.view.chart.ChartModel;
import ru.cashprediction.core.ui.view.status.StatusModel;
import ru.cashprediction.core.ui.view.summary.SummaryModel;
import ru.cashprediction.core.ui.view.table.TableModel;
import static org.junit.jupiter.api.Assertions.*;

/** Проверки событий драйвера на настоящем FormSession, без запуска приложения или реестра. */
class ModelUiDriverTest {
    @TempDir Path temporary;

    /** Наблюдаемое намерение пользователя. */
    private record Call(String name, List<Object> args) { }
    private final List<Call> calls = new ArrayList<>();
    private AppEnvironment environment() { return AppEnvironment.from(LaunchOptions.parse("--home", temporary.toString(), "--registry", "memory", "--today", "2026-09-13")); }
    private UiIntents intents() {
        return (UiIntents) Proxy.newProxyInstance(UiIntents.class.getClassLoader(), new Class<?>[] {UiIntents.class}, (p, method, args) -> {
            calls.add(new Call(method.getName(), args == null ? List.of() : java.util.Arrays.asList(args)));
            if (method.getName().equals("contextMenu") && args[0] instanceof ContextTarget.Preview preview)
                return List.of(new MenuNode.Action("ctx.preview.adjust", CommandId.PREVIEW_ADJUST,
                        CommandArgs.keyValue(preview.windowId(), Integer.toString(preview.index())), "Скорректировать", null, "", true));
            return method.getReturnType() == boolean.class ? true : null;
        });
    }
    private RecordingUiPort port() {
        var port = new RecordingUiPort(ClientProfile.fx("25"));
        var save = new MenuNode.Action("file.save", CommandId.FILE_SAVE, CommandArgs.NONE, "Сохранить", null, "", true);
        var disabled = new MenuNode.Action("file.openFile", CommandId.FILE_OPEN_FILE, CommandArgs.NONE, "Недоступно", null, "", false);
        var slider = new MenuNode.Slider("view.horizonSlider", CommandId.VIEW_HORIZON_SLIDER, 1, 120, 12, 12, List.of(), "12", "", 220);
        var spinner = new MenuNode.Spinner("whatIf.extra", CommandId.WHAT_IF_EXTRA, "Дополнительно", 0, 10000000, 100, 0, "", 100, 600);
        var menu = new MenuNode.Submenu("file", "Файл", "", true, List.of(save, disabled, slider, spinner));
        var table = (TableModel) Proxy.newProxyInstance(TableModel.class.getClassLoader(), new Class<?>[] {TableModel.class}, (p, m, a) -> {
            if (m.getName().equals("indexOf")) return "r1".equals(a[0]) ? 0 : -1;
            if (m.getReturnType() == long.class) return 1L;
            if (m.getReturnType() == int.class) return 0;
            return m.getReturnType() == String.class ? "" : List.of();
        });
        var chart = (ChartModel) Proxy.newProxyInstance(ChartModel.class.getClassLoader(), new Class<?>[] {ChartModel.class}, (p, m, a) -> null);
        port.showMain(new MainScreenModel(1, "Тест", new MenuBarModel(List.of(menu)),
                new ToolbarModel(List.of(new ToolbarNode.Button("tb.save", CommandId.FILE_SAVE, "Сохранить", "", true, Emphasis.NONE))),
                new SummaryModel(true, List.of(), ""), table, chart, new StatusModel(List.of()), ru.cashprediction.core.document.ViewMode.TABLE), null);
        return port;
    }

    /** Логика с зависимыми полями, скрытым и недоступным вводом, выбором и предпросмотром. */
    private static final class Logic implements FormLogic {
        final AtomicInteger accepted = new AtomicInteger();
        int previewIndex = -1;
        boolean activated;
        /** Возвращает раскладку доступных полей и кнопок. */
        @Override public FormSpec spec(FormContext context) {
            return new FormSpec("test", WindowType.TEXT_INPUT, "test", Presentation.TEXT_INPUT, "Форма", "", 460,
                    true, false, true, List.of(new FormPage("main", List.of(
                    new FormRow.Field(FieldSpecs.text("value", "Значение", "")),
                    new FormRow.Field(FieldSpecs.check("enable", "Разрешить")),
                    new FormRow.Field(FieldSpecs.text("dependent", "Зависимое", "")),
                    new FormRow.Field(FieldSpecs.text("hidden", "Скрытое", "")),
                    new FormRow.Field(FieldSpecs.text("readonly", "Чтение", "")),
                    new FormRow.Field(FieldSpecs.choice("choice", "Выбор", List.of(Option.of("a", "Первый"), Option.of("b", "Второй")))),
                    new FormRow.SideColumn("Даты", FieldSpecs.text("preview", "Даты", ""), List.of(), true)))),
                    List.of(new ButtonSpec("save", "Сохранить", ButtonRole.OK, ""), new ButtonSpec("cancel", "Отмена", ButtonRole.CANCEL, "")), "save");
        }
        /** Возвращает безопасные исходные значения. */
        @Override public Map<String, String> defaults(FormContext context) { return Map.of("value", "", "enable", "false", "choice", "a"); }
        /** Запрещает подтверждение пустого значения и редактирование скрытых полей. */
        @Override public FormView evaluate(FormState state, FormContext context) {
            return new FormView(0, 0, "", Map.of("dependent", new FieldView(state.value("dependent"), true, state.value("enable").equals("true"), false, null, null, null),
                    "hidden", new FieldView("", false, true, false, null, null, null),
                    "readonly", new FieldView("", true, true, true, null, null, null)),
                    state.value("value").isEmpty() ? Problem.error("Введите значение") : Problem.NONE,
                    Map.of(), List.of(), List.of(new PreviewItem("05.10.2026", true)), "", false);
        }
        /** Подтверждает только успешную пользовательскую кнопку. */
        @Override public FormOutcome onButton(String id, FormState state, FormContext context) {
            if (id.equals("save")) { accepted.incrementAndGet(); return new FormOutcome.Close(state.value("value")); }
            return new FormOutcome.Close(null);
        }
        /** Записывает настоящий выбор и активацию предпросмотра. */
        @Override public FormOutcome onPreview(int index, boolean activate, FormState state, FormContext context) { previewIndex = index; activated = activate; return FormOutcome.stay(); }
        /** Записывает двойной щелчок после изменения значения списка. */
        @Override public FormOutcome onFieldActivated(String id, int index, FormState state, FormContext context) { previewIndex = index; activated = true; return FormOutcome.stay(); }
    }
    /** Хост формы, который не пишет снимки и не обращается к диску. */
    private static final class Host implements FormSession.Host {
        Object result;
        /** Принимает показ окна. */
        @Override public void registered(FormSession session) { }
        /** Принимает удаление окна. */
        @Override public void unregistered(FormSession session) { }
        /** Принимает событие изменения поля. */
        @Override public void touched(FormSession session) { }
        /** Запоминает результат подтверждения. */
        @Override public void closed(FormSession session, Object value) { result = value; }
        /** Не открывает дочерние окна в этой логике. */
        @Override public void openChild(FormSession parent, WindowState child) { throw new AssertionError("unexpected child"); }
        /** Не принимает действий этой логики. */
        @Override public void applied(FormSession session, Object action) { throw new AssertionError("unexpected apply"); }
    }
    private FormSession open(RecordingUiPort port, String id, Logic logic, Host host) {
        var context = new FormContext(id, WindowState.MAIN_OWNER, Map.of(), FakeStates.empty(port.profile, temporary.resolve("CashMemory")));
        var session = new FormSession(WindowType.TEXT_INPUT, true, logic, context, host);
        port.openForm(session, session.spec(), session.view(), Placement.centered(WindowState.MAIN_OWNER)); return session;
    }

    @Test void requiresExplicitIsolatedHomeBeforeAnyStartup() {
        assertThrows(IllegalArgumentException.class, () -> new ModelUiDriver(AppEnvironment.from(LaunchOptions.defaults()), ClientProfile.fx("25")));
        assertThrows(IllegalArgumentException.class, () -> new ModelUiDriver(AppEnvironment.from(LaunchOptions.parse("--home", temporary.toString())), ClientProfile.fx("25")));
        assertEquals(ClientKind.FX, new ModelUiDriver(environment(), ClientProfile.fx("25")).client());
        assertFalse(java.nio.file.Files.exists(temporary.resolve("CashMemory")));
    }
    @Test void formValidationAndReadonlyFieldsCannotBeBypassed() {
        var port = port(); var logic = new Logic(); var session = open(port, "w1", logic, new Host());
        var driver = new ModelUiDriver(environment(), port, intents());
        assertThrows(IllegalStateException.class, () -> driver.execute(new SelfTestCommand.Ok("last")));
        for (String id : List.of("dependent", "hidden", "readonly"))
            assertThrows(IllegalStateException.class, () -> driver.execute(new SelfTestCommand.Fill("last", Map.of(id, "value"))));
        assertEquals(0, logic.accepted.get()); assertEquals("", session.state().value("value")); assertFalse(session.isClosed());
        var updates = new java.util.LinkedHashMap<String, String>(); updates.put("enable", "true"); updates.put("dependent", "allowed"); updates.put("value", "ready");
        driver.execute(new SelfTestCommand.Fill("last", updates)); driver.execute(new SelfTestCommand.Ok("last"));
        assertEquals("allowed", session.state().value("dependent")); assertEquals(1, logic.accepted.get()); assertTrue(session.isClosed());
    }
    @Test void modalChainBlocksParentAndMainAndAllowsTopWindow() {
        var port = port(); var parent = open(port, "w1", new Logic(), new Host()); var child = open(port, "w2", new Logic(), new Host());
        var driver = new ModelUiDriver(environment(), port, intents());
        assertThrows(IllegalStateException.class, () -> driver.execute(new SelfTestCommand.Menu("file.save")));
        assertThrows(IllegalStateException.class, () -> driver.execute(new SelfTestCommand.Fill("w1", Map.of("value", "blocked"))));
        driver.execute(new SelfTestCommand.Fill("last", Map.of("value", "child"))); driver.execute(new SelfTestCommand.Cancel("last"));
        assertTrue(child.isClosed()); assertFalse(parent.isClosed()); assertTrue(calls.isEmpty());
        driver.execute(new SelfTestCommand.Fill("w1", Map.of("value", "parent"))); assertEquals("parent", parent.state().value("value"));
    }
    @Test void listAndPreviewUseActualActivationAndRejectInventedOptions() {
        var port = port(); var logic = new Logic(); var session = open(port, "w1", logic, new Host());
        var driver = new ModelUiDriver(environment(), port, intents());
        driver.execute(new SelfTestCommand.ListPick("Форма", "Выбор", "Второй", true));
        assertEquals("b", session.state().value("choice")); assertEquals(1, logic.previewIndex); assertTrue(logic.activated);
        driver.execute(new SelfTestCommand.ListPick("Форма", "Даты", "05.10.2026", true));
        assertEquals(0, logic.previewIndex); assertTrue(logic.activated);
        assertThrows(IllegalStateException.class, () -> driver.execute(new SelfTestCommand.Fill("last", Map.of("choice", "invented"))));
    }
    @Test void menuToolbarAndKeysPreserveSourcesAndFireOnce() {
        var port = port(); var driver = new ModelUiDriver(environment(), port, intents());
        driver.execute(new SelfTestCommand.Menu("Файл/Сохранить")); driver.execute(new SelfTestCommand.Click("tb.save"));
        driver.execute(new SelfTestCommand.Key(KeyChord.parse("Ctrl+S")));
        assertEquals(3, calls.size()); assertEquals(InvokeSource.MENU, calls.get(0).args().get(2));
        assertEquals(InvokeSource.TOOLBAR, calls.get(1).args().get(2)); assertEquals("key", calls.get(2).name());
        assertEquals(FocusScope.TABLE, calls.get(2).args().get(1));
        assertThrows(IllegalStateException.class, () -> driver.execute(new SelfTestCommand.Menu("file.openFile")));
        assertEquals(3, calls.size());
    }
    @Test void previewContextUsesOwnerFormSourceThroughModalGate() {
        var port = port(); open(port, "w1", new Logic(), new Host()); var driver = new ModelUiDriver(environment(), port, intents());
        driver.execute(new SelfTestCommand.Context("preview:last:0")); driver.execute(new SelfTestCommand.Menu("ctx.preview.adjust"));
        assertEquals("contextMenu", calls.getFirst().name()); assertEquals("command", calls.getLast().name());
        assertEquals(InvokeSource.FORM, calls.getLast().args().get(2));
        assertEquals("w1", ((CommandArgs) calls.getLast().args().get(1)).key());
    }
    @Test void latestFilterUsesNextQueueTurnAndSpinnerKeepsItsOwnDelay() {
        var port = port(); var driver = new ModelUiDriver(environment(), port, intents());
        driver.execute(new SelfTestCommand.FilterType("old")); driver.execute(new SelfTestCommand.FilterType("new"));
        assertTrue(calls.isEmpty()); driver.advance(Duration.ZERO);
        assertEquals(List.of("new"), calls.getFirst().args());
        driver.execute(new SelfTestCommand.SpinnerSet("whatIf.extra", 100)); driver.execute(new SelfTestCommand.SpinnerSet("whatIf.extra", 200));
        driver.advance(Duration.ofMillis(599)); assertEquals(1, calls.size()); driver.advance(Duration.ofMillis(1));
        assertEquals("spinnerCommit", calls.getLast().name()); assertEquals(200L, calls.getLast().args().get(1));
    }
    @Test void sliderRangeAndGeometryUseFrozenIntents() {
        var port = port(); var driver = new ModelUiDriver(environment(), port, intents());
        assertThrows(IllegalStateException.class, () -> driver.execute(new SelfTestCommand.SliderSet("view.horizonSlider", 121)));
        driver.execute(new SelfTestCommand.SliderSet("view.horizonSlider", 24)); driver.execute(new SelfTestCommand.Size(1100, 700));
        assertEquals("sliderCommit", calls.getFirst().name()); assertEquals(24, calls.getFirst().args().get(1));
        assertEquals(1100, port.mainGeometry().bounds().width()); assertEquals(700, port.mainGeometry().bounds().height());
        assertEquals("mainGeometry", calls.getLast().name());
    }
    @Test void restoredMainGeometryIsCapturedFromPort() {
        var port = port(); var restored = new MainWindowState(new WindowBounds(40, 60, 1100, 720), true, "TABLE", "", "M12", Map.of(), "", "", "");
        port.showMain(port.screen, restored);
        assertEquals(restored.bounds(), port.mainGeometry().bounds()); assertTrue(port.mainGeometry().maximized());
        port.showMain(port.screen, new MainWindowState(new WindowBounds(0, 0, 100, 100), false, "TABLE", "", "M12", Map.of(), "", "", ""));
        assertEquals(restored.bounds(), port.mainGeometry().bounds());
    }
    @Test void chooserAnswersOnceAndCannotWriteOutsideCashMemory() {
        var port = port(); var driver = new ModelUiDriver(environment(), port, intents()); AtomicInteger results = new AtomicInteger();
        driver.execute(new SelfTestCommand.Chooser(Path.of("test.md")));
        var spec = new FileChooserSpec(FileChooserSpec.Purpose.SAVE_PLAN_AS, FileChooserSpec.Mode.SAVE, "Save", "md", List.of("md"), temporary, "");
        port.chooseFile(spec, path -> { assertEquals(Optional.of(environment().cashMemory().resolve("test.md")), path); results.incrementAndGet(); });
        assertEquals(1, results.get()); assertNull(port.chooser);
        assertThrows(IllegalStateException.class, () -> driver.execute(new SelfTestCommand.Chooser(temporary.resolve("outside.md"))));
        driver.execute(new SelfTestCommand.Chooser(null)); port.chooseFile(spec, path -> { assertTrue(path.isEmpty()); results.incrementAndGet(); });
        assertEquals(2, results.get());
    }
}
