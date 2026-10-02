package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.UiIntents;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.menu.*;

/** Проверки реального дерева меню без создания главного окна. */
class SwingMenusTest {
    @Test void popupUsesSharedRowsAndSeparatorButDoesNotClipIntrinsicCustomControls() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); var renderer = new SwingMenus(intents());
            var slider = new MenuNode.Slider("slider", CommandId.VIEW_PERIOD_M12, 1, 2, 1, 1, List.of("one", "two"), "one", "", 240);
            var spinner = new MenuNode.Spinner("spinner", CommandId.WHAT_IF_EXTRA, "extra", 0, 10000, 1000, 0, "", 120, 600);
            var action = new MenuNode.Action("save", CommandId.FILE_SAVE, null, "save", KeyChord.parse("Ctrl+S"), "tip", true);
            var popup = renderer.popup(List.of(action, new MenuNode.Separator("sep"), slider, spinner), InvokeSource.MENU);
            popup.setSize(popup.getPreferredSize()); popup.doLayout();
            int step = ru.cashprediction.core.ui.token.DesignTokens.SPACING;
            assertEquals(new java.awt.Insets(step + 1, step + 1, step + 1, step + 1), popup.getInsets());
            JMenuItem item = (JMenuItem) popup.getComponent(0);
            assertEquals(ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT, item.getHeight());
            assertEquals(SwingLook.font(ru.cashprediction.core.ui.token.FontToken.BASE), item.getFont());
            assertEquals(new java.awt.Point(step, item.getHeight() + step), item.getToolTipLocation(null));
            assertEquals(2 * step + 1, popup.getComponent(1).getHeight());
            for (int index : new int[]{2, 3}) assertEquals(popup.getComponent(index).getPreferredSize().height, popup.getComponent(index).getHeight());
            assertTrue(popup.getComponent(2).getHeight() > item.getHeight());
            System.out.println("shared popup=" + popup.getBounds() + " row=" + item.getBounds() + " separator=" + popup.getComponent(1).getBounds()
                    + " sliderHeight=" + popup.getComponent(2).getHeight() + " spinnerHeight=" + popup.getComponent(3).getHeight());
        });
    }
    @Test void openedSubmenuIsNotACheckedCommand() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var menu = (JMenu) new SwingMenus(intents()).bar(model(false, false)).getMenu(0);
            menu.setSelected(true);
            assertTrue(menu.isSelected());
            assertFalse(SwingUiDumper.menuItems(new java.awt.Component[]{menu}).getFirst().checked());
            assertTrue(SwingUiDumper.menuItems(menu.getMenuComponents()).getFirst().checked());
        });
    }
    @Test void separatorsAreDisabledActualWidgets() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var item = new SwingMenus(intents()).widget(new MenuNode.Separator("sep"), InvokeSource.MENU);
            assertFalse(item.isEnabled());
            assertFalse(SwingUiDumper.menuItems(new java.awt.Component[]{item}).getFirst().enabled());
        });
    }
    private final List<String> calls = new ArrayList<>();
    private UiIntents intents() {
        return (UiIntents) Proxy.newProxyInstance(UiIntents.class.getClassLoader(), new Class<?>[]{UiIntents.class}, (proxy, method, args) -> {
            if (method.getName().equals("command")) calls.add(((CommandId) args[0]).id());
            return method.getReturnType() == boolean.class ? false : null;
        });
    }

    @Test void spinnerImmediatelyForwardsValueToCoreDebounce() throws Exception {
        List<Long> values = new ArrayList<>();
        var target = (UiIntents) Proxy.newProxyInstance(UiIntents.class.getClassLoader(), new Class<?>[]{UiIntents.class}, (proxy, method, args) -> {
            if (method.getName().equals("spinnerCommit")) values.add((Long) args[1]);
            return method.getReturnType() == boolean.class ? false : null;
        });
        SwingUtilities.invokeAndWait(() -> {
            var node = new MenuNode.Spinner("whatIf.extra", CommandId.WHAT_IF_EXTRA, "extra", 0, 10_000_000, 1000, 0, "", 120, 600);
            var widget = new SwingMenus(target).widget(node, InvokeSource.MENU);
            SwingUiDumper.first(widget, JSpinner.class).setValue(5000L);
            assertEquals(List.of(5000L), values);
        });
    }
    private MenuBarModel model(boolean chart, boolean shortPeriod) {
        return new MenuBarModel(List.of(new MenuNode.Submenu("view", "view", "", true, List.of(
                new MenuNode.Radio("view.table", "mode", CommandId.VIEW_TABLE, null, "table", null, "", true, !chart),
                new MenuNode.Radio("view.chart", "mode", CommandId.VIEW_CHART, null, "chart", null, "", true, chart),
                new MenuNode.Radio("view.period.M3", "period", CommandId.VIEW_PERIOD_M3, null, "3", null, "", true, shortPeriod),
                new MenuNode.Radio("view.period.M12", "period", CommandId.VIEW_PERIOD_M12, null, "12", null, "", true, !shortPeriod)))));
    }
    @Test void rebuildHasIndependentRadioGroupsAndKeepsBothForests() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingMenus renderer = new SwingMenus(intents());
            JMenuBar first = renderer.bar(model(false, false)), second = renderer.bar(model(true, true));
            assertTrue(((AbstractButton) SwingUiDriver.find(first, "view.table")).isSelected());
            assertTrue(((AbstractButton) SwingUiDriver.find(first, "view.period.M12")).isSelected());
            assertTrue(((AbstractButton) SwingUiDriver.find(second, "view.chart")).isSelected());
            assertTrue(((AbstractButton) SwingUiDriver.find(second, "view.period.M3")).isSelected());
            assertFalse(((AbstractButton) SwingUiDriver.find(second, "view.table")).isSelected());
            assertTrue(java.util.Arrays.stream(SwingMenus.class.getDeclaredFields()).noneMatch(field -> java.util.Map.class.isAssignableFrom(field.getType())));
            ((AbstractButton) SwingUiDriver.find(second, "view.table")).doClick(0);
            assertTrue(((AbstractButton) SwingUiDriver.find(first, "view.table")).isSelected());
            assertFalse(((AbstractButton) SwingUiDriver.find(second, "view.chart")).isSelected());
        });
    }
    @Test void popupRadioGroupDoesNotMutateMenubarSelection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingMenus renderer = new SwingMenus(intents()); JMenuBar bar = renderer.bar(model(false, false));
            JPopupMenu popup = renderer.popup(model(true, true).menus().getFirst().children(), InvokeSource.CONTEXT_MENU);
            assertTrue(((AbstractButton) SwingUiDriver.find(popup, "view.chart")).isSelected());
            assertTrue(((AbstractButton) SwingUiDriver.find(bar, "view.table")).isSelected());
        });
    }
    @Test void actionFiresOnceAndAcceleratorHasNoSwingBinding() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingMenus renderer = new SwingMenus(intents());
            JMenuItem item = (JMenuItem) renderer.widget(new MenuNode.Action("save", CommandId.FILE_SAVE, null, "save", KeyChord.parse("Ctrl+S"), "tip", true), InvokeSource.MENU);
            assertNull(item.getAccelerator()); assertEquals("Ctrl+S", item.getClientProperty("cp.accel"));
            item.doClick(0); assertEquals(List.of("file.save"), calls);
        });
    }
    @Test void dumperReadsMutatedWidgetRatherThanModel() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingMenus renderer = new SwingMenus(intents());
            JMenuBar bar = renderer.bar(model(false, false)); JRadioButtonMenuItem chart = (JRadioButtonMenuItem) SwingUiDriver.find(bar, "view.chart");
            chart.setText("changed"); chart.setEnabled(false); chart.setSelected(true);
            var dump = SwingUiDumper.menuItems(bar.getComponents()).getFirst().children().get(1);
            assertEquals("changed", dump.text()); assertTrue(dump.checked()); assertFalse(dump.enabled());
        });
    }
}
