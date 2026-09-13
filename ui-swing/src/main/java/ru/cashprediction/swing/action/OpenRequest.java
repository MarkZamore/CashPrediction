package ru.cashprediction.swing.action;

import java.awt.Window;
import java.util.function.Consumer;
import ru.cashprediction.core.session.StatefulWindow;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.swing.dialog.SwingDialogHost;

/**
 * Как открыть окно: кто владелец, восстанавливается ли окно из снимка и кому сообщить о показе или неудаче.
 *
 * <p>Одни и те же методы фасада команд открывают окна в трёх случаях: по команде пользователя
 * ({@link #interactive}), при восстановлении сессии ({@link #restore}) и из самотеста ({@link #scripted}).
 * Благодаря этому восстановленное окно создаётся ровно тем же кодом, что и открытое пользователем.</p>
 *
 * <p>Record неизменяем; используется в потоке EDT.</p>
 *
 * @param ownerWindow окно-владелец; {@code null} — найти по {@code ownerId}
 * @param ownerId     идентификатор владельца для снимка: {@code main} или {@code wN}
 * @param state       состояние из снимка для восстановления; {@code null} — новое окно
 * @param onShown     вызывается, когда окно показано
 * @param onFailed    вызывается с причиной, если окно открыть нельзя
 * @param interactive {@code true} — команда пользователя: о невозможности открыть окно сообщается диалогом
 */
public record OpenRequest(Window ownerWindow, String ownerId, WindowState state, Consumer<StatefulWindow> onShown,
                          Consumer<String> onFailed, boolean interactive) {

    /** Подставляет пустые обработчики и владельца по умолчанию. */
    public OpenRequest {
        ownerId = ownerId == null || ownerId.isBlank() ? WindowState.MAIN_OWNER : ownerId;
        onShown = onShown == null ? window -> { } : onShown;
        onFailed = onFailed == null ? reason -> { } : onFailed;
    }

    /**
     * Команда пользователя: владелец — верхнее модальное окно или главное окно.
     *
     * @param host хост диалогов
     * @return запрос
     */
    public static OpenRequest interactive(SwingDialogHost host) {
        return new OpenRequest(host.activeOwner(), host.activeOwnerId(), null, null, null, true);
    }

    /**
     * Команда пользователя поверх главного окна (пункт меню, кнопка панели инструментов).
     *
     * @return запрос; окно-владелец найдётся по идентификатору {@code main}
     */
    public static OpenRequest fromMain() {
        return new OpenRequest(null, WindowState.MAIN_OWNER, null, null, null, true);
    }

    /**
     * Команда пользователя поверх другого открытого окна (вложенный диалог).
     *
     * @param ownerId идентификатор окна-владельца ({@code wN}) или {@code main}
     * @return запрос
     */
    public static OpenRequest ownedBy(String ownerId) {
        return new OpenRequest(null, ownerId, null, null, null, true);
    }

    /**
     * Восстановление окна из снимка.
     *
     * @param state    состояние окна с новым идентификатором
     * @param ownerId  владелец
     * @param onShown  колбэк показа (продолжает цепочку восстановления)
     * @param onFailed колбэк неудачи
     * @return запрос
     */
    public static OpenRequest restore(WindowState state, String ownerId, Consumer<StatefulWindow> onShown,
                                      Consumer<String> onFailed) {
        return new OpenRequest(null, ownerId, state, onShown, onFailed, false);
    }

    /**
     * Открытие из самотеста: как команда пользователя, но об ошибках сообщается колбэком, а не диалогом.
     *
     * @param host     хост диалогов
     * @param onShown  колбэк показа
     * @param onFailed колбэк неудачи
     * @return запрос
     */
    public static OpenRequest scripted(SwingDialogHost host, Consumer<StatefulWindow> onShown, Consumer<String> onFailed) {
        return new OpenRequest(host.activeOwner(), host.activeOwnerId(), null, onShown, onFailed, false);
    }

    /**
     * Восстанавливается ли окно из снимка.
     *
     * @return {@code true}, если есть состояние
     */
    public boolean isRestore() {
        return state != null;
    }

    /**
     * Значение поля из восстанавливаемого состояния.
     *
     * @param fieldId идентификатор поля
     * @return значение или пустая строка
     */
    public String field(String fieldId) {
        return state == null ? "" : state.field(fieldId);
    }
}
