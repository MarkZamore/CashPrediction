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
import ru.cashprediction.core.ui.form.Presentation;

/** Проверяет выбор файла всех клиентов, дописывание расширения, замену и однократность результата. */
class FileChooserServiceTest {
    @TempDir Path temp;

    @TestFactory
    List<DynamicTest> extensionAndOverwriteAreIdenticalForAllProfiles() {
        List<DynamicTest> tests = new ArrayList<>();
        for (ClientProfile profile : List.of(ClientProfile.fx("25"), ClientProfile.swing(), ClientProfile.web())) {
            tests.add(DynamicTest.dynamicTest(profile.kind().name(), () -> {
                FakeFileFlowContext f = new FakeFileFlowContext(temp.resolve(profile.kind().name()));
                f.profile = profile;
                Path target = Files.writeString(f.environment.cashMemory().resolve("result.md"), "old");
                List<Optional<Path>> results = new ArrayList<>();
                f.choosers.chooseFile(spec(f, FileChooserSpec.Mode.SAVE), results::add);
                Path selected = target.resolveSibling("result");
                if (profile.equals(ClientProfile.web())) {
                    assertTrue(f.chooserRequests.isEmpty());
                    assertEquals(Presentation.FILE_BROWSER, f.form().spec().presentation());
                    f.formResult(selected);
                } else {
                    f.fileAnswer.accept(Optional.of(selected));
                }
                assertEquals("replaceFile", f.alerts.getLast().purpose());
                assertTrue(results.isEmpty());
                var answer = f.alertAnswer;
                f.answer("replace");
                answer.accept("replace");
                assertEquals(List.of(Optional.of(target)), results);
                assertEquals("old", Files.readString(target));
            }));
        }
        return tests;
    }

    @Test
    void nativeReplacePromptSuppressesOnlyExactSelectedFilename() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(temp);
        f.profile = ClientProfile.fx("25");
        Path path = Files.writeString(f.environment.cashMemory().resolve("result.MD"), "old");
        List<Optional<Path>> results = new ArrayList<>();
        f.choosers.chooseFile(spec(f, FileChooserSpec.Mode.SAVE), results::add);
        f.fileAnswer.accept(Optional.of(path));
        assertEquals(List.of(Optional.of(path)), results);
        assertTrue(f.alerts.isEmpty());
    }

    @Test
    void cancellingReplaceCompletesOnceAndNeverReopensChooser() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(temp);
        Path path = Files.writeString(f.environment.cashMemory().resolve("result.md"), "old");
        List<Optional<Path>> results = new ArrayList<>();
        f.choosers.chooseFile(spec(f, FileChooserSpec.Mode.SAVE), results::add);
        var selected = f.fileAnswer;
        selected.accept(Optional.of(path));
        selected.accept(Optional.of(path));
        assertEquals(1, f.alerts.size());
        var answer = f.alertAnswer;
        f.answer("cancel");
        answer.accept("replace");
        assertEquals(List.of(Optional.empty()), results);
        assertEquals(1, f.chooserRequests.size());
    }

    @Test
    void cancellingNativeChooserCompletesOnceWithoutOpeningAlert() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(temp);
        List<Optional<Path>> results = new ArrayList<>();
        f.choosers.chooseFile(spec(f, FileChooserSpec.Mode.SAVE), results::add);
        var answer = f.fileAnswer;
        answer.accept(Optional.empty());
        answer.accept(Optional.of(f.environment.cashMemory().resolve("late")));
        assertEquals(List.of(Optional.empty()), results);
        assertTrue(f.alerts.isEmpty());
    }

    @Test
    void openNeverAppendsExtensionOrAsksToReplace() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(temp);
        Path path = Files.writeString(f.environment.cashMemory().resolve("no-extension"), "old");
        List<Optional<Path>> results = new ArrayList<>();
        f.choosers.chooseFile(spec(f, FileChooserSpec.Mode.OPEN), results::add);
        f.fileAnswer.accept(Optional.of(path));
        assertEquals(List.of(Optional.of(path)), results);
        assertTrue(f.alerts.isEmpty());
    }

    @Test
    void webDirectoryUsesCoreFormAndNativeDirectoryDoesNot() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(temp);
        List<Optional<Path>> results = new ArrayList<>();
        DirectoryChooserSpec spec = new DirectoryChooserSpec("Test", f.environment.cashMemory());
        f.choosers.chooseDirectory(spec, results::add);
        assertTrue(f.requests.isEmpty());
        var nativeAnswer = f.directoryAnswer;
        nativeAnswer.accept(Optional.empty());
        nativeAnswer.accept(Optional.of(temp));
        assertEquals(List.of(Optional.empty()), results);
        f.profile = ClientProfile.web();
        f.directoryAnswer = null;
        f.choosers.chooseDirectory(spec, results::add);
        assertNull(f.directoryAnswer);
        assertEquals(Presentation.FILE_BROWSER, f.form().spec().presentation());
        f.formResult(temp);
        assertEquals(List.of(Optional.empty(), Optional.of(temp.toAbsolutePath().normalize())), results);
    }

    @Test
    void webChooserCancelReturnsEmptyAndDoesNotUseNativePort() throws Exception {
        FakeFileFlowContext f = new FakeFileFlowContext(temp);
        f.profile = ClientProfile.web();
        List<Optional<Path>> results = new ArrayList<>();
        f.choosers.chooseFile(spec(f, FileChooserSpec.Mode.SAVE), results::add);
        f.form().closeRequested();
        assertEquals(List.of(Optional.empty()), results);
        assertTrue(f.chooserRequests.isEmpty());
    }

    /** Возвращает минимальный запрос для проверки сервиса независимо от переводов FileFlow. */
    private static FileChooserSpec spec(FakeFileFlowContext f, FileChooserSpec.Mode mode) {
        return new FileChooserSpec(FileChooserSpec.Purpose.SAVE_PLAN_AS, mode, "Test", "Test", List.of("md"),
                f.environment.cashMemory(), "result.md");
    }
}
