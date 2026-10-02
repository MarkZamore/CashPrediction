package ru.cashprediction.fx.ui;

import java.util.*;
import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import ru.cashprediction.core.app.UiIntents;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.menu.*;

/** Универсальное отображение всех видов узлов меню. */
public final class FxMenus {
    final UiIntents intents;
    final FxClassUsageProbe probe;
    final Map<String, MenuItem> byId = new LinkedHashMap<>();
    final Map<String, ToggleGroup> groups = new HashMap<>();
    final List<ContextMenu> contexts = new ArrayList<>();

    /** Создаёт адаптер с общим получателем действий. */
    public FxMenus(UiIntents intents, FxClassUsageProbe probe) { this.intents = intents; this.probe = probe; }

    /** Строит строку меню из готовой модели. */
    public MenuBar bar(MenuBarModel model) {
        byId.clear(); groups.clear();
        // JavaFX: MenuBar → Swing: JMenuBar → Web: nav.menubar
        MenuBar bar = probe.created(new MenuBar());
        bar.setMinHeight(ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT);
        bar.setPrefHeight(ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT);
        bar.setMaxHeight(ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT);
        for (var node : model.menus()) bar.getMenus().add((Menu) item(node, InvokeSource.MENU));
        return FxStyles.id(bar, "menuBar");
    }

    /** Строит настоящее контекстное меню. */
    public ContextMenu context(List<MenuNode> nodes, InvokeSource source) {
        // JavaFX: ContextMenu → Swing: JPopupMenu → Web: div.context-menu
        ContextMenu menu = probe.created(new ContextMenu());
        contexts.add(menu);
        nodes.forEach(n -> menu.getItems().add(item(n, source)));
        menu.setOnShown(e -> menu.getItems().forEach(this::hooks));
        return menu;
    }

    /** Создаёт виджет соответствующего виду узла класса. */
    public MenuItem item(MenuNode node, InvokeSource source) {
        MenuItem item;
        switch (node) {
            case MenuNode.Submenu n -> {
                // JavaFX: Menu → Swing: JMenu → Web: div.submenu
                Menu menu = probe.created(new Menu(n.text()));
                menu.setDisable(!n.enabled());
                n.children().forEach(c -> menu.getItems().add(item(c, source)));
                menu.setOnShown(e -> menu.getItems().forEach(this::hooks));
                item = menu; meta(item, n.tooltip(), null);
            }
            case MenuNode.Separator n -> {
                // JavaFX: SeparatorMenuItem → Swing: JPopupMenu.Separator → Web: hr
                var separatorItem = probe.created(new SeparatorMenuItem());
                // SeparatorMenuItem является CustomMenuItem: размер линии задаём его настоящему содержимому.
                if (separatorItem.getContent() instanceof javafx.scene.layout.Region separator) {
                    double height = 2 * ru.cashprediction.core.ui.token.DesignTokens.SPACING + 1;
                    separator.setPadding(new javafx.geometry.Insets(ru.cashprediction.core.ui.token.DesignTokens.SPACING, 0,
                            ru.cashprediction.core.ui.token.DesignTokens.SPACING, 0));
                    separator.setMinHeight(height); separator.setPrefHeight(height); separator.setMaxHeight(height);
                }
                item = separatorItem;
                item.setDisable(true);
            }
            case MenuNode.Action n -> {
                // JavaFX: MenuItem → Swing: JMenuItem → Web: button.menu-item
                item = probe.created(new MenuItem(n.text()));
                action(item, n.command(), n.args(), source, n.enabled(), n.accel(), n.tooltip());
            }
            case MenuNode.Check n -> {
                // JavaFX: CheckMenuItem → Swing: JCheckBoxMenuItem → Web: button.menu-check
                CheckMenuItem check = probe.created(new CheckMenuItem(n.text())); check.setSelected(n.checked()); item = check;
                action(item, n.command(), n.args(), source, n.enabled(), n.accel(), n.tooltip());
            }
            case MenuNode.Radio n -> {
                // JavaFX: RadioMenuItem → Swing: JRadioButtonMenuItem → Web: button.menu-radio
                RadioMenuItem radio = probe.created(new RadioMenuItem(n.text()));
                // Повторения одного радио в меню и тулбаре независимы: выбор каждой копии приходит из ядра.
                radio.setSelected(n.selected()); item = radio; item.getProperties().put("cp.group", n.group());
                action(item, n.command(), n.args(), source, n.enabled(), n.accel(), n.tooltip());
            }
            case MenuNode.Info n -> {
                // JavaFX: MenuItem → Swing: JMenuItem → Web: button.menu-info
                item = probe.created(new MenuItem(n.text())); item.setDisable(true);
            }
            case MenuNode.Slider n -> {
                Label label = new Label(n.currentLabel());
                Slider slider = new Slider(n.min(), n.max(), n.value());
                slider.setMajorTickUnit(n.majorTick()); slider.setShowTickMarks(true); slider.setPrefWidth(n.widthPx());
                FxStyles.id(slider, n.id());
                slider.valueProperty().addListener((o, a, b) -> {
                    int i = (int) Math.round(b.doubleValue()) - n.min();
                    if (i >= 0 && i < n.labels().size()) label.setText(n.labels().get(i));
                });
                slider.setOnMouseReleased(e -> intents.sliderCommit(n.id(), (int) Math.round(slider.getValue())));
                slider.setOnKeyReleased(e -> intents.sliderCommit(n.id(), (int) Math.round(slider.getValue())));
                // JavaFX: CustomMenuItem → Swing: SwingSliderMenuItem → Web: input.range
                item = probe.created(new CustomMenuItem(new VBox(4, label, slider), false));
                item.getProperties().put("cp.control", slider); item.getProperties().put("cp.label", label);
                meta(item, n.tooltip(), null);
            }
            case MenuNode.Spinner n -> {
                Spinner<Long> spinner = new Spinner<>();
                spinner.setValueFactory(new SpinnerValueFactory<>() {
                    { setValue(n.value()); }
                    /** Изменяет значение стрелкой вниз в пределах модели. */
                    @Override public void decrement(int steps) { setValue(Math.max(n.min(), getValue() - n.step() * steps)); }
                    /** Изменяет значение стрелкой вверх в пределах модели. */
                    @Override public void increment(int steps) { setValue(Math.min(n.max(), getValue() + n.step() * steps)); }
                });
                spinner.setEditable(true); spinner.setPrefWidth(n.fieldWidthPx()); FxStyles.id(spinner, n.id());
                spinner.getValueFactory().setConverter(new javafx.util.converter.LongStringConverter());
                PauseTransition delay = new PauseTransition(Duration.millis(n.applyDelayMs()));
                delay.setOnFinished(e -> intents.spinnerCommit(n.id(), spinner.getValue()));
                spinner.valueProperty().addListener((o, a, b) -> delay.playFromStart());
                spinner.getEditor().setOnAction(e -> { try { spinner.getValueFactory().setValue(Long.parseLong(spinner.getEditor().getText())); } catch (NumberFormatException ignored) { } });
                // JavaFX: CustomMenuItem → Swing: SwingSpinnerMenuItem → Web: input.number
                item = probe.created(new CustomMenuItem(new VBox(4, new Label(n.label()), spinner), false));
                item.getProperties().put("cp.control", spinner); item.getProperties().put("cp.label", n.label());
                meta(item, n.tooltip(), null);
            }
        }
        item.setMnemonicParsing(false); item.setId(node.id());
        item.getProperties().put("cp.id", node.id()); item.getProperties().put("cp.kind", node.getClass().getSimpleName());
        byId.put(node.id(), item); return item;
    }

    private void action(MenuItem item, CommandId command, CommandArgs args, InvokeSource source, boolean enabled, KeyChord accel, String tip) {
        item.setDisable(!enabled); meta(item, tip, accel);
        item.setOnAction(e -> intents.command(command, args, source));
    }

    private void meta(MenuItem item, String tip, KeyChord accel) {
        item.getProperties().put("cp.tooltip", tip);
        if (accel != null) {
            item.getProperties().put("cp.accel", accel.display());
            // JavaFX регистрирует setAccelerator как обработчик: подпись рисуется отдельным узлом,
            // иначе Ctrl+Z в поле ввода обошёл бы текстовую область диспетчера ядра.
            Label shown = new Label(accel.display()); shown.getStyleClass().add("accelerator-text");
            item.setGraphic(shown); item.getProperties().put("cp.acceleratorLabel", shown);
        }
    }

    /** Оформляет реальную строку Skin и подключает подсказку после создания её узла. */
    void hooks(MenuItem item) {
        Node node = item.getStyleableNode();
        if (node == null) return;
        if (node.getScene() != null) FxStyles.root(node.getScene().getRoot());
        if (node instanceof javafx.scene.layout.Region row && (!(item instanceof CustomMenuItem) || item instanceof SeparatorMenuItem)) {
            double height = item instanceof SeparatorMenuItem ? 2 * ru.cashprediction.core.ui.token.DesignTokens.SPACING + 1
                    : ru.cashprediction.core.ui.token.DesignTokens.CONTROL_HEIGHT;
            row.setMinHeight(height); row.setPrefHeight(height); row.setMaxHeight(height);
        }
        if (Boolean.getBoolean("fx.popup.metrics")) {
            javafx.application.Platform.runLater(() -> printMetrics(item, node));
        }
        String tip = (String) item.getProperties().getOrDefault("cp.tooltip", "");
        if (!tip.isEmpty()) FxStyles.menuTip(node, tip, probe);
        node.setOnMouseEntered(e -> intents.menuHover(item.getId()));
        node.setOnMouseExited(e -> intents.menuHover(null));
    }

    private void printMetrics(MenuItem item, Node node) {
            var bounds = node.localToScreen(node.getLayoutBounds());
            System.out.println("MENU_METRICS id=" + item.getId() + " kind=" + item.getProperties().get("cp.kind")
                    + " node=" + node.getClass().getSimpleName() + " row=" + bounds);
            for (Node label : node.lookupAll(".label"))
                System.out.println("MENU_LABEL_METRICS id=" + item.getId() + " label=" + label.localToScreen(label.getLayoutBounds()));
    }
}
