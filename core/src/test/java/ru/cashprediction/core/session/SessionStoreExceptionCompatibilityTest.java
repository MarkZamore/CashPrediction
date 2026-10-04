package ru.cashprediction.core.session;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** Сохраняет исходную совместимость checked exception при добавлении машинной категории. */
class SessionStoreExceptionCompatibilityTest {
    /** Конструктор только с message оставляет разрешённой позднюю установку причины. */
    @Test void legacyAndTypedMessageConstructorsRetainInitCauseSemantics() {
        var cause = new java.io.IOException("local-cause");
        var legacy = new SessionStoreException("legacy-detail");
        assertSame(legacy, legacy.initCause(cause));
        assertSame(cause, legacy.getCause());
        assertEquals(SessionStoreException.Code.UNSPECIFIED, legacy.code());
        assertEquals("legacy-detail", legacy.getMessage());
        var typed = new SessionStoreException(SessionStoreException.Code.CORRUPT, "typed-detail");
        assertSame(typed, typed.initCause(cause));
        assertSame(cause, typed.getCause());
        var original = new SessionStoreException("original", cause);
        assertSame(cause, original.getCause());
        assertThrows(IllegalStateException.class, () -> original.initCause(cause));
    }
}
