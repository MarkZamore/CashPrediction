package ru.cashprediction.parity.audit;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import ru.cashprediction.parity.check.hotkey.HotkeyCases;
import ru.cashprediction.parity.check.hotkey.HotkeyRun;
import ru.cashprediction.parity.launch.ReactorLayout;
import static org.junit.jupiter.api.Assertions.*;

/** Отказывает явно, если desktop не позволяет настоящие окна, фокус и клавиатурный ввод. */
@EnabledIfSystemProperty(named = "parity.desktopPreflight", matches = "true")
public final class GateCoverageDesktopTest {
    /** Проверяет Swing через Robot и затем штатный настоящий FX keyboard driver. */
    @Test void actualDesktopFocusRoundTrip() throws Exception {
        assertFalse(GraphicsEnvironment.isHeadless(), "Interactive Windows desktop required");
        assertTrue(System.getProperty("os.name").startsWith("Windows"), "Windows required");
        assertTrue(Toolkit.getDefaultToolkit().getScreenSize().width >= 1200, "Desktop too narrow");
        AtomicReference<JFrame> frame = new AtomicReference<>();
        AtomicReference<JTextField> field = new AtomicReference<>();
        Robot robot = null;
        Point requested = null;
        long focusStarted = 0, focusFinished = 0, deliveryStarted = 0;
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame f = new JFrame("Desktop preflight"); JTextField t = new JTextField(20);
                f.add(t); f.pack(); f.setLocationRelativeTo(null); f.setVisible(true); f.toFront(); t.requestFocusInWindow();
                frame.set(f); field.set(t);
            });
            AtomicReference<Point> point = new AtomicReference<>();
            AtomicReference<GraphicsDevice> device = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> {
                device.set(frame.get().getGraphicsConfiguration().getDevice());
                Point location = field.get().getLocationOnScreen();
                point.set(new Point(location.x + field.get().getWidth() / 2,
                        location.y + field.get().getHeight() / 2));
            });
            robot = new Robot(device.get()); robot.setAutoDelay(80);
            requested = point.get();
            focusStarted = System.nanoTime();
            // requestFocusInWindow не доказывает foreground Windows; активируем наше поле OS-щелчком.
            robot.mouseMove(requested.x, requested.y);
            // Наблюдаем попадание настоящего указателя до щелчка; координаты вручную не масштабируем.
            long pointerDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            boolean hit;
            do {
                hit = observation(frame.get(), field.get()).pointerHit();
                if (hit) break;
                Thread.sleep(25);
            } while (System.nanoTime() < pointerDeadline);
            assertTrue(hit, "Actual pointer did not reach Swing field");
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            boolean focused = false;
            while (!focused && System.nanoTime() < deadline) {
                Observation current = observation(frame.get(), field.get());
                focused = current.focused() && current.pointerHit();
                if (!focused) Thread.sleep(50);
            }
            assertTrue(focused, "Swing focus/pointer unavailable (Windows foreground not independently observed)");
            focusFinished = System.nanoTime();
            robot.keyPress(KeyEvent.VK_7);
            try { } finally { robot.keyRelease(KeyEvent.VK_7); }
            deliveryStarted = System.nanoTime();
            deadline = deliveryStarted + Duration.ofSeconds(10).toNanos();
            robot.waitForIdle();
            AtomicReference<String> typed = new AtomicReference<>();
            // waitForIdle не гарантирует, что Windows уже доставила native key в очередь AWT.
            // Ждём только фактический текст, не повторяем ввод и не подставляем ожидаемое значение.
            do {
                SwingUtilities.invokeAndWait(() -> typed.set(field.get().getText()));
                if ("7".equals(typed.get())) break;
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            assertEquals("7", typed.get(), "Native key did not reach focused window");
        } catch (Exception | AssertionError failure) {
            // Снимаем фактическое окно до dispose; ошибка диагностики не скрывает первичный отказ.
            try {
                if (frame.get() != null && field.get() != null) {
                    Path evidence = Files.createTempDirectory(Files.createDirectories(
                            ReactorLayout.fromSystemProperties().parityRoot()), "desktop-preflight-");
                    Observation current = observation(frame.get(), field.get());
                    long now = System.nanoTime();
                    String details = current + "\nrequestedPointer=" + requested
                            + "\nwindowsForeground=not independently observed (AWT focus only)"
                            + "\nfocusElapsedMillis=" + elapsed(focusStarted, focusFinished == 0 ? now : focusFinished)
                            + "\ndeliveryElapsedMillis=" + elapsed(deliveryStarted, now) + "\n";
                    Files.writeString(evidence.resolve("report-preflight.txt"), details);
                    Robot captureRobot = robot != null ? robot : new Robot(frame.get().getGraphicsConfiguration().getDevice());
                    assertTrue(ImageIO.write(captureRobot.createScreenCapture(current.frameBounds()),
                            "png", evidence.resolve("actual-window.png").toFile()), "PNG writer unavailable");
                    failure.addSuppressed(new AssertionError("Actual preflight evidence: " + evidence));
                }
            } catch (Exception | AssertionError diagnosticFailure) { failure.addSuppressed(diagnosticFailure); }
            throw failure;
        } finally { if (frame.get() != null) SwingUtilities.invokeAndWait(() -> frame.get().dispose()); }
        var probe = HotkeyCases.forClient("fx").stream().filter(p -> p.name().endsWith(" en")).findFirst().orElseThrow();
        HotkeyRun.verify(ReactorLayout.fromSystemProperties(), "fx", probe);
    }

    /** Возвращает независимые наблюдения AWT и настоящего указателя, не изменяя фокус или текст. */
    private static Observation observation(JFrame frame, JTextField field) throws Exception {
        AtomicReference<Observation> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Point location = field.getLocationOnScreen();
            Rectangle bounds = new Rectangle(location.x, location.y, field.getWidth(), field.getHeight());
            PointerInfo info = MouseInfo.getPointerInfo();
            Point pointer = info == null ? null : info.getLocation();
            KeyboardFocusManager focus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            GraphicsConfiguration screen = frame.getGraphicsConfiguration();
            result.set(new Observation(frame.getBounds(), bounds, screen.getDevice().getIDstring(),
                    screen.getBounds(), screen.getDefaultTransform().toString(), pointer,
                    pointer != null && bounds.contains(pointer) && field.getMousePosition(true) != null,
                    frame.isActive() && focus.getActiveWindow() == frame && field.isFocusOwner(),
                    String.valueOf(focus.getFocusOwner()), String.valueOf(focus.getActiveWindow()), field.getText()));
        });
        return result.get();
    }

    /** Длительность только действительно начатого ожидания; отсутствие начала обозначается явно. */
    private static String elapsed(long start, long end) {
        return start == 0 ? "not started" : Long.toString(Duration.ofNanos(end - start).toMillis());
    }

    /** Фактические координаты, экран, фокус и текст на момент наблюдения; это не Win32 foreground. */
    private record Observation(Rectangle frameBounds, Rectangle fieldBounds, String screenDevice,
                               Rectangle screenBounds, String transform, Point actualPointer,
                               boolean pointerHit, boolean focused, String focusOwner, String activeWindow,
                               String text) { }
}
