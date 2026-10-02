package ru.cashprediction.parity.audit;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Защищает свежие native references от принятия старых suffix-lost JSON без GUI и реестра. */
final class NativeReferenceFidelityTest {
    /** Оба разделителя допустимы; неверный и стёртый client suffix не доказывают prefix-fix. */
    @Test void requiresActualPreservedClientSuffix() {
        NativeAllowanceReferenceTest.requirePreservedNode("HKCU\\Prefs\\<node>\\fx (JSON)", "fx");
        NativeAllowanceReferenceTest.requirePreservedNode("HKCU/Prefs/<node>/swing (JSON)", "swing");
        for (String details : java.util.List.of("HKCU/Prefs/<node> (JSON)",
                "HKCU/Prefs/<node>/swing (JSON)", "HKCU/Prefs/<node>/fx-extra (JSON)"))
            assertThrows(AssertionError.class, () -> NativeAllowanceReferenceTest.requirePreservedNode(details, "fx"));
    }

    /** Сырой UUID и модельный клиент не принимаются вместо корректной нормализации native capture. */
    @Test void rawNodeAndNonNativeClientAreRejected() {
        assertThrows(AssertionError.class, () -> NativeAllowanceReferenceTest.requirePreservedNode(
                "HKCU/Prefs/ru/cashprediction/selftest/00000000-0000-0000-0000-000000000001/fx (JSON)", "fx"));
        assertThrows(IllegalArgumentException.class,
                () -> NativeAllowanceReferenceTest.requirePreservedNode("<node>/web", "web"));
    }
}
