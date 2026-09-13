package ru.cashprediction.fx.dialog;

import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowState;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Как открыть диалог: чей он (владелец) и, при восстановлении сессии, какое состояние в него перенести
 * и кому сообщить о показе.
 *
 * <p>Один и тот же метод фасада команд обслуживает и пункт меню, и восстановление после сбоя: меню
 * передаёт {@link #fromMain()}, а {@code FxWindowFactory} — {@link #restore}. Благодаря этому
 * восстановленное окно ведёт себя в точности как открытое вручную.</p>
 *
 * <p>Запись неизменяема.</p>
 *
 * @param ownerId  владелец: {@code main} или идентификатор открытого окна ({@code w3})
 * @param restore  состояние из снимка или {@code null} при обычном открытии
 * @param onShown  при восстановлении: вызвать, когда окно показано (регистрирует его координатор)
 * @param onFailed при восстановлении: вызвать, если окно открыть нельзя (причина по-русски)
 */
public record OpenRequest(String ownerId, WindowState restore, Consumer<StatefulWindow> onShown,
                          Consumer<String> onFailed) {

    /** Нормализует владельца и колбэки. */
    public OpenRequest {
        ownerId = ownerId == null || ownerId.isBlank() ? WindowState.MAIN_OWNER : ownerId;
        onShown = Objects.requireNonNullElse(onShown, w -> { });
        onFailed = Objects.requireNonNullElse(onFailed, r -> { });
    }

    /**
     * Обычное открытие поверх главного окна.
     *
     * @return запрос
     */
    public static OpenRequest fromMain() {
        return new OpenRequest(WindowState.MAIN_OWNER, null, null, null);
    }

    /**
     * Обычное открытие поверх другого окна (вложенный диалог).
     *
     * @param ownerId идентификатор окна-владельца
     * @return запрос
     */
    public static OpenRequest ownedBy(String ownerId) {
        return new OpenRequest(ownerId, null, null, null);
    }

    /**
     * Открытие при восстановлении сессии.
     *
     * @param state    состояние окна из снимка (с новым идентификатором)
     * @param ownerId  владелец в текущем сеансе
     * @param onShown  колбэк показа
     * @param onFailed колбэк отказа
     * @return запрос
     */
    public static OpenRequest restore(WindowState state, String ownerId, Consumer<StatefulWindow> onShown,
                                      Consumer<String> onFailed) {
        return new OpenRequest(ownerId, Objects.requireNonNull(state, "state"), onShown, onFailed);
    }

    /**
     * Восстанавливается ли окно из снимка.
     *
     * @return {@code true}, если есть состояние из снимка
     */
    public boolean isRestore() {
        return restore != null;
    }

    /**
     * Сообщает координатору восстановления, что окно открыть нельзя. При обычном открытии ничего не делает
     * (там причину показывает сам фасад в сообщении об ошибке).
     *
     * @param reason причина по-русски
     */
    public void fail(String reason) {
        if (isRestore()) {
            onFailed.accept(reason);
        }
    }
}
