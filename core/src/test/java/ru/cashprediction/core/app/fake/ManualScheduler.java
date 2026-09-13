package ru.cashprediction.core.app.fake;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import ru.cashprediction.core.session.Scheduler;

/**
 * Планировщик с виртуальным временем для тестов контроллера: всё выполняется в потоке теста.
 *
 * <p>Отложенные и периодические задачи выполняются только в {@link #advance(Duration)} по порядку срока (при равном
 * сроке — по порядку постановки). Задачи {@link #execute(Runnable)} ставятся в очередь и выполняются в
 * {@link #runPending()} (так ответы на сообщения приходят после возврата из {@code showAlert}, как в настоящих
 * клиентах).</p>
 *
 * <p>Не потокобезопасен: только поток теста.</p>
 */
public final class ManualScheduler implements Scheduler {

    /** Запланированная задача. */
    private static final class Entry implements Task {
        private final Runnable action;
        private final long period;
        private final long order;
        private long due;
        private boolean cancelled;

        Entry(Runnable action, long due, long period, long order) {
            this.action = action;
            this.due = due;
            this.period = period;
            this.order = order;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Deque<Runnable> pending = new ArrayDeque<>();
    private long now;
    private long counter;
    private boolean shutdown;

    @Override
    public Task schedule(Runnable action, Duration delay) {
        Entry entry = new Entry(action, now + delay.toNanos(), 0, counter++);
        entries.add(entry);
        return entry;
    }

    @Override
    public Task scheduleAtFixedRate(Runnable action, Duration initialDelay, Duration period) {
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("period must be positive: " + period);
        }
        Entry entry = new Entry(action, now + initialDelay.toNanos(), period.toNanos(), counter++);
        entries.add(entry);
        return entry;
    }

    @Override
    public void execute(Runnable action) {
        pending.addLast(action);
    }

    @Override
    public void shutdown() {
        shutdown = true;
        entries.clear();
        pending.clear();
    }

    /** @return вызван ли {@link #shutdown()} */
    public boolean isShutdown() {
        return shutdown;
    }

    /**
     * Выполняет задачи очереди {@link #execute}, включая добавленные во время выполнения.
     *
     * @return сколько задач выполнено
     */
    public int runPending() {
        int done = 0;
        while (!pending.isEmpty()) {
            pending.removeFirst().run();
            done++;
        }
        return done;
    }

    /**
     * Сдвигает виртуальное время и выполняет наступившие задачи по порядку; после каждой — очередь {@link #execute}.
     *
     * @param duration на сколько сдвинуть
     */
    public void advance(Duration duration) {
        long target = now + duration.toNanos();
        runPending();
        while (true) {
            entries.removeIf(e -> e.cancelled);
            Entry next = null;
            for (Entry entry : entries) {
                if (entry.due <= target && (next == null || entry.due < next.due
                        || (entry.due == next.due && entry.order < next.order))) {
                    next = entry;
                }
            }
            if (next == null) {
                now = target;
                return;
            }
            now = next.due;
            if (next.period > 0) {
                next.due += next.period;
            } else {
                entries.remove(next);
            }
            next.action.run();
            runPending();
        }
    }

    /** @return текущее виртуальное время от создания */
    public Duration elapsed() {
        return Duration.ofNanos(now);
    }

    /** @return число активных отложенных задач */
    public int scheduledCount() {
        return (int) entries.stream().filter(e -> !e.cancelled).count();
    }

    /** @return число задач в очереди {@link #execute} */
    public int pendingCount() {
        return pending.size();
    }
}
