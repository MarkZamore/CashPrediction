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
import ru.cashprediction.core.ui.selftest.paint.PaintCaptureRequest;
import ru.cashprediction.core.ui.selftest.paint.PaintObservation;
import ru.cashprediction.core.ui.selftest.paint.WidgetCapture;

/** Драйвер сценариев: ввод, выбор и команды проходят через настоящие виджеты. */
public final class FxUiDriver implements UiDriver {
    private final FxUiPort port;
    private final AppEnvironment environment;
    private final FxUiDumper dumper;
    private volatile long pendingDelay;
    private Node hoveredCard;
    private final java.util.concurrent.atomic.AtomicBoolean capturing = new java.util.concurrent.atomic.AtomicBoolean();

    /** Сохраняет исходный конкретный порт отдельно от контроллера. */
    public FxUiDriver(FxUiPort port, AppController controller, AppEnvironment environment) {
        this.port = port; this.environment = environment; dumper = new FxUiDumper(port, controller);
    }
    /** Возвращает идентичность настоящего клиента. */
    @Override public ClientKind client() { return ClientKind.FX; }
    /** Исполняет шаг на потоке JavaFX через события виджетов. */
    @Override public void execute(SelfTestCommand command) {
        if (command instanceof SelfTestCommand.Hover hover && hover.target().startsWith("menu:")) {
            Node node = fx(() -> {
                leaveCard();
                port.stage.toFront(); port.stage.requestFocus();
                MenuItem item = port.menus.byId.get(hover.target().substring(5));
                if (item == null) throw new IllegalStateException(hover.target());
                showAncestors(item);
                return item.getStyleableNode();
            });
            awaitGestureLayout();
            hoverMenuNode(node);
            pendingDelay = 600;
            return;
        }
        if (command instanceof SelfTestCommand.Fill fill) {
            fx(() -> { leaveCard(); return null; });
            fill.values().forEach((id, value) -> setCommitted(fx(() -> fieldMap(fill.window()).get(id)), value));
            return;
        }
        if (command instanceof SelfTestCommand.Field field) {
            fx(() -> { leaveCard(); return null; });
            var widget = fx(() -> fieldMap(field.windowTitle()).values().stream()
                    .filter(f -> f.label.getText().equals(field.label())).findFirst().orElseThrow());
            setCommitted(widget, field.text());
            return;
        }
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
            return ru.cashprediction.fx.ui.PngEncoder.encode(image);
        });
    }

    /**
     * Выполняет физический ввод, ждёт настоящий post-layout callback и читает raw/PNG
     * одним вызовом FX-потока. Неполный журнал или отсутствующая краска узла дают
     * явный Unsupported с диагностической попыткой, а не успешный WidgetCapture.
     * @param request идентичность опыта, состояния карточек и общий монотонный дедлайн
     * @return только подтверждённый согласованный захват
     * @throws Exception при дедлайне, неподдержанном доказательстве или ошибке клиента
     */
    @Override public WidgetCapture capture(PaintCaptureRequest request) throws Exception {
        var result = captureDiagnostic(request);
        if (!result.observation().unsupported().isEmpty() || !result.observation().synchronization().stable())
            throw new UnsupportedCapture(result);
        return result.requireSupported(request);
    }

    /**
     * Собирает реальную диагностическую попытку, включая Unsupported и нестабильный bracket.
     * Результат не разрешает запись успешного commit и не удостоверяет нативный проход S5.
     * @param request запрос опыта с общим дедлайном для очереди, ввода и снимка
     * @return исходные raw/PNG и фактические наблюдения с причинами отказа
     * @throws Exception при недоступном вводе, дедлайне или ошибке снятия сцены
     */
    public FxPaintCollector.Result captureDiagnostic(PaintCaptureRequest request) throws Exception {
        Objects.requireNonNull(request);
        if (Platform.isFxApplicationThread()) throw new IllegalStateException("capture requires worker thread");
        remaining(request.deadlineNanos());
        if (!capturing.compareAndSet(false, true)) throw new IllegalStateException("capture already running");
        CaptureTransaction[] opened = new CaptureTransaction[1];
        try {
            Map<String, String> artifacts = artifactDigests(request.deadlineNanos());
            CaptureTransaction transaction = captureFx(request.deadlineNanos(), () -> {
                if (port.main == null || !port.stage.isShowing()) throw new UnsupportedOperationException("main scene unavailable");
                var value = new CaptureTransaction(port.stage, r -> dumper.dump(r.scenario(), r.step()), artifacts);
                opened[0] = value;
                return value;
            });
            long delay = captureFx(request.deadlineNanos(), () -> {
                long value = pendingDelay; pendingDelay = 0; return value;
            });
            captureDelay(request, delay + ru.cashprediction.core.session.SessionRecorder.DEBOUNCE.toMillis() + 40);
            prepareCaptureInput(request, transaction);
            // Реальные hover/focus таймеры 350 мс должны успеть показать свои поверхности до переписи.
            captureDelay(request, ru.cashprediction.core.session.SessionRecorder.DEBOUNCE.toMillis() + 40);
            awaitCapturePulse(request, transaction);
            return captureFx(request.deadlineNanos(), () -> {
                try {
                    return new FxPaintCollector().collect(request, port.stage.getScene(),
                            port.main.toolbar.root, port.main.summary, transaction);
                } finally { transaction.close(); }
            });
        } finally {
            // Даже отменённая задача подключения могла начать исполнение: очистка стоит после неё в FX-очереди.
            Platform.runLater(() -> { if (opened[0] != null) opened[0].close(); });
            capturing.set(false);
        }
    }

    /** Выдерживает существующие задержки сценария в пределах дедлайна, не подтверждая этим краску. */
    private static void captureDelay(PaintCaptureRequest request, long millis) throws Exception {
        long nanos = TimeUnit.MILLISECONDS.toNanos(millis);
        long left = remaining(request.deadlineNanos());
        if (nanos >= left) throw new TimeoutException("FX capture settle exceeds deadline");
        TimeUnit.NANOSECONDS.sleep(nanos);
        captureFx(request.deadlineNanos(), () -> null);
    }

    /** Хеширует только фактические запущенные JAR, а не каталог исходников или указанный в плане хеш. */
    private static Map<String, String> artifactDigests(long deadline) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (Class<?> type : List.of(FxUiDriver.class, UiDriver.class)) {
            remaining(deadline);
            var source = type.getProtectionDomain().getCodeSource();
            if (source == null || !"file".equals(source.getLocation().getProtocol())) continue;
            var path = java.nio.file.Path.of(source.getLocation().toURI());
            if (!java.nio.file.Files.isRegularFile(path)) continue;
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var stream = java.nio.file.Files.newInputStream(path)) {
                byte[] buffer = new byte[65536]; int length;
                while ((length = stream.read(buffer)) >= 0) { remaining(deadline); digest.update(buffer, 0, length); }
            }
            result.put(type.getName(), HexFormat.of().formatHex(digest.digest()));
        }
        return Map.copyOf(result);
    }

    /** Сохраняет фактическую неуспешную попытку без создания commit или записи файлов. */
    static final class UnsupportedCapture extends UnsupportedOperationException {
        private final FxPaintCollector.Result result;
        /** Причины содержат неподдержанные свойства и фактические изменения bracket. */
        UnsupportedCapture(FxPaintCollector.Result result) {
            super("FX capture unsupported: " + result.observation().unsupported()
                    + "; changes=" + result.observation().synchronization().changes());
            this.result = result;
        }
        /** Возвращает диагностические raw/PNG/наблюдения; это не успешная пара. */
        public FxPaintCollector.Result result() { return result; }
    }

    /** Перемещает Robot и использует штатную клавиатурную навигацию без fireEvent/requestFocus узла. */
    private void prepareCaptureInput(PaintCaptureRequest request, CaptureTransaction transaction) throws Exception {
        Node target = captureFx(request.deadlineNanos(), () -> {
            Node active = null;
            for (var entry : request.cardStates().entrySet()) {
                Node card = port.main.summary.getChildren().stream()
                        .filter(n -> entry.getKey().equals(FxUiDumper.id(n))).findFirst()
                        .orElseThrow(() -> new UnsupportedOperationException("capture card unavailable: " + entry.getKey()));
                if (entry.getValue() != PaintCaptureRequest.CardState.NORMAL) active = card;
            }
            port.stage.requestFocus();
            // Сначала физически уводим указатель на статус, чтобы получить новый native move даже при повторе опыта.
            transaction.move(port.main.status);
            return active;
        });
        try {
            awaitCaptureCondition(request, () -> transaction.pointerAcknowledged() && port.stage.isFocused());
        } catch (TimeoutException timeout) {
            var failure = new TimeoutException("FX native pointer acknowledgement: " + transaction.inputStatus());
            failure.initCause(timeout);
            throw failure;
        }
        boolean focus = request.cardStates().containsValue(PaintCaptureRequest.CardState.FOCUS);
        if (target != null && !focus) {
            captureFx(request.deadlineNanos(), () -> { transaction.move(target); return null; });
            awaitCaptureInput(request, transaction, () -> transaction.pointerAcknowledged() && target.isHover());
        }
        // Tab проходит настоящий диспетчер. Счётчик ограничивает циклическую/недоступную навигацию.
        for (int count = 0; count < 128; count++) {
            boolean reached = captureFx(request.deadlineNanos(), () -> {
                Node owner = port.stage.getScene().getFocusOwner();
                if (focus) return owner != null && descendant(owner, target) && owner.isFocused();
                return owner == null || port.main.summary.getChildren().stream().noneMatch(n -> descendant(owner, n));
            });
            if (reached) return;
            long sequence = captureFx(request.deadlineNanos(), transaction::tab);
            awaitCaptureInput(request, transaction, () -> transaction.keyAcknowledged(sequence));
        }
        throw new UnsupportedOperationException("physical card focus traversal unavailable");
    }

    /** Сохраняет диагностику текущей команды также при отказе hover или клавиатурной доставки. */
    private static void awaitCaptureInput(PaintCaptureRequest request, CaptureTransaction transaction,
                                          Supplier<Boolean> condition) throws Exception {
        try { awaitCaptureCondition(request, condition); }
        catch (TimeoutException timeout) {
            var failure = new TimeoutException("FX native input acknowledgement: " + transaction.inputStatus());
            failure.initCause(timeout); throw failure;
        }
    }

    /** Ожидает callback всех наблюдаемых сцен; callback не означает завершение краски узлов. */
    private static void awaitCapturePulse(PaintCaptureRequest request, CaptureTransaction transaction) throws Exception {
        captureFx(request.deadlineNanos(), () -> { transaction.attachScenes(); Platform.requestNextPulse(); return null; });
        awaitCaptureCondition(request, () -> {
            if (transaction.attachScenes()) Platform.requestNextPulse();
            return transaction.stamp().postLayoutPulse();
        });
    }

    /** Проверяет только фактическое условие на FX-потоке в пределах общего дедлайна. */
    private static void awaitCaptureCondition(PaintCaptureRequest request, Supplier<Boolean> condition) throws Exception {
        while (!captureFx(request.deadlineNanos(), condition::get)) {
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining(request.deadlineNanos()), TimeUnit.MILLISECONDS.toNanos(5)));
        }
    }

    /** Вычисляет оставшееся время с поддержкой перехода nanoTime через границу long. */
    private static long remaining(long deadline) throws TimeoutException {
        long left = deadline - System.nanoTime();
        if (left <= 0) throw new TimeoutException("FX capture deadline exceeded");
        return left;
    }

    /** Ограничивает также ожидание FX-очереди; ещё не начавшаяся отменённая задача не выполняет ввод. */
    private static <T> T captureFx(long deadline, Callable<T> action) throws Exception {
        remaining(deadline);
        FutureTask<T> task = new FutureTask<>(() -> { remaining(deadline); return action.call(); });
        Platform.runLater(task);
        try { return task.get(remaining(deadline), TimeUnit.NANOSECONDS); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            if (error.getCause() instanceof Error cause) throw cause;
            throw new IllegalStateException(error.getCause());
        } catch (TimeoutException | InterruptedException error) { task.cancel(false); throw error; }
    }

    /** Проверяет фактическое вложение цели native hit-test или владельца фокуса. */
    private static boolean descendant(Node node, Node ancestor) {
        for (Node current = node; current != null; current = current.getParent()) if (current == ancestor) return true;
        return false;
    }

    /**
     * Измеряет именно порядок Window.getWindows и округлённые смещения обычного композитора.
     * Этот порядок не удостоверяет физический z-order рабочего стола; collector отклоняет overlay-окклюзию.
     */
    static FxPaintCollector.Frame compositorFrame(javafx.stage.Window main) {
        var scene = main.getScene();
        var base = scene.getRoot().localToScreen(scene.getRoot().getBoundsInLocal());
        if (base == null) throw new UnsupportedOperationException("compositor screen origin unavailable");
        var unit = new PaintObservation.Transform(1, 0, 0, 1, 0, 0);
        List<FxPaintCollector.SurfaceReading> surfaces = new ArrayList<>();
        surfaces.add(new FxPaintCollector.SurfaceReading(scene, unit, 0));
        for (var window : javafx.stage.Window.getWindows().stream()
                .filter(w -> w != main && w.isShowing() && w.getScene() != null).toList()) {
            var location = window.getScene().getRoot().localToScreen(window.getScene().getRoot().getBoundsInLocal());
            if (location == null) throw new UnsupportedOperationException("overlay screen origin unavailable");
            int dx = (int) Math.round(location.getMinX() - base.getMinX());
            int dy = (int) Math.round(location.getMinY() - base.getMinY());
            surfaces.add(new FxPaintCollector.SurfaceReading(window.getScene(),
                    new PaintObservation.Transform(1, 0, 0, 1, dx, dy), surfaces.size()));
        }
        return new FxPaintCollector.Frame(unit, (int) Math.ceil(scene.getWidth()), (int) Math.ceil(scene.getHeight()), surfaces);
    }

    /** Повторяет исходную композицию screenshot: замену ARGB пикселей и обрезку по основной сцене. */
    static byte[] compose(FxPaintCollector.Frame frame,
                          java.util.function.Function<javafx.scene.Scene, javafx.scene.image.WritableImage> snapshot) {
        var image = snapshot.apply(frame.surfaces().getFirst().scene());
        for (var surface : frame.surfaces().subList(1, frame.surfaces().size())) {
            var overlay = snapshot.apply(surface.scene());
            int dx = (int) surface.sceneToPng().tx(), dy = (int) surface.sceneToPng().ty();
            for (int y = 0; y < overlay.getHeight(); y++) for (int x = 0; x < overlay.getWidth(); x++) {
                if (dx + x >= 0 && dy + y >= 0 && dx + x < image.getWidth() && dy + y < image.getHeight())
                    image.getPixelWriter().setArgb(dx + x, dy + y, overlay.getPixelReader().getArgb(x, y));
            }
        }
        return PngEncoder.encode(image);
    }

    /** Зависимости коллектора: настоящий журнал, источник Image и события native Robot одной попытки. */
    static final class CaptureTransaction implements FxPaintCollector.Transaction, AutoCloseable {
        private final javafx.stage.Window main;
        private final java.util.function.Function<PaintCaptureRequest, UiDump> raw;
        private final Map<String, String> artifacts;
        private final FxCaptureJournal journal = new FxCaptureJournal();
        private final javafx.scene.robot.Robot robot = new javafx.scene.robot.Robot();
        private final List<javafx.scene.Scene> scenes = new ArrayList<>();
        private javafx.event.EventHandler<MouseEvent> pointerListener = event -> pointerEvent(0, event);
        private javafx.event.EventHandler<KeyEvent> keyListener = event -> keyEvent(0, event);
        private final FxInputAckMatcher ack = new FxInputAckMatcher();
        private Node picked;
        private long pickedEpoch = -1;
        private volatile String pointerStatus = "unobserved";
        private javafx.geometry.Point2D requestedPointer;
        private long sequence;
        private final List<SnapshotCompletion> snapshots = new ArrayList<>();
        private String modality = "none";
        private boolean closed;

        /** Подключает listeners до ввода и bracket, не создавая фиктивной эпохи краски. */
        CaptureTransaction(javafx.stage.Window main, java.util.function.Function<PaintCaptureRequest, UiDump> raw,
                           Map<String, String> artifacts) {
            this.main = main; this.raw = raw; this.artifacts = Map.copyOf(artifacts);
            try { attachScenes(); } catch (RuntimeException | Error error) { close(); throw error; }
        }
        /** Подключает новые поверхности и снимает исчезнувшие, которым больше не приходит pulse. */
        boolean attachScenes() {
            List<javafx.scene.Scene> shown = new ArrayList<>(); shown.add(main.getScene());
            javafx.stage.Window.getWindows().stream().filter(w -> w.isShowing() && w.getScene() != null)
                    .map(javafx.stage.Window::getScene).forEach(shown::add);
            boolean changed = false;
            for (var scene : List.copyOf(scenes)) if (!shown.contains(scene)) {
                scene.removeEventFilter(MouseEvent.MOUSE_MOVED, pointerListener);
                scene.removeEventFilter(KeyEvent.KEY_RELEASED, keyListener);
                scene.removeEventFilter(KeyEvent.KEY_PRESSED, keyListener);
                journal.detach(scene); scenes.remove(scene); changed = true;
            }
            for (var scene : shown) if (!scenes.contains(scene)) {
                journal.attach(scene); scenes.add(scene);
                scene.addEventFilter(MouseEvent.MOUSE_MOVED, pointerListener);
                scene.addEventFilter(KeyEvent.KEY_RELEASED, keyListener);
                scene.addEventFilter(KeyEvent.KEY_PRESSED, keyListener);
                changed = true;
            }
            return changed;
        }
        /** Читает pickResult доставленного события и сверяет координаты с физическим указателем ОС. */
        private void pointerEvent(long listenerSequence, MouseEvent event) {
            long callbackNanos = System.nanoTime();
            var physical = robot.getMousePosition();
            boolean accepted = ack.pointer(listenerSequence, callbackNanos,
                    new FxInputAckMatcher.Point(event.getScreenX(), event.getScreenY()), point(physical),
                    event.isSynthesized(), event.getPickResult() != null && event.getPickResult().getIntersectedNode() != null);
            if (listenerSequence == sequence) {
                picked = accepted ? event.getPickResult().getIntersectedNode() : null;
                pickedEpoch = accepted ? journal.stamp().epoch() : -1;
            }
            pointerStatus = ack.status(point(physical)) + "; focused=" + main.isFocused();
        }
        /** Учитывает press и release Tab через listener конкретной команды, без native timestamp. */
        private void keyEvent(long listenerSequence, KeyEvent event) {
            ack.key(listenerSequence, System.nanoTime(), event.getCode() == KeyCode.TAB,
                    event.getEventType() == KeyEvent.KEY_PRESSED);
            pointerStatus = ack.status(point(robot.getMousePosition())) + "; focused=" + main.isFocused();
        }
        /** Задаёт физическое перемещение в центр живой области, без изменения hoverProperty. */
        void move(Node node) {
            var box = node.localToScreen(node.getLayoutBounds());
            if (box == null || !node.isVisible()) throw new UnsupportedOperationException("physical pointer target unavailable");
            requestedPointer = new javafx.geometry.Point2D(Math.rint((box.getMinX() + box.getMaxX()) / 2),
                    Math.rint((box.getMinY() + box.getMaxY()) / 2));
            picked = null; pickedEpoch = -1; modality = "pointer";
            // Две немедленные команды могут быть объединены ОС и вернуться на прежнее место без события.
            // Выбираем одну другую точку внутри области, когда центр уже занят указателем.
            var previous = robot.getMousePosition();
            if (previous.distance(requestedPointer) <= 2) {
                double x = requestedPointer.getX() + 4;
                if (x >= box.getMaxX()) x = requestedPointer.getX() - 4;
                if (x <= box.getMinX()) throw new UnsupportedOperationException("physical pointer target too narrow");
                requestedPointer = new javafx.geometry.Point2D(x, requestedPointer.getY());
            }
            sequence = ack.armPointer(point(requestedPointer), System.nanoTime());
            bindInputListeners(sequence);
            pointerStatus = ack.status(point(previous)) + "; focused=" + main.isFocused();
            robot.mouseMove(requestedPointer);
        }
        /** Проверяет native move последней команды, а не наличие старого hover. */
        boolean pointerAcknowledged() {
            var point = robot.getMousePosition();
            pointerStatus = ack.status(point(point)) + "; focused=" + main.isFocused();
            return ack.pointerAcknowledged(point(point));
        }
        /** Возвращает последний снимок фактов; чтение не инициирует новый ввод или краску. */
        String inputStatus() { return pointerStatus; }

        /** Фиксирует поколение в замыкании listener, не назначая старому callback новую команду. */
        private void bindInputListeners(long listenerSequence) {
            for (var scene : scenes) {
                scene.removeEventFilter(MouseEvent.MOUSE_MOVED, pointerListener);
                scene.removeEventFilter(KeyEvent.KEY_PRESSED, keyListener);
                scene.removeEventFilter(KeyEvent.KEY_RELEASED, keyListener);
            }
            pointerListener = event -> pointerEvent(listenerSequence, event);
            keyListener = event -> keyEvent(listenerSequence, event);
            for (var scene : scenes) {
                scene.addEventFilter(MouseEvent.MOUSE_MOVED, pointerListener);
                scene.addEventFilter(KeyEvent.KEY_PRESSED, keyListener);
                scene.addEventFilter(KeyEvent.KEY_RELEASED, keyListener);
            }
        }

        /** Переносит фактические координаты без исправления NaN, масштаба или sentinel. */
        private static FxInputAckMatcher.Point point(javafx.geometry.Point2D value) {
            return new FxInputAckMatcher.Point(value.getX(), value.getY());
        }
        /** Посылает сбалансированную пару физического Tab, не оставляя нажатую клавишу при ошибке. */
        long tab() {
            modality = "keyboard"; sequence = ack.armKey(System.nanoTime());
            bindInputListeners(sequence);
            pointerStatus = ack.status(point(robot.getMousePosition())) + "; focused=" + main.isFocused();
            try { robot.keyPress(KeyCode.TAB); } finally { robot.keyRelease(KeyCode.TAB); }
            return sequence;
        }
        /** Проверяет доставку освобождения именно последней клавиатурной команды. */
        boolean keyAcknowledged(long expected) { return ack.keyAcknowledged(expected); }
        /** Читает ненормализованный дамп в том же FX callback, что и PNG. */
        @Override public UiDump raw(PaintCaptureRequest request) { return raw.apply(request); }
        /** Снимает реальные сцены через журнал, по тем же правилам композиции, что обычный screenshot. */
        @Override public byte[] png() {
            attachScenes(); snapshots.clear();
            byte[] bytes = compose(frame(), scene -> {
                long before = journal.stamp().renderGeneration();
                var image = journal.snapshot(scene);
                snapshots.add(new SnapshotCompletion(scene, before, journal.stamp().renderGeneration(),
                        Objects.requireNonNull(journal.lastSnapshotGeneration(scene)), (int) image.getWidth(), (int) image.getHeight()));
                return image;
            });
            return bytes;
        }
        /** Возвращает независимые факты завершения snapshot, не разрешая ими переход в stable. */
        List<SnapshotCompletion> snapshots() { return List.copyOf(snapshots); }
        /** Читает точные преобразования фактического композитора до и после снимка. */
        @Override public FxPaintCollector.Frame frame() { return compositorFrame(main); }
        /** Передаёт реальные поколения, не скрывая изменения snapshot внутри bracket. */
        @Override public FxPaintCollector.Stamp stamp() { return journal.stamp(); }
        /** Возвращает указатель и native pick; изменение эпохи после события делает старый hit неизвестным. */
        @Override public FxPaintCollector.Input input() {
            var point = robot.getMousePosition();
            boolean delivered = pointerAcknowledged();
            boolean known = delivered && pickedEpoch == journal.stamp().epoch();
            return new FxPaintCollector.Input(new PaintObservation.Point(point.getX(), point.getY()),
                    known ? picked : null, known, modality, sequence,
                    delivered && ("pointer".equals(modality) || ack.keyAcknowledged(sequence)));
        }
        /** Связывает установленный Image с удержанными байтами конкретного декодирования. */
        @Override public FxPaintCollector.Source source(javafx.scene.image.Image installed) {
            return FxIcons.decodedSource(installed).map(s -> new FxPaintCollector.Source(installed, s.bytes(), s.source())).orElse(null);
        }
        /** Отсутствующий hook краски узла остаётся null, даже после успешного snapshot сцены. */
        @Override public Long lastPaintEpoch(Node node) { return journal.lastPaintEpoch(node); }
        /** Читает среду и имена фактических шрифтов; хеши несуществующего manifest не выдумывает. */
        @Override public PaintObservation.Environment environment() {
            Set<String> fonts = new TreeSet<>(); readFonts(main.getScene().getRoot(), fonts);
            return new PaintObservation.Environment(System.getProperty("os.name") + " " + System.getProperty("os.version"),
                    System.getProperty("java.runtime.version"), "JavaFX " + System.getProperty("javafx.version", "unknown") + " Scene.snapshot",
                    main.getOutputScaleX(), "observed-fonts=" + String.join(",", fonts)
                    + "; font-load certification unavailable; artifact manifest " + (artifacts.isEmpty() ? "unavailable" : "partial"), artifacts);
        }
        /** Переписывает шрифты созданных Text, включая skin-узлы, без ожиданий загрузки. */
        private static void readFonts(Node node, Set<String> fonts) {
            if (node instanceof javafx.scene.text.Text text) fonts.add(text.getFont().getName());
            if (node instanceof javafx.scene.Parent parent) parent.getChildrenUnmodifiable().forEach(n -> readFonts(n, fonts));
        }
        /** Снимает listeners всех сцен и журнала; повторное закрытие безопасно. */
        @Override public void close() {
            if (closed) return; closed = true;
            for (var scene : scenes) {
                scene.removeEventFilter(MouseEvent.MOUSE_MOVED, pointerListener);
                scene.removeEventFilter(KeyEvent.KEY_RELEASED, keyListener);
                scene.removeEventFilter(KeyEvent.KEY_PRESSED, keyListener);
            }
            journal.close(); scenes.clear();
        }
    }

    /**
     * Факт успешного возврата Scene.snapshot с объектом сцены и настоящими размерами пикселей.
     * Поколения прочитаны у журнала непосредственно вокруг вызова; это не экранная презентация,
     * не краска отдельных узлов и не разрешение игнорировать renderGeneration в DTO.
     */
    record SnapshotCompletion(javafx.scene.Scene scene, long generationBefore, long generationAfter,
                              long sceneSnapshotGeneration, int pixelWidth, int pixelHeight) { }

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
            case SelfTestCommand.FilterType c -> { TextField field = (TextField) port.main.toolbar.widgets.get("tb.filter"); field.requestFocus(); field.setText(c.text()); pendingDelay = ru.cashprediction.core.ui.token.DesignTokens.FILTER_DEBOUNCE_MS; }
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

    /** Завершённая CPS-правка текстового поля требует настоящего focusLost, не ручного вызова commit. */
    private void setCommitted(FxFieldWidgets field, String value) {
        fx(() -> {
            if (field == null || field.control.isDisabled() || !field.root.isVisible())
                throw new IllegalStateException("field unavailable");
            return null;
        });
        if (field.control instanceof TextInputControl text) typeCommittedText(text, value);
        else fx(() -> { set(field, value); return null; });
    }

    /** Ждёт нативную активацию сцены, вводит сырой текст и завершает его реальным переводом фокуса. */
    static void typeCommittedText(TextInputControl input, String value) {
        if (Platform.isFxApplicationThread()) throw new IllegalStateException("Focus wait requires worker");
        Node root = fx(() -> {
            if (input.getScene() == null || input.getScene().getWindow() == null
                    || !input.getScene().getWindow().isShowing() || input.isDisabled() || !input.isVisible())
                throw new IllegalStateException("Text input not showing");
            var window = input.getScene().getWindow();
            // Popup не активирует своё окно-владельца: после немодального диалога
            // ввод возможен лишь при настоящем фокусе владельца всплывающей формы.
            if (window instanceof javafx.stage.PopupWindow popup && popup.getOwnerWindow() != null)
                popup.getOwnerWindow().requestFocus();
            window.requestFocus(); input.requestFocus();
            return input.getScene().getRoot();
        });
        awaitPhysicalFocus(input);
        fx(() -> { input.setText(value); return null; });
        boolean traversable = fx(root::isFocusTraversable);
        try {
            fx(() -> { root.setFocusTraversable(true); root.requestFocus(); return null; });
            awaitPhysicalFocus(root);
            fx(() -> { input.requestFocus(); return null; });
            awaitPhysicalFocus(input);
        } finally {
            fx(() -> { root.setFocusTraversable(traversable); return null; });
        }
    }

    /** Проверяет одновременно реальную активность Window, focusOwner сцены и focusedProperty узла. */
    private static void awaitPhysicalFocus(Node target) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!fx(() -> {
            var scene = target.getScene();
            if (scene == null || scene.getWindow() == null || !scene.getWindow().isShowing() || target.isDisabled())
                throw new IllegalStateException("Focus target unavailable");
            return scene.getWindow().isFocused() && scene.getFocusOwner() == target && target.isFocused();
        })) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException(
                    "Physical FX focus transition did not settle: " + fx(() -> {
                        var scene = target.getScene(); var window = scene.getWindow();
                        var owner = window instanceof javafx.stage.PopupWindow popup ? popup.getOwnerWindow() : null;
                        return "window=" + window.getClass().getSimpleName() + ":focused=" + window.isFocused()
                                + ", ownerFocused=" + (owner == null ? "none" : owner.isFocused())
                                + ", focusOwner=" + scene.getFocusOwner() + ", targetFocused=" + target.isFocused();
                    }));
            try { Thread.sleep(10); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
        }
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
            throw new IllegalStateException("Menu hover requires physical pointer delivery");
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
    /** Перемещает настоящий указатель в строку меню и проверяет hover, не добавляя синтетический MOUSE_ENTERED. */
    static void hoverMenuNode(Node node) {
        if (node == null) throw new IllegalStateException("Menu node unavailable");
        var requested = fx(() -> {
            var point = node.localToScreen(node.getBoundsInLocal().getCenterX(), node.getBoundsInLocal().getCenterY());
            if (point == null) throw new IllegalStateException("Menu node not shown");
            new javafx.scene.robot.Robot().mouseMove(point);
            return point;
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!fx(() -> node.isHover() && new javafx.scene.robot.Robot().getMousePosition().distance(requested) <= 2)) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Physical menu hover did not reach " + requested);
            try { Thread.sleep(20); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException("Menu hover interrupted", error); }
        }
    }

    private void leaveCard() {
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
