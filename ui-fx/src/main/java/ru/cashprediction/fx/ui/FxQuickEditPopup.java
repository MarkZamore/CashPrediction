package ru.cashprediction.fx.ui;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.input.*;
import javafx.scene.layout.VBox;
import ru.cashprediction.core.ui.token.*;
import javafx.stage.Popup;
import java.util.*;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.form.*;

/** Настоящая всплывающая форма быстрой правки с сохранением сырого ввода. */
public final class FxQuickEditPopup implements WindowHandle {
    final Popup popup;
    final Map<String, FxFieldWidgets> fields = new LinkedHashMap<>();
    final Label header = new Label(), problem = new Label();
    final FormSpec spec;
    final List<Label> hints = new ArrayList<>();
    final VBox content = new VBox(6);
    private final FormSession session;
    private boolean closing;

    /** Подключает поля модели к настоящему Popup. */
    public FxQuickEditPopup(FormSession session, FormSpec spec, FormView view, Placement placement, FxUiPort port) {
        this.session = session; this.spec = spec;
        // JavaFX: Popup → Swing: PopupFactory → Web: div.quick-edit
        popup = port.probe.created(new Popup()); popup.setAutoHide(true); popup.setHideOnEscape(true);
        // JavaFX: PopupWindow.AnchorLocation → Swing: PopupFactory content origin → Web: div.quick-edit border box
        // Для нового окна якорь обозначает содержимое, а не внешний прямоугольник с тенью.
        // Восстановленные RAW-координаты продолжают обозначать внешнее окно.
        if (placement.bounds() == null) popup.setAnchorLocation(javafx.stage.PopupWindow.AnchorLocation.CONTENT_TOP_LEFT);
        content.setPadding(new javafx.geometry.Insets(10)); FxStyles.root(content); content.getChildren().add(header);
        content.getStyleClass().add("cp-quick-edit");
        // Ширина содержимого POPUP задаётся FormSpec отдельно от пиксельной ширины его редакторов.
        content.setMinWidth(0); content.setPrefWidth(spec.width()); content.setMaxWidth(spec.width());
        header.setWrapText(true); header.setMinWidth(0); header.setMaxWidth(Double.MAX_VALUE);
        problem.setWrapText(true); problem.setMinWidth(0); problem.setMaxWidth(Double.MAX_VALUE);
        for (var page : spec.pages()) for (var row : page.rows()) {
            if (row instanceof FormRow.Field f) {
                var field = new FxFieldWidgets(f.field(), session, port); fields.put(f.field().id(), field);
                content.getChildren().add(field.root);
            }
            if (row instanceof FormRow.Hint h) {
                Label hint = new Label(h.text()); hint.setWrapText(true); hint.setMinWidth(0);
                FxStyles.text(hint, ColorToken.TEXT_MUTED, FontToken.SMALL); hints.add(hint);
            }
        }
        // Общий каркас POPUP: проблема непосредственно после полей, затем подсказки FormRow.Hint из ядра.
        content.getChildren().add(problem); content.getChildren().addAll(hints); popup.getContent().add(content);
        content.addEventFilter(KeyEvent.KEY_PRESSED, e -> { if (e.getCode() == KeyCode.ESCAPE) { session.closeRequested(); e.consume(); } });
        popup.setOnAutoHide(e -> { if (!closing) session.closeRequested(); }); popup.setOnShown(e -> session.shown());
        update(view);
        Platform.runLater(() -> {
            if (closing || session.isClosed()) return;
            var owner = port.owner(placement.ownerId());
            var ownerContent = owner == port.stage && port.main != null ? port.main.root : owner.getScene().getRoot();
            var ownerBox = ownerContent.localToScreen(ownerContent.getLayoutBounds());
            content.setMaxWidth(Math.min(spec.width(), ownerBox.getWidth() - 2 * content.getPadding().getLeft()));
            double x = ownerBox.getMinX() + ownerBox.getWidth() / 2 - 150, y = ownerBox.getMinY() + ownerBox.getHeight() / 2 - 50;
            if (placement.anchor() != null && port.main != null) {
                for (var node : port.main.table.root.lookupAll(".table-cell")) {
                    if (!placement.anchor().rowId().equals(node.getProperties().get("cp.row"))
                            || !placement.anchor().columnId().equals(node.getProperties().get("cp.column"))) continue;
                    var cell = node.localToScreen(node.getLayoutBounds());
                    var table = port.main.table.root.localToScreen(port.main.table.root.getLayoutBounds());
                    if (cell != null && table.intersects(cell)) { x = cell.getMinX(); y = cell.getMaxY(); break; }
                }
            }
            if (placement.bounds() != null) { x = placement.bounds().x(); y = placement.bounds().y(); }
            popup.show(owner, x, y);
            fields.values().stream().findFirst().ifPresent(f -> { f.control.requestFocus(); if (f.control instanceof javafx.scene.control.TextInputControl t) t.selectAll(); });
        });
    }
    /** Обновляет состояние формы без замены null-значений. */
    @Override public void update(FormView view) {
        header.setText(view.header()); FxStyles.text(header, ColorToken.TEXT_PRIMARY, FontToken.BASE);
        header.setFont(javafx.scene.text.Font.font(FontToken.BASE.primaryFamily(), javafx.scene.text.FontWeight.BOLD, FontToken.BASE.sizePx()));
        problem.setText(view.problem().display()); FxStyles.text(problem, view.problem().color(), ru.cashprediction.core.ui.token.FontToken.SMALL);
        problem.setVisible(!problem.getText().isEmpty()); problem.setManaged(problem.isVisible());
        fields.forEach((id, f) -> f.update(view.fields().get(id), view));
    }
    /** Этот хост обслуживает только форму. */
    @Override public void updateAlert(AlertSpec spec) { throw new UnsupportedOperationException("popup"); }
    /** Закрывает Popup без второго события отмены. */
    @Override public void close() { closing = true; popup.hide(); }
    /** Возвращает фокус полю. */
    @Override public void toFront() { fields.values().stream().findFirst().ifPresent(f -> f.control.requestFocus()); }
    /** Читает реальные границы Popup. */
    @Override public WindowBounds bounds() { return FxUiPort.bounds(popup); }
    /** Проверяет реальный показ Popup. */
    @Override public boolean showing() { return popup.isShowing(); }
}
