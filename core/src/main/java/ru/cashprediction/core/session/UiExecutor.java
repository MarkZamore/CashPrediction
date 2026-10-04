package ru.cashprediction.core.session;

/**
 * Доступ к UI-потоку клиента без зависимости ядра от JavaFX или Swing.
 *
 * <p>Реализации: JavaFX — {@code Platform.runLater}/{@code Platform.isFxApplicationThread},
 * Swing — {@code SwingUtilities.invokeLater}/{@code isEventDispatchThread}, web-сервер и тесты —
 * прямое выполнение в вызывающем потоке.</p>
 *
 * <p>Реализации обязаны быть потокобезопасными: {@link #execute(Runnable)} вызывается из фоновых
 * потоков рекордера.</p>
 */
public interface UiExecutor {

    /**
     * Выполняет задачу в UI-потоке: асинхронно, если вызвано из другого потока.
     *
     * @param task задача
     */
    void execute(Runnable task);

    /**
     * Проверяет, выполняется ли вызов в UI-потоке.
     *
     * @return {@code true}, если текущий поток — UI-поток клиента
     */
    boolean isUiThread();

    /**
     * Исполнитель, который выполняет задачи сразу в вызывающем потоке и считает любой поток UI-потоком.
     * Подходит для web-сервера (у него нет UI-потока) и для тестов.
     *
     * @return прямой исполнитель
     */
    static UiExecutor direct() {
        return new UiExecutor() {
            /**
             * Выполняет задачу синхронно в вызывающем потоке, без очереди и переключения потока.
             * Исключения задачи передаются вызывающему коду непосредственно.
             *
             * @param task задача, завершающаяся до возврата из метода
             * @throws NullPointerException если задача равна {@code null}
             */
            @Override
            public void execute(Runnable task) {
                task.run();
            }

            /**
             * Считает любой вызывающий поток допустимым потоком интерфейса
             * для прямого исполнителя web-сервера и тестов.
             *
             * @return всегда {@code true}, без проверки принадлежности потока
             */
            @Override
            public boolean isUiThread() {
                return true;
            }
        };
    }
}
