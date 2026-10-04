package ru.cashprediction.core.app.file;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.FileChooserSpec;

/** Проверяет headless-контракт chooser; нативное окно здесь не запускается и не считается проверенным. */
class ManagedChooserScopeTest {
    @TempDir Path directory;

    @Test void externalOpenRemainsAllowed() throws Exception {
        FakeFileFlowContext fixture = new FakeFileFlowContext(directory);
        Path external = Files.writeString(directory.resolve("External.md"), "manual");
        List<Optional<Path>> results = new ArrayList<>();
        fixture.choosers.chooseFile(spec(directory, FileChooserSpec.Mode.OPEN), results::add);
        fixture.fileAnswer.accept(Optional.of(external));
        assertEquals(List.of(Optional.of(external)), results);
        assertTrue(fixture.alerts.isEmpty());
    }

    @Test void externalSaveStartsInsideAndIsRefusedOnceWithoutWriting() throws Exception {
        FakeFileFlowContext fixture = new FakeFileFlowContext(directory);
        Path external = Files.writeString(directory.resolve("External.md"), "manual");
        List<Optional<Path>> results = new ArrayList<>();
        fixture.choosers.chooseFile(spec(directory, FileChooserSpec.Mode.SAVE), results::add);
        assertEquals(fixture.environment.cashMemory(), fixture.chooserRequests.getLast().initialFolder());
        var answer = fixture.fileAnswer;
        answer.accept(Optional.of(external));
        answer.accept(Optional.of(external));
        assertEquals(1, fixture.alerts.size());
        fixture.answer("ok");
        assertEquals(List.of(Optional.empty()), results);
        assertEquals("manual", Files.readString(external));
    }

    /** Создаёт запрос с намеренно внешней начальной папкой. */
    private static FileChooserSpec spec(Path folder, FileChooserSpec.Mode mode) {
        return new FileChooserSpec(mode == FileChooserSpec.Mode.OPEN ? FileChooserSpec.Purpose.OPEN_PLAN
                : FileChooserSpec.Purpose.SAVE_PLAN_AS, mode, "fixture", "Markdown", List.of("md"), folder, "External.md");
    }
}
