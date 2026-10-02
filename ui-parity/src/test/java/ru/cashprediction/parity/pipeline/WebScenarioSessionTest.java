package ru.cashprediction.parity.pipeline;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет handshake локального сервера без запуска настоящего интерфейса. */
class WebScenarioSessionTest {
    @TempDir Path root;

    /** Случайные ссылки и неполный журнал не являются готовностью сервера. */
    @Test void requiresExplicitHandshake() throws Exception {
        Path log = root.resolve("stdout");
        assertTrue(WebScenarioSession.address(log).isEmpty());
        Files.writeString(log, "http://127.0.0.1:1234/?t=x\n");
        assertTrue(WebScenarioSession.address(log).isEmpty());
        Files.writeString(log, "PARITY_URL http://127.0.0.1:");
        assertTrue(WebScenarioSession.address(log).isEmpty());
        Files.writeString(log, "PARITY_URL http://127.0.0.1:1234/app.html?t=x\n");
        assertEquals("/app.html", WebScenarioSession.address(log).orElseThrow().getPath());
    }

    /** Дублирующийся адрес и отсутствующий токен считаются нарушением протокола. */
    @Test void rejectsDuplicateAndMissingToken() throws Exception {
        Path log = root.resolve("stdout");
        Files.writeString(log, "PARITY_URL http://127.0.0.1:1234/\n");
        assertThrows(IllegalArgumentException.class, () -> WebScenarioSession.address(log));
        Files.writeString(log, "PARITY_URL http://127.0.0.1:1234/?t=x\nPARITY_URL http://127.0.0.1:1234/?t=x\n");
        assertThrows(IllegalStateException.class, () -> WebScenarioSession.address(log));
    }
}
