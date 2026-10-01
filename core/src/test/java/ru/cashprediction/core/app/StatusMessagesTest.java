package ru.cashprediction.core.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.view.status.StatusLevel;

/** Проверяет приоритет и срок показа сообщений строки состояния. */
class StatusMessagesTest {

    private static final Instant NOW = Instant.parse("2026-09-29T06:00:00Z");

    @Test
    void hoverTakesPriorityThenTemporaryThenAlphabeticalPersistentMessage() {
        StatusMessages messages = StatusMessages.EMPTY
                .withPersistent("zeta", "Z", StatusLevel.WARN)
                .withPersistent("alpha", "A", StatusLevel.ERROR)
                .show("temporary", StatusLevel.SUCCESS, NOW);
        assertEquals("temporary", messages.visible(NOW).orElseThrow().text());
        assertEquals("A", messages.visible(NOW.plusSeconds(11)).orElseThrow().text());
        assertEquals("hint", messages.hover("hint").visible(NOW).orElseThrow().text());
    }

    @Test
    void expiryIsExclusiveAndRemovingUnknownCauseKeepsTheSameValue() {
        StatusMessages messages = StatusMessages.EMPTY.show("temporary", StatusLevel.INFO, NOW);
        assertTrue(messages.visible(NOW.plusSeconds(10)).isEmpty());
        assertEquals(messages, messages.withoutPersistent("missing"));
    }
}
