package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.selftest.*;
import ru.cashprediction.core.ui.selftest.paint.PaintCaptureRequest;
import ru.cashprediction.core.ui.selftest.paint.PaintObservation;
import ru.cashprediction.core.ui.selftest.paint.WidgetCapture;

/** Драйвер настоящего Swing-интерфейса, не вызывающий команды бизнес-контроллера вместо виджетов. */
public final class SwingUiDriver implements UiDriver {
    private final SwingUiPort port;
    private final SwingUiDumper dumper;
    private final Robot robot;
    private long settleMillis;
    private final AppController controller;
    private boolean startupReady;
    private final AtomicBoolean capturing = new AtomicBoolean();

    /** Создаёт драйвер интерактивного рабочего стола. */
    public SwingUiDriver(SwingUiPort port, AppController controller, String scenario) throws AWTException {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Real Swing widgets require a desktop");
        this.port = port; this.controller = controller; dumper = new SwingUiDumper(port, controller, scenario); robot = new Robot(); robot.setAutoDelay(25);
    }
    /** Возвращает реальный вид клиента. */
    @Override public ClientKind client() { return ClientKind.SWING; }

    /** Исполняет шаг через виджеты или физическую клавиатуру. */
    @Override public void execute(SelfTestCommand command) throws Exception {
        settleMillis = command instanceof SelfTestCommand.FilterType || command instanceof SelfTestCommand.SpinnerSet || command instanceof SelfTestCommand.Hover ? 720 : 30;
        if (command instanceof SelfTestCommand.Hover hover) { hoverActual(hover.target()); return; }
        // Перед следующим действием указатель действительно покидает наведённый компонент.
        Point neutral = edt(() -> {
            if (port.frame == null || !port.frame.isShowing()) return null;
            Point point = port.frame.root.getLocationOnScreen();
            point.translate(port.frame.root.getWidth() - 1, port.frame.root.getHeight() - 1); return point;
        });
        if (neutral != null) { robot.mouseMove(neutral.x, neutral.y); robot.waitForIdle(); }
        if (command instanceof SelfTestCommand.Key key) { key(key.chord()); return; }
        if (command instanceof SelfTestCommand.Field field) {
            typeCommitted(edt(() -> field(field.windowTitle(), field.label())), field.text()); return;
        }
        if (command instanceof SelfTestCommand.Fill fill) {
            SwingFormDialog form = edt(() -> form(fill.window()));
            for (var entry : fill.values().entrySet()) typeCommitted(edt(() -> binding(form, entry.getKey())), entry.getValue());
            return;
        }
        edt(() -> {
            MenuSelectionManager.defaultManager().clearSelectedPath();
            switch (command) {
                case SelfTestCommand.Today date -> { if (!date.date().equals(port.environment.clock().today())) throw new IllegalStateException("today must match --today"); }
                case SelfTestCommand.Size size -> { Insets border = port.frame.getInsets(); port.frame.setSize(size.width() + border.left + border.right, size.height() + border.top + border.bottom); port.frame.validate(); }
                case SelfTestCommand.Sample ignored -> { menu("file.sample"); settleMillis = ru.cashprediction.core.session.SessionRecorder.DEBOUNCE.toMillis(); }
                case SelfTestCommand.Menu menu -> menu(menu.idOrPath());
                case SelfTestCommand.Menus menus -> menu(menus.path());
                case SelfTestCommand.Click click -> click(click.toolbarId());
                case SelfTestCommand.View view -> menu(view.mode() == ru.cashprediction.core.document.ViewMode.TABLE ? "view.table" : "view.chart");
                case SelfTestCommand.Period period -> menu("view.period." + period.period().name());
                case SelfTestCommand.Filter filter -> {
                    String id = switch (filter.key()) { case "whatIfIncome" -> "whatIf.income"; case "whatIfExpense" -> "whatIf.expense"; default -> "view.flag." + filter.key(); };
                    if (filter.key().equals("pastExpanded")) { JComponent group = null; var table = port.frame.table.adapter.source(); for (int i = 0; i < table.rowCount(); i++) if (table.row(i).kind() == ru.cashprediction.core.ui.view.table.RowKind.PAST_HEADER) { String text = table.row(i).cells().getFirst(); boolean expanded = text.startsWith("▾"); if (expanded != filter.value()) rowMouse(table.row(i).rowId(), "date", 1); break; } }
                    else { JComponent item = find(port.frame.menus, id); if (!(item instanceof JCheckBoxMenuItem check)) throw new IllegalArgumentException("Filter " + filter.key()); if (check.isSelected() != filter.value()) press(check); }
                }
                case SelfTestCommand.FilterType filter -> { port.frame.toolbar.filter().requestFocusInWindow(); port.frame.toolbar.filter().setText(filter.text()); }
                case SelfTestCommand.Select select -> select(select.rowId());
                case SelfTestCommand.DoubleClick click -> rowMouse(click.rowId(), click.columnId(), 2);
                case SelfTestCommand.RowClick click -> rowMouse(click.rowId(), click.columnId(), 1);
                case SelfTestCommand.Context context -> context(context.target());
                case SelfTestCommand.Hover hover -> hover(hover.target());
                case SelfTestCommand.Field field -> type(field(field.windowTitle(), field.label()), field.text());
                case SelfTestCommand.Fill fill -> { SwingFormDialog form = form(fill.window()); for (var entry : fill.values().entrySet()) type(binding(form, entry.getKey()), entry.getValue()); }
                case SelfTestCommand.Button button -> button(form(button.windowTitle()), button.label());
                case SelfTestCommand.Ok ok -> { SwingFormDialog form = form(ok.window()); JButton button = form.dialog.getRootPane().getDefaultButton(); if (button == null) throw new IllegalStateException("No default button"); press(button); }
                case SelfTestCommand.Cancel cancel -> { SwingFormDialog form = form(cancel.window()); form.dialog.dispatchEvent(new WindowEvent(form.dialog, WindowEvent.WINDOW_CLOSING)); }
                case SelfTestCommand.Answer answer -> answer(answer.buttonText());
                case SelfTestCommand.Chooser chooser -> port.answerChooser(chooser.path() == null ? null : port.environment.cashMemory().resolve(chooser.path()).normalize());
                case SelfTestCommand.Save ignored -> menu("file.save");
                case SelfTestCommand.Snapshot ignored -> menu("recovery.snapshotNow");
                case SelfTestCommand.Exit ignored -> menu("file.exit");
                case SelfTestCommand.Throw ignored -> menu("recovery.simulate.exception");
                case SelfTestCommand.Crash ignored -> menu("recovery.simulate.halt");
                case SelfTestCommand.SliderSet slider -> { JComponent item = find(port.frame.menus, slider.itemId()); if (item == null) throw new IllegalArgumentException(slider.itemId()); JSlider widget = SwingUiDumper.first(item, JSlider.class); widget.setValueIsAdjusting(true); widget.setValue(slider.value()); widget.setValueIsAdjusting(false); }
                case SelfTestCommand.SpinnerSet spinner -> { JComponent item = find(port.frame.menus, spinner.itemId()); if (item == null) throw new IllegalArgumentException(spinner.itemId()); SwingUiDumper.first(item, JSpinner.class).setValue(spinner.value()); }
                case SelfTestCommand.ListPick pick -> { SwingFieldWidgets.Binding binding = field(pick.windowTitle(), pick.label()); if (!(binding.input instanceof JList<?> list)) throw new IllegalArgumentException("Not a list"); int index = -1; for (int i = 0; i < list.getModel().getSize(); i++) if (list.getModel().getElementAt(i) instanceof Option option && (option.text().equals(pick.itemText()) || option.value().equals(pick.itemText()))) index = i; if (index < 0) throw new IllegalArgumentException("No list item " + pick.itemText()); list.setSelectedIndex(index); if (pick.activate()) list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, 4, list.getCellBounds(index, index).y + 2, 2, false, MouseEvent.BUTTON1)); }
                case SelfTestCommand.FieldEnter enter -> { SwingFieldWidgets.Binding binding = field(enter.windowTitle(), enter.label()); if (binding.input instanceof JTextField text) text.postActionEvent(); else if (binding.input instanceof JSpinner spinner) ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().postActionEvent(); else throw new IllegalArgumentException("No field Enter"); }
                case SelfTestCommand.QuickEdit edit -> { rowMouse(edit.rowId(), "income", 2); SwingUtilities.invokeLater(() -> type(binding(form("last"), "amount"), edit.amount())); }
                case SelfTestCommand.Open open -> {
                    if (!open.context().isEmpty()) throw new UnsupportedOperationException("Open context requires user gestures");
                    menu(switch (open.type()) { case NEW_PLAN_WIZARD -> "file.new"; case PLAN_SETTINGS -> "edit.planSettings"; case RULE_EDITOR -> "edit.addIncome"; case ONE_TIME_EDITOR -> "edit.addOneTime"; case ADJUSTMENT_EDITOR -> "edit.adjust"; case GOAL_CALCULATOR -> "tools.goal"; case CSV_EXPORT -> "file.exportCsv"; default -> throw new UnsupportedOperationException(open.type().name()); });
                }
                default -> throw new UnsupportedOperationException(command.getClass().getSimpleName());
            }
            return null;
        });
    }

    private void key(KeyChord chord) throws Exception {
        edt(() -> {
            Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            Component target = focused;
            if (!port.alerts.isEmpty()) target = new ArrayList<>(port.alerts.values()).getLast().dialog.getRootPane();
            else if (!port.forms.isEmpty()) {
                SwingFormDialog form = new ArrayList<>(port.forms.values()).getLast();
                if (target == null || !SwingUtilities.isDescendingFrom(target, form.content)) target = form.fields.values().stream().flatMap(List::stream).filter(b -> b.root.isVisible() && b.input.isEnabled()).map(b -> (Component) b.input).findFirst().orElse(form.dialog.getRootPane());
            }
            if (target == null && port.frame != null) target = port.frame.table.table;
            if (target == null) throw new IllegalStateException("No keyboard target");
            for (KeyEvent event : keyEvents(target, chord)) {
                // Та же цепочка физического события: единственный мост, затем штатные InputMap/ActionMap виджета.
                if (!port.keys.dispatchKeyEvent(event)) SwingUtilities.processKeyBindings(event);
            }
            return null;
        });
    }

    /** Полный цикл клавиши: у одиночного Alt модификатор присутствует при нажатии и снят при отпускании. */
    static List<KeyEvent> keyEvents(Component target, KeyChord chord) {
        int code = SwingKeyBridge.keyCode(chord.key());
        int modifiers = (chord.ctrl() ? KeyEvent.CTRL_DOWN_MASK : 0) | (chord.alt() ? KeyEvent.ALT_DOWN_MASK : 0) | (chord.shift() ? KeyEvent.SHIFT_DOWN_MASK : 0);
        int pressed = code == KeyEvent.VK_ALT ? modifiers | KeyEvent.ALT_DOWN_MASK : modifiers;
        int released = code == KeyEvent.VK_ALT ? modifiers & ~KeyEvent.ALT_DOWN_MASK : modifiers;
        long time = System.currentTimeMillis();
        return List.of(new KeyEvent(target, KeyEvent.KEY_PRESSED, time, pressed, code, KeyEvent.CHAR_UNDEFINED),
                new KeyEvent(target, KeyEvent.KEY_RELEASED, time + 1, released, code, KeyEvent.CHAR_UNDEFINED));
    }
    private void menu(String id) {
        JComponent widget = id.startsWith("ctx.") && port.lastContext != null ? find(port.lastContext, id) : find(port.frame.menus, id);
        if (!(widget instanceof JMenuItem item)) throw new IllegalArgumentException("No menu " + id);
        if (item instanceof JMenu menu) { MenuSelectionManager.defaultManager().setSelectedPath(path(menu, true)); return; }
        MenuSelectionManager.defaultManager().setSelectedPath(path(item, false)); activateMenuItem(item);
    }
    /** Повторяет порядок штатного BasicMenuItemUI: снять захват меню до передачи action кнопки. */
    static void activateMenuItem(JMenuItem item) {
        if (!item.isEnabled()) throw new IllegalStateException("Disabled " + item.getText());
        MenuSelectionManager.defaultManager().clearSelectedPath();
        press(item);
    }
    private static MenuElement[] path(JMenuItem item, boolean open) {
        List<MenuElement> reverse = new ArrayList<>(); Component current = item;
        while (current != null) {
            if (current instanceof MenuElement element) reverse.add(element);
            current = current instanceof JPopupMenu popup ? popup.getInvoker() : current.getParent();
            if (current instanceof JFrame) break;
        }
        java.util.Collections.reverse(reverse);
        if (open && item instanceof JMenu menu) reverse.add(menu.getPopupMenu()); return reverse.toArray(MenuElement[]::new);
    }
    private void click(String id) { JComponent component = port.frame.toolbar.widget(id); if (component instanceof AbstractButton button) press(button); else if (component != null && component.getClientProperty("cp.main") instanceof AbstractButton button) press(button); else throw new IllegalArgumentException("No toolbar action " + id); }
    private void select(String rowId) { int row = port.frame.table.adapter.source().indexOf(rowId); if (row < 0) throw new IllegalArgumentException("Hidden row " + rowId); port.frame.table.table.setRowSelectionInterval(row, row); port.frame.table.table.scrollRectToVisible(port.frame.table.table.getCellRect(row, 0, true)); port.frame.table.table.requestFocusInWindow(); }
    private void rowMouse(String rowId, String columnId, int count) {
        select(rowId); JTable table = port.frame.table.table; int row = table.getSelectedRow(), col = 0;
        for (int i = 0; i < table.getColumnCount(); i++) if (table.getColumnModel().getColumn(i).getIdentifier().equals(columnId)) col = i;
        Rectangle cell = table.getCellRect(row, col, true); table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0, cell.x + 4, cell.y + 4, count, false, MouseEvent.BUTTON1));
    }
    private void context(String target) {
        if (target.startsWith("preview:")) {
            String[] parts = target.split(":", 3); SwingFormDialog form = form(parts[1]); int index = Integer.parseInt(parts[2]);
            SwingFieldWidgets.Binding binding = form.fields.values().stream().flatMap(List::stream).filter(b -> b.spec.kind() == FieldKind.PREVIEW && b.input instanceof JList<?>).findFirst().orElseThrow();
            JList<?> list = (JList<?>) binding.input; list.setSelectedIndex(index); Rectangle cell = list.getCellBounds(index, index);
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, 4, cell.y + 2, 1, true, MouseEvent.BUTTON3));
        }
        else if (target.startsWith("row:") || target.startsWith("total:")) { select(target.substring(target.indexOf(':') + 1)); JTable table = port.frame.table.table; Rectangle cell = table.getCellRect(table.getSelectedRow(), 2, true); table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, cell.x + 4, cell.y + 4, 1, true, MouseEvent.BUTTON3)); }
        else if (target.equals("pastHeader")) { for (int i = 0; i < port.frame.table.adapter.getRowCount(); i++) if (port.frame.table.adapter.source().row(i).kind() == ru.cashprediction.core.ui.view.table.RowKind.PAST_HEADER) { JTable table = port.frame.table.table; Rectangle cell = table.getCellRect(i, 2, true); table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, cell.x + 4, cell.y + 4, 1, true, MouseEvent.BUTTON3)); return; } throw new IllegalStateException("No past header"); }
        else if (target.startsWith("card:")) { JComponent card = find(port.frame.summary, target.substring(5)); if (card == null) throw new IllegalArgumentException(target); card.dispatchEvent(new MouseEvent(card, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, 8, 8, 1, true, MouseEvent.BUTTON3)); }
        else if (target.startsWith("chart:")) { String[] xy = target.substring(6).split(","); port.frame.chart.dispatchEvent(new MouseEvent(port.frame.chart, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0, Integer.parseInt(xy[0]), Integer.parseInt(xy[1]), 1, true, MouseEvent.BUTTON3)); }
        else throw new UnsupportedOperationException("Context " + target);
    }
    private void hover(String target) {
        JComponent widget = target.startsWith("menu:") ? find(port.frame.menus, target.substring(5)) : target.startsWith("card:") ? find(port.frame.summary, target.substring(5)) : null;
        if (widget != null) widget.dispatchEvent(new MouseEvent(widget, MouseEvent.MOUSE_ENTERED, System.currentTimeMillis(), 0, 4, 4, 0, false));
        else if (target.startsWith("chart:")) { String[] xy = target.substring(6).split(","); port.frame.chart.dispatchEvent(new MouseEvent(port.frame.chart, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, Integer.parseInt(xy[0]), Integer.parseInt(xy[1]), 0, false)); }
        else throw new UnsupportedOperationException("Hover " + target);
    }
    /** Наводит физический указатель на видимый компонент, включая настоящий пункт раскрытого меню. */
    private void hoverActual(String target) throws Exception {
        edt(() -> { port.frame.toFront(); port.frame.requestFocus(); return null; });
        JComponent component = edt(() -> target.startsWith("menu:") ? find(port.frame.menus, target.substring(5))
                : target.startsWith("card:") ? find(port.frame.summary, target.substring(5)) : port.frame.chart);
        Point point = edt(() -> {
            JComponent widget = target.startsWith("menu:") ? find(port.frame.menus, target.substring(5))
                    : target.startsWith("card:") ? find(port.frame.summary, target.substring(5)) : null;
            if (widget instanceof JMenuItem item) MenuSelectionManager.defaultManager().setSelectedPath(path(item, false));
            if (widget != null) {
                Point location = widget.getLocationOnScreen(); location.translate(widget.getWidth() / 2, widget.getHeight() / 2); return location;
            }
            if (target.startsWith("chart:")) {
                String[] xy = target.substring(6).split(","); Point location = port.frame.chart.getLocationOnScreen();
                location.translate(Integer.parseInt(xy[0]), Integer.parseInt(xy[1])); return location;
            }
            throw new UnsupportedOperationException("Hover " + target);
        });
        robot.mouseMove(point.x, point.y); robot.waitForIdle();
        // waitForIdle опустошает EDT, но не гарантирует доставку нативного события мыши Windows.
        // Проверяем реальное попадание в компонент, не вызывая его обработчики или показ попапа напрямую.
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!edt(() -> component != null && component.isShowing() && component.getMousePosition(true) != null)) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Physical hover did not reach " + target
                    + "; requested=" + point + "; actual=" + MouseInfo.getPointerInfo().getLocation());
            robot.mouseMove(point.x, point.y); robot.waitForIdle(); Thread.sleep(20);
        }
    }
    private SwingFormDialog form(String name) { List<SwingFormDialog> forms = new ArrayList<>(port.forms.values()); if (name.equals("last") && !forms.isEmpty()) return forms.getLast(); return forms.stream().filter(f -> f.session.windowId().equals(name) || f.dialog.getTitle().equals(name) || SwingUiDumper.plain(f.header).equals(name) || f.spec.windowType().name().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException("No form " + name)); }
    private SwingFieldWidgets.Binding field(String window, String label) { return binding(form(window), label); }
    private SwingFieldWidgets.Binding binding(SwingFormDialog form, String id) { return form.fields.values().stream().flatMap(List::stream).filter(b -> b.spec.id().equals(id) || b.label.getText().equals(id + ":") || b.input instanceof AbstractButton button && button.getText().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("No field " + id)); }
    private void type(SwingFieldWidgets.Binding binding, String value) {
        if (!binding.input.isEnabled() || !binding.root.isVisible()) throw new IllegalStateException("Field disabled " + binding.spec.id());
        switch (binding.input) {
            case JTextComponent text -> text.setText(value);
            case JSpinner spinner -> ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setText(value);
            case JComboBox<?> combo -> { Object option = binding.options.stream().filter(o -> o.value().equals(value) || o.text().equals(value)).findFirst().orElse(null); combo.setSelectedItem(option == null ? value : option); }
            case JCheckBox check -> { if (check.isSelected() != Boolean.parseBoolean(value)) press(check); }
            default -> { AbstractButton selected = binding.radioPeers().stream().filter(b -> value.equals(b.getClientProperty("cp.value")) || b.getText().equals(value)).findFirst().orElseThrow(() -> new IllegalArgumentException("No radio value " + value)); press(selected); }
        }
    }
    /** Завершает правку настоящим переносом фокуса, чтобы сработали штатные слушатели поля. */
    private void typeCommitted(SwingFieldWidgets.Binding binding, String value) throws Exception {
        Component input = edt(() -> binding.input instanceof JSpinner spinner
                ? ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField()
                : binding.input instanceof JComboBox<?> combo && combo.isEditable()
                ? combo.getEditor().getEditorComponent() : binding.input);
        edt(() -> { MenuSelectionManager.defaultManager().clearSelectedPath(); return null; });
        if (input instanceof JTextComponent) awaitFocus(input);
        edt(() -> { type(binding, value); return null; });
        robot.waitForIdle(); edt(() -> null);
        Component commitTarget = edt(() -> {
            SwingFormDialog form = port.forms.values().stream().filter(f -> SwingUtilities.isDescendingFrom(input, f.content)).findFirst().orElseThrow();
            // Фокус получает настоящий доступный контрол, а не служебная панель BoxLayout.
            return commitFocusTarget(form);
        });
        if (input instanceof JTextComponent) awaitFocus(commitTarget);
        robot.waitForIdle(); edt(() -> null);
    }
    /** Ждёт физического события фокуса; один flush EDT не гарантирует завершения нативного перехода. */
    private void awaitFocus(Component target) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!edt(() -> {
            if (target.isFocusOwner()) return true;
            if (!target.isShowing() || !target.isEnabled() || !target.isFocusable())
                throw new IllegalStateException("Focus target unavailable: " + target);
            Window window = SwingUtilities.getWindowAncestor(target);
            if (window != null && !window.isFocused()) { window.toFront(); window.requestFocus(); }
            target.requestFocusInWindow();
            return false;
        })) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Focus transition did not settle: target=" + target
                    + " owner=" + edt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()));
            Thread.sleep(20);
        }
        edt(() -> null);
    }
    /** Выбирает живую кнопку для штатного focusLost, не нажимая её и не вызывая commit вручную. */
    static Component commitFocusTarget(SwingFormDialog form) {
        // Быстрая правка по спецификации не имеет кнопок: focusLost остаётся настоящим событием AWT.
        if (form.spec.buttons().isEmpty() && form.content.isFocusable()) return form.content;
        return form.spec.buttons().reversed().stream().map(b -> form.buttons.get(b.id()))
                .filter(b -> b != null && b.isVisible() && b.isEnabled() && b.isFocusable()).findFirst()
                .orElseThrow(() -> new IllegalStateException("No enabled focus target in " + form.session.windowId()));
    }
    private void button(SwingFormDialog form, String text) { press(form.buttons.entrySet().stream().filter(e -> e.getKey().equals(text) || e.getValue().getText().equals(text)).map(MapEntry -> MapEntry.getValue()).findFirst().orElseThrow(() -> new IllegalArgumentException("No button " + text))); }
    private void answer(String text) { for (SwingAlerts alert : new ArrayList<>(port.alerts.values()).reversed()) for (var entry : alert.buttons.entrySet()) if (entry.getKey().equals(text) || entry.getValue().getText().equals(text)) { press(entry.getValue()); return; } throw new IllegalArgumentException("No alert answer " + text); }
    private static void press(AbstractButton button) { if (!button.isEnabled()) throw new IllegalStateException("Disabled " + button.getText()); button.doClick(0); }
    static JComponent find(Container root, String id) { if (root instanceof JComponent c && id.equals(c.getClientProperty("cp.id"))) return c; Component[] children = root instanceof JMenu menu ? menu.getMenuComponents() : root.getComponents(); for (Component child : children) if (child instanceof Container container) { JComponent found = find(container, id); if (found != null) return found; } return null; }

    /** Даёт EDT обработать события и модельные задержки фильтра/спиннера. */
    @Override public void awaitIdle(Duration timeout) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Cannot wait on EDT");
        long deadline = System.nanoTime() + timeout.toNanos();
        Thread.sleep(Math.min(settleMillis, Math.max(0, timeout.toMillis()))); settleMillis = 30;
        robot.waitForIdle(); edt(() -> null); edt(() -> null);
        while (true) {
            startupReady = edt(() -> {
                var recorder = controller.recorder();
                if (port.exited || recorder != null && (!recorder.isEnabled() || recorder.isClosed())) return true;
                var status = controller.state().recorder();
                boolean questionVisible = port.alerts.values().stream()
                        .anyMatch(a -> a.showing() && startupDecision(a.spec.purpose()));
                if (snapshotWaitNotRequired(status, questionVisible)) return true;
                // PENDING_RESTORE без вопроса означает работающую цепочку показа,
                // а не готовый интерфейс: ждём настоящего завершения восстановления.
                if (status == RecorderStatus.PENDING_RESTORE) return false;
                if (recorder == null && port.alerts.values().stream().anyMatch(a -> a.showing() && startupDecision(a.spec.purpose()))) return true;
                if (recorder == null || !recorder.isStarted()) return false;
                return startupStoresReady(recorder.stores().size(), controller.state().stores())
                        && port.forms.values().stream().allMatch(SwingFormDialog::showing);
            });
            if (startupReady) break;
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Snapshot status did not settle");
            Thread.sleep(20);
        }
        edt(() -> null);
    }

    /** Барьер ждёт фактический результат каждого хранилища, включая явную ошибку, не сочиняя успешный статус. */
    static boolean startupStoresReady(int expected, List<ru.cashprediction.core.session.StoreStatus> stores) {
        return stores.size() == expected && stores.stream().allMatch(s -> s.savedAt() != null || !s.message().isEmpty());
    }
    /** До ответа на восстановление и во втором экземпляре запись намеренно не запускается. */
    static boolean snapshotWaitNotRequired(RecorderStatus status, boolean questionVisible) {
        return status == RecorderStatus.DISABLED_SECOND_INSTANCE
                || status == RecorderStatus.PENDING_RESTORE && questionVisible;
    }
    /** Видимый вопрос запуска должен получить ответ до установки регистратора. */
    static boolean startupDecision(String purpose) {
        return "crashRecovery".equals(purpose) || "alreadyRunning".equals(purpose)
                || "restoreReport".equals(purpose) || "recorderNotStarted".equals(purpose)
                || "loadDiagnostics".equals(purpose);
    }
    /** Снимает настоящий дамп в EDT. */
    @Override public UiDump dump(String step) { try { return edt(() -> dumper.dump(step)); } catch (Exception e) { throw new IllegalStateException(e); } }

    /**
     * Возвращает только поддержанную пару; нынешний стандартный root не имеет painter hooks.
     * Диагностический raw/PNG сохраняется в UnsupportedCapture вместо ложного успешного commit.
     * @param request идентичность опыта и общий монотонный дедлайн
     * @return подтверждённая пара, когда все необходимые hooks действительно подключены
     * @throws Exception при неподдержанном захвате, ошибке или истечении дедлайна
     */
    @Override public WidgetCapture capture(PaintCaptureRequest request) throws Exception {
        return captureDiagnostic(request).requireSupported(request);
    }

    /**
     * Читает raw и обе границы геометрии на EDT, а экран Robot снимает на вызывающем worker.
     * Журнал попытки не импортирует значки и не объявляет отсутствующие paint scopes завершёнными.
     * @param request запрос опыта, включая намерения физического ввода
     * @return фактическая диагностическая попытка, не разрешение на запись успешного commit
     * @throws Exception при недоступном root, вводе, дедлайне или ошибке снимка
     */
    public CaptureResult captureDiagnostic(PaintCaptureRequest request) throws Exception {
        Objects.requireNonNull(request); captureWorker(); remaining(request.deadlineNanos());
        if (!port.environment.options().isSelftest()) throw new IllegalStateException("capture requires selftest");
        if (!capturing.compareAndSet(false, true)) throw new IllegalStateException("capture already running");
        CaptureTransaction[] opened = new CaptureTransaction[1];
        try {
            CaptureTransaction transaction = captureEdt(request.deadlineNanos(), () -> {
                if (port.frame == null || !port.frame.root.isShowing()) throw new UnsupportedOperationException("main capture root unavailable");
                JComponent root = port.frame.root;
                var value = new CaptureTransaction(root, () -> port.frame == null ? null : port.frame.root,
                        r -> dumper.dump(r.step()), () -> captureGeometry(root));
                opened[0] = value; return value;
            });
            prepareCaptureInput(request, transaction);
            return captureBracket(request, transaction, robot::createScreenCapture);
        } finally {
            // Очистка идёт после уже начавшегося задания; новый capture не обгоняет позднюю установку.
            SwingUtilities.invokeLater(() -> {
                try { if (opened[0] != null) opened[0].close(); }
                finally { capturing.set(false); }
            });
        }
    }

    /** Физически уводит указатель, наводит целевую карточку и при необходимости проходит настоящий Tab. */
    private void prepareCaptureInput(PaintCaptureRequest request, CaptureTransaction transaction) throws Exception {
        JComponent target = captureEdt(request.deadlineNanos(), () -> {
            JComponent active = null;
            for (var entry : request.cardStates().entrySet()) {
                JComponent card = find(port.frame.summary, entry.getKey());
                if (card == null || !card.isShowing()) throw new UnsupportedOperationException("capture card unavailable: " + entry.getKey());
                if (entry.getValue() != PaintCaptureRequest.CardState.NORMAL) active = card;
            }
            port.frame.toFront(); port.frame.requestFocus(); return active;
        });
        moveCapturePointer(request, transaction, captureEdt(request.deadlineNanos(), () -> {
            Point point = transaction.root.getLocationOnScreen();
            point.translate(transaction.root.getWidth() - 5, transaction.root.getHeight() - 5); return point;
        }));
        boolean focus = request.cardStates().containsValue(PaintCaptureRequest.CardState.FOCUS);
        if (target != null && !focus) {
            moveCapturePointer(request, transaction, captureEdt(request.deadlineNanos(), () -> {
                Point point = target.getLocationOnScreen(); point.translate(target.getWidth() / 2, target.getHeight() / 2); return point;
            }));
        }
        for (int count = 0; count < 128; count++) {
            boolean reached = captureEdt(request.deadlineNanos(), () -> {
                Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                if (focus) return owner != null && (owner == target || SwingUtilities.isDescendingFrom(owner, target)) && owner.isFocusOwner();
                return owner == null || !SwingUtilities.isDescendingFrom(owner, port.frame.summary);
            });
            if (reached) return;
            captureEdt(request.deadlineNanos(), () -> { transaction.input.armKey(); return null; });
            try { robot.keyPress(KeyEvent.VK_TAB); }
            finally { robot.keyRelease(KeyEvent.VK_TAB); }
            awaitCaptureInput(request, transaction);
        }
        throw new UnsupportedOperationException("physical card focus traversal unavailable");
    }

    /** Подтверждает движение только доставленным событием, совпадающим с физической точкой ОС. */
    private void moveCapturePointer(PaintCaptureRequest request, CaptureTransaction transaction, Point requested) throws Exception {
        Point destination = captureEdt(request.deadlineNanos(), () -> {
            PointerInfo pointer = MouseInfo.getPointerInfo();
            Point point = new Point(requested);
            if (pointer != null && pointer.getLocation().equals(point)) point.translate(-2, 0);
            transaction.input.armPointer(point); return point;
        });
        remaining(request.deadlineNanos()); robot.mouseMove(destination.x, destination.y);
        awaitCaptureInput(request, transaction);
    }

    /** Ждёт подтверждение события в пределах общего дедлайна, не блокируя EDT и не вызывая synthetic dispatch. */
    private static void awaitCaptureInput(PaintCaptureRequest request, CaptureTransaction transaction) throws Exception {
        while (!captureEdt(request.deadlineNanos(), transaction.input::acknowledged))
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining(request.deadlineNanos()), TimeUnit.MILLISECONDS.toNanos(5)));
    }

    /** Реальная неизменяемая граница root; Rectangle не отдаётся наружу по ссылке. */
    record CaptureGeometry(Rectangle rectangle, PaintObservation.Transform deviceTransform, boolean showing) {
        /** Копирует измеренные координаты и проверяет ненулевой размер. */
        CaptureGeometry {
            rectangle = new Rectangle(rectangle); Objects.requireNonNull(deviceTransform);
            if (rectangle.width <= 0 || rectangle.height <= 0) throw new IllegalArgumentException("capture root size");
        }
        /** Возвращает копию экранного прямоугольника. */
        @Override public Rectangle rectangle() { return new Rectangle(rectangle); }
    }

    /** Измеряет экранную точку и реальный device transform на EDT, без целевого профиля или округления масштаба. */
    private static CaptureGeometry captureGeometry(JComponent root) {
        Point point = root.getLocationOnScreen();
        var config = root.getGraphicsConfiguration();
        var transform = config == null ? new java.awt.geom.AffineTransform() : config.getDefaultTransform();
        double[] values = new double[6]; transform.getMatrix(values);
        return new CaptureGeometry(new Rectangle(point.x, point.y, root.getWidth(), root.getHeight()),
                new PaintObservation.Transform(values[0], values[1], values[2], values[3], values[4], values[5]), root.isShowing());
    }

    /** Снимок worker; тестовый backend не считается доказательством native raster. */
    @FunctionalInterface interface ScreenCapture {
        /** Снимает исходный экранный прямоугольник вне EDT. */
        BufferedImage capture(Rectangle rectangle) throws Exception;
    }

    /** Выполняет один bracket; для hooked root получает реальный paint, не объявляя его census полным. */
    static CaptureResult captureBracket(PaintCaptureRequest request, CaptureTransaction transaction, ScreenCapture screen) throws Exception {
        captureWorker(); Objects.requireNonNull(screen);
        CaptureGeometry before = captureEdt(request.deadlineNanos(), () -> transaction.prepare(request));
        remaining(request.deadlineNanos());
        BufferedImage image = Objects.requireNonNull(screen.capture(before.rectangle()));
        remaining(request.deadlineNanos());
        byte[] png;
        try (var bytes = new ByteArrayOutputStream(); var output = new MemoryCacheImageOutputStream(bytes)) {
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
            output.flush(); png = bytes.toByteArray();
        }
        return captureEdt(request.deadlineNanos(), () -> transaction.finish(request, before, image, png));
    }

    /** Диагностическая пара с защитой PNG; неподдержанная запись не превращается в WidgetCapture. */
    public record CaptureResult(UiDump raw, byte[] png, PaintObservation observation) {
        /** Копирует оригинальный PNG и проверяет идентичность raw/observation. */
        public CaptureResult {
            Objects.requireNonNull(raw); Objects.requireNonNull(observation); Objects.requireNonNull(png);
            if (png.length < 33 || png.length > ru.cashprediction.core.ui.selftest.paint.PaintObservationCodec.MAX_PNG_BYTES)
                throw new IllegalArgumentException("capture PNG size");
            png = png.clone();
            var id = observation.identity();
            if (raw.schema() != UiDump.SCHEMA || !raw.client().equals(id.client())
                    || !raw.scenario().equals(id.scenario()) || !raw.step().equals(id.step()))
                throw new IllegalArgumentException("capture raw identity");
            // Здесь проверяется заголовок диагностического PNG; полный контракт проверит WidgetCapture только при поддержанном захвате.
            var header = java.nio.ByteBuffer.wrap(png);
            if (header.getLong(0) != 0x89504e470d0a1a0aL || header.getInt(8) != 13 || header.getInt(12) != 0x49484452
                    || header.getInt(16) != observation.viewport().pngWidth() || header.getInt(20) != observation.viewport().pngHeight())
                throw new IllegalArgumentException("capture PNG header");
        }
        /** Возвращает копию исходного снимка. */
        @Override public byte[] png() { return png.clone(); }
        /** Отказывает с диагностикой до создания успешной пары при unknown paint или нестабильном bracket. */
        public WidgetCapture requireSupported(PaintCaptureRequest request) {
            if (!observation.unsupported().isEmpty() || !observation.synchronization().stable()) throw new UnsupportedCapture(this);
            WidgetCapture capture = new WidgetCapture(raw, png, observation); capture.requireRequest(request); return capture;
        }
    }

    /** Отказ содержит снятую попытку, но не разрешает успешный manifest/commit. */
    public static final class UnsupportedCapture extends UnsupportedOperationException {
        private final CaptureResult result;
        /** Сохраняет конкретные причины отказа и диагностический снимок. */
        UnsupportedCapture(CaptureResult result) {
            super("Swing capture unsupported: " + result.observation().unsupported() + "; changes=" + result.observation().synchronization().changes());
            this.result = result;
        }
        /** Возвращает исходные raw, PNG и companion-наблюдение отказавшего опыта. */
        public CaptureResult result() { return result; }
    }

    /** Временные listeners, manager и одноразовый collector bracket одного настоящего root. */
    static final class CaptureTransaction implements AutoCloseable {
        final JComponent root;
        final NativeCaptureInput input;
        private final Supplier<JComponent> currentRoot;
        private final Function<PaintCaptureRequest, UiDump> rawReader;
        private final Supplier<CaptureGeometry> geometry;
        private final SwingPaintJournal journal;
        private final SwingPaintContext paintContext;
        private final SwingCaptureRepaintManager manager;
        private final SwingPaintCollector collector;
        private SwingPaintCollector.Bracket bracket;
        private UiDump raw;
        private boolean closed;

        /** Подключает root-local наблюдение до ввода; частичная установка очищается немедленно на EDT. */
        CaptureTransaction(JComponent root, Supplier<JComponent> currentRoot, Function<PaintCaptureRequest, UiDump> rawReader,
                           Supplier<CaptureGeometry> geometry) {
            requireCaptureEdt(); this.root = Objects.requireNonNull(root); this.currentRoot = Objects.requireNonNull(currentRoot);
            this.rawReader = Objects.requireNonNull(rawReader); this.geometry = Objects.requireNonNull(geometry);
            paintContext = root instanceof SwingPaintRoot hooked ? hooked.context() : null;
            journal = paintContext == null ? new SwingPaintJournal(root) : paintContext.journal();
            manager = SwingCaptureRepaintManager.install(root, journal);
            SwingPaintCollector watching = null; NativeCaptureInput nativeInput = null;
            try { watching = new SwingPaintCollector(root, journal); nativeInput = new NativeCaptureInput(root); }
            catch (RuntimeException | Error failure) {
                try { if (watching != null) watching.close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                try { manager.close(); }
                catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            collector = watching; input = nativeInput;
        }

        /** Открывает bracket ДО raw/geometry; их побочные изменения остаются внутри наблюдения. */
        CaptureGeometry prepare(PaintCaptureRequest request) {
            live(); if (bracket != null) throw new IllegalStateException("capture bracket already open");
            if (currentRoot.get() != root) throw new IllegalStateException("capture root replaced before bracket");
            manager.synchronizeJournal();
            if (paintContext != null) {
                manager.beginEpoch();
                boolean returned = false;
                try {
                    paintContext.paintPass(() -> root.paintImmediately(0, 0, root.getWidth(), root.getHeight()));
                    returned = true;
                } finally {
                    if (returned) manager.finishEpoch(); else manager.abortEpoch();
                }
                paintContext.validateSources();
            }
            bracket = collector.prepare(request, collector.observeInteraction(input.modality, input.sequence, input.acknowledged()));
            raw = rawReader.apply(request); return geometry.get();
        }

        /** Закрывает bracket после Robot, добавляя смену root/экранной геометрии и пробел census в companion. */
        CaptureResult finish(PaintCaptureRequest request, CaptureGeometry before, BufferedImage image, byte[] png) {
            live(); if (bracket == null) throw new IllegalStateException("no capture bracket");
            List<String> changes = new ArrayList<>();
            if (currentRoot.get() != root) changes.add("content root replaced during capture");
            if (!before.equals(geometry.get())) changes.add("screen geometry or device transform changed");
            try { manager.synchronizeJournal(); }
            catch (IllegalStateException replaced) { changes.add("capture repaint manager ownership lost"); }
            boolean sourcesCurrent = paintContext == null || paintContext.validateSources();
            var environment = new PaintObservation.Environment(System.getProperty("os.name") + " " + System.getProperty("os.version"),
                    System.getProperty("java.runtime.version"), "AWT Robot.createScreenCapture", Math.hypot(before.deviceTransform().a(), before.deviceTransform().b()),
                    "font-load certification and artifact digests unavailable", Map.of());
            var handle = bracket; bracket = null;
            PaintObservation observed = collector.finish(handle, image.getWidth(), image.getHeight(), environment);
            List<PaintObservation.Unsupported> unsupported = new ArrayList<>(observed.unsupported());
            unsupported.add(new PaintObservation.Unsupported(journal.identity(root), "paint-hooks", paintContext == null
                    ? "full root/owner painter hooks are not connected"
                    : "root/owner callbacks connected; full UI delegate image/text/shape census and external occlusion are unsupported"));
            if (!sourcesCurrent)
                unsupported.add(new PaintObservation.Unsupported(journal.identity(root), "image-source", "original decoded image chain changed during capture"));
            if (!input.acknowledged()) unsupported.add(new PaintObservation.Unsupported(journal.identity(root), "native-input", "last physical gesture no longer acknowledged"));
            var old = observed.synchronization(); changes.addAll(old.changes());
            var synchronization = new PaintObservation.Synchronization(old.mode(), old.epochBefore(), old.epochAfter(), old.layoutRevisionBefore(), old.layoutRevisionAfter(),
                    old.paintRevisionBefore(), old.paintRevisionAfter(), old.renderGenerationBefore(), old.renderGenerationAfter(), old.frameId(),
                    old.fingerprintBefore(), old.fingerprintAfter(), old.startNanos(), old.endNanos(), old.settled(), changes);
            var diagnostic = new PaintObservation(observed.schema(), observed.kind(), observed.identity(), observed.viewport(), observed.environment(), synchronization,
                    observed.interaction(), observed.surfaces(), observed.assets(), observed.icons(), observed.cards(), unsupported);
            return new CaptureResult(raw, png, diagnostic);
        }

        /** Снимает listeners и восстанавливает только всё ещё принадлежащий попытке manager. */
        @Override public void close() {
            requireCaptureEdt(); if (closed) return; closed = true;
            try { if (bracket != null) { collector.abort(bracket); bracket = null; } }
            finally { try { input.close(); } finally { try { collector.close(); } finally { manager.close(); } } }
        }

        /** Запрещает повторное использование закрытой транзакции и чтение компонентов с worker. */
        private void live() { requireCaptureEdt(); if (closed) throw new IllegalStateException("capture transaction closed"); }
    }

    /** Подтверждает только доставленные после команды события и актуальную физическую точку/активное окно. */
    static final class NativeCaptureInput implements AutoCloseable {
        private final JComponent root;
        private final AWTEventListener mouse = this::mouseEvent;
        private final KeyEventDispatcher keyboard = this::keyEvent;
        private Point requested;
        private long since;
        private boolean delivered;
        private boolean closed;
        private String modality = "none";
        private long sequence;

        /** Подключает наблюдение без dispatchEvent, processKeyBindings или изменения состояния виджета. */
        NativeCaptureInput(JComponent root) {
            requireCaptureEdt(); this.root = root;
            Toolkit.getDefaultToolkit().addAWTEventListener(mouse, AWTEvent.MOUSE_MOTION_EVENT_MASK);
            try { KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyboard); }
            catch (RuntimeException | Error failure) { Toolkit.getDefaultToolkit().removeAWTEventListener(mouse); throw failure; }
        }
        /** Начинает новую последовательность движения; совпадение физической точки без события недостаточно. */
        void armPointer(Point point) { requireCaptureEdt(); requested = new Point(point); since = System.currentTimeMillis(); delivered = false; modality = "pointer"; sequence++; }
        /** Начинает новую последовательность Tab; старая доставка не подтверждает следующий жест. */
        void armKey() { requireCaptureEdt(); since = System.currentTimeMillis(); delivered = false; modality = "keyboard"; sequence++; }
        /** Принимает только реально доставленное движение к текущей физической точке внутри root. */
        private void mouseEvent(AWTEvent event) {
            if (closed || !"pointer".equals(modality) || !(event instanceof MouseEvent move) || move.getID() != MouseEvent.MOUSE_MOVED
                    || move.getWhen() < since || !inside(move.getComponent()) || requested == null) return;
            PointerInfo pointer = GraphicsEnvironment.isHeadless() ? null : MouseInfo.getPointerInfo();
            delivered = requested.equals(move.getLocationOnScreen()) && pointer != null && requested.equals(pointer.getLocation());
        }
        /** Наблюдает отпускание настоящего Tab до штатной focus traversal, ничего не потребляя. */
        private boolean keyEvent(KeyEvent event) {
            if (!closed && "keyboard".equals(modality) && event.getID() == KeyEvent.KEY_RELEASED && event.getKeyCode() == KeyEvent.VK_TAB
                    && event.getWhen() >= since && inside(event.getComponent())) delivered = true;
            return false;
        }
        /** Возвращает факт доставки и физической активности; не объявляет желаемое состояние карточки наблюдением. */
        boolean acknowledged() {
            requireCaptureEdt(); if (closed || !delivered) return false;
            Window window = SwingUtilities.getWindowAncestor(root);
            if (window == null || !window.isActive() || !window.isFocused()) return false;
            if ("pointer".equals(modality)) {
                PointerInfo pointer = GraphicsEnvironment.isHeadless() ? null : MouseInfo.getPointerInfo();
                return pointer != null && requested.equals(pointer.getLocation());
            }
            return inside(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
        }
        /** Проверяет физическое дерево компонентов, а не маркер или планируемый focus id. */
        private boolean inside(Component component) { return component != null && (component == root || SwingUtilities.isDescendingFrom(component, root)); }
        /** Удаляет оба наблюдателя; прежние dispatcher продолжают обычную работу. */
        @Override public void close() {
            requireCaptureEdt(); if (closed) return; closed = true;
            Toolkit.getDefaultToolkit().removeAWTEventListener(mouse);
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyboard);
        }
    }

    /** Ограничивает ожидание EDT; отменённое ещё не начатое задание не подключает listeners позже. */
    static <T> T captureEdt(long deadline, Callable<T> action) throws Exception {
        captureWorker(); remaining(deadline);
        FutureTask<T> task = new FutureTask<>(() -> { remaining(deadline); return action.call(); });
        SwingUtilities.invokeLater(task);
        try { return task.get(remaining(deadline), TimeUnit.NANOSECONDS); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception cause) throw cause;
            if (failure.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException(failure.getCause());
        } catch (TimeoutException | InterruptedException failure) { task.cancel(false); throw failure; }
    }
    /** Проверяет общий монотонный дедлайн до и после потенциально долгой native операции. */
    private static long remaining(long deadline) throws TimeoutException {
        long left = deadline - System.nanoTime(); if (left <= 0) throw new TimeoutException("Swing capture deadline exceeded"); return left;
    }
    /** Запрещает Robot capture и ожидания очереди на EDT. */
    private static void captureWorker() { if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("capture requires worker thread"); }
    /** Запрещает чтение Swing компонентов или lifecycle транзакции вне EDT. */
    private static void requireCaptureEdt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("capture transaction requires EDT"); }
    /** Снимает реальное содержимое главного окна либо единственного видимого окна запуска через Robot. */
    @Override public byte[] screenshot(String step) throws IOException {
        try {
            Rectangle bounds = edt(() -> {
                Component root = captureRoot();
                Point p = root.getLocationOnScreen();
                if (root.getWidth() <= 0 || root.getHeight() <= 0) throw new IllegalStateException("Visible capture target has no size");
                return new Rectangle(p.x, p.y, root.getWidth(), root.getHeight());
            });
            BufferedImage image = robot.createScreenCapture(bounds); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
        } catch (Exception e) { throw new IOException("Screenshot", e); }
    }

    /** Не создаёт фиктивное главное окно ради снимка вопроса восстановления или второго экземпляра. */
    private Component captureRoot() {
        if (port.frame != null && port.frame.root.isShowing()) return port.frame.root;
        Component visible = null;
        for (SwingAlerts alert : port.alerts.values())
            if (alert.showing()) visible = alert.dialog.getContentPane();
        if (visible != null) return visible;
        for (SwingFormDialog form : port.forms.values())
            if (form.showing()) visible = form.content;
        if (visible == null) throw new IllegalStateException("No actual visible window to capture");
        return visible;
    }
    private static <T> T edt(Callable<T> action) throws Exception { if (SwingUtilities.isEventDispatchThread()) return action.call(); FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(); }
}
