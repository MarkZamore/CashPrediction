package ru.cashprediction.core.ui.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.ClientProfile;
import ru.cashprediction.core.model.Money;

/** Проверяет готовые модели сообщений, общие для трёх клиентов. */
class AlertCatalogTest {
    @Test void deleteRuleIsRestorableAndKeepsTarget() {
        AlertSpec alert = AlertCatalog.deleteRule("r1", "Аренда", Money.ofMajor(45_000), "₽", "ежемесячно 1", 2);
        assertEquals(AlertCatalog.PURPOSE_DELETE_RULE, alert.purpose());
        assertEquals("r1", alert.targetId());
        assertTrue(alert.restorable());
        assertEquals("delete", alert.defaultButtonId());
    }
    @Test void unsavedChangesHasThreeButtonsInOrder() {
        AlertSpec alert = AlertCatalog.unsavedChanges("Мой план");
        assertEquals(List.of("save", "dontSave", "cancel"), alert.buttons().stream().map(AlertButton::id).toList());
        assertFalse(alert.restorable());
    }
    @Test void webAlreadyRunningHasOnlyContinue() {
        AlertSpec alert = AlertCatalog.alreadyRunning(ClientProfile.web());
        assertEquals(List.of("continue"), alert.buttons().stream().map(AlertButton::id).toList());
    }
}
