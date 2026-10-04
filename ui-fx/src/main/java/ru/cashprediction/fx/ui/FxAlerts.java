package ru.cashprediction.fx.ui;

import java.util.*;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.ui.alert.*;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.text.UiText;

/** Хост сообщений с однократным ответом и подтверждением настоящего показа. */
public final class FxAlerts implements WindowHandle {
    final Alert alert;
    final Map<String, Button> buttons = new LinkedHashMap<>();
    final Label content = new Label();
    final Label glyph = new Label();
    final TextArea details = new TextArea();
    final Hyperlink link = new Hyperlink();
    AlertSpec spec;
    final String id;
    private final FxUiPort port;
    private final Consumer<String> answer;
    private final AlertSession session;
    private boolean answered;
    private boolean closing;

    /** Создаёт сообщение без блокировки потока контроллера. */
    public FxAlerts(AlertSpec spec, AlertSession session, Consumer<String> answer, FxUiPort port) {
        this.port = port; this.answer = answer; this.session = session;
        id = session == null ? ((AppController) port.intents).state().windows().windows().getLast().windowId() : session.windowId();
        // JavaFX: Alert → Swing: SwingAlert → Web: dialog.alert
        alert = port.probe.created(new Alert(Alert.AlertType.valueOf(spec.kind().name())));
        alert.initOwner(session == null ? port.stage : port.owner(session.ownerId()));
        alert.initModality(javafx.stage.Modality.APPLICATION_MODAL);
        FxStyles.root(alert.getDialogPane()); content.setWrapText(true); details.setEditable(false); details.setPrefRowCount(16);
        link.setOnAction(e -> { boolean expanded = !details.isVisible(); details.setVisible(expanded); details.setManaged(expanded); link.setText(UiText.get(expanded ? "details.hide" : "details.show")); });
        alert.getDialogPane().setContent(new VBox(8, content, link, details));
        alert.setOnCloseRequest(e -> { if (!closing) { e.consume(); specCancel(); } });
        alert.setOnShown(e -> {
            if (session != null) {
                if (session.restoredBounds() != null)
                    FxFormDialog.applyBounds(alert.getDialogPane().getScene().getWindow(), session.restoredBounds());
                // Регистрация и продолжение восстановления видят уже применённую геометрию окна.
                session.shown();
            }
        });
        updateAlert(spec);
        Platform.runLater(() -> { if (!closing) alert.show(); });
    }

    private void specCancel() { spec.buttons().stream().filter(b -> b.role() == ButtonRole.CANCEL).findFirst().ifPresentOrElse(b -> respond(b.id()), () -> respond(spec.defaultButtonId())); }
    private void respond(String id) { if (!answered) {
        if (Boolean.getBoolean("fx.hover.metrics")) System.out.println("HOVER_ALERT_RESPONSE purpose=" + spec.purpose() + " button=" + id + " nanos=" + System.nanoTime());
        answered = true; close(); answer.accept(id);
    } }

    /** Отображает новое содержимое сообщения в настоящих виджетах. */
    @Override public void updateAlert(AlertSpec next) {
        spec = next; alert.setTitle(next.windowTitle()); alert.setHeaderText(next.header()); content.setText(next.content());
        glyph.setText(next.glyph());
        glyph.setTextFill(javafx.scene.paint.Color.web(switch (next.kind()) {
            case ERROR -> ru.cashprediction.core.ui.token.ColorToken.EXPENSE.hex();
            case WARNING -> ru.cashprediction.core.ui.token.ColorToken.WARN.hex();
            default -> ru.cashprediction.core.ui.token.ColorToken.ACCENT.hex();
        }));
        FxIcons.icon(glyph, next.glyph().isEmpty() ? next.kind().webGlyph() : next.glyph(), ru.cashprediction.core.ui.token.DesignTokens.ALERT_ICON_SIZE);
        alert.setGraphic(glyph);
        alert.getDialogPane().setMinWidth(next.minWidth()); alert.getDialogPane().setPrefWidth(next.minWidth());
        content.setMinWidth(0); content.setPrefWidth(next.minWidth() - 24);
        details.setText(next.details()); details.setVisible(next.detailsExpanded()); details.setManaged(next.detailsExpanded());
        link.setText(UiText.get(next.detailsExpanded() ? "details.hide" : "details.show")); link.setVisible(!next.details().isEmpty()); link.setManaged(!next.details().isEmpty());
        FxIcons.decorate(link);
        buttons.clear(); alert.getButtonTypes().clear();
        for (var b : next.buttons()) {
            // JavaFX: ButtonType → Swing: JButton → Web: button.dialog-action
            ButtonType type = port.probe.created(new ButtonType(b.text(), b.role() == ButtonRole.CANCEL ? ButtonBar.ButtonData.CANCEL_CLOSE : b.id().equals(next.defaultButtonId()) ? ButtonBar.ButtonData.OK_DONE : ButtonBar.ButtonData.OTHER));
            alert.getButtonTypes().add(type); Button widget = FxStyles.id((Button) alert.getDialogPane().lookupButton(type), b.id());
            FxStyles.dialogButton(widget);
            widget.setDisable(!b.enabled()); widget.setDefaultButton(b.id().equals(next.defaultButtonId())); widget.setTooltip(FxStyles.tip(b.tooltip(), port.probe));
            widget.addEventFilter(javafx.event.ActionEvent.ACTION, e -> { e.consume(); respond(b.id()); }); buttons.put(b.id(), widget);
        }
        alert.getDialogPane().applyCss();
        if (alert.getDialogPane().lookup(".button-bar") instanceof ButtonBar bar) {
            bar.setButtonOrder(ButtonBar.BUTTON_ORDER_NONE);
            bar.setButtonMinWidth(ru.cashprediction.core.ui.token.DesignTokens.BUTTON_MIN_WIDTH);
            bar.setPadding(new javafx.geometry.Insets(0, 4, 0, 4));
            if (bar.lookup(".container") instanceof javafx.scene.layout.HBox row)
                row.setSpacing(ru.cashprediction.core.ui.token.DesignTokens.FORM_VGAP);
        }
        // Смена порядка создаёт раскладку ButtonBarSkin заново и возвращает платформенный минимум 75 px.
        // Применяем токен после этого эффекта, оставляя каждой кнопке её естественную ширину текста.
        buttons.values().forEach(FxStyles::dialogButton);
        if (alert.getDialogPane().lookup(".header-panel .label") instanceof Label heading) {
            // Перенос сохраняет заданную ширину контента вместо расширения окна длинной шапкой.
            heading.setWrapText(true); heading.setMinWidth(0); heading.setPrefWidth(next.minWidth() - 80);
            FxStyles.text(heading, ru.cashprediction.core.ui.token.ColorToken.TEXT_PRIMARY, ru.cashprediction.core.ui.token.FontToken.HEADER);
        }
    }
    /** Формы обслуживаются отдельным хостом. */
    @Override public void update(FormView view) { throw new UnsupportedOperationException("alert"); }
    /** Закрывает сообщение без второго ответа. */
    @Override public void close() { closing = true; alert.close(); }
    /** Поднимает живое окно сообщения. */
    @Override public void toFront() { if (alert.getDialogPane().getScene() != null) ((javafx.stage.Stage) alert.getDialogPane().getScene().getWindow()).toFront(); }
    /** Читает геометрию окна. */
    @Override public WindowBounds bounds() { var scene = alert.getDialogPane().getScene(); return scene == null ? null : FxUiPort.bounds(scene.getWindow()); }
    /** Возвращает факт показа окна. */
    @Override public boolean showing() { return alert.isShowing(); }
}
