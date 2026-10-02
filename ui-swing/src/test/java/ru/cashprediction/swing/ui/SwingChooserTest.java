package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;

/** Проверяет ответы выбора до и после реального запроса порта, без подмены запроса успешным шагом. */
class SwingChooserTest {
    @TempDir Path directory;

    @Test void queuedCancelWaitsForNextFileRequestAndIsConsumedOnce() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingUiPort port = port();
            try {
                List<Optional<Path>> answers = new ArrayList<>();
                port.answerChooser(null);
                assertTrue(port.chooserRequests.isEmpty()); assertTrue(answers.isEmpty());
                port.chooseFile(spec(), answers::add);
                assertEquals(List.of(Optional.empty()), answers);
                assertEquals(1, port.chooserRequests.size());
                port.chooseFile(spec(), answers::add);
                assertEquals(1, answers.size());
                port.answerChooser(directory.resolve("selected.md"));
                assertEquals(Optional.of(directory.resolve("selected.md")), answers.getLast());
                assertEquals(2, port.chooserRequests.size());
            } finally { port.exit(ExitKind.CLEAN, 0); }
        });
    }

    @Test void pendingRequestAndQueuedAnswerCannotBeOverwritten() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingUiPort port = port();
            try {
                port.answerChooser(null);
                assertThrows(IllegalStateException.class, () -> port.answerChooser(directory));
                port.chooseFile(spec(), answer -> { });
                port.chooseFile(spec(), answer -> { });
                assertThrows(IllegalStateException.class, () -> port.chooseFile(spec(), answer -> { }));
                port.answerChooser(null);
            } finally { port.exit(ExitKind.CLEAN, 0); }
        });
    }

    @Test void queuedPathWaitsForSaveAsRequestAndIsConsumedOnce() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingUiPort port = port();
            try {
                Path chosen = directory.resolve("selected.md");
                List<Optional<Path>> answers = new ArrayList<>();
                port.answerChooser(chosen);
                assertTrue(port.chooserRequests.isEmpty()); assertTrue(answers.isEmpty());
                assertThrows(IllegalStateException.class, () -> port.answerChooser(null));
                var saveAs = new FileChooserSpec(FileChooserSpec.Purpose.SAVE_PLAN_AS, FileChooserSpec.Mode.SAVE,
                        "save", "plan", List.of("md"), directory, "initial.md");
                port.chooseFile(saveAs, answers::add);
                assertEquals(List.of(Optional.of(chosen)), answers);
                assertEquals("SAVE", port.chooserRequests.getFirst().mode());
                port.chooseFile(saveAs, answers::add);
                assertEquals(1, answers.size());
                port.answerChooser(null);
                assertEquals(List.of(Optional.of(chosen), Optional.empty()), answers);
                assertEquals(2, port.chooserRequests.size());
            } finally { port.exit(ExitKind.CLEAN, 0); }
        });
    }

    @Test void sameQueueAnswersDirectoryRequest() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingUiPort port = port();
            try {
                List<Optional<Path>> answers = new ArrayList<>(); port.answerChooser(directory);
                port.chooseDirectory(new DirectoryChooserSpec("directory", directory), answers::add);
                assertEquals(List.of(Optional.of(directory)), answers);
                assertEquals("directory", port.chooserRequests.getFirst().kind());
            } finally { port.exit(ExitKind.CLEAN, 0); }
        });
    }

    /** Создаёт изолированный порт с штатным контрактом самотеста. */
    private SwingUiPort port() {
        return new SwingUiPort(AppEnvironment.from(LaunchOptions.parse(new String[]{"--home", directory.toString(), "--registry", "memory", "--selftest", "s07-forms-misc"})));
    }

    /** Представляет настоящий запрос открытия файла, а не результата выбора. */
    private FileChooserSpec spec() {
        return new FileChooserSpec(FileChooserSpec.Purpose.OPEN_PLAN, FileChooserSpec.Mode.OPEN, "open", "plan", List.of("md"), directory, "");
    }
}
