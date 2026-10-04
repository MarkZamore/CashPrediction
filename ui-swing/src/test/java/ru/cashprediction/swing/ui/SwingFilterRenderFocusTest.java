package ru.cashprediction.swing.ui;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.app.UiIntents;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.menu.ToolbarModel;
import ru.cashprediction.core.ui.menu.ToolbarNode;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет живую идентичность поля фильтра при render, не создавая окон или фиктивного focus owner. */
class SwingFilterRenderFocusTest {
    /** Обновление модели не отсоединяет поле, его document и родителя от toolbar. */
    @Test void renderKeepsAttachedFilterAndDocument() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install();
            SwingToolbar toolbar = new SwingToolbar(null);
            toolbar.render(model("", false));
            var field = toolbar.filter(); var parent = field.getParent(); var document = field.getDocument();
            field.setText("sample"); field.setCaretPosition(3);
            int[] removals = {0};
            toolbar.addContainerListener(new java.awt.event.ContainerAdapter() {
                /** Регистрирует настоящее удаление контейнера фильтра, а не предполагаемый фокус. */
                @Override public void componentRemoved(java.awt.event.ContainerEvent event) {
                    if (event.getChild() == parent) removals[0]++;
                }
            });
            toolbar.render(model("sample", true));
            assertSame(field, toolbar.filter()); assertSame(document, toolbar.filter().getDocument());
            assertSame(parent, field.getParent()); assertSame(toolbar, parent.getParent());
            assertEquals(0, removals[0]); assertEquals(3, field.getCaretPosition());
            toolbar.render(model("", false));
            assertSame(field, toolbar.filter()); assertEquals("", field.getText()); assertEquals(0, removals[0]);
        });
    }

    /** Повторный render не дублирует Enter handlers и не передаёт программный setText как user intent. */
    @Test void retainedFilterEnterUsesLatestTextExactlyOnce() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); List<String> calls = new ArrayList<>();
            UiIntents intents = (UiIntents) Proxy.newProxyInstance(UiIntents.class.getClassLoader(), new Class<?>[]{UiIntents.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("filterText")) calls.add("text:" + args[0]);
                        if (method.getName().equals("command")) { assertEquals(CommandId.FILTER_FOCUS_TABLE, args[0]); calls.add("focusTable"); }
                        return method.getReturnType() == boolean.class ? false : null;
                    });
            SwingToolbar toolbar = new SwingToolbar(intents);
            toolbar.render(model("old", true)); var field = toolbar.filter();
            for (int i = 0; i < 3; i++) toolbar.render(model("new", true));
            assertTrue(calls.isEmpty()); assertSame(field, toolbar.filter());
            field.postActionEvent();
            assertEquals(List.of("text:new", "focusTable"), calls);
        });
    }

    /** Создаёт общую модель фильтра; текст fixture не является пользовательской локализацией. */
    private static ToolbarModel model(String text, boolean clear) {
        return new ToolbarModel(List.of(new ToolbarNode.FilterField("tb.filter", text, "", "", 220, 200, clear, "")));
    }
}
