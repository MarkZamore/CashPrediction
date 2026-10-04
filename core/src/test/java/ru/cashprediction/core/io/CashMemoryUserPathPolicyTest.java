package ru.cashprediction.core.io;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Пользовательские чтение и запись не должны обходить защиту служебных файлов через алиасы. */
class CashMemoryUserPathPolicyTest {
    private static final List<String> SERVICE_NAMES = List.of(
            "web-reconnect.md", "web-reconnect-lock.md", "settings.md", "web-session.md",
            "web-session.plan.md", "session-fx.xml", "session-swing.xml", "session-web.xml",
            "web-reconnect-tmp-0123456789abcdef0123456789abcdef.md", "settings.md.123.456.tmp");

    @TempDir Path directory;

    @Test void serviceNameClassificationIsCaseInsensitive() {
        for (String name : SERVICE_NAMES) {
            assertTrue(CashMemoryLayout.isServiceFileName(name), name);
            assertTrue(CashMemoryLayout.isServiceFileName(name.toUpperCase(Locale.ROOT)), name);
        }
        for (String name : List.of("plan.md", "settings-personal.md", "web-reconnect-personal.md",
                "session-fx-notes.md", "web-session-personal.md", ""))
            assertFalse(CashMemoryLayout.isServiceFileName(name), name);
    }

    @Test void ordinaryExistingAndNewUserPathsAreAllowedInsideAndOutside() throws IOException {
        Path memory = Files.createDirectory(directory.resolve("CashMemory"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path existing = Files.writeString(memory.resolve("plan.md"), "public-plan-marker");
        Path external = Files.writeString(outside.resolve("other-plan.md"), "public-outside-marker");
        for (Path candidate : List.of(existing, external, memory.resolve("new-plan.md"),
                memory.resolve("missing/a/b/new-plan.md"), outside.resolve("missing/a/new-plan.md")))
            assertFalse(CashMemoryLayout.isProtectedUserPath(memory, candidate), candidate.getFileName().toString());
        assertEquals("public-plan-marker", Files.readString(existing));
        assertEquals("public-outside-marker", Files.readString(external));
        assertFalse(Files.exists(memory.resolve("missing")));
        assertFalse(Files.exists(outside.resolve("missing")));
    }

    @Test void missingCashMemoryDoesNotRequireCreatingDirectories() throws IOException {
        Path memory = directory.resolve("missing/CashMemory");
        assertFalse(CashMemoryLayout.isProtectedUserPath(memory, memory.resolve("nested/plan.md")));
        assertFalse(CashMemoryLayout.isProtectedUserPath(memory, directory.resolve("outside/plan.md")));
        assertFalse(Files.exists(directory.resolve("missing")));
    }

    @Test void existingAndAbsentServicePathsAreProtectedIncludingNormalizedAliases() throws IOException {
        Path memory = Files.createDirectory(directory.resolve("CashMemory"));
        for (String name : SERVICE_NAMES) {
            Path service = memory.resolve(name);
            assertTrue(CashMemoryLayout.isProtectedUserPath(memory, service), name);
            Files.writeString(service, "service-test-marker");
            assertTrue(CashMemoryLayout.isProtectedUserPath(memory, service), name);
            assertTrue(CashMemoryLayout.isProtectedUserPath(memory, memory.resolve("absent/../" + name)), name);
            assertTrue(CashMemoryLayout.isProtectedUserPath(memory, memory.resolve("./" + name)), name);
            assertTrue(CashMemoryLayout.isProtectedUserPath(memory, memory.resolve(name.toUpperCase(Locale.ROOT))), name);
            assertEquals("service-test-marker", Files.readString(service));
        }
        assertFalse(Files.exists(memory.resolve("absent")));
    }

    @Test void windowsTrailingDotAliasesCannotExposeCredential() throws IOException {
        assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
                "NOT EXECUTED: Windows filename aliases require Windows");
        Path memory = Files.createDirectory(directory.resolve("CashMemory"));
        Files.writeString(memory.resolve("web-reconnect.md"), "service-test-marker");
        assertTrue(CashMemoryLayout.isProtectedUserPath(memory, memory.resolve("WEB-RECONNECT.MD.")));
    }

    @Test void symbolicFileAliasOutsideMemoryIsProtectedForRawReads() throws IOException {
        Path memory = Files.createDirectory(directory.resolve("CashMemory"));
        Path service = Files.writeString(memory.resolve("web-reconnect.md"), "service-test-marker");
        Path alias = directory.resolve("public-looking-plan.md");
        symbolicLinkOrSkip(alias, service);
        assertTrue(Files.isSameFile(alias, service));
        assertTrue(CashMemoryLayout.isProtectedUserPath(memory, alias));
        assertEquals("service-test-marker", Files.readString(service));
    }

    @Test void symbolicDirectoryAliasCannotExposeServiceFile() throws IOException {
        Path memory = Files.createDirectory(directory.resolve("CashMemory"));
        Path service = Files.writeString(memory.resolve("web-reconnect-lock.md"), "service-test-marker");
        Path alias = directory.resolve("innocent-folder");
        symbolicLinkOrSkip(alias, memory);
        Path candidate = alias.resolve(service.getFileName());
        assertTrue(Files.isSameFile(candidate, service));
        assertTrue(CashMemoryLayout.isProtectedUserPath(memory, candidate));
    }

    @Test void hardLinkAliasOutsideMemoryIsProtectedForRawReads() throws IOException {
        Path memory = Files.createDirectory(directory.resolve("CashMemory"));
        Path service = Files.writeString(memory.resolve("web-reconnect.md"), "service-test-marker");
        Path alias = directory.resolve("innocent-import.md");
        hardLinkOrSkip(alias, service);
        assertTrue(Files.isSameFile(alias, service));
        assertTrue(CashMemoryLayout.isProtectedUserPath(memory, alias));
        assertEquals("service-test-marker", Files.readString(service));
    }

    @Test void hardLinkAliasOfAtomicTemporaryFileIsProtectedForRawReads() throws IOException {
        Path memory = Files.createDirectory(directory.resolve("CashMemory"));
        Path service = Files.writeString(memory.resolve("settings.md.123.456.tmp"), "service-test-marker");
        Path alias = directory.resolve("innocent-import.md");
        hardLinkOrSkip(alias, service);
        assertTrue(Files.isSameFile(alias, service));
        assertTrue(CashMemoryLayout.isProtectedUserPath(memory, alias));
    }

    @Test void hardLinkAliasInsideMemoryIsProtectedButOrdinaryPlanLinkIsAllowed() throws IOException {
        Path memory = Files.createDirectory(directory.resolve("CashMemory"));
        Path service = Files.writeString(memory.resolve("settings.md"), "service-test-marker");
        Path alias = memory.resolve("innocent-export.md");
        hardLinkOrSkip(alias, service);
        assertTrue(CashMemoryLayout.isProtectedUserPath(memory, alias));
        Path plan = Files.writeString(memory.resolve("normal-plan.md"), "public-plan-marker");
        Path planAlias = directory.resolve("normal-plan-alias.md");
        hardLinkOrSkip(planAlias, plan);
        assertFalse(CashMemoryLayout.isProtectedUserPath(memory, planAlias));
    }

    private static void symbolicLinkOrSkip(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | SecurityException | FileSystemException unavailable) {
            assumeTrue(false, "NOT EXECUTED: symbolic links unavailable: " + unavailable.getClass().getSimpleName());
        }
    }

    private static void hardLinkOrSkip(Path link, Path target) throws IOException {
        try { Files.createLink(link, target); }
        catch (UnsupportedOperationException | SecurityException | FileSystemException unavailable) {
            assumeTrue(false, "NOT EXECUTED: hard links unavailable: " + unavailable.getClass().getSimpleName());
        }
    }
}
