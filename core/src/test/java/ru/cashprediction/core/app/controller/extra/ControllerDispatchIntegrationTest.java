package ru.cashprediction.core.app.controller.extra;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.fake.FakeUiPort;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.model.*;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.table.*;

/** Сквозные сценарии диспетчера: проверяют модели порта, результат настоящих форм и историю документа. */
class ControllerDispatchIntegrationTest {
    @TempDir Path home;

    private ControllerFixture fixture(boolean sample) {
        var fixture = new ControllerFixture(home, ClientProfile.swing(), sample);
        fixture.show();
        return fixture;
    }

    @Test void sampleCommandBuildsUnsavedForecastAndDirtyTitle() {
        var f = fixture(false);
        f.command(CommandId.FILE_SAMPLE);
        assertFalse(f.app.document().plan().rules().isEmpty());
        assertTrue(f.app.document().isDirty());
        assertTrue(f.app.document().file().isEmpty());
        assertEquals(UiText.get("main.title.dirty", f.app.document().plan().name()), f.screen().windowTitle());
        assertTrue(f.screen().table().rowCount() > 1);
        assertEquals(9, f.screen().summary().cards().size());
        assertFalse(Files.exists(home.resolve("CashMemory")));
    }

    @Test void saveCommandWritesReadablePlanAndUpdatesTitleAndRecentMenu() throws Exception {
        var f = fixture(true);
        f.command(CommandId.FILE_SAVE);
        Path file = f.app.document().file().orElseThrow();
        assertTrue(file.startsWith(home.resolve("CashMemory")));
        assertEquals(f.app.document().plan(), new PlanRepository(file.getParent()).load(file, ControllerFixture.TODAY).plan());
        assertFalse(f.app.document().isDirty());
        assertEquals(UiText.get("main.title", f.app.document().plan().name()), f.screen().windowTitle());
        // JavaFX: MenuItem → Swing: JMenuItem → Web: menuitem.
        var recent = (MenuNode.Action) f.screen().menuBar().find("file.recent.0").orElseThrow();
        assertEquals(CommandId.FILE_RECENT_OPEN, recent.command());
        assertTrue(recent.enabled());
    }

    @Test void renameFormAppliesOneUndoStepAndChangesRenderedTitle() {
        var f = fixture(true);
        Plan before = f.app.document().plan();
        f.command(CommandId.FILE_RENAME);
        var form = f.form();
        f.field(form, "value", "Renamed");
        form.buttonPressed("rename");
        assertTrue(form.isClosed());
        assertEquals("Renamed", f.app.document().plan().name());
        assertEquals(UiText.get("main.title.dirty", "Renamed"), f.screen().windowTitle());
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
        f.command(CommandId.EDIT_REDO);
        assertEquals("Renamed", f.app.document().plan().name());
    }

    @ParameterizedTest
    @EnumSource(value = CommandId.class, names = {"EDIT_ADD_INCOME", "EDIT_ADD_EXPENSE"})
    void newRuleFormValidatesAndAddsActualForecastRows(CommandId command) {
        var f = fixture(false);
        Plan before = f.app.document().plan();
        f.command(command);
        var form = f.form();
        assertEquals(WindowType.RULE_EDITOR, form.windowType());
        assertFalse(form.view().buttons().get("ok").enabled());
        f.field(form, "title", "Monthly");
        f.field(form, "amount", "1234,50");
        assertTrue(form.view().buttons().get("ok").enabled());
        assertTrue(form.view().preview().stream().anyMatch(PreviewItem::selectable));
        form.buttonPressed("ok");
        var rule = f.app.document().plan().rules().getFirst();
        assertEquals(command == CommandId.EDIT_ADD_INCOME ? Kind.INCOME : Kind.EXPENSE, rule.kind());
        assertEquals(Money.parse("1234,50"), rule.amount());
        assertEquals("Monthly", f.row(RowKind.RULE).cells().get(2));
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
    }

    @ParameterizedTest
    @EnumSource(value = CommandId.class, names = {"EDIT_ADD_ONE_TIME", "ROW_ADD_ONE_TIME", "CHART_ADD_ONE_TIME"})
    void oneTimeAliasesUseRequestedDateAndApplyRealResult(CommandId command) {
        var f = fixture(false);
        var date = ControllerFixture.TODAY.plusDays(4);
        f.command(command, new CommandArgs("", date, "", "", ""));
        var form = f.form();
        assertEquals(date.toString(), form.captureState().field("date"));
        f.field(form, "title", "Once");
        f.field(form, "amount", "75");
        form.buttonPressed("ok");
        var tx = f.app.document().plan().oneTimes().getFirst();
        assertEquals(date, tx.date());
        assertEquals(Kind.EXPENSE, tx.kind());
        assertEquals("Once", f.row(RowKind.ONE_TIME).cells().get(2));
        f.command(CommandId.EDIT_UNDO);
        assertTrue(f.app.document().plan().oneTimes().isEmpty());
    }

    @Test void invalidOperationDoesNotChangePlanOrUndoAndCanBeCorrected() {
        var f = fixture(false);
        var before = f.app.document().plan();
        f.command(CommandId.EDIT_ADD_ONE_TIME);
        var form = f.form();
        f.field(form, "title", "Once");
        f.field(form, "amount", "broken");
        form.buttonPressed("ok");
        assertEquals(Problem.Severity.ERROR, form.view().problem().severity());
        assertFalse(form.isClosed());
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
        f.field(form, "amount", "100");
        form.buttonPressed("ok");
        assertTrue(form.isClosed());
        assertEquals(1, f.app.document().plan().oneTimes().size());
    }

    @Test void selectedRuleEditUsesSelectionAndAppliesOnlyOneUndo() {
        var f = fixture(true);
        var row = f.rule();
        var before = f.app.document().plan();
        f.app.selectRow(row.rowId());
        f.command(CommandId.EDIT_EDIT);
        assertEquals(row.ruleId().value(), f.form().context().contextValue(WindowType.CONTEXT_RULE_ID));
        f.field(f.form(), "title", "Updated");
        f.form().buttonPressed("ok");
        assertEquals("Updated", f.app.document().plan().findRule(row.ruleId()).orElseThrow().title());
        assertEquals("Updated", f.screen().table().row(f.screen().table().indexOf(row.rowId())).cells().get(2));
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
    }

    @Test void startRowEditOpensPlanSettingsAndAppliesBalance() {
        var f = fixture(true);
        var before = f.app.document().plan();
        f.command(CommandId.ROW_EDIT, CommandArgs.row(f.row(RowKind.START).rowId()));
        assertEquals(WindowType.PLAN_SETTINGS, f.form().windowType());
        f.field(f.form(), "startBalance", "4321");
        f.form().buttonPressed("ok");
        assertEquals(Money.ofMajor(4321), f.app.document().plan().startBalance());
        assertTrue(f.row(RowKind.START).cells().get(6).contains("4 321"));
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
    }

    @Test void rowSkipResetAndUndoUpdateTableAndAdjustment() {
        var f = fixture(true);
        var row = f.rule();
        f.command(CommandId.ROW_SKIP, CommandArgs.row(row.rowId()));
        assertTrue(f.app.document().plan().findAdjustment(row.occurrenceKey().orElseThrow()).orElseThrow().action() instanceof Adjustment.Skip);
        assertEquals(-1, f.screen().table().indexOf(row.rowId()));
        f.command(CommandId.VIEW_FLAG_SHOW_SKIPPED);
        assertTrue(f.screen().table().indexOf(row.rowId()) >= 0);
        f.command(CommandId.ROW_RESET, CommandArgs.row(row.rowId()));
        assertTrue(f.app.document().plan().findAdjustment(row.occurrenceKey().orElseThrow()).isEmpty());
        f.command(CommandId.EDIT_UNDO);
        assertTrue(f.app.document().plan().findAdjustment(row.occurrenceKey().orElseThrow()).isPresent());
    }

    @Test void rowDeleteWaitsForConfirmationAndUndoRestoresRule() {
        var f = fixture(true);
        var before = f.app.document().plan();
        var row = f.rule();
        f.command(CommandId.ROW_DELETE, CommandArgs.row(row.rowId()));
        assertEquals("deleteRule", f.alert().spec().purpose());
        assertNotNull(f.alert().session());
        assertEquals(before, f.app.document().plan());
        f.alert().press("delete");
        assertTrue(f.app.document().plan().findRule(row.ruleId()).isEmpty());
        assertEquals(-1, f.screen().table().indexOf(row.rowId()));
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
    }

    @Test void rowDisableRemovesAllOccurrencesAndUndoRestoresThem() {
        var f = fixture(true);
        var row = f.rule();
        var before = f.app.document().plan();
        f.command(CommandId.ROW_DISABLE_RULE, CommandArgs.row(row.rowId()));
        assertFalse(f.app.document().plan().findRule(row.ruleId()).orElseThrow().enabled());
        assertEquals(-1, f.screen().table().indexOf(row.rowId()));
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
        assertTrue(f.screen().table().indexOf(row.rowId()) >= 0);
    }

    @Test void quickEditAppliesAmountAndPreservesOneUndoStep() {
        var f = fixture(true);
        var row = f.rule();
        var before = f.app.document().plan();
        String column = row.kind() == Kind.INCOME ? "income" : "expense";
        f.command(CommandId.ROW_QUICK_EDIT, new CommandArgs(row.rowId(), null, "", column, ""));
        assertEquals(WindowType.QUICK_EDIT_POPUP, f.form().windowType());
        assertFalse(f.form().modal());
        f.field(f.form(), "amount", "9876");
        f.form().buttonPressed("ok");
        var adjustment = f.app.document().plan().findAdjustment(row.occurrenceKey().orElseThrow()).orElseThrow();
        assertEquals(Money.ofMajor(9876), adjustment.action().newAmount().orElseThrow());
        assertTrue(f.screen().table().row(f.screen().table().indexOf(row.rowId())).cells().get(column.equals("income") ? 4 : 5).contains("9 876"));
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
    }

    @Test void copiedRowUsesCurrentTableCells() {
        var f = fixture(true);
        var row = f.row(RowKind.RULE);
        f.command(CommandId.ROW_COPY, CommandArgs.row(row.rowId()));
        assertEquals(String.join("\t", row.cells()), f.port.clipboard());
    }

    @Test void copiedTotalUsesDisplayedAmounts() {
        var f = fixture(true);
        var row = f.row(RowKind.MONTH_TOTAL);
        f.command(CommandId.TOTAL_COPY, CommandArgs.row(row.rowId()));
        assertTrue(f.port.clipboard().endsWith(String.join("\t", row.cells().subList(4, 7))));
        assertEquals(4, f.port.clipboard().split("\t", -1).length);
    }

    @Test void chartAndTableCommandsChangeRenderedModeWithoutDirtyingPlan() {
        var f = fixture(true);
        f.command(CommandId.VIEW_CHART);
        assertEquals(ViewMode.CHART, f.screen().mode());
        assertFalse(f.app.document().isDirty());
        f.command(CommandId.VIEW_TABLE);
        assertEquals(ViewMode.TABLE, f.screen().mode());
        assertFalse(f.app.document().canUndo());
    }

    @Test void incomeFilterUpdatesCheckAndActualTableRows() {
        var f = fixture(true);
        var income = f.app.document().forecast().rows().stream().filter(row -> row.origin() == ru.cashprediction.core.forecast.Origin.RULE && row.kind() == Kind.INCOME).findFirst().orElseThrow();
        assertTrue(f.screen().table().indexOf(income.rowId()) >= 0);
        f.command(CommandId.VIEW_FLAG_SHOW_INCOME);
        assertEquals(-1, f.screen().table().indexOf(income.rowId()));
        // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: menuitemcheckbox.
        assertFalse(((MenuNode.Check) f.screen().menuBar().find("view.flag.showIncome").orElseThrow()).checked());
        assertFalse(f.app.document().isDirty());
    }

    @Test void filterClearRestoresRowsAndFilterFocusDispatchesPortFocus() {
        var f = fixture(true);
        int count = f.screen().table().rowCount();
        f.app.filterText("UnmatchableValue");
        f.port.manualScheduler().advance(Duration.ofMillis(300));
        assertTrue(f.screen().table().rowCount() < count);
        assertEquals(Placeholder.Kind.FILTERED, f.screen().table().placeholder().kind());
        f.command(CommandId.FILTER_CLEAR);
        assertEquals(count, f.screen().table().rowCount());
        f.command(CommandId.VIEW_FOCUS_FILTER);
        assertEquals(FocusTarget.FILTER, f.port.calls("focus").getLast().args().getFirst());
        f.command(CommandId.FILTER_FOCUS_TABLE);
        assertEquals(FocusTarget.TABLE, f.port.calls("focus").getLast().args().getFirst());
    }

    @Test void matchingFilterKeepsStartRowAndOnlyMatchingOperations() {
        var f = fixture(true);
        var row = f.rule();
        f.app.filterText(row.title());
        f.port.manualScheduler().advance(Duration.ofMillis(300));
        assertEquals(RowKind.START, f.screen().table().row(0).kind());
        assertTrue(f.screen().table().indexOf(row.rowId()) >= 0);
        for (int index = 0; index < f.screen().table().rowCount(); index++) {
            var shown = f.screen().table().row(index);
            if (shown.kind() == RowKind.RULE || shown.kind() == RowKind.ONE_TIME)
                assertEquals(row.title(), shown.cells().get(2));
        }
    }

    @Test void horizonSliderIsUndoableWhilePeriodOnlyChangesView() {
        var f = fixture(true);
        var before = f.app.document().plan();
        f.command(CommandId.VIEW_PERIOD_M3);
        assertFalse(f.app.document().isDirty());
        assertFalse(f.app.document().canUndo());
        f.app.sliderCommit("view.horizonSlider", 24);
        assertEquals(before.startDate().plusMonths(24).minusDays(1), f.app.document().plan().endDate());
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
    }

    @Test void customHorizonFormAppliesResultAndUndo() {
        var f = fixture(true);
        var before = f.app.document().plan();
        f.command(CommandId.VIEW_HORIZON_MONTHS);
        f.field(f.form(), "value", "37");
        f.form().buttonPressed("apply");
        assertEquals(before.startDate().plusMonths(37).minusDays(1), f.app.document().plan().endDate());
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
    }

    @Test void whatIfChangesForecastAndChecksButNotPlanThenResetCancelsPendingExtra() {
        var f = fixture(true);
        var before = f.app.document().plan();
        var balance = f.app.document().forecast().balanceAt(before.endDate());
        f.command(CommandId.WHAT_IF_INCOME);
        assertNotEquals(balance, f.app.document().forecast().balanceAt(before.endDate()));
        assertEquals(before, f.app.document().plan());
        // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: menuitemcheckbox.
        assertTrue(((MenuNode.Check) f.screen().menuBar().find("whatIf.income").orElseThrow()).checked());
        f.app.spinnerCommit("whatIf.extra", 3000);
        f.command(CommandId.WHAT_IF_RESET);
        f.port.manualScheduler().advance(Duration.ofMillis(600));
        assertTrue(f.app.state().view().whatIf().isNone());
        assertEquals(balance, f.app.document().forecast().balanceAt(before.endDate()));
        assertFalse(f.app.document().canUndo());
    }

    @Test void whatIfApplyWaitsForConfirmationAndCreatesSingleUndo() {
        var f = fixture(true);
        var before = f.app.document().plan();
        f.command(CommandId.WHAT_IF_EXPENSE);
        f.command(CommandId.WHAT_IF_APPLY);
        assertEquals(before, f.app.document().plan());
        assertEquals("applyWhatIf", f.alert().spec().purpose());
        f.alert().press("apply");
        assertNotEquals(before, f.app.document().plan());
        assertTrue(f.app.state().view().whatIf().isNone());
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
    }

    @Test void currencyChoiceAppliesResultAndUpdatesSummary() {
        var f = fixture(true);
        var before = f.app.document().plan();
        f.command(CommandId.TOOLS_CURRENCY);
        f.field(f.form(), "value", "BYN");
        f.form().buttonPressed("choose");
        assertEquals("BYN", f.app.document().plan().currency());
        assertTrue(f.screen().summary().cards().getFirst().value().contains("BYN"));
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
    }

    @Test void goalCalculatorIsSingleInstanceAndReceivesDocumentChanges() {
        var f = fixture(true);
        f.command(CommandId.TOOLS_GOAL);
        var goal = f.form();
        f.command(CommandId.TOOLS_GOAL);
        assertEquals(1, f.port.forms().size());
        assertEquals(1, f.port.forms().getFirst().handle().toFrontCount());
        long revision = goal.view().revision();
        f.command(CommandId.VIEW_HORIZON_SLIDER, new CommandArgs("", null, "", "", "24"));
        assertTrue(goal.view().revision() > revision);
        assertEquals(f.app.document().plan(), goal.context().app().document().plan());
    }

    @Test void cardCopyValueMatchesActualSummaryCard() {
        var f = fixture(true);
        var card = f.screen().summary().cards().getFirst();
        f.command(CommandId.CARD_COPY_VALUE, CommandArgs.card(card.id(), card.date()));
        assertEquals(card.title() + ": " + card.value(), f.port.clipboard());
    }

    @ParameterizedTest
    @EnumSource(value = CommandId.class, names = {"CARD_SHOW_IN_TABLE", "CHART_SHOW_IN_TABLE"})
    void dateNavigationRendersTableSelectsAndRevealsRow(CommandId command) {
        var f = fixture(true);
        f.command(CommandId.VIEW_CHART);
        var row = f.rule();
        f.command(command, new CommandArgs("", row.date(), "now", "", ""));
        assertEquals(ViewMode.TABLE, f.screen().mode());
        assertFalse(f.app.state().selectedRowId().isEmpty());
        assertEquals(f.app.state().selectedRowId(), f.screen().table().selectedRowId());
        assertEquals(f.app.state().selectedRowId(), f.port.calls("revealRow").getLast().args().getFirst());
    }

    @ParameterizedTest
    @EnumSource(value = CommandId.class, names = {"HELP_ABOUT", "HELP_HOTKEYS", "HELP_FORMAT", "TOOLS_VALIDATE", "TOOLS_CLEANUP", "RECOVERY_SHOW_LAST"})
    void informationalDispatchProducesReadableModelAndModalGate(CommandId command) {
        var f = fixture(true);
        var before = f.app.document().plan();
        f.command(command);
        var alert = f.alert();
        assertFalse(alert.spec().windowTitle().isBlank());
        assertFalse(alert.spec().content().isBlank());
        assertFalse(alert.spec().content().contains("!s2."));
        f.command(CommandId.VIEW_CHART);
        assertEquals(ViewMode.TABLE, f.screen().mode());
        assertEquals(0, f.app.executedCount(CommandId.VIEW_CHART));
        f.dismiss();
        f.command(CommandId.VIEW_CHART);
        assertEquals(ViewMode.CHART, f.screen().mode());
        assertEquals(before, f.app.document().plan());
    }

    @Test void recoveryStoreCommandUpdatesSettingsAndRenderedRadio() {
        var f = fixture(true);
        f.command(CommandId.RECOVERY_STORE_XML);
        assertEquals(RecoveryStoreKind.XML, f.app.state().settings().recoveryStore());
        // JavaFX: RadioMenuItem → Swing: JRadioButtonMenuItem → Web: menuitemradio.
        assertTrue(((MenuNode.Radio) f.screen().menuBar().find("recovery.store.xml").orElseThrow()).selected());
        f.command(CommandId.RECOVERY_STORE_REGISTRY);
        assertEquals(RecoveryStoreKind.REGISTRY, f.app.state().settings().recoveryStore());
        assertFalse(f.app.document().isDirty());
    }

    @Test void recoveryHaltCancellationAndConfirmationHaveDifferentExitResults() {
        var f = fixture(true);
        f.command(CommandId.RECOVERY_SIMULATE_HALT);
        f.alert().press("cancel");
        assertTrue(f.port.exitKind().isEmpty());
        f.command(CommandId.RECOVERY_SIMULATE_HALT);
        f.alert().press("halt");
        assertEquals(ExitKind.HALT, f.port.exitKind().orElseThrow());
        assertEquals(3, f.port.exitCode());
    }

    @Test void uiContextMenuDispatchContainsRealRowActions() {
        var f = fixture(true);
        f.command(CommandId.UI_CONTEXT_MENU, CommandArgs.row(f.rule().rowId()));
        var call = f.port.calls("showContextMenu").getLast();
        assertEquals(new ContextTarget.Row(f.rule().rowId()), call.args().getFirst());
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: menu.
        var nodes = (List<?>) call.args().get(1);
        assertTrue(nodes.stream().anyMatch(node -> node instanceof MenuNode.Action action && action.command() == CommandId.ROW_ADJUST && action.enabled()));
        f.command(CommandId.UI_MENU_BAR);
        assertEquals(FocusTarget.MENU_BAR, f.port.calls("focus").getLast().args().getFirst());
    }

    @Test void openFileChoiceLoadsPlanAndRendersItsActualForecast() throws Exception {
        var f = fixture(false);
        var plan = SamplePlan.create(ControllerFixture.TODAY).withName("Opened");
        Path file = home.resolve("CashMemory/Opened.md");
        new PlanRepository(file.getParent()).save(plan, file);
        f.port.chooseFileResult(file);
        f.command(CommandId.FILE_OPEN_FILE);
        assertEquals(FileChooserSpec.Purpose.OPEN_PLAN, f.port.fileRequests().getFirst().purpose());
        f.port.pump();
        assertEquals(plan, f.app.document().plan());
        assertEquals(file, f.app.document().file().orElseThrow());
        assertEquals(UiText.get("main.title", "Opened"), f.screen().windowTitle());
        assertTrue(f.screen().table().rowCount() > 1);
        assertFalse(f.app.document().isDirty());
    }

    @Test void saveAsNormalizesExtensionAndRenamesWithUndo() throws Exception {
        var f = fixture(true);
        var before = f.app.document().plan();
        f.port.chooseFileResult(home.resolve("CashMemory/Copy"));
        f.command(CommandId.FILE_SAVE_AS);
        f.port.pump();
        Path target = home.resolve("CashMemory/Copy.md");
        assertEquals(target, f.app.document().file().orElseThrow());
        assertEquals("Copy", f.app.document().plan().name());
        assertEquals("Copy", new PlanRepository(target.getParent()).load(target, ControllerFixture.TODAY).plan().name());
        assertFalse(f.app.document().isDirty());
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
    }

    @Test void pngCommandExportsCurrentChartSceneAndExactPortBytes() throws Exception {
        var f = fixture(true);
        Path target = home.resolve("CashMemory/chart.png");
        f.port.chooseFileResult(target);
        f.command(CommandId.FILE_SAVE_PNG);
        var scene = f.port.calls("renderChartPng").getFirst()
                .arg(0, ru.cashprediction.core.ui.view.chart.ChartScene.class);
        assertEquals(1200, scene.width());
        assertEquals(700, scene.height());
        assertFalse(scene.primitives().isEmpty());
        f.port.pump();
        assertArrayEquals(FakeUiPort.PNG_SIGNATURE, Files.readAllBytes(target));
        assertFalse(f.app.document().isDirty());
    }

    @Test void csvFormResultExportsActualForecastWithoutChangingPlan() throws Exception {
        var f = fixture(true);
        var before = f.app.document().plan();
        Path target = home.resolve("CashMemory/export.csv");
        f.port.chooseFileResult(target);
        f.command(CommandId.FILE_EXPORT_CSV);
        assertEquals(WindowType.CSV_EXPORT, f.form().windowType());
        f.form().buttonPressed(f.form().spec().defaultButtonId());
        f.port.pump();
        assertEquals(FileChooserSpec.Purpose.EXPORT_CSV, f.port.fileRequests().getFirst().purpose());
        String csv = Files.readString(target);
        assertTrue(csv.contains(f.rule().title()));
        assertTrue(csv.contains(f.rule().date().toString()) || csv.contains(ru.cashprediction.core.ui.text.UiFormats.date(f.rule().date())));
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
    }

    @Test void goalSaveApplyKeepsWindowOpenAndCreatesOneUndo() {
        var f = fixture(true);
        var before = f.app.document().plan();
        f.command(CommandId.TOOLS_GOAL);
        var goal = f.form();
        f.field(goal, "target", "876543");
        goal.buttonPressed("saveGoal");
        assertFalse(goal.isClosed());
        assertEquals(Money.ofMajor(876543), f.app.document().plan().goal().target());
        assertFalse(goal.view().results().isEmpty());
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
        assertFalse(goal.isClosed());
    }

    @Test void pastHeaderToggleRendersHiddenEventsAndCollapsesThemAgain() {
        var f = fixture(false);
        f.app.document().replace(SamplePlan.create(ControllerFixture.TODAY.minusMonths(1)), null, false, List.of());
        var before = f.app.document().plan();
        var header = f.row(RowKind.PAST_HEADER);
        int collapsed = f.screen().table().rowCount();
        var past = f.app.document().forecast().rows().stream()
                .filter(row -> row.origin() == ru.cashprediction.core.forecast.Origin.RULE && row.date().isBefore(ControllerFixture.TODAY))
                .findFirst().orElseThrow();
        assertEquals(-1, f.screen().table().indexOf(past.rowId()));
        f.command(CommandId.PAST_TOGGLE, CommandArgs.row(header.rowId()));
        assertTrue(f.app.state().pastExpanded());
        assertTrue(f.screen().table().rowCount() > collapsed);
        assertTrue(f.screen().table().indexOf(past.rowId()) >= 0);
        f.command(CommandId.PAST_TOGGLE);
        assertEquals(collapsed, f.screen().table().rowCount());
        assertEquals(-1, f.screen().table().indexOf(past.rowId()));
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
    }
}
