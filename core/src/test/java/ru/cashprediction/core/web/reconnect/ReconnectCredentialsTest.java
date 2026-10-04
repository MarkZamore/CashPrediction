package ru.cashprediction.core.web.reconnect;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Проверяет сохранность ключа, ограниченные отказы и изоляцию портативных копий. */
class ReconnectCredentialsTest {
    @TempDir Path directory;

    /** Недоступная индивидуальная Windows identity не заменяется группой владельца папки. */
    @Test void unknownWindowsPrincipalFailsClosedBeforeCreatingFiles() throws Exception {
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) return;
        String original = System.getProperty("user.name");
        try {
            System.setProperty("user.name", "cp-missing-user-" + UUID.randomUUID());
            assertTrue(ReconnectCredentials.open(directory).isEmpty());
            try (var entries = Files.list(directory)) { assertEquals(0, entries.count()); }
        } finally {
            if (original == null) System.clearProperty("user.name");
            else System.setProperty("user.name", original);
        }
        assertTrue(ReconnectCredentials.open(directory).isPresent());
        var acl = Files.getFileAttributeView(directory.resolve("web-reconnect.md"), AclFileAttributeView.class);
        UserPrincipal expected = directory.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        assertEquals(expected, acl.getOwner());
        assertFalse(acl.getOwner() instanceof GroupPrincipal);
        assertTrue(acl.getAcl().stream().filter(entry -> entry.type() == AclEntryType.ALLOW)
                .allMatch(entry -> entry.principal().equals(expected)));
    }

    @Test void repeatedAndConcurrentOpenRetainsOneCredential() throws Exception {
        var first = ReconnectCredentials.open(directory).orElseThrow();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<Callable<ReconnectCredential>>();
            for (int i = 0; i < 24; i++) tasks.add(() -> ReconnectCredentials.open(directory).orElseThrow());
            for (var result : pool.invokeAll(tasks)) {
                assertEquals(first.id(), result.get().id()); assertEquals(first.keyHex(), result.get().keyHex());
            }
        }
        try (var files = Files.list(directory)) {
            assertEquals(Set.of("web-reconnect.md", "web-reconnect-lock.md"), files.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test void malformedAndOversizedFilesRemainUnchanged() throws Exception {
        assertTrue(ReconnectCredentials.open(directory).isPresent());
        Path file = directory.resolve("web-reconnect.md");
        for (String bad : List.of("truncated", "x".repeat(1025), Files.readString(file).replace("Version: 1", "Version: 2"))) {
            Files.writeString(file, bad);
            assertTrue(ReconnectCredentials.open(directory).isEmpty()); assertEquals(bad, Files.readString(file));
        }
    }

    @Test void distinctCopiesAndCopiedDocumentFailClosed() throws Exception {
        Path a = Files.createDirectory(directory.resolve("a")), b = Files.createDirectory(directory.resolve("b"));
        var first = ReconnectCredentials.open(a).orElseThrow(); var second = ReconnectCredentials.open(b).orElseThrow();
        assertNotEquals(first.id(), second.id()); assertNotEquals(first.keyHex(), second.keyHex());
        String copied = Files.readString(a.resolve("web-reconnect.md"));
        Files.writeString(b.resolve("web-reconnect.md"), copied);
        assertTrue(ReconnectCredentials.open(b).isEmpty()); assertEquals(copied, Files.readString(b.resolve("web-reconnect.md")));
    }

    @Test void lockAndNonRegularPathsFailWithoutChangingDocument() throws Exception {
        assertTrue(ReconnectCredentials.open(directory).isPresent());
        Path file = directory.resolve("web-reconnect.md"); byte[] original = Files.readAllBytes(file);
        try (var channel = FileChannel.open(directory.resolve("web-reconnect-lock.md"), StandardOpenOption.WRITE);
                var lock = channel.lock()) {
            assertTrue(lock.isValid());
            assertTrue(ReconnectCredentials.open(directory).isEmpty());
            assertArrayEquals(original, Files.readAllBytes(file));
        }
        assertTrue(ReconnectCredentials.open(directory.resolve("missing")).isEmpty());
        assertTrue(ReconnectCredentials.open(file).isEmpty());
    }

    @Test void ownerOnlyPermissionsAndUnsafeExistingPermissions() throws Exception {
        assertTrue(ReconnectCredentials.open(directory).isPresent());
        Path file = directory.resolve("web-reconnect.md");
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), posix.readAttributes().permissions());
            posix.setPermissions(PosixFilePermissions.fromString("rw-r--r--"));
            assertTrue(ReconnectCredentials.open(directory).isEmpty());
        } else {
            var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            assertNotNull(acl);
            assertTrue(acl.getAcl().stream().filter(entry -> entry.type() == AclEntryType.ALLOW).allMatch(entry -> entry.principal().equals(aclOwner(acl))));
            var unsafe = new ArrayList<>(acl.getAcl());
            UserPrincipal other = Files.getFileAttributeView(directory, AclFileAttributeView.class).getAcl().stream()
                    .filter(entry -> entry.type() == AclEntryType.ALLOW && !entry.principal().equals(aclOwner(acl)))
                    .map(AclEntry::principal).findFirst().orElseThrow();
            unsafe.add(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(other).setPermissions(AclEntryPermission.READ_DATA).build());
            acl.setAcl(unsafe);
            assertTrue(ReconnectCredentials.open(directory).isEmpty());
        }
    }

    private static UserPrincipal aclOwner(AclFileAttributeView acl) {
        try { return acl.getOwner(); } catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    @Test void inaccessibleCredentialFailsClosedWithoutReplacement() throws Exception {
        assertTrue(ReconnectCredentials.open(directory).isPresent());
        Path file = directory.resolve("web-reconnect.md");
        byte[] original = Files.readAllBytes(file);
        var posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) {
            var permissions = posix.readAttributes().permissions();
            try {
                posix.setPermissions(Set.of());
                assertTrue(ReconnectCredentials.open(directory).isEmpty());
            } finally { posix.setPermissions(permissions); }
        } else {
            var acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            var originalAcl = acl.getAcl();
            var denied = new ArrayList<AclEntry>();
            denied.add(AclEntry.newBuilder().setType(AclEntryType.DENY).setPrincipal(acl.getOwner())
                    .setPermissions(AclEntryPermission.READ_DATA).build());
            denied.addAll(originalAcl);
            try {
                acl.setAcl(denied);
                assertTrue(ReconnectCredentials.open(directory).isEmpty());
            } finally { acl.setAcl(originalAcl); }
        }
        assertArrayEquals(original, Files.readAllBytes(file));
        assertTrue(ReconnectCredentials.open(directory).isPresent());
    }

    @Test void separateProcessesCreateOneCredentialWithoutSharedJvmLock() throws Exception {
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
        // Surefire загружает production-модуль и patch тестов из разных каталогов.
        // Дочерний немодульный процесс получает оба расположения явно, а не classpath родителя.
        String classes = Path.of(ReconnectCredentials.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + java.io.File.pathSeparator
                + Path.of(ReconnectCredentialsTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var processes = new ArrayList<Process>();
        try {
            for (int i = 0; i < 6; i++) processes.add(new ProcessBuilder(executable.toString(), "-XX:-UsePerfData",
                    "-cp", classes, StoreProcess.class.getName(), directory.toString()).redirectErrorStream(true).start());
            String expected = null;
            for (Process process : processes) {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS));
                String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).strip();
                assertEquals(0, process.exitValue(), output);
                assertTrue(output.matches("[0-9a-f]{32}:[0-9a-f]{64}"), output);
                if (expected == null) expected = output; else assertEquals(expected, output);
            }
        } finally {
            for (Process process : processes) if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    /** Изолированный процесс проверяет межпроцессную блокировку, не печатая ключ. */
    public static final class StoreProcess {
        /** Открывает ключ и печатает только идентификатор и контрольную сумму для тестового сравнения. */
        public static void main(String[] arguments) throws Exception {
            var value = ReconnectCredentials.open(Path.of(arguments[0])).orElseThrow();
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.keyHex().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            System.out.println(value.id() + ":" + HexFormat.of().formatHex(digest));
        }
    }
}
