package ru.cashprediction.core.session;

/**
 * Окно, состояние которого попадает в снимок сессии и может быть восстановлено.
 *
 * <p>Реализуется диалогами и всплывающими окнами каждого клиента (JavaFX, Swing, Web-прокси
 * на сервере). Окно регистрируется в {@link SessionRecorder} в момент показа и снимается
 * с регистрации при закрытии.</p>
 *
 * <p>Методы {@link #captureState()} и {@link #applyState(WindowState)} вызываются только в
 * UI-потоке клиента (см. {@link UiExecutor}); остальные методы должны быть безопасны для
 * вызова из любого потока (обычно это неизменяемые поля окна).</p>
 */
public interface StatefulWindow {

    /**
     * Идентификатор окна в текущем сеансе.
     *
     * @return например {@code w3}; выдаётся {@link SessionRecorder#nextWindowId()}
     */
    String windowId();

    /**
     * Тип окна из общего словаря.
     *
     * @return тип окна
     */
    WindowType windowType();

    /**
     * Модальность окна.
     *
     * @return {@code true}, если окно модальное
     */
    boolean modal();

    /**
     * Владелец окна.
     *
     * @return {@code main} или идентификатор другого окна
     */
    String ownerId();

    /**
     * Снимает текущее состояние окна: геометрию, контекст и значения полей.
     * Вызывается в UI-потоке.
     *
     * @return состояние окна
     */
    WindowState captureState();

    /**
     * Переносит сохранённые значения полей (и, по возможности, геометрию) в окно.
     * Вызывается в UI-потоке до показа окна.
     *
     * @param state состояние из снимка
     */
    void applyState(WindowState state);
}
