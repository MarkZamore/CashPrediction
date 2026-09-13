package ru.cashprediction.fx.session;

import javafx.application.Platform;
import ru.cashprediction.core.session.UiExecutor;

/**
 * Доступ ядра к UI-потоку JavaFX (FX Application Thread).
 *
 * <p>Рекордер сессии снимает состояние окон только в UI-потоке: из фонового потока он просит
 * выполнить задачу через {@link Platform#runLater(Runnable)}. Аналоги: Swing —
 * {@code SwingUtilities.invokeLater}, Web — прямое выполнение ({@link UiExecutor#direct()}).</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class FxUiExecutor implements UiExecutor {

    /** Создаёт исполнитель. */
    public FxUiExecutor() {
    }

    /**
     * Выполняет задачу в UI-потоке: сразу, если вызов уже из него, иначе через {@code runLater}.
     *
     * @param task задача
     */
    @Override
    public void execute(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    /**
     * Проверяет, что текущий поток — FX Application Thread.
     *
     * @return {@code true} в UI-потоке JavaFX
     */
    @Override
    public boolean isUiThread() {
        return Platform.isFxApplicationThread();
    }
}
