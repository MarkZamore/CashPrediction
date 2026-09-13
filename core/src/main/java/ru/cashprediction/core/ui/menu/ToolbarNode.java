package ru.cashprediction.core.ui.menu;

import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.ui.command.CommandId;

/**
 * Элемент тулбара (спецификация v2, §4; архитектура §3.3). Кнопки тулбара не забирают фокус; стрелка «▾» у кнопок
 * с меню рисуется самим элементом и в текст не входит.
 *
 * <ul>
 *   <li>{@link SplitButton} — JavaFX {@code SplitMenuButton} → Swing {@code SwingSplitMenuButton} → Web пара кнопок;</li>
 *   <li>{@link MenuButton} — {@code MenuButton} → {@code SwingMenuButton} → кнопка с выпадающим меню;</li>
 *   <li>{@link Toggle} — {@code ToggleButton} + {@code ToggleGroup} → {@code JToggleButton} + {@code ButtonGroup}
 *       → {@code button[aria-pressed]};</li>
 *   <li>{@link FilterField} — {@code TextField} → {@code JTextField} → {@code input};</li>
 *   <li>{@link Button} — {@code Button} → {@code JButton} → {@code button};</li>
 *   <li>{@link Separator}, {@link Spacer} — разделитель и растягивающаяся распорка.</li>
 * </ul>
 */
public sealed interface ToolbarNode
        permits ToolbarNode.SplitButton, ToolbarNode.Toggle, ToolbarNode.MenuButton, ToolbarNode.FilterField,
        ToolbarNode.Button, ToolbarNode.Separator, ToolbarNode.Spacer {

    /** @return стабильный id элемента ({@code tb.add}, {@code tb.period}, {@code tb.filter}, …) */
    String id();

    /**
     * Кнопка с основным действием и выпадающим списком ({@code tb.add}).
     *
     * @param id      id
     * @param text    текст основной части
     * @param tooltip подсказка
     * @param main    основное действие
     * @param items   пункты выпадающего списка
     */
    record SplitButton(String id, String text, String tooltip, MenuNode.Action main, List<MenuNode> items)
            implements ToolbarNode {
        /** Проверяет поля и копирует список. */
        public SplitButton {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(main, "main");
            text = Objects.requireNonNullElse(text, "");
            tooltip = Objects.requireNonNullElse(tooltip, "");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    /**
     * Кнопка-переключатель группы (Таблица / График). Повторный щелчок по нажатой кнопке ничего не меняет.
     *
     * @param id       id
     * @param command  команда
     * @param text     текст
     * @param tooltip  подсказка
     * @param selected нажата ли
     * @param group    имя группы
     */
    record Toggle(String id, CommandId command, String text, String tooltip, boolean selected, String group)
            implements ToolbarNode {
        /** Проверяет поля. */
        public Toggle {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(command, "command");
            text = Objects.requireNonNullElse(text, "");
            tooltip = Objects.requireNonNullElse(tooltip, "");
            group = Objects.requireNonNullElse(group, "");
        }
    }

    /**
     * Кнопка-меню ({@code tb.period}, {@code tb.whatIf}).
     *
     * @param id       id
     * @param text     текст без стрелки
     * @param tooltip  подсказка
     * @param emphasis выделение текста
     * @param items    пункты меню
     */
    record MenuButton(String id, String text, String tooltip, Emphasis emphasis, List<MenuNode> items)
            implements ToolbarNode {
        /** Проверяет поля и копирует список. */
        public MenuButton {
            Objects.requireNonNull(id, "id");
            text = Objects.requireNonNullElse(text, "");
            tooltip = Objects.requireNonNullElse(tooltip, "");
            emphasis = emphasis == null ? Emphasis.NONE : emphasis;
            items = List.copyOf(Objects.requireNonNull(items, "items"));
        }
    }

    /**
     * Поле фильтра с кнопкой «✕». Ввод передаётся ядру через {@code UiIntents.filterText} после {@code debounceMs};
     * Esc очищает, Enter переводит фокус в таблицу.
     *
     * @param id           id
     * @param text         текущий текст
     * @param prompt       подсказка в пустом поле («Фильтр… (Ctrl+F)»)
     * @param tooltip      подсказка поля
     * @param widthPx      ширина
     * @param debounceMs   задержка применения
     * @param clearVisible видна ли кнопка «✕»
     * @param clearTooltip подсказка кнопки «✕»
     */
    record FilterField(String id, String text, String prompt, String tooltip, int widthPx, int debounceMs,
                       boolean clearVisible, String clearTooltip) implements ToolbarNode {
        /** Проверяет поля. */
        public FilterField {
            Objects.requireNonNull(id, "id");
            text = Objects.requireNonNullElse(text, "");
            prompt = Objects.requireNonNullElse(prompt, "");
            tooltip = Objects.requireNonNullElse(tooltip, "");
            clearTooltip = Objects.requireNonNullElse(clearTooltip, "");
        }
    }

    /**
     * Обычная кнопка («↶», «↷», «Сохранить»).
     *
     * @param id          id
     * @param command     команда
     * @param glyphOrText значок или текст
     * @param tooltip     подсказка
     * @param enabled     доступна ли
     * @param emphasis    выделение текста
     */
    record Button(String id, CommandId command, String glyphOrText, String tooltip, boolean enabled, Emphasis emphasis)
            implements ToolbarNode {
        /** Проверяет поля. */
        public Button {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(command, "command");
            glyphOrText = Objects.requireNonNullElse(glyphOrText, "");
            tooltip = Objects.requireNonNullElse(tooltip, "");
            emphasis = emphasis == null ? Emphasis.NONE : emphasis;
        }
    }

    /**
     * Разделитель.
     *
     * @param id id
     */
    record Separator(String id) implements ToolbarNode {
        /** Проверяет id. */
        public Separator {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * Растягивающаяся распорка.
     *
     * @param id id
     */
    record Spacer(String id) implements ToolbarNode {
        /** Проверяет id. */
        public Spacer {
            Objects.requireNonNull(id, "id");
        }
    }
}
