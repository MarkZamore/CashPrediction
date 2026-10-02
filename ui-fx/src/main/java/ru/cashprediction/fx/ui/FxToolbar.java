package ru.cashprediction.fx.ui;

import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** Обновляет набор элементов в порядке модели. */
    public void render(ToolbarModel model) {
        widgets.clear(); root.getChildren().clear();
        for (var item : model.items()) {
            Node node;
            String tip = "";
            switch (item) {
                case ToolbarNode.SplitButton n -> {
                    // JavaFX: SplitMenuButton → Swing: SwingSplitMenuButton → Web: div.split-button
                    SplitMenuButton button = probe.created(new SplitMenuButton()); button.setText(n.text());
                    n.items().forEach(i -> button.getItems().add(menus.item(i, InvokeSource.TOOLBAR)));
                    button.setOnAction(e -> intents.command(n.main().command(), n.main().args(), InvokeSource.TOOLBAR));
                    button.setDisable(!n.main().enabled()); node = button; tip = n.tooltip();
                }
                case ToolbarNode.MenuButton n -> {
                    // JavaFX: MenuButton → Swing: SwingMenuButton → Web: button.menu-button
                    MenuButton button = probe.created(new MenuButton(n.text()));
                    n.items().forEach(i -> button.getItems().add(menus.item(i, InvokeSource.TOOLBAR)));
                    node = button; tip = n.tooltip();
                }
                case ToolbarNode.Toggle n -> {
                    ToggleButton button = new ToggleButton(n.text()); button.setSelected(n.selected());
                    button.setOnAction(e -> intents.command(n.command(), CommandArgs.NONE, InvokeSource.TOOLBAR));
                    node = button; tip = n.tooltip();
                }
                case ToolbarNode.Button n -> {
                    Button button = new Button(n.glyphOrText()); button.setDisable(!n.enabled());
                    button.setOnAction(e -> intents.command(n.command(), CommandArgs.NONE, InvokeSource.TOOLBAR));
                    node = button; tip = n.tooltip();
                }
                case ToolbarNode.FilterField n -> {
                    TextField field = new TextField(n.text()); field.setPromptText(n.prompt()); field.setPrefWidth(n.widthPx());
                    field.setStyle("-fx-text-fill: " + ru.cashprediction.core.ui.token.ColorToken.TEXT_PRIMARY.hex() + ";");
                    PauseTransition delay = new PauseTransition(Duration.millis(n.debounceMs()));
                    delay.setOnFinished(e -> intents.filterText(field.getText()));
                    field.textProperty().addListener((o, a, b) -> delay.playFromStart());
                    field.setOnAction(e -> { delay.stop(); intents.filterText(field.getText()); intents.key(new KeyChord(false, false, false, "ENTER"), FocusScope.FILTER, n.id()); });
                    node = field; tip = n.tooltip();
                }
                case ToolbarNode.Separator n -> node = new Separator(javafx.geometry.Orientation.VERTICAL);
                case ToolbarNode.Spacer n -> { Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); node = spacer; }
            }
            FxStyles.id(node, item.id()); node.getProperties().put("cp.kind", item.getClass().getSimpleName());
            if (node instanceof Labeled label) FxStyles.text(label, ru.cashprediction.core.ui.token.ColorToken.TEXT_PRIMARY, ru.cashprediction.core.ui.token.FontToken.BASE);
            Emphasis emphasis = item instanceof ToolbarNode.Button b ? b.emphasis() : item instanceof ToolbarNode.MenuButton b ? b.emphasis() : Emphasis.NONE;
            if (node instanceof Labeled label && emphasis != Emphasis.NONE) {
                var font = ru.cashprediction.core.ui.token.FontToken.BASE;
                FxStyles.text(label, emphasis == Emphasis.ACCENT ? ru.cashprediction.core.ui.token.ColorToken.ACCENT : ru.cashprediction.core.ui.token.ColorToken.WHATIF, font);
                label.setFont(javafx.scene.text.Font.font(font.primaryFamily(), javafx.scene.text.FontWeight.BOLD, font.sizePx()));
            }
            if (emphasis != Emphasis.NONE) node.getStyleClass().add(emphasis == Emphasis.ACCENT ? "cp-accent" : "cp-whatif");
            size(node, item.id());
            node.getProperties().put("cp.tooltip", tip);
            if (node instanceof Control control && !tip.isEmpty()) control.setTooltip(FxStyles.tip(tip, probe));
            if (!(node instanceof TextField)) node.setFocusTraversable(false);
            widgets.put(item.id(), node); root.getChildren().add(node);
        }
    }

    private static void size(Node node, String id) {
        if (node instanceof Control control) control.setStyle(control.getStyle() + "-fx-effect: null; -fx-background-insets: 0;");
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
