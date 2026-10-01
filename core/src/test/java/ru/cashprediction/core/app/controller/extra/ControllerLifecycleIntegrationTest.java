package ru.cashprediction.core.app.controller.extra;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.app.flow.CoreWindowFactory;
import ru.cashprediction.core.app.flow.SessionBridge;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.io.PlanRepository;
import ru.cashprediction.core.markdown.PlanMarkdownWriter;
import ru.cashprediction.core.model.*;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.text.UiText;

/** Вложенные окна, восстановленные результаты, безопасный запуск и подтверждения сохранения на настоящем контроллере. */
class ControllerLifecycleIntegrationTest {
    @TempDir Path home;

    private ControllerFixture fixture() {
        var fixture = new ControllerFixture(home, ClientProfile.swing(), true);
        fixture.show();
        return fixture;
    }

    private FormSession ruleEditor(ControllerFixture f) {
        f.command(CommandId.ROW_GO_TO_RULE, CommandArgs.row(f.rule().rowId()));
        var parent = f.form();
        parent.shown();
        assertEquals(WindowType.RULE_EDITOR, parent.windowType());
        assertTrue(parent.view().preview().getFirst().selectable());
        return parent;
    }

    private FormSession child(ControllerFixture f, FormSession parent) {
        f.app.command(CommandId.PREVIEW_ADJUST,
                new CommandArgs("", null, "", parent.windowId(), "0"), InvokeSource.FORM);
        var child = f.form();
        assertNotSame(parent, child);
        assertEquals(WindowType.ADJUSTMENT_EDITOR, child.windowType());
        assertEquals(parent.windowId(), child.ownerId());
        assertEquals(parent.windowId(), f.port.forms().getLast().placement().ownerId());
        return child;
    }

    @Test void previewChildAppliesAdjustmentRefreshesParentAndUndoesAsSingleStep() {
        var f = fixture();
        var before = f.app.document().plan();
        var parent = ruleEditor(f);
        f.field(parent, "title", "Unsaved parent title");
        var child = child(f, parent);
        assertTrue(child.view().fields().get("amount").enabled());
        assertFalse(child.view().fields().get("date").enabled());
        f.field(child, "amount", "12345");
        var key = new OccurrenceKey(new RuleId(child.context().contextValue(WindowType.CONTEXT_RULE_ID)),
                java.time.LocalDate.parse(child.context().contextValue(WindowType.CONTEXT_ORIGINAL_DATE)));
        child.buttonPressed("ok");
        assertTrue(child.isClosed());
        assertFalse(parent.isClosed());
        assertEquals(parent.windowId(), f.app.state().windows().topModal().orElseThrow().windowId());
        assertEquals(Money.ofMajor(12345), f.app.document().plan().findAdjustment(key).orElseThrow().action().newAmount().orElseThrow());
        assertEquals("Unsaved parent title", parent.captureState().field("title"));
        f.port.manualScheduler().advance(Duration.ofMillis(250));
        boolean markerUpdated = parent.view().preview().getFirst().text()
                .contains(UiText.get("rule.preview.adjusted").strip());
        assertEquals(before.rules(), f.app.document().plan().rules());
        parent.closeRequested();
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
        assertTrue(markerUpdated, "Saved child adjustment must immediately refresh the parent preview marker");
    }

    @Test void nestedChildBlocksAllMainSourcesAndParentPreviewUntilClosed() {
        var f = fixture();
        var parent = ruleEditor(f);
        var child = child(f, parent);
        int forms = f.port.forms().size();
        for (var source : InvokeSource.values()) {
            if (source != InvokeSource.FORM)
                f.app.command(CommandId.VIEW_CHART, CommandArgs.NONE, source);
        }
        assertFalse(f.app.key(KeyChord.parse("Ctrl+2"), FocusScope.MAIN, ""));
        f.app.filterText("blocked");
        f.app.selectRow("start");
        f.app.command(CommandId.PREVIEW_ADJUST,
                new CommandArgs("", null, "", parent.windowId(), "1"), InvokeSource.FORM);
        assertEquals(forms, f.port.forms().size());
        assertEquals(1, f.app.executedCount(CommandId.PREVIEW_ADJUST));
        assertEquals(0, f.app.executedCount(CommandId.VIEW_CHART));
        assertEquals("", f.app.state().view().filterText());
        assertEquals(ViewMode.TABLE, f.screen().mode());
        child.closeRequested();
        f.app.command(CommandId.PREVIEW_ADJUST,
                new CommandArgs("", null, "", parent.windowId(), "1"), InvokeSource.FORM);
        assertEquals(forms + 1, f.port.forms().size());
        assertEquals(parent.windowId(), f.form().ownerId());
        f.form().closeRequested();
        parent.closeRequested();
        f.command(CommandId.VIEW_CHART);
        assertEquals(ViewMode.CHART, f.screen().mode());
    }

    @Test void cancelPreviewChildDoesNotApplyDraftOrCreateHistory() {
        var f = fixture();
        var before = f.app.document().plan();
        var parent = ruleEditor(f);
        var child = child(f, parent);
        f.field(child, "amount", "123");
        child.buttonPressed("cancel");
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
        assertFalse(parent.view().preview().getFirst().text().contains(UiText.get("rule.preview.adjusted").strip()));
    }

    @Test void nonexistentFormCannotDispatchPreview() {
        var f = fixture();
        f.app.command(CommandId.PREVIEW_ADJUST,
                new CommandArgs("", null, "", "gone", "0"), InvokeSource.FORM);
        assertTrue(f.port.forms().isEmpty());
        assertEquals(0, f.app.executedCount(CommandId.PREVIEW_ADJUST));
    }

    @Test void closingParentClosesChildSessionAndHandleWithoutApplyingDraft() {
        var f = fixture();
        var before = f.app.document().plan();
        var parent = ruleEditor(f);
        var child = child(f, parent);
        child.shown();
        f.field(child, "amount", "123");
        parent.closeRequested();
        assertTrue(parent.isClosed());
        assertTrue(child.isClosed());
        assertTrue(f.port.forms().getLast().handle().closed());
        assertTrue(f.app.session(child.windowId()).isEmpty());
        assertTrue(f.app.state().windows().windows().isEmpty());
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
        f.command(CommandId.VIEW_CHART);
        assertEquals(ViewMode.CHART, f.screen().mode());
    }

    @Test void restoredDeleteAlertWaitsForShownAndAppliesUndoableDecision() {
        var f = fixture();
        var before = f.app.document().plan();
        var row = f.rule();
        f.command(CommandId.ROW_DELETE, CommandArgs.row(row.rowId()));
        var saved = f.alert().session().captureState();
        f.alert().press("cancel");
        var shown = new AtomicInteger();
        var errors = new ArrayList<String>();
        new CoreWindowFactory(f.app).open(saved, WindowState.MAIN_OWNER,
                window -> shown.incrementAndGet(), errors::add);
        assertTrue(errors.isEmpty(), errors.toString());
        var pending = f.alert();
        assertNotNull(pending.session());
        assertEquals(saved.id(), pending.session().windowId());
        assertEquals(0, shown.get());
        pending.session().shown();
        pending.session().shown();
        assertEquals(1, shown.get());
        assertEquals(before, f.app.document().plan());
        pending.press("delete");
        assertTrue(f.app.document().plan().findRule(row.ruleId()).isEmpty());
        assertFalse(f.app.state().windows().modalOpen());
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
    }

    @Test void restoredOneTimeResultAppliesAfterShownHandshakeAndIsUndoable() {
        var f = fixture();
        var before = f.app.document().plan();
        f.command(CommandId.EDIT_ADD_ONE_TIME);
        var draft = f.form();
        f.field(draft, "title", "Restored once");
        f.field(draft, "amount", "345");
        var saved = draft.captureState();
        draft.closeRequested();
        var shown = new AtomicInteger();
        var errors = new ArrayList<String>();
        new CoreWindowFactory(f.app).open(saved, WindowState.MAIN_OWNER,
                window -> shown.incrementAndGet(), errors::add);
        assertTrue(errors.isEmpty(), errors.toString());
        var restored = f.form();
        assertEquals(0, shown.get());
        assertEquals("Restored once", restored.view().fields().get("title").value());
        assertEquals(saved.fields(), restored.captureState().fields());
        restored.shown();
        restored.shown();
        assertEquals(1, shown.get());
        restored.buttonPressed("ok");
        assertEquals(before.oneTimes().size() + 1, f.app.document().plan().oneTimes().size());
        assertEquals("Restored once", f.app.document().plan().oneTimes().getLast().title());
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
        assertFalse(f.app.document().canUndo());
    }

    @Test void restoredRuleEditAppliesToExistingRuleRatherThanAddingDuplicate() {
        var f = fixture();
        var before = f.app.document().plan();
        var editor = ruleEditor(f);
        f.field(editor, "title", "Restored rule");
        var saved = editor.captureState();
        editor.closeRequested();
        var errors = new ArrayList<String>();
        new CoreWindowFactory(f.app).open(saved, WindowState.MAIN_OWNER, ignored -> { }, errors::add);
        assertTrue(errors.isEmpty(), errors.toString());
        f.form().buttonPressed("ok");
        assertEquals(before.rules().size(), f.app.document().plan().rules().size());
        assertEquals("Restored rule", f.app.document().plan().findRule(new RuleId(saved.contextValue(WindowType.CONTEXT_RULE_ID))).orElseThrow().title());
        f.command(CommandId.EDIT_UNDO);
        assertEquals(before, f.app.document().plan());
    }

    @Test void restoredMainGeometryAndWhatIfReachActualShowMainModel() {
        var f = new ControllerFixture(home, ClientProfile.swing(), true);
        var main = new MainWindowState(new WindowBounds(50, 60, 1100, 740), true, "CHART", "", "M3",
                Map.of("showIncome", false, "pastExpanded", true, "whatIfExpense", true), "rent", "", "2000,00");
        var bridge = new SessionBridge(f.app);
        bridge.applyMain(main);
        assertTrue(f.port.calls("render").isEmpty());
        bridge.showMainWindow();
        assertEquals(main, f.port.calls("showMain").getFirst().args().get(1));
        assertEquals(ViewMode.CHART, f.screen().mode());
        assertEquals("rent", f.app.state().view().filterText());
        assertFalse(f.app.state().view().showIncome());
        assertTrue(f.app.state().pastExpanded());
        assertEquals(Money.ofMajor(2000), f.app.state().view().whatIf().extraMonthlySaving());
        assertEquals(9, f.screen().summary().cards().size());
        assertFalse(f.app.document().canUndo());
    }

    private void dirty(ControllerFixture f) {
        f.command(CommandId.FILE_RENAME);
        f.field(f.form(), "value", "Dirty plan");
        f.form().buttonPressed("rename");
        assertTrue(f.app.document().isDirty());
    }

    @ParameterizedTest
    @EnumSource(value = CommandId.class, names = {"FILE_NEW", "FILE_OPEN_FILE", "FILE_SAMPLE", "FILE_EXIT"})
    void dirtyCancellationGatesDestructiveContinuation(CommandId command) {
        var f = fixture();
        dirty(f);
        var before = f.app.document().plan();
        int forms = f.port.forms().size();
        f.command(command);
        assertEquals("unsavedChanges", f.alert().spec().purpose());
        assertEquals(before, f.app.document().plan());
        assertEquals(forms, f.port.forms().size());
        assertTrue(f.port.fileRequests().isEmpty());
        assertTrue(f.port.exitKind().isEmpty());
        f.alert().press("cancel");
        assertEquals(before, f.app.document().plan());
        assertTrue(f.app.document().isDirty());
        assertEquals(forms, f.port.forms().size());
        assertTrue(f.port.fileRequests().isEmpty());
        assertTrue(f.port.exitKind().isEmpty());
    }

    @Test void dirtySaveSuccessContinuesOpeningOnlyAfterReadableFileExists() throws Exception {
        var f = fixture();
        dirty(f);
        var before = f.app.document().plan();
        f.command(CommandId.FILE_OPEN_FILE);
        assertTrue(f.port.fileRequests().isEmpty());
        f.alert().press("save");
        Path file = f.app.document().file().orElseThrow();
        assertTrue(Files.isRegularFile(file));
        assertEquals(before, new PlanRepository(file.getParent()).load(file, ControllerFixture.TODAY).plan());
        assertFalse(f.app.document().isDirty());
        assertEquals(1, f.port.fileRequests().size());
        assertEquals(FileChooserSpec.Purpose.OPEN_PLAN, f.port.fileRequests().getFirst().purpose());
    }

    @Test void dirtySaveFailureKeepsPlanAndNeverContinuesOpen() throws Exception {
        var f = fixture();
        dirty(f);
        var before = f.app.document().plan();
        Files.createFile(home.resolve("CashMemory"));
        f.command(CommandId.FILE_OPEN_FILE);
        f.alert().press("save");
        assertEquals(before, f.app.document().plan());
        assertTrue(f.app.document().isDirty());
        assertTrue(f.port.fileRequests().isEmpty());
        assertTrue(f.port.exitKind().isEmpty());
        assertFalse(f.alert().spec().content().isBlank());
    }

    @Test void firstSaveOverwriteCancellationDoesNotContinueDiscardAction() throws Exception {
        var f = fixture();
        dirty(f);
        var before = f.app.document().plan();
        Path target = home.resolve("CashMemory/Dirty plan.md");
        Files.createDirectories(target.getParent());
        String existing = PlanMarkdownWriter.write(before.withName("Existing"));
        Files.writeString(target, existing);
        f.command(CommandId.FILE_OPEN_FILE);
        f.alert().press("save");
        assertEquals("overwriteOnFirstSave", f.alert().spec().purpose());
        f.alert().press("cancel");
        assertEquals(existing, Files.readString(target));
        assertEquals(before, f.app.document().plan());
        assertTrue(f.app.document().isDirty());
        assertTrue(f.port.fileRequests().isEmpty());
    }

    @Test void dirtyDontSaveContinuesSampleWithoutWritingDiscardedPlan() {
        var f = fixture();
        dirty(f);
        f.command(CommandId.FILE_SAMPLE);
        f.alert().press("dontSave");
        assertNotEquals("Dirty plan", f.app.document().plan().name());
        assertTrue(f.app.document().isDirty());
        assertFalse(Files.exists(home.resolve("CashMemory")));
        assertFalse(f.app.document().canUndo());
        assertEquals(UiText.get("main.title.dirty", f.app.document().plan().name()), f.screen().windowTitle());
    }

    @Test void cleanExitClosesModelessFormAndWritesOnlyInsideTemporaryMemory() throws Exception {
        var f = fixture();
        f.command(CommandId.TOOLS_GOAL);
        var goal = f.form();
        f.app.closeMainRequested();
        assertEquals(ExitKind.CLEAN, f.port.exitKind().orElseThrow());
        assertEquals(0, f.port.exitCode());
        assertTrue(goal.isClosed());
        assertTrue(f.app.state().windows().windows().isEmpty());
        try (var files = Files.walk(home)) {
            assertTrue(files.filter(Files::isRegularFile).allMatch(file -> file.startsWith(home.resolve("CashMemory"))));
        }
    }

    @Test void firstStartupShowsWizardAndWritesNoPlanOutsideIsolatedMemory() throws Exception {
        var f = new ControllerFixture(home, ClientProfile.swing(), false);
        assertFalse(Files.exists(home.resolve("CashMemory")));
        f.app.start();
        assertEquals(1, f.port.calls("showMain").size());
        assertEquals(WindowType.NEW_PLAN_WIZARD, f.form().windowType());
        assertTrue(f.app.state().windows().modalOpen());
        assertNotNull(f.app.recorder());
        assertEquals(RecorderStatus.RECORDING, f.app.state().recorder());
        assertTrue(f.app.document().file().isEmpty());
        try (var files = Files.walk(home)) {
            var written = files.filter(Files::isRegularFile).toList();
            assertTrue(written.stream().allMatch(file -> file.startsWith(home.resolve("CashMemory"))));
            assertTrue(written.stream().noneMatch(file -> file.getFileName().toString().endsWith(".md")));
        }
        f.form().closeRequested();
        f.app.closeMainRequested();
        assertEquals(ExitKind.CLEAN, f.port.exitKind().orElseThrow());
    }

    @Test void externalReloadDuringSaveDoesNotContinueOpeningAnotherPlan() throws Exception {
        var f = fixture();
        f.command(CommandId.FILE_SAVE);
        Path file = f.app.document().file().orElseThrow();
        f.command(CommandId.EDIT_ADD_ONE_TIME);
        f.field(f.form(), "title", "Pending");
        f.field(f.form(), "amount", "321");
        f.form().buttonPressed("ok");
        // Имя прочитанного плана определяется именем файла; проверяется изменённое содержимое.
        var external = SamplePlan.create(ControllerFixture.TODAY)
                .withStart(ControllerFixture.TODAY, Money.ofMajor(654321));
        Files.writeString(file, PlanMarkdownWriter.write(external));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(file).toMillis() + 10_000));
        f.command(CommandId.FILE_OPEN_FILE);
        f.alert().press("save");
        assertEquals("externalChange", f.alert().spec().purpose());
        f.alert().press("reload");
        assertEquals(external, f.app.document().plan());
        assertFalse(f.app.document().isDirty());
        assertTrue(f.port.fileRequests().isEmpty());
        assertEquals(UiText.get("main.title", external.name()), f.screen().windowTitle());
    }

    @Test void startupSnapshotCommandCapturesOpenFormOnlyAfterShown() throws Exception {
        var f = new ControllerFixture(home, ClientProfile.swing(), false);
        f.app.start();
        var wizard = f.form();
        wizard.closeRequested();
        f.command(CommandId.EDIT_ADD_ONE_TIME);
        var form = f.form();
        f.field(form, "title", "Snapshot draft");
        assertTrue(f.app.recorder().registeredWindows().isEmpty());
        form.shown();
        assertEquals(1, f.app.recorder().registeredWindows().size());
        form.closeRequested();
        try {
            assertAll(
                    () -> assertDoesNotThrow(() -> f.command(CommandId.RECOVERY_SNAPSHOT_NOW)),
                    () -> {
                        var snapshot = f.app.environment().xmlStore("swing").load().orElseThrow();
                        assertTrue(snapshot.windows().isEmpty());
                        assertEquals(f.app.document().plan().name(),
                                ru.cashprediction.core.markdown.PlanMarkdownReader.read(snapshot.plan().markdown(),
                                        "Snapshot", ControllerFixture.TODAY).plan().name());
                        assertTrue(Files.isRegularFile(home.resolve("CashMemory/session-swing.xml")));
                    });
        } finally {
            f.app.closeMainRequested();
        }
        assertEquals(ExitKind.CLEAN, f.port.exitKind().orElseThrow());
    }

    @Test void clearSnapshotsConfirmationClearsStoreAndShowsLocalizedStatus() throws Exception {
        var f = new ControllerFixture(home, ClientProfile.swing(), false);
        f.app.start();
        f.form().closeRequested();
        f.app.recorder().saveNow();
        assertTrue(f.app.environment().xmlStore("swing").load().isPresent());
        f.command(CommandId.RECOVERY_CLEAR);
        assertEquals("clearSnapshots", f.alert().spec().purpose());
        assertTrue(f.app.environment().xmlStore("swing").load().isPresent());
        try {
            assertAll(
                    () -> assertDoesNotThrow(() -> f.alert().press("clear")),
                    () -> assertTrue(f.app.environment().xmlStore("swing").load().isEmpty()),
                    () -> assertFalse(f.app.state().windows().modalOpen()));
        } finally {
            f.app.closeMainRequested();
        }
    }
}
