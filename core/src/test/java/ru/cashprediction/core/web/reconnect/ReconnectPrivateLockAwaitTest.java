package ru.cashprediction.core.web.reconnect;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Детерминированные границы private lock ожидания без разрешения group owner или изменения ACL. */
class ReconnectPrivateLockAwaitTest {
    @TempDir Path directory;

    /** Переход завершается только после нескольких полных отрицательных проверок. */
    @Test void ownerInitializationTransitionRetriesStrictPredicate() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicInteger polls = new AtomicInteger();
        assertTrue(ReconnectCredentials.awaitPrivateLock(() -> polls.incrementAndGet() == 3,
                clock::get, clock::addAndGet, TimeUnit.MILLISECONDS.toNanos(500)));
        assertEquals(3, polls.get()); assertEquals(TimeUnit.MILLISECONDS.toNanos(20), clock.get());
    }

    /** Постоянный отказ не превращается в доступ после ожидания. */
    @Test void permanentlyUnsafePredicateExhaustsOnlyOriginalBudget() throws Exception {
        AtomicLong clock = new AtomicLong(); AtomicInteger polls = new AtomicInteger();
        assertFalse(ReconnectCredentials.awaitPrivateLock(() -> { polls.incrementAndGet(); return false; },
                clock::get, clock::addAndGet, TimeUnit.MILLISECONDS.toNanos(500)));
        assertEquals(50, polls.get()); assertEquals(TimeUnit.MILLISECONDS.toNanos(500), clock.get());
    }

    /** Последняя пауза не добавляет полный интервал после оставшихся пяти миллисекунд. */
    @Test void remainingBudgetCapsPauseAndExpiredDeadlineDoesNotPoll() throws Exception {
        AtomicLong clock = new AtomicLong();
        assertFalse(ReconnectCredentials.awaitPrivateLock(() -> false, clock::get, clock::addAndGet,
                TimeUnit.MILLISECONDS.toNanos(15)));
        assertEquals(TimeUnit.MILLISECONDS.toNanos(15), clock.get());
        assertFalse(ReconnectCredentials.awaitPrivateLock(() -> { fail("expired deadline polled"); return true; },
                clock::get, nanos -> fail("expired deadline slept"), clock.get()));
    }

    /** Ошибка ACL и отмена остаются ошибкой/отменой, а не successful retry. */
    @Test void permissionFailureAndInterruptAreNotSwallowed() {
        assertThrows(IOException.class, () -> ReconnectCredentials.awaitPrivateLock(() -> {
            throw new IOException("fixture ACL unavailable");
        }, () -> 0L, nanos -> fail("ACL exception retried"), 1L));
        assertThrows(InterruptedException.class, () -> ReconnectCredentials.awaitPrivateLock(() -> false,
                () -> 0L, nanos -> { throw new InterruptedException("fixture cancel"); }, 1L));
    }

    /** Внешний open сохраняет interrupt flag и не создаёт файлы после отмены. */
    @Test void interruptedOpenRestoresInterruptFlag() throws Exception {
        try {
            Thread.currentThread().interrupt();
            assertTrue(ReconnectCredentials.open(directory).isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
            try (var files = Files.list(directory)) { assertEquals(0L, files.count()); }
        } finally { Thread.interrupted(); }
    }

    /** Существующий unsafe lock не ремонтируется; ключ, owner и права остаются неизменными. */
    @Test void unsafeExistingLockRemainsUnchangedAndEmpty() throws Exception {
        assertTrue(ReconnectCredentials.open(directory).isPresent());
        Path lock = directory.resolve("web-reconnect-lock.md"), credential = directory.resolve("web-reconnect.md");
        byte[] lockBytes = Files.readAllBytes(lock), credentialBytes = Files.readAllBytes(credential);
        var posix = Files.getFileAttributeView(lock, PosixFileAttributeView.class);
        if (posix != null) {
            var unsafe = PosixFilePermissions.fromString("rw-r--r--");
            posix.setPermissions(unsafe);
            assertTrue(ReconnectCredentials.open(directory).isEmpty());
            assertEquals(unsafe, posix.readAttributes().permissions());
        } else {
            var acl = Files.getFileAttributeView(lock, AclFileAttributeView.class);
            var owner = acl.getOwner();
            var permissions = EnumSet.allOf(AclEntryPermission.class);
            permissions.remove(AclEntryPermission.WRITE_DATA);
            var unsafe = List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(permissions).build());
            acl.setAcl(unsafe);
            var actualBefore = acl.getAcl();
            assertTrue(ReconnectCredentials.open(directory).isEmpty());
            assertEquals(owner, acl.getOwner()); assertEquals(actualBefore, acl.getAcl());
        }
        assertArrayEquals(lockBytes, Files.readAllBytes(lock));
        assertArrayEquals(credentialBytes, Files.readAllBytes(credential));
    }
}
