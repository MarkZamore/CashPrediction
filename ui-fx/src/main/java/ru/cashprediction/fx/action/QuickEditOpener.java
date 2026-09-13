package ru.cashprediction.fx.action;

import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowState;

import java.util.function.Consumer;

/**
 * Открывает всплывающее окно быстрой правки суммы ({@code WindowType.QUICK_EDIT_POPUP}) при восстановлении сессии.
 *
 * <p>Сам {@code Popup} живёт у таблицы главного окна (его нужно поставить рядом с ячейкой), поэтому фабрика окон
 * не создаёт его сама, а делегирует главному окну через этот интерфейс.</p>
 *
 * <p>Вызывается в FX Application Thread.</p>
 */
// JavaFX: Popup → Swing: PopupFactory.getSharedInstance().getPopup(owner, panel, x, y) → Web: тот же механизм с <form>
@FunctionalInterface
public interface QuickEditOpener {

    /**
     * Показывает быструю правку по сохранённому состоянию.
     *
     * <p>Реализация обязана ровно один раз вызвать {@code onShown} (окно показано; его зарегистрирует координатор)
     * или {@code onFailed} (например, строки с таким событием нет в таблице).</p>
     *
     * @param state    состояние из снимка: контекст {@code ruleId}, {@code originalDate}, поле {@code amount}
     * @param onShown  колбэк показа
     * @param onFailed колбэк отказа с причиной по-русски
     */
    void open(WindowState state, Consumer<StatefulWindow> onShown, Consumer<String> onFailed);
}
