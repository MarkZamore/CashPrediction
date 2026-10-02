package ru.cashprediction.core.ui.selftest;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет публикацию эталона и внедрённые сбои без записи в настоящие ui-golden и без платформенных блокировок. */
final class GoldenWriterTest {
    @TempDir Path temporary;

    /** Новый файл и последующая замена содержат полный JSON в UTF-8 без оставшегося временного файла. */
    @Test void createsAndReplacesUtf8Fixture() throws Exception {
        Path golden = temporary.resolve("nested/fixture.json");
        GoldenWriter.write(golden, "{\"value\":\"Пример\"}\n");
        assertEquals("{\"value\":\"Пример\"}\n", Files.readString(golden, StandardCharsets.UTF_8));
        GoldenWriter.write(golden, "{\"value\":2}\n");
        assertEquals("{\"value\":2}\n", Files.readString(golden));
        assertOnly(golden);
    }

    /** Временная блокировка повторяет замену того же соседнего файла, сохраняя старый JSON до успеха. */
    @Test void retriesLockedOnceUsingSameParentAndClosedCompleteFile() throws Exception {
        Path golden = oldFixture();
        var sources = new ArrayList<Path>(); var pauses = new ArrayList<Long>();
        GoldenWriter.write(golden, "{\"new\":true}", (source, destination, options) -> {
            assertEquals(golden.toAbsolutePath(), destination);
            assertEquals(destination.getParent(), source.getParent());
            assertNotEquals(source, destination);
            assertEquals("{\"new\":true}", Files.readString(source));
            assertEquals("{\"old\":true}", Files.readString(destination));
            assertEquals(List.of(REPLACE_EXISTING, ATOMIC_MOVE), List.of(options));
            sources.add(source);
            if (sources.size() == 1) throw locked(destination);
            Files.move(source, destination, options);
        }, pauses::add);
        assertEquals(2, sources.size()); assertEquals(sources.getFirst(), sources.getLast());
        assertEquals(1, pauses.size()); assertTrue(pauses.getFirst() > 0);
        assertEquals("{\"new\":true}", Files.readString(golden)); assertOnly(golden);
    }

    /** Постоянная блокировка выходит с исходной ошибкой ровно после пяти попыток и сохраняет старый файл. */
    @Test void persistentFailureIsBoundedAndPreservesOldFile() throws Exception {
        Path golden = oldFixture(); var calls = new AtomicInteger(); var pauses = new AtomicInteger();
        IOException failure = locked(golden);
        IOException actual = assertThrows(IOException.class, () -> GoldenWriter.write(golden, "{\"new\":true}",
                (source, destination, options) -> { calls.incrementAndGet(); throw failure; }, millis -> pauses.incrementAndGet()));
        assertSame(failure, actual); assertEquals(GoldenWriter.MAX_ATTEMPTS, calls.get());
        assertEquals(GoldenWriter.MAX_ATTEMPTS - 1, pauses.get());
        assertEquals("{\"old\":true}", Files.readString(golden)); assertOnly(golden);
    }

    /** Неудачная первая публикация не оставляет ни эталона, ни промежуточного JSON. */
    @Test void failureCreatingNewFixtureCleansTemporaryFile() throws Exception {
        Path golden = temporary.resolve("new.json");
        assertThrows(IOException.class, () -> GoldenWriter.write(golden, "{}",
                (source, destination, options) -> { throw locked(destination); }, millis -> { }));
        assertFalse(Files.exists(golden));
        try (var files = Files.list(temporary)) { assertEquals(0, files.count()); }
    }

    /** Только отсутствие поддержки ATOMIC_MOVE разрешает обычную замену без предварительного удаления эталона. */
    @Test void unsupportedAtomicMoveFallsBackToReplaceExisting() throws Exception {
        Path golden = oldFixture(); var calls = new ArrayList<List<CopyOption>>();
        GoldenWriter.write(golden, "{\"new\":true}", (source, destination, options) -> {
            calls.add(List.of(options));
            assertEquals("{\"old\":true}", Files.readString(golden));
            if (List.of(options).contains(ATOMIC_MOVE))
                throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "test provider");
            Files.move(source, destination, options);
        }, millis -> fail("Unsupported atomic move must immediately try fallback"));
        assertEquals(List.of(List.of(REPLACE_EXISTING, ATOMIC_MOVE), List.of(REPLACE_EXISTING)), calls);
        assertEquals("{\"new\":true}", Files.readString(golden)); assertOnly(golden);
    }

    /** Блокировка обычной замены после unsupported также ограничена и не разрушает предыдущий эталон. */
    @Test void persistentFallbackFailureDoesNotDeletePreviousFixture() throws Exception {
        Path golden = oldFixture(); var fallbackCalls = new AtomicInteger();
        IOException failure = locked(golden);
        assertSame(failure, assertThrows(IOException.class, () -> GoldenWriter.write(golden, "{}", (source, destination, options) -> {
            if (List.of(options).contains(ATOMIC_MOVE))
                throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "test provider");
            assertEquals("{\"old\":true}", Files.readString(golden));
            fallbackCalls.incrementAndGet(); throw failure;
        }, millis -> { })));
        assertEquals(GoldenWriter.MAX_ATTEMPTS, fallbackCalls.get());
        assertEquals("{\"old\":true}", Files.readString(golden)); assertOnly(golden);
    }

    /** Прерванное ожидание сохраняет interrupt и исходную ошибку; временный файл удаляется без второго перемещения. */
    @Test void interruptedRetryPreservesFlagCauseAndOldFixture() throws Exception {
        Path golden = oldFixture(); var calls = new AtomicInteger(); IOException locked = locked(golden);
        var interrupted = new InterruptedException("test interruption");
        try {
            var failure = assertThrows(InterruptedIOException.class, () -> GoldenWriter.write(golden, "{}",
                    (source, destination, options) -> { calls.incrementAndGet(); throw locked; }, millis -> { throw interrupted; }));
            assertTrue(Thread.currentThread().isInterrupted()); assertSame(interrupted, failure.getCause());
            assertArrayEquals(new Throwable[]{locked}, failure.getSuppressed());
            assertEquals(1, calls.get()); assertEquals("{\"old\":true}", Files.readString(golden)); assertOnly(golden);
        } finally { Thread.interrupted(); }
    }

    /** Уже выставленный interrupt не начинает генерацию и не сбрасывается внутри writer. */
    @Test void alreadyInterruptedDoesNotTouchFixture() throws Exception {
        Path golden = oldFixture(); Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> GoldenWriter.write(golden, "{}",
                    (source, destination, options) -> fail("Move after interruption"), millis -> fail("Sleep after interruption")));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals("{\"old\":true}", Files.readString(golden)); assertOnly(golden);
        } finally { Thread.interrupted(); }
    }

    /** Ошибка уборки не заменяет исходную ошибку публикации и остаётся доступной как suppressed. */
    @Test void cleanupFailureIsReportedAlongsideOriginalFailure() throws Exception {
        Path golden = oldFixture(); var calls = new AtomicInteger(); IOException failure = locked(golden);
        var actual = assertThrows(IOException.class, () -> GoldenWriter.write(golden, "{}", (source, destination, options) -> {
            if (calls.incrementAndGet() == 1) {
                // Непустая папка вместо временного файла воспроизводит невозможность удаления в finally.
                Files.delete(source); Files.createDirectory(source); Files.writeString(source.resolve("held.txt"), "held");
            }
            throw failure;
        }, millis -> { }));
        assertSame(failure, actual); assertEquals(GoldenWriter.MAX_ATTEMPTS, calls.get());
        assertEquals(1, actual.getSuppressed().length);
        assertInstanceOf(java.nio.file.DirectoryNotEmptyException.class, actual.getSuppressed()[0]);
        assertEquals("{\"old\":true}", Files.readString(golden));
    }

    /** Создаёт предыдущий проверенный эталон только внутри изолированной папки теста. */
    private Path oldFixture() throws IOException {
        Path golden = temporary.resolve("fixture.json"); Files.writeString(golden, "{\"old\":true}"); return golden;
    }

    /** Воспроизводит наблюдённую временную блокировку Windows через внедрённую IOException. */
    private static FileSystemException locked(Path golden) {
        return new FileSystemException(golden.toString(), null, "File used by another process");
    }

    /** Проверяет отсутствие соседних временных файлов после успеха, ошибки или прерывания. */
    private static void assertOnly(Path golden) throws IOException {
        try (var files = Files.list(golden.getParent())) { assertEquals(List.of(golden), files.toList()); }
    }
}
