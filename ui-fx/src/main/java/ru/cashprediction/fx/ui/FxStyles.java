package ru.cashprediction.fx.ui;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.Tooltip;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;
import ru.cashprediction.core.ui.token.*;

/** Оформление виджетов исключительно общими токенами. */
public final class FxStyles {
    private FxStyles() { }

    /** Устанавливает базовые цвета и шрифт корня без файлов на диске. */
    public static void root(Node root) {
        root.setStyle(rootCss());
        if (root instanceof Parent parent && !parent.getStylesheets().contains(stylesheet())) parent.getStylesheets().add(stylesheet());
    }

    /** Строит наследуемые цвета настоящего корня, включая фокус стандартных скинов. */
    static String rootCss() {
        StringBuilder css = new StringBuilder("-fx-font-family: 'Segoe UI'; -fx-font-size: 13px;");
        TokenCss.fxLookups().forEach((k, v) -> css.append(k).append(':').append(v).append(';'));
        css.append("-fx-base: #F6F8FA; -fx-background: #FFFFFF; -fx-control-inner-background: #FFFFFF; -fx-accent: #1F6FEB;");
        css.append("-fx-focus-color: ").append(ColorToken.ACCENT.hex()).append(';');
        css.append("-fx-faint-focus-color: ").append(ColorToken.ACCENT_WEAK.hex()).append(';');
        return css.toString();
    }

    /** Возвращает подключаемую таблицу оформления, не создавая виджеты или окна. */
    static String stylesheet() { return SHEET; }

    /** Применяет цвет и размер текста. */
    public static void text(Labeled label, ColorToken color, FontToken font) {
        label.setTextFill(Color.web(color.hex()));
        label.setFont(Font.font(font.primaryFamily(), font.bold() ? FontWeight.BOLD : FontWeight.NORMAL, font.sizePx()));
    }

    /** Измеряемая кнопка общего каркаса: собственная ширина текста, отступы и высота из токенов. */
    static void dialogButton(Button button) {
        text(button, ColorToken.TEXT_PRIMARY, FontToken.BASE);
        button.setPadding(new javafx.geometry.Insets(DesignTokens.TOOLBAR_BUTTON_PAD_V, DesignTokens.TOOLBAR_BUTTON_PAD_H,
                DesignTokens.TOOLBAR_BUTTON_PAD_V, DesignTokens.TOOLBAR_BUTTON_PAD_H));
        button.setMinWidth(DesignTokens.BUTTON_MIN_WIDTH);
        button.setMinHeight(DesignTokens.CONTROL_HEIGHT); button.setPrefHeight(DesignTokens.CONTROL_HEIGHT); button.setMaxHeight(DesignTokens.CONTROL_HEIGHT);
        ButtonBar.setButtonUniformSize(button, false);
    }

    /** Создаёт подсказку с общей задержкой и переносом. */
    public static Tooltip tip(String text, FxClassUsageProbe probe) {
        // JavaFX: Tooltip → Swing: JToolTip → Web: div.tooltip
        Tooltip tip = probe.created(new Tooltip(text));
        tip.getProperties().put("cp.popupKind", "tooltip");
        tip.setWrapText(true); tip.setMaxWidth(420);
        tip.setShowDelay(Duration.millis(600)); tip.setShowDuration(Duration.seconds(20));
        tip.setStyle("-fx-background-color: #262C34; -fx-text-fill: #F5F7FA; -fx-font-size: 12px;");
        return tip;
    }

    /** Подсказка меню следует реальной области пункта, а не положению указателя внутри него. */
    static void menuTip(Node node, String text, FxClassUsageProbe probe) {
        if (node.getProperties().containsKey("cp.menuTip")) return;
        Tooltip tip = tip(text, probe);
        node.getProperties().put("cp.menuTip", tip);
        // JavaFX: PopupWindow.AnchorLocation → Swing: JToolTip content origin → Web: div.tooltip border box
        tip.setAnchorLocation(javafx.stage.PopupWindow.AnchorLocation.CONTENT_TOP_LEFT);
        var show = new javafx.animation.PauseTransition(Duration.millis(DesignTokens.TOOLTIP_DELAY_MS));
        var dismiss = new javafx.animation.PauseTransition(Duration.millis(DesignTokens.TOOLTIP_DISMISS_MS));
        Runnable hide = () -> { show.stop(); dismiss.stop(); tip.hide(); };
        show.setOnFinished(e -> {
            var box = node.localToScreen(node.getLayoutBounds());
            if (box == null || node.getScene() == null || !node.getScene().getWindow().isShowing()) return;
            tip.show(node, box.getMinX() + DesignTokens.SPACING, box.getMaxY() + DesignTokens.SPACING);
            if (Boolean.getBoolean("fx.popup.metrics")) {
                System.out.println("TOOLTIP_ANCHOR row=" + box + " popup=" + tip.getX() + "," + tip.getY());
                for (Node label : node.lookupAll(".label")) if (label instanceof Labeled value)
                    System.out.println("TOOLTIP_ANCHOR_LABEL box=" + label.localToScreen(label.getLayoutBounds()) + " font=" + value.getFont());
            }
            dismiss.playFromStart();
        });
        dismiss.setOnFinished(e -> tip.hide());
        node.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_ENTERED, e -> { hide.run(); show.playFromStart(); });
        node.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_EXITED, e -> hide.run());
        node.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> hide.run());
        if (node.getScene() != null && node.getScene().getWindow() != null)
            node.getScene().getWindow().showingProperty().addListener((o, oldValue, showing) -> { if (!showing) hide.run(); });
    }

    /** Присваивает стабильный идентификатор модели. */
    public static <T extends Node> T id(T node, String id) {
        node.getProperties().put("cp.id", id); return node;
    }

    private static final int TOOLBAR_ARROW = DesignTokens.CONTROL_HEIGHT - 2 * DesignTokens.SPACING;
    private static final String SHEET = "data:text/css;base64," + java.util.Base64.getEncoder().encodeToString((
            ".root { -fx-font-family: 'Segoe UI'; -fx-font-size: 13px; -fx-text-base-color: " + ColorToken.TEXT_PRIMARY.hex() + "; }"
            // Два настоящих фоновых слоя рисуют рамку 1 px без изменения Insets и размеров редактора.
            + ".text-input, .combo-box-base, .spinner { -fx-background-color: " + ColorToken.BORDER_STRONG.hex() + ", " + ColorToken.BG_SURFACE.hex() + "; -fx-background-insets: 0, 1; }"
            + ".text-input:focused, .combo-box-base:focused, .combo-box-base:contains-focus, .spinner:focused, .spinner:contains-focus { -fx-background-color: " + ColorToken.ACCENT.hex() + ", " + ColorToken.BG_SURFACE.hex() + "; -fx-background-insets: 0, 1; }"
            + ".text-area .content, .text-area:focused .content { -fx-background-color: " + ColorToken.BG_SURFACE.hex() + "; -fx-background-insets: 0; }"
            // У составного поля рамка принадлежит внешнему контролу, не дублируется вокруг его редактора.
            + ".combo-box-base:editable > .text-field, .spinner > .text-field { -fx-background-color: " + ColorToken.BG_SURFACE.hex() + "; -fx-background-insets: 1; }"
            + ".combo-box-base > .arrow-button, .spinner > .increment-arrow-button, .spinner > .decrement-arrow-button { -fx-background-color: transparent; }"
            // Выделение относится к настоящему defaultButton, а не к тексту или назначению Save.
            + ".button:default { -fx-background-color: " + ColorToken.ACCENT.hex() + ", " + ColorToken.BG_SURFACE.hex() + "; -fx-background-insets: 0, 1; -fx-text-fill: " + ColorToken.ACCENT.hex() + "; }"
            + ".button:default:hover, .button:default:armed { -fx-background-color: " + ColorToken.ACCENT.hex() + ", " + ColorToken.ACCENT_WEAK.hex() + "; }"
            + ".menu-bar { -fx-padding: 0; }"
            + ".context-menu { -fx-padding: 4px; -fx-background-color: " + ColorToken.BG_SURFACE.hex() + "; -fx-border-color: " + ColorToken.BORDER_STRONG.hex() + "; -fx-border-width: 1px; }"
            + ".context-menu .menu-item { -fx-padding: 4px 9px; }"
            + ".context-menu .separator-menu-item { -fx-padding: 4px; }"
            + ".table-view .column-header, .table-view .filler { -fx-background-color: " + ColorToken.BG_ALT.hex() + "; -fx-size: 28px; }"
            + ".table-row-cell { -fx-background-color: " + ColorToken.BG_SURFACE.hex() + "; }"
            + ".table-cell { -fx-border-color: transparent; }"
            + ".cp-toolbar, .cp-summary, .cp-status { -fx-background-color: " + ColorToken.BG_WINDOW.hex() + "; }"
            + ".cp-toolbar .menu-button, .cp-toolbar .split-menu-button { -fx-padding: 0; }"
            + ".cp-toolbar .menu-button > .label, .cp-toolbar .split-menu-button > .label { -fx-padding: " + DesignTokens.TOOLBAR_BUTTON_PAD_V + "px " + DesignTokens.TOOLBAR_BUTTON_PAD_H + "px; }"
            + ".cp-toolbar .menu-button > .arrow-button, .cp-toolbar .split-menu-button > .arrow-button { -fx-padding: 0; -fx-min-width: " + TOOLBAR_ARROW + "px; -fx-pref-width: " + TOOLBAR_ARROW + "px; -fx-max-width: " + TOOLBAR_ARROW + "px; }"
            + ".cp-accent { -fx-text-fill: " + ColorToken.ACCENT.hex() + "; -fx-font-weight: bold; }"
            + ".cp-form .text-area, .cp-form .text-area .content { -fx-padding: 0; }"
            + ".cp-popover, .cp-quick-edit { -fx-background-color: " + ColorToken.BG_SURFACE.hex() + "; -fx-background-radius: " + DesignTokens.QUICK_EDIT_RADIUS
            + "px; -fx-border-color: " + ColorToken.BORDER_STRONG.hex() + "; -fx-border-width: 1px; -fx-border-radius: " + DesignTokens.QUICK_EDIT_RADIUS
            + "px; -fx-effect: dropshadow(gaussian, rgba(0,0,0," + ColorToken.SHADOW.opacity() + "), " + DesignTokens.SHADOW_BLUR
            + ", 0, 0, " + DesignTokens.SHADOW_OFFSET_Y + "); }"
            + ".cp-whatif { -fx-text-fill: " + ColorToken.WHATIF.hex() + "; -fx-font-weight: bold; }")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
}
