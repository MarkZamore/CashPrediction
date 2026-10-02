package ru.cashprediction.fx.ui;

import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/** Панель формы с явной распоркой: стандартный ButtonBar добавляет её перед всеми кнопками даже при BUTTON_ORDER_NONE. */
// JavaFX: DialogPane → Swing: SwingDialogPane → Web: div.dialog-pane
final class FxFormPane extends DialogPane {
    /** Отделяет общий внешний отступ от внутренних платформенных отступов DialogPane. */
    FxFormPane() {
        setPadding(new javafx.geometry.Insets(4 * ru.cashprediction.core.ui.token.DesignTokens.SPACING));
        getStyleClass().add("cp-form");
    }
    /** Сохраняет настоящие кнопки DialogPane, их Esc и Enter, задавая порядок и прижатие по роли. */
    @Override protected Node createButtonBar() {
        HBox row = new HBox(8);
        row.setPadding(new javafx.geometry.Insets(ru.cashprediction.core.ui.token.DesignTokens.FORM_VGAP, 0, 0, 0));
        row.getStyleClass().add("button-bar"); row.setMaxWidth(Double.MAX_VALUE);
        getButtonTypes().addListener((javafx.collections.ListChangeListener<javafx.scene.control.ButtonType>) change -> arrange(row));
        arrange(row);
        return row;
    }

    private void arrange(HBox row) {
        row.getChildren().clear();
        for (var type : getButtonTypes()) if (type.getButtonData() == ButtonBar.ButtonData.LEFT) row.getChildren().add(lookupButton(type));
        Region spacer = new Region(); spacer.setMinWidth(0); HBox.setHgrow(spacer, Priority.ALWAYS); row.getChildren().add(spacer);
        for (var type : getButtonTypes()) if (type.getButtonData() != ButtonBar.ButtonData.LEFT) row.getChildren().add(lookupButton(type));
    }
}
