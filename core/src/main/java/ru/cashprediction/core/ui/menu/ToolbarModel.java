package ru.cashprediction.core.ui.menu;

import java.util.List;
import java.util.Objects;

/**
 * Тулбар главного окна слева направо (спецификация v2, §4). JavaFX {@code ToolBar} → Swing {@code JToolBar}
 * (не перетаскивается) → Web {@code div[role=toolbar]} (переносится на вторую строку при нехватке ширины, §10 №15).
 *
 * @param items элементы по порядку
 */
public record ToolbarModel(List<ToolbarNode> items) {

    /** Копирует список. */
    public ToolbarModel {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
    }
}
