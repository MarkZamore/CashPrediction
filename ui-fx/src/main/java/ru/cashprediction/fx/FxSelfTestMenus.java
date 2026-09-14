package ru.cashprediction.fx;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Labeled;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitMenuButton;
import javafx.scene.control.TableCell;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import ru.cashprediction.core.forecast.Origin;
import ru.cashprediction.fx.dialog.AdjustmentDialog;
import ru.cashprediction.fx.dialog.AppButtonTypes;
import ru.cashprediction.fx.dialog.AppDialogPane;
import ru.cashprediction.fx.dialog.CsvExportDialog;
import ru.cashprediction.fx.dialog.Dialogs;
import ru.cashprediction.fx.dialog.FxRecoveryDialog;
import ru.cashprediction.fx.dialog.GoalCalculatorDialog;
import ru.cashprediction.fx.dialog.NewPlanWizard;
import ru.cashprediction.fx.dialog.OneTimeDialog;
import ru.cashprediction.fx.dialog.PlanSettingsDialog;
import ru.cashprediction.fx.dialog.RuleDialog;
import ru.cashprediction.fx.dialog.StatefulAlert;
import ru.cashprediction.fx.dialog.StatefulChoiceDialog;
import ru.cashprediction.fx.dialog.StatefulTextInputDialog;
import ru.cashprediction.fx.menu.ForecastContextMenu;
import ru.cashprediction.fx.popup.DayCardPopupWindow;
import ru.cashprediction.fx.popup.QuickEditPopup;
import ru.cashprediction.fx.popup.SparklinePopupControl;
import ru.cashprediction.fx.view.TableEntry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Отчёт самотеста о меню и об использовании 23 классов JavaFX из раздела 7 плана (команда {@code menus}).
 *
 * <p>Снимок экрана с открытым меню без нажатия клавиш сделать нельзя, поэтому проверка идёт по модели:
 * отчёт обходит строку меню, панель инструментов, контекстные меню строки таблицы, графика и карточки сводки
 * и печатает каждый пункт с его классом JavaFX, сочетанием клавиш, состоянием флажка или переключателя и группой.
 * Обычный пункт без обработчика действия считается «не подключённым» — это ошибка самотеста.
 * Классы окон (диалоги, всплывающие окна) проверяются по цепочке наследования, а {@code FileChooser} и
 * {@code DirectoryChooser} — по фабрикам {@link Dialogs}: нативные окна выбора файла нельзя открыть без
 * человека.</p>
 *
 * <p>Перед обходом меню отчёт посылает ему событие {@code ON_SHOWING} — ровно то, что делает JavaFX при
 * открытии меню: так перестраивается список «Недавние», а общие пункты периода и «что-если» переносятся в
 * обходимое меню. Повторно встреченный пункт помечается «тот же объект»: это доказывает, что меню «Вид» и
 * кнопка «Период» используют одни и те же {@code RadioMenuItem}.</p>
 *
 * <p>Служебный класс для разработчиков; только FX Application Thread.</p>
 */
final class FxSelfTestMenus {

    /** 23 класса JavaFX из таблицы соответствия (раздел 7 плана) в порядке таблицы. */
    static final List<String> REQUIRED = List.of("MenuBar", "Menu", "MenuItem", "CheckMenuItem", "RadioMenuItem",
            "SeparatorMenuItem", "CustomMenuItem", "MenuButton", "SplitMenuButton", "PopupWindow", "Popup",
            "PopupControl", "Tooltip", "ContextMenu", "ContextMenuEvent", "Dialog", "DialogPane", "ButtonType", "Alert",
            "TextInputDialog", "ChoiceDialog", "DirectoryChooser", "FileChooser");

    /**
     * Итог отчёта.
     *
     * @param text     текст отчёта
     * @param present  сколько из 23 классов найдено
     * @param missing  ненайденные классы
     * @param unwired  пункты меню без обработчика действия
     */
    record Report(String text, int present, List<String> missing, List<String> unwired) {
    }

    private final AppController app;
    private final StringBuilder out = new StringBuilder();
    private final Map<String, List<String>> found = new LinkedHashMap<>();
    private final Map<ToggleGroup, Integer> groups = new IdentityHashMap<>();
    private final Map<MenuItem, String> seen = new IdentityHashMap<>();
    private final List<String> unwired = new ArrayList<>();

    private FxSelfTestMenus(AppController app) {
        this.app = app;
    }

    /**
     * Строит отчёт по главному окну приложения.
     *
     * @param app контроллер приложения
     * @return отчёт
     */
    static Report build(AppController app) {
        FxSelfTestMenus report = new FxSelfTestMenus(app);
        report.menuBar();
        report.toolBar();
        report.contextMenus();
        report.tooltips();
        report.windowClasses();
        return report.finish();
    }

    // ------------------------------------------------------------------ строка меню

    private void menuBar() {
        section("Строка меню");
        var bar = app.window().menuBar();
        line(0, describeClass(bar) + " - меню: " + bar.getMenus().size());
        mark(bar, "строка меню главного окна");
        for (Menu menu : bar.getMenus()) {
            menuItem(menu, 1, "строка меню");
        }
    }

    private void menuItem(MenuItem item, int depth, String where) {
        StringBuilder text = new StringBuilder(describeClass(item)).append(" «").append(item.getText() == null ? "" : item.getText()).append('»');
        if (item.getAccelerator() != null) {
            text.append("  клавиши=").append(item.getAccelerator().getDisplayText());
        }
        if (item.isDisable()) {
            text.append("  недоступен");
        }
        String previous = seen.putIfAbsent(item, where);
        if (previous != null) {
            text.append("  [тот же объект, что в: ").append(previous).append(']');
        }
        mark(item, where);
        switch (item) {
            case Menu menu -> {
                // Как при настоящем открытии: перестроить «Недавние», перенести общие пункты.
                Event.fireEvent(menu, new Event(Menu.ON_SHOWING));
                text.append("  пунктов=").append(menu.getItems().size());
                line(depth, text.toString());
                for (MenuItem child : List.copyOf(menu.getItems())) {
                    menuItem(child, depth + 1, where + " → «" + menu.getText() + "»");
                }
                return;
            }
            case SeparatorMenuItem _ -> {
                // Разделитель — тоже CustomMenuItem, но действия у него нет по смыслу.
            }
            case CustomMenuItem custom -> {
                text.append("  содержимое=").append(nodeSummary(custom.getContent()))
                        .append("  hideOnClick=").append(custom.isHideOnClick());
            }
            case RadioMenuItem radio -> {
                text.append("  выбран=").append(radio.isSelected()).append("  группа=").append(group(radio.getToggleGroup()));
                if (radio.getToggleGroup() == null) {
                    unwired.add(where + ": переключатель «" + radio.getText() + "» без ToggleGroup");
                }
            }
            case CheckMenuItem check -> text.append("  отмечен=").append(check.isSelected());
            default -> {
                if (item.getOnAction() == null && !item.isDisable()) {
                    text.append("  НЕТ ОБРАБОТЧИКА");
                    unwired.add(where + ": «" + item.getText() + "»");
                }
            }
        }
        line(depth, text.toString());
    }

    // ------------------------------------------------------------------ панель инструментов

    private void toolBar() {
        section("Панель инструментов");
        ToolBar toolBar = app.window().toolBar();
        line(0, describeClass(toolBar) + " - элементов: " + toolBar.getItems().size());
        for (Node node : toolBar.getItems()) {
            StringBuilder text = new StringBuilder(describeClass(node));
            if (node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isEmpty()) {
                text.append(" «").append(labeled.getText()).append('»');
            }
            if (node instanceof TextInputControl input) {
                text.append(" подсказка-в-поле=«").append(input.getPromptText()).append('»');
            }
            if (node instanceof Control control && control.getTooltip() != null) {
                text.append("  Tooltip=«").append(control.getTooltip().getText()).append('»');
                mark(control.getTooltip(), "панель инструментов: " + describeClass(node));
            }
            if (node instanceof SplitMenuButton split) {
                text.append(split.getOnAction() == null ? "  основная часть: НЕТ ОБРАБОТЧИКА" : "  основная часть: действие есть");
                if (split.getOnAction() == null) {
                    unwired.add("панель инструментов: основная часть «" + split.getText() + "»");
                }
            }
            mark(node, "панель инструментов");
            line(1, text.toString());
            if (node instanceof MenuButton button) {
                Event.fireEvent(button, new Event(MenuButton.ON_SHOWING));
                for (MenuItem item : List.copyOf(button.getItems())) {
                    menuItem(item, 2, "кнопка «" + button.getText() + "»");
                }
            }
        }
    }

    // ------------------------------------------------------------------ контекстные меню

    private void contextMenus() {
        section("Контекстные меню (ContextMenuEvent → ContextMenu)");
        var table = app.window().table();
        var chartNode = app.window().chart().chartNode();
        long cards = app.window().summary().getChildrenUnmodifiable().stream()
                .filter(n -> n.getOnContextMenuRequested() != null).count();
        line(0, "обработчик ContextMenuEvent: таблица=" + (table.getOnContextMenuRequested() != null)
                + ", график=" + (chartNode.getOnContextMenuRequested() != null) + ", карточек сводки с обработчиком=" + cards);
        if (table.getOnContextMenuRequested() != null || chartNode.getOnContextMenuRequested() != null || cards > 0) {
            found.computeIfAbsent("ContextMenuEvent", k -> new ArrayList<>()).add("таблица, график, карточки сводки");
        } else {
            unwired.add("нет ни одного обработчика ContextMenuEvent");
        }
        Optional<TableEntry> ruleRow = table.getItems().stream()
                .filter(e -> !e.isTotal() && e.row().origin() == Origin.RULE).findFirst();
        if (ruleRow.isPresent()) {
            contextMenu(ForecastContextMenu.forRow(app, ruleRow.get(), () -> { }), "строка таблицы " + ruleRow.get().id());
        } else {
            line(0, "(в таблице нет строки правила - меню строки не построено)");
        }
        contextMenu(ForecastContextMenu.forChart(app, app.today()), "график");
        contextMenu(ForecastContextMenu.forSummaryCard(app, app.today(), false), "карточка сводки");
    }

    private void contextMenu(ContextMenu menu, String where) {
        line(0, describeClass(menu) + " для: " + where + " - пунктов: " + menu.getItems().size());
        mark(menu, where);
        for (MenuItem item : menu.getItems()) {
            menuItem(item, 1, "контекстное меню: " + where);
        }
    }

    // ------------------------------------------------------------------ подсказки

    private void tooltips() {
        section("Tooltip в главном окне");
        Scene scene = app.window().stage().getScene();
        List<String> texts = new ArrayList<>();
        int cells = collectTooltips(scene.getRoot(), texts);
        line(0, "элементов управления с Tooltip: " + texts.size() + "; ячеек таблицы с Tooltip (создаются при показе строк): " + cells);
        for (String text : texts) {
            line(1, text);
        }
        if (!texts.isEmpty()) {
            found.computeIfAbsent("Tooltip", k -> new ArrayList<>()).add("элементы главного окна: " + texts.size());
        }
    }

    /** Собирает подсказки элементов управления; подсказки ячеек таблицы только считает (их сотни). */
    private int collectTooltips(Node node, List<String> texts) {
        int cells = 0;
        if (node instanceof Control control && control.getTooltip() != null) {
            if (node instanceof TableCell<?, ?>) {
                cells++;
            } else {
                texts.add(describeClass(node) + ": «" + control.getTooltip().getText().replace('\n', ' ') + "»");
            }
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                cells += collectTooltips(child, texts);
            }
        }
        return cells;
    }

    // ------------------------------------------------------------------ классы окон

    private void windowClasses() {
        section("Окна: цепочки наследования");
        List<Class<?>> classes = List.of(NewPlanWizard.class, PlanSettingsDialog.class, RuleDialog.class, OneTimeDialog.class,
                AdjustmentDialog.class, GoalCalculatorDialog.class, CsvExportDialog.class, FxRecoveryDialog.class,
                StatefulAlert.class, StatefulTextInputDialog.class, StatefulChoiceDialog.class, AppDialogPane.class,
                QuickEditPopup.class, SparklinePopupControl.class, DayCardPopupWindow.class);
        for (Class<?> type : classes) {
            line(0, chain(type));
            firstJavaFx(type).ifPresent(fx -> found.computeIfAbsent(fx.getSimpleName(), k -> new ArrayList<>()).add(type.getSimpleName()));
        }

        section("ButtonType (AppButtonTypes)");
        int buttons = 0;
        for (Field field : AppButtonTypes.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ButtonType.class) {
                try {
                    ButtonType type = (ButtonType) field.get(null);
                    line(0, field.getName() + " = «" + type.getText() + "» " + type.getButtonData());
                    buttons++;
                } catch (IllegalAccessException e) {
                    line(0, field.getName() + ": недоступно");
                }
            }
        }
        if (buttons > 0) {
            found.computeIfAbsent("ButtonType", k -> new ArrayList<>()).add("AppButtonTypes: " + buttons);
        }

        section("FileChooser / DirectoryChooser (фабрики Dialogs; нативные окна не восстанавливаются)");
        for (Method method : Dialogs.class.getMethods()) {
            Class<?> result = method.getReturnType();
            if (result == FileChooser.class || result == DirectoryChooser.class) {
                line(0, "Dialogs." + method.getName() + "(…) → " + result.getSimpleName());
                found.computeIfAbsent(result.getSimpleName(), k -> new ArrayList<>()).add("Dialogs." + method.getName());
            }
        }
    }

    // ------------------------------------------------------------------ итог

    private Report finish() {
        section("Итог: 23 класса JavaFX");
        List<String> missing = new ArrayList<>();
        int present = 0;
        for (String name : REQUIRED) {
            List<String> where = found.getOrDefault(name, List.of());
            if (where.isEmpty()) {
                missing.add(name);
                line(0, "НЕТ      " + name);
            } else {
                present++;
                List<String> distinct = where.stream().distinct().toList();
                line(0, "есть     " + name + "  (" + distinct.size() + " мест(а), например: "
                        + String.join("; ", distinct.subList(0, Math.min(3, distinct.size()))) + ")");
            }
        }
        line(0, "найдено " + present + " из " + REQUIRED.size() + "; пунктов без обработчика: " + unwired.size());
        for (String problem : unwired) {
            line(1, "не подключено: " + problem);
        }
        return new Report(out.toString(), present, List.copyOf(missing), List.copyOf(unwired));
    }

    // ------------------------------------------------------------------ служебное

    private void mark(Object object, String where) {
        firstJavaFx(object.getClass()).ifPresent(fx -> found.computeIfAbsent(fx.getSimpleName(), k -> new ArrayList<>()).add(where));
    }

    /** Первый класс JavaFX в цепочке наследования: для {@code AddSplitMenuButton} — {@code SplitMenuButton}. */
    private static Optional<Class<?>> firstJavaFx(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().startsWith("javafx.")) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    private static String describeClass(Object object) {
        Class<?> own = object.getClass();
        Class<?> fx = firstJavaFx(own).orElse(own);
        String ownName = own.getSimpleName().isEmpty() ? own.getName() : own.getSimpleName();
        return fx == own ? fx.getSimpleName() : fx.getSimpleName() + " (" + ownName + ")";
    }

    private static String chain(Class<?> type) {
        StringBuilder text = new StringBuilder(type.getSimpleName());
        for (Class<?> c = type.getSuperclass(); c != null && c != Object.class; c = c.getSuperclass()) {
            text.append(" → ").append(c.getSimpleName());
            if (c.getName().startsWith("javafx.")) {
                break;
            }
        }
        return text.toString();
    }

    private static String nodeSummary(Node node) {
        if (node == null) {
            return "нет";
        }
        List<String> parts = new ArrayList<>();
        parts.add(describeClass(node));
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                parts.add(describeClass(child) + (child instanceof Labeled l ? " «" + l.getText() + "»" : ""));
            }
        }
        return String.join(" + ", parts);
    }

    private String group(ToggleGroup group) {
        if (group == null) {
            return "нет";
        }
        return "#" + groups.computeIfAbsent(group, g -> groups.size() + 1);
    }

    private void section(String title) {
        out.append(System.lineSeparator()).append("== ").append(title).append(System.lineSeparator());
    }

    private void line(int depth, String text) {
        out.append("  ".repeat(depth)).append(text).append(System.lineSeparator());
    }
}
