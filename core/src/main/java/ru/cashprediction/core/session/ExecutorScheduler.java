package ru.cashprediction.core.session;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Боевой {@link Scheduler} на одном фоновом потоке-демоне.
 *
 * <p>Один поток — осознанный выбор: все записи снимков идут строго последовательно, и две фоновые
 * записи никогда не пересекаются. Поток-демон не мешает JVM завершиться, если программа закрыта
 * без {@link SessionRecorder#shutdownClean()}.</p>
 *
 * <p>После {@link #shutdown()} новые задачи молча отбрасываются: рекордер может попытаться
 * запланировать запись в момент выхода, и исключение там было бы бесполезным.</p>
 *
 * <p>Класс потокобезопасен (опирается на {@link ScheduledThreadPoolExecutor}).</p>
 */
public final class ExecutorScheduler implements Scheduler {

    /** Исполнитель с одним потоком. */
    private final ScheduledThreadPoolExecutor executor;

    /**
     * Создаёт планировщик с потоком-демоном.
     *
     * @param threadName имя потока (видно в дампах потоков и отладчике)
     */
    public ExecutorScheduler(String threadName) {
        executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
        // Отменённые debounce-задачи не должны копиться в очереди при быстром вводе.
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    @Override
    public Task schedule(Runnable action, Duration delay) {
        try {
            ScheduledFuture<?> future = executor.schedule(action, delay.toNanos(), TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        } catch (RejectedExecutionException afterShutdown) {
            return () -> { };
        }
    }

    @Override
    public Task scheduleAtFixedRate(Runnable action, Duration initialDelay, Duration period) {
        try {
            ScheduledFuture<?> future = executor.scheduleAtFixedRate(action, initialDelay.toNanos(), period.toNanos(),
                    TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        } catch (RejectedExecutionException afterShutdown) {
            return () -> { };
        }
    }

    @Override
    public void execute(Runnable action) {
        try {
            executor.execute(action);
        } catch (RejectedExecutionException afterShutdown) {
            // Планировщик уже остановлен при выходе: запись больше не нужна.
        }
    }

    @Override
    public void shutdown() {
        executor.shutdown();
    }
}
