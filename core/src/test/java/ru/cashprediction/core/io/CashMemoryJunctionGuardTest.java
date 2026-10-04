package ru.cashprediction.core.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppClock;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.LaunchOptions;

/** Реальные Windows junction, без GUI, реестра, updater и изменения привилегий. */
@EnabledOnOs(OS.WINDOWS)
@Timeout(20)
class CashMemoryJunctionGuardTest {
    @TempDir Path temporary;

    @Test void ordinaryHomeIsReadOnlyUntilEnsureAndExistingBytesSurvive() throws Exception {
        Path home = temporary.resolve("ordinary");
        AppEnvironment environment = environment(home);
        assertFalse(Files.exists(home));
        Path memory = AppPaths.ensureCashMemory(home, environment.cashMemory());
        Files.writeString(memory.resolve("keep.md"), "KEEP");
        Map<String, String> before = inventory(memory);
        assertEquals(memory, AppPaths.ensureCashMemory(home, memory));
        assertEquals(before, inventory(memory));
        Path explicit = temporary.resolve("explicit.md");
        AtomicFiles.writeString(explicit, "EXPLICIT");
        assertEquals("EXPLICIT", Files.readString(explicit));
    }

    @Test void preexistingCashMemoryJunctionRejectsEnvironmentAndEveryTextSink() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("settings.md"), "KEEP SETTINGS");
        Files.writeString(outside.resolve("session-fx.xml"), "KEEP XML");
        Map<String, String> before = inventory(outside);
        Path link = home.resolve("CashMemory");
        junction(link, outside);
        try {
            assertThrows(UncheckedIOException.class, () -> environment(home));
            LaunchOptions options = LaunchOptions.parse("--registry", "memory");
            assertThrows(UncheckedIOException.class, () -> new AppEnvironment(options, home, link, AppClock.system()));
            assertThrows(IOException.class, () -> AppPaths.ensureCashMemory(home, link));
            for (String name : List.of("settings.md", "session-fx.xml", "session-swing.xml",
                    "web-session.md", "web-session.plan.md")) {
                assertThrows(IOException.class, () -> AtomicFiles.writeString(link.resolve(name), "MUST NOT WRITE"));
            }
            assertEquals(before, inventory(outside));
        } finally { Files.deleteIfExists(link); }
        assertEquals(before, inventory(outside));
    }

    @Test void homeAncestorJunctionRejectsBeforeCreatingMissingMemory() throws Exception {
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Path link = temporary.resolve("home-alias");
        junction(link, outside);
        try {
            assertThrows(UncheckedIOException.class, () -> environment(link));
            assertThrows(IOException.class, () -> AppPaths.ensureCashMemory(link, link.resolve("CashMemory")));
            assertTrue(inventory(outside).isEmpty());
        } finally { Files.deleteIfExists(link); }
    }

    @Test void junctionAddedAfterEnvironmentCannotReachEnsureOrLateFlush() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        AppEnvironment environment = environment(home);
        Path outside = Files.createDirectory(temporary.resolve("outside"));
        Files.writeString(outside.resolve("settings.md"), "KEEP");
        Map<String, String> before = inventory(outside);
        junction(environment.cashMemory(), outside);
        try {
            assertThrows(IOException.class, () -> AppPaths.ensureCashMemory(home, environment.cashMemory()));
            assertThrows(IOException.class, () -> AtomicFiles.writeString(environment.cashMemory().resolve("settings.md"), "FLUSH"));
            assertEquals(before, inventory(outside));
        } finally { Files.deleteIfExists(environment.cashMemory()); }
    }

    @Test void unrelatedMemoryArgumentIsRejectedWithoutCreatingAnything() {
        Path home = temporary.resolve("home");
        Path other = temporary.resolve("other");
        assertThrows(UncheckedIOException.class, () -> new AppEnvironment(
                LaunchOptions.parse("--registry", "memory"), home, other, AppClock.system()));
        assertFalse(Files.exists(home));
        assertFalse(Files.exists(other));
    }

    @Test void existingMemoryFileIsNotDeletedOrReplaced() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path memory = home.resolve("CashMemory");
        Files.writeString(memory, "KEEP FILE");
        assertThrows(UncheckedIOException.class, () -> environment(home));
        assertThrows(IOException.class, () -> AppPaths.ensureCashMemory(home, memory));
        assertEquals("KEEP FILE", Files.readString(memory));
    }

    private static AppEnvironment environment(Path home) {
        return AppEnvironment.from(LaunchOptions.parse("--home", home.toString(), "--registry", "memory"));
    }

    private static Map<String, String> inventory(Path directory) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                result.put(file.getFileName().toString(), HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))));
            }
        }
        return result;
    }

    private static void junction(Path link, Path outside) throws Exception {
        // Повтор existing WindowsJunctionTest: только собственный @TempDir, без elevation.
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                link.toString(), outside.toString()).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "JUNCTION_CREATOR_EXIT_TIMEOUT");
            fail("JUNCTION_CREATION_TIMEOUT");
        }
        assertEquals(0, process.exitValue(), "JUNCTION_CREATION_FAILED");
    }
}
