package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.UiIntents;
import ru.cashprediction.core.ui.menu.Emphasis;
import ru.cashprediction.core.ui.menu.ToolbarModel;
import ru.cashprediction.core.ui.menu.ToolbarNode;
import ru.cashprediction.core.ui.token.DesignTokens;

/** Размер PNG не должен уменьшать общую область стрелки и сдвигать последующие элементы. */
class SwingToolbarGeometryTest {
    /** Проверяет реальные preferred sizes обычной и выделенной кнопок, не подменяя метрики дампа. */
    @Test void menuArrowRetainsSharedSlotWidth() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            UiIntents intents = (UiIntents) Proxy.newProxyInstance(UiIntents.class.getClassLoader(),
                    new Class<?>[]{UiIntents.class}, (proxy, method, args) -> null);
            var toolbar = new SwingToolbar(intents);
            toolbar.render(new ToolbarModel(List.of(
                    new ToolbarNode.MenuButton("period", "Period", "", Emphasis.NONE, List.of()),
                    new ToolbarNode.MenuButton("scenario", "Scenario", "", Emphasis.WHATIF, List.of()))));
            for (String id : List.of("period", "scenario")) {
                AbstractButton button = (AbstractButton) toolbar.widget(id);
                int textWidth = SwingIcons.textWidth(button.getText(), button.getFontMetrics(button.getFont()));
                int arrowSlot = DesignTokens.CONTROL_HEIGHT - 2 * DesignTokens.SPACING;
                assertEquals(DesignTokens.INLINE_ICON_SIZE, button.getIcon().getIconWidth());
                assertEquals(arrowSlot, button.getIcon().getIconWidth() + button.getIconTextGap());
                assertEquals(textWidth + 2 * DesignTokens.TOOLBAR_BUTTON_PAD_H + arrowSlot,
                        button.getPreferredSize().width);
                assertEquals(button.getPreferredSize(), button.getMinimumSize());
                assertEquals(button.getPreferredSize(), button.getMaximumSize());
            }
        });
    }
}
