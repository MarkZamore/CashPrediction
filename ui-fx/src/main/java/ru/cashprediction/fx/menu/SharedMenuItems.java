package ru.cashprediction.fx.menu;

import javafx.collections.ObservableList;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;

import java.util.List;
import java.util.Objects;

/**
 * Одни и те же пункты меню в двух местах: в меню строки меню и в {@link MenuButton} панели инструментов.
 *
 * <p>Пункт меню JavaFX может принадлежать только одному меню. А по плану «Период ▾» на панели инструментов и
 * «Вид → период» — это <b>одни и те же</b> {@code RadioMenuItem} с общей {@code ToggleGroup}, как и пункты «Что-если».
 * Две копии пунктов в одной группе переключателей не работают: выбор в одной копии снимал бы отметку в другой.
 * Поэтому пункты хранятся в одном экземпляре и переносятся в то меню, которое сейчас открывается
 * (событие {@code ON_SHOWING} приходит до построения всплывающего списка). Одновременно открыто только одно
 * меню, так что пользователь всегда видит полный и согласованный список.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
public final class SharedMenuItems {

    private final List<MenuItem> items;
    private ObservableList<MenuItem> holder;

    /**
     * Создаёт набор общих пунктов.
     *
     * @param items пункты в порядке показа
     */
    public SharedMenuItems(List<? extends MenuItem> items) {
        this.items = List.copyOf(items);
    }

    /**
     * Подключает меню строки меню: пункты встают сразу после {@code anchor}.
     * Первое подключённое место сразу получает пункты.
     *
     * @param menu   меню
     * @param anchor пункт, после которого вставлять (например, разделитель); {@code null} — в конец
     */
    public void attach(Menu menu, MenuItem anchor) {
        Objects.requireNonNull(menu, "menu");
        if (holder == null) {
            moveInto(menu.getItems(), anchor);
        }
        menu.addEventHandler(Menu.ON_SHOWING, e -> moveInto(menu.getItems(), anchor));
    }

    /**
     * Подключает кнопку-меню панели инструментов: пункты составляют весь её список.
     *
     * @param button кнопка-меню
     */
    public void attach(MenuButton button) {
        Objects.requireNonNull(button, "button");
        if (holder == null) {
            moveInto(button.getItems(), null);
        }
        button.addEventHandler(MenuButton.ON_SHOWING, e -> moveInto(button.getItems(), null));
    }

    private void moveInto(ObservableList<MenuItem> target, MenuItem anchor) {
        if (target == holder) {
            return;
        }
        if (holder != null) {
            holder.removeAll(items);
        }
        int index = anchor == null ? target.size() : target.indexOf(anchor) + 1;
        if (index <= 0 && anchor != null) {
            index = target.size();
        }
        target.addAll(index, items);
        holder = target;
    }
}
