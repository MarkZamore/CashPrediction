package ru.cashprediction.core.ui.menu;

import java.time.LocalDate;
import java.util.List;
import ru.cashprediction.core.ui.command.CommandArgs;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.command.RowRef;
import ru.cashprediction.core.ui.text.UiFormats;
import ru.cashprediction.core.ui.text.UiText;
import ru.cashprediction.core.ui.view.table.RowKind;

/**
 * Контекстные меню главного окна и предпросмотра дат (спецификация v2, §5.1-§5.3, §5.5, §6.3). Id пунктов -
 * {@code ctx.<цель>.<пункт>}, разделители {@code ctx.<цель>.sep.N} (ключи дампа {@code contextMenus[…]/items}).
 * Подсказок у пунктов контекстных меню спецификация не задаёт, поэтому они пустые; ускорители - только у пунктов
 * строки из §5.2 (Enter, Ctrl+J, Delete).
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
final class ContextMenuBuilder {

    private ContextMenuBuilder() {
    }

    /**
     * Строит меню объекта.
     *
     * @param m      фабрика узлов состояния
     * @param target объект
     * @param facts  даты карточки и графика
     * @return пункты; пустой список - меню не показывается
     */
    static List<MenuNode> build(MenuItems m, ContextTarget target, ContextFacts facts) {
        // JavaFX: ContextMenu по ContextMenuEvent → Swing: JPopupMenu по isPopupTrigger/Shift+F10 → Web: contextmenu
        List<MenuNode> nodes = switch (target) {
            case ContextTarget.Row row -> row(m, row.rowId());
            case ContextTarget.Total total -> total(m, total.rowId());
            case ContextTarget.PastHeader header -> pastHeader(m, header.rowId());
            case ContextTarget.Card card -> card(m, card.cardId(), facts.cardDate(m.state(), card.cardId()).orElse(null));
            case ContextTarget.Chart chart -> chart(m, facts.chartDate(m.state(), chart).orElse(null));
            case ContextTarget.Preview preview -> preview(m, preview);
        };
        return MenuItems.clean(nodes);
    }

    /** Меню строки события (§5.2): пустое, если строки нет в плане (щелчок мимо строк меню не показывает). */
    private static List<MenuNode> row(MenuItems m, String rowId) {
        RowRef row = RowRef.resolve(m.state(), rowId);
        if (row.kind() == null) {
            return List.of();
        }
        CommandArgs args = CommandArgs.row(row.rowId());
        LocalDate date = row.date(m.state()).orElse(null);
        String editText = row.is(RowKind.START) ? UiText.get("ctx.row.edit.start") : UiText.get("ctx.row.edit");
        return List.of(
                m.action("ctx.row.edit", CommandId.ROW_EDIT, args, editText, m.accel(CommandId.EDIT_EDIT), ""),
                m.action("ctx.row.quickEdit", CommandId.ROW_QUICK_EDIT, args, UiText.get("ctx.row.quickEdit"), null, ""),
                m.action("ctx.row.adjust", CommandId.ROW_ADJUST, args, UiText.get("ctx.row.adjust"),
                        m.accel(CommandId.EDIT_ADJUST), ""),
                m.action("ctx.row.skip", CommandId.ROW_SKIP, args, UiText.get("ctx.row.skip"), null, ""),
                m.action("ctx.row.reset", CommandId.ROW_RESET, args, UiText.get("ctx.row.reset"), null, ""),
                MenuItems.separator("ctx.row.sep.1"),
                m.action("ctx.row.addOneTime", CommandId.ROW_ADD_ONE_TIME, CommandArgs.rowAndDate(row.rowId(), date),
                        UiText.get("ctx.row.addOneTime", UiFormats.date(date)), null, ""),
                m.action("ctx.row.goToRule", CommandId.ROW_GO_TO_RULE, args, UiText.get("ctx.row.goToRule"), null, ""),
                m.action("ctx.row.disableRule", CommandId.ROW_DISABLE_RULE, args, UiText.get("ctx.row.disableRule"),
                        null, ""),
                MenuItems.separator("ctx.row.sep.2"),
                m.action("ctx.row.copy", CommandId.ROW_COPY, args, UiText.get("ctx.row.copy"), null, ""),
                m.action("ctx.row.delete", CommandId.ROW_DELETE, args, UiText.get("ctx.row.delete"),
                        m.accel(CommandId.EDIT_DELETE), ""));
    }

    /** Меню строки итога месяца (§5.2). */
    private static List<MenuNode> total(MenuItems m, String rowId) {
        return List.of(
                m.action("ctx.total.copy", CommandId.TOTAL_COPY, CommandArgs.row(rowId), UiText.get("ctx.total.copy"),
                        null, ""),
                MenuItems.separator("ctx.total.sep.1"),
                m.checkWithText("ctx.total.monthTotals", CommandId.VIEW_FLAG_MONTH_TOTALS,
                        UiText.get("menu.view.flag.monthTotals"), "", m.state().view().monthTotals()));
    }

    /** Меню группы «Прошедшие события» (§5.2): один пункт по состоянию группы. */
    private static List<MenuNode> pastHeader(MenuItems m, String rowId) {
        String text = m.state().pastExpanded() ? UiText.get("ctx.pastHeader.hide") : UiText.get("ctx.pastHeader.show");
        return List.of(m.action("ctx.pastHeader.toggle", CommandId.PAST_TOGGLE, CommandArgs.row(rowId), text, null, ""));
    }

    /** Меню карточки сводки (§5.1): без даты «Показать в таблице» отключён. */
    private static List<MenuNode> card(MenuItems m, String cardId, LocalDate date) {
        CommandArgs args = CommandArgs.card(cardId, date);
        String show = date == null ? UiText.get("ctx.card.showInTable.noDate")
                : UiText.get("ctx.card.showInTable", UiFormats.date(date));
        return List.of(
                m.action("ctx.card.showInTable", CommandId.CARD_SHOW_IN_TABLE, args, show, null, ""),
                m.action("ctx.card.copyValue", CommandId.CARD_COPY_VALUE, args, UiText.get("ctx.card.copyValue"), null,
                        ""),
                m.action("ctx.card.goal", CommandId.TOOLS_GOAL, CommandArgs.NONE, UiText.get("menu.tools.goal"), null, ""),
                MenuItems.separator("ctx.card.sep.1"),
                m.checkWithText("ctx.card.summaryPanel", CommandId.VIEW_FLAG_SUMMARY_PANEL,
                        UiText.get("menu.view.flag.summaryPanel"), "", m.state().view().summaryPanel()));
    }

    /** Меню графика (§5.3): вне области построения даты нет, пункты 1-2 отключены. */
    private static List<MenuNode> chart(MenuItems m, LocalDate date) {
        CommandArgs args = CommandArgs.date(date);
        String show = date == null ? UiText.get("ctx.chart.showInTable.noDate")
                : UiText.get("ctx.chart.showInTable", UiFormats.date(date));
        String add = date == null ? UiText.get("ctx.chart.addOneTime.noDate")
                : UiText.get("ctx.chart.addOneTime", UiFormats.date(date));
        return List.of(
                m.action("ctx.chart.showInTable", CommandId.CHART_SHOW_IN_TABLE, args, show, null, ""),
                m.action("ctx.chart.addOneTime", CommandId.CHART_ADD_ONE_TIME, args, add, null, ""),
                MenuItems.separator("ctx.chart.sep.1"),
                m.checkWithText("ctx.chart.chartMarkers", CommandId.VIEW_FLAG_CHART_MARKERS,
                        UiText.get("menu.view.flag.chartMarkers"), "", m.state().view().chartMarkers()),
                m.checkWithText("ctx.chart.chartBars", CommandId.VIEW_FLAG_CHART_BARS, UiText.get("menu.view.flag.chartBars"), "",
                        m.state().view().chartBars()),
                MenuItems.separator("ctx.chart.sep.2"),
                m.action("ctx.chart.savePng", CommandId.FILE_SAVE_PNG, CommandArgs.NONE, UiText.get("menu.file.savePng"),
                        null, ""));
    }

    /**
     * Меню элемента предпросмотра дат редактора правила (§6.3): «Скорректировать эту дату…»; источник команды у
     * клиента - {@code InvokeSource.FORM}, в {@code args.key} - id окна, в {@code args.value} - номер элемента. Можно ли
     * корректировать дату (режим изменения, выбранный элемент), решает форма в {@code FormSession.previewSelected}.
     */
    private static List<MenuNode> preview(MenuItems m, ContextTarget.Preview preview) {
        CommandArgs args = CommandArgs.keyValue(preview.windowId(), Integer.toString(preview.index()));
        return List.of(m.action("ctx.preview.adjust", CommandId.PREVIEW_ADJUST, args, UiText.get("ctx.preview.adjust"),
                null, ""));
    }
}
