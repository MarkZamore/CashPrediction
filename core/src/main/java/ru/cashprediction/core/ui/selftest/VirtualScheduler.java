package ru.cashprediction.core.ui.selftest;

import java.time.Duration;
import java.util.PriorityQueue;
import ru.cashprediction.core.session.Scheduler;

/** Виртуальное время самотеста; периодические задачи не мешают достижению покоя. */
final class VirtualScheduler implements Scheduler {
    private final PriorityQueue<Entry> queue = new PriorityQueue<>();
    private long now, sequence;
    private boolean stopped;

    /** Задача с устойчивым порядком при одинаковом времени запуска. */
    private final class Entry implements Task, Comparable<Entry> {
        final Runnable action;
        final long order = sequence++;
        final long period;
        long due;
        boolean cancelled;
        Entry(Runnable action, long due, long period) { this.action = action; this.due = due; this.period = period; }
        /** Помечает задачу отменённой. */
        @Override public void cancel() { cancelled = true; }
        /** Сравнивает сроки и порядок постановки. */
        @Override public int compareTo(Entry other) { int c = Long.compare(due, other.due); return c == 0 ? Long.compare(order, other.order) : c; }
    }

    /** Планирует однократную задачу. */
    @Override public synchronized Task schedule(Runnable action, Duration delay) { return add(action, delay, 0); }
    /** Планирует периодическую задачу. */
    @Override public synchronized Task scheduleAtFixedRate(Runnable action, Duration initialDelay, Duration period) {
        if (period.toMillis() <= 0) throw new IllegalArgumentException("period");
        return add(action, initialDelay, period.toMillis());
    }
    private Task add(Runnable action, Duration delay, long period) {
        if (stopped || delay.isNegative()) throw new IllegalStateException("scheduler");
        Entry entry = new Entry(action, Math.addExact(now, delay.toMillis()), period);
        queue.add(entry); return entry;
    }
    /** Ставит фоновую задачу в очередь потока контроллера. */
    @Override public synchronized void execute(Runnable action) { add(action, Duration.ZERO, 0); }
    /** Останавливает и очищает очередь. */
    @Override public synchronized void shutdown() { stopped = true; queue.clear(); }

    synchronized void advance(Duration duration) {
        if (duration.isNegative()) throw new IllegalArgumentException("duration");
        long target = Math.addExact(now, duration.toMillis());
        int calls = 0;
        while (!queue.isEmpty() && queue.peek().due <= target) {
            Entry entry = queue.remove();
            if (entry.cancelled) continue;
            if (++calls > 10000) throw new IllegalStateException("scheduler livelock");
            now = entry.due;
            entry.action.run();
            if (!entry.cancelled && entry.period > 0 && !stopped) { entry.due += entry.period; queue.add(entry); }
        }
        now = target;
    }

    /** Исполняет только ближайшую работу, не прокручивая бессрочные таймеры. */
    synchronized void idle(Duration timeout) {
        long deadline = now + timeout.toMillis();
        for (int i = 0; i < 10000; i++) {
            queue.removeIf(e -> e.cancelled);
            long due = queue.stream().filter(e -> e.period == 0 && e.due <= now + 1000)
                    .mapToLong(e -> e.due).min().orElse(Long.MAX_VALUE);
            if (due == Long.MAX_VALUE) { advance(Duration.ZERO); return; }
            if (due > deadline) throw new IllegalStateException("idle timeout");
            advance(Duration.ofMillis(Math.max(0, due - now)));
        }
        throw new IllegalStateException("scheduler livelock");
    }
}
