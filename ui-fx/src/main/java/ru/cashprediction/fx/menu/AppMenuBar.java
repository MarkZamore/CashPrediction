package ru.cashprediction.fx.menu;

import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import ru.cashprediction.core.document.AppSettings;
import ru.cashprediction.core.document.PlanDocument;
import ru.cashprediction.core.document.RecoveryStoreKind;
import ru.cashprediction.core.model.Kind;
import ru.cashprediction.fx.ShellContext;
import ru.cashprediction.fx.action.FxActions;
import ru.cashprediction.fx.dialog.OpenRequest;

import java.util.List;
import java.util.Objects;

/**
 * Строка меню главного окна: Файл | Правка | Вид | Инструменты | Восстановление | Справка (раздел 6.4 плана).
 *
 * <p>Пункты вызывают фасад команд {@link FxActions}; окна, которые можно восстановить после сбоя, открываются с
 * {@code OpenRequest.fromMain()}. Горячие клавиши заданы как {@code KeyCombination} пунктов меню: JavaFX сам
 * регистрирует их на сцене главного окна. Enter и Delete не назначены ускорителями, их обрабатывает таблица:
 * иначе Delete в поле фильтра удалял бы операцию, а не символ.</p>
 *
 * <p>Элементы вида (режим, период, флаги, «что-если», слайдер горизонта) живут в {@link ViewControls} и общие
 * с панелью инструментов. Только FX Application Thread.</p>
 */
// JavaFX: MenuBar → Swing: JMenuBar → Web: <nav role="menubar">
public final class AppMenuBar extends MenuBar {

    private final ShellContext shell;
    private final FxActions actions;
    private final MenuItem undo;
    private final MenuItem redo;
    // JavaFX: Menu → Swing: JMenu → Web: <ul role="menu">
    private final Menu recent = new Menu("Недавние");
    // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: role="menuitemcheckbox"
    private final CheckMenuItem autosave = new CheckMenuItem("Автосохранение");
    // JavaFX: RadioMenuItem + ToggleGroup → Swing: JRadioButtonMenuItem + ButtonGroup → Web: role="menuitemradio" (в Web — единственный пункт «Сервер»)
    private final ToggleGroup storeGroup = new ToggleGroup();
    private final RadioMenuItem storeRegistry = new RadioMenuItem("Хранилище по умолчанию: реестр Windows");
    private final RadioMenuItem storeXml = new RadioMenuItem("Хранилище по умолчанию: XML-файл");
    private boolean syncing;

    /**
     * Строит все меню.
     *
     * @param shell    оболочка приложения
     * @param controls элементы управления видом (общие с панелью инструментов)
     */
    public AppMenuBar(ShellContext shell, ViewControls controls) {
        this.shell = Objects.requireNonNull(shell, "shell");
        this.actions = shell.actions();
        this.undo = item("Отменить", this::shortcut, KeyCode.Z, actions::undo);
        this.redo = item("Повторить", this::shortcut, KeyCode.Y, actions::redo);
        getMenus().addAll(List.of(fileMenu(), editMenu(), viewMenu(controls), toolsMenu(controls), recoveryMenu(), helpMenu()));
        setUseSystemMenuBar(false);
    }

    // ------------------------------------------------------------------ Файл

    private Menu fileMenu() {
        // JavaFX: Menu → Swing: JMenu → Web: <ul role="menu">
        Menu menu = new Menu("Файл");
        autosave.selectedProperty().addListener((o, a, b) -> {
            if (!syncing) {
                shell.updateSettings(s -> s.withAutosave(b));
            }
        });
        menu.getItems().addAll(
                item("Новый план…", this::shortcut, KeyCode.N, () -> actions.newPlan(OpenRequest.fromMain())),
                item("Открыть…", this::shortcut, KeyCode.O, () -> actions.openPlan(OpenRequest.fromMain())),
                item("Открыть из файла…", null, null, actions::openFromFile),
                item("Открыть пример", null, null, actions::openSample),
                recent,
                // JavaFX: SeparatorMenuItem → Swing: JMenu.addSeparator() → Web: <li role="separator"><hr>
                new SeparatorMenuItem(),
                item("Сохранить", this::shortcut, KeyCode.S, () -> actions.save(ok -> { })),
                item("Сохранить как…", this::shortcutShift, KeyCode.S, () -> actions.saveAs(ok -> { })),
                item("Переименовать…", null, KeyCode.F2, () -> actions.rename(OpenRequest.fromMain())),
                // JavaFX: SeparatorMenuItem → Swing: JMenu.addSeparator() → Web: <li role="separator"><hr>
                new SeparatorMenuItem(),
                autosave,
                item("Экспорт CSV…", this::shortcutShift, KeyCode.C, () -> actions.exportCsv(OpenRequest.fromMain())),
                item("Сохранить график PNG…", null, null, () -> actions.saveChartPng(shell.chartNode())),
                item("Папка CashMemory…", null, null, actions::chooseCashMemoryFolder),
                // JavaFX: SeparatorMenuItem → Swing: JMenu.addSeparator() → Web: <li role="separator"><hr>
                new SeparatorMenuItem(),
                item("Выход", null, null, shell::requestExit));
        // Список недавних читается из настроек в момент открытия меню: он меняется при каждом открытии плана.
        menu.addEventHandler(Menu.ON_SHOWING, e -> rebuildRecent());
        rebuildRecent();
        return menu;
    }

    private void rebuildRecent() {
        recent.getItems().clear();
        List<String> plans = shell.settings().recentPlans();
        if (plans.isEmpty()) {
            // JavaFX: MenuItem → Swing: JMenuItem → Web: <li role="menuitem" aria-disabled="true">
            MenuItem empty = new MenuItem("(список пуст)");
            empty.setDisable(true);
            recent.getItems().add(empty);
            return;
        }
        for (String plan : plans) {
            // JavaFX: MenuItem → Swing: JMenuItem → Web: <li role="menuitem">
            MenuItem item = new MenuItem(plan);
            item.setMnemonicParsing(false);
            item.setOnAction(e -> actions.openRecent(plan));
            recent.getItems().add(item);
        }
    }

    // ------------------------------------------------------------------ Правка

    private Menu editMenu() {
        // JavaFX: Menu → Swing: JMenu → Web: <ul role="menu">
        Menu menu = new Menu("Правка");
        menu.getItems().addAll(
                item("Добавить доход…", this::shortcut, KeyCode.I, () -> actions.addRule(Kind.INCOME, OpenRequest.fromMain())),
                item("Добавить расход…", this::shortcut, KeyCode.E, () -> actions.addRule(Kind.EXPENSE, OpenRequest.fromMain())),
                item("Разовая операция…", this::shortcut, KeyCode.T, () -> actions.addOneTime(null, Kind.INCOME, OpenRequest.fromMain())),
                item("Изменить…    (Enter)", null, null, () -> actions.editRow(selected())),
                item("Удалить…    (Delete)", null, null, () -> actions.deleteRow(selected())),
                item("Скорректировать событие…", this::shortcut, KeyCode.J, () -> actions.adjustRow(selected())),
                item("Пропустить событие", null, null, () -> actions.skipRow(selected())),
                item("Вернуть как по правилу", null, null, () -> actions.resetRow(selected())),
                // JavaFX: SeparatorMenuItem → Swing: JMenu.addSeparator() → Web: <li role="separator"><hr>
                new SeparatorMenuItem(),
                undo,
                redo,
                // JavaFX: SeparatorMenuItem → Swing: JMenu.addSeparator() → Web: <li role="separator"><hr>
                new SeparatorMenuItem(),
                item("Параметры плана…", null, null, () -> actions.planSettings(OpenRequest.fromMain())),
                item("Горизонт в месяцах…", null, null, () -> actions.customMonths(OpenRequest.fromMain())),
                item("Актуализировать на сегодня…", null, null, () -> actions.actualize(OpenRequest.fromMain())),
                item("Сверить баланс…", null, null, () -> actions.reconcile(OpenRequest.fromMain())));
        return menu;
    }

    private String selected() {
        return shell.selectedRowId().orElse("");
    }

    // ------------------------------------------------------------------ Вид

    private Menu viewMenu(ViewControls controls) {
        // JavaFX: Menu → Swing: JMenu → Web: <ul role="menu">
        Menu menu = new Menu("Вид");
        controls.tableItem().setAccelerator(new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN));
        controls.chartItem().setAccelerator(new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.SHORTCUT_DOWN));
        // JavaFX: SeparatorMenuItem → Swing: JMenu.addSeparator() → Web: <li role="separator"><hr>
        SeparatorMenuItem beforePeriod = new SeparatorMenuItem();
        menu.getItems().addAll(controls.tableItem(), controls.chartItem(), new SeparatorMenuItem());
        menu.getItems().addAll(controls.flagItems().values());
        menu.getItems().add(beforePeriod);
        // JavaFX: SeparatorMenuItem → Swing: JMenu.addSeparator() → Web: <li role="separator"><hr>
        menu.getItems().addAll(new SeparatorMenuItem(), controls.horizonItem(), new SeparatorMenuItem(),
                item("Перейти к фильтру", this::shortcut, KeyCode.F, shell::focusFilter));
        // Пункты периода — те же RadioMenuItem, что и в кнопке «Период» панели инструментов (общая ToggleGroup).
        controls.periodShared().attach(menu, beforePeriod);
        return menu;
    }

    // ------------------------------------------------------------------ Инструменты

    private Menu toolsMenu(ViewControls controls) {
        // JavaFX: Menu → Swing: JMenu → Web: <ul role="menu">
        Menu menu = new Menu("Инструменты");
        // JavaFX: Menu (вложенное) → Swing: JMenu внутри JMenu → Web: вложенный <ul role="menu">
        Menu whatIf = new Menu("Что-если");
        controls.whatIfShared().attach(whatIf, null);
        menu.getItems().addAll(
                item("Калькулятор цели…", this::shortcut, KeyCode.G, () -> actions.goalCalculator(OpenRequest.fromMain())),
                whatIf,
                // JavaFX: SeparatorMenuItem → Swing: JMenu.addSeparator() → Web: <li role="separator"><hr>
                new SeparatorMenuItem(),
                item("Проверить план", null, null, actions::validatePlan),
                item("Очистить неиспользуемые корректировки", null, null, actions::removeOrphanAdjustments),
                item("Валюта…", null, null, () -> actions.currency(OpenRequest.fromMain())));
        return menu;
    }

    // ------------------------------------------------------------------ Восстановление

    private Menu recoveryMenu() {
        // JavaFX: Menu → Swing: JMenu → Web: <ul role="menu">
        Menu menu = new Menu("Восстановление");
        storeRegistry.setUserData(RecoveryStoreKind.REGISTRY);
        storeXml.setUserData(RecoveryStoreKind.XML);
        storeRegistry.setToggleGroup(storeGroup);
        storeXml.setToggleGroup(storeGroup);
        storeGroup.selectedToggleProperty().addListener((o, a, b) -> {
            if (syncing) {
                return;
            }
            if (b == null) {
                syncSettings(shell.settings());
                return;
            }
            RecoveryStoreKind kind = (RecoveryStoreKind) b.getUserData();
            shell.updateSettings(s -> s.withRecoveryStore(kind));
        });
        // JavaFX: Menu (вложенное) → Swing: JMenu внутри JMenu → Web: вложенный <ul role="menu">
        Menu simulate = new Menu("Симулировать сбой");
        simulate.getItems().addAll(
                item("Аварийное завершение процесса", null, null, actions::simulateHalt),
                item("Необработанное исключение", null, null, actions::simulateException));
        menu.getItems().addAll(storeRegistry, storeXml,
                // JavaFX: SeparatorMenuItem → Swing: JMenu.addSeparator() → Web: <li role="separator"><hr>
                new SeparatorMenuItem(),
                item("Сделать снимок сейчас", null, null, actions::snapshotNow),
                item("Показать последний снимок…", null, null, actions::showLastSnapshot),
                item("Очистить снимки…", null, null, () -> actions.clearSnapshots(OpenRequest.fromMain())),
                // JavaFX: SeparatorMenuItem → Swing: JMenu.addSeparator() → Web: <li role="separator"><hr>
                new SeparatorMenuItem(),
                simulate);
        return menu;
    }

    // ------------------------------------------------------------------ Справка

    private Menu helpMenu() {
        // JavaFX: Menu → Swing: JMenu → Web: <ul role="menu">
        Menu menu = new Menu("Справка");
        menu.getItems().addAll(
                item("О программе", null, KeyCode.F1, actions::about),
                item("Горячие клавиши", null, null, actions::hotkeys),
                item("Формат файла .md", null, null, actions::formatHelp));
        return menu;
    }

    // ------------------------------------------------------------------ синхронизация

    /**
     * Обновляет пункты «Отменить/Повторить» по истории документа.
     *
     * @param document документ плана
     */
    public void refresh(PlanDocument document) {
        undo.setText(document.undoDescription().map(d -> "Отменить: " + d).orElse("Отменить"));
        redo.setText(document.redoDescription().map(d -> "Повторить: " + d).orElse("Повторить"));
        undo.setDisable(!document.canUndo());
        redo.setDisable(!document.canRedo());
    }

    /**
     * Выставляет «Автосохранение» и хранилище по умолчанию по настройкам, не отправляя изменений обратно.
     *
     * @param settings настройки
     */
    public void syncSettings(AppSettings settings) {
        syncing = true;
        try {
            autosave.setSelected(settings.autosave());
            storeGroup.selectToggle(settings.recoveryStore() == RecoveryStoreKind.XML ? storeXml : storeRegistry);
        } finally {
            syncing = false;
        }
    }

    // ------------------------------------------------------------------ фабрики пунктов

    /** Способ построить сочетание клавиш по коду клавиши. */
    @FunctionalInterface
    private interface Combo {
        KeyCombination of(KeyCode code);
    }

    private KeyCombination shortcut(KeyCode code) {
        return new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN);
    }

    private KeyCombination shortcutShift(KeyCode code) {
        return new KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    }

    private static MenuItem item(String text, Combo combo, KeyCode key, Runnable action) {
        // JavaFX: MenuItem → Swing: JMenuItem + setAccelerator → Web: <li role="menuitem"> + keydown
        MenuItem item = new MenuItem(text);
        if (key != null) {
            item.setAccelerator(combo != null ? combo.of(key) : new KeyCodeCombination(key));
        }
        item.setOnAction(e -> action.run());
        return item;
    }
}
