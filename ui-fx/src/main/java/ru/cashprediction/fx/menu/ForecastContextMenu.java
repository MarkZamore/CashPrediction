package ru.cashprediction.fx.menu;

import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import ru.cashprediction.core.forecast.ForecastRow;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.core.util.DateFormats;
import ru.cashprediction.fx.ShellContext;
import ru.cashprediction.fx.dialog.OpenRequest;
import ru.cashprediction.fx.view.TableEntry;

import java.time.LocalDate;

/**
 * Контекстные меню главного окна: строка таблицы прогноза, график, карточка сводки.
 *
 * <p>Меню создаются заново при каждом вызове ({@code ContextMenuEvent}), поэтому доступность пунктов всегда
 * соответствует строке под указателем. Все пункты вызывают тот же фасад команд, что и главное меню.</p>
 *
 * <p>Только FX Application Thread.</p>
 */
// JavaFX: ContextMenu → Swing: JPopupMenu (ForecastPopupMenu) → Web: <ul class="context-menu">
public final class ForecastContextMenu {

    private ForecastContextMenu() {
    }

    /**
     * Меню строки таблицы.
     *
     * @param shell     оболочка приложения
     * @param entry     строка под указателем
     * @param quickEdit открыть быструю правку суммы этой строки
     * @return меню (не показано)
     */
    public static ContextMenu forRow(ShellContext shell, TableEntry entry, Runnable quickEdit) {
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: <ul class="context-menu">
        ContextMenu menu = new ContextMenu();
        if (entry.isTotal()) {
            MenuItem copy = item("Копировать итог месяца", () -> copy(DateFormats.monthTitle(entry.month()) + "\t"
                    + entry.totals().income().format() + "\t" + entry.totals().expense().format() + "\t"
                    + entry.totals().closingBalance().format()));
            menu.getItems().add(copy);
            return menu;
        }
        ForecastRow row = entry.row();
        String rowId = row.rowId();
        boolean rule = row.origin() == Origin.RULE;
        boolean oneTime = row.origin() == Origin.ONE_TIME;

        MenuItem edit = item(row.origin() == Origin.START ? "Параметры плана…" : "Изменить…",
                () -> shell.actions().editRow(rowId));
        edit.setDisable(row.origin() == Origin.WHAT_IF);
        MenuItem quick = item("Быстрая правка суммы…", quickEdit);
        quick.setDisable(!rule);
        MenuItem adjust = item("Скорректировать событие…", () -> shell.actions().adjustRow(rowId));
        adjust.setDisable(!rule);
        MenuItem skip = item("Пропустить событие", () -> shell.actions().skipRow(rowId));
        skip.setDisable(!rule || row.flags().skipped());
        MenuItem reset = item("Вернуть как по правилу", () -> shell.actions().resetRow(rowId));
        reset.setDisable(!rule || !(row.flags().adjusted() || row.flags().skipped()));
        MenuItem addOneTime = item("Добавить разовую на эту дату…",
                () -> shell.actions().addOneTime(row.date(), Kind.EXPENSE, OpenRequest.fromMain()));
        MenuItem goToRule = item("Перейти к правилу…", () -> shell.actions().editRule(row.ruleId(), OpenRequest.fromMain()));
        goToRule.setDisable(!rule);
        MenuItem disable = item("Отключить правило", () -> shell.actions().disableRule(row.ruleId()));
        disable.setDisable(!rule);
        MenuItem delete = item("Удалить…", () -> shell.actions().deleteRow(rowId));
        delete.setDisable(!rule && !oneTime);
        MenuItem copy = item("Копировать строку", () -> copy(DateFormats.ru(row.date()) + "\t" + row.title() + "\t"
                + row.amount().formatSigned() + "\t" + row.balanceAfter().format()));

        // JavaFX: SeparatorMenuItem → Swing: JPopupMenu.addSeparator() → Web: <li role="separator"><hr>
        menu.getItems().addAll(edit, quick, adjust, skip, reset, new SeparatorMenuItem(), addOneTime, goToRule, disable,
                delete, new SeparatorMenuItem(), copy);
        return menu;
    }

    /**
     * Меню графика.
     *
     * @param shell оболочка приложения
     * @param date  дата под указателем или {@code null}, если указатель вне области построения
     * @return меню (не показано)
     */
    public static ContextMenu forChart(ShellContext shell, LocalDate date) {
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: <ul class="context-menu">
        ContextMenu menu = new ContextMenu();
        String suffix = date == null ? "" : " (" + DateFormats.ru(date) + ")";
        MenuItem showInTable = item("Показать в таблице с этой даты" + suffix, () -> shell.showTableFrom(date));
        showInTable.setDisable(date == null);
        MenuItem addOneTime = item("Добавить разовую на эту дату…" + suffix,
                () -> shell.actions().addOneTime(date, Kind.EXPENSE, OpenRequest.fromMain()));
        addOneTime.setDisable(date == null);
        MenuItem png = item("Сохранить график PNG…", () -> shell.actions().saveChartPng(shell.chartNode()));
        // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: role="menuitemcheckbox"
        CheckMenuItem markers = new CheckMenuItem("Маркеры событий");
        markers.setSelected(shell.document().viewState().chartMarkers());
        markers.setOnAction(e -> shell.updateView(v -> v.withChartMarkers(markers.isSelected())));
        // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: role="menuitemcheckbox"
        CheckMenuItem bars = new CheckMenuItem("Столбцы итогов месяцев");
        bars.setSelected(shell.document().viewState().chartBars());
        bars.setOnAction(e -> shell.updateView(v -> v.withChartBars(bars.isSelected())));
        // JavaFX: SeparatorMenuItem → Swing: JPopupMenu.addSeparator() → Web: <li role="separator"><hr>
        menu.getItems().addAll(showInTable, addOneTime, new SeparatorMenuItem(), png, new SeparatorMenuItem(), markers, bars);
        return menu;
    }

    /**
     * Меню карточки сводки.
     *
     * @param shell оболочка приложения
     * @param date  дата карточки (например, дата минимума) или {@code null}
     * @param goal  карточка цели
     * @return меню (не показано)
     */
    public static ContextMenu forSummaryCard(ShellContext shell, LocalDate date, boolean goal) {
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: <ul class="context-menu">
        ContextMenu menu = new ContextMenu();
        MenuItem showInTable = item("Показать в таблице с этой даты"
                + (date == null ? "" : " (" + DateFormats.ru(date) + ")"), () -> shell.showTableFrom(date));
        showInTable.setDisable(date == null);
        MenuItem calculator = item(goal ? "Калькулятор цели…" : "Когда я накоплю…? (калькулятор цели)",
                () -> shell.actions().goalCalculator(OpenRequest.fromMain()));
        MenuItem hide = item("Скрыть панель сводки", () -> shell.updateView(v -> v.withSummaryPanel(false)));
        // JavaFX: SeparatorMenuItem → Swing: JPopupMenu.addSeparator() → Web: <li role="separator"><hr>
        menu.getItems().addAll(showInTable, calculator, new SeparatorMenuItem(), hide);
        return menu;
    }

    private static MenuItem item(String text, Runnable action) {
        // JavaFX: MenuItem → Swing: JMenuItem → Web: <li role="menuitem">
        MenuItem item = new MenuItem(text);
        item.setOnAction(e -> action.run());
        return item;
    }

    private static void copy(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
