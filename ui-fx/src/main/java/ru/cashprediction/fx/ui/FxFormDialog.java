package ru.cashprediction.fx.ui;

import java.util.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.session.WindowBounds;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.alert.AlertSpec;
import ru.cashprediction.core.ui.token.*;
import ru.cashprediction.core.ui.text.UiText;

/** Общий хост формы, включая специализированные стандартные диалоги. */
public final class FxFormDialog implements WindowHandle {
    final FormSession session;
    final FormSpec spec;
    final Dialog<Object> dialog;
    final Map<String, FxFieldWidgets> fields = new LinkedHashMap<>();
    final List<FxFieldWidgets> physicalFields = new ArrayList<>();
    final Map<String, Button> buttons = new LinkedHashMap<>();
    final VBox content = new VBox(DesignTokens.FORM_VGAP);
    final VBox formRows = new VBox(8);
    final HBox formBody = new HBox(10);
    final Label header = new Label();
    final Label glyph = new Label();
    private final HBox heading = new HBox(10, glyph, header);
    final Label problem = new Label();
    final VBox results = new VBox(0);
    final List<Label> sections = new ArrayList<>();
    final List<Label> hints = new ArrayList<>();
    final TextArea details = new TextArea();
    final Hyperlink detailsLink = new Hyperlink();
    final FxUiPort port;
    final Control specializedEditor;
    int page = -1;
    private final Placement placement;
    private boolean closing;

    /** Создаёт диалог с владельцем и откладывает показ до присоединения ручки ядром. */
    @SuppressWarnings("unchecked")
    public FxFormDialog(FormSession session, FormSpec spec, FormView initial, Placement placement, FxUiPort port) {
        this.session = session; this.spec = spec; this.port = port; this.placement = placement;
        if (spec.presentation() == Presentation.TEXT_INPUT) {
            // JavaFX: TextInputDialog → Swing: SwingTextInputDialog → Web: dialog.text-input
            dialog = (Dialog<Object>) (Dialog<?>) port.probe.created(new TextInputDialog());
        } else if (spec.presentation() == Presentation.CHOICE) {
            // JavaFX: ChoiceDialog → Swing: SwingChoiceDialog → Web: dialog.choice
            dialog = (Dialog<Object>) (Dialog<?>) port.probe.created(new ChoiceDialog<>());
        } else if (spec.presentation() == Presentation.CONFIRM) {
            // JavaFX: Alert → Swing: SwingAlert → Web: dialog.alert
            dialog = (Dialog<Object>) (Dialog<?>) port.probe.created(new Alert(Alert.AlertType.CONFIRMATION));
        } else {
            // JavaFX: Dialog → Swing: SwingDialog → Web: dialog.form
            dialog = port.probe.created(new Dialog<>());
        }
        specializedEditor = (Object) dialog instanceof TextInputDialog input ? input.getEditor()
                : (Object) dialog instanceof ChoiceDialog<?> ? findChoice(dialog.getDialogPane().getContent()) : null;
        if (specializedEditor != null && specializedEditor.getParent() instanceof Pane parent) parent.getChildren().remove(specializedEditor);
        // JavaFX: DialogPane → Swing: SwingDialogPane → Web: div.dialog-pane
        DialogPane pane = port.probe.created(new FxFormPane()); dialog.setDialogPane(pane);
        FxStyles.root(pane); dialog.setTitle(spec.windowTitle()); dialog.setResizable(spec.resizable());
        Window owner = port.owner(placement.ownerId()); dialog.initOwner(owner);
        dialog.initModality(spec.modal() ? Modality.APPLICATION_MODAL : Modality.NONE);
        header.setWrapText(true); FxStyles.text(header, ColorToken.TEXT_PRIMARY, FontToken.HEADER);
        glyph.setText(spec.glyph()); FxStyles.text(glyph, ColorToken.ACCENT, FontToken.HEADER); glyph.setStyle("-fx-font-size: 26px;");
        HBox.setHgrow(header, Priority.ALWAYS); header.setMinWidth(0);
        // Общий каркас: внешний отступ задаёт панель; строка значка сохраняет текстовую высоту настольного заголовка.
        glyph.setPadding(new javafx.geometry.Insets(DesignTokens.SPACING / 2.0, 0, DesignTokens.SPACING / 2.0, 0));
        heading.setPadding(javafx.geometry.Insets.EMPTY);
        heading.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        heading.setStyle("-fx-border-color: " + ColorToken.BORDER.fxLookup() + "; -fx-border-width: 0 0 1 0;");
        content.setPadding(new javafx.geometry.Insets(DesignTokens.FORM_VGAP, 0, 0, 0));
        formRows.setPadding(new javafx.geometry.Insets(DesignTokens.SPACING, 0, DesignTokens.SPACING, 0));
        pane.setHeader(heading); pane.setContent(content); pane.setMinWidth(spec.width()); pane.setPrefWidth(spec.width());
        problem.setMinHeight(8 * DesignTokens.SPACING); problem.setWrapText(true);
        details.setEditable(false); details.setWrapText(false); details.setPrefRowCount(16);
        detailsLink.setOnAction(e -> { boolean expanded = !details.isVisible(); details.setVisible(expanded); details.setManaged(expanded); detailsLink.setText(UiText.get(expanded ? "details.hide" : "details.show")); });
        dialog.setOnCloseRequest(e -> { if (!closing) { e.consume(); session.closeRequested(); } });
        dialog.setOnShown(e -> {
            Window window = pane.getScene().getWindow();
            if (placement.bounds() != null) applyBounds(window, placement.bounds());
            else {
                pane.applyCss(); pane.layout();
                centerFresh(window);
            }
            for (var property : List.of(window.xProperty(), window.yProperty(), window.widthProperty(), window.heightProperty())) property.addListener((o, a, b) -> session.boundsChanged(bounds()));
            session.shown();
            fields.values().stream().filter(f -> f.spec.focusFirst()).findFirst().ifPresent(f -> { f.control.requestFocus(); if (f.control instanceof TextInputControl t) t.selectAll(); });
        });
        update(initial); Platform.runLater(() -> { if (!closing && !session.isClosed()) dialog.show(); });
    }

    private void rebuild(FormView view) {
        fields.clear(); physicalFields.clear(); buttons.clear(); sections.clear(); hints.clear(); content.getChildren().clear(); results.getChildren().clear();
        results.setMinHeight(0);
        formRows.getChildren().clear(); formBody.getChildren().setAll(formRows); HBox.setHgrow(formRows, Priority.ALWAYS);
        formRows.setMinWidth(0); content.getChildren().add(formBody);
        if (!spec.pages().isEmpty()) for (FormRow row : spec.pages().get(view.page()).rows()) {
            switch (row) {
                case FormRow.Field r -> formRows.getChildren().add(field(r.field()).root);
                case FormRow.Inline r -> {
                    HBox line = new HBox(10); line.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                    Label caption = new Label(r.label().isEmpty() ? "" : r.label() + ":"); caption.setMinWidth(150); caption.setAlignment(javafx.geometry.Pos.CENTER_RIGHT); line.getChildren().add(caption);
                    r.fields().forEach(s -> {
                        FxFieldWidgets widget = field(s);
                        // В составном ряду подпись общая; подпись поля остаётся для поиска и дампа.
                        widget.root.getChildren().remove(widget.label); line.getChildren().add(widget.root);
                    }); formRows.getChildren().add(line);
                }
                case FormRow.Section r -> {
                    Label l = new Label(r.caption()); FxStyles.text(l, ColorToken.TEXT_PRIMARY, FontToken.HEADER);
                    l.setPadding(new javafx.geometry.Insets(1, 0, 1, 0)); l.setMaxWidth(Double.MAX_VALUE);
                    l.setStyle("-fx-border-color: " + ColorToken.BORDER.fxLookup() + "; -fx-border-width: 1 0 0 0;");
                    sections.add(l); formRows.getChildren().add(l);
                }
                case FormRow.Hint r -> { Label l = FxStyles.id(new Label(r.text()), r.id()); l.setWrapText(true); FxStyles.text(l, ColorToken.TEXT_MUTED, FontToken.SMALL); l.setPadding(new javafx.geometry.Insets(1, 0, 1, 0)); hints.add(l); formRows.getChildren().add(l); }
                case FormRow.Results r -> {
                    FxResultMetrics.reserve(results, r.minLines()); formRows.getChildren().add(results);
                }
                case FormRow.SideColumn r -> {
                    VBox side = new VBox(8); side.setMinWidth(300); side.setPrefWidth(300); side.setMaxWidth(300);
                    formBody.getChildren().addAll(new Separator(javafx.geometry.Orientation.VERTICAL), side);
                    Label l = new Label(r.caption()); FxStyles.text(l, ColorToken.TEXT_PRIMARY, FontToken.HEADER); sections.add(l); side.getChildren().add(l);
                    FxFieldWidgets preview = field(r.preview()); side.getChildren().add(preview.root); VBox.setVgrow(preview.root, Priority.ALWAYS);
                    for (var b : r.buttons()) { Button button = button(b.id(), b.text(), b.tooltip()); button.setMaxWidth(Double.MAX_VALUE); side.getChildren().add(button); }
                    if (r.contextMenu()) {
                        // JavaFX: ContextMenuEvent → Swing: MouseEvent.popupTrigger → Web: contextmenu
                        preview.control.setOnContextMenuRequested(e -> {
                            port.probe.created(e);
                            int selected = ((ListView<?>) preview.control).getSelectionModel().getSelectedIndex();
                            var context = port.menus.context(port.intents.contextMenu(new ru.cashprediction.core.ui.menu.ContextTarget.Preview(session.windowId(), selected)), ru.cashprediction.core.ui.command.InvokeSource.FORM);
                            context.getProperties().put("cp.target", "preview:" + session.windowId() + ":" + selected);
                            context.show(preview.control, e.getScreenX(), e.getScreenY()); e.consume();
                        });
                    }
                }
            }
        }
        content.getChildren().addAll(problem, detailsLink, details);
        DialogPane pane = dialog.getDialogPane(); pane.getButtonTypes().clear();
        for (ButtonSpec b : spec.buttons()) {
            // JavaFX: ButtonType → Swing: JButton → Web: button.dialog-action
            ButtonType type = port.probe.created(new ButtonType(b.text(), b.role() == ButtonRole.CANCEL ? ButtonBar.ButtonData.CANCEL_CLOSE : b.role() == ButtonRole.LEFT ? ButtonBar.ButtonData.LEFT : ButtonBar.ButtonData.OTHER));
            pane.getButtonTypes().add(type); Button button = (Button) pane.lookupButton(type); FxStyles.id(button, b.id());
            button.addEventFilter(javafx.event.ActionEvent.ACTION, e -> { e.consume(); session.buttonPressed(b.id()); });
            button.setMinWidth(88); button.setMinHeight(DesignTokens.CONTROL_HEIGHT);
            button.setPrefHeight(DesignTokens.CONTROL_HEIGHT); button.setMaxHeight(DesignTokens.CONTROL_HEIGHT);
            button.setTooltip(FxStyles.tip(b.tooltip(), port.probe)); buttons.put(b.id(), button);
        }
        pane.applyCss();
        page = view.page();
    }

    private FxFieldWidgets field(FieldSpec spec) {
        var f = new FxFieldWidgets(spec, session, port, fields.isEmpty() ? specializedEditor : null); physicalFields.add(f);
        var first = fields.putIfAbsent(spec.id(), f);
        if (first != null && spec.kind() == FieldKind.RADIO) first.attachRadioPeer(f);
        return f;
    }
    private static Control findChoice(javafx.scene.Node root) {
        if (root instanceof ComboBox<?> combo) return combo;
        if (root instanceof javafx.scene.Parent parent) for (var child : parent.getChildrenUnmodifiable()) { Control found = findChoice(child); if (found != null) return found; }
        return null;
    }
    private Button button(String id, String text, String tip) { Button b = FxStyles.id(new Button(text), id); b.setMinHeight(DesignTokens.CONTROL_HEIGHT); b.setPrefHeight(DesignTokens.CONTROL_HEIGHT); b.setMaxHeight(DesignTokens.CONTROL_HEIGHT); b.setTooltip(FxStyles.tip(tip, port.probe)); b.setOnAction(e -> session.buttonPressed(id)); buttons.put(id, b); return b; }

    /** Обновляет только виджеты, оставляя незавершённый ввод при null. */
    @Override public void update(FormView view) {
        boolean pageChanged = page != view.page();
        if (page != view.page()) rebuild(view);
        header.setText(view.header()); problem.setText(view.problem().display()); FxStyles.text(problem, view.problem().color(), FontToken.SMALL);
        // Одно-полевые модели без шапки не резервируют высоту пустого значка и пустого Label.
        dialog.getDialogPane().setHeader(view.header().isEmpty() && spec.glyph().isEmpty() ? null : heading);
        physicalFields.forEach(f -> f.update(view.fields().get(f.spec.id()), view));
        buttons.forEach((id, b) -> {
            ButtonView state = view.buttons().getOrDefault(id, ButtonView.ENABLED);
            b.setDisable(!state.enabled()); b.setVisible(state.visible()); b.setManaged(state.visible());
            if (state.text() != null) b.setText(state.text());
            if (state.tooltip() != null) b.setTooltip(FxStyles.tip(state.tooltip(), port.probe));
            b.setDefaultButton(id.equals(spec.defaultButtonId()));
        });
        results.getChildren().clear(); view.results().forEach(r -> { Label l = new Label(r.text()); FxStyles.text(l, r.color(), FontToken.BASE); l.setWrapText(true); l.setMinWidth(0); l.setMaxWidth(Double.MAX_VALUE); results.getChildren().add(l); });
        details.setText(view.details()); details.setVisible(view.detailsExpanded()); details.setManaged(view.detailsExpanded());
        detailsLink.setVisible(!view.details().isEmpty()); detailsLink.setManaged(!view.details().isEmpty());
        detailsLink.setText(UiText.get(view.detailsExpanded() ? "details.hide" : "details.show"));
        if (pageChanged && dialog.isShowing() && placement.bounds() == null) Platform.runLater(() -> {
            if (closing || !dialog.isShowing()) return;
            var pane = dialog.getDialogPane(); pane.applyCss();
            var window = (Stage) pane.getScene().getWindow(); window.sizeToScene(); pane.layout();
            // Страница меняет реальную высоту: центрируем новое содержимое, а не оставляем верх старой страницы.
            centerFresh(window);
        });
    }

    private void centerFresh(Window window) {
        Window owner = dialog.getOwner();
        javafx.scene.Node ownerContent = owner == port.stage && port.main != null ? port.main.root
                : owner == null || owner.getScene() == null ? null : owner.getScene().getRoot();
        FxContentPlacement.centerFresh(window, dialog.getDialogPane(), ownerContent);
    }

    /** Сообщения обновляет отдельный хост. */
    @Override public void updateAlert(AlertSpec spec) { throw new UnsupportedOperationException("form"); }
    /** Закрывает окно по решению ядра без повторного пользовательского события. */
    @Override public void close() { closing = true; dialog.close(); }
    /** Поднимает настоящее окно. */
    @Override public void toFront() { if (dialog.getDialogPane().getScene() != null) ((Stage) dialog.getDialogPane().getScene().getWindow()).toFront(); }
    /** Читает живые границы окна. */
    @Override public WindowBounds bounds() { var scene = dialog.getDialogPane().getScene(); return scene == null ? null : FxUiPort.bounds(scene.getWindow()); }
    /** Проверяет настоящий показ. */
    @Override public boolean showing() { return dialog.isShowing(); }

    /** Записывает независимые размеры живых узлов для ограниченных проверок раскладки. */
    void printLayoutMetrics(String step) {
        if (!Boolean.getBoolean("fx.form.metrics")) return;
        var pane = dialog.getDialogPane();
        System.out.println("FORM_METRICS " + step + " " + spec.purpose() + " " + spec.windowType()
                + " pane=" + pane.getWidth() + "x" + pane.getHeight()
                + " header=" + (pane.getHeader() == null ? 0 : pane.getHeader().getLayoutBounds().getHeight())
                + " content=" + content.getHeight() + " body=" + formBody.getHeight()
                + " rows=" + formRows.getHeight() + " problem=" + problem.getHeight()
                + " footer=" + pane.lookup(".button-bar").getLayoutBounds().getHeight()
                + " results=" + results.getHeight() + " resultsMin=" + results.getMinHeight()
                + " resultCount=" + results.getChildren().size());
        for (var child : formRows.getChildren()) if (child.isManaged()) System.out.println("FORM_ROW "
                + step + " " + child.getClass().getSimpleName() + " " + child.getLayoutBounds().getHeight());
        for (var entry : fields.entrySet()) if (entry.getValue().root.isManaged()) System.out.println("FORM_FIELD "
                + step + " " + entry.getKey() + " " + entry.getValue().control.getLayoutBounds().getHeight());
    }

    static void applyBounds(Window window, WindowBounds b) { window.setX(b.x()); window.setY(b.y()); window.setWidth(b.width()); window.setHeight(b.height()); }

}
