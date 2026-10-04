package ru.cashprediction.swing.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.app.*;
import ru.cashprediction.core.document.ViewMode;
import ru.cashprediction.core.ui.command.KeyChord;
import ru.cashprediction.core.ui.selftest.SelfTestCommand;

/** Регрессии реального контента, рамки восстановления и отсутствующих скрытых заголовков таблицы. */
class SwingContentGeometryTest {
    @TempDir Path directory;

    @Test void formsReportMeasuredContentRelativeToMainAndKeepRawRestoreBounds() throws Exception {
        withApplication((port, app, driver) -> {
            step(driver, new SelfTestCommand.Select("r1@2026-10-05"));
            for (String menu : java.util.List.of("edit.planSettings", "edit.addIncome", "edit.adjust", "tools.goal")) {
                step(driver, new SelfTestCommand.Menu(menu));
                SwingUtilities.invokeAndWait(() -> {
                    var form = new ArrayList<>(port.forms.values()).getLast();
                    var raw = form.bounds(); Point content = form.content.getLocationOnScreen(), main = port.frame.root.getLocationOnScreen();
                    var dump = new SwingUiDumper(port, app, "geometry-test").dump("form").windows().getLast();
                    assertEquals(form.spec.width(), form.content.getWidth());
                    assertEquals(content.x - main.x, dump.bounds().x()); assertEquals(content.y - main.y, dump.bounds().y());
                    assertEquals(form.content.getWidth(), dump.bounds().width()); assertEquals(form.content.getHeight(), dump.bounds().height());
                    assertEquals(form.dialog.getX(), raw.x()); assertEquals(form.dialog.getY(), raw.y());
                    assertEquals(form.dialog.getWidth(), raw.width()); assertEquals(form.dialog.getHeight(), raw.height());
                    assertEquals(raw, form.bounds());
                    assertEquals(raw, form.session.captureState().bounds());
                    assertTrue(raw.height() > dump.bounds().height());
                    var last = form.buttons.get(form.spec.buttons().getLast().id());
                    Point button = SwingUtilities.convertPoint(last, 0, 0, form.content);
                    assertEquals(form.content.getWidth() - 16, button.x + last.getWidth(), menu + " bar=" + form.buttonBar.getBounds() + " last=" + last.getBounds() + " bottom=" + form.buttonBar.getParent().getBounds());
                });
                step(driver, new SelfTestCommand.Cancel("last"));
            }
            step(driver, new SelfTestCommand.View(ViewMode.CHART));
            var hidden = driver.dump("chart");
            assertFalse(hidden.frame().regions().containsKey("table.header"));
            assertTrue(hidden.frame().regions().keySet().stream().noneMatch(id -> id.startsWith("table.column.")));
            step(driver, new SelfTestCommand.View(ViewMode.TABLE));
            assertTrue(driver.dump("table").frame().regions().containsKey("table.header"));
        });
    }

    @Test void alertButtonsEndAtContentPaddingWithoutTrailingGapOrOsBorder() throws Exception {
        withApplication((port, app, driver) -> {
            step(driver, new SelfTestCommand.Select("r1@2026-10-05")); step(driver, new SelfTestCommand.Menu("edit.delete"));
            SwingUtilities.invokeAndWait(() -> {
                var alert = new ArrayList<>(port.alerts.values()).getLast();
                var content = alert.dialog.getContentPane();
                assertEquals(alert.spec.minWidth(), content.getWidth());
                var buttons = new ArrayList<>(alert.buttons.values()); var last = buttons.getLast();
                Point end = SwingUtilities.convertPoint(last, 0, 0, content);
                assertEquals(content.getWidth() - 16, end.x + last.getWidth());
                for (int i = 1; i < buttons.size(); i++) {
                    Point left = SwingUtilities.convertPoint(buttons.get(i - 1), 0, 0, content);
                    Point right = SwingUtilities.convertPoint(buttons.get(i), 0, 0, content);
                    assertEquals(8, right.x - left.x - buttons.get(i - 1).getWidth());
                }
                assertEquals(alert.dialog.getWidth(), alert.bounds().width());
                assertEquals(end.x, new SwingUiDumper(port, app, "geometry-test").dump("alert").alerts().getLast().buttons().getLast().x());
            });
        });
    }

    /** Открывает настоящее приложение в изолированной папке и гарантирует освобождение окон и таймеров. */
    private void withApplication(Check check) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless()); SwingUiPort[] ports = new SwingUiPort[1]; AppController[] apps = new AppController[1];
        SwingUtilities.invokeAndWait(() -> {
            SwingLook.install(); var env = AppEnvironment.from(LaunchOptions.parse("--home", directory.toString(), "--registry", "memory", "--today", "2026-09-13", "--selftest", "s05-forms-plan"));
            ports[0] = new SwingUiPort(env); apps[0] = new AppController(ports[0], env); ports[0].bind(apps[0]); apps[0].start();
        });
        try {
            var driver = new SwingUiDriver(ports[0], apps[0], "geometry-test");
            step(driver, new SelfTestCommand.Key(KeyChord.parse("Esc"))); step(driver, new SelfTestCommand.Sample());
            check.run(ports[0], apps[0], driver);
        } finally { SwingUtilities.invokeAndWait(() -> ports[0].exit(ExitKind.CLEAN, 0)); }
    }
    /** Ждёт реальные события и обычное сохранение состояния после действия виджета. */
    private static void step(SwingUiDriver driver, SelfTestCommand command) throws Exception { driver.execute(command); driver.awaitIdle(Duration.ofSeconds(5)); }
    /** Проверка живого приложения в одном ограниченном запуске. */
    private interface Check { void run(SwingUiPort port, AppController app, SwingUiDriver driver) throws Exception; }
}
