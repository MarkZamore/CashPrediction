package ru.cashprediction.swing.action;

import java.util.function.Consumer;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowState;

/**
 * Открывает всплывающее окно быстрой правки суммы ({@code WindowType.QUICK_EDIT_POPUP}).
 *
 * <p>Само всплывающее окно живёт у таблицы главного окна (его нужно поставить рядом с ячейкой суммы), поэтому
 * фасад команд и фабрика окон не создают его сами, а делегируют главному окну через этот интерфейс.</p>
 *
 * <p>Контракт реализации: ровно один раз вызвать {@code onShown} (окно показано) или {@code onFailed} (например,
 * строки с таким событием нет в таблице); при закрытии окна — снять его с регистрации в рекордере
 * ({@code SessionRecorder.unregister}). Регистрирует окно тот, кто передал {@code onShown}: координатор
 * восстановления или фасад команд.</p>
 *
 * <p>Вызывается в потоке EDT.</p>
 */
// JavaFX: Popup → Swing: PopupFactory.getSharedInstance().getPopup(owner, panel, x, y) → Web: тот же механизм с <form>
@FunctionalInterface
public interface QuickEditOpener {

    /**
     * Показывает быструю правку по состоянию окна.
     *
     * @param state    состояние: идентификатор окна, контекст {@code ruleId} и {@code originalDate}, поле {@code amount}
     *                 (пустое поле — показать текущую сумму события)
     * @param onShown  колбэк показа
     * @param onFailed колбэк отказа с причиной по-русски
     */
    void open(WindowState state, Consumer<StatefulWindow> onShown, Consumer<String> onFailed);
}
