package ru.cashprediction.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Тесты модели снимка: словарь окон, нормализация записей, неизменяемость и проверки инвариантов.
 */
class SessionModelTest {

    @Test
    void windowTypeDictionaryMatchesPlan() {
        assertEquals(List.of("NEW_PLAN_WIZARD", "PLAN_SETTINGS", "RULE_EDITOR", "ONE_TIME_EDITOR", "ADJUSTMENT_EDITOR",
                "GOAL_CALCULATOR", "TEXT_INPUT", "CHOICE", "ALERT", "CSV_EXPORT", "QUICK_EDIT_POPUP"),
                java.util.Arrays.stream(WindowType.values()).map(Enum::name).toList());
        assertEquals(List.of("name", "currency", "startDate", "startBalance", "horizonKind", "horizonValue", "horizonUntil",
                "cushion", "quickIncomeTitle", "quickIncomeAmount", "quickIncomeDay", "quickExpenseTitle",
                "quickExpenseAmount", "quickExpenseDay"), WindowType.NEW_PLAN_WIZARD.fieldIds());
        assertEquals(List.of("page"), WindowType.NEW_PLAN_WIZARD.contextKeys());
        assertEquals(List.of("name", "currency", "startDate", "startBalance", "horizonKind", "horizonValue", "horizonUntil",
                "cushion", "note", "goalTitle", "goalTarget", "goalDate"), WindowType.PLAN_SETTINGS.fieldIds());
        assertEquals(List.of(), WindowType.PLAN_SETTINGS.contextKeys());
        assertEquals(List.of("mode", "ruleId"), WindowType.RULE_EDITOR.contextKeys());
        assertEquals(List.of("title", "kind", "amount", "category", "recurrenceKind", "dayOfMonth", "everyN", "weekday",
                "monthDay", "fromEnabled", "from", "untilEnabled", "until", "weekendPolicy", "enabled", "note"),
                WindowType.RULE_EDITOR.fieldIds());
        assertEquals(List.of("mode", "txId"), WindowType.ONE_TIME_EDITOR.contextKeys());
        assertEquals(List.of("date", "title", "kind", "amount", "category", "note"), WindowType.ONE_TIME_EDITOR.fieldIds());
        assertEquals(List.of("ruleId", "originalDate"), WindowType.ADJUSTMENT_EDITOR.contextKeys());
        assertEquals(List.of("action", "amount", "date", "note"), WindowType.ADJUSTMENT_EDITOR.fieldIds());
        assertEquals(List.of("target", "byDateEnabled", "byDate", "extraSaving"), WindowType.GOAL_CALCULATOR.fieldIds());
        assertEquals(List.of("purpose"), WindowType.TEXT_INPUT.contextKeys());
        assertEquals(List.of("value"), WindowType.CHOICE.fieldIds());
        assertEquals(List.of("purpose", "targetId"), WindowType.ALERT.contextKeys());
        assertEquals(List.of(), WindowType.ALERT.fieldIds());
        assertEquals(List.of("separator", "bom", "range"), WindowType.CSV_EXPORT.fieldIds());
        assertEquals(List.of("ruleId", "originalDate"), WindowType.QUICK_EDIT_POPUP.contextKeys());
        assertEquals(List.of("amount"), WindowType.QUICK_EDIT_POPUP.fieldIds());
        for (WindowType type : WindowType.values()) {
            boolean expectedModal = type != WindowType.GOAL_CALCULATOR && type != WindowType.QUICK_EDIT_POPUP;
            assertEquals(expectedModal, type.defaultModal(), type.name());
            assertFalse(type.title().isBlank());
            assertEquals(type, WindowType.fromName(type.name()).orElseThrow());
        }
        assertTrue(WindowType.fromName("FUTURE_WINDOW").isEmpty());
        assertTrue(WindowType.fromName(null).isEmpty());
    }

    @Test
    void recordsNormalizeNullsAndCopyMapsInOrder() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("z", "1");
        fields.put("a", null);
        WindowState state = new WindowState("w1", WindowType.CHOICE, true, null, null, null, fields);
        assertEquals("main", state.ownerId());
        assertEquals(Map.of(), state.context());
        assertEquals(List.of("z", "a"), List.copyOf(state.fields().keySet()));
        assertEquals("", state.field("a"));
        fields.put("b", "2");
        assertFalse(state.fields().containsKey("b"), "запись не должна видеть изменения исходной карты");
        assertThrows(UnsupportedOperationException.class, () -> state.fields().put("x", "y"));

        MainWindowState main = new MainWindowState(null, false, null, null, null, null, null, null);
        assertEquals(MainWindowState.empty(), main);
        assertNull(main.bounds());
        assertEquals(PlanState.CLEAN, new PlanState(false, null));

        SessionSnapshot snapshot = new SessionSnapshot(1, Instant.EPOCH, "fx", null, null, null);
        assertEquals(MainWindowState.empty(), snapshot.main());
        assertEquals(List.of(), snapshot.windows());
    }

    @Test
    void invariantsAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new WindowBounds(0, 0, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> new WindowBounds(Double.NaN, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new WindowState(" ", WindowType.ALERT, true, "main", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SessionMarker("paused", 1, Instant.EPOCH, "fx"));
        assertThrows(IllegalArgumentException.class, () -> new SessionMarker("running", 1, Instant.EPOCH, "FX"));
        IllegalArgumentException newer = assertThrows(IllegalArgumentException.class,
                () -> new SessionSnapshot(2, Instant.EPOCH, "fx", null, null, null));
        assertTrue(newer.getMessage().contains("более новой версией"), newer.getMessage());
        Map<String, Boolean> badFilters = new HashMap<>();
        badFilters.put("x", null);
        assertThrows(NullPointerException.class, () -> new MainWindowState(null, false, "", "", "", badFilters, "", ""));
    }

    @Test
    void markerAndSnapshotHelpers() {
        SessionMarker running = SessionFixtures.running("swing");
        assertTrue(running.isRunning());
        assertEquals(new SessionMarker("closed", 12345, SessionFixtures.STARTED, "swing"), running.closed());
        SessionSnapshot snapshot = SessionFixtures.simple("fx");
        assertTrue(snapshot.sameContent(snapshot.withSavedAt(Instant.EPOCH)));
        assertFalse(snapshot.sameContent(null));
        assertFalse(snapshot.sameContent(new SessionSnapshot(1, snapshot.savedAt(), "fx",
                snapshot.main().withSelectedRowId("r1@2026-10-05"), snapshot.plan(), snapshot.windows())));
        assertEquals(WindowType.RULE_EDITOR, snapshot.window("w1").orElseThrow().type());
        assertEquals("JavaFX", SnapshotSchema.clientTitle("fx"));
        assertEquals(List.of("fx", "swing", "web"), SnapshotSchema.CLIENTS);
        assertEquals(1, SnapshotSchema.CURRENT);
    }
}
