package ru.cashprediction.core.app.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.AppClock;
import ru.cashprediction.core.app.AppEnvironment;
import ru.cashprediction.core.app.LaunchOptions;
import ru.cashprediction.core.session.store.InMemoryRegistryBackend;
import ru.cashprediction.core.session.store.RegistrySessionStore;

/**
 * Окружение процесса и часы (архитектура §3.8; решения L2, L12): папки из {@code --home}, узел реестра установки,
 * явный префикс и реестр в памяти; создание окружения ничего не пишет на диск и не открывает реестр.
 */
class AppEnvironmentTest {

    @Test
    void homeAndTodayComeFromOptions(@TempDir Path temp) {
        Path home = temp.resolve("portable").resolve("..").resolve("portable");
        LaunchOptions options = LaunchOptions.parse(List.of("--home", home.toString(), "--today", "2026-09-13"),
                new Properties());
        AppEnvironment environment = AppEnvironment.from(options);

        Path normalized = temp.resolve("portable").toAbsolutePath().normalize();
        assertEquals(normalized, environment.appHome());
        assertEquals(normalized.resolve("CashMemory"), environment.cashMemory());
        assertEquals(LocalDate.of(2026, 9, 13), environment.clock().today());
        assertTrue(environment.clock().isTodayFixed());
        assertFalse(Files.exists(environment.cashMemory()), "окружение не создаёт CashMemory");
    }

    @Test
    void registryNodeFollowsLedgerL2(@TempDir Path home) {
        AppEnvironment installation = AppEnvironment.from(
                LaunchOptions.parse(List.of("--home", home.toString()), new Properties()));
        assertEquals(RegistrySessionStore.installationNodePath("fx", installation.cashMemory()),
                installation.registryNodePath("fx"));
        assertTrue(installation.registryNodePath("swing").matches("ru/cashprediction/session/swing-[0-9a-f]{8}"));

        AppEnvironment explicit = AppEnvironment.from(LaunchOptions.parse(
                List.of("--home", home.toString(), "--registry-node", "ru/cashprediction/selftest/abc"), new Properties()));
        assertEquals("ru/cashprediction/selftest/abc/swing", explicit.registryNodePath("swing"));

        AppEnvironment memory = AppEnvironment.from(LaunchOptions.parse(
                List.of("--home", home.toString(), "--registry", "memory"), new Properties()));
        assertEquals("", memory.registryNodePath("fx"));
        RegistrySessionStore store = memory.registryStore("fx");
        assertTrue(store.backend() instanceof InMemoryRegistryBackend, "--registry memory не трогает настоящий реестр");
        assertTrue(store.isAvailable());
    }

    @Test
    void fileStoresLiveInCashMemoryAndAreNotCreatedEagerly(@TempDir Path home) {
        AppEnvironment environment = AppEnvironment.from(
                LaunchOptions.parse(List.of("--home", home.toString(), "--registry", "memory"), new Properties()));
        assertEquals(environment.cashMemory().resolve("session-swing.xml"), environment.xmlStore("swing").file());
        assertEquals(environment.cashMemory().resolve("web-session.md"), environment.webStore().sessionFile());
        assertFalse(Files.exists(environment.cashMemory()));
    }

    @Test
    void clockVariants() {
        Clock fixed = Clock.fixed(Instant.parse("2026-12-31T22:30:00Z"), ZoneOffset.ofHours(3));
        AppClock fromClock = AppClock.of(fixed, null);
        assertEquals(LocalDate.of(2027, 1, 1), fromClock.today(), "дата берётся в часовом поясе часов");
        assertFalse(fromClock.isTodayFixed());
        assertEquals(Instant.parse("2026-12-31T22:30:00Z"), fromClock.now());
        assertEquals(ZoneOffset.ofHours(3), fromClock.zone());

        AppClock pinned = AppClock.of(fixed, LocalDate.of(2026, 9, 13));
        assertEquals(LocalDate.of(2026, 9, 13), pinned.today());
        assertEquals(fixed.instant(), pinned.now(), "фиксируется только дата, время остаётся настоящим");
        assertEquals(LocalDate.of(2026, 9, 13), AppClock.fixedToday(LocalDate.of(2026, 9, 13)).today());
        assertFalse(AppClock.system().isTodayFixed());
    }
}
