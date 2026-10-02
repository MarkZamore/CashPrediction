package ru.cashprediction.fx.ui;

import javafx.scene.control.RadioMenuItem;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.UiIntents;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.menu.MenuNode;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет свойства меню без открытия окна и без бизнес-моделей клиента. */
class FxMenuAdapterTest {
    private FxMenus menus() {
        UiIntents intents = (UiIntents) Proxy.newProxyInstance(UiIntents.class.getClassLoader(), new Class<?>[]{UiIntents.class}, (p, m, a) -> null);
        return new FxMenus(intents, new FxClassUsageProbe());
    }
    /** Повторное радио в тулбаре не снимает отметку с основной копии меню. */
    @Test void repeatedRadioKeepsBothModelSelections() {
        FxMenus menus = menus();
        var node = new MenuNode.Radio("period", "period", CommandId.VIEW_PERIOD_M12, CommandArgs.NONE, "period", null, "", true, true);
        RadioMenuItem bar = (RadioMenuItem) menus.item(node, InvokeSource.MENU);
        RadioMenuItem toolbar = (RadioMenuItem) menus.item(node, InvokeSource.TOOLBAR);
        assertTrue(bar.isSelected()); assertTrue(toolbar.isSelected());
    }
    /** Доступность команды точно переносится из модели. */
    @Test void unavailableActionStaysDisabled() {
        var action = new MenuNode.Action("adjust", CommandId.EDIT_ADJUST, CommandArgs.NONE, "adjust", null, "", false);
        assertTrue(menus().item(action, InvokeSource.MENU).isDisable());
    }
}
