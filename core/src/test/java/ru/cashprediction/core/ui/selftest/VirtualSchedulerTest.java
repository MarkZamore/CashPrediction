package ru.cashprediction.core.ui.selftest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Проверки задержек, отмены и периодических задач без ожидания реального времени. */
class VirtualSchedulerTest {
    @Test void advancesInDeadlineOrderAndHonorsCancellation() {
        var scheduler = new VirtualScheduler(); var events = new ArrayList<String>();
        scheduler.schedule(() -> events.add("spinner"), Duration.ofMillis(600));
        scheduler.schedule(() -> events.add("filter"), Duration.ofMillis(300));
        scheduler.schedule(() -> events.add("cancelled"), Duration.ofMillis(200)).cancel();
        scheduler.advance(Duration.ofMillis(299)); assertTrue(events.isEmpty());
        scheduler.advance(Duration.ofMillis(1)); assertEquals(List.of("filter"), events);
        scheduler.idle(Duration.ofSeconds(1)); assertEquals(List.of("filter", "spinner"), events);
    }
    @Test void idleDoesNotLoopOverPeriodicAutosaveOrExpireStatus() {
        var scheduler = new VirtualScheduler(); int[] calls = {0};
        scheduler.scheduleAtFixedRate(() -> calls[0]++, Duration.ofSeconds(5), Duration.ofSeconds(5));
        scheduler.idle(Duration.ofSeconds(20)); assertEquals(0, calls[0]);
        scheduler.advance(Duration.ofSeconds(15)); assertEquals(3, calls[0]);
        scheduler.shutdown(); scheduler.advance(Duration.ofSeconds(15)); assertEquals(3, calls[0]);
    }
    @Test void idleReportsTimeoutAndPreservesUnexecutedTask() {
        var scheduler = new VirtualScheduler(); int[] calls = {0};
        scheduler.schedule(() -> calls[0]++, Duration.ofMillis(600));
        assertThrows(IllegalStateException.class, () -> scheduler.idle(Duration.ofMillis(500)));
        assertEquals(0, calls[0]); scheduler.advance(Duration.ofMillis(600)); assertEquals(1, calls[0]);
    }
}
