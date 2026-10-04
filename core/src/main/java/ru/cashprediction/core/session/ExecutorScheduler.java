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

    /**
     * Планирует однократное выполнение на общем фоновом потоке после задержки.
     * Нулевая или отрицательная задержка допускает немедленный запуск.
     * Отмена удаляет ожидающую задачу из очереди, не прерывая уже начатое выполнение.
     *
     * @param action задача для фонового выполнения
     * @param delay задержка, переводимая в наносекунды
     * @return средство отмены; после остановки планировщика задача отбрасывается
     *         и возвращается средство отмены без действия
     * @throws NullPointerException если задержка равна {@code null},
     *                              либо задача равна {@code null} при приёме исполнителем
     * @throws ArithmeticException если задержка не представима в наносекундах типом {@code long}
     */
    @Override
    public Task schedule(Runnable action, Duration delay) {
        try {
            ScheduledFuture<?> future = executor.schedule(action, delay.toNanos(), TimeUnit.NANOSECONDS);
            return () -> future.cancel(false);
        } catch (RejectedExecutionException afterShutdown) {
            return () -> { };
        }
    }

    /**
     * Планирует повторные запуски с фиксированным периодом от первого планового запуска,
     * а не от завершения предыдущего. Выполнения не пересекаются; при длительной работе
     * очередной запуск запаздывает. Необработанное исключение задачи прекращает повторы.
     * Отмена запрещает дальнейшие запуски, не прерывая текущий.
     *
     * @param action периодическая задача на общем фоновом потоке
     * @param initialDelay задержка первого запуска; нулевая или отрицательная означает немедленный запуск
     * @param period положительный период между плановыми запусками
     * @return средство отмены; после остановки задача отбрасывается и отмена ничего не делает
     * @throws NullPointerException если задача или одна из длительностей равна {@code null}
     * @throws IllegalArgumentException если период нулевой или отрицательный
     * @throws ArithmeticException если одна из длительностей не представима в наносекундах типом {@code long}
     */
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

    /**
     * Ставит задачу в очередь общего фонового потока без задержки.
     * Вызов не ждёт завершения задачи; после остановки планировщика задача молча отбрасывается.
     *
     * @param action задача для последовательного выполнения вместе с остальными задачами планировщика
     * @throws NullPointerException если задача равна {@code null}
     */
    @Override
    public void execute(Runnable action) {
        try {
            executor.execute(action);
        } catch (RejectedExecutionException afterShutdown) {
            // Планировщик уже остановлен при выходе: запись больше не нужна.
        }
    }

    /**
     * Начинает остановку планировщика: новые задачи отбрасываются, ожидающие отложенные
     * и периодические задачи отменяются. Уже выполняющаяся задача не прерывается;
     * вызов не ждёт завершения потока. Повторная остановка допустима.
     */
    @Override
    public void shutdown() {
        executor.shutdown();
    }
}
