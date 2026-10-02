package ru.cashprediction.core.ui.selftest;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/** Публикует тестовый JSON из соседнего временного файла; постоянная ошибка не становится успешной генерацией. */
final class GoldenWriter {
    /** Не больше пяти попыток одной операции, то есть четырёх повторов после временной ошибки. */
    static final int MAX_ATTEMPTS = 5;
    private static final long RETRY_DELAY_MILLIS = 40;

    private GoldenWriter() { }

    /** Операция замены внедряется для воспроизводимой проверки блокировки файла без платформенных гонок. */
    @FunctionalInterface
    interface MoveOperation {
        /** Перемещает полностью записанный временный файл с указанными параметрами. */
        void move(Path source, Path destination, CopyOption... options) throws IOException;
    }

    /** Ожидание внедряется, чтобы тесты могли проверить прерывание без реальной задержки. */
    @FunctionalInterface
    interface RetryPause {
        /** Ожидает ограниченный промежуток перед следующей попыткой. */
        void sleep(long millis) throws InterruptedException;
    }

    /** Записывает UTF-8 JSON рядом с эталоном и атомарно заменяет его после закрытия временного файла. */
    static void write(Path golden, String json) throws IOException {
        write(golden, json, (source, destination, options) -> Files.move(source, destination, options), Thread::sleep);
    }

    /** Использует те же пять попыток и уборку при внедрённой операции публикации. */
    static void write(Path golden, String json, MoveOperation move, RetryPause pause) throws IOException {
        checkInterrupted();
        Path destination = golden.toAbsolutePath().normalize();
        Path parent = destination.getParent();
        retry(() -> Files.createDirectories(parent), pause);
        Path temporary = retry(() -> Files.createTempFile(parent, ".golden-", ".json.tmp"), pause);
        Throwable failure = null;
        try {
            retry(() -> Files.writeString(temporary, json, StandardCharsets.UTF_8), pause);
            retry(() -> {
                try {
                    move.move(temporary, destination, REPLACE_EXISTING, ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    // Не удаляем прежний эталон: обычная замена получает тот же полностью записанный соседний файл.
                    move.move(temporary, destination, REPLACE_EXISTING);
                }
                return null;
            }, pause);
        } catch (IOException | RuntimeException | Error problem) {
            failure = problem;
            throw problem;
        } finally {
            // Уборка выполняется и при выставленном interrupt; флаг здесь не сбрасывается.
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    /** Одна операция с ограниченными повторами IOException; другие ошибки немедленно выходят наружу. */
    @FunctionalInterface
    private interface IoOperation<T> {
        /** Выполняет одну попытку ввода-вывода. */
        T run() throws IOException;
    }

    /** Возвращает последнюю ошибку после исчерпания попыток и сохраняет причину при прерывании ожидания. */
    private static <T> T retry(IoOperation<T> operation, RetryPause pause) throws IOException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            checkInterrupted();
            try { return operation.run(); }
            catch (IOException failure) {
                if (attempt == MAX_ATTEMPTS) throw failure;
                try { pause.sleep(RETRY_DELAY_MILLIS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    var problem = new InterruptedIOException("Golden write retry interrupted");
                    problem.initCause(interrupted);
                    problem.addSuppressed(failure);
                    throw problem;
                }
            }
        }
        throw new AssertionError("Retry loop exhausted without result");
    }

    /** Не начинает следующую операцию после прерывания и не потребляет выставленный флаг. */
    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Golden write interrupted");
    }
}
