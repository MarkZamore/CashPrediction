package ru.cashprediction.core.app.flow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Проверяет обнаружение внешней правки и удаления файла плана. */
class ExternalChangeGuardTest {

    @Test
    void detectsNewTimestampAndDeletion(@TempDir Path directory) throws Exception {
        Path plan = directory.resolve("plan.md");
        Files.writeString(plan, "first");
        ExternalChangeGuard guard = new ExternalChangeGuard();
        guard.remember(plan);
        assertFalse(guard.changedExternally(plan));

        Files.setLastModifiedTime(plan, FileTime.from(Instant.parse("2026-09-29T07:00:00Z")));
        assertTrue(guard.changedExternally(plan));
        guard.remember(plan);
        Files.delete(plan);
        assertTrue(guard.changedExternally(plan));
    }

    @Test
    void differentOrForgottenFileDoesNotCauseAFalseConflict(@TempDir Path directory) throws Exception {
        Path first = Files.writeString(directory.resolve("first.md"), "first");
        Path second = Files.writeString(directory.resolve("second.md"), "second");
        ExternalChangeGuard guard = new ExternalChangeGuard();
        guard.remember(first);
        assertFalse(guard.changedExternally(second));
        guard.forget();
        assertFalse(guard.changedExternally(first));
    }
}
