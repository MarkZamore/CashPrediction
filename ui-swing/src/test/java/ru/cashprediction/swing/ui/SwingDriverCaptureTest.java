package ru.cashprediction.swing.ui;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.paint.PaintCaptureRequest;
import ru.cashprediction.core.ui.selftest.paint.PaintObservation;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет настоящий collector/manager lifecycle и границы driver без native окон или Robot. */
class SwingDriverCaptureTest {
    @Test void rawAndGeometryAreOnEdtScreenIsOutsideAndMissingPaintersNeverPass() throws Exception {
        List<String> calls = new ArrayList<>(); AtomicInteger reads = new AtomicInteger();
        Fixture fixture = edt(() -> fixture(request -> {
            assertTrue(SwingUtilities.isEventDispatchThread()); calls.add("raw"); return raw(request);
        }, () -> {
            assertTrue(SwingUtilities.isEventDispatchThread()); calls.add(reads.getAndIncrement() == 0 ? "pre" : "post"); return geometry(10);
        }));
        try {
            PaintCaptureRequest request = request();
            var result = SwingUiDriver.captureBracket(request, fixture.transaction, rectangle -> {
                assertFalse(SwingUtilities.isEventDispatchThread());
                assertEquals(List.of("raw", "pre"), calls); assertEquals(new Rectangle(10, 20, 1200, 800), rectangle);
                calls.add("screen"); return image();
            });
            assertEquals(List.of("raw", "pre", "screen", "post"), calls);
            assertTrue(result.observation().unsupported().stream().anyMatch(u -> u.property().equals("paint-hooks")));
            assertTrue(result.observation().unsupported().stream().anyMatch(u -> u.property().equals("paint.root")));
            assertTrue(result.observation().unsupported().stream().anyMatch(u -> u.property().equals("paint.census")));
            assertTrue(result.observation().icons().isEmpty());
            assertFalse(result.observation().synchronization().stable());
            assertFalse(result.observation().interaction().gestureAcknowledged());
            var failure = assertThrows(SwingUiDriver.UnsupportedCapture.class, () -> result.requireSupported(request));
            assertSame(result, failure.result());
            byte[] copied = result.png(); byte original = copied[0]; copied[0] ^= 1;
            assertEquals(original, result.png()[0]);
        } finally { close(fixture); }
    }

    @Test void screenOriginChangeIsRecordedEvenWithIdenticalRootAndPixels() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        Fixture fixture = edt(() -> fixture(SwingDriverCaptureTest::raw, () -> geometry(reads.getAndIncrement() == 0 ? 10 : 11)));
        try {
            var result = SwingUiDriver.captureBracket(request(), fixture.transaction, rectangle -> image());
            assertTrue(result.observation().synchronization().changes().contains("screen geometry or device transform changed"));
        } finally { close(fixture); }
    }

    @Test void rootReplacementDuringScreenshotIsARejectedBoundary() throws Exception {
        Fixture fixture = edt(() -> fixture(SwingDriverCaptureTest::raw, () -> geometry(10)));
        try {
            var result = SwingUiDriver.captureBracket(request(), fixture.transaction, rectangle -> {
                edt(() -> { fixture.current.set(new JPanel()); return null; }); return image();
            });
            assertTrue(result.observation().synchronization().changes().contains("content root replaced during capture"));
        } finally { close(fixture); }
    }

    @Test void rawReadMutationIsInsideCollectorBracket() throws Exception {
        AtomicReference<JPanel> root = new AtomicReference<>();
        Fixture fixture = edt(() -> {
            Fixture value = fixture(request -> { root.get().setName("changed-during-raw"); return raw(request); }, () -> geometry(10));
            root.set(value.root); return value;
        });
        try {
            var result = SwingUiDriver.captureBracket(request(), fixture.transaction, rectangle -> image());
            assertTrue(result.observation().synchronization().changes().contains("mutation generation changed"));
            assertTrue(result.observation().synchronization().changes().stream().anyMatch(reason -> reason.startsWith("property:")));
        } finally { close(fixture); }
    }

    @Test void requestAndReturnRepaintAbaDuringScreenshotAreObserved() throws Exception {
        Fixture fixture = edt(() -> fixture(SwingDriverCaptureTest::raw, () -> geometry(10)));
        try {
            var result = SwingUiDriver.captureBracket(request(), fixture.transaction, rectangle -> {
                fixture.root.repaint(0, 0, 1, 1); return image();
            });
            assertTrue(result.observation().synchronization().changes().contains("paint generation changed"));
        } finally { close(fixture); }
    }

    @Test void foreignManagerIsNotRestoredOverAndOwnershipLossIsDiagnostic() throws Exception {
        Fixture fixture = edt(() -> fixture(SwingDriverCaptureTest::raw, () -> geometry(10)));
        RepaintManager replacement = new RepaintManager();
        try {
            var result = SwingUiDriver.captureBracket(request(), fixture.transaction, rectangle -> {
                edt(() -> { RepaintManager.setCurrentManager(replacement); return null; }); return image();
            });
            assertTrue(result.observation().synchronization().changes().contains("capture repaint manager ownership lost"));
            edt(() -> { fixture.transaction.close(); assertSame(replacement, RepaintManager.currentManager(fixture.root)); return null; });
        } finally {
            edt(() -> { fixture.transaction.close(); RepaintManager.setCurrentManager(fixture.previous); return null; });
        }
    }

    @Test void failingScreenPreservesFailureAndCloseRemovesObserversAndRestoresManager() throws Exception {
        Fixture fixture = edt(() -> fixture(SwingDriverCaptureTest::raw, () -> geometry(10)));
        try {
            IOException failure = new IOException("screen failed");
            assertSame(failure, assertThrows(IOException.class,
                    () -> SwingUiDriver.captureBracket(request(), fixture.transaction, rectangle -> { throw failure; })));
        } finally { close(fixture); }
        edt(() -> {
            assertEquals(fixture.listenersBefore, fixture.root.getPropertyChangeListeners().length);
            assertSame(fixture.previous, RepaintManager.currentManager(fixture.root));
            fixture.transaction.close();
            return null;
        });
    }

    @Test void failingRawAbortsTheOpenBracketOnCloseWithoutCallingScreen() throws Exception {
        IllegalStateException failure = new IllegalStateException("raw failed");
        Fixture fixture = edt(() -> fixture(request -> { throw failure; }, () -> geometry(10)));
        AtomicBoolean screenCalled = new AtomicBoolean();
        try {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> SwingUiDriver.captureBracket(request(), fixture.transaction, rectangle -> { screenCalled.set(true); return image(); })));
            assertFalse(screenCalled.get());
        } finally { close(fixture); }
    }

    @Test void edtCaptureAndExpiredDeadlineAreRejectedBeforeBackend() throws Exception {
        Fixture fixture = edt(() -> fixture(SwingDriverCaptureTest::raw, () -> geometry(10)));
        AtomicBoolean called = new AtomicBoolean();
        try {
            edt(() -> {
                assertThrows(IllegalStateException.class,
                        () -> SwingUiDriver.captureBracket(request(), fixture.transaction, rectangle -> { called.set(true); return image(); }));
                return null;
            });
            PaintCaptureRequest expired = request(System.nanoTime() - 1);
            assertThrows(TimeoutException.class,
                    () -> SwingUiDriver.captureBracket(expired, fixture.transaction, rectangle -> { called.set(true); return image(); }));
            assertFalse(called.get());
        } finally { close(fixture); }
    }

    @Test void timeoutCancelsQueuedEdtTaskSoItCannotInstallLateObservers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean executed = new AtomicBoolean();
        SwingUtilities.invokeLater(() -> {
            entered.countDown();
            try { if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("EDT fixture deadline"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        });
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        try {
            assertThrows(TimeoutException.class, () -> SwingUiDriver.captureEdt(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50),
                    () -> { executed.set(true); return null; }));
        } finally { release.countDown(); edt(() -> null); }
        assertFalse(executed.get());
    }

    @Test void screenReturningAfterDeadlineCannotPublishDiagnosticPair() throws Exception {
        Fixture fixture = edt(() -> fixture(SwingDriverCaptureTest::raw, () -> geometry(10)));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(150);
        try {
            assertThrows(TimeoutException.class, () -> SwingUiDriver.captureBracket(request(deadline), fixture.transaction, rectangle -> {
                long remaining = deadline - System.nanoTime();
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                return image();
            }));
            // Если очередь уже исчерпала дедлайн, отказ до backend также правилен.
            assertFalse(edt(() -> fixture.transaction.input.acknowledged()));
        } finally { close(fixture); }
    }

    @Test void desiredPointerOrTabWithoutDeliveredEventNeverAcknowledgesInput() throws Exception {
        edt(() -> {
            try (var input = new SwingUiDriver.NativeCaptureInput(new JPanel())) {
                input.armPointer(new Point(10, 20)); assertFalse(input.acknowledged());
                input.armKey(); assertFalse(input.acknowledged());
            }
            return null;
        });
    }

    @Test void geometryOwnsDefensiveRectangleCopies() {
        Rectangle rectangle = new Rectangle(10, 20, 1200, 800);
        var boundary = new SwingUiDriver.CaptureGeometry(rectangle, transform(), false);
        rectangle.x = 100;
        Rectangle exposed = boundary.rectangle(); exposed.y = 100;
        assertEquals(new Rectangle(10, 20, 1200, 800), boundary.rectangle());
    }

    /** Лёгкий fixture сообщает только тестовые координаты; collector всё равно видит отсутствие native экрана. */
    private static Fixture fixture(java.util.function.Function<PaintCaptureRequest, UiDump> reader,
                                   java.util.function.Supplier<SwingUiDriver.CaptureGeometry> geometry) {
        JPanel root = new JPanel(null); root.setSize(1200, 800);
        JPanel toolbar = new JPanel(null); toolbar.setBounds(0, 0, 1200, 40); toolbar.putClientProperty("cp.id", "toolbar");
        JButton button = new JButton("fixture"); button.setBounds(0, 0, 30, 30); button.putClientProperty("cp.id", "edit.undo");
        toolbar.add(button); root.add(toolbar);
        int listeners = root.getPropertyChangeListeners().length;
        RepaintManager previous = RepaintManager.currentManager(root);
        AtomicReference<javax.swing.JComponent> current = new AtomicReference<>(root);
        var transaction = new SwingUiDriver.CaptureTransaction(root, current::get, reader, geometry);
        return new Fixture(root, current, transaction, previous, listeners);
    }

    /** Закрывает реальную транзакцию на EDT и проверяет возврат прежнего manager. */
    private static void close(Fixture fixture) throws Exception {
        edt(() -> { fixture.transaction.close(); assertSame(fixture.previous, RepaintManager.currentManager(fixture.root)); return null; });
    }

    /** Неизменяемое владение теста без имитации showing, active Window или успешного paint scope. */
    private record Fixture(JPanel root, AtomicReference<javax.swing.JComponent> current,
                           SwingUiDriver.CaptureTransaction transaction, RepaintManager previous, int listenersBefore) { }

    /** Создаёт только диагностический raw с совпадающей идентичностью запроса. */
    private static UiDump raw(PaintCaptureRequest request) {
        return new UiDump(1, "swing", request.scenario(), request.step(),
                new UiDump.Frame("fixture", "none", new UiDump.Size(1, 1), 1200, 800, Map.of()),
                List.of(), null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
    }
    /** Возвращает не native снимок, а растр отказавшего тестового backend. */
    private static BufferedImage image() { return new BufferedImage(1200, 800, BufferedImage.TYPE_INT_ARGB); }
    /** Возвращает измерение fixture, не утверждающее присутствие экрана. */
    private static SwingUiDriver.CaptureGeometry geometry(int x) { return new SwingUiDriver.CaptureGeometry(new Rectangle(x, 20, 1200, 800), transform(), false); }
    /** Тождественное преобразование тестового прямоугольника. */
    private static PaintObservation.Transform transform() { return new PaintObservation.Transform(1, 0, 0, 1, 0, 0); }
    /** Запрос с достаточным дедлайном для лёгкого отказавшего опыта. */
    private static PaintCaptureRequest request() { return request(System.nanoTime() + Duration.ofSeconds(5).toNanos()); }
    /** Запрос с явно заданным монотонным дедлайном. */
    private static PaintCaptureRequest request(long deadline) { return new PaintCaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 1, "fixture", "shot", 1, "0".repeat(64), Map.of(), deadline); }
    /** Выполняет только лёгкую подготовку/очистку на EDT; не создаёт native окна. */
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
