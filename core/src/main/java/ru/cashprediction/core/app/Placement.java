package ru.cashprediction.core.app;

import java.util.Objects;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.session.WindowState;

/**
 * Где открыть окно формы ({@code UiPort.openForm}; спецификация v2, §6.0 «Размер», §5.6.1 «Положение»).
 *
 * <p>Правила для клиента: если {@link #bounds()} заданы и окно в них видно на каком-либо экране — поставить окно
 * туда (восстановление после сбоя); иначе, если задан {@link #anchor()}, — под ячейкой таблицы (быстрая правка;
 * если строка не видна, центр главного окна со смещением −150/−50); иначе — по центру над владельцем, по размеру
 * содержимого, не уже ширины формы и в пределах видимой области экрана. Web: немодальное окно — у правого края,
 * y = 96.</p>
 *
 * @param ownerId id владельца: {@link WindowState#MAIN_OWNER} или id родительского окна
 * @param bounds  границы из снимка или {@code null}
 * @param anchor  ячейка таблицы, к которой привязано всплывающее окно, или {@code null}
 */
public record Placement(String ownerId, WindowBounds bounds, Anchor anchor) {

    /**
     * Ячейка таблицы для привязки всплывающего окна.
     *
     * @param rowId    id строки
     * @param columnId id колонки ({@code income} или {@code expense})
     */
    public record Anchor(String rowId, String columnId) {
        /** Проверяет поля. */
        public Anchor {
            Objects.requireNonNull(rowId, "rowId");
            Objects.requireNonNull(columnId, "columnId");
        }
    }

    /** Подставляет главное окно, если владелец не задан. */
    public Placement {
        ownerId = ownerId == null || ownerId.isBlank() ? WindowState.MAIN_OWNER : ownerId;
    }

    /**
     * По центру над владельцем.
     *
     * @param ownerId id владельца
     * @return размещение
     */
    public static Placement centered(String ownerId) {
        return new Placement(ownerId, null, null);
    }

    /**
     * В границах из снимка (или по центру, если границ нет).
     *
     * @param ownerId id владельца
     * @param bounds  границы или {@code null}
     * @return размещение
     */
    public static Placement restored(String ownerId, WindowBounds bounds) {
        return new Placement(ownerId, bounds, null);
    }

    /**
     * Под ячейкой таблицы.
     *
     * @param rowId    id строки
     * @param columnId id колонки
     * @return размещение над главным окном
     */
    public static Placement underCell(String rowId, String columnId) {
        return new Placement(WindowState.MAIN_OWNER, null, new Anchor(rowId, columnId));
    }
}
