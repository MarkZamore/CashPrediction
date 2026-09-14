package ru.cashprediction.core.app;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import ru.cashprediction.core.session.WindowState;
import ru.cashprediction.core.session.WindowType;

/**
 * Открытые окна форм и сообщений в порядке открытия (архитектура §3.7): цепочка модальных окон и ключи
 * единственных экземпляров (калькулятор цели, быстрая правка).
 *
 * <p><b>Правило модальности:</b> пока открыто хоть одно модальное окно, намерения главного окна игнорируются, а
 * немодальные окна не принимают ввод (FX/Swing {@code APPLICATION_MODAL}, web {@code showModal}). Новое модальное
 * окно получает владельцем верхнее модальное окно или главное окно.</p>
 *
 * @param windows окна по порядку открытия
 */
public record OpenWindows(List<OpenWindow> windows) {

    /** Нет открытых окон. */
    public static final OpenWindows NONE = new OpenWindows(List.of());

    /**
     * Одно открытое окно.
     *
     * @param windowId          id окна ({@code w1}, {@code w2}, … из {@code SessionRecorder.nextWindowId})
     * @param type              тип окна (для сообщений — {@link WindowType#ALERT})
     * @param purpose           назначение ({@code rename}, {@code deleteRule}, {@code unsavedChanges}, …) или пустая строка
     * @param modal             модальное ли
     * @param ownerId           владелец: {@link WindowState#MAIN_OWNER} или id окна
     * @param singleInstanceKey ключ единственного экземпляра ({@code GOAL_CALCULATOR}, {@code QUICK_EDIT_POPUP}) или пустая строка
     */
    public record OpenWindow(String windowId, WindowType type, String purpose, boolean modal, String ownerId,
                             String singleInstanceKey) {
        /** Проверяет поля. */
        public OpenWindow {
            Objects.requireNonNull(windowId, "windowId");
            Objects.requireNonNull(type, "type");
            purpose = Objects.requireNonNullElse(purpose, "");
            ownerId = ownerId == null || ownerId.isBlank() ? WindowState.MAIN_OWNER : ownerId;
            singleInstanceKey = Objects.requireNonNullElse(singleInstanceKey, "");
        }
    }

    /** Копирует список. */
    public OpenWindows {
        windows = List.copyOf(Objects.requireNonNull(windows, "windows"));
    }

    /** @return открыто ли хоть одно модальное окно */
    public boolean modalOpen() {
        throw new UnsupportedOperationException("S2: core-app-file - OpenWindows.modalOpen");
    }

    /** @return последнее открытое модальное окно (владелец следующего модального окна) или пусто */
    public Optional<OpenWindow> topModal() {
        throw new UnsupportedOperationException("S2: core-app-file - OpenWindows.topModal");
    }

    /**
     * Ищет окно единственного экземпляра.
     *
     * @param singleInstanceKey ключ, например {@code GOAL_CALCULATOR}
     * @return окно или пусто
     */
    public Optional<OpenWindow> findSingleInstance(String singleInstanceKey) {
        throw new UnsupportedOperationException("S2: core-app-file - OpenWindows.findSingleInstance");
    }

    /**
     * Добавляет окно в конец.
     *
     * @param window окно
     * @return новый список
     */
    public OpenWindows with(OpenWindow window) {
        throw new UnsupportedOperationException("S2: core-app-file - OpenWindows.with");
    }

    /**
     * Убирает окно и все окна, владельцем которых оно было (дочерние закрываются вместе с родителем).
     *
     * @param windowId id окна
     * @return новый список
     */
    public OpenWindows without(String windowId) {
        throw new UnsupportedOperationException("S2: core-app-file - OpenWindows.without");
    }
}
