package ru.cashprediction.swing.ui;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.ui.command.*;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.form.*;
import ru.cashprediction.core.ui.selftest.*;

/** Драйвер настоящего Swing-интерфейса, не вызывающий команды бизнес-контроллера вместо виджетов. */
public final class SwingUiDriver implements UiDriver {
    private final SwingUiPort port;
    private final SwingUiDumper dumper;
    private final Robot robot;
    private long settleMillis;
    private final AppController controller;
    private boolean startupReady;

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
                if (snapshotWaitNotRequired(controller.state().recorder())) return true;
                if (recorder == null && port.alerts.values().stream().anyMatch(a -> a.showing() && startupDecision(a.spec.purpose()))) return true;
                if (recorder == null || !recorder.isStarted()) return false;
                return startupStoresReady(recorder.stores().size(), controller.state().stores());
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
    static boolean snapshotWaitNotRequired(RecorderStatus status) {
        return status == RecorderStatus.PENDING_RESTORE || status == RecorderStatus.DISABLED_SECOND_INSTANCE;
    }
    /** Видимый вопрос запуска должен получить ответ до установки регистратора. */
    static boolean startupDecision(String purpose) {
        return "crashRecovery".equals(purpose) || "alreadyRunning".equals(purpose);
    }
    /** Снимает настоящий дамп в EDT. */
    @Override public UiDump dump(String step) { try { return edt(() -> dumper.dump(step)); } catch (Exception e) { throw new IllegalStateException(e); } }
    /** Снимает реальное содержимое главного окна и открытых окон через Robot. */
    @Override public byte[] screenshot(String step) throws IOException {
        try {
            Rectangle bounds = edt(() -> { Point p = port.frame.root.getLocationOnScreen(); return new Rectangle(p.x, p.y, port.frame.root.getWidth(), port.frame.root.getHeight()); });
            BufferedImage image = robot.createScreenCapture(bounds); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes); return bytes.toByteArray();
        } catch (Exception e) { throw new IOException("Screenshot", e); }
    }
    private static <T> T edt(Callable<T> action) throws Exception { if (SwingUtilities.isEventDispatchThread()) return action.call(); FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(); }
}
