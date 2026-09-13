package ru.cashprediction.core.ui.menu;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Строка меню главного окна: шесть меню «Файл | Правка | Вид | Инструменты | Восстановление | Справка»
 * (спецификация v2, §3). JavaFX {@code MenuBar} → Swing {@code JMenuBar} → Web {@code div[role=menubar]}.
 *
 * @param menus меню по порядку
 */
public record MenuBarModel(List<MenuNode.Submenu> menus) {

    /** Копирует список. */
    public MenuBarModel {
        menus = List.copyOf(Objects.requireNonNull(menus, "menus"));
    }

    /**
     * Ищет узел по id на любой глубине.
     *
     * @param id id узла, например {@code view.period.M3}
     * @return узел или пусто
     */
    public Optional<MenuNode> find(String id) {
        for (MenuNode.Submenu menu : menus) {
            Optional<MenuNode> found = find(menu, id);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<MenuNode> find(MenuNode node, String id) {
        if (node.id().equals(id)) {
            return Optional.of(node);
        }
        if (node instanceof MenuNode.Submenu submenu) {
            for (MenuNode child : submenu.children()) {
                Optional<MenuNode> found = find(child, id);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }
}
