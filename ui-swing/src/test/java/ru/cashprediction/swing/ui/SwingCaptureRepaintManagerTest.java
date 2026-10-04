package ru.cashprediction.swing.ui;

import java.applet.Applet;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import static org.junit.jupiter.api.Assertions.*;

/** Проверяет отдельный наблюдатель repaint на лёгких компонентах, без окон, Robot и эталонных токенов. */
@SuppressWarnings("removal")
@ResourceLock("swing-repaint-manager")
class SwingCaptureRepaintManagerTest {
    /** Проверки входных данных и повторного install не меняют текущий менеджер. */
    @Test void installationChecksRootAndRejectsNestedOwnership() throws Exception {
        edt(() -> {
            try (Fixture fixture = new Fixture()) {
                JPanel other = new JPanel();
                assertThrows(IllegalArgumentException.class,
                        () -> SwingCaptureRepaintManager.install(other, fixture.journal));
                assertSame(fixture.previous, RepaintManager.currentManager(other));
                fixture.install();
                assertThrows(IllegalStateException.class,
                        () -> SwingCaptureRepaintManager.install(fixture.root, fixture.journal));
                assertSame(fixture.manager, RepaintManager.currentManager(fixture.root));
            }
            return null;
        });
    }

    /** Все публичные операции используют состояние и точные аргументы прежнего менеджера. */
    @Test void forwardsDirtyInvalidAndBufferApisToExistingManager() throws Exception {
        edt(() -> {
            try (Fixture fixture = new Fixture()) {
                JPanel child = new JPanel(); fixture.root.add(child);
                fixture.install(); var manager = fixture.manager; var delegate = fixture.previous; delegate.calls.clear();
                manager.addDirtyRegion(child, -3, 7, 17, 15);
                assertEquals(new Rectangle(-3, 7, 17, 15), manager.getDirtyRegion(child));
                manager.addInvalidComponent(child); manager.removeInvalidComponent(child);
                manager.markCompletelyDirty(child); assertTrue(manager.isCompletelyDirty(child));
                manager.markCompletelyClean(child); assertFalse(manager.isCompletelyDirty(child));
                assertTrue(manager.getDirtyRegion(child).isEmpty());
                manager.validateInvalidComponents(); manager.paintDirtyRegions();
                // JavaFX: Window → Swing: Window → Web: window surface.
                manager.addDirtyRegion((Window) null, 1, 2, 3, 4);
                manager.addDirtyRegion((Applet) null, 5, 6, 7, 8);
                assertSame(delegate.buffer, manager.getOffscreenBuffer(child, 31, 29));
                assertSame(child, delegate.bufferComponent); assertEquals(new Dimension(31, 29), delegate.bufferSize);
                assertSame(delegate.buffer, manager.getVolatileOffscreenBuffer(child, 37, 41));
                assertEquals(new Dimension(37, 41), delegate.bufferSize);
                Dimension maximum = new Dimension(123, 456); manager.setDoubleBufferMaximumSize(maximum);
                assertSame(maximum, manager.getDoubleBufferMaximumSize());
                manager.setDoubleBufferingEnabled(false); assertFalse(manager.isDoubleBufferingEnabled());
                manager.setDoubleBufferingEnabled(true); assertTrue(manager.isDoubleBufferingEnabled());
                assertEquals(delegate.toString(), manager.toString());
                assertEquals(List.of("dirty", "get-dirty", "invalid-add", "invalid-remove", "full", "is-full",
                        "clean", "is-full", "get-dirty", "validate", "paint", "window:1:2:3:4",
                        "applet:5:6:7:8", "buffer", "volatile-buffer", "set-maximum", "get-maximum",
                        "set-buffering", "is-buffering", "set-buffering", "is-buffering"), delegate.calls);
                var event = manager.diagnostics().events().getFirst();
                assertEquals("dirty-add", event.operation()); assertEquals(-3, event.x());
                assertEquals(7, event.y()); assertEquals(17, event.width()); assertEquals(15, event.height());
            }
            return null;
        });
    }

    /** Соседнее дерево не меняет журнал; корень, потомок и предок учитываются по текущей иерархии. */
    @Test void scopesRequestsToPhysicalRootAndTracksReparenting() throws Exception {
        edt(() -> {
            try (Fixture fixture = new Fixture()) {
                JPanel parent = new JPanel(), sibling = new JPanel(), child = new JPanel();
                parent.add(fixture.root); parent.add(sibling); fixture.root.add(child); fixture.install();
                long before = fixture.journal.snapshot().revision();
                fixture.manager.addDirtyRegion(sibling, 0, 0, 2, 2);
                fixture.manager.addInvalidComponent(sibling); fixture.manager.markCompletelyClean(sibling);
                assertEquals(before, fixture.journal.snapshot().revision());
                fixture.manager.addDirtyRegion(child, 0, 0, 2, 2);
                assertTrue(fixture.journal.snapshot().revision() > before);
                before = fixture.journal.snapshot().revision();
                fixture.manager.addDirtyRegion(parent, 0, 0, 2, 2);
                assertTrue(fixture.journal.snapshot().revision() > before);
                sibling.add(child); before = fixture.journal.snapshot().revision();
                fixture.manager.addDirtyRegion(child, 0, 0, 2, 2);
                assertEquals(before, fixture.journal.snapshot().revision());
                fixture.manager.addDirtyRegion(fixture.root, 0, 0, 0, -1);
                assertEquals(new Rectangle(0, 0, 0, -1), fixture.previous.regions.get(fixture.root));
                assertTrue(fixture.journal.snapshot().revision() > before);
            }
            return null;
        });
    }

    /** Даже полный dirty marker, clean и успешный flush не создают завершённых владельцев. */
    @Test void dirtyLifecycleNeverManufacturesPaintCensus() throws Exception {
        edt(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.install(); fixture.manager.beginEpoch();
                fixture.manager.markCompletelyDirty(fixture.root);
                fixture.manager.paintDirtyRegions(); fixture.manager.markCompletelyClean(fixture.root);
                fixture.manager.finishEpoch();
                var snapshot = fixture.journal.snapshot();
                assertTrue(snapshot.finished()); assertTrue(snapshot.owners().isEmpty());
                long before = snapshot.revision();
                fixture.manager.addDirtyRegion(fixture.root, 1, 1, 2, 2);
                fixture.manager.markCompletelyClean(fixture.root);
                snapshot = fixture.journal.snapshot();
                assertFalse(snapshot.finished()); assertTrue(snapshot.revision() > before);
                assertTrue(snapshot.owners().isEmpty());
            }
            return null;
        });
    }

    /** Собственные проходы делегата не выдаются за перехваченные проходы или экранный барьер. */
    @Test void delegateInternalPassesAreNotReportedAsObservedPaints() throws Exception {
        edt(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.install(); fixture.manager.beginEpoch();
                fixture.manager.addDirtyRegion(fixture.root, 0, 0, 80, 40);
                long before = fixture.manager.diagnostics().generation();
                fixture.previous.validateInvalidComponents(); fixture.previous.paintDirtyRegions();
                assertEquals(before, fixture.manager.diagnostics().generation());
                assertTrue(fixture.manager.diagnostics().events().stream()
                        .noneMatch(event -> event.operation().startsWith("dirty-paint")));
                fixture.manager.finishEpoch(); assertTrue(fixture.journal.snapshot().owners().isEmpty());
            }
            return null;
        });
    }

    /** Scope остаётся ответственностью журнала: offscreen и частичный clip не становятся экранным census. */
    @Test void respectsRealScopesPartialClipsAndAbort() throws Exception {
        edt(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.install(); var manager = fixture.manager;
                assertThrows(IllegalStateException.class, manager::finishEpoch);
                manager.beginEpoch(); assertThrows(IllegalStateException.class, manager::beginEpoch);
                Graphics2D graphics = new BufferedImage(80, 40, BufferedImage.TYPE_INT_ARGB).createGraphics();
                try {
                    graphics.setClip(0, 0, 12, 9);
                    try (var scope = fixture.journal.openOwner(fixture.root, graphics)) {
                        assertThrows(IllegalStateException.class, manager::finishEpoch); scope.complete();
                    }
                    manager.finishEpoch(); var owner = fixture.journal.snapshot().owners().getFirst();
                    assertFalse(owner.fullClip()); assertFalse(owner.screen()); assertFalse(owner.complete());
                    manager.beginEpoch();
                    try (var scope = fixture.journal.openOwner(fixture.root, graphics)) {
                        assertNotNull(fixture.journal.currentScope(fixture.root));
                    }
                    manager.abortEpoch(); assertFalse(fixture.journal.snapshot().finished());
                    assertFalse(fixture.journal.snapshot().owners().getFirst().complete());
                    long epoch = fixture.journal.snapshot().epoch();
                    manager.beginEpoch(); manager.finishEpoch();
                    assertEquals(epoch + 1, fixture.journal.snapshot().epoch());
                    assertTrue(fixture.journal.snapshot().owners().isEmpty());
                } finally { graphics.dispose(); }
            }
            return null;
        });
    }

    /** Ошибка исходного painter остаётся исходным исключением и инвалидирует завершение эпохи. */
    @Test void propagatesDelegateFailureAndRequiresFreshEpoch() throws Exception {
        edt(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.install(); fixture.manager.beginEpoch();
                RuntimeException failure = new IllegalStateException("delegate failure"); fixture.previous.failure = failure;
                assertSame(failure, assertThrows(IllegalStateException.class, fixture.manager::paintDirtyRegions));
                fixture.manager.finishEpoch(); assertFalse(fixture.journal.snapshot().finished());
                assertEquals(1, fixture.manager.diagnostics().failedCalls());
                assertTrue(fixture.manager.diagnostics().events().stream().anyMatch(e -> e.operation().equals("dirty-paint-failed")));
                fixture.previous.failure = null;
                fixture.manager.beginEpoch(); fixture.manager.finishEpoch(); assertTrue(fixture.journal.snapshot().finished());
                fixture.previous.failure = failure;
                assertSame(failure, assertThrows(IllegalStateException.class,
                        () -> fixture.manager.addDirtyRegion(fixture.root, 1, 2, 3, 4)));
                assertFalse(fixture.journal.snapshot().finished());
                assertEquals(2, fixture.manager.diagnostics().failedCalls());
            }
            return null;
        });
    }

    /** Граница capture внутри ещё выполняющегося enqueue не может стать стабильной. */
    @Test void inFlightDelegateCallKeepsEpochInvalidated() throws Exception {
        edt(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.install(); fixture.manager.beginEpoch();
                fixture.previous.beforeDirty = () -> {
                    assertEquals(1, fixture.manager.diagnostics().inFlightCalls());
                    fixture.manager.finishEpoch(); assertFalse(fixture.journal.snapshot().finished());
                };
                fixture.manager.addDirtyRegion(fixture.root, 0, 0, 10, 10);
                assertEquals(0, fixture.manager.diagnostics().inFlightCalls());
                assertFalse(fixture.journal.snapshot().finished());
                fixture.previous.beforeDirty = null;
                fixture.manager.beginEpoch(); fixture.manager.finishEpoch(); assertTrue(fixture.journal.snapshot().finished());
            }
            return null;
        });
    }

    /** Close восстанавливает только собственную установку и отключает сохранённые ссылки. */
    @Test void restoresOnlyWhileOwnedAndClosedHandleIsTransparent() throws Exception {
        edt(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.install(); fixture.manager.close(); fixture.manager.close();
                assertSame(fixture.previous, RepaintManager.currentManager(fixture.root));
                long before = fixture.journal.snapshot().revision();
                fixture.manager.addDirtyRegion(fixture.root, 2, 3, 4, 5); fixture.manager.paintDirtyRegions();
                assertEquals(before, fixture.journal.snapshot().revision());
                assertEquals(new Rectangle(2, 3, 4, 5), fixture.previous.regions.get(fixture.root));
                assertTrue(fixture.manager.diagnostics().closed());
                assertThrows(IllegalStateException.class, fixture.manager::beginEpoch);
            }
            try (Fixture fixture = new Fixture()) {
                fixture.install(); fixture.manager.beginEpoch(); fixture.manager.finishEpoch();
                RepaintManager replacement = new RepaintManager(); RepaintManager.setCurrentManager(replacement);
                assertThrows(IllegalStateException.class, fixture.manager::synchronizeJournal);
                assertFalse(fixture.journal.snapshot().finished()); assertFalse(fixture.manager.diagnostics().owned());
                fixture.manager.close(); assertSame(replacement, RepaintManager.currentManager(fixture.root));
            }
            return null;
        });
    }

    /** Количество событий ограничено, но каждое repaint ABA сохраняется в поколении. */
    @Test void boundsDiagnosticsWithoutLosingGenerations() throws Exception {
        edt(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.install();
                for (int index = 0; index < 300; index++) fixture.manager.addDirtyRegion(fixture.root, index, 0, 1, 1);
                var diagnostics = fixture.manager.diagnostics();
                assertEquals(600, diagnostics.generation()); assertEquals(600, diagnostics.deliveredGeneration());
                assertEquals(128, diagnostics.events().size()); assertEquals(472, diagnostics.droppedEvents());
                assertEquals(473, diagnostics.events().getFirst().generation());
                assertThrows(UnsupportedOperationException.class, () -> diagnostics.events().clear());
            }
            return null;
        });
    }

    /** Потокобезопасный repaint делегируется на вызывающем потоке, а journal меняется на EDT. */
    @Test void workerRequestsAreDeliveredOnEdtAndLifecycleRejectsWorkerCalls() throws Exception {
        Fixture fixture = edt(() -> { Fixture result = new Fixture(); result.install(); return result; });
        try {
            assertThrows(IllegalStateException.class, fixture.manager::beginEpoch);
            assertThrows(IllegalStateException.class, fixture.manager::finishEpoch);
            assertThrows(IllegalStateException.class, fixture.manager::abortEpoch);
            assertThrows(IllegalStateException.class, fixture.manager::synchronizeJournal);
            assertThrows(IllegalStateException.class, fixture.manager::diagnostics);
            assertThrows(IllegalStateException.class, fixture.manager::close);
            assertThrows(IllegalStateException.class,
                    () -> SwingCaptureRepaintManager.install(fixture.root, fixture.journal));
            long before = edt(() -> fixture.journal.snapshot().revision());
            fixture.manager.addDirtyRegion(fixture.root, 3, 4, 5, 6);
            fixture.manager.addInvalidComponent(fixture.root);
            edt(() -> {
                fixture.manager.synchronizeJournal();
                assertTrue(fixture.journal.snapshot().revision() > before);
                var diagnostics = fixture.manager.diagnostics();
                assertEquals(4, diagnostics.generation()); assertEquals(4, diagnostics.deliveredGeneration());
                assertEquals(new Rectangle(3, 4, 5, 6), fixture.previous.regions.get(fixture.root));
                return null;
            });
            assertSame(Thread.currentThread(), fixture.previous.lastCaller);
        } finally { edt(() -> { fixture.close(); return null; }); }
    }

    /** Закрытие незавершённой эпохи не объявляет успешное наблюдение. */
    @Test void closeInvalidatesOpenEpoch() throws Exception {
        edt(() -> {
            try (Fixture fixture = new Fixture()) {
                fixture.install(); fixture.manager.beginEpoch(); fixture.manager.close();
                assertFalse(fixture.journal.snapshot().finished());
                assertTrue(fixture.manager.diagnostics().epochOpen());
                assertSame(fixture.previous, RepaintManager.currentManager(fixture.root));
            }
            return null;
        });
    }

    /** Изолирует текущий менеджер и обязательно восстанавливает его после каждой проверки. */
    private static final class Fixture implements AutoCloseable {
        final JPanel root = new JPanel(null);
        final SwingPaintJournal journal = new SwingPaintJournal(root);
        final RepaintManager original = RepaintManager.currentManager(root);
        final RecordingManager previous = new RecordingManager();
        SwingCaptureRepaintManager manager;

        /** Создаёт только лёгкий корень и подменяет менеджер для проверки делегирования. */
        Fixture() { root.setSize(80, 40); RepaintManager.setCurrentManager(previous); }
        /** Устанавливает проверяемый наблюдатель. */
        void install() { manager = SwingCaptureRepaintManager.install(root, journal); }
        /** Восстанавливает исходный контекст даже после замены наблюдателя сторонним менеджером. */
        @Override public void close() {
            try { if (manager != null) manager.close(); }
            finally { RepaintManager.setCurrentManager(original); }
        }
    }

    /** Проверочный делегат без очереди настоящего экранного рисования. */
    private static final class RecordingManager extends RepaintManager {
        final List<String> calls = new ArrayList<>();
        final Map<JComponent, Rectangle> regions = new IdentityHashMap<>();
        final Map<JComponent, Boolean> full = new IdentityHashMap<>();
        final Image buffer = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Component bufferComponent;
        Dimension bufferSize;
        Dimension maximum;
        boolean buffering;
        RuntimeException failure;
        Runnable beforeDirty;
        Thread lastCaller;

        /** Записывает invalid-add без обращения к экрану. */
        @Override public void addInvalidComponent(JComponent component) { calls.add("invalid-add"); lastCaller = Thread.currentThread(); }
        /** Записывает invalid-remove. */
        @Override public void removeInvalidComponent(JComponent component) { calls.add("invalid-remove"); }
        /** Сохраняет исходные координаты, включая пустые и отрицательные размеры. */
        @Override public void addDirtyRegion(JComponent component, int x, int y, int width, int height) {
            calls.add("dirty"); lastCaller = Thread.currentThread();
            if (beforeDirty != null) beforeDirty.run();
            if (failure != null) throw failure;
            regions.put(component, new Rectangle(x, y, width, height));
        }
        /** Проверяет выбор перегрузки для окна без создания окна. */
        // JavaFX: Window → Swing: Window → Web: window surface.
        @Override public void addDirtyRegion(Window window, int x, int y, int width, int height) {
            calls.add("window:" + x + ":" + y + ":" + width + ":" + height);
        }
        /** Проверяет выбор устаревшей перегрузки без создания applet. */
        @Override public void addDirtyRegion(Applet applet, int x, int y, int width, int height) {
            calls.add("applet:" + x + ":" + y + ":" + width + ":" + height);
        }
        /** Возвращает только состояние проверочного делегата. */
        @Override public Rectangle getDirtyRegion(JComponent component) {
            calls.add("get-dirty"); return regions.getOrDefault(component, new Rectangle());
        }
        /** Сохраняет полный marker. */
        @Override public void markCompletelyDirty(JComponent component) { calls.add("full"); full.put(component, true); }
        /** Снимает marker и dirty region. */
        @Override public void markCompletelyClean(JComponent component) { calls.add("clean"); full.remove(component); regions.remove(component); }
        /** Читает полный marker. */
        @Override public boolean isCompletelyDirty(JComponent component) { calls.add("is-full"); return full.containsKey(component); }
        /** Записывает явный validate без окна. */
        @Override public void validateInvalidComponents() { calls.add("validate"); }
        /** Имитирует нормальный возврат или исходную ошибку, не вызывая paint компонентов. */
        @Override public void paintDirtyRegions() { calls.add("paint"); if (failure != null) throw failure; }
        /** Возвращает узнаваемый объект буфера. */
        @Override public Image getOffscreenBuffer(Component component, int width, int height) {
            calls.add("buffer"); bufferComponent = component; bufferSize = new Dimension(width, height); return buffer;
        }
        /** Возвращает узнаваемый объект для отдельной volatile-перегрузки. */
        @Override public Image getVolatileOffscreenBuffer(Component component, int width, int height) {
            calls.add("volatile-buffer"); bufferComponent = component; bufferSize = new Dimension(width, height); return buffer;
        }
        /** Сохраняет точный объект настройки. */
        @Override public void setDoubleBufferMaximumSize(Dimension size) { calls.add("set-maximum"); maximum = size; }
        /** Возвращает точный объект настройки. */
        @Override public Dimension getDoubleBufferMaximumSize() { calls.add("get-maximum"); return maximum; }
        /** Сохраняет настройку буферизации. */
        @Override public void setDoubleBufferingEnabled(boolean enabled) { calls.add("set-buffering"); buffering = enabled; }
        /** Читает сохранённую настройку. */
        @Override public boolean isDoubleBufferingEnabled() { calls.add("is-buffering"); return buffering; }
    }

    /** Выполняет модульную проверку на EDT без GUI и без ожидания завершения экранного repaint. */
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
