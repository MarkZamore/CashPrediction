package ru.cashprediction.core.ui.menu;

import java.util.List;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientKind;

/**
 * Построение моделей меню, тулбара и контекстных меню из состояния (архитектура §3.3, спецификация v2, §3–§5).
 *
 * <p>Все методы — чистые функции состояния: одно и то же {@link AppState} даёт равные модели. Доступность берётся
 * из {@code CommandAvailability}, ускорители — из {@code HotkeyTable.shownAccelerator} для {@code client}, тексты и
 * подсказки — из {@code UiText} ({@code menu.<id>}, {@code menu.<id>.tip}, {@code toolbar.<id>}, {@code ctx.<target>.<id>}).
 * Соседние, начальные и конечные разделители удаляются; у каждого узла стабильный id.</p>
 *
 * <p>Отличия web (§10): в меню «Восстановление» одно отключённое радио {@code recovery.store.server} вместо двух;
 * подсказка «Выход» — «Закрыть программу и остановить сервер»; ускорители — колонка «Web».</p>
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
public final class MenuModels {

    private MenuModels() {
    }

    /**
     * Строка меню главного окна.
     *
     * @param state  состояние
     * @param client вид клиента
     * @return модель шести меню
     */
    public static MenuBarModel menuBar(AppState state, ClientKind client) {
        throw new UnsupportedOperationException("S1: core-menu - MenuModels.menuBar");
    }

    /**
     * Тулбар главного окна.
     *
     * @param state  состояние
     * @param client вид клиента
     * @return модель тулбара
     */
    public static ToolbarModel toolbar(AppState state, ClientKind client) {
        throw new UnsupportedOperationException("S1: core-menu - MenuModels.toolbar");
    }

    /**
     * Контекстное меню объекта (строка, итог, группа прошедших, карточка, график, предпросмотр).
     *
     * @param state  состояние
     * @param target объект
     * @param client вид клиента
     * @return пункты меню; пустой список — меню не показывается (например, щелчок в пустом месте таблицы)
     */
    public static List<MenuNode> contextMenu(AppState state, ContextTarget target, ClientKind client) {
        throw new UnsupportedOperationException("S1: core-menu - MenuModels.contextMenu");
    }
}
