package ru.cashprediction.parity.audit;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Регрессия настоящей ошибки нового handshake без запуска браузера или JVM клиента. */
final class WebAllowanceSessionTest {
    /** Сохраняет HTTP-схему и ключ, запрещает неоднозначный адрес и удалённый origin. */
    @Test void handshakePreservesSchemeAndRequiresOneLoopbackAddress() {
        String line = "PARITY_URL http://127.0.0.1:8080/app.html?t=unit";
        assertEquals("http", WebAllowanceSession.address(List.of("startup", line)).getScheme());
        assertEquals("t=unit", WebAllowanceSession.address(List.of(line)).getQuery());
        assertThrows(IllegalArgumentException.class, () -> WebAllowanceSession.address(List.of()));
        assertThrows(IllegalArgumentException.class, () -> WebAllowanceSession.address(List.of(line, line)));
        assertThrows(IllegalArgumentException.class, () -> WebAllowanceSession.address(
                List.of("PARITY_URL http://example.com:8080/app.html?t=unit")));
    }
}
