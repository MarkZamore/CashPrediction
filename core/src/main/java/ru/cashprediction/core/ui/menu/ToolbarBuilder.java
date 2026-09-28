package ru.cashprediction.core.ui.menu;

import java.util.ArrayList;
import java.util.List;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.ui.command.CommandArgs;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.token.DesignTokens;

/**
 * Тулбар главного окна слева направо (спецификация v2, §4). Id элементов - ключи дампа ({@code toolbar/…}):
 * {@code tb.add}, {@code tb.sep.1}, {@code tb.table}, {@code tb.chart}, {@code tb.sep.2}, {@code tb.period},
 * {@code tb.whatIf}, {@code tb.sep.3}, {@code tb.filter}, {@code tb.spacer}, {@code tb.undo}, {@code tb.redo},
 * {@code tb.save}. Пункты выпадающих меню носят id пунктов строки меню ({@code edit.addExpense},
 * {@code view.period.M3}, {@code whatIf.extra}), поэтому шаг самотеста {@code click tb.add.menu:edit.addExpense}
 * находит пункт по тому же id.
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
final class ToolbarBuilder {

    private ToolbarBuilder() {
    }

    /**
     * Строит тулбар.
     *
     * @param m фабрика узлов состояния
     * @return модель тулбара
     */
    static ToolbarModel build(MenuItems m) {
        DocumentView document = m.state().document();
        ViewState view = m.state().view();
        List<ToolbarNode> nodes = new ArrayList<>();

        // Пункты выпадающего списка без ускорителей: у кнопок тулбара сочетания названы в подсказке (§4 п. 1).
        MenuNode.Action main = m.action("edit.addIncome", CommandId.EDIT_ADD_INCOME, CommandArgs.NONE,
                UiText.get("toolbar.tb.add"), null, UiText.get("toolbar.tb.add.tip"));
        List<MenuNode> addItems = MenuItems.clean(List.of(
                m.action("edit.addExpense", CommandId.EDIT_ADD_EXPENSE, CommandArgs.NONE,
                        UiText.get("menu.edit.addExpense"), null, UiText.get("menu.edit.addExpense.tip")),
                m.action("edit.addOneTime", CommandId.EDIT_ADD_ONE_TIME, CommandArgs.NONE,
                        UiText.get("menu.edit.addOneTime"), null, UiText.get("menu.edit.addOneTime.tip")),
                MenuItems.separator("tb.add.sep.1"),
                m.action("edit.adjust", CommandId.EDIT_ADJUST, CommandArgs.NONE, UiText.get("toolbar.tb.add.adjust"),
                        null, UiText.get("menu.edit.adjust.tip"))));
        // JavaFX: SplitMenuButton → Swing: SwingSplitMenuButton → Web: пара кнопок с выпадающим меню
        nodes.add(new ToolbarNode.SplitButton("tb.add", UiText.get("toolbar.tb.add"), UiText.get("toolbar.tb.add.tip"),
                main, addItems));
        nodes.add(new ToolbarNode.Separator("tb.sep.1"));

        // JavaFX: ToggleButton + ToggleGroup → Swing: JToggleButton + ButtonGroup → Web: button[aria-pressed]
        nodes.add(new ToolbarNode.Toggle("tb.table", CommandId.VIEW_TABLE, UiText.get("toolbar.tb.table"),
                UiText.get("toolbar.tb.table.tip"), view.mode() == ViewMode.TABLE, MenuModels.GROUP_MODE));
        nodes.add(new ToolbarNode.Toggle("tb.chart", CommandId.VIEW_CHART, UiText.get("toolbar.tb.chart"),
                UiText.get("toolbar.tb.chart.tip"), view.mode() == ViewMode.CHART, MenuModels.GROUP_MODE));
        nodes.add(new ToolbarNode.Separator("tb.sep.2"));

        // JavaFX: MenuButton → Swing: SwingMenuButton → Web: кнопка с выпадающим div[role=menu]
        nodes.add(new ToolbarNode.MenuButton("tb.period", MenuItems.periodText(view.period()),
                UiText.get("toolbar.tb.period.tip"), Emphasis.NONE, MenuItems.clean(m.periodItems("tb.period.sep.1"))));
        boolean whatIfActive = !view.whatIf().isNone();
        String whatIfText = whatIfActive ? UiText.get("toolbar.tb.whatIf.active") : UiText.get("toolbar.tb.whatIf");
        // JavaFX: MenuButton → Swing: SwingMenuButton → Web: кнопка с выпадающим div[role=menu]
        nodes.add(new ToolbarNode.MenuButton("tb.whatIf", whatIfText, UiText.get("toolbar.tb.whatIf.tip"),
                whatIfActive ? Emphasis.WHATIF : Emphasis.NONE, MenuItems.clean(m.whatIfItems("tb.whatIf.sep.1"))));
        nodes.add(new ToolbarNode.Separator("tb.sep.3"));

        nodes.add(new ToolbarNode.FilterField("tb.filter", view.filterText(), UiText.get("toolbar.tb.filter.prompt"),
                UiText.get("toolbar.tb.filter.tip"), DesignTokens.FILTER_WIDTH, DesignTokens.FILTER_DEBOUNCE_MS,
                !view.filterText().isEmpty(), UiText.get("toolbar.tb.filter.clear.tip")));
        nodes.add(new ToolbarNode.Spacer("tb.spacer"));

        String undoTip = document.canUndo() && !document.undoText().isBlank()
                ? UiText.get("toolbar.tb.undo.tip.named", document.undoText()) : UiText.get("toolbar.tb.undo.tip");
        nodes.add(new ToolbarNode.Button("tb.undo", CommandId.EDIT_UNDO, UiText.get("toolbar.tb.undo"), undoTip,
                m.enabled(CommandId.EDIT_UNDO, CommandArgs.NONE), Emphasis.NONE));
        String redoTip = document.canRedo() && !document.redoText().isBlank()
                ? UiText.get("toolbar.tb.redo.tip.named", document.redoText()) : UiText.get("toolbar.tb.redo.tip");
        nodes.add(new ToolbarNode.Button("tb.redo", CommandId.EDIT_REDO, UiText.get("toolbar.tb.redo"), redoTip,
                m.enabled(CommandId.EDIT_REDO, CommandArgs.NONE), Emphasis.NONE));
        // «Сохранить» никогда не отключается; при несохранённых изменениях - жирный текст цвета accent (§4 п. 13).
        nodes.add(new ToolbarNode.Button("tb.save", CommandId.FILE_SAVE, UiText.get("toolbar.tb.save"),
                UiText.get("toolbar.tb.save.tip"), m.enabled(CommandId.FILE_SAVE, CommandArgs.NONE),
                document.dirty() ? Emphasis.ACCENT : Emphasis.NONE));
        return new ToolbarModel(nodes);
    }
}
