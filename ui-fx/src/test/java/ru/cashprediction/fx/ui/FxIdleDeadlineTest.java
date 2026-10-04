package ru.cashprediction.fx.ui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет общий deadline настоящего FutureTask handoff без запуска JavaFX Toolkit. */
class FxIdleDeadlineTest {
    @Test void stalledQueueTimesOutAndCancelledActionNeverRuns() {
        var queue = new ArrayList<Runnable>();
        var called = new AtomicBoolean();
        long start = System.nanoTime();
        assertThrows(TimeoutException.class, () -> FxUiDriver.idleHandoff(
                start + Duration.ofMillis(40).toNanos(), () -> { called.set(true); return true; }, queue::add));
        assertTrue(System.nanoTime() - start < Duration.ofSeconds(1).toNanos());
        queue.forEach(Runnable::run);
        assertFalse(called.get());
    }
    @Test void successfulButLateDispatchCannotBypassCommonDeadline() {
        assertThrows(TimeoutException.class, () -> FxUiDriver.idleHandoff(
                System.nanoTime()+Duration.ofMillis(20).toNanos(), () -> true, task -> {
                    try { Thread.sleep(35); } catch (InterruptedException failure) { throw new AssertionError(failure); }
                    task.run();
                }));
    }
    @Test void expiredBudgetDoesNotSubmitAnotherQueueTask() {
        var queue = new ArrayList<Runnable>();
        assertThrows(TimeoutException.class, () -> FxUiDriver.idleHandoff(System.nanoTime()-1, () -> true, queue::add));
        assertTrue(queue.isEmpty());
    }
    @Test void interruptionIsPreservedAndCancelsPendingAction() throws Exception {
        var queued = new ArrayList<Runnable>();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedException.class, () -> FxUiDriver.idleHandoff(
                    System.nanoTime()+Duration.ofSeconds(1).toNanos(), () -> true, queued::add));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        queued.forEach(Runnable::run);
    }
    @Test void noExtraDebounceAndNormalQueueDrainRemainSupported() throws Exception {
        assertEquals(40,FxUiDriver.idleDelayMillis(0,5000));
        assertEquals(440,FxUiDriver.idleDelayMillis(400,5000));
        assertTrue(FxUiDriver.idleHandoff(System.nanoTime()+Duration.ofSeconds(1).toNanos(), () -> true,Runnable::run));
    }
}
