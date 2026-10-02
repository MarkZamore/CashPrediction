package ru.cashprediction.web.ui;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.Consumer;
import ru.cashprediction.core.session.Scheduler;
import ru.cashprediction.core.session.UiExecutor;

/** Единственный поток контроллера; таймеры возвращают задачи в него, HTTP ждёт отдельно. */
public final class ControllerThread implements UiExecutor, Scheduler, AutoCloseable {
    private volatile Thread owner;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "cashprediction-controller");
        thread.setDaemon(true);
        owner = thread;
        return thread;
    });
    private final List<ScheduledFuture<?>> timers = new CopyOnWriteArrayList<>();
    private boolean timersStopped;
    private Consumer<Throwable> errors = Throwable::printStackTrace;

    /** Задаёт обработчик исключений фоновых задач в потоке контроллера. */
    public void onError(Consumer<Throwable> handler) { errors = handler; }

    /** Возвращает результат задачи, не блокируя поток контроллера. */
    public <T> CompletableFuture<T> submit(Callable<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        executor.execute(() -> {
            try { result.complete(task.call()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }

    /** {@inheritDoc} */
    @Override public void execute(Runnable task) {
        executor.execute(() -> {
            try { task.run(); }
            catch (Throwable error) { errors.accept(error); }
        });
    }
    /** {@inheritDoc} */
    @Override public boolean isUiThread() { return Thread.currentThread() == owner; }
    /** Проверяет принадлежность вызывающего потока. */
    public void check() { if (!isUiThread()) throw new IllegalStateException("ControllerThread"); }
    /** {@inheritDoc} */
    @Override public synchronized Task schedule(Runnable action, Duration delay) {
        if (timersStopped) return () -> { };
        ScheduledFuture<?> future = executor.schedule(() -> guarded(action), delay.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        timers.add(future);
        return () -> { future.cancel(false); timers.remove(future); };
    }
    /** {@inheritDoc} */
    @Override public synchronized Task scheduleAtFixedRate(Runnable action, Duration delay, Duration period) {
        if (timersStopped) return () -> { };
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> guarded(action), delay.toMillis(), period.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS);
        timers.add(future);
        return () -> { future.cancel(false); timers.remove(future); };
    }
    private void guarded(Runnable action) {
        try { action.run(); } catch (Throwable error) { errors.accept(error); }
        timers.removeIf(java.util.concurrent.Future::isDone);
    }
    /** Останавливает таймеры ядра; чтение экрана остановленного сервера ещё доступно. */
    @Override public synchronized void shutdown() {
        timersStopped = true;
        timers.forEach(future -> future.cancel(false));
        timers.clear();
    }
    /** Освобождает поток при закрытии HTTP-сервера. */
    @Override public void close() { shutdown(); executor.shutdownNow(); }
}
