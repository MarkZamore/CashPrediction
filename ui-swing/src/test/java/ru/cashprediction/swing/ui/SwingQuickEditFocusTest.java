package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.model.*;
import ru.cashprediction.core.ui.command.KeyChord;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;

/** Проверяет нативный фокус и ввод быстрой правки отдельно от программного заполнения драйвером. */
@EnabledIfSystemProperty(named = "cashprediction.swing.quickEditFocus", matches = "true")
class SwingQuickEditFocusTest {
    private static final String ROW = "r1@2026-10-05";
    private static final OccurrenceKey KEY = new OccurrenceKey(new RuleId("r1"), LocalDate.of(2026, 10, 5));
    @TempDir Path directory;

    /** Открытие мышью само переводит фокус: ошибка, Enter, отмена и повторное открытие не изменяют фильтр. */
    @Test void nativeTypingRepeatedApplyCancelAndRemovalNeverLeakIntoFilter() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Fixture fixture = create();
        try {
            for (int round = 0; round < 3; round++) {
                SwingQuickEditPopup first = openWithMouse(fixture);
                type(fixture.robot, "0"); press(fixture.robot, KeyEvent.VK_ENTER);
                await(fixture, "validation after native Enter", () -> first.showing() && !first.session.view().problem().display().isEmpty());
                edt(() -> {
                    assertSame(first, popup(fixture));
                    assertTrue(editor(first).isFocusOwner());
                    assertEquals(Money.ofMajor(0), Money.parse(first.session.state().value("amount")));
                    assertFilterEmpty(fixture);
                    return null;
                });
                replace(fixture.robot, "81000"); press(fixture.robot, KeyEvent.VK_ENTER);
                awaitClosed(fixture, first);
                assertAmount(fixture, "81000");

                SwingQuickEditPopup cancelled = openWithMouse(fixture);
                type(fixture.robot, "82000"); press(fixture.robot, KeyEvent.VK_ESCAPE);
                awaitClosed(fixture, cancelled);
                assertAmount(fixture, "81000");

                SwingQuickEditPopup removed = openWithMouse(fixture);
                type(fixture.robot, "80000"); press(fixture.robot, KeyEvent.VK_ENTER);
                awaitClosed(fixture, removed);
                edt(() -> { assertTrue(fixture.app.state().document().plan().findAdjustment(KEY).isEmpty()); return null; });
            }
        } finally { edt(() -> { fixture.port.exit(ExitKind.CLEAN, 0); return null; }); }
    }

    /** Заполнение драйвером и настоящий focusLost оставляют Enter внутри popup после изменения его высоты. */
    @Test void driverFillKeepsCommitFocusInsidePopupAcrossReopenAndValidation() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless());
        Fixture fixture = create();
        try {
            for (int round = 0; round < 3; round++) {
                SwingQuickEditPopup opened = openWithMouse(fixture);
                for (String amount : new String[]{"0", "81000"}) {
                    fixture.driver.execute(new SelfTestCommand.Fill("last", Map.of("amount", amount)));
                    fixture.robot.waitForIdle();
                    edt(() -> {
                        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
                        assertNotNull(focused);
                        assertTrue(SwingUtilities.isDescendingFrom(focused, opened.content));
                        assertFilterEmpty(fixture);
                        return null;
                    });
                    press(fixture.robot, KeyEvent.VK_ENTER);
                    if (amount.equals("0")) await(fixture, "validation after driver fill and native Enter", () -> opened.showing() && !opened.session.view().problem().display().isEmpty());
                    else awaitClosed(fixture, opened);
                }
                assertAmount(fixture, "81000");
                SwingQuickEditPopup cancelled = openWithMouse(fixture);
                fixture.driver.execute(new SelfTestCommand.Fill("last", Map.of("amount", "82000")));
                press(fixture.robot, KeyEvent.VK_ESCAPE);
                awaitClosed(fixture, cancelled);
                assertAmount(fixture, "81000");
            }
        } finally { edt(() -> { fixture.port.exit(ExitKind.CLEAN, 0); return null; }); }
    }

    /** Подготавливает пример через штатный драйвер, без реестра и без принудительного фокуса popup. */
    private Fixture create() throws Exception {
        Fixture fixture = edt(() -> {
            SwingLook.install();
            var environment = AppEnvironment.from(LaunchOptions.parse("--home", directory.toString(), "--registry", "memory",
                    "--today", "2026-09-13", "--selftest", "s12-quick-edit"));
            var port = new SwingUiPort(environment);
            var app = new AppController(port, environment); port.bind(app); app.start();
            return new Fixture(port, app, new SwingUiDriver(port, app, "quick-focus"), new Robot(), new MouseProbe());
        });
        try {
            for (SelfTestCommand command : java.util.List.of(new SelfTestCommand.Key(KeyChord.parse("Esc")), new SelfTestCommand.Sample())) {
                fixture.driver.execute(command); fixture.driver.awaitIdle(Duration.ofSeconds(5));
            }
            fixture.robot.setAutoDelay(25);
            edt(() -> { fixture.port.frame.table.table.addMouseListener(fixture.mouse); return null; });
            return fixture;
        } catch (Exception | Error failure) {
            edt(() -> { fixture.port.exit(ExitKind.CLEAN, 0); return null; });
            throw failure;
        }
    }

    /** Открывает popup двойным физическим щелчком после фокуса фильтра и ждёт автоматического фокуса редактора. */
    private static SwingQuickEditPopup openWithMouse(Fixture fixture) throws Exception {
        edt(() -> { fixture.port.frame.toFront(); fixture.port.frame.toolbar.filter().requestFocus(); return null; });
        await(fixture, "filter focus before double click", () -> fixture.port.frame.toolbar.filter().isFocusOwner());
        edt(() -> {
            JTable table = fixture.port.frame.table.table;
            int row = table.convertRowIndexToView(fixture.port.frame.model.table().indexOf(ROW));
            int column = table.getColumnModel().getColumnIndex("income");
            assertTrue(row >= 0, "Missing quick-edit row " + ROW);
            assertTrue(fixture.port.frame.model.table().row(table.convertRowIndexToModel(row)).quickEditable());
            assertFalse(table.getValueAt(row, column).toString().isEmpty(), "Empty income cell");
            table.scrollRectToVisible(table.getCellRect(row, column, true));
            return null;
        });
        // Прокрутка и отложенная раскладка должны закончиться до выбора экранной точки физического щелчка.
        fixture.robot.waitForIdle();
        Point cell = edt(() -> {
            JTable table = fixture.port.frame.table.table;
            int row = table.convertRowIndexToView(fixture.port.frame.model.table().indexOf(ROW));
            int column = table.getColumnModel().getColumnIndex("income");
            Rectangle visible = table.getCellRect(row, column, true).intersection(table.getVisibleRect());
            assertTrue(table.isShowing() && visible.width > 0 && visible.height > 0,
                    () -> "Quick-edit cell is not visible; " + diagnostic(fixture));
            Point point = new Point(visible.x + visible.width / 2, visible.y + visible.height / 2);
            assertEquals(row, table.rowAtPoint(point));
            assertEquals(column, table.columnAtPoint(point));
            SwingUtilities.convertPointToScreen(point, table);
            fixture.mouse.target = new Point(point); fixture.mouse.events.clear();
            return point;
        });
        fixture.robot.mouseMove(cell.x, cell.y);
        // Предыдущее открытие могло закончиться быстрее системного интервала двойного щелчка.
        // Начинаем новую физическую пару, а не продолжение серии с clickCount=3/4.
        Object interval = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");
        fixture.robot.delay((interval instanceof Number value ? value.intValue() : 500) + 40);
        for (int click = 0; click < 2; click++) {
            fixture.robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); fixture.robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        }
        await(fixture, "popup and native editor focus after double click", () -> {
            if (fixture.port.forms.size() != 1) return false;
            // JavaFX: Popup → Swing: SwingQuickEditPopup → Web: div быстрой правки
            var form = fixture.port.forms.values().iterator().next();
            return form instanceof SwingQuickEditPopup opened && opened.showing() && editor(opened).isFocusOwner();
        });
        return edt(() -> {
            SwingQuickEditPopup opened = popup(fixture);
            // JavaFX: Popup → Swing: фокусируемый JWindow PopupFactory → Web: div быстрой правки
            Window host = SwingUtilities.getWindowAncestor(opened.content);
            assertInstanceOf(JWindow.class, host); assertNotSame(fixture.port.frame, host);
            assertTrue(host.isFocusableWindow()); assertTrue(host.isFocused());
            assertEquals(opened.spec.width(), opened.content.getWidth());
            assertFalse(opened.dialog.isVisible());
            assertEquals(editor(opened).getText(), editor(opened).getSelectedText());
            assertFilterEmpty(fixture);
            return opened;
        });
    }

    /** Возвращает единственное реальное окно быстрой правки. */
    private static SwingQuickEditPopup popup(Fixture fixture) {
        assertEquals(1, fixture.port.forms.size());
        return assertInstanceOf(SwingQuickEditPopup.class, fixture.port.forms.values().iterator().next());
    }

    /** Возвращает денежный редактор popup. */
    private static JTextField editor(SwingQuickEditPopup popup) { return (JTextField) popup.fields.get("amount").getFirst().input; }

    /** Проверяет фактическое применение суммы в общей модели. */
    private static void assertAmount(Fixture fixture, String amount) throws Exception {
        edt(() -> {
            assertEquals(Money.parse(amount), fixture.app.state().document().plan().findAdjustment(KEY).orElseThrow().action().newAmount().orElseThrow());
            assertFilterEmpty(fixture); return null;
        });
    }

    /** Проверяет текст настоящего фильтра, включая события после закрытия popup. */
    private static void assertFilterEmpty(Fixture fixture) { assertEquals("", fixture.port.frame.toolbar.filter().getText()); }

    /** Дожидается закрытия и обработки очереди, чтобы обнаружить запоздалые события активации. */
    private static void awaitClosed(Fixture fixture, SwingQuickEditPopup popup) throws Exception {
        await(fixture, "popup closed", () -> popup.closed && fixture.port.forms.isEmpty());
        fixture.robot.waitForIdle();
        edt(() -> { assertFalse(popup.showing()); assertFilterEmpty(fixture); return null; });
    }

    /** Заменяет ошибочный текст физической клавиатурой. */
    private static void replace(Robot robot, String value) {
        robot.keyPress(KeyEvent.VK_CONTROL); press(robot, KeyEvent.VK_A); robot.keyRelease(KeyEvent.VK_CONTROL); type(robot, value);
    }

    /** Вводит цифры в текущего нативного владельца фокуса. */
    private static void type(Robot robot, String value) { for (char digit : value.toCharArray()) press(robot, KeyEvent.VK_0 + digit - '0'); }

    /** Отправляет полный цикл физической клавиши, не вызывая обработчики формы. */
    private static void press(Robot robot, int key) { robot.keyPress(key); robot.keyRelease(key); }

    /** Ждёт наблюдаемое состояние без запросов фокуса или синтетических событий. */
    private static void await(Fixture fixture, String phase, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        do {
            fixture.robot.waitForIdle();
            if (edt(condition)) return;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("Native quick-edit condition did not settle: " + phase + "; " + edt(() -> diagnostic(fixture)));
    }

    /** Снимает в EDT формы, нативный фокус, геометрию цели и фактически доставленные события мыши. */
    private static String diagnostic(Fixture fixture) {
        var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        JTable table = fixture.port.frame.table.table;
        PointerInfo pointer = MouseInfo.getPointerInfo();
        Point screen = pointer == null ? null : pointer.getLocation();
        Point local = screen == null || !table.isShowing() ? null : new Point(screen);
        if (local != null) SwingUtilities.convertPointFromScreen(local, table);
        int expectedRow = table.convertRowIndexToView(fixture.port.frame.model.table().indexOf(ROW));
        int expectedColumn = table.getColumnModel().getColumnIndex("income");
        StringBuilder result = new StringBuilder("exited=").append(fixture.port.exited)
                .append(", focusOwner=").append(componentInfo(focus.getFocusOwner()))
                .append(", focusedWindow=").append(componentInfo(focus.getFocusedWindow()))
                .append(", activeWindow=").append(componentInfo(focus.getActiveWindow()))
                .append(", frame=").append(componentInfo(fixture.port.frame))
                .append(", filter=").append(componentInfo(fixture.port.frame.toolbar.filter()))
                .append(", filterText=").append(fixture.port.frame.toolbar.filter().getText())
                .append(", table=").append(componentInfo(table))
                .append(", visibleRect=").append(table.getVisibleRect())
                .append(", viewport=").append(fixture.port.frame.table.scroll.getViewport().getViewPosition())
                .append(", expectedRow=").append(expectedRow).append(", expectedColumn=").append(expectedColumn)
                .append(", expectedCellBounds=").append(expectedRow < 0 ? "unavailable" : table.getCellRect(expectedRow, expectedColumn, true))
                .append(", multiClickInterval=").append(Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval"))
                .append(", targetScreen=").append(fixture.mouse.target)
                .append(", mouseScreen=").append(screen).append(", mouseLocal=").append(local)
                .append(", mouseCell=").append(local == null ? "unavailable" : cellInfo(table, local))
                .append(", mouseEvents=").append(fixture.mouse.events)
                .append(", alerts=").append(fixture.port.alerts.keySet()).append(", forms=[");
        fixture.port.forms.forEach((id, form) -> {
            // JavaFX: Dialog / Popup → Swing: SwingFormDialog / JWindow → Web: dialog / div
            Window host = SwingUtilities.getWindowAncestor(form.content);
            result.append("{id=").append(id).append(", type=").append(form.getClass().getSimpleName())
                    .append(", showing=").append(form.showing()).append(", closed=").append(form.closed)
                    .append(", content=").append(componentInfo(form.content))
                    .append(", host=").append(componentInfo(host))
                    .append(", dialog=").append(componentInfo(form.dialog));
            // JavaFX: Popup → Swing: SwingQuickEditPopup → Web: div быстрой правки
            if (form instanceof SwingQuickEditPopup opened) result.append(", editor=").append(componentInfo(editor(opened)));
            result.append("}");
        });
        return result.append("]").toString();
    }

    /** Описывает компонент и реальные экранные границы только при наличии показанного peer. */
    private static String componentInfo(Component component) {
        if (component == null) return "null";
        String result = component.getClass().getName() + "[name=" + component.getName()
                + ", showing=" + component.isShowing() + ", displayable=" + component.isDisplayable()
                + ", enabled=" + component.isEnabled() + ", focusable=" + component.isFocusable()
                + ", focusOwner=" + component.isFocusOwner() + ", bounds=" + component.getBounds()
                + ", screen=" + (component.isShowing() ? component.getLocationOnScreen() : "unavailable");
        if (component instanceof Window window) result += ", focused=" + window.isFocused()
                + ", active=" + window.isActive() + ", focusableWindow=" + window.isFocusableWindow();
        return result + "]";
    }

    /** Возвращает индексы и идентификаторы ячейки, в которую действительно попало событие мыши. */
    private static String cellInfo(JTable table, Point point) {
        int row = table.rowAtPoint(point), column = table.columnAtPoint(point);
        var model = ((SwingTableAdapter) table.getModel()).source();
        return "row=" + row + ", rowId=" + (row < 0 ? "none" : model.row(table.convertRowIndexToModel(row)).rowId())
                + ", column=" + column + ", columnId=" + (column < 0 ? "none" : table.getColumnModel().getColumn(column).getIdentifier());
    }

    /** Наблюдает настоящие события таблицы, не вызывая обработчики и не меняя фокус. */
    private static final class MouseProbe extends MouseAdapter {
        private Point target;
        private final java.util.List<String> events = new java.util.ArrayList<>();

        /** Записывает фазу нажатия физической кнопки. */
        @Override public void mousePressed(MouseEvent event) { record(event); }
        /** Записывает фазу отпускания физической кнопки. */
        @Override public void mouseReleased(MouseEvent event) { record(event); }
        /** Записывает полученный JTable счётчик щелчков для проверки двойного нажатия. */
        @Override public void mouseClicked(MouseEvent event) { record(event); }

        /** Ограничивает журнал событиями последней попытки открытия. */
        private void record(MouseEvent event) {
            if (events.size() == 12) events.removeFirst();
            String phase = switch (event.getID()) {
                case MouseEvent.MOUSE_PRESSED -> "pressed";
                case MouseEvent.MOUSE_RELEASED -> "released";
                case MouseEvent.MOUSE_CLICKED -> "clicked";
                default -> Integer.toString(event.getID());
            };
            events.add("phase=" + phase + ", count=" + event.getClickCount() + ", when=" + event.getWhen()
                    + ", button=" + event.getButton() + ", screen=" + event.getLocationOnScreen()
                    + ", local=" + event.getPoint() + ", " + cellInfo((JTable) event.getComponent(), event.getPoint()));
        }
    }

    /** Выполняет чтение или подготовку виджетов в EDT с передачей ошибки тесту. */
    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new java.util.concurrent.FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }

    /** Объединяет настоящие виджеты, контроллер и физическую клавиатуру изолированного теста. */
    private record Fixture(SwingUiPort port, AppController app, SwingUiDriver driver, Robot robot, MouseProbe mouse) { }
}
