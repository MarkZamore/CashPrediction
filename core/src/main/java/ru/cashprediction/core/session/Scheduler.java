package ru.cashprediction.core.session;

import java.time.Duration;

/**
 * Планировщик отложенных и периодических задач рекордера.
 *
 * <p>Отдельная абстракция вместо прямого {@code ScheduledExecutorService} нужна ради тестов:
 * фейковый планировщик с «виртуальным временем» позволяет детерминированно проверить debounce
 * 400 мс и таймер 5 с, не засыпая в тестах. Боевая реализация — {@link ExecutorScheduler}.</p>
 *
 * <p>Реализации обязаны быть потокобезопасными.</p>
 */
public interface Scheduler {

    /**
     * Отменяемая запланированная задача.
     */
    interface Task {
        /**
         * Отменяет задачу; если она уже выполняется, выполнение не прерывается.
         */
        void cancel();
    }

    /**
     * Выполняет задачу один раз после задержки.
     *
     * @param action задача
     * @param delay  задержка
     * @return отменяемая задача
     */
    Task schedule(Runnable action, Duration delay);

    /**
     * Выполняет задачу периодически.
     *
     * @param action       задача
     * @param initialDelay задержка первого запуска
     * @param period       период
     * @return отменяемая задача
     */
    Task scheduleAtFixedRate(Runnable action, Duration initialDelay, Duration period);

    /**
     * Выполняет задачу в фоновом потоке как можно скорее.
     *
     * @param action задача
     */
    void execute(Runnable action);

    /**
     * Останавливает планировщик: новые задачи не принимаются, отложенные не выполняются.
     */
    void shutdown();
}
