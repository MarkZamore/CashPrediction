package ru.cashprediction.fx.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет отказы matcher без JavaFX, Robot, toolkit и доказательств краски. */
class FxInputAckMatcherTest {
    private static final FxInputAckMatcher.Point TARGET = new FxInputAckMatcher.Point(100, 200);

    /** Старое поколение и callback до arm не подтверждают новую команду даже в тех же координатах. */
    @Test void rejectsPreviousListenerAndCallbackBeforeArm() {
        var matcher = new FxInputAckMatcher();
        long old = matcher.armPointer(TARGET, 10);
        assertTrue(matcher.pointer(old, 11, TARGET, TARGET, false, true));
        long current = matcher.armPointer(TARGET, 20);
        assertFalse(matcher.pointerAcknowledged(TARGET));
        assertFalse(matcher.pointer(old, 21, TARGET, TARGET, false, true));
        assertFalse(matcher.pointer(current, 20, TARGET, TARGET, false, true));
        assertFalse(matcher.pointerAcknowledged(TARGET));
        assertTrue(matcher.status(TARGET).contains("staleCallbacks=1"));
        assertTrue(matcher.status(TARGET).contains("reason=callback-before-arm"));
    }

    /** Диагностика различает отсутствие события, pick, физическое несовпадение и синтетический ввод. */
    @Test void reportsReasonsAndObservedCoordinatesWithoutAcknowledgingFailures() {
        var matcher = new FxInputAckMatcher();
        long command = matcher.armPointer(TARGET, 10);
        assertTrue(matcher.status(TARGET).contains("reason=awaiting-event"));
        assertFalse(matcher.pointer(command, 11, TARGET, TARGET, false, false));
        assertTrue(matcher.status(TARGET).contains("reason=missing-pick"));
        assertFalse(matcher.pointer(command, 12, TARGET, new FxInputAckMatcher.Point(103, 200), false, true));
        assertTrue(matcher.status(TARGET).contains("reason=event-physical-mismatch"));
        assertFalse(matcher.pointer(command, 13, TARGET, TARGET, true, true));
        assertTrue(matcher.status(TARGET).contains("reason=synthesized-event"));
        assertTrue(matcher.status(TARGET).contains("delivered=null"));
        assertTrue(matcher.status(TARGET).contains("event=Point[x=100.0, y=200.0]"));
        assertFalse(matcher.pointerAcknowledged(TARGET));
    }

    /** NaN не проходит сравнение, а ошибка координат после успешного события отзывает pointer ack. */
    @Test void rejectsNonFiniteAndRevokesPriorPointerOnMismatch() {
        var matcher = new FxInputAckMatcher();
        long command = matcher.armPointer(TARGET, 10);
        assertTrue(matcher.pointer(command, 11, TARGET, TARGET, false, true));
        assertTrue(matcher.pointerAcknowledged(new FxInputAckMatcher.Point(101, 199)));
        assertFalse(matcher.pointerAcknowledged(new FxInputAckMatcher.Point(102, 200)));
        assertFalse(matcher.pointer(command, 12, new FxInputAckMatcher.Point(Double.NaN, 200), TARGET, false, true));
        assertTrue(matcher.status(TARGET).contains("reason=non-finite-coordinates"));
        assertFalse(matcher.pointerAcknowledged(TARGET));
        var elsewhere = new FxInputAckMatcher.Point(110, 200);
        assertFalse(matcher.pointer(command, 13, elsewhere, elsewhere, false, true));
        assertTrue(matcher.status(TARGET).contains("reason=requested-physical-mismatch"));
    }

    /** Release без press, прежний listener и прежний номер команды не подтверждают новый Tab. */
    @Test void requiresCurrentPressReleasePair() {
        var matcher = new FxInputAckMatcher();
        long old = matcher.armKey(10);
        matcher.key(old, 11, true, false);
        assertFalse(matcher.keyAcknowledged(old));
        assertTrue(matcher.status(TARGET).contains("reason=release-without-current-press"));
        matcher.key(old, 12, true, true); matcher.key(old, 13, true, false);
        assertTrue(matcher.keyAcknowledged(old));
        long current = matcher.armKey(20);
        matcher.key(old, 21, true, true); matcher.key(old, 22, true, false);
        assertFalse(matcher.keyAcknowledged(current));
        assertFalse(matcher.keyAcknowledged(old));
        matcher.key(current, 23, false, true); matcher.key(current, 24, true, false);
        assertFalse(matcher.keyAcknowledged(current));
        matcher.key(current, 25, true, true); matcher.key(current, 26, true, false);
        assertTrue(matcher.keyAcknowledged(current));
        assertTrue(matcher.status(TARGET).contains("nativeEventTime=unavailable"));
    }

    /** Tab сохраняет отдельный pointer-факт, а новое перемещение сбрасывает оба подтверждения. */
    @Test void keepsPointerSequenceSeparateFromKeyboardSequence() {
        var matcher = new FxInputAckMatcher();
        long pointer = matcher.armPointer(TARGET, 10);
        assertTrue(matcher.pointer(pointer, 11, TARGET, TARGET, false, true));
        long key = matcher.armKey(20);
        assertTrue(matcher.pointerAcknowledged(TARGET));
        matcher.key(key, 21, true, true); matcher.key(key, 22, true, false);
        assertTrue(matcher.keyAcknowledged(key));
        assertTrue(matcher.status(TARGET).contains("pointerSequence=" + pointer));
        matcher.armPointer(TARGET, 30);
        assertFalse(matcher.pointerAcknowledged(TARGET));
        assertFalse(matcher.keyAcknowledged(key));
    }

    /** Поздний callback старого listener не отменяет или переприсваивает подтверждённую новую команду. */
    @Test void staleCallbacksCannotOverwriteCurrentAcknowledgement() {
        var matcher = new FxInputAckMatcher();
        long old = matcher.armPointer(TARGET, 10);
        long current = matcher.armPointer(TARGET, 20);
        assertTrue(matcher.pointer(current, 21, TARGET, TARGET, false, true));
        assertFalse(matcher.pointer(old, 22, TARGET, TARGET, true, false));
        assertTrue(matcher.pointerAcknowledged(TARGET));
        long key = matcher.armKey(30);
        matcher.key(key, 31, true, true); matcher.key(key, 32, true, false);
        matcher.key(current, 33, true, false);
        assertTrue(matcher.keyAcknowledged(key));
        assertTrue(matcher.status(TARGET).contains("staleCallbacks=2"));
    }
}
