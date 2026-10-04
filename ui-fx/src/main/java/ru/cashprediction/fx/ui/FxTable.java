package ru.cashprediction.fx.ui;

import java.util.*;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.ObservableListBase;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import ru.cashprediction.core.ui.token.DesignTokens;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.menu.ContextTarget;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.view.table.*;

/** Виртуальная таблица индексов, которая запрашивает строки по мере отрисовки. */
public final class FxTable {
    final TableView<Integer> root = new TableView<>();
    TableModel model;
    final Map<String, TableColumn<Integer, String>> columns = new LinkedHashMap<>();
    private final UiIntents intents;
    private final FxMenus menus;
    private final FxClassUsageProbe probe;
    private boolean updating;
    private boolean userSelection;
    private long selectionVersion;
    private String lastModelScrollId = "";
    private long revealVersion;

    /** Создаёт виртуальную таблицу и связывает настоящие события ячеек. */
    public FxTable(UiIntents intents, FxMenus menus, FxClassUsageProbe probe) {
        this.intents = intents; this.menus = menus; this.probe = probe;
        FxStyles.id(root, "table"); root.getProperties().put("cp.scope", FocusScope.TABLE);
        // Полосы VirtualFlow создаются и заменяются вместе с настоящим скином таблицы.
        FxIcons.skin(root);
        root.setFixedCellSize(26); root.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        root.setColumnResizePolicy(features -> {
            if (model == null) return false;
            int fixed = model.columns().stream().filter(c -> !c.grows()).mapToInt(ColumnSpec::widthPx).sum();
            for (var spec : model.columns()) {
                var column = columns.get(spec.id());
                if (column != null) features.setColumnWidth(column, spec.grows()
                        ? Math.max(spec.widthPx(), features.getContentWidth() - fixed) : spec.widthPx());
            }
            return true;
        });
        root.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(Color.web(ColorToken.BG_SURFACE.hex()), javafx.scene.layout.CornerRadii.EMPTY, javafx.geometry.Insets.EMPTY)));
        root.addEventFilter(KeyEvent.KEY_PRESSED, e -> { userSelection = true; javafx.application.Platform.runLater(() -> userSelection = false); });
        root.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> { userSelection = true; javafx.application.Platform.runLater(() -> userSelection = false); });
        root.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (!updating && b != null && model != null && b >= 0 && b < model.rowCount()) {
                long version = ++selectionVersion;
                TableModel selectedModel = model;
                String rowId = model.row(b).rowId();
                boolean fromInput = userSelection;
                // TableView ещё меняет selectedIndices: нельзя синхронно перестраивать список или выделение.
                // Новая модель или следующий жест отменяют отложенный ответ предыдущего события.
                javafx.application.Platform.runLater(() -> {
                    if (version != selectionVersion || updating || model != selectedModel
                            || !Objects.equals(root.getSelectionModel().getSelectedItem(), b)) return;
                    if (fromInput) { intents.selectRow(rowId); return; }
                    int wanted = model.indexOf(model.selectedRowId());
                    if (wanted != b) {
                        updating = true;
                        try { root.getSelectionModel().clearSelection(); if (wanted >= 0) root.getSelectionModel().select(wanted); }
                        finally { updating = false; }
                    }
                });
            }
        });
    }

    /** Устанавливает индекс и колонки, не создавая все тексты строк. */
    public void render(TableModel next) {
        selectionVersion++;
        updating = true;
        try {
            model = next; columns.clear(); root.getColumns().clear();
            for (int i = 0; i < next.columns().size(); i++) {
                int columnIndex = i; ColumnSpec spec = next.columns().get(i);
                TableColumn<Integer, String> column = new TableColumn<>(spec.title());
                column.setId(spec.id()); column.setSortable(false); column.setReorderable(false); column.setPrefWidth(spec.widthPx());
                // Скин переносит класс колонки в её заголовок; выравнивание задаёт общая модель.
                column.getStyleClass().add(switch (spec.align()) {
                    case LEFT -> "cp-column-left";
                    case CENTER -> "cp-column-center";
                    case RIGHT -> "cp-column-right";
                });
                // Ширину остатка сообщает нативная раскладка с учётом настоящего scrollbar, без константы 18 px.
                column.setCellValueFactory(v -> new ReadOnlyStringWrapper(model.row(v.getValue()).cells().get(columnIndex)));
                column.setCellFactory(c -> new TableCell<>() {
                    /** Обновляет реальную заливку при смене выделения виртуальной ячейки. */
                    @Override public void updateSelected(boolean selected) {
                        super.updateSelected(selected); paintBackground();
                    }
                    private void paintBackground() {
                        if (model == null || getIndex() < 0 || getIndex() >= model.rowCount()) { setBackground(null); return; }
                        ColorToken background = root.getSelectionModel().isSelected(getIndex())
                                ? ColorToken.ACCENT_WEAK : model.row(getIndex()).rowStyle().background();
                        setBackground(background == null ? null : new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(Color.web(background.hex()), javafx.scene.layout.CornerRadii.EMPTY, javafx.geometry.Insets.EMPTY)));
                    }
                    /** Обновляет текст, цвета, подсказку и действия реально отображаемой ячейки. */
                    @Override protected void updateItem(String value, boolean empty) {
                        super.updateItem(value, empty); setText(empty ? null : value); setGraphic(null);
                        getProperties().remove("cp.paintText"); getProperties().remove("cp.logicalText");
                        setAccessibleText(null);
                        if (empty || getIndex() < 0 || getIndex() >= model.rowCount()) { setTooltip(null); return; }
                        TableRowView row = model.row(getIndex());
                        CellStyle look = row.cellStyles().get(spec.id()); RowStyle rowLook = row.rowStyle();
                        ColorToken color = look != null && look.text() != null ? look.text() : rowLook.text();
                        boolean bold = spec.bold() || rowLook.bold() || look != null && look.bold();
                        boolean italic = rowLook.italic() || look != null && look.italic();
                        Text text = new Text(value); text.setFill(Color.web(color.hex()));
                        text.setFont(Font.font("Segoe UI", bold ? FontWeight.BOLD : FontWeight.NORMAL,
                                italic ? FontPosture.ITALIC : FontPosture.REGULAR, 13));
                        text.setStrikethrough(look != null && look.strike()); setText(null);
                        getProperties().put("cp.paintText", text); getProperties().put("cp.logicalText", value);
                        setAccessibleText(value);
                        setGraphic(spec.id().equals(LazyTableModel.COLUMN_MARKS) || row.kind() == RowKind.PAST_HEADER
                                ? FxIcons.tableGraphic(text, spec.id().equals(LazyTableModel.COLUMN_MARKS)) : text);
                        setAlignment(switch (spec.align()) { case LEFT -> Pos.CENTER_LEFT; case CENTER -> Pos.CENTER; case RIGHT -> Pos.CENTER_RIGHT; });
                        paintBackground();
                        setTooltip(FxStyles.tip(intents.decoratedTableTooltip(model.revision(), getIndex(), spec.id()), probe));
                        getProperties().put("cp.row", row.rowId()); getProperties().put("cp.column", spec.id());
                        getProperties().put("cp.rowKind", row.kind().name());
                        getProperties().put("cp.background", rowLook.background() == null ? "" : rowLook.background().id());
                        setOnMouseClicked(e -> {
                            root.getSelectionModel().select(getIndex());
                            intents.selectRow(row.rowId());
                            intents.activateRow(row.rowId(), spec.id(), e.getClickCount() == 2 ? Activation.DOUBLE_CLICK : Activation.CLICK);
                        });
                        // JavaFX: ContextMenuEvent → Swing: MouseEvent.popupTrigger → Web: contextmenu
                        setOnContextMenuRequested(e -> {
                            probe.created(e);
                            if (row.kind() != RowKind.MONTH_TOTAL && row.kind() != RowKind.PAST_HEADER) intents.selectRow(row.rowId());
                            ContextTarget target = switch (row.kind()) {
                                case MONTH_TOTAL -> new ContextTarget.Total(row.rowId());
                                case PAST_HEADER -> new ContextTarget.PastHeader(row.rowId());
                                default -> new ContextTarget.Row(row.rowId());
                            };
                            var context = menus.context(intents.contextMenu(target), InvokeSource.CONTEXT_MENU);
                            context.getProperties().put("cp.target", FxUiPort.contextId(target));
                            // Выбор строки может синхронно заменить ячейку; таблица остаётся присоединённым владельцем.
                            context.show(root, e.getScreenX(), e.getScreenY()); e.consume();
                        });
                    }
                });
                columns.put(spec.id(), column); root.getColumns().add(column);
            }
            root.setItems(new ObservableListBase<>() {
                /** Возвращает индекс строки без загрузки текста. */
                @Override public Integer get(int index) { return Objects.checkIndex(index, size()); }
                /** Возвращает размер индекса ядра. */
                @Override public int size() { return next.rowCount(); }
            });
            VBox empty = new VBox(8); empty.setAlignment(Pos.CENTER);
            Label caption = new Label(next.placeholder() == null ? "" : next.placeholder().text()); caption.setWrapText(true); empty.getChildren().add(caption);
            HBox actions = new HBox(DesignTokens.FORM_VGAP); actions.setAlignment(Pos.CENTER); empty.getChildren().add(actions);
            for (var b : next.placeholder() == null ? List.<Placeholder.Button>of() : next.placeholder().buttons()) {
                Button button = FxStyles.id(new Button(b.text()), b.id());
                FxStyles.dialogButton(button);
                button.setOnAction(e -> intents.command(b.command(), CommandArgs.NONE, InvokeSource.MAIN)); actions.getChildren().add(button);
            }
            root.setPlaceholder(empty);
            int selected = next.indexOf(next.selectedRowId());
            root.getSelectionModel().clearSelection();
            if (selected >= 0) root.getSelectionModel().select(selected);
            String scrollId = next.scrollToRowId();
            int scroll = next.indexOf(scrollId);
            if (scroll >= 0 && !scrollId.equals(lastModelScrollId)) scrollToTop(scroll);
            lastModelScrollId = scrollId;
        } finally { updating = false; }
    }

    /** Прокручивает к индексу уже рассчитанного ядром идентификатора. */
    public void reveal(String rowId, RevealMode mode) {
        int index = model == null ? -1 : model.indexOf(rowId);
        if (index < 0) return;
        if (mode == RevealMode.SCROLL_TO_TOP) scrollToTop(index);
        else { ensureVisible(index); root.getSelectionModel().select(index); }
    }

    /** Показывает строку ближайшим сдвигом реального viewport, не сдвигая уже видимую строку. */
    void ensureVisible(int index) {
        revealVersion++;
        root.applyCss(); root.layout();
        if (!(root.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow)) {
            root.scrollTo(index); return;
        }
        var first = flow.getFirstVisibleCell(); var viewport = flow.lookup(".clipped-container");
        if (first == null || viewport == null || root.getFixedCellSize() <= 0) { flow.scrollTo(index); return; }
        var firstBox = first.localToScene(first.getLayoutBounds());
        var visible = viewport.localToScene(viewport.getLayoutBounds());
        double top = firstBox.getMinY() + (index - first.getIndex()) * root.getFixedCellSize();
        double bottom = top + root.getFixedCellSize();
        if (top < visible.getMinY()) flow.scrollPixels(top - visible.getMinY());
        else if (bottom > visible.getMaxY()) flow.scrollPixels(bottom - visible.getMaxY());
        root.layout();
    }

    private void scrollToTop(int index) {
        long version = ++revealVersion;
        applyTop(index);
        // После обновления данных скин ещё может пересчитать виртуальные ячейки в следующей очереди.
        // Более поздний nearest-жест отменяет повтор, чтобы не вернуть пользователя к старому запросу TOP.
        javafx.application.Platform.runLater(() -> {
            if (version != revealVersion || model == null || index >= model.rowCount()) return;
            applyTop(index); javafx.application.Platform.requestNextPulse();
        });
    }

    private void applyTop(int index) {
        root.applyCss(); root.layout();
        if (root.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow) {
            flow.scrollToTop(index); root.layout();
        }
    }
}
