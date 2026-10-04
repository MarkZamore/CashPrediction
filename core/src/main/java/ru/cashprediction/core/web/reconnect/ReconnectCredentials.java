package ru.cashprediction.core.web.reconnect;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** Закрытое хранилище ключа в CashMemory; любая ошибка отключает переподключение, но не запуск. */
public final class ReconnectCredentials {
    private static final String NAME = "web-reconnect.md";
    private static final String LOCK = "web-reconnect-lock.md";
    private static final int MAX_BYTES = 1024;
    private static final ReentrantLock[] JVM_LOCKS = new ReentrantLock[64];
    private static final Pattern DOCUMENT = Pattern.compile("# CashPrediction web reconnect\\nVersion: 1\\nInstallation: ([0-9a-f]{32})\\nKey: ([0-9a-f]{64})\\nPath-SHA256: ([0-9a-f]{64})\\n");
    static { Arrays.setAll(JVM_LOCKS, ignored -> new ReentrantLock()); }
    private ReconnectCredentials() { }

    /** Открывает ключ либо безопасно возвращает пустой результат, сохраняя повреждённый документ. */
    public static Optional<ReconnectCredential> open(Path cashMemory) {
        if (cashMemory == null) return Optional.empty();
        Path directory;
        try {
            directory = cashMemory.toAbsolutePath().normalize();
            for (Path part = directory; part != null; part = part.getParent())
                if (Files.isSymbolicLink(part)) return Optional.empty();
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            directory = directory.toRealPath();
        } catch (IOException | RuntimeException error) { return Optional.empty(); }
        ReentrantLock local = JVM_LOCKS[Math.floorMod(directory.hashCode(), JVM_LOCKS.length)];
        boolean acquired = false;
        try {
            acquired = local.tryLock(500, TimeUnit.MILLISECONDS);
            if (!acquired) return Optional.empty();
            Path lockPath = directory.resolve(LOCK);
            if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) {
                try { createPrivate(lockPath); }
                catch (FileAlreadyExistsException race) { /* Другой процесс создал постоянный файл блокировки. */ }
            }
            if (!regular(lockPath) || !privatePermissions(lockPath, false)) return Optional.empty();
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                FileLock lock = null;
                do {
                    try { lock = channel.tryLock(); }
                    catch (java.nio.channels.OverlappingFileLockException overlap) { return Optional.empty(); }
                    if (lock == null) Thread.sleep(10);
                } while (lock == null && System.nanoTime() < deadline);
                if (lock == null) return Optional.empty();
                try (FileLock held = lock) {
                    if (!held.isValid()) return Optional.empty();
                    return readOrCreate(directory);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); return Optional.empty();
        } catch (IOException | RuntimeException error) { return Optional.empty(); }
        finally { if (acquired) local.unlock(); }
    }

    private static Optional<ReconnectCredential> readOrCreate(Path directory) throws IOException {
        Path file = directory.resolve(NAME);
        String path = directory.toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) path = path.toLowerCase(Locale.ROOT);
        String binding;
        try { binding = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { return Optional.empty(); }
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return read(file, binding);
        ReconnectCredential credential = new ReconnectCredential(randomHex(16), randomHex(32));
        String text = "# CashPrediction web reconnect\nVersion: 1\nInstallation: " + credential.id()
                + "\nKey: " + credential.keyHex() + "\nPath-SHA256: " + binding + "\n";
        Path temporary = directory.resolve("web-reconnect-tmp-" + randomHex(16) + ".md");
        try {
            createPrivate(temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            // Без неатомарного запасного пути: недоступность механизма не мешает обычному запуску.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            return read(file, binding);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static Optional<ReconnectCredential> read(Path file, String binding) throws IOException {
        if (!regular(file) || !privatePermissions(file, false)) return Optional.empty();
        byte[] bytes;
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = stream.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) return Optional.empty();
        var match = DOCUMENT.matcher(new String(bytes, StandardCharsets.UTF_8));
        if (!match.matches() || !match.group(3).equals(binding)) return Optional.empty();
        return Optional.of(new ReconnectCredential(match.group(1), match.group(2)));
    }

    private static boolean regular(Path file) { return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file); }

    private static void createPrivate(Path file) throws IOException {
        if (Files.getFileStore(file.getParent()).supportsFileAttributeView(PosixFileAttributeView.class))
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        else {
            var parentAcl = Files.getFileAttributeView(file.getParent(), AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (parentAcl == null || parentAcl.getOwner() instanceof GroupPrincipal)
                throw new IOException("private reconnect storage unavailable");
            List<AclEntry> entries = List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                    .setPrincipal(parentAcl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build());
            // ACL задаётся при создании: другой процесс никогда не видит окно унаследованных открытых прав.
            Files.createFile(file, new FileAttribute<List<AclEntry>>() {
                /**
                 * Возвращает имя начального файлового атрибута списка контроля доступа.
                 * @return {@code acl:acl}, чтобы права задавались при создании файла
                 */
                @Override public String name() { return "acl:acl"; }
                /**
                 * Возвращает заранее подготовленное разрешение всех операций только владельцу родительской папки.
                 * @return неизменяемый список из одной разрешающей записи ACL для создаваемого файла
                 */
                @Override public List<AclEntry> value() { return entries; }
            });
        }
        if (!privatePermissions(file, true)) throw new IOException("private reconnect storage unavailable");
    }

    private static boolean privatePermissions(Path file, boolean initialize) throws IOException {
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) return posix.readAttributes().permissions().equals(PosixFilePermissions.fromString("rw-------"));
        var acl = Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) return false;
        UserPrincipal owner = acl.getOwner();
        if (owner instanceof GroupPrincipal) return false;
        if (initialize) acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        boolean readable = false, writable = false;
        for (AclEntry entry : acl.getAcl()) {
            if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner) && !entry.permissions().isEmpty()) return false;
            if (entry.type() == AclEntryType.ALLOW && entry.principal().equals(owner)) {
                readable |= entry.permissions().contains(AclEntryPermission.READ_DATA);
                writable |= entry.permissions().contains(AclEntryPermission.WRITE_DATA);
            }
        }
        return readable && writable;
    }

    private static String randomHex(int size) { byte[] bytes = new byte[size]; new SecureRandom().nextBytes(bytes); return HexFormat.of().formatHex(bytes); }
}
