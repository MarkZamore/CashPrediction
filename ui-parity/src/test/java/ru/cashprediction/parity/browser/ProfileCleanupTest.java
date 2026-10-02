package ru.cashprediction.parity.browser;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет освобождение временной блокировки и отсутствие ложного успеха очистки. */
class ProfileCleanupTest {
    /** Временная блокировка повторяется, после успеха новые попытки не выполняются. */
    @Test void temporaryLockIsRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ProfileCleanup.retry(() -> {
            if (calls.incrementAndGet() < 3) throw new AccessDeniedException("profile-file");
        }, 5, 0);
        assertEquals(3, calls.get());
    }

    /** Постоянная ошибка сохраняется целиком, число попыток ограничено. */
    @Test void permanentFailureIsNotSwallowed() {
        AtomicInteger calls = new AtomicInteger();
        IOException original = new AccessDeniedException("profile-file");
        assertSame(original, assertThrows(IOException.class, () -> ProfileCleanup.retry(() -> {
            calls.incrementAndGet(); throw original;
        }, 3, 0)));
        assertEquals(3, calls.get());
    }

    /** Прерывание не превращается в успешное закрытие и сохраняет флаг потока. */
    @Test void interruptionIsNotSwallowed() {
        Thread.currentThread().interrupt();
        try {
            IOException failure = assertThrows(IOException.class, () -> ProfileCleanup.retry(() -> {
                throw new AccessDeniedException("profile-file");
            }, 3, 1));
            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    /** Неверные ограничения отклоняются до операций с профилем. */
    @Test void invalidLimitsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProfileCleanup.retry(() -> fail("Unexpected delete"), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ProfileCleanup.retry(() -> fail("Unexpected delete"), 1, -1));
    }
}
