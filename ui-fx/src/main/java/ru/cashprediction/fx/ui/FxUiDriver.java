package ru.cashprediction.fx.ui;

import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.*;

/** Драйвер сценариев: ввод, выбор и команды проходят через настоящие виджеты. */
public final class FxUiDriver implements UiDriver {
    private final FxUiPort port;
    private final AppEnvironment environment;
    private final FxUiDumper dumper;
    private volatile long pendingDelay;
    private Node hoveredCard;
    private Node hoveredMenu;

    /** Сохраняет исходный конкретный порт отдельно от контроллера. */
    public FxUiDriver(FxUiPort port, AppController controller, AppEnvironment environment) {
        this.port = port; this.environment = environment; dumper = new FxUiDumper(port, controller);
    }
    /** Возвращает идентичность настоящего клиента. */
    @Override public ClientKind client() { return ClientKind.FX; }
    /** Исполняет шаг на потоке JavaFX через события виджетов. */
    @Override public void execute(SelfTestCommand command) {
        String gestureRow = switch (command) {
            case SelfTestCommand.Select c -> c.rowId();
            case SelfTestCommand.DoubleClick c -> c.rowId();
            case SelfTestCommand.RowClick c -> c.rowId();
            case SelfTestCommand.QuickEdit c -> c.rowId();
            default -> null;
        };
        if (gestureRow != null) {
            fx(() -> { select(gestureRow); return null; });
            awaitGestureLayout();
        }
        fx(() -> { perform(command); return null; });
    }

    private void awaitGestureLayout() {
        if (Platform.isFxApplicationThread()) throw new IllegalStateException("UI pulse wait");
        var ready = new CompletableFuture<Void>();
        fx(() -> {
            var scene = port.stage.getScene();
            if (scene == null || !port.stage.isShowing()) { ready.complete(null); return null; }
            Runnable[] listener = new Runnable[1];
            // Жест использует ячейку после настоящего layout-пульса, а не старую виртуальную позицию.
            listener[0] = () -> { scene.removePostLayoutPulseListener(listener[0]); ready.complete(null); };
            scene.addPostLayoutPulseListener(listener[0]); Platform.requestNextPulse(); return null;
        });
        try { ready.get(5, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        catch (ExecutionException | TimeoutException e) { throw new IllegalStateException(e); }
    }
    /** Ждёт очереди JavaFX и задержек реального ввода, не блокируя поток интерфейса. */
    @Override public void awaitIdle(Duration timeout) throws InterruptedException {
        if (Platform.isFxApplicationThread()) throw new IllegalStateException("UI wait");
        long delay = pendingDelay; pendingDelay = 0;
        // Idle включает настоящую отложенную запись сеанса, как и общий контракт драйвера.
        long settle = delay + ru.cashprediction.core.session.SessionRecorder.DEBOUNCE.toMillis();
        fx(() -> null); Thread.sleep(Math.min(settle + 40, timeout.toMillis())); fx(() -> null);
    }
    /** Читает реальные виджеты на потоке интерфейса. */
    @Override public UiDump dump(String step) { return fx(() -> {
        port.windows.values().stream().filter(WindowHandle::showing).filter(w -> w instanceof FxFormDialog).map(w -> (FxFormDialog) w).forEach(w -> w.printLayoutMetrics(step));
        return dumper.dump(environment.options().selftest(), step);
    }); }
    /** Снимает настоящий экран вместе с открытыми диалогами. */
    @Override public byte[] screenshot(String step) throws IOException {
        return fx(() -> {
            var scene = port.stage.getScene();
            var image = scene.snapshot(null);
            var base = scene.getRoot().localToScreen(scene.getRoot().getBoundsInLocal());
            // Снимки реальных сцен не зависят от перекрывающих окон других приложений.
            for (var window : javafx.stage.Window.getWindows().stream().filter(w -> w != port.stage && w.isShowing() && w.getScene() != null).toList()) {
                var overlay = window.getScene().snapshot(null);
                var location = window.getScene().getRoot().localToScreen(window.getScene().getRoot().getBoundsInLocal());
                int dx = (int) Math.round(location.getMinX() - base.getMinX()), dy = (int) Math.round(location.getMinY() - base.getMinY());
                for (int y = 0; y < overlay.getHeight(); y++) for (int x = 0; x < overlay.getWidth(); x++) {
                    if (dx + x >= 0 && dy + y >= 0 && dx + x < image.getWidth() && dy + y < image.getHeight()) image.getPixelWriter().setArgb(dx + x, dy + y, overlay.getPixelReader().getArgb(x, y));
                }
            }
            return ru.cashprediction.fx.action.PngEncoder.encode(image);
        });
    }

    private void perform(SelfTestCommand command) {
        if (!(command instanceof SelfTestCommand.Hover)) leaveCard();
        switch (command) {
            case SelfTestCommand.Today c -> { if (!environment.clock().today().equals(c.date())) throw new IllegalStateException("--today"); }
            case SelfTestCommand.Size c -> { port.stage.setWidth(c.width() + port.stage.getWidth() - port.stage.getScene().getWidth()); port.stage.setHeight(c.height() + port.stage.getHeight() - port.stage.getScene().getHeight()); }
            case SelfTestCommand.Sample _ -> { menu("file.sample"); pendingDelay = ru.cashprediction.core.session.SessionRecorder.DEBOUNCE.toMillis(); }
            case SelfTestCommand.Save _ -> menu("file.save");
            case SelfTestCommand.Snapshot _ -> menu("recovery.snapshotNow");
            case SelfTestCommand.Menu c -> menu(c.idOrPath());
            case SelfTestCommand.Click c -> click(c.toolbarId());
            case SelfTestCommand.View c -> menu("view." + c.mode().name().toLowerCase(Locale.ROOT));
            case SelfTestCommand.Period c -> menu("view.period." + c.period().name());
            case SelfTestCommand.Filter c -> {
                String id = c.key().equals("whatIfIncome") ? "whatIf.income" : c.key().equals("whatIfExpense") ? "whatIf.expense" : "view.flag." + c.key();
                MenuItem item = port.menus.byId.get(id); if (!(item instanceof CheckMenuItem check)) throw new IllegalStateException(id);
                if (check.isSelected() != c.value()) menu(id);
            }
            case SelfTestCommand.FilterType c -> { TextField field = (TextField) port.main.toolbar.widgets.get("tb.filter"); field.requestFocus(); field.setText(c.text()); pendingDelay = 300; }
            case SelfTestCommand.Key c -> key(c.chord());
            case SelfTestCommand.Fill c -> c.values().forEach((id, value) -> set(fieldMap(c.window()).get(id), value));
            case SelfTestCommand.Field c -> set(fieldMap(c.windowTitle()).values().stream().filter(f -> f.label.getText().equals(c.label())).findFirst().orElseThrow(), c.text());
            case SelfTestCommand.Ok c -> {
                var popup = quick(c.window());
                if (popup != null) popup.fields.values().iterator().next().control.fireEvent(new javafx.event.ActionEvent());
                else { var f = form(c.window()); press(f.buttons.get(f.spec.defaultButtonId())); }
            }
            case SelfTestCommand.Cancel c -> {
                var popup = quick(c.window());
                Node root = popup == null ? form(c.window()).dialog.getDialogPane().getScene().getRoot() : popup.popup.getContent().getFirst();
                root.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            }
            case SelfTestCommand.Button c -> press(form(c.windowTitle()).buttons.values().stream().filter(b -> b.getText().equals(c.label())).findFirst().orElseThrow());
            case SelfTestCommand.Answer c -> {
                var a = port.windows.values().stream().filter(h -> h instanceof FxAlerts && h.showing()).map(h -> (FxAlerts) h).reduce((a1, a2) -> a2).orElseThrow();
                press(a.buttons.values().stream().filter(b -> b.getText().equals(c.buttonText())).findFirst().orElseThrow());
                if (Set.of("clearSnapshots", "crashRecovery").contains(a.spec.purpose())) pendingDelay = ru.cashprediction.core.session.SessionRecorder.DEBOUNCE.toMillis();
            }
            case SelfTestCommand.FieldEnter c -> {
                var f = fieldMap(c.windowTitle()).values().stream().filter(w -> w.label.getText().equals(c.label())).findFirst().orElseThrow();
                f.control.fireEvent(new javafx.event.ActionEvent());
            }
            case SelfTestCommand.ListPick c -> {
                var f = fieldMap(c.windowTitle()).values().stream().filter(w -> w.label.getText().equals(c.label()) || w.spec.kind() == ru.cashprediction.core.ui.form.FieldKind.PREVIEW).findFirst().orElseThrow();
                ListView<?> list = (ListView<?>) f.control; int index = list.getItems().indexOf(c.itemText()); if (index < 0) throw new IllegalStateException(c.itemText());
                list.getSelectionModel().select(index); if (c.activate()) mouse(list, 2);
            }
            case SelfTestCommand.Chooser c -> port.answerChooser(c.path() == null ? null : environment.cashMemory().resolve(c.path()).normalize());
            case SelfTestCommand.Select c -> mouse(cell(c.rowId(), "title"), 1);
            case SelfTestCommand.DoubleClick c -> mouse(cell(c.rowId(), c.columnId()), 2);
            case SelfTestCommand.RowClick c -> mouse(cell(c.rowId(), c.columnId().isEmpty() ? "title" : c.columnId()), 1);
            case SelfTestCommand.QuickEdit c -> {
                Node target;
                target = cell(c.rowId(), "income");
                if (target instanceof TableCell<?, ?> t && (!(t.getGraphic() instanceof javafx.scene.text.Text text) || text.getText().isEmpty())) target = cell(c.rowId(), "expense");
                mouse(target, 2); Platform.runLater(() -> fieldMap("last").values().stream().findFirst().ifPresent(f -> set(f, c.amount())));
            }
            case SelfTestCommand.SliderSet c -> {
                MenuItem item = port.menus.byId.get(c.itemId()); showAncestors(item); Slider slider = (Slider) item.getProperties().get("cp.control"); slider.setValue(c.value());
                slider.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.RIGHT, false, false, false, false));
            }
            case SelfTestCommand.SpinnerSet c -> {
                MenuItem item = find(new ArrayList<>(port.main.bar.getMenus()), c.itemId()); showAncestors(item);
                @SuppressWarnings("unchecked") Spinner<Long> spinner = (Spinner<Long>) item.getProperties().get("cp.control");
                // Ввод проходит редактор и его штатный обработчик, а не скрытый дубль панели инструментов.
                spinner.getEditor().setText(Long.toString(c.value()));
                spinner.getEditor().fireEvent(new javafx.event.ActionEvent());
                pendingDelay = 600;
            }
            case SelfTestCommand.Hover c -> hover(c.target());
            case SelfTestCommand.Context c -> context(c.target());
            case SelfTestCommand.Open c -> {
                if (!c.context().isEmpty()) throw new IllegalStateException("open context");
                menu(switch (c.type()) { case NEW_PLAN_WIZARD -> "file.new"; case PLAN_SETTINGS -> "edit.planSettings"; case RULE_EDITOR -> "edit.addIncome"; case ONE_TIME_EDITOR -> "edit.addOneTime"; case ADJUSTMENT_EDITOR -> "edit.adjust"; case GOAL_CALCULATOR -> "tools.goal"; case CSV_EXPORT -> "file.exportCsv"; default -> throw new IllegalStateException(c.type().name()); });
            }
            case SelfTestCommand.Crash _ -> menu("recovery.simulate.halt");
            case SelfTestCommand.Throw _ -> menu("recovery.simulate.exception");
            case SelfTestCommand.Exit _ -> menu("file.exit");
            default -> throw new UnsupportedOperationException(command.getClass().getSimpleName());
        }
    }

    private void menu(String id) {
        MenuItem contextual = port.contexts.stream().filter(ContextMenu::isShowing).map(c -> find(c.getItems(), id)).filter(Objects::nonNull).findFirst().orElse(null);
        if (contextual != null) {
            if (contextual.isDisable()) throw new IllegalStateException(id);
            contextual.fire(); port.contexts.forEach(ContextMenu::hide); return;
        }
        if (port.windows.values().stream().anyMatch(h -> h.showing() && (h instanceof FxAlerts || h instanceof FxFormDialog f && f.dialog.getModality() != javafx.stage.Modality.NONE))) throw new IllegalStateException("main blocked: " + id);
        MenuItem item = find(new ArrayList<>(port.main.bar.getMenus()), id);
        if (item == null) item = port.menus.byId.get(id);
        if (item == null && id.contains("/")) { List<MenuItem> level = new ArrayList<>(port.main.bar.getMenus()); for (String label : id.split("/")) { item = level.stream().filter(m -> m.getText().equals(label)).findFirst().orElseThrow(); if (item instanceof Menu m) level = m.getItems(); } }
        if (item == null || item.isDisable()) throw new IllegalStateException(id);
        showAncestors(item); item.fire();
        port.main.bar.getMenus().forEach(Menu::hide); port.contexts.forEach(ContextMenu::hide);
    }
    private void showAncestors(MenuItem item) { if (item.getParentMenu() != null) { showAncestors(item.getParentMenu()); item.getParentMenu().show(); } }
    private MenuItem find(List<MenuItem> items, String id) {
        for (MenuItem item : items) { if (id.equals(item.getId())) return item; if (item instanceof Menu m) { MenuItem found = find(m.getItems(), id); if (found != null) return found; } }
        return null;
    }
    private void click(String id) {
        String[] parts = id.split("\\.menu:", 2); Node n = port.main.toolbar.widgets.get(parts[0]);
        if (parts.length == 2 && n instanceof MenuButton m) { m.show(); menu(parts[1]); }
        else if (n instanceof ButtonBase b) press(b); else throw new IllegalStateException(id);
    }
    private void key(KeyChord chord) {
        javafx.scene.Scene scene = javafx.stage.Window.getWindows().stream().filter(javafx.stage.Window::isFocused).map(javafx.stage.Window::getScene).filter(Objects::nonNull).findFirst().orElse(port.stage.getScene());
        // При показе модального окна Windows ещё может сообщать фокус главного окна.
        // Выбираем настоящий верхний диалог, которому пользовательский ввод уже принадлежит.
        for (var handle : port.windows.values()) if (handle.showing()) {
            if (handle instanceof FxFormDialog form && form.dialog.getModality() != javafx.stage.Modality.NONE) scene = form.dialog.getDialogPane().getScene();
            else if (handle instanceof FxAlerts alert) scene = alert.alert.getDialogPane().getScene();
            else if (handle instanceof FxQuickEditPopup popup) scene = popup.popup.getScene();
        }
        Node target = scene.getFocusOwner() == null ? scene.getRoot() : scene.getFocusOwner();
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.valueOf(chord.key()), chord.shift(), chord.ctrl(), chord.alt(), false));
        target.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.valueOf(chord.key()), chord.shift(), chord.ctrl(), chord.alt(), false));
    }
    private FxFormDialog form(String name) { return port.windows.values().stream().filter(h -> h instanceof FxFormDialog f && f.showing() && (name.equals("last") || name.equals(f.dialog.getTitle()) || name.equals(f.header.getText()) || name.equals(f.session.windowId()))).map(h -> (FxFormDialog) h).reduce((a, b) -> b).orElseThrow(); }
    private FxQuickEditPopup quick(String name) {
        var live = port.windows.entrySet().stream().filter(e -> e.getValue().showing()).toList();
        if (live.isEmpty()) return null;
        var last = live.getLast();
        return last.getValue() instanceof FxQuickEditPopup p && (name.equals("last") || name.equals(last.getKey()) || name.equals(p.header.getText())) ? p : null;
    }
    private Map<String, FxFieldWidgets> fieldMap(String name) {
        var live = port.windows.values().stream().filter(WindowHandle::showing).toList();
        if (name.equals("last") && !live.isEmpty() && live.getLast() instanceof FxQuickEditPopup p) return p.fields;
        return form(name).fields;
    }
    private void set(FxFieldWidgets field, String value) {
        if (field == null || field.control.isDisabled() || !field.root.isVisible()) throw new IllegalStateException("field unavailable");
        field.control.requestFocus(); field.setText(value);
        // Самотест задаёт завершённую правку; обычный ввод остаётся сырым до потери фокуса.
        var root = field.control.getScene().getRoot(); root.setFocusTraversable(true); root.requestFocus();
        field.control.requestFocus();
    }
    private static void press(ButtonBase b) { if (b == null || b.isDisabled() || !b.isVisible()) throw new IllegalStateException("button unavailable"); b.fire(); }
    private void select(String id) { int index = row(id); port.main.table.ensureVisible(index); port.main.table.root.getSelectionModel().select(index); port.main.table.root.requestFocus(); }
    private int row(String id) { int index = port.main.table.model.indexOf(id); if (index < 0) for (int i = 0; i < port.main.table.root.getItems().size(); i++) if (port.main.table.model.row(i).rowId().startsWith(id)) { index = i; break; } if (index < 0) throw new IllegalStateException(id); return index; }
    private Node cell(String id, String column) {
        select(id); port.main.root.applyCss(); port.main.root.layout();
        return port.main.table.root.lookupAll(".table-cell").stream().filter(n -> n instanceof TableCell<?, ?> c && c.getIndex() == row(id) && column.equals(c.getTableColumn().getId())).findFirst().orElseThrow();
    }
    private static void mouse(Node node, int count) {
        var screen = node.localToScreen(1, 1);
        node.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, screen.getX(), screen.getY(), MouseButton.PRIMARY, count, false, false, false, false, false, false, false, false, false, true, new PickResult(node, new javafx.geometry.Point3D(1, 1, 0), 0)));
    }
    private void hover(String target) {
        if (target.startsWith("menu:")) {
            leaveCard(); MenuItem item = port.menus.byId.get(target.substring(5)); showAncestors(item);
            Node n = item.getStyleableNode(); if (n == null) throw new IllegalStateException(target); hoveredMenu = n;
            var screen = n.localToScreen(1, 1);
            for (var type : List.of(MouseEvent.MOUSE_ENTERED, MouseEvent.MOUSE_MOVED)) n.fireEvent(new MouseEvent(type, 1, 1, screen.getX(), screen.getY(), MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false, false, new PickResult(n, new javafx.geometry.Point3D(1, 1, 0), 0)));
            pendingDelay = 600;
        }
        else if (target.startsWith("card:")) {
            Node n = port.main.summary.getChildren().stream().filter(w -> target.substring(5).equals(FxUiDumper.id(w))).findFirst().orElseThrow();
            if (hoveredCard != null && hoveredCard != n) hoveredCard.fireEvent(new MouseEvent(MouseEvent.MOUSE_EXITED, 1, 1, 1, 1, MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false, false, null));
            // Команда hover означает наведение, которое работает и без активного окна Windows.
            hoveredCard = n; n.fireEvent(new MouseEvent(MouseEvent.MOUSE_ENTERED, 1, 1, 1, 1, MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false, false, null)); pendingDelay = 350;
        }
        else if (target.startsWith("chart:")) {
            String[] point = target.substring(6).split(","); double x = Double.parseDouble(point[0]), y = Double.parseDouble(point[1]);
            var screen = port.main.chart.localToScreen(x, y);
            port.main.chart.fireEvent(new MouseEvent(MouseEvent.MOUSE_MOVED, x, y, screen.getX(), screen.getY(), MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false, false, new PickResult(port.main.chart, new javafx.geometry.Point3D(x, y, 0), 0)));
        } else throw new UnsupportedOperationException(target);
    }
    private void leaveCard() {
        if (hoveredMenu != null) {
            hoveredMenu.fireEvent(new MouseEvent(MouseEvent.MOUSE_EXITED, 1, 1, 1, 1, MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false, false, null));
            hoveredMenu = null;
        }
        if (hoveredCard == null) return;
        hoveredCard.fireEvent(new MouseEvent(MouseEvent.MOUSE_EXITED, 1, 1, 1, 1, MouseButton.NONE, 0, false, false, false, false, false, false, false, false, false, false, null));
        hoveredCard = null;
    }
    private void context(String target) {
        port.contexts.forEach(ContextMenu::hide);
        Node node; double x = 100, y = 100;
        if (target.startsWith("row:")) node = cell(target.substring(4), "title");
        else if (target.startsWith("total:")) node = cell(target.substring(6), "title");
        else if (target.equals("pastHeader")) node = cell("past@group", "title");
        else if (target.startsWith("card:")) node = port.main.summary.getChildren().stream().filter(w -> target.substring(5).equals(FxUiDumper.id(w))).findFirst().orElseThrow();
        else if (target.startsWith("chart:")) { node = port.main.chart; String[] point = target.substring(6).split(","); x = Double.parseDouble(point[0]); y = Double.parseDouble(point[1]); }
        else if (target.startsWith("preview:")) {
            String[] parts = target.substring(8).split(":");
            ListView<?> list = (ListView<?>) fieldMap(parts[0]).values().stream().filter(f -> f.spec.kind() == ru.cashprediction.core.ui.form.FieldKind.PREVIEW).findFirst().orElseThrow().control;
            list.getSelectionModel().select(Integer.parseInt(parts[1])); node = list;
        }
        else throw new UnsupportedOperationException(target);
        // JavaFX: ContextMenuEvent → Swing: MouseEvent.popupTrigger → Web: contextmenu
        var screen = node.localToScreen(x, y);
        node.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, x, y, screen.getX(), screen.getY(), false, new PickResult(node, new javafx.geometry.Point3D(x, y, 0), 0)));
    }
    private static <T> T fx(Supplier<T> action) {
        if (Platform.isFxApplicationThread()) return action.get();
        FutureTask<T> task = new FutureTask<>(action::get); Platform.runLater(task);
        try { return task.get(10, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
