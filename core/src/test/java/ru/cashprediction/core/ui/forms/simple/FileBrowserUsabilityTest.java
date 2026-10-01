package ru.cashprediction.core.ui.forms.simple;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.DirectoryChooserSpec;
import ru.cashprediction.core.app.FileChooserSpec;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.fake.FakeStates;
import ru.cashprediction.core.app.fake.FakeWindowHandle;
import ru.cashprediction.core.io.FolderListing;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;
import ru.cashprediction.core.ui.form.FieldSpec;
import ru.cashprediction.core.ui.form.FormContext;
import ru.cashprediction.core.ui.form.FormLogic;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormRow;
import ru.cashprediction.core.ui.form.FormSession;
import ru.cashprediction.core.ui.form.FormSpec;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.form.FormView;
import ru.cashprediction.core.ui.form.Problem;
import ru.cashprediction.core.ui.text.UiText;

/** Проверяет удобство серверного выбора файлов (§6.21) только во временных папках. */
class FileBrowserUsabilityTest {
    @TempDir Path folder;

    @Test void committedRootSelectionUsesListedPathsAndClearsFileSelection() {
        for (FileBrowserForm form : List.of(fileForm(false, 10), fileForm(true, 10),
                new FileBrowserForm(new DirectoryChooserSpec("", folder), new FolderListing(folder)))) {
            for (FolderListing.Root root : form.listing().roots()) {
                FormState state = state(form, Map.of("root", root.path().toString(), "value", "old.md"));
                var outcome = assertInstanceOf(FormOutcome.SetFields.class,
                        form.onFieldChanged("root", true, state, null).orElseThrow());
                assertEquals(Map.of("root", root.path().toString(), "path", root.path().toString(), "value", ""), outcome.values());
                assertFalse(outcome.values().containsKey("name"));
            }
        }
    }

    @Test void rootSelectionRejectsIndicesMalformedPathsAndUnlistedFolders() {
        FileBrowserForm form = fileForm(false, 10);
        for (String value : invalidRoots()) {
            FormState state = state(form, Map.of("root", value, "value", "old.md"));
            var outcome = assertInstanceOf(FormOutcome.Stay.class,
                    form.onFieldChanged("root", true, state, null).orElseThrow());
            assertEquals(Problem.error(UiText.get("dialog.file.notFound", value)), outcome.problem());
        }
    }

    @Test void uncommittedRootsAndOtherFieldChangesDoNotNavigate() {
        FileBrowserForm form = fileForm(false, 10);
        FormState state = state(form, Map.of("root", form.listing().roots().getFirst().path().toString()));
        assertTrue(form.onFieldChanged("root", false, state, null).isEmpty());
        for (String field : List.of("path", "value", "name", "up", "unknown")) {
            assertTrue(form.onFieldChanged(field, true, state, null).isEmpty());
        }
    }

    @Test void sessionAppliesCommittedRootBeforeEvaluationAndPushesUpdatedModel() {
        FileBrowserForm form = fileForm(true, 10);
        List<FormState> evaluated = new ArrayList<>();
        FormSession session = navigationSession(form, evaluated);
        FakeWindowHandle handle = new FakeWindowHandle("browser", null);
        session.attach(handle);
        session.fieldChanged("value", "old.md", true, 1);
        session.fieldChanged("name", "custom.MD", true, 2);
        assertEquals(Problem.Severity.ERROR, session.fieldChanged("root", "not-a-root", true, 3).problem().severity());
        String root = form.listing().roots().getFirst().path().toString();
        int before = evaluated.size();
        var view = session.fieldChanged("root", root, true, 4);
        assertEquals(before + 1, evaluated.size());
        assertEquals(root, evaluated.getLast().value("path"));
        assertEquals("", evaluated.getLast().value("value"));
        assertEquals(root, session.state().value("root"));
        assertEquals(root, session.state().value("path"));
        assertEquals("", session.state().value("value"));
        assertEquals("custom.MD", session.state().value("name"));
        assertEquals(root, view.fields().get("path").value());
        assertEquals("", view.fields().get("value").value());
        assertEquals("custom.MD", view.fields().get("name").value());
        assertEquals(Problem.NONE, view.problem());
        assertEquals(view, handle.updates().getLast());
        assertEquals(4, session.lastClientRev());
        assertFalse(session.isClosed());
    }

    @Test void realBrowserSessionKeepsFolderAndSelectionForInvalidOrUncommittedRoot() throws Exception {
        FileBrowserForm form = fileForm(false, 10);
        Path selected = Files.createFile(folder.resolve("plan.md"));
        FormSession session = newSession(form);
        session.fieldChanged("value", selected.toString(), true, 1);
        String root = form.listing().roots().getFirst().path().toString();
        session.fieldChanged("root", root, false, 2);
        assertEquals(folder.toString(), session.state().value("path"));
        assertEquals(selected.toString(), session.state().value("value"));
        for (String invalid : invalidRoots()) {
            var view = session.fieldChanged("root", invalid, true, 3);
            assertEquals(folder.toString(), session.state().value("path"));
            assertEquals(selected.toString(), session.state().value("value"));
            assertEquals(Problem.error(UiText.get("dialog.file.notFound", invalid)), view.problem());
            assertFalse(view.buttons().get("ok").enabled());
        }
    }

    @Test void tooltipsArePartOfFieldSpecifications() {
        FileBrowserForm form = fileForm(false, 10);
        Map<String, FieldSpec> fields = new HashMap<>();
        form.spec(null).pages().getFirst().rows().stream().flatMap(row -> {
            if (row instanceof FormRow.Field field) return Stream.of(field.field());
            if (row instanceof FormRow.Inline inline) return inline.fields().stream();
            return Stream.<FieldSpec>empty();
        }).forEach(field -> fields.put(field.id(), field));
        assertEquals(UiText.get("dialog.file.path.tip"), fields.get("path").tooltip());
        assertEquals(UiText.get("dialog.file.up.tip"), fields.get("up").tooltip());
        assertEquals(UiText.get("dialog.file.cashMemory.tip"), fields.get("cashMemory").tooltip());
        assertEquals(12, fields.get("value").textRows());
    }

    @Test void truncatedListingWarnsWithoutBlockingSelection() throws Exception {
        Files.createFile(folder.resolve("a.md"));
        Files.createFile(folder.resolve("b.md"));
        FileBrowserForm form = fileForm(false, 1);
        var view = form.evaluate(state(form, Map.of()), null);
        assertEquals(Problem.Severity.WARNING, view.problem().severity());
        assertEquals(UiText.get("dialog.file.truncated", 1), view.problem().text());
        assertEquals(1, view.fields().get("value").options().size());
        String selected = view.fields().get("value").options().getFirst().value();
        FormState state = state(form, Map.of("value", selected));
        assertTrue(form.evaluate(state, null).buttons().get("ok").enabled());
        assertEquals(Path.of(selected), assertInstanceOf(FormOutcome.Close.class, form.onButton("ok", state, null)).result());
    }

    @Test void warningDisappearsAfterNavigatingToCompleteListing() throws Exception {
        Path child = Files.createDirectory(folder.resolve("child"));
        Files.createFile(folder.resolve("a.md"));
        FileBrowserForm form = fileForm(false, 1);
        assertEquals(Problem.Severity.WARNING, form.evaluate(state(form, Map.of()), null).problem().severity());
        var view = form.evaluate(state(form, Map.of("path", child.toString())), null);
        assertEquals(Problem.NONE, view.problem());
        assertEquals(UiText.get("dialog.file.empty"), view.fields().get("value").options().getFirst().text());
        assertFalse(view.buttons().get("ok").enabled());
    }

    @Test void reachingLimitExactlyDoesNotWarn() throws Exception {
        Files.createFile(folder.resolve("a.md"));
        FileBrowserForm form = fileForm(false, 1);
        assertEquals(Problem.NONE, form.evaluate(state(form, Map.of()), null).problem());
    }

    @Test void nameErrorsTakePrecedenceOverTruncation() throws Exception {
        Files.createFile(folder.resolve("a.md"));
        Files.createFile(folder.resolve("b.md"));
        FileBrowserForm form = fileForm(true, 1);
        var empty = form.evaluate(state(form, Map.of("name", "")), null);
        assertEquals(Problem.error(UiText.get("dialog.file.nameRequired")), empty.problem());
        assertFalse(empty.buttons().get("ok").enabled());
        var invalid = form.evaluate(state(form, Map.of("name", "a/b")), null);
        assertEquals(Problem.error(UiText.get("dialog.file.nameInvalid")), invalid.problem());
        var valid = form.evaluate(state(form, Map.of("name", "plan")), null);
        assertEquals(Problem.Severity.WARNING, valid.problem().severity());
        assertTrue(valid.buttons().get("ok").enabled());
    }

    @Test void badPathsStayInFormForEnterUpAndOk() {
        FileBrowserForm form = fileForm(false, 10);
        for (String path : List.of("", " ", "bad\u0000path", folder.resolve("missing").toString())) {
            FormState state = state(form, Map.of("path", path));
            assertEquals(Problem.Severity.ERROR, form.evaluate(state, null).problem().severity());
            assertFalse(form.evaluate(state, null).buttons().get("ok").enabled());
            assertFalse(form.evaluate(state, null).fields().get("up").enabled());
            assertInstanceOf(FormOutcome.Stay.class, form.onFieldSubmitted("path", state, null).orElseThrow());
            assertInstanceOf(FormOutcome.Stay.class, form.onButton("up", state, null));
            assertInstanceOf(FormOutcome.Stay.class, form.onButton("ok", state, null));
        }
    }

    @Test void submittedPathNormalizesAndClearsStaleSelection() throws Exception {
        Files.createDirectory(folder.resolve("child"));
        FileBrowserForm form = fileForm(false, 10);
        FormState state = state(form, Map.of("path", folder.resolve("child/..").toString(), "value", "old.md"));
        var outcome = assertInstanceOf(FormOutcome.SetFields.class, form.onFieldSubmitted("path", state, null).orElseThrow());
        assertEquals(folder.toString(), outcome.values().get("path"));
        assertEquals(folder.getRoot().toString(), outcome.values().get("root"));
        assertEquals("", outcome.values().get("value"));
        assertTrue(form.onFieldSubmitted("name", state, null).isEmpty());
    }

    @Test void activationHonorsBoundsAndDirectoryNavigation() throws Exception {
        Path child = Files.createDirectory(folder.resolve("child"));
        Files.createFile(folder.resolve("a.md"));
        FileBrowserForm form = fileForm(false, 10);
        FormState state = state(form, Map.of());
        for (int index : new int[] {-1, 2, Integer.MAX_VALUE}) {
            assertInstanceOf(FormOutcome.Stay.class, form.onFieldActivated("value", index, state, null));
        }
        assertInstanceOf(FormOutcome.Stay.class, form.onFieldActivated("path", 0, state, null));
        var entered = assertInstanceOf(FormOutcome.SetFields.class, form.onFieldActivated("value", 0, state, null));
        assertEquals(child.toString(), entered.values().get("path"));
        var up = assertInstanceOf(FormOutcome.SetFields.class, form.onButton("up", state(form, entered.values()), null));
        assertEquals(folder.toString(), up.values().get("path"));
    }

    @Test void openingRequiresAnExistingListedFileRatherThanFolderOrStaleValue() throws Exception {
        Path child = Files.createDirectory(folder.resolve("child"));
        Path file = Files.createFile(folder.resolve("plan.md"));
        Path filtered = Files.createFile(folder.resolve("plan.csv"));
        FileBrowserForm form = fileForm(false, 10);
        for (String value : List.of(child.toString(), filtered.toString(), "bad\u0000path", folder.resolve("missing.md").toString())) {
            FormState state = state(form, Map.of("value", value));
            assertFalse(form.evaluate(state, null).buttons().get("ok").enabled());
            assertInstanceOf(FormOutcome.Stay.class, form.onButton("ok", state, null));
        }
        FormState selected = state(form, Map.of("value", file.toString()));
        assertTrue(form.evaluate(selected, null).buttons().get("ok").enabled());
        Files.delete(file);
        assertInstanceOf(FormOutcome.Stay.class, form.onButton("ok", selected, null));
    }

    @Test void changingFolderInvalidatesSelectionFromPreviousFolder() throws Exception {
        Path file = Files.createFile(folder.resolve("plan.md"));
        Path child = Files.createDirectory(folder.resolve("child"));
        FileBrowserForm form = fileForm(false, 10);
        FormState state = state(form, Map.of("path", child.toString(), "value", file.toString()));
        assertFalse(form.evaluate(state, null).buttons().get("ok").enabled());
        assertInstanceOf(FormOutcome.Stay.class, form.onButton("ok", state, null));
        FormState filePath = state(form, Map.of("path", file.toString()));
        assertEquals(Problem.error(UiText.get("dialog.file.notFound", file.toString())), form.evaluate(filePath, null).problem());
    }

    @Test void savingSelectedFileCopiesItsNameAndPreservesExtension() throws Exception {
        Path file = Files.createFile(folder.resolve("plan.MD"));
        FileBrowserForm form = fileForm(true, 10);
        var selected = assertInstanceOf(FormOutcome.SetFields.class, form.onFieldActivated("value", 0, state(form, Map.of()), null));
        assertEquals("plan.MD", selected.values().get("name"));
        assertEquals(file, assertInstanceOf(FormOutcome.Close.class, form.onButton("ok", state(form, selected.values()), null)).result());
        assertEquals(folder.resolve("new.md"), assertInstanceOf(FormOutcome.Close.class,
                form.onButton("ok", state(form, Map.of("name", " new ")), null)).result());
    }

    @Test void savingRejectsDotSegmentsAndControlCharacters() {
        FileBrowserForm form = fileForm(true, 10);
        for (String name : List.of(".", "..", "bad\u0000name", "bad\nname", "../plan", "a:b")) {
            FormState state = state(form, Map.of("name", name));
            assertFalse(form.evaluate(state, null).buttons().get("ok").enabled());
            assertInstanceOf(FormOutcome.Stay.class, form.onButton("ok", state, null));
        }
    }

    @Test void directoryModeChoosesCurrentFolderAndCashMemoryClearsSelection() throws Exception {
        Path cashMemory = Files.createDirectory(folder.resolve("CashMemory"));
        FileBrowserForm form = new FileBrowserForm(new DirectoryChooserSpec("", folder), new FolderListing(cashMemory));
        assertTrue(form.evaluate(state(form, Map.of()), null).buttons().get("ok").enabled());
        assertEquals(folder, assertInstanceOf(FormOutcome.Close.class, form.onButton("ok", state(form, Map.of()), null)).result());
        var outcome = assertInstanceOf(FormOutcome.SetFields.class, form.onButton("cashMemory", state(form, Map.of("value", "old")), null));
        assertEquals(cashMemory.toString(), outcome.values().get("path"));
        assertEquals(cashMemory.getRoot().toString(), outcome.values().get("root"));
        assertEquals("", outcome.values().get("value"));
        assertInstanceOf(FormOutcome.Close.class, form.onButton("cancel", state(form, Map.of()), null));
    }

    private FileBrowserForm fileForm(boolean saving, int limit) {
        return new FileBrowserForm(new FileChooserSpec(FileChooserSpec.Purpose.OPEN_PLAN,
                saving ? FileChooserSpec.Mode.SAVE : FileChooserSpec.Mode.OPEN, "", "", List.of("md"), folder, "plan"),
                new FolderListing(folder.resolve("CashMemory"), ZoneOffset.UTC, limit));
    }

    private List<String> invalidRoots() {
        return List.of("", " ", "0", "-1", "999999999999999999999999", "not-a-root", "bad\u0000root", folder.toString());
    }

    private FormSession newSession(FormLogic logic) {
        FormContext context = new FormContext("browser", "main", Map.of(), FakeStates.empty(ClientProfile.web(), folder));
        return new FormSession(WindowType.CHOICE, true, logic, context, new NavigationHost());
    }

    private FormSession navigationSession(FileBrowserForm form, List<FormState> evaluated) {
        // Настоящий сеанс и обработчик формы; только чтение содержимого корня заменено наблюдением состояния.
        // Иначе успешный переход прочитал бы диск вне @TempDir. Чтение папок проверяют остальные тесты формы.
        return newSession(new FormLogic() {
            /** Использует настоящую раскладку обозревателя. */
            @Override public FormSpec spec(FormContext context) { return form.spec(context); }
            /** Использует настоящие начальные значения. */
            @Override public Map<String, String> defaults(FormContext context) { return form.defaults(context); }
            /** Запоминает состояние, дошедшее до пересчёта, без чтения диска. */
            @Override public FormView evaluate(FormState state, FormContext context) {
                evaluated.add(state);
                return new FormView(0, state.page(), "", Map.of(), Problem.NONE, Map.of(), List.of(), List.of(), "", false);
            }
            /** Передаёт событие выбора диска настоящей форме. */
            @Override public Optional<FormOutcome> onFieldChanged(String field, boolean committed, FormState state, FormContext context) {
                return form.onFieldChanged(field, committed, state, context);
            }
            /** Передаёт события кнопок настоящей форме. */
            @Override public FormOutcome onButton(String button, FormState state, FormContext context) {
                return form.onButton(button, state, context);
            }
        });
    }

    /** Контроллер тестового сеанса: навигация не должна закрывать окно или вызывать действия приложения. */
    private static final class NavigationHost implements FormSession.Host {
        /** Невосстанавливаемая форма не регистрируется. */
        @Override public void registered(FormSession session) { fail("Unexpected registration"); }
        /** Невосстанавливаемая форма не снимается с регистрации. */
        @Override public void unregistered(FormSession session) { fail("Unexpected unregistration"); }
        /** Изменение формы не требует внешних действий в тесте. */
        @Override public void touched(FormSession session) { }
        /** Навигация не завершает выбор. */
        @Override public void closed(FormSession session, Object result) { fail("Unexpected close"); }
        /** Навигация не открывает дочерних окон. */
        @Override public void openChild(FormSession parent, WindowState child) { fail("Unexpected child"); }
        /** Навигация не выполняет действий приложения. */
        @Override public void applied(FormSession session, Object action) { fail("Unexpected action"); }
    }

    private FormState state(FileBrowserForm form, Map<String, String> overrides) {
        Map<String, String> values = new HashMap<>(form.defaults(null));
        values.putAll(overrides);
        return new FormState(0, values);
    }
}
