package ru.cashprediction.core.app.file;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.app.DirectoryChooserSpec;
import ru.cashprediction.core.app.FileChooserSpec;
import ru.cashprediction.core.io.FolderListing;
import ru.cashprediction.core.ui.form.FormOutcome;
import ru.cashprediction.core.ui.form.FormState;
import ru.cashprediction.core.ui.forms.simple.FileBrowserForm;
import ru.cashprediction.core.ui.text.UiText;

/** Проверяет общую защиту callback выбора и повторную проверку серверной формы без GUI. */
class ProtectedFileChooserTest {
    @TempDir Path temp;

    /** Нативный и серверный пути одинаково отклоняют служебные OPEN/SAVE до подтверждения замены. */
    @TestFactory List<DynamicTest> everyProfileRejectsProtectedCallbackBeforePrompt() {
        List<DynamicTest> tests = new ArrayList<>();
        for (var profile : List.of(ClientProfile.fx("25"), ClientProfile.swing(), ClientProfile.web())) {
            for (var mode : FileChooserSpec.Mode.values()) {
                tests.add(DynamicTest.dynamicTest(profile.kind() + "-" + mode, () -> {
                    var f = new FakeFileFlowContext(temp.resolve(profile.kind() + "-" + mode)); f.profile = profile;
                    Path path = Files.writeString(f.environment.cashMemory().resolve("web-reconnect.md"), "secret-sentinel");
                    List<Optional<Path>> results = new ArrayList<>();
                    f.choosers.chooseFile(spec(f.environment.cashMemory(), mode), results::add);
                    Path selected = mode == FileChooserSpec.Mode.SAVE ? path.resolveSibling("web-reconnect") : path;
                    if (profile.kind() == ru.cashprediction.core.app.ClientKind.WEB) f.formResult(selected);
                    else f.fileAnswer.accept(Optional.of(selected));
                    assertTrue(results.isEmpty(), "Error must be acknowledged before the cancelled callback");
                    assertEquals(1, f.alerts.size());
                    assertNotEquals("replaceFile", f.alerts.getFirst().purpose());
                    assertTrue(f.alerts.getFirst().content().contains(UiText.get("dialog.file.protected")));
                    assertFalse(f.alerts.getFirst().content().contains("secret-sentinel"));
                    var answer = f.alertAnswer; answer.accept("ok"); answer.accept("ok");
                    assertEquals(List.of(Optional.empty()), results);
                    assertEquals("secret-sentinel", Files.readString(path));
                }));
            }
        }
        return tests;
    }

    /** Подмена raw выбора и прямой вызов кнопки не обходят disabled состояние серверной формы. */
    @Test void browserRejectsProtectedOpenAndSaveInEvaluateAndClose() throws Exception {
        Path memory = Files.createDirectories(temp.resolve("CashMemory"));
        Path key = Files.writeString(memory.resolve("web-reconnect.md"), "secret-sentinel");
        for (var mode : FileChooserSpec.Mode.values()) {
            var form = new FileBrowserForm(spec(memory, mode), new FolderListing(memory));
            var values = new java.util.LinkedHashMap<>(form.defaults(null));
            if (mode == FileChooserSpec.Mode.SAVE) values.put("name", "web-reconnect");
            else values.put("value", key.toString());
            var state = new FormState(0, values);
            var view = form.evaluate(state, null);
            assertFalse(view.buttons().get("ok").enabled());
            assertEquals(UiText.get("dialog.file.protected"), view.problem().text());
            var outcome = assertInstanceOf(FormOutcome.Stay.class, form.onButton("ok", state, null));
            assertEquals(UiText.get("dialog.file.protected"), outcome.problem().text());
        }
        assertEquals("secret-sentinel", Files.readString(key));
    }

    /** Между отображением и нажатием меняется объект файла: закрытие обязано проверить новую цель. */
    @Test void browserRechecksTargetWhenOrdinaryFileBecomesProtectedHardLink() throws Exception {
        Path memory = Files.createDirectories(temp.resolve("CashMemory"));
        Path key = Files.writeString(memory.resolve("web-reconnect.md"), "secret-sentinel");
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Path alias = Files.writeString(outside.resolve("ordinary.md"), "ordinary");
        var form = new FileBrowserForm(spec(outside, FileChooserSpec.Mode.OPEN), new FolderListing(memory));
        var values = new java.util.LinkedHashMap<>(form.defaults(null)); values.put("value", alias.toString());
        var state = new FormState(0, values);
        assertTrue(form.evaluate(state, null).buttons().get("ok").enabled());
        Files.delete(alias); Files.createLink(alias, key);
        var outcome = assertInstanceOf(FormOutcome.Stay.class, form.onButton("ok", state, null));
        assertEquals(UiText.get("dialog.file.protected"), outcome.problem().text());
        assertEquals("secret-sentinel", Files.readString(key));
    }

    /** Замена объекта во время replace-dialog не передаёт защищённую цель callback записи. */
    @Test void confirmedReplaceRechecksHardLinkBeforeCallback() throws Exception {
        var f = new FakeFileFlowContext(temp);
        Path key = Files.writeString(f.environment.cashMemory().resolve("web-reconnect.md"), "secret-sentinel");
        Path target = Files.writeString(f.environment.cashMemory().resolve("ordinary.md"), "ordinary");
        List<Optional<Path>> results = new ArrayList<>();
        f.choosers.chooseFile(spec(f.environment.cashMemory(), FileChooserSpec.Mode.SAVE), results::add);
        f.fileAnswer.accept(Optional.of(target));
        assertEquals("replaceFile", f.alerts.getLast().purpose());
        Files.delete(target); Files.createLink(target, key);
        f.answer("replace");
        assertTrue(results.isEmpty());
        assertNotEquals("replaceFile", f.alerts.getLast().purpose());
        assertTrue(f.alerts.getLast().content().contains(UiText.get("dialog.file.protected")));
        f.answer("ok");
        assertEquals(List.of(Optional.empty()), results);
        assertEquals("secret-sentinel", Files.readString(key));
    }

    /** Выбор папок не применяет ограничение пользовательских файлов. */
    @Test void directoriesAndOrdinaryDocumentsRemainAllowed() throws Exception {
        Path memory = Files.createDirectories(temp.resolve("CashMemory"));
        Path directory = Files.createDirectories(memory.resolve("web-reconnect-tmp-folder"));
        var folders = new FileBrowserForm(new DirectoryChooserSpec("Test", directory), new FolderListing(memory));
        var state = new FormState(0, folders.defaults(null));
        assertTrue(folders.evaluate(state, null).buttons().get("ok").enabled());
        assertEquals(directory, assertInstanceOf(FormOutcome.Close.class, folders.onButton("ok", state, null)).result());
        var files = new FileBrowserForm(spec(memory, FileChooserSpec.Mode.SAVE), new FolderListing(memory));
        var values = new java.util.LinkedHashMap<>(files.defaults(null)); values.put("name", "ordinary");
        var normal = new FormState(0, values);
        assertTrue(files.evaluate(normal, null).buttons().get("ok").enabled());
        assertEquals(memory.resolve("ordinary.md"), assertInstanceOf(FormOutcome.Close.class, files.onButton("ok", normal, null)).result());
    }

    /** Создаёт минимальный запрос без зависимости от диалогового toolkit. */
    private static FileChooserSpec spec(Path folder, FileChooserSpec.Mode mode) {
        return new FileChooserSpec(mode == FileChooserSpec.Mode.OPEN ? FileChooserSpec.Purpose.OPEN_PLAN : FileChooserSpec.Purpose.SAVE_PLAN_AS,
                mode, "Test", "Test", List.of("md"), folder, "ordinary.md");
    }
}
