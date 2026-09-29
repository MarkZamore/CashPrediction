package ru.cashprediction.core.ui.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.model.Money;

/** Проверяет однократную регистрацию восстанавливаемого предупреждения. */
class AlertSessionTest {
    @Test void lifecycleRegistersAndUnregistersOnlyOnce() {
        List<String> calls = new ArrayList<>();
        AlertSession session = new AlertSession("w1", "main", AlertCatalog.deleteRule("r1", "Аренда", Money.ofMajor(1), "₽", "ежемесячно", 0), new AlertSession.Host() {
            @Override public void registered(AlertSession value) { calls.add("register"); }
            @Override public void unregistered(AlertSession value) { calls.add("unregister"); }
        });
        session.shown(); session.shown(); session.closed(); session.closed();
        assertEquals(List.of("register", "unregister"), calls);
        assertEquals("deleteRule", session.captureState().contextValue("purpose"));
        assertEquals("r1", session.captureState().contextValue("targetId"));
    }
}
