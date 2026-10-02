package ru.cashprediction.core.ui.selftest;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.command.KeyChord;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет сообщение открытия примера до и после срока, не исключая статус из сравнения. */
final class ModelStatusLifetimeTest {
    @TempDir Path root;

    /** Сообщение живёт ровно десять секунд виртуального планировщика контроллера. */
    @Test void sampleMessageExpiresAfterTenSeconds() throws Exception {
        var env = AppEnvironment.from(LaunchOptions.parse("--home", root.toString(), "--registry", "memory", "--today", "2026-09-13"));
        var driver = new ModelUiDriver(env, ClientProfile.fx("25"));
        try {
            driver.execute(new SelfTestCommand.Key(KeyChord.parse("Esc")));
            driver.execute(new SelfTestCommand.Sample());
            driver.advance(Duration.ofMillis(9999));
            assertFalse(message(driver).isBlank());
            driver.advance(Duration.ofMillis(1));
            assertEquals("", message(driver));
        } finally { driver.stopTimers(); }
    }

    /** Читает видимый сегмент сообщения, не подменяя состояние таймера. */
    private static String message(ModelUiDriver driver) {
        return driver.dump("status").status().stream().filter(segment -> segment.id().equals("message"))
                .findFirst().orElseThrow().text();
    }
}
