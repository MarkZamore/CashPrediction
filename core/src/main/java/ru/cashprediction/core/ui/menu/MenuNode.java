package ru.cashprediction.core.ui.menu;

import java.util.List;
import java.util.Objects;
import ru.cashprediction.core.ui.command.CommandArgs;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.command.KeyChord;

/**
 * Узел меню: строки меню, подменю, контекстного меню или выпадающего списка кнопки тулбара (архитектура §3.3,
 * спецификация v2, §3–§5).
 *
 * <p>Каждый вид узла соответствует ровно одному классу инструмента:</p>
 * <ul>
 *   <li>{@link Action} — JavaFX {@code MenuItem} → Swing {@code JMenuItem} → Web {@code div[role=menuitem]};</li>
 *   <li>{@link Check} — {@code CheckMenuItem} → {@code JCheckBoxMenuItem} → {@code menuitemcheckbox};</li>
 *   <li>{@link Radio} — {@code RadioMenuItem} + {@code ToggleGroup} → {@code JRadioButtonMenuItem} + {@code ButtonGroup}
 *       → {@code menuitemradio};</li>
 *   <li>{@link Separator} — {@code SeparatorMenuItem} → {@code JPopupMenu.Separator} → {@code hr};</li>
 *   <li>{@link Submenu} — {@code Menu} → {@code JMenu} → вложенный список;</li>
 *   <li>{@link Slider}, {@link Spinner} — {@code CustomMenuItem} ({@code hideOnClick=false}) → {@code SwingSliderMenuItem}
 *       / {@code SwingSpinnerMenuItem} → {@code input type=range/number} в пункте;</li>
 *   <li>{@link Info} — отключённый {@code MenuItem} с текстом (например «(список пуст)»).</li>
 * </ul>
 *
 * <p>Id узла стабилен ({@code file}, {@code file.new}, {@code file.recent.0}, {@code view.period.M3}), привязывается
 * к виджету ({@code cp.id}) и служит ключом дампа. Тексты уже готовы (из {@code UiText}); клиент ничего не решает.</p>
 */
public sealed interface MenuNode
        permits MenuNode.Action, MenuNode.Check, MenuNode.Radio, MenuNode.Separator, MenuNode.Submenu,
        MenuNode.Slider, MenuNode.Spinner, MenuNode.Info {

    /** @return стабильный id узла */
    String id();

    /**
     * Обычный пункт.
     *
     * @param id      id узла
     * @param command команда
     * @param args    аргументы команды
     * @param text    метка
     * @param accel   показываемый ускоритель или {@code null}
     * @param tooltip подсказка ({@code menu.<id>.tip})
     * @param enabled доступен ли пункт
     */
    record Action(String id, CommandId command, CommandArgs args, String text, KeyChord accel, String tooltip,
                  boolean enabled) implements MenuNode {
        /** Проверяет обязательные поля. */
        public Action {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(command, "command");
            args = args == null ? CommandArgs.NONE : args;
            text = Objects.requireNonNullElse(text, "");
            tooltip = Objects.requireNonNullElse(tooltip, "");
        }
    }

    /**
     * Флажок.
     *
     * @param id      id узла
     * @param command команда-переключатель
     * @param args    аргументы команды
     * @param text    метка
     * @param accel   показываемый ускоритель или {@code null}
     * @param tooltip подсказка
     * @param enabled доступен ли пункт
     * @param checked отмечен ли
     */
    record Check(String id, CommandId command, CommandArgs args, String text, KeyChord accel, String tooltip,
                 boolean enabled, boolean checked) implements MenuNode {
        /** Проверяет обязательные поля. */
        public Check {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(command, "command");
            args = args == null ? CommandArgs.NONE : args;
            text = Objects.requireNonNullElse(text, "");
            tooltip = Objects.requireNonNullElse(tooltip, "");
        }
    }

    /**
     * Радио-пункт группы.
     *
     * @param id       id узла
     * @param group    имя группы ({@code mode}, {@code period}, {@code store})
     * @param command  команда выбора
     * @param args     аргументы команды
     * @param text     метка
     * @param accel    показываемый ускоритель или {@code null}
     * @param tooltip  подсказка
     * @param enabled  доступен ли пункт
     * @param selected выбран ли
     */
    record Radio(String id, String group, CommandId command, CommandArgs args, String text, KeyChord accel,
                 String tooltip, boolean enabled, boolean selected) implements MenuNode {
        /** Проверяет обязательные поля. */
        public Radio {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(command, "command");
            args = args == null ? CommandArgs.NONE : args;
            text = Objects.requireNonNullElse(text, "");
            tooltip = Objects.requireNonNullElse(tooltip, "");
        }
    }

    /**
     * Разделитель. Соседние и крайние разделители удаляет ядро ({@code MenuModels}).
     *
     * @param id id узла (например {@code file.sep.1})
     */
    record Separator(String id) implements MenuNode {
        /** Проверяет id. */
        public Separator {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * Подменю или меню строки меню.
     *
     * @param id       id узла ({@code file}, {@code file.recent})
     * @param text     метка
     * @param tooltip  подсказка
     * @param enabled  доступно ли
     * @param children дочерние узлы по порядку
     */
    record Submenu(String id, String text, String tooltip, boolean enabled, List<MenuNode> children) implements MenuNode {
        /** Проверяет поля и копирует список. */
        public Submenu {
            Objects.requireNonNull(id, "id");
            text = Objects.requireNonNullElse(text, "");
            tooltip = Objects.requireNonNullElse(tooltip, "");
            children = List.copyOf(Objects.requireNonNull(children, "children"));
        }
    }

    /**
     * Слайдер в меню (горизонт плана, §3.3). Меню не закрывается; подпись над слайдером обновляется при перетаскивании
     * из готовых {@code labels}; план меняется один раз при отпускании ({@code UiIntents.sliderCommit}).
     *
     * @param id        id узла
     * @param command   команда ({@link CommandId#VIEW_HORIZON_SLIDER})
     * @param min       минимум шкалы
     * @param max       максимум шкалы
     * @param majorTick шаг основных делений
     * @param value        текущее положение
     * @param labels       подписи для каждого значения {@code min..max}: {@code labels.get(v - min)}
     * @param currentLabel подпись, которая показывается до первого движения ползунка. Обычно равна
     *                     {@code labels.get(value - min)}; если горизонт больше максимума шкалы (§3.3: больше 120
     *                     месяцев), ползунок стоит на {@code max}, а подпись — настоящий горизонт («15 лет»). После
     *                     первого движения клиент берёт подписи из {@code labels}
     * @param tooltip      подсказка
     * @param widthPx      ширина слайдера
     */
    record Slider(String id, CommandId command, int min, int max, int majorTick, int value, List<String> labels,
                  String currentLabel, String tooltip, int widthPx) implements MenuNode {
        /** Проверяет поля и копирует подписи. */
        public Slider {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(command, "command");
            labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
            // Без явной подписи — подпись текущего положения шкалы (если оно в пределах списка).
            if (currentLabel == null) {
                int index = value - min;
                currentLabel = index >= 0 && index < labels.size() ? labels.get(index) : "";
            }
            tooltip = Objects.requireNonNullElse(tooltip, "");
        }
    }

    /**
     * Спиннер в меню (доп. экономия «что-если», §3.4). Меню не закрывается; значение применяется через
     * {@code applyDelayMs} после последнего изменения ({@code UiIntents.spinnerCommit}).
     *
     * @param id           id узла
     * @param command      команда ({@link CommandId#WHAT_IF_EXTRA})
     * @param label        подпись слева, например «Откладывать доп. в месяц, ₽:»
     * @param min          минимум
     * @param max          максимум
     * @param step         шаг
     * @param value        текущее значение
     * @param tooltip      подсказка
     * @param fieldWidthPx ширина поля
     * @param applyDelayMs задержка применения
     */
    record Spinner(String id, CommandId command, String label, long min, long max, long step, long value,
                   String tooltip, int fieldWidthPx, int applyDelayMs) implements MenuNode {
        /** Проверяет поля. */
        public Spinner {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(command, "command");
            label = Objects.requireNonNullElse(label, "");
            tooltip = Objects.requireNonNullElse(tooltip, "");
        }
    }

    /**
     * Всегда отключённый информационный пункт.
     *
     * @param id   id узла
     * @param text текст, например «(список пуст)»
     */
    record Info(String id, String text) implements MenuNode {
        /** Проверяет поля. */
        public Info {
            Objects.requireNonNull(id, "id");
            text = Objects.requireNonNullElse(text, "");
        }
    }
}
