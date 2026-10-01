package ru.cashprediction.core.app.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.app.flow.FormRequest;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.session.UiExecutor;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.ui.alert.AlertCatalog;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.forms.simple.TextInputForms;
import ru.cashprediction.core.ui.view.MainScreenModel;
import ru.cashprediction.core.ui.view.ScreenPart;
import ru.cashprediction.core.ui.view.status.StatusLevel;

/** Интеграция настоящего контроллера с портом без графического инструмента и без реестра. */
class AppControllerTest {
    @TempDir Path home;

    private AppController controller(FakeUiPort port) {
        return new AppController(port, new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                home, home.resolve("CashMemory"), AppClock.fixedToday(LocalDate.of(2026, 10, 1))));
    }

    @Test void constructionAndQueriesDoNotCreateFilesOrShowUi() throws Exception {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController app = controller(port);
        assertTrue(app.state().document().forecastAvailable());
        assertFalse(Files.exists(home.resolve("CashMemory")));
        assertTrue(port.calls().isEmpty());
        assertEquals(0, app.executedCount(CommandId.FILE_NEW));
    }

    @Test void refreshBeforeShowDoesNotRenderAndShowIsExactlyOnce() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController app = controller(port);
        app.refresh();
        app.refresh();
        assertTrue(port.calls("render").isEmpty());
        app.showMain(null);
        assertEquals(1, port.calls("showMain").size());
        assertThrows(IllegalStateException.class, () -> app.showMain(null));
        int count = port.calls("render").size();
        app.refresh();
        assertEquals(count, port.calls("render").size());
    }

    @Test void statusOnlyChangePreservesLazyTableAndChartRevisions() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController app = controller(port);
        app.showMain(null);
        MainScreenModel before = port.calls("showMain").getFirst().arg(0, MainScreenModel.class);
        app.status(StatusLevel.INFO, "status.hint.noRow");
        var call = port.calls("render").getLast();
        MainScreenModel after = call.arg(0, MainScreenModel.class);
        assertSame(before.table(), after.table());
        assertSame(before.chart(), after.chart());
        assertEquals(EnumSet.of(ScreenPart.STATUS), call.args().get(1));
        app.filterText("missing");
        MainScreenModel filtered = port.calls("render").getLast().arg(0, MainScreenModel.class);
        assertTrue(filtered.table().revision() > before.table().revision());
        assertEquals("", app.tableTooltip(before.table().revision(), 0, "date"));
        assertTrue(app.chartHover(before.chart().revision(), 30, 30, 800, 600).isEmpty());
    }

    @Test void modalFormBlocksMainCommandsUntilClosed() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController app = controller(port);
        app.showMain(null);
        FormSession form = rename(app, ignored -> { });
        app.command(CommandId.VIEW_CHART, CommandArgs.NONE, InvokeSource.MENU);
        assertEquals(0, app.executedCount(CommandId.VIEW_CHART));
        app.filterText("blocked");
        assertEquals("", app.state().view().filterText());
        form.closeRequested();
        assertFalse(app.state().windows().modalOpen());
        app.command(CommandId.VIEW_CHART, CommandArgs.NONE, InvokeSource.MENU);
        assertEquals(1, app.executedCount(CommandId.VIEW_CHART));
        assertEquals(ru.cashprediction.core.document.ViewMode.CHART, app.state().view().mode());
    }

    @Test void formResultAppliesOnceAndFailedApplyKeepsEditorOpen() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController app = controller(port);
        AtomicInteger count = new AtomicInteger();
        FormSession form = rename(app, value -> {
            if (value != null) {
                count.incrementAndGet();
                throw new IllegalArgumentException("rejected");
            }
        });
        form.apply(new FormOutcome.Close("changed"));
        assertFalse(form.isClosed());
        assertTrue(app.session(form.windowId()).isPresent());
        assertEquals(1, count.get());
        form.closeRequested();
        assertTrue(app.session(form.windowId()).isEmpty());
    }

    @Test void formShownHandshakeWaitsForActualClientEventAndSupportsLateSubscriber() {
        AppController app = controller(new FakeUiPort(ClientProfile.swing()));
        FormSession form = rename(app, ignored -> { });
        AtomicInteger shown = new AtomicInteger();
        form.whenShown(ignored -> shown.incrementAndGet());
        assertEquals(0, shown.get());
        form.shown();
        form.shown();
        assertEquals(1, shown.get());
        form.whenShown(ignored -> shown.incrementAndGet());
        assertEquals(2, shown.get());
    }

    @Test void alertResponseRemovesModalBeforeContinuation() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController app = controller(port);
        AtomicInteger responses = new AtomicInteger();
        app.showAlert(AlertCatalog.alreadyRunning(port.profile()), button -> {
            assertFalse(app.state().windows().modalOpen());
            responses.incrementAndGet();
        });
        assertTrue(app.state().windows().modalOpen());
        var pending = port.pendingAlerts().getFirst();
        pending.press(pending.spec().buttons().getFirst().id());
        assertEquals(1, responses.get());
    }

    @Test void disabledHotkeyDoesNotCountAsExecuted() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController app = controller(port);
        app.showMain(null);
        assertTrue(app.key(KeyChord.parse("Ctrl+Z"), FocusScope.MAIN, ""));
        assertEquals(0, app.executedCount(CommandId.EDIT_UNDO));
        assertTrue(app.state().status().visible(app.environment().clock().now()).isPresent());
    }

    @Test void everyIntentRejectsCallsOutsideControllerThreadBeforeAnyPortOperation() {
        FakeUiPort delegate = new FakeUiPort(ClientProfile.swing());
        UiExecutor wrongThread = new UiExecutor() {
            @Override public void execute(Runnable task) { throw new AssertionError("unexpected dispatch"); }
            @Override public boolean isUiThread() { return false; }
        };
        UiPort guarded = (UiPort) java.lang.reflect.Proxy.newProxyInstance(UiPort.class.getClassLoader(),
                new Class<?>[] { UiPort.class }, (proxy, method, args) -> method.getName().equals("executor")
                        ? wrongThread : method.invoke(delegate, args));
        AppController app = new AppController(guarded, new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                home, home.resolve("CashMemory"), AppClock.fixedToday(LocalDate.of(2026, 10, 1))));
        java.util.List<Runnable> actions = java.util.List.of(
                () -> app.command(CommandId.FILE_NEW, CommandArgs.NONE, InvokeSource.MENU),
                () -> app.key(KeyChord.parse("Ctrl+S"), FocusScope.MAIN, ""),
                () -> app.selectRow("start"), () -> app.activateRow("start", "title", Activation.CLICK),
                () -> app.filterText("x"), () -> app.sliderCommit("view.horizonSlider", 12),
                () -> app.spinnerCommit("whatIf.extra", 100), () -> app.mainGeometry(null, false),
                () -> app.menuHover(null), app::closeMainRequested,
                () -> app.uncaught(Thread.currentThread(), new RuntimeException("test")),
                () -> app.contextMenu(new ru.cashprediction.core.ui.menu.ContextTarget.Row("start")),
                () -> app.tableTooltip(0, 0, "date"), () -> app.chartScene(800, 600),
                () -> app.chartHover(0, 30, 30, 800, 600), () -> app.dayCard(LocalDate.of(2026, 10, 1)),
                () -> app.sparkline("now"), () -> app.calendar(java.time.YearMonth.of(2026, 10), null));
        for (Runnable action : actions) assertThrows(IllegalStateException.class, action::run);
        assertTrue(delegate.calls().isEmpty());
    }

    @Test void continuationWindowDoesNotRemainOwnedByClosingForm() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController app = controller(port);
        FormSession first = rename(app, value -> {
            if (value != null) rename(app, ignored -> { });
        });
        first.apply(new FormOutcome.Close("next"));
        FormSession next = port.forms().getLast().session();
        assertTrue(first.isClosed());
        assertFalse(next.isClosed());
        assertEquals(WindowState.MAIN_OWNER, next.ownerId());
        assertTrue(app.session(next.windowId()).isPresent());
        assertEquals(1, app.state().windows().windows().size());
    }

    @Test void parentCloseAlsoClosesChildWindowAndRemovesBothSessions() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController app = controller(port);
        FormSession parent = rename(app, ignored -> { });
        FormSession child = rename(app, ignored -> { });
        assertEquals(parent.windowId(), child.ownerId());
        parent.closeRequested();
        assertTrue(app.state().windows().windows().isEmpty());
        assertTrue(app.session(parent.windowId()).isEmpty());
        assertTrue(app.session(child.windowId()).isEmpty());
        assertTrue(child.isClosed());
        assertTrue(port.forms().getLast().handle().closed());
    }

    @Test void restoredAlertPreservesIdentityAndAcknowledgesOnlyRealShown() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController app = controller(port);
        var spec = AlertCatalog.clearSnapshots();
        WindowState saved = new WindowState("w73", WindowType.ALERT, true, "w72",
                new ru.cashprediction.core.session.WindowBounds(40, 60, 560, 300),
                Map.of(WindowType.CONTEXT_PURPOSE, "clearSnapshots", WindowType.CONTEXT_TARGET_ID, ""), Map.of());
        AtomicInteger shown = new AtomicInteger();
        AtomicInteger decisions = new AtomicInteger();
        app.showRestoredAlert(spec, saved, session -> {
            assertEquals("w73", session.windowId());
            assertEquals("w72", session.ownerId());
            shown.incrementAndGet();
        }, button -> decisions.incrementAndGet());
        assertEquals(0, shown.get());
        var pending = port.pendingAlerts().getFirst();
        pending.session().shown();
        pending.session().shown();
        assertEquals(1, shown.get());
        assertEquals(saved, pending.session().captureState());
        pending.press("clear");
        assertEquals(1, decisions.get());
        assertTrue(app.state().windows().windows().isEmpty());
    }

    @Test void statusExpiresWithSchedulerAndOlderTimerCannotRemoveNewerMessage() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController app = controller(port);
        app.showMain(null);
        app.status(StatusLevel.INFO, "status.hint.noRow");
        port.manualScheduler().advance(java.time.Duration.ofSeconds(5));
        app.status(StatusLevel.INFO, "status.hint.nothingToUndo");
        port.manualScheduler().advance(java.time.Duration.ofSeconds(5));
        assertTrue(app.state().status().visible(app.environment().clock().now()).isPresent());
        port.manualScheduler().advance(java.time.Duration.ofSeconds(5));
        assertTrue(app.state().status().visible(app.environment().clock().now()).isEmpty());
    }

    private FormSession rename(AppController app, java.util.function.Consumer<Object> callback) {
        return app.openForm(FormRequest.fresh(TextInputForms.rename(), WindowType.TEXT_INPUT, true,
                Map.of(WindowType.CONTEXT_PURPOSE, TextInputForms.PURPOSE_RENAME)), null, callback);
    }

    @Test void enterOnPastHeaderTogglesWithoutAttemptingAnEdit() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController app = controller(port);
        app.document().replace(SamplePlan.create(LocalDate.of(2026, 9, 1)), null, false, java.util.List.of());
        app.showMain(null);
        var table = port.calls("showMain").getLast().arg(0, MainScreenModel.class).table();
        String header = java.util.stream.IntStream.range(0, table.rowCount()).mapToObj(table::row)
                .filter(row -> row.kind() == ru.cashprediction.core.ui.view.table.RowKind.PAST_HEADER)
                .findFirst().orElseThrow().rowId();
        app.selectRow(header);
        boolean before = app.state().pastExpanded();
        assertTrue(app.key(KeyChord.parse("Enter"), FocusScope.TABLE, header));
        assertEquals(!before, app.state().pastExpanded());
        assertEquals(0, app.executedCount(CommandId.EDIT_EDIT));
        assertEquals(1, app.executedCount(CommandId.PAST_TOGGLE));
    }

    @Test void quickEditDraftIsDiscardedOnModalOpeningAndExternalPlanChange() {
        FakeUiPort port = new FakeUiPort(ClientProfile.swing());
        AppController app = controller(port);
        app.document().replace(SamplePlan.create(app.environment().clock().today()), null, false, java.util.List.of());
        app.showMain(null);
        var table = port.calls("showMain").getLast().arg(0, MainScreenModel.class).table();
        var row = java.util.stream.IntStream.range(0, table.rowCount()).mapToObj(table::row)
                .filter(ru.cashprediction.core.ui.view.table.TableRowView::quickEditable).findFirst().orElseThrow();
        String column = row.cells().get(4).isEmpty() ? "expense" : "income";
        app.activateRow(row.rowId(), column, Activation.DOUBLE_CLICK);
        FormSession quick = port.forms().getLast().session();
        assertEquals(WindowType.QUICK_EDIT_POPUP, quick.windowType());
        FormSession modal = rename(app, ignored -> { });
        assertTrue(quick.isClosed());
        modal.closeRequested();
        app.activateRow(row.rowId(), column, Activation.DOUBLE_CLICK);
        FormSession another = port.forms().getLast().session();
        assertEquals(WindowType.QUICK_EDIT_POPUP, another.windowType());
        app.document().replace(SamplePlan.create(app.environment().clock().today().plusMonths(1)), null, false,
                java.util.List.of());
        assertTrue(another.isClosed());
        assertTrue(app.state().windows().windows().isEmpty());
    }

    @Test void shownThenFailedOpeningDoesNotRetainPhantomSnapshotOrInvokeResult() {
        FakeUiPort delegate = new FakeUiPort(ClientProfile.swing());
        var fail = new java.util.concurrent.atomic.AtomicBoolean();
        UiPort port = (UiPort) java.lang.reflect.Proxy.newProxyInstance(UiPort.class.getClassLoader(),
                new Class<?>[] { UiPort.class }, (proxy, method, args) -> {
                    if (method.getName().equals("openForm") && fail.get()) {
                        ((FormSession) args[0]).shown();
                        throw new IllegalStateException("opening failed");
                    }
                    return method.invoke(delegate, args);
                });
        AppController app = new AppController(port, new AppEnvironment(LaunchOptions.parse("--registry", "memory"),
                home, home.resolve("CashMemory"), AppClock.fixedToday(LocalDate.of(2026, 10, 1))));
        app.start();
        delegate.forms().getLast().session().closeRequested();
        AtomicInteger results = new AtomicInteger();
        fail.set(true);
        assertThrows(IllegalStateException.class, () -> rename(app, value -> results.incrementAndGet()));
        assertEquals(0, results.get());
        assertTrue(app.state().windows().windows().isEmpty());
        assertTrue(app.recorder().registeredWindows().isEmpty());
        fail.set(false);
        app.closeMainRequested();
    }
}
