package ru.cashprediction.swing.session;

import javax.swing.SwingUtilities;
import ru.cashprediction.core.session.UiExecutor;

/**
 * Доступ ядра к потоку диспетчеризации событий Swing (EDT).
 *
 * <p>{@code SessionRecorder} снимает состояние окон только в UI-потоке, а пишет снимки в фоновом потоке.
 * Эта реализация связывает его со Swing: {@link SwingUtilities#invokeLater} и
 * {@link SwingUtilities#isEventDispatchThread()}.</p>
 *
 * <p>Класс без состояния и потокобезопасен: {@link #execute(Runnable)} вызывается из фоновых потоков рекордера.</p>
 */
public final class SwingUiExecutor implements UiExecutor {

    /**
     * Создаёт исполнитель.
     */
    public SwingUiExecutor() {
    }

    /**
     * Выполняет задачу в EDT: сразу, если вызов уже в EDT, иначе через {@code invokeLater}.
     *
     * @param task задача
     */
    @Override
    public void execute(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * Проверяет, что текущий поток — EDT.
     *
     * @return {@code true} в потоке диспетчеризации событий Swing
     */
    @Override
    public boolean isUiThread() {
        return SwingUtilities.isEventDispatchThread();
    }
}
