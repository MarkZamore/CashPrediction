package ru.cashprediction.fx.ui;

import java.util.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import ru.cashprediction.core.app.AppController;
import ru.cashprediction.core.ui.command.CommandId;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.dump.DumpGeometry;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.token.ColorToken;

/** Считывает реальные свойства виджетов, включая виртуальные ячейки через их фабрику. */
public final class FxUiDumper {
    private final FxUiPort port;
    private final AppController controller;

    /** Подключает конкретный порт, не извлекая его из обёртки контроллера. */
    public FxUiDumper(FxUiPort port, AppController controller) { this.port = port; this.controller = controller; }

    /** Собирает дамп только из созданных элементов интерфейса. */
    public UiDump dump(String scenario, String step) {
        MainWindowView main = port.main;
        var scene = port.stage.getScene();
        Map<String, UiDump.Box> regions = new LinkedHashMap<>();
        if (main != null) { main.root.applyCss(); main.root.layout(); regions.put("menuBar", box(main.bar)); regions.put("toolbar", box(main.toolbar.root)); regions.put("summary", box(main.summary)); regions.put("center", box(main.root.getCenter())); regions.put("status", box(main.status)); }
        if (main != null) {
            baseline(main.toolbar.root).ifPresent(b -> regions.put("toolbar.baseline", b));
            // Во всех клиентах сравнивается текст первого видимого сегмента, не разделитель или соседнее сообщение.
            main.status.getChildren().stream().filter(n -> n instanceof Label && n.isVisible() && n.isManaged())
                    .findFirst().flatMap(FxUiDumper::baseline).ifPresent(b -> regions.put("status.baseline", b));
        }
        if (main != null && main.root.getCenter() == main.table.root) {
            Node header = main.table.root.lookup(".nested-column-header");
            if (header != null && header.isVisible()) regions.put("table.header", box(header));
            for (Node node : main.table.root.lookupAll(".column-header")) {
                if (node instanceof javafx.scene.control.skin.TableColumnHeader columnHeader && node.isVisible()
                        && columnHeader.getTableColumn() != null && columnHeader.getTableColumn().isVisible()) {
                    regions.put("table.column." + columnHeader.getTableColumn().getId(), box(node));
                }
            }
        }
        UiDump.Frame frame = main == null || !port.stage.isShowing() ? null
                : new UiDump.Frame(port.stage.getTitle(), "os", new UiDump.Size(port.stage.getMinWidth(), port.stage.getMinHeight()), scene == null ? 0 : scene.getWidth(), scene == null ? 0 : scene.getHeight(), regions);
        List<UiDump.Window> windows = new ArrayList<>(); List<UiDump.Alert> alerts = new ArrayList<>();
        for (var entry : port.windows.entrySet()) {
            if (!entry.getValue().showing()) continue;
            if (entry.getValue() instanceof FxFormDialog f) {
                windows.add(new UiDump.Window(entry.getKey(), f.spec.windowType().name(), f.spec.purpose(), f.dialog.getTitle(), f.header.getText(), f.glyph.isVisible() ? f.glyph.getText() : "", f.dialog.getModality() != javafx.stage.Modality.NONE, ownerId(f.dialog.getOwner()), f.page, contentBounds(f.dialog.getDialogPane()),
                        f.sections.stream().map(Label::getText).toList(), f.hints.stream().map(Label::getText).toList(), fields(f.fields), preview(f.fields), previewIndex(f.fields), f.results.getChildren().stream().filter(n -> n instanceof Label).map(n -> new UiDump.ResultText(((Label) n).getText(), color(((Label) n).getTextFill()))).toList(), f.problem.getText(), buttons(f.buttons), f.details.getText(), f.detailsLink.isVisible() ? f.detailsLink.getText() : "", f.details.isVisible()));
            } else if (entry.getValue() instanceof FxQuickEditPopup p) {
                windows.add(new UiDump.Window(entry.getKey(), p.spec.windowType().name(), p.spec.purpose(), "", p.header.getText(), "", false, ownerId(p.popup.getOwnerWindow()), 0, contentBounds(p.popup.getContent().getFirst()), List.of(), p.hints.stream().map(Label::getText).toList(), fields(p.fields), List.of(), -1, List.of(), p.problem.isVisible() ? p.problem.getText() : "", List.of(), "", "", false));
            } else if (entry.getValue() instanceof FxAlerts a) {
                alerts.add(new UiDump.Alert(a.id, a.spec.purpose(), a.alert.getAlertType().name(), a.alert.getTitle(), a.alert.getGraphic() == a.glyph && a.glyph.isVisible() ? a.glyph.getText() : "", (int) a.alert.getDialogPane().getMinWidth(), a.alert.getHeaderText(), a.content.getText(), a.details.getText(), a.link.isVisible() ? a.link.getText() : "", a.details.isVisible(), buttons(a.buttons)));
            }
        }
        List<UiDump.Segment> status = main == null ? List.of() : main.status.getChildren().stream().filter(n -> n instanceof Label).map(n -> {
            Label l = (Label) n; return new UiDump.Segment(id(l), l.getText(), tip(l), color(l.getTextFill()), l.isVisible());
        }).toList();
        List<UiDump.ContextMenu> contexts = port.contexts.stream().filter(ContextMenu::isShowing).map(c -> new UiDump.ContextMenu(c.getProperties().getOrDefault("cp.target", "").toString(), c.getItems().stream().map(FxUiDumper::menu).toList())).toList();
        Map<String, Integer> counters = new LinkedHashMap<>(); for (CommandId id : CommandId.values()) { int count = controller.executedCount(id); if (count > 0) counters.put(id.id(), count); }
        return new UiDump(1, "fx", scenario, step, frame, main == null ? List.of() : main.bar.getMenus().stream().map(FxUiDumper::menu).toList(), main == null ? null : toolbar(main.toolbar), main == null ? null : summary(main.summary), main == null ? null : table(main.table), main == null ? null : chart(main),
                status, contexts, windows, alerts, popups(), List.of(), port.chooserRequests, port.probe.snapshot(), counters);
    }

    private static List<UiDump.Field> fields(Map<String, FxFieldWidgets> fields) {
        return fields.values().stream().filter(f -> f.root.isVisible()).map(f -> new UiDump.Field(f.spec.id(), f.spec.kind().name(), FxFieldWidgets.semanticCaption(f.label.getText()), f.text(), f.control instanceof TextInputControl t ? t.getPromptText() : "", f.control instanceof Control c ? tip(c) : "", f.spec.suffix(), !f.control.isDisabled(), f.root.isVisible(), f.control instanceof TextInputControl t && !t.isEditable(), options(f))).toList();
    }
    private static List<String> options(FxFieldWidgets f) {
        if (f.control instanceof ComboBox<?> combo) return combo.getItems().stream().map(o -> comboText(combo, o)).toList();
        if (f.spec.kind() == ru.cashprediction.core.ui.form.FieldKind.RADIO) return f.radioButtons().stream().map(RadioButton::getText).toList();
        if (f.control instanceof ListView<?> list) return list.getItems().stream().map(Object::toString).toList();
        return List.of();
    }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static String comboText(ComboBox combo, Object value) { return combo.getConverter().toString(value); }
    private static List<String> preview(Map<String, FxFieldWidgets> fields) { return fields.values().stream().filter(f -> f.spec.kind() == ru.cashprediction.core.ui.form.FieldKind.PREVIEW).flatMap(f -> ((ListView<?>) f.control).getItems().stream().map(Object::toString)).toList(); }
    private static int previewIndex(Map<String, FxFieldWidgets> fields) { return fields.values().stream().filter(f -> f.spec.kind() == ru.cashprediction.core.ui.form.FieldKind.PREVIEW).mapToInt(f -> ((ListView<?>) f.control).getSelectionModel().getSelectedIndex()).findFirst().orElse(-1); }
    private static List<UiDump.Button> buttons(Map<String, Button> map) { return map.values().stream().filter(Node::isVisible).map(b -> new UiDump.Button(id(b), b.getText(), tip(b), !b.isDisabled(), b.isDefaultButton(), box(b).x())).toList(); }

    /** Считывает настоящий пункт меню и его дочерние виджеты. */
    public static UiDump.MenuItem menu(MenuItem item) {
        String value = "", valueLabel = "", text = Objects.toString(item.getText(), "");
        if (item.getProperties().get("cp.control") instanceof Slider slider) { value = Integer.toString((int) Math.round(slider.getValue())); valueLabel = ((Label) item.getProperties().get("cp.label")).getText(); }
        if (item.getProperties().get("cp.control") instanceof Spinner<?> spinner) { value = spinner.getEditor().getText(); text = Objects.toString(item.getProperties().get("cp.label"), ""); }
        return new UiDump.MenuItem(item.getId(), Objects.toString(item.getProperties().get("cp.kind"), ""), text, item.getGraphic() instanceof Label shown && item.getProperties().get("cp.acceleratorLabel") == shown ? shown.getText() : "", !item.isDisable(), item instanceof CheckMenuItem c && c.isSelected() || item instanceof RadioMenuItem r && r.isSelected(), Objects.toString(item.getProperties().get("cp.group"), ""), value, valueLabel, Objects.toString(item.getProperties().get("cp.tooltip"), ""), item instanceof Menu m ? m.getItems().stream().map(FxUiDumper::menu).toList() : List.of());
    }
    private static UiDump.Toolbar toolbar(FxToolbar toolbar) {
        List<UiDump.ToolbarItem> items = new ArrayList<>(); for (var n : toolbar.root.getChildren()) {
            String text = n instanceof Labeled l ? l.getText() : n instanceof TextField f ? f.getText() : "";
            List<MenuItem> children = n instanceof MenuButton b ? b.getItems() : List.of();
            items.add(new UiDump.ToolbarItem(id(n), Objects.toString(n.getProperties().get("cp.kind"), ""), text, n instanceof TextField f ? f.getPromptText() : "", Objects.toString(n.getProperties().get("cp.tooltip"), ""), !n.isDisabled(), n instanceof ToggleButton b && b.isSelected(), n instanceof Labeled l ? color(l.getTextFill()) : n instanceof TextField f ? textInputColor(f) : "", n instanceof Labeled l && l.getFont().getStyle().contains("Bold"), 0, box(n), children.stream().map(FxUiDumper::menu).toList()));
        }
        return new UiDump.Toolbar(false, items);
    }
    private static UiDump.Summary summary(FlowPane pane) {
        List<UiDump.Card> cards = new ArrayList<>(); String unavailable = "";
        for (Node n : pane.getChildren()) {
            if (n instanceof VBox b && b.getChildren().size() >= 3) { Label title = (Label) b.getChildren().get(0), value = (Label) b.getChildren().get(1), caption = (Label) b.getChildren().get(2); cards.add(new UiDump.Card(id(b), title.getText(), value.getText(), color(value.getTextFill()), caption.getText(), color(caption.getTextFill()), Objects.toString(b.getProperties().get("cp.tooltip"), ""), box(b))); }
            else if (n instanceof Label l) unavailable = l.getText();
        }
        return new UiDump.Summary(pane.isVisible(), cards, unavailable);
    }
    private static UiDump.Chart chart(MainWindowView main) {
        if (!(main.chart.getProperties().get("cp.drawn") instanceof UiDump.Chart drawn)) return null;
        return new UiDump.Chart(main.legend.getChildren().stream().filter(n -> n instanceof Label).map(n -> ((Label) n).getText()).toList(), drawn.xLabels(), drawn.yLabels(), drawn.lineLabels(), drawn.markerCount(), drawn.barCount(), drawn.notice());
    }
    private List<UiDump.Popup> popups() {
        List<UiDump.Popup> result = new ArrayList<>();
        for (var window : javafx.stage.Window.getWindows()) {
            if (!window.isShowing() || !window.getProperties().containsKey("cp.popupKind")) continue;
            List<String> lines = new ArrayList<>(); popupTexts(window.getScene().getRoot(), lines);
            result.add(new UiDump.Popup(window.getProperties().get("cp.popupKind").toString(), lines, contentBounds(popupContent(window))));
        }
        return result;
    }
    /** Измеряет настоящий skin-node, а не layoutBounds служебной CSS-обёртки PopupControl. */
    static Node popupContent(javafx.stage.Window window) {
        // Стандартная подсказка рисует отступ и фон на CSS-обёртке, её skin Label содержит только текст.
        if (window instanceof Tooltip) return window.getScene().getRoot();
        if (window instanceof PopupControl control && control.getSkin() != null) return control.getSkin().getNode();
        if (window instanceof javafx.stage.Popup popup && !popup.getContent().isEmpty()) return popup.getContent().getFirst();
        return window.getScene().getRoot();
    }
    private static void popupTexts(Node node, List<String> lines) {
        if (!node.isVisible()) return;
        if (node instanceof Labeled label) { if (!label.getText().isEmpty()) lines.add(label.getText()); return; }
        if (node instanceof javafx.scene.Parent parent) parent.getChildrenUnmodifiable().forEach(child -> popupTexts(child, lines));
    }

    private static UiDump.Table table(FxTable table) {
        if (table.model == null) return null;
        MessageDigest digest; try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        List<UiDump.Row> rows = new ArrayList<>();
        int selected = table.root.getSelectionModel().getSelectedIndex(); String selectedId = "";
        // Фабрика создаёт настоящие ячейки и применяет тот же путь updateItem, что виртуальная таблица.
        List<TableCell<Integer, String>> cells = new ArrayList<>();
        for (var column : table.root.getColumns()) {
            @SuppressWarnings("unchecked") TableColumn<Integer, String> c = (TableColumn<Integer, String>) column;
            var cell = c.getCellFactory().call(c); cell.updateTableView(table.root); cell.updateTableColumn(c); cells.add(cell);
        }
        for (int i = 0; i < table.root.getItems().size(); i++) {
            List<String> texts = new ArrayList<>(); Map<String, UiDump.CellLook> looks = new LinkedHashMap<>();
            for (int col = 0; col < cells.size(); col++) {
                var cell = cells.get(col); cell.updateIndex(i);
                Text text = (Text) cell.getGraphic(); texts.add(text == null ? Objects.toString(cell.getText(), "") : text.getText());
                if (text != null) looks.put(table.root.getColumns().get(col).getId(), new UiDump.CellLook(color(text.getFill()), text.getFont().getStyle().contains("Bold"), text.getFont().getStyle().contains("Italic"), text.isStrikethrough()));
            }
            String rowId = Objects.toString(cells.getFirst().getProperties().get("cp.row"), "");
            if (i == selected) selectedId = rowId;
            byte[] bytes = UiJson.write(texts).getBytes(StandardCharsets.UTF_8); digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            String bg = effectiveBackground(cells.getFirst(), table.root);
            if (i < 60 || i == selected) rows.add(new UiDump.Row(i, rowId, Objects.toString(cells.getFirst().getProperties().get("cp.rowKind"), ""), texts, bg, looks));
        }
        VBox placeholder = (VBox) table.root.getPlaceholder(); String caption = ((Label) placeholder.getChildren().getFirst()).getText();
        Map<String, Button> placeholderButtons = new LinkedHashMap<>();
        collectButtons(placeholder, placeholderButtons);
        return new UiDump.Table(table.root.getColumns().stream().map(TableColumn::getText).toList(), table.root.getItems().size(), rows, HexFormat.of().formatHex(digest.digest()), caption, buttons(placeholderButtons), selectedId);
    }
    private static void collectButtons(Node node, Map<String, Button> result) {
        if (node instanceof Button button) result.put(id(button), button);
        else if (node instanceof javafx.scene.Parent parent) parent.getChildrenUnmodifiable().forEach(child -> collectButtons(child, result));
    }
    static String id(Node node) { return Objects.toString(node.getProperties().get("cp.id"), ""); }
    @SuppressWarnings({"rawtypes", "unchecked"}) private static String textInputColor(TextInputControl control) {
        for (javafx.css.CssMetaData metadata : control.getCssMetaData()) if ("-fx-text-fill".equals(metadata.getProperty())) {
            Object value = metadata.getStyleableProperty(control).getValue();
            return value instanceof javafx.scene.paint.Paint paint ? color(paint) : "";
        }
        return "";
    }
    /** Читает нарисованный фон, проходя прозрачные слои до реального родителя таблицы. */
    static String effectiveBackground(Node node, Node fallback) {
        for (Node current = node; current != null; current = current.getParent()) {
            String fill = opaqueBackground(current); if (!fill.isEmpty()) return fill;
        }
        for (Node current = fallback; current != null; current = current.getParent()) {
            String fill = opaqueBackground(current); if (!fill.isEmpty()) return fill;
        }
        return "";
    }
    private static String opaqueBackground(Node node) {
        if (node instanceof Region region && region.getBackground() != null) {
            var fills = region.getBackground().getFills();
            for (int i = fills.size() - 1; i >= 0; i--) if (fills.get(i).getFill() instanceof Color c && c.getOpacity() == 1) return color(c);
        }
        return "";
    }
    private String ownerId(javafx.stage.Window owner) { if (owner == null || owner == port.stage) return ru.cashprediction.core.session.WindowState.MAIN_OWNER; return port.windows.keySet().stream().filter(id -> port.owner(id) == owner).findFirst().orElse(""); }
    static String tip(Control node) { return node.getTooltip() == null ? "" : node.getTooltip().getText(); }
    static String color(javafx.scene.paint.Paint paint) { if (!(paint instanceof Color c)) return ""; int argb = ((int) Math.round(c.getOpacity() * 255) << 24) | ((int) Math.round(c.getRed() * 255) << 16) | ((int) Math.round(c.getGreen() * 255) << 8) | (int) Math.round(c.getBlue() * 255); return ColorToken.byArgb(argb).map(ColorToken::id).orElse(c.toString()); }
    /** Читает реально выделенную область раскладки без теней и выходящих за неё дочерних узлов. */
    static UiDump.Box box(Node node) {
        var layout = node instanceof Region region
                ? new javafx.geometry.BoundingBox(0, 0, region.getWidth(), region.getHeight()) : node.getLayoutBounds();
        var b = node.localToScene(layout);
        return new UiDump.Box(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight());
    }
    /** Измеряет базовую линию первого нарисованного текста относительно содержимого сцены. */
    static Optional<UiDump.Box> baseline(Node node) {
        if (!node.isVisible()) return Optional.empty();
        if (node instanceof Text text && !text.getText().isEmpty()) {
            var bounds = text.getBoundsInLocal();
            var point = text.localToScene(bounds.getMinX(), text.getLayoutBounds().getMinY() + text.getBaselineOffset());
            return Optional.of(new UiDump.Box(point.getX(), point.getY(), bounds.getWidth(), 1));
        }
        if (node instanceof javafx.scene.Parent parent) for (Node child : parent.getChildrenUnmodifiable()) {
            var found = baseline(child); if (found.isPresent()) return found;
        }
        return Optional.empty();
    }
    /** Измеряет живое содержимое на экране; RAW-границы WindowHandle для восстановления не использует. */
    private UiDump.Box contentBounds(Node content) {
        if (port.main == null || !port.stage.isShowing()) return null;
        return relativeContent(content.localToScreen(content.getLayoutBounds()),
                port.main.root.localToScreen(port.main.root.getLayoutBounds()));
    }
    /** Переводит измерения JavaFX общим контрактом, не меняя размер содержимого. */
    static UiDump.Box relativeContent(javafx.geometry.Bounds content, javafx.geometry.Bounds main) {
        if (content == null || main == null) return null;
        return DumpGeometry.relativeContent(new UiDump.Box(content.getMinX(), content.getMinY(), content.getWidth(), content.getHeight()),
                new UiDump.Box(main.getMinX(), main.getMinY(), main.getWidth(), main.getHeight()));
    }
}
