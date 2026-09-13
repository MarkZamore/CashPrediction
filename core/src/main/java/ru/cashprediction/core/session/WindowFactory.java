package ru.cashprediction.core.session;

import java.util.function.Consumer;

/**
 * Фабрика окон клиента для восстановления: создаёт окно нужного типа, переносит в него значения
 * и показывает его.
 *
 * <p>Метод асинхронный намеренно. Модальный диалог Swing, показанный через {@code setVisible(true)},
 * блокирует вызывающий код до закрытия диалога, а JavaFX {@code showAndWait()} — тоже. Если бы
 * фабрика возвращала окно синхронно, второй вложенный диалог никогда бы не открылся. Поэтому
 * фабрика сообщает о показе колбэком (Swing — из {@code windowOpened}, JavaFX — из
 * {@code setOnShown}), и {@link RestoreCoordinator} открывает следующее окно только после него.</p>
 *
 * <p>Вызывается и вызывает колбэки в UI-потоке клиента.</p>
 */
public interface WindowFactory {

    /**
     * Создаёт и показывает окно по сохранённому состоянию.
     *
     * <p>Фабрика обязана ровно один раз вызвать либо {@code onShown} (окно на экране), либо
     * {@code onFailed} (окно открыть нельзя).</p>
     *
     * @param state    состояние окна с уже назначенным новым идентификатором
     * @param ownerId  владелец: {@code main} или идентификатор уже показанного окна
     * @param onShown  вызывается, когда окно показано; аргумент — само окно
     * @param onFailed вызывается, если окно открыть не удалось; аргумент — причина на русском
     */
    void open(WindowState state, String ownerId, Consumer<StatefulWindow> onShown, Consumer<String> onFailed);
}
