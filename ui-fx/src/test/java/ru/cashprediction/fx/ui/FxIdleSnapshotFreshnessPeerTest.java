package ru.cashprediction.fx.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.session.*;
import ru.cashprediction.core.session.store.RegistrySessionStore;

/** Проверяет свежесть snapshot barrier настоящим recorder и memory backend без запуска FX Toolkit. */
class FxIdleSnapshotFreshnessPeerTest {
    @TempDir Path home;

    /** Предыдущий успешный status не должен подтверждать запись нового ещё ожидающего snapshot. */
    @Test void previousOutcomeMustNotAcknowledgePendingNewCapture() throws Exception {
        try (Fixture fixture = new Fixture(home)) {
            fixture.draft.set("new partially-entered draft");
            fixture.recorder.touch();
            fixture.scheduler.advance(Duration.ofMillis(FxUiDriver.idleDelayMillis(0, 5000)));
            assertEquals("old draft", fixture.store.load().orElseThrow().main().filterText());
            assertEquals("old draft", fixture.recorder.lastCaptured().orElseThrow().main().filterText());
            boolean readyBeforeCapture = FxUiDriver.snapshotStoresReady(fixture.recorder, fixture.outcomes);
            fixture.scheduler.advance(Duration.ofMillis(360));
            assertEquals("new partially-entered draft", fixture.store.load().orElseThrow().main().filterText());
            System.out.println("PEER barrierAt40=" + readyBeforeCapture + " persistedAt40=old persistedAt400=new");
            assertFalse(readyBeforeCapture, "Старый StoreStatus не является receipt нового pending capture");
        }
    }

    /** Положительный контроль: действительный debounce заканчивается записью без фиксированной задержки каждого dump. */
    @Test void genuineRecorderCompletesNewCaptureAtItsOwnDebounce() throws Exception {
        try (Fixture fixture = new Fixture(home)) {
            fixture.draft.set("new draft");
            fixture.recorder.touch();
            fixture.scheduler.advance(Duration.ofMillis(399));
            assertEquals("old draft", fixture.store.load().orElseThrow().main().filterText());
            fixture.scheduler.advance(Duration.ofMillis(1));
            assertEquals("new draft", fixture.store.load().orElseThrow().main().filterText());
            assertEquals("new draft", fixture.recorder.lastCaptured().orElseThrow().main().filterText());
            assertTrue(FxUiDriver.snapshotStoresReady(fixture.recorder, fixture.outcomes));
        }
    }

    /** Собственный lifecycle actual core recorder/store; часы заморожены, scheduler управляется отдельно. */
    @Test void finalUiTurnRechecksActualTouchBetweenFirstTrueAndFinalDrain() throws Exception {
        try (Fixture fixture = new Fixture(home)) {
            var dispatches = new java.util.concurrent.atomic.AtomicInteger();
            FxUiDriver.awaitSnapshotIdle(System.nanoTime()+Duration.ofSeconds(2).toNanos(),
                    () -> FxUiDriver.snapshotStoresReady(fixture.recorder,fixture.outcomes), task -> {
                        int turn = dispatches.incrementAndGet();
                        if(turn == 2) {
                            fixture.draft.set("new after first ready");
                            fixture.recorder.touch();
                        }
                        task.run();
                        if(turn == 2) {
                            assertFalse(fixture.recorder.isSnapshotIdle());
                            fixture.scheduler.advance(Duration.ofMillis(400));
                        }
                    });
            assertTrue(dispatches.get() >= 4,"second false must return to wait, not accept final drain");
            assertEquals("new after first ready",fixture.store.load().orElseThrow().main().filterText());
        }
    }
    /** Локальное виртуальное время FX-теста, без зависимости от тестовых классов core.
     * Только поток теста; очередь execute дренируется после каждого наступившего таймера.
     */
    private static final class ManualScheduler implements Scheduler {
        /** Отменяемый таймер с устойчивым порядком одинаковых сроков. */
        private static final class Entry implements Task {
            private final Runnable action;
            private final long period;
            private final long order;
            private long due;
            private boolean cancelled;

            private Entry(Runnable action, long due, long period, long order) {
                this.action = action;
                this.due = due;
                this.period = period;
                this.order = order;
            }

            /** Отменяет ещё не выполненный таймер. */
            @Override public void cancel() { cancelled = true; }
        }

        private final List<Entry> entries = new ArrayList<>();
        private final Deque<Runnable> pending = new ArrayDeque<>();
        private long now;
        private long counter;

        /** Ставит однократный таймер. */
        @Override public Task schedule(Runnable action, Duration delay) {
            Entry entry = new Entry(action, now + delay.toNanos(), 0, counter++);
            entries.add(entry);
            return entry;
        }

        /** Ставит периодический таймер с положительным периодом. */
        @Override public Task scheduleAtFixedRate(Runnable action, Duration initialDelay, Duration period) {
            if (period.isZero() || period.isNegative()) {
                throw new IllegalArgumentException("period must be positive: " + period);
            }
            Entry entry = new Entry(action, now + initialDelay.toNanos(), period.toNanos(), counter++);
            entries.add(entry);
            return entry;
        }

        /** Оставляет фоновую работу в очереди до дренирования. */
        @Override public void execute(Runnable action) { pending.addLast(action); }

        /** Удаляет собственные таймеры и очередь при завершении fixture. */
        @Override public void shutdown() { entries.clear(); pending.clear(); }

        private void runPending() {
            while (!pending.isEmpty()) pending.removeFirst().run();
        }

        private void advance(Duration duration) {
            long target = now + duration.toNanos();
            runPending();
            while (true) {
                entries.removeIf(entry -> entry.cancelled);
                Entry next = null;
                for (Entry entry : entries) {
                    if (entry.due <= target && (next == null || entry.due < next.due
                            || (entry.due == next.due && entry.order < next.order))) {
                        next = entry;
                    }
                }
                if (next == null) { now = target; return; }
                now = next.due;
                if (next.period > 0) next.due += next.period;
                else entries.remove(next);
                next.action.run();
                runPending();
            }
        }
    }

    /** Собственный lifecycle actual core recorder/store; часы заморожены, scheduler управляется отдельно. */
    private static final class Fixture implements AutoCloseable {
        private final AtomicReference<String> draft = new AtomicReference<>("old draft");
        private final ManualScheduler scheduler = new ManualScheduler();
        private final RegistrySessionStore store;
        private final List<StoreStatus> outcomes = new ArrayList<>();
        private final SessionRecorder recorder;

        private Fixture(Path home) {
            store = RegistrySessionStore.inMemory("fx", home.resolve("CashMemory"));
            SnapshotSource source = new SnapshotSource() {
                /** Снимает текущее введённое значение, не подменяя работу recorder. */
                @Override public MainWindowState captureMain() {
                    return new MainWindowState(null, false, "TABLE", "", "12m", Map.of(), draft.get(), "");
                }
                /** Возвращает неизменённый план. */
                @Override public PlanState capturePlan() { return PlanState.CLEAN; }
            };
            recorder = new SessionRecorder("fx", List.of(store), UiExecutor.direct(), source, scheduler,
                    Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 42);
            recorder.addStatusListener(status -> {
                outcomes.removeIf(previous -> previous.storeId().equals(status.storeId()));
                outcomes.add(status);
            });
            recorder.start();
            recorder.saveNow();
        }

        /** Останавливает только собственные задачи; реестр ОС не используется. */
        @Override public void close() { recorder.shutdownClean(); scheduler.shutdown(); }
    }
}
