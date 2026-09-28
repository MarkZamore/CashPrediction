package ru.cashprediction.core.ui.menu;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import ru.cashprediction.core.app.AppState;
import ru.cashprediction.core.app.ClientKind;
import ru.cashprediction.core.app.DocumentView;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.document.ViewState;
import ru.cashprediction.core.ui.command.CommandArgs;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.text.UiText;

/**
 * Строка меню главного окна (спецификация v2, §3.1-§3.6): шесть меню в порядке «Файл | Правка | Вид | Инструменты |
 * Восстановление | Справка», стабильные id узлов ({@code file}, {@code file.new}, {@code file.sep.1},
 * {@code file.recent.0}, {@code view.period.M3}) - ключи дампа ({@code menuBar/…}).
 *
 * <p>Класс без состояния, потокобезопасен.</p>
 */
final class MenuBarBuilder {

    /** Расширение файла плана: в «Недавних» план из CashMemory подписан именем без него (§3.1). */
    private static final String PLAN_EXTENSION = ".md";

    private MenuBarBuilder() {
    }

    /**
     * Строит строку меню.
     *
     * @param items фабрика узлов состояния
     * @return модель шести меню
     */
    static MenuBarModel build(MenuItems items) {
        // JavaFX: MenuBar → Swing: JMenuBar → Web: div[role=menubar]
        return new MenuBarModel(List.of(file(items), edit(items), view(items), tools(items), recovery(items),
                help(items)));
    }

    private static MenuNode.Submenu file(MenuItems m) {
        AppState state = m.state();
        List<MenuNode> c = new ArrayList<>();
        c.add(m.action("file.new", CommandId.FILE_NEW, "menu.file.new", "menu.file.new.tip"));
        c.add(m.action("file.open", CommandId.FILE_OPEN, "menu.file.open", "menu.file.open.tip"));
        c.add(m.action("file.openFile", CommandId.FILE_OPEN_FILE, "menu.file.openFile", "menu.file.openFile.tip"));
        c.add(m.action("file.sample", CommandId.FILE_SAMPLE, "menu.file.sample", "menu.file.sample.tip"));
        c.add(MenuItems.submenu("file.recent", "menu.file.recent", "menu.file.recent.tip", recent(m)));
        c.add(MenuItems.separator("file.sep.1"));
        c.add(m.action("file.save", CommandId.FILE_SAVE, "menu.file.save", "menu.file.save.tip"));
        c.add(m.action("file.saveAs", CommandId.FILE_SAVE_AS, "menu.file.saveAs", "menu.file.saveAs.tip"));
        c.add(m.action("file.rename", CommandId.FILE_RENAME, "menu.file.rename", "menu.file.rename.tip"));
        c.add(MenuItems.separator("file.sep.2"));
        c.add(m.check("file.autosave", CommandId.FILE_AUTOSAVE, "menu.file.autosave", "menu.file.autosave.tip",
                state.settings().autosave()));
        c.add(m.action("file.exportCsv", CommandId.FILE_EXPORT_CSV, "menu.file.exportCsv", "menu.file.exportCsv.tip"));
        c.add(m.action("file.savePng", CommandId.FILE_SAVE_PNG, "menu.file.savePng", "menu.file.savePng.tip"));
        c.add(m.action("file.cashMemory", CommandId.FILE_CASH_MEMORY, "menu.file.cashMemory",
                "menu.file.cashMemory.tip"));
        c.add(MenuItems.separator("file.sep.3"));
        // [WEB] «Выход» останавливает сервер, и подсказка говорит об этом (§3.1, §10 №3).
        String exitTip = m.client() == ClientKind.WEB ? "menu.file.exit.tip.web" : "menu.file.exit.tip";
        c.add(m.action("file.exit", CommandId.FILE_EXIT, "menu.file.exit", exitTip));
        return MenuItems.submenu("file", "menu.file", null, c);
    }

    /**
     * Пункты «Недавние» (§3.1): не больше 10, новые сверху; метка - имя файла без «.md», если файл в CashMemory, иначе
     * полный путь; подсказка - полный путь; пустой список - отключённый пункт «(список пуст)».
     */
    private static List<MenuNode> recent(MenuItems m) {
        AppState state = m.state();
        List<String> plans = state.settings().recentPlans();
        if (plans.isEmpty()) {
            // JavaFX: MenuItem (disable) → Swing: JMenuItem (setEnabled(false)) → Web: div[aria-disabled]
            return List.of(new MenuNode.Info("file.recent.empty", UiText.get("recent.empty")));
        }
        Path cashMemory = state.cashMemory().toAbsolutePath().normalize();
        List<MenuNode> items = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            String entry = plans.get(i);
            String fullPath;
            String label;
            try {
                Path path = Path.of(entry);
                Path full = (path.isAbsolute() ? path : cashMemory.resolve(path)).toAbsolutePath().normalize();
                fullPath = full.toString();
                label = cashMemory.equals(full.getParent()) ? baseName(full) : fullPath;
            } catch (InvalidPathException e) {
                // Вручную исправленный settings.md может содержать не путь: пункт показывает запись как есть, а
                // «Файл плана не найден» при открытии объяснит пользователю, что с ней не так.
                fullPath = entry;
                label = entry;
            }
            items.add(m.action("file.recent." + i, CommandId.FILE_RECENT_OPEN,
                    CommandArgs.keyValue(Integer.toString(i), fullPath), label, null, fullPath));
        }
        return items;
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(PLAN_EXTENSION)
                ? name.substring(0, name.length() - PLAN_EXTENSION.length()) : name;
    }

    private static MenuNode.Submenu edit(MenuItems m) {
        DocumentView document = m.state().document();
        List<MenuNode> c = new ArrayList<>();
        c.add(m.action("edit.addIncome", CommandId.EDIT_ADD_INCOME, "menu.edit.addIncome", "menu.edit.addIncome.tip"));
        c.add(m.action("edit.addExpense", CommandId.EDIT_ADD_EXPENSE, "menu.edit.addExpense",
                "menu.edit.addExpense.tip"));
        c.add(m.action("edit.addOneTime", CommandId.EDIT_ADD_ONE_TIME, "menu.edit.addOneTime",
                "menu.edit.addOneTime.tip"));
        c.add(m.action("edit.edit", CommandId.EDIT_EDIT, "menu.edit.edit", "menu.edit.edit.tip"));
        c.add(m.action("edit.delete", CommandId.EDIT_DELETE, "menu.edit.delete", "menu.edit.delete.tip"));
        c.add(m.action("edit.adjust", CommandId.EDIT_ADJUST, "menu.edit.adjust", "menu.edit.adjust.tip"));
        c.add(m.action("edit.skip", CommandId.EDIT_SKIP, "menu.edit.skip", "menu.edit.skip.tip"));
        c.add(m.action("edit.reset", CommandId.EDIT_RESET, "menu.edit.reset", "menu.edit.reset.tip"));
        c.add(MenuItems.separator("edit.sep.1"));
        String undo = document.canUndo() && !document.undoText().isBlank()
                ? UiText.get("menu.edit.undo.named", document.undoText()) : UiText.get("menu.edit.undo");
        c.add(m.action("edit.undo", CommandId.EDIT_UNDO, CommandArgs.NONE, undo, m.accel(CommandId.EDIT_UNDO),
                UiText.get("menu.edit.undo.tip")));
        String redo = document.canRedo() && !document.redoText().isBlank()
                ? UiText.get("menu.edit.redo.named", document.redoText()) : UiText.get("menu.edit.redo");
        c.add(m.action("edit.redo", CommandId.EDIT_REDO, CommandArgs.NONE, redo, m.accel(CommandId.EDIT_REDO),
                UiText.get("menu.edit.redo.tip")));
        c.add(MenuItems.separator("edit.sep.2"));
        c.add(m.action("edit.planSettings", CommandId.EDIT_PLAN_SETTINGS, "menu.edit.planSettings",
                "menu.edit.planSettings.tip"));
        c.add(m.action("edit.actualize", CommandId.EDIT_ACTUALIZE, "menu.edit.actualize", "menu.edit.actualize.tip"));
        c.add(m.action("edit.reconcile", CommandId.EDIT_RECONCILE, "menu.edit.reconcile", "menu.edit.reconcile.tip"));
        return MenuItems.submenu("edit", "menu.edit", null, c);
    }

    private static MenuNode.Submenu view(MenuItems m) {
        ViewState view = m.state().view();
        List<MenuNode> c = new ArrayList<>();
        c.add(m.radio("view.table", MenuModels.GROUP_MODE, CommandId.VIEW_TABLE, "menu.view.table",
                "menu.view.table.tip", view.mode() == ViewMode.TABLE));
        c.add(m.radio("view.chart", MenuModels.GROUP_MODE, CommandId.VIEW_CHART, "menu.view.chart",
                "menu.view.chart.tip", view.mode() == ViewMode.CHART));
        c.add(MenuItems.separator("view.sep.1"));
        c.add(m.check("view.flag.showIncome", CommandId.VIEW_FLAG_SHOW_INCOME, "menu.view.flag.showIncome",
                "menu.view.flag.showIncome.tip", view.showIncome()));
        c.add(m.check("view.flag.showExpense", CommandId.VIEW_FLAG_SHOW_EXPENSE, "menu.view.flag.showExpense",
                "menu.view.flag.showExpense.tip", view.showExpense()));
        c.add(m.check("view.flag.showOneTime", CommandId.VIEW_FLAG_SHOW_ONE_TIME, "menu.view.flag.showOneTime",
                "menu.view.flag.showOneTime.tip", view.showOneTime()));
        c.add(m.check("view.flag.showSkipped", CommandId.VIEW_FLAG_SHOW_SKIPPED, "menu.view.flag.showSkipped",
                "menu.view.flag.showSkipped.tip", view.showSkipped()));
        c.add(m.check("view.flag.monthTotals", CommandId.VIEW_FLAG_MONTH_TOTALS, "menu.view.flag.monthTotals",
                "menu.view.flag.monthTotals.tip", view.monthTotals()));
        c.add(m.check("view.flag.chartMarkers", CommandId.VIEW_FLAG_CHART_MARKERS, "menu.view.flag.chartMarkers",
                "menu.view.flag.chartMarkers.tip", view.chartMarkers()));
        c.add(m.check("view.flag.chartBars", CommandId.VIEW_FLAG_CHART_BARS, "menu.view.flag.chartBars",
                "menu.view.flag.chartBars.tip", view.chartBars()));
        c.add(m.check("view.flag.summaryPanel", CommandId.VIEW_FLAG_SUMMARY_PANEL, "menu.view.flag.summaryPanel",
                "menu.view.flag.summaryPanel.tip", view.summaryPanel()));
        c.add(MenuItems.separator("view.sep.2"));
        c.addAll(m.periodItems("view.sep.3"));
        c.add(MenuItems.separator("view.sep.4"));
        c.add(m.action("view.focusFilter", CommandId.VIEW_FOCUS_FILTER, "menu.view.focusFilter",
                "menu.view.focusFilter.tip"));
        return MenuItems.submenu("view", "menu.view", null, c);
    }

    private static MenuNode.Submenu tools(MenuItems m) {
        List<MenuNode> c = new ArrayList<>();
        c.add(m.action("tools.goal", CommandId.TOOLS_GOAL, "menu.tools.goal", "menu.tools.goal.tip"));
        c.add(MenuItems.submenu("tools.whatIf", "menu.tools.whatIf", "menu.tools.whatIf.tip",
                m.whatIfItems("tools.whatIf.sep.1")));
        c.add(MenuItems.separator("tools.sep.1"));
        c.add(m.action("tools.validate", CommandId.TOOLS_VALIDATE, "menu.tools.validate", "menu.tools.validate.tip"));
        c.add(m.action("tools.cleanup", CommandId.TOOLS_CLEANUP, "menu.tools.cleanup", "menu.tools.cleanup.tip"));
        c.add(m.action("tools.currency", CommandId.TOOLS_CURRENCY, "menu.tools.currency", "menu.tools.currency.tip"));
        return MenuItems.submenu("tools", "menu.tools", null, c);
    }

    private static MenuNode.Submenu recovery(MenuItems m) {
        List<MenuNode> c = new ArrayList<>();
        if (m.client() == ClientKind.WEB) {
            // [WEB] Одно отмеченное и отключённое радио вместо двух (§3.5, §10 №1): снимок хранит сервер.
            c.add(m.radio("recovery.store.server", MenuModels.GROUP_STORE, CommandId.RECOVERY_STORE_SERVER,
                    "menu.recovery.store.server", "menu.recovery.store.server.tip", true));
        } else {
            RecoveryStoreKind store = m.state().settings().recoveryStore();
            c.add(m.radio("recovery.store.registry", MenuModels.GROUP_STORE, CommandId.RECOVERY_STORE_REGISTRY,
                    "menu.recovery.store.registry", "menu.recovery.store.registry.tip",
                    store == RecoveryStoreKind.REGISTRY));
            c.add(m.radio("recovery.store.xml", MenuModels.GROUP_STORE, CommandId.RECOVERY_STORE_XML,
                    "menu.recovery.store.xml", "menu.recovery.store.xml.tip", store == RecoveryStoreKind.XML));
        }
        c.add(MenuItems.separator("recovery.sep.1"));
        c.add(m.action("recovery.snapshotNow", CommandId.RECOVERY_SNAPSHOT_NOW, "menu.recovery.snapshotNow",
                "menu.recovery.snapshotNow.tip"));
        c.add(m.action("recovery.showLast", CommandId.RECOVERY_SHOW_LAST, "menu.recovery.showLast",
                "menu.recovery.showLast.tip"));
        c.add(m.action("recovery.clear", CommandId.RECOVERY_CLEAR, "menu.recovery.clear", "menu.recovery.clear.tip"));
        c.add(MenuItems.separator("recovery.sep.2"));
        c.add(MenuItems.submenu("recovery.simulate", "menu.recovery.simulate", "menu.recovery.simulate.tip", List.of(
                m.action("recovery.simulate.halt", CommandId.RECOVERY_SIMULATE_HALT, "menu.recovery.simulate.halt",
                        "menu.recovery.simulate.halt.tip"),
                m.action("recovery.simulate.exception", CommandId.RECOVERY_SIMULATE_EXCEPTION,
                        "menu.recovery.simulate.exception", "menu.recovery.simulate.exception.tip"))));
        return MenuItems.submenu("recovery", "menu.recovery", null, c);
    }

    private static MenuNode.Submenu help(MenuItems m) {
        return MenuItems.submenu("help", "menu.help", null, List.of(
                m.action("help.about", CommandId.HELP_ABOUT, "menu.help.about", "menu.help.about.tip"),
                m.action("help.hotkeys", CommandId.HELP_HOTKEYS, "menu.help.hotkeys", "menu.help.hotkeys.tip"),
                m.action("help.format", CommandId.HELP_FORMAT, "menu.help.format", "menu.help.format.tip")));
    }
}
