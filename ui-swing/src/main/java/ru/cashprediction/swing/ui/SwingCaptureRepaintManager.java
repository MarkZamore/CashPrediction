package ru.cashprediction.swing.ui;

import java.applet.Applet;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Window;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import javax.swing.JComponent;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;

/**
 * Временный наблюдатель запросов repaint одного корня, делегирующий прежнему менеджеру.
 * Устанавливается только явно для capture и не создаёт записей владельцев в журнале.
 * Dirty region, снятие invalid и возврат paintDirtyRegions не доказывают завершённый paint.
 * Полные реальные PaintScope открывает и завершает интегратор через SwingPaintJournal.
 * Внутренняя очередь прежнего менеджера вызывает его собственные проходы, минуя эту обёртку;
 * счётчики проходов здесь описывают только явные вызовы обёртки и не являются барьером экрана.
 * Перед обеими границами Robot bracket интегратор вызывает synchronizeJournal на EDT.
 * Публичные операции буферизации делегируются; внутренние package-private hooks Swing
 * недоступны обёртке и не являются источником доказательств рисования.
 */
@SuppressWarnings("removal")
public final class SwingCaptureRepaintManager extends RepaintManager implements AutoCloseable {
    private static final int EVENT_LIMIT = 128;
    private final JComponent root;
    private final SwingPaintJournal journal;
    private final RepaintManager previous;
    private final Object lock = new Object();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long generation;
    private long deliveredGeneration;
    private long droppedEvents;
    private long failedCalls;
    private long epochFailures;
    private long inFlightCalls;
    private boolean deliveryQueued;
    private boolean closed;
    private boolean epochOpen;

    /** Связывает уже проверенные объекты; глобальная установка выполняется отдельно. */
    private SwingCaptureRepaintManager(JComponent root, SwingPaintJournal journal, RepaintManager previous) {
        this.root = root;
        this.journal = journal;
        this.previous = previous;
    }

    /**
     * Устанавливает наблюдение на EDT и возвращает обязательный к закрытию handle.
     * Несовпадающий корень и повторная установка поверх такой же обёртки запрещены.
     * Обычный запуск приложения, не вызывающий install, остаётся без наблюдения.
     */
    public static SwingCaptureRepaintManager install(JComponent root, SwingPaintJournal journal) {
        edt(); Objects.requireNonNull(root); Objects.requireNonNull(journal);
        if (journal.root() != root) throw new IllegalArgumentException("different journal root");
        RepaintManager previous = RepaintManager.currentManager(root);
        if (previous instanceof SwingCaptureRepaintManager)
            throw new IllegalStateException("capture repaint manager already installed");
        SwingCaptureRepaintManager manager = new SwingCaptureRepaintManager(root, journal, previous);
        RepaintManager.setCurrentManager(manager);
        return manager;
    }

    /**
     * Начинает свежую эпоху журнала, предварительно доставляя фоновые инвалидации.
     * Сам по себе метод не запрашивает repaint, не ждёт EDT и не открывает PaintScope.
     */
    public void beginEpoch() {
        live(); synchronizeJournal();
        if (epochOpen) throw new IllegalStateException("capture epoch already open");
        journal.beginEpoch();
        epochOpen = true;
        synchronized (lock) { epochFailures = failedCalls; }
    }

    /**
     * Заканчивает эпоху после возврата реальных painter; открытые scope проверяет журнал.
     * Завершение не добавляет отсутствующий census и не подтверждает экранную готовность.
     * Ошибка делегата во время эпохи оставляет её инвалидированной до новой попытки.
     */
    public void finishEpoch() {
        live(); synchronizeJournal();
        if (!epochOpen) throw new IllegalStateException("no capture epoch");
        journal.finishEpoch(); epochOpen = false;
        synchronized (lock) {
            if (failedCalls != epochFailures || generation != deliveredGeneration || inFlightCalls != 0)
                journal.paintInvalidated();
        }
    }

    /** Прерывает эпоху после закрытия scope, сохраняя незавершённость наблюдения. */
    public void abortEpoch() {
        live(); synchronizeJournal();
        if (!epochOpen) throw new IllegalStateException("no capture epoch");
        journal.finishEpoch(); epochOpen = false; journal.paintInvalidated();
    }

    /**
     * Доставляет накопленные запросы в EDT-журнал без ожидания и рисования.
     * Один пакет фоновых событий даёт одну инвалидацию, но generation учитывает каждую фазу запроса.
     * Потеря владения глобальной установкой инвалидирует журнал и запрещает продолжение capture.
     */
    public void synchronizeJournal() {
        edt();
        synchronized (lock) {
            if (closed) throw new IllegalStateException("capture repaint manager closed");
            deliver();
        }
        if (RepaintManager.currentManager(root) != this) {
            journal.paintInvalidated();
            throw new IllegalStateException("capture repaint manager replaced");
        }
    }

    /** Возвращает ограниченную диагностику запросов; список событий не является census. */
    public Diagnostics diagnostics() {
        edt();
        synchronized (lock) {
            if (!closed) deliver();
            boolean owned = RepaintManager.currentManager(root) == this;
            if (!closed && !owned) journal.paintInvalidated();
            return new Diagnostics(owned, closed, epochOpen, generation, deliveredGeneration,
                    droppedEvents, failedCalls, inFlightCalls, List.copyOf(events));
        }
    }

    /**
     * Снимает наблюдение на EDT; прежний менеджер восстанавливается только при сохранённом владении.
     * Закрытая обёртка продолжает прозрачно делегировать вызовы из сохранённых сторонних ссылок.
     * При открытой эпохе журнал инвалидируется; scope и abortEpoch нужно завершить до close.
     */
    @Override public void close() {
        edt();
        synchronized (lock) {
            if (closed) return;
            deliver(); journal.paintInvalidated(); closed = true; events.clear();
        }
        if (RepaintManager.currentManager(root) == this) RepaintManager.setCurrentManager(previous);
    }

    /** Делегирует invalidation и сохраняет факт запроса, даже при последующем remove. */
    @Override public void addInvalidComponent(JComponent component) {
        forward(component, "invalid-add", 0, 0, 0, 0, () -> previous.addInvalidComponent(component));
    }

    /** Делегирует удаление invalid component, не объявляя layout завершённым. */
    @Override public void removeInvalidComponent(JComponent component) {
        forward(component, "invalid-remove", 0, 0, 0, 0, () -> previous.removeInvalidComponent(component));
    }

    /** Делегирует исходный прямоугольник без обрезки, объединения и подмены полного repaint. */
    @Override public void addDirtyRegion(JComponent component, int x, int y, int width, int height) {
        forward(component, "dirty-add", x, y, width, height,
                () -> previous.addDirtyRegion(component, x, y, width, height));
    }

    /** Делегирует dirty region окна; учитывает только физического предка capture-корня. */
    // JavaFX: Window → Swing: Window → Web: window surface.
    @Override public void addDirtyRegion(Window window, int x, int y, int width, int height) {
        forward(window, "window-dirty-add", x, y, width, height,
                () -> previous.addDirtyRegion(window, x, y, width, height));
    }

    /** Сохраняет совместимость публичного устаревшего API прежнего менеджера. */
    @Override public void addDirtyRegion(Applet applet, int x, int y, int width, int height) {
        forward(applet, "applet-dirty-add", x, y, width, height,
                () -> previous.addDirtyRegion(applet, x, y, width, height));
    }

    /** Читает настоящий dirty region у делегата, не ведя конкурирующую очередь repaint. */
    @Override public Rectangle getDirtyRegion(JComponent component) { return previous.getDirtyRegion(component); }

    /** Делегирует полный dirty marker; он не подтверждает полный завершённый paint. */
    @Override public void markCompletelyDirty(JComponent component) {
        forward(component, "dirty-full", 0, 0, 0, 0, () -> previous.markCompletelyDirty(component));
    }

    /** Делегирует очистку marker, сохраняя историю ABA в поколении журнала. */
    @Override public void markCompletelyClean(JComponent component) {
        forward(component, "dirty-clean", 0, 0, 0, 0, () -> previous.markCompletelyClean(component));
    }

    /** Возвращает полный dirty marker именно прежнего менеджера. */
    @Override public boolean isCompletelyDirty(JComponent component) { return previous.isCompletelyDirty(component); }

    /** Учитывает явно вызванный проход validate; автоматические проходы делегата сюда не входят. */
    @Override public void validateInvalidComponents() { pass("validate", previous::validateInvalidComponents); }

    /** Учитывает явный проход dirty paint, не создавая completed owner scopes. */
    @Override public void paintDirtyRegions() { pass("dirty-paint", previous::paintDirtyRegions); }

    /** Возвращает обычный буфер прежнего менеджера. */
    @Override public Image getOffscreenBuffer(Component component, int width, int height) {
        return previous.getOffscreenBuffer(component, width, height);
    }

    /** Возвращает volatile-буфер прежнего менеджера. */
    @Override public Image getVolatileOffscreenBuffer(Component component, int width, int height) {
        return previous.getVolatileOffscreenBuffer(component, width, height);
    }

    /** Меняет лимит буфера непосредственно у прежнего менеджера. */
    @Override public void setDoubleBufferMaximumSize(Dimension size) { previous.setDoubleBufferMaximumSize(size); }

    /** Читает лимит буфера прежнего менеджера. */
    @Override public Dimension getDoubleBufferMaximumSize() { return previous.getDoubleBufferMaximumSize(); }

    /** Меняет настройку буферизации непосредственно у прежнего менеджера. */
    @Override public void setDoubleBufferingEnabled(boolean enabled) { previous.setDoubleBufferingEnabled(enabled); }

    /** Читает настройку буферизации прежнего менеджера. */
    @Override public boolean isDoubleBufferingEnabled() { return previous.isDoubleBufferingEnabled(); }

    /** Сохраняет стандартную строковую диагностику делегата. */
    @Override public String toString() { return previous.toString(); }

    /** Ограниченное событие с исходными координатами, без удержания дерева компонентов. */
    public record Event(long generation, String operation, int x, int y, int width, int height) { }

    /** Неизменяемая диагностика, явно отделённая от завершённых проходов SwingPaintJournal. */
    public record Diagnostics(boolean owned, boolean closed, boolean epochOpen, long generation,
                              long deliveredGeneration, long droppedEvents, long failedCalls,
                              long inFlightCalls, List<Event> events) {
        /** Защищает список событий от изменения вызывающей стороной. */
        public Diagnostics { events = List.copyOf(events); }
    }

    /**
     * Учитывает вход и возврат относящейся к корню операции, не меняя исключения делегата.
     * Возврат тоже меняет поколение: фоновый запрос мог начать enqueue до Robot bracket,
     * а закончить его внутри bracket после доставки первой инвалидации на EDT.
     */
    private void forward(Component component, String operation, int x, int y, int width, int height, Runnable action) {
        boolean relevant = related(component);
        if (relevant) record(operation, x, y, width, height, false, 1);
        try { action.run(); }
        catch (RuntimeException | Error failure) {
            if (relevant) record(operation + "-failed", x, y, width, height, true, -1);
            throw failure;
        }
        if (relevant) record(operation + "-return", x, y, width, height, false, -1);
    }

    /** Отдельно отмечает вход и возврат явного прохода; исключение не превращается в успех. */
    private void pass(String operation, Runnable action) {
        record(operation + "-begin", 0, 0, 0, 0, false, 1);
        try { action.run(); }
        catch (RuntimeException | Error failure) {
            record(operation + "-failed", 0, 0, 0, 0, true, -1); throw failure;
        }
        record(operation + "-return", 0, 0, 0, 0, false, -1);
    }

    /** Проверяет физическое дерево под treeLock; предки учитываются консервативно без census. */
    private boolean related(Component component) {
        if (component == null) return false;
        synchronized (root.getTreeLock()) {
            for (Component current = component; current != null; current = current.getParent())
                if (current == root) return true;
            for (Component current = root; current != null; current = current.getParent())
                if (current == component) return true;
        }
        return false;
    }

    /** Сохраняет поколение сразу; доставку из любого фонового потока объединяет в один EDT callback. */
    private void record(String operation, int x, int y, int width, int height, boolean failed, int inFlightDelta) {
        synchronized (lock) {
            if (closed) return;
            generation++;
            inFlightCalls += inFlightDelta;
            if (failed) failedCalls++;
            if (events.size() == EVENT_LIMIT) { events.removeFirst(); droppedEvents++; }
            events.addLast(new Event(generation, operation, x, y, width, height));
            if (SwingUtilities.isEventDispatchThread()) deliver();
            else if (!deliveryQueued) {
                deliveryQueued = true;
                SwingUtilities.invokeLater(this::deliverQueued);
            }
        }
    }

    /** Доставляет только ещё активную инвалидацию, не оживляя закрытый handle. */
    private void deliverQueued() {
        synchronized (lock) {
            deliveryQueued = false;
            if (!closed) deliver();
        }
    }

    /** Вызывается на EDT под lock; поколения не теряются при конкурентных фоновых запросах. */
    private void deliver() {
        if (generation != deliveredGeneration || inFlightCalls != 0) {
            journal.paintInvalidated(); deliveredGeneration = generation;
        }
    }

    /** Проверяет явный capture-жизненный цикл на EDT. */
    private void live() {
        edt();
        synchronized (lock) { if (closed) throw new IllegalStateException("capture repaint manager closed"); }
    }

    /** Запрещает установку, границы эпох и закрытие вне EDT, оставляя repaint потокобезопасным. */
    private static void edt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("capture repaint manager requires EDT");
    }
}
