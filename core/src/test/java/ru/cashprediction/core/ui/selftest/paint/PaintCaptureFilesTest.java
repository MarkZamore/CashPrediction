package ru.cashprediction.core.ui.selftest.paint;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import java.util.concurrent.TimeUnit;

/** Проверяет сохранность связанного набора, а не качество синтетической отрисовки. */
final class PaintCaptureFilesTest {
    @TempDir Path root;

    /** Полная попытка читается, но её идентичность повторно использовать нельзя. */
    @Test void persistsAndRefusesDuplicateAttempt() throws Exception {
        var capture = capture();
        Path directory = PaintCaptureFiles.write(root, PaintCaptureFixtures.request(), capture);
        PaintCaptureFiles.verify(directory, PaintCaptureFixtures.request(), capture);
        try (var files = Files.list(directory)) { assertEquals(4, files.count()); }
        assertThrows(IOException.class, () -> PaintCaptureFiles.write(root, PaintCaptureFixtures.request(), capture));
    }

    /** Ни удаление, ни изменение любого из четырёх документов не считается успехом. */
    @Test void rejectsEveryMissingOrChangedFile() throws Exception {
        for (String name : new String[]{"raw.json", "capture.png", "paint.json", "commit.json"}) {
            Path run = Files.createDirectory(root.resolve(name));
            var capture = capture();
            Path directory = PaintCaptureFiles.write(run, PaintCaptureFixtures.request(), capture);
            byte[] original = Files.readAllBytes(directory.resolve(name));
            Files.write(directory.resolve(name), new byte[]{1, 2, 3});
            assertThrows(IOException.class, () -> PaintCaptureFiles.verify(directory, PaintCaptureFixtures.request(), capture), name);
            Files.write(directory.resolve(name), original);
            Files.delete(directory.resolve(name));
            assertThrows(IOException.class, () -> PaintCaptureFiles.verify(directory, PaintCaptureFixtures.request(), capture), name);
        }
    }

    /** Незавершённая попытка без commit не проходит и не перезаписывается. */
    @Test void rejectsPartialAttemptWithoutOverwriting() throws Exception {
        Path partial = Files.createDirectory(root.resolve(PaintCaptureFixtures.CAPTURE.toString()));
        Files.writeString(partial.resolve("raw.json"), "{}");
        assertThrows(IOException.class, () -> PaintCaptureFiles.verify(partial, PaintCaptureFixtures.request(), capture()));
        assertThrows(IOException.class, () -> PaintCaptureFiles.write(root, PaintCaptureFixtures.request(), capture()));
        assertEquals("{}", Files.readString(partial.resolve("raw.json")));
    }

    /** Даже полный набор, переименованный как другой захват, отклоняется. */
    @Test void rejectsDifferentDirectoryIdentity() throws Exception {
        Path directory = PaintCaptureFiles.write(root, PaintCaptureFixtures.request(), capture());
        Path moved = root.resolve("other");
        Files.move(directory, moved);
        assertThrows(IOException.class, () -> PaintCaptureFiles.verify(moved, PaintCaptureFixtures.request(), capture()));
    }

    /** Настоящий NTFS junction нельзя использовать ни как область записи, ни как предка. */
    @Test @EnabledOnOs(OS.WINDOWS)
    void rejectsWindowsJunctionWithoutTouchingTarget() throws Exception {
        Path target = Files.createDirectory(root.resolve("owned-target"));
        Path nested = Files.createDirectory(target.resolve("nested"));
        Path sentinel = target.resolve("sentinel.md");
        Files.writeString(sentinel, "owned sentinel");
        Path link = root.resolve("junction");
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                link.toString(), target.toString()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("JUNCTION_CREATION_TIMEOUT");
        }
        assertEquals(0, process.exitValue(), "JUNCTION_CREATION_FAILED");
        try {
            assertThrows(IOException.class, () -> PaintCaptureFiles.write(link, PaintCaptureFixtures.request(), capture()));
            assertThrows(IOException.class, () -> PaintCaptureFiles.write(link.resolve("nested"), PaintCaptureFixtures.request(), capture()));
            assertFalse(Files.exists(target.resolve(PaintCaptureFixtures.CAPTURE.toString())));
            assertFalse(Files.exists(nested.resolve(PaintCaptureFixtures.CAPTURE.toString())));
            assertEquals("owned sentinel", Files.readString(sentinel));
        } finally {
            // Только сам созданный junction: не обход и не рекурсивное удаление целевого каталога.
            Files.delete(link);
        }
    }

    /** Строит только unit-фикстуру, не эталон настоящего клиента. */
    private static WidgetCapture capture() {
        return new WidgetCapture(PaintCaptureFixtures.raw("fx", "normal"),
                PaintCaptureFixtures.png(1200, 800), PaintCaptureFixtures.observation());
    }
}
