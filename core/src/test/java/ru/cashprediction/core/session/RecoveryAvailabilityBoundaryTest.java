package ru.cashprediction.core.session;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Проверяет отказ инфраструктурных проверок доступности на data-only границе. */
class RecoveryAvailabilityBoundaryTest {
    /** Отказ availability нельзя выдать за отсутствующий снимок или передать контроллеру исключением. */
    @Test void throwingAvailabilityBecomesExplicitProblemWithoutLoad() {
        check("isAvailable", "availability-failure");
    }
    /** Отказ reason сохраняется как проблема, хотя адаптер уже сообщил недоступность. */
    @Test void throwingReasonBecomesExplicitProblemWithoutLoad() {
        check("unavailableReason", "reason-failure");
    }
    /** Proxy подменяет только аварийный внешний адаптер, а служба является production-кодом. */
    private static void check(String failedMethod, String detail) {
        var loads = new AtomicInteger();
        SessionStore store = (SessionStore) Proxy.newProxyInstance(SessionStore.class.getClassLoader(),
                new Class<?>[]{SessionStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals(failedMethod)) throw new IllegalStateException(detail);
                    return switch (method.getName()) {
                        case "id" -> "broken";
                        case "isAvailable" -> false;
                        case "unavailableReason" -> "policy";
                        case "load" -> { loads.incrementAndGet(); throw new AssertionError("unexpected load"); }
                        default -> throw new AssertionError(method.getName());
                    };
                });
        var result = assertDoesNotThrow(() -> new LocalRecoverySnapshots(List.of(store)).read("broken"));
        assertTrue(result.snapshot().isEmpty());
        assertEquals(SessionStoreException.Code.UNSPECIFIED, result.problem().orElseThrow().code());
        assertEquals(detail, result.problem().orElseThrow().detail());
        assertEquals(0, loads.get());
    }
}
