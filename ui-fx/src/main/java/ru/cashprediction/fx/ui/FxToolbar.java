package ru.cashprediction.fx.ui;

import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import ru.cashprediction.core.app.UiIntents;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.menu.*;
import ru.cashprediction.core.ui.token.DesignTokens;

/** Панель инструментов из общих узлов без собственной доступности команд. */
public final class FxToolbar {
    final Map<String, Node> widgets = new LinkedHashMap<>();
    final HBox root = new HBox(DesignTokens.SPACING);
    private final FxMenus menus;
    private final UiIntents intents;
    private final FxClassUsageProbe probe;

    /** Подключает меню и получателя действий. */
    public FxToolbar(FxMenus menus, UiIntents intents, FxClassUsageProbe probe) {
        this.menus = menus; this.intents = intents; this.probe = probe;
        root.setMinHeight(DesignTokens.TOOLBAR_HEIGHT); root.setPrefHeight(DesignTokens.TOOLBAR_HEIGHT); root.setMaxHeight(DesignTokens.TOOLBAR_HEIGHT);
        root.setAlignment(javafx.geometry.Pos.CENTER_LEFT); root.setPadding(javafx.geometry.Insets.EMPTY); FxStyles.id(root, "toolbar");
        root.getStyleClass().add("cp-toolbar");
    }

    /** Обновляет свойства в порядке модели, сохраняя редактор, его фокус и незавершённый ввод. */
    public void render(ToolbarModel model) {
        Map<String, Node> previousWidgets = new LinkedHashMap<>(widgets);
        var ordered = new ArrayList<Node>();
        widgets.clear();
        for (var item : model.items()) {
            Node previous = previousWidgets.remove(item.id());
            ToolbarNode previousModel = previous == null ? null : (ToolbarNode) previous.getProperties().get("cp.toolbarNode");
            if (Objects.equals(item, previousModel)) {
                widgets.put(item.id(), previous); ordered.add(previous); continue;
            }
            Node node;
            String tip = "";
            switch (item) {
                case ToolbarNode.SplitButton n -> {
                    // JavaFX: SplitMenuButton → Swing: JPanel с основной JButton и JButton-стрелкой с JPopupMenu → Web: div.split-button
                    SplitMenuButton button = previous instanceof SplitMenuButton b ? b : probe.created(new SplitMenuButton()); button.setText(n.text());
                    button.getItems().clear();
                    n.items().forEach(i -> button.getItems().add(menus.item(i, InvokeSource.TOOLBAR)));
                    button.setOnAction(e -> intents.command(n.main().command(), n.main().args(), InvokeSource.TOOLBAR));
                    button.setDisable(!n.main().enabled()); node = button; tip = n.tooltip();
                }
                case ToolbarNode.MenuButton n -> {
                    // JavaFX: MenuButton → Swing: JButton + JPopupMenu → Web: button + div[role=menu]
                    MenuButton button = previous instanceof MenuButton b && !(b instanceof SplitMenuButton) ? b : probe.created(new MenuButton());
                    button.setText(n.text()); button.getItems().clear();
                    n.items().forEach(i -> button.getItems().add(menus.item(i, InvokeSource.TOOLBAR)));
                    node = button; tip = n.tooltip();
                }
                case ToolbarNode.Toggle n -> {
                    ToggleButton button = previous instanceof ToggleButton b ? b : new ToggleButton();
                    button.setText(n.text()); button.setSelected(n.selected());
                    button.setOnAction(e -> intents.command(n.command(), CommandArgs.NONE, InvokeSource.TOOLBAR));
                    node = button; tip = n.tooltip();
                }
                case ToolbarNode.Button n -> {
                    Button button = previous instanceof Button b ? b : new Button();
                    button.setText(n.glyphOrText()); button.setDisable(!n.enabled());
                    button.setOnAction(e -> intents.command(n.command(), CommandArgs.NONE, InvokeSource.TOOLBAR));
                    node = button; tip = n.tooltip();
                }
                case ToolbarNode.FilterField n -> {
                    TextField field = previous instanceof TextField f ? f : new TextField(n.text());
                    field.setPromptText(n.prompt()); field.setPrefWidth(n.widthPx());
                    if (!field.getStyleClass().contains("cp-filter")) field.getStyleClass().add("cp-filter");
                    field.setMinWidth(n.widthPx()); field.setMaxWidth(n.widthPx());
                    field.setStyle("-fx-text-fill: " + ru.cashprediction.core.ui.token.ColorToken.TEXT_PRIMARY.hex() + ";");
                    PauseTransition delay = (PauseTransition) field.getProperties().get("cp.filterDelay");
                    if (delay == null) {
                        delay = new PauseTransition();
                        PauseTransition editorDelay = delay;
                        delay.setOnFinished(e -> intents.filterText(field.getText()));
                        field.textProperty().addListener((o, a, b) -> {
                            if (!Boolean.TRUE.equals(field.getProperties().get("cp.filterUpdating"))) editorDelay.playFromStart();
                        });
                        field.getProperties().put("cp.filterDelay", delay);
                    }
                    delay.setDuration(Duration.millis(n.debounceMs()));
                    // Модель другого элемента не должна затереть ввод, ещё ожидающий отправки в ядро.
                    if (previousModel instanceof ToolbarNode.FilterField before && !n.text().equals(before.text())) {
                        delay.stop(); field.getProperties().put("cp.filterUpdating", true);
                        try { if (!n.text().equals(field.getText())) field.setText(n.text()); }
                        finally { field.getProperties().remove("cp.filterUpdating"); }
                    }
                    PauseTransition editorDelay = delay;
                    field.setOnAction(e -> { editorDelay.stop(); intents.filterText(field.getText()); intents.key(new KeyChord(false, false, false, "ENTER"), FocusScope.FILTER, n.id()); });
                    node = field; tip = n.tooltip();
                }
                case ToolbarNode.Separator n -> node = previous instanceof Separator ? previous : new Separator(javafx.geometry.Orientation.VERTICAL);
                case ToolbarNode.Spacer n -> { Region spacer = previous instanceof Region r ? r : new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); node = spacer; }
            }
            FxStyles.id(node, item.id()); node.getProperties().put("cp.kind", item.getClass().getSimpleName());
            if (node instanceof Labeled label) FxStyles.text(label, ru.cashprediction.core.ui.token.ColorToken.TEXT_PRIMARY, ru.cashprediction.core.ui.token.FontToken.BASE);
            if (node instanceof Labeled label) FxIcons.decorate(label);
            if (node instanceof Control control) FxIcons.skin(control);
            Emphasis emphasis = item instanceof ToolbarNode.Button b ? b.emphasis() : item instanceof ToolbarNode.MenuButton b ? b.emphasis() : Emphasis.NONE;
            if (node instanceof Labeled label && emphasis != Emphasis.NONE) {
                var font = ru.cashprediction.core.ui.token.FontToken.BASE;
                FxStyles.text(label, emphasis == Emphasis.ACCENT ? ru.cashprediction.core.ui.token.ColorToken.ACCENT : ru.cashprediction.core.ui.token.ColorToken.WHATIF, font);
                label.setFont(javafx.scene.text.Font.font(font.primaryFamily(), javafx.scene.text.FontWeight.BOLD, font.sizePx()));
            }
            node.getStyleClass().removeAll("cp-accent", "cp-whatif");
            if (emphasis != Emphasis.NONE) node.getStyleClass().add(emphasis == Emphasis.ACCENT ? "cp-accent" : "cp-whatif");
            size(node, item.id());
            node.getProperties().put("cp.tooltip", tip);
            if (node instanceof Control control) control.setTooltip(tip.isEmpty() ? null : FxStyles.tip(tip, probe));
            if (!(node instanceof TextField)) node.setFocusTraversable(false);
            node.getProperties().put("cp.toolbarNode", item);
            widgets.put(item.id(), node); ordered.add(node);
        }
        for (Node removed : previousWidgets.values())
            if (removed.getProperties().get("cp.filterDelay") instanceof PauseTransition delay) delay.stop();
        // Не использовать clear/setAll: даже повторное добавление того же TextField отбирает фокус и caret.
        for (int i = 0; i < ordered.size(); i++) {
            Node node = ordered.get(i);
            if (i == root.getChildren().size()) root.getChildren().add(node);
            else if (root.getChildren().get(i) != node) {
                if (root.getChildren().contains(node)) root.getChildren().remove(node);
                if (i < root.getChildren().size()) root.getChildren().set(i, node); else root.getChildren().add(node);
            }
        }
        if (root.getChildren().size() > ordered.size()) root.getChildren().remove(ordered.size(), root.getChildren().size());
    }

    private static void size(Node node, String id) {
        if (node instanceof Control control && !control.getStyle().contains("-fx-effect: null;"))
            control.setStyle(control.getStyle() + "-fx-effect: null; -fx-background-insets: 0;");
        if (node instanceof Region region) {
            region.setMinHeight(DesignTokens.CONTROL_HEIGHT); region.setPrefHeight(DesignTokens.CONTROL_HEIGHT); region.setMaxHeight(DesignTokens.CONTROL_HEIGHT);
            if (node instanceof Separator) { region.setMinWidth(1); region.setPrefWidth(1); region.setMaxWidth(1); }
        }
        if (node instanceof Labeled label && node instanceof Region region) {
            boolean glyph = id.equals("tb.undo") || id.equals("tb.redo");
            int arrow = node instanceof MenuButton ? DesignTokens.CONTROL_HEIGHT - 2 * DesignTokens.SPACING : 0;
            var text = new javafx.scene.text.Text(label.getText()); text.setFont(label.getFont());
            double width = glyph ? DesignTokens.CONTROL_HEIGHT : Math.ceil(text.getLayoutBounds().getWidth()) + 2 * DesignTokens.TOOLBAR_BUTTON_PAD_H + arrow;
            label.setPadding(new javafx.geometry.Insets(DesignTokens.TOOLBAR_BUTTON_PAD_V, glyph ? 0 : DesignTokens.TOOLBAR_BUTTON_PAD_H, DesignTokens.TOOLBAR_BUTTON_PAD_V, glyph ? 0 : DesignTokens.TOOLBAR_BUTTON_PAD_H));
            region.setMinWidth(width); region.setPrefWidth(width); region.setMaxWidth(width);
        }
    }
}
