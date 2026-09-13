package ru.cashprediction.core.session;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Планировщик с виртуальным временем для детерминированных тестов рекордера.
 *
 * <p>Отложенные задачи выполняются только в {@link #advance(Duration)} в потоке теста. Фоновые задачи
 * ({@link #execute(Runnable)}) по режиму: сразу ({@link Mode#SYNC}), в очередь до {@link #runQueued()}
 * ({@link Mode#QUEUE}) или в настоящем отдельном потоке ({@link Mode#THREAD}).</p>
 */
final class FakeScheduler implements Scheduler {

    /** Режим выполнения фоновых задач. */
    enum Mode { SYNC, QUEUE, THREAD }

    /** Запланированная задача. */
    private static final class Entry implements Task {
        private final Runnable action;
        private final long period;
        private long due;
        private volatile boolean cancelled;

        Entry(Runnable action, long due, long period) {
            this.action = action;
            this.due = due;
            this.period = period;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Runnable> queued = new ArrayList<>();
    /** Потоки, запущенные в режиме {@link Mode#THREAD}. */
    final List<Thread> threads = new CopyOnWriteArrayList<>();
    private long now;
    volatile Mode mode = Mode.SYNC;
    volatile boolean shutdown;

    @Override
    public synchronized Task schedule(Runnable action, Duration delay) {
        Entry entry = new Entry(action, now + delay.toNanos(), 0);
        entries.add(entry);
        return entry;
    }

    @Override
    public synchronized Task scheduleAtFixedRate(Runnable action, Duration initialDelay, Duration period) {
        Entry entry = new Entry(action, now + initialDelay.toNanos(), period.toNanos());
        entries.add(entry);
        return entry;
    }

    @Override
    public void execute(Runnable action) {
        switch (mode) {
            case SYNC -> action.run();
            case QUEUE -> {
                synchronized (this) {
                    queued.add(action);
                }
            }
            case THREAD -> {
                Thread thread = new Thread(action, "fake-background");
                threads.add(thread);
                thread.start();
            }
        }
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    /**
     * Сдвигает виртуальное время, выполняя наступившие задачи по порядку.
     *
     * @param duration на сколько сдвинуть
     */
    void advance(Duration duration) {
        long target;
        synchronized (this) {
            target = now + duration.toNanos();
        }
        while (true) {
            Entry next = null;
            synchronized (this) {
                entries.removeIf(e -> e.cancelled);
                for (Entry entry : entries) {
                    if (entry.due <= target && (next == null || entry.due < next.due)) {
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
            }
            next.action.run();
        }
    }

    /** Выполняет накопленные фоновые задачи. */
    void runQueued() {
        List<Runnable> copy;
        synchronized (this) {
            copy = new ArrayList<>(queued);
            queued.clear();
        }
        copy.forEach(Runnable::run);
    }

    /** @return число активных запланированных задач */
    synchronized int pendingCount() {
        return (int) entries.stream().filter(e -> !e.cancelled).count();
    }
}
