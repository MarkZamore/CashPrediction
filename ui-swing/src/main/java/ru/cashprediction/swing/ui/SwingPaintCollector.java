package ru.cashprediction.swing.ui;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsConfiguration;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ContainerAdapter;
import java.awt.event.ContainerEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.beans.PropertyChangeListener;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.AbstractButton;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import ru.cashprediction.core.ui.selftest.paint.PaintCaptureRequest;
import ru.cashprediction.core.ui.selftest.paint.PaintObservation;
import ru.cashprediction.core.ui.selftest.paint.PaintObservationCodec;

/**
 * Наблюдает реальные toolbar/summary-компоненты и журнал painter в одном capture-bracket.
 * prepare/finish вызываются на EDT, Robot и ожидание repaint остаются у интегратора вне EDT.
 * Этот класс не вызывает print/paint на BufferedImage, не отменяет popup и не выбирает эталон.
 * Неподключённые painter, repaint-hook, неизвестные ресурсы, clip и нестабильный bracket
 * возвращаются как unsupported; WidgetCapture ядра запрещает принимать такую запись за успех.
 * Интегратор обязан вызывать journal.paintInvalidated при каждом запросе/проходе repaint,
 * включая repaint вне собираемой эпохи: одних property listeners недостаточно для repaint ABA.
 */
public final class SwingPaintCollector implements AutoCloseable {
    private final JComponent root;
    private final SwingPaintJournal journal;
    private final Map<Component, Boolean> watched = new IdentityHashMap<>();
    private final Map<Document, Boolean> documents = new IdentityHashMap<>();
    private final PropertyChangeListener properties = event -> {
        changed("property:" + event.getPropertyName());
        if (event.getSource() instanceof JTextComponent text) watchDocument(text.getDocument());
    };
    private final ComponentAdapter geometry = new ComponentAdapter() {
        /** Учитывает перемещение даже при последующем возврате на старое место. */
        @Override public void componentMoved(ComponentEvent event) { changed("component-moved"); }
        /** Учитывает изменение размера. */
        @Override public void componentResized(ComponentEvent event) { changed("component-resized"); }
        /** Учитывает появление компонента. */
        @Override public void componentShown(ComponentEvent event) { changed("component-shown"); }
        /** Учитывает скрытие компонента. */
        @Override public void componentHidden(ComponentEvent event) { changed("component-hidden"); }
    };
    private final ContainerAdapter children = new ContainerAdapter() {
        /** Подключает наблюдение новых детей до чтения следующего fingerprint. */
        @Override public void componentAdded(ContainerEvent event) { changed("component-added"); watch(event.getChild()); }
        /** Учитывает удаление, сохраняя слушатели до закрытия bracket для ABA. */
        @Override public void componentRemoved(ContainerEvent event) { changed("component-removed"); }
    };
    private final DocumentListener textChanges = new DocumentListener() {
        /** Учитывает ввод текста. */
        @Override public void insertUpdate(DocumentEvent event) { changed("document-insert"); }
        /** Учитывает удаление текста. */
        @Override public void removeUpdate(DocumentEvent event) { changed("document-remove"); }
        /** Учитывает изменение атрибутов текста. */
        @Override public void changedUpdate(DocumentEvent event) { changed("document-change"); }
    };
    private final AWTEventListener input = this::observeAwtEvent;

    /** Учитывает реальные события только после присвоения корня в конструкторе. */
    private void observeAwtEvent(java.awt.AWTEvent event) {
        Object source = event.getSource();
        if (source instanceof Component component && (component == root || SwingUtilities.isDescendingFrom(component, root)
                || component instanceof Window)) changed("awt:" + event.getID());
    }
    private long mutation;
    private final List<String> changes = new ArrayList<>();
    private Bracket pending;
    private boolean closed;

    /** Устанавливает временные слушатели для одного корня selftest; close обязательно снимает их. */
    public SwingPaintCollector(JComponent root, SwingPaintJournal journal) {
        edt(); this.root = Objects.requireNonNull(root); this.journal = Objects.requireNonNull(journal);
        if (root != journal.root()) throw new IllegalArgumentException("different journal root");
        watch(root);
        Window ownerWindow = SwingUtilities.getWindowAncestor(root);
        if (ownerWindow instanceof javax.swing.RootPaneContainer panes) watch(panes.getRootPane());
        Toolkit.getDefaultToolkit().addAWTEventListener(input, java.awt.AWTEvent.FOCUS_EVENT_MASK
                | java.awt.AWTEvent.MOUSE_EVENT_MASK | java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK
                | java.awt.AWTEvent.KEY_EVENT_MASK | java.awt.AWTEvent.WINDOW_EVENT_MASK
                | java.awt.AWTEvent.WINDOW_FOCUS_EVENT_MASK | java.awt.AWTEvent.PAINT_EVENT_MASK);
    }

    /**
     * Читает состояние после завершённой экранной эпохи, непосредственно перед Robot capture.
     * Намерения cardStates не превращаются в наблюдаемые hover/focus или цвета.
     */
    public Bracket prepare(PaintCaptureRequest request, PaintObservation.Interaction acknowledgedInput) {
        live(); if (pending != null) throw new IllegalStateException("capture bracket already open");
        Objects.requireNonNull(request); Objects.requireNonNull(acknowledgedInput);
        changes.clear();
        PaintObservation.Interaction actual = observeInteraction(acknowledgedInput.modality(), acknowledgedInput.gestureSequence(), acknowledgedInput.gestureAcknowledged());
        if (!Objects.equals(acknowledgedInput.pointer(), actual.pointer()) || !Objects.equals(acknowledgedInput.focusOwner(), actual.focusOwner())
                || !acknowledgedInput.activeRoot().equals(actual.activeRoot())) changes.add("input acknowledgement differs from pre-capture state");
        pending = new Bracket(request, journal.snapshot(), mutation, fingerprint(), System.nanoTime(),
                actual, root.getWidth(), root.getHeight());
        return pending;
    }

    /** Читает реальные pointer/focus/activeRoot; номер и modality приходят из подтверждённого native-input hook. */
    public PaintObservation.Interaction observeInteraction(String modality, long gestureSequence, boolean gestureAcknowledged) {
        live(); Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        Window window = SwingUtilities.getWindowAncestor(root);
        return new PaintObservation.Interaction(pointer(), modality, focus == null ? null : journal.identity(focus),
                window != null && window.isActive() ? journal.identity(root) : "inactive-root", gestureSequence, gestureAcknowledged);
    }

    /**
     * Завершает одноразовый bracket после неизменённого Robot PNG; размеры берутся из реального PNG.
     * Environment передаёт интегратор из runtime/artifact, не из ожидаемых профилей.
     * Ошибочное наблюдение пригодно для диагностики, но не для успешного WidgetCapture.
     */
    public PaintObservation finish(Bracket bracket, int pngWidth, int pngHeight, PaintObservation.Environment environment) {
        live(); if (bracket == null || bracket != pending) throw new IllegalArgumentException("foreign or consumed bracket");
        pending = null;
        long end = System.nanoTime(); SwingPaintJournal.Snapshot after = journal.snapshot(); String fingerprintAfter = fingerprint();
        List<String> reasons = new ArrayList<>(changes);
        if (mutation != bracket.mutation) reasons.add("mutation generation changed");
        if (after.revision() != bracket.paint.revision()) reasons.add("paint generation changed");
        if (!fingerprintAfter.equals(bracket.fingerprint)) reasons.add("widget fingerprint changed");
        if (end - bracket.request.deadlineNanos() >= 0) reasons.add("capture deadline exceeded");
        List<PaintObservation.Unsupported> unsupported = new ArrayList<>(); String rootId = journal.identity(root);
        if (!bracket.paint.finished() || !after.finished()) unsupported.add(problem(rootId, "paint", "no completed epoch"));
        if (bracket.paint.owners().stream().noneMatch(p -> p.owner() == root && p.complete()))
            unsupported.add(problem(rootId, "paint.root", "missing full root paint bracket"));
        GraphicsConfiguration config = root.getGraphicsConfiguration();
        AffineTransform device = config == null ? new AffineTransform() : config.getDefaultTransform();
        double scaleX = Math.hypot(device.getScaleX(), device.getShearY()), scaleY = Math.hypot(device.getScaleY(), device.getShearX());
        if (config == null) unsupported.add(problem(rootId, "device", "no screen graphics configuration"));
        if (scaleX != 1 || scaleY != 1 || device.getShearX() != 0 || device.getShearY() != 0)
            unsupported.add(problem(rootId, "scale", "strict profile requires unit screen transform"));
        if (root.getWidth() != 1200 || root.getHeight() != 800 || pngWidth != 1200 || pngHeight != 800)
            unsupported.add(problem(rootId, "viewport", "strict profile requires 1200x800 without resampling"));
        if (bracket.width != root.getWidth() || bracket.height != root.getHeight()) reasons.add("viewport changed");
        java.awt.Point screen = root.isShowing() ? root.getLocationOnScreen() : null;
        if (screen == null) unsupported.add(problem(rootId, "screen", "content is not showing"));
        Window window = SwingUtilities.getWindowAncestor(root);
        if (window == null || !window.isActive() || !window.isFocused()) unsupported.add(problem(rootId, "foreground", "root window is not active and focused"));
        if (window != null && (window.getOpacity() != 1 || window.getShape() != null
                || window.getBackground() != null && window.getBackground().getAlpha() != 255))
            unsupported.add(problem(rootId, "window-composition", "translucent or shaped native window"));
        PaintObservation.Transform toPng = new PaintObservation.Transform(scaleX, 0, 0, scaleY, 0, 0);
        PaintObservation.Viewport viewport = new PaintObservation.Viewport(root.getWidth(), root.getHeight(), pngWidth, pngHeight,
                toPng, screen == null ? null : new PaintObservation.Point(screen.x, screen.y), scaleX, scaleY, null, rootId);
        List<PaintObservation.Surface> surfaces = surfaces(screen, toPng, unsupported);
        List<PaintObservation.Icon> icons = new ArrayList<>(); List<PaintObservation.Card> cards = new ArrayList<>();
        Map<String, PaintObservation.Asset> assets = new LinkedHashMap<>();
        List<JComponent> targets = targets();
        if (targets.isEmpty()) unsupported.add(problem(rootId, "paint.census", "no visible toolbar or summary owners"));
        for (JComponent owner : targets) {
            String instance = journal.identity(owner);
            if (owner.getClientProperty("cp.id") == null) unsupported.add(problem(instance, "owner", "unclassified physical owner"));
            SwingPaintJournal.OwnerSnapshot paint = bracket.paint.owners().stream().filter(p -> p.owner() == owner).findFirst().orElse(null);
            if (paint == null || !paint.complete()) unsupported.add(problem(instance, "paint.census", "missing full completed on-screen owner paint"));
            List<SwingPaintJournal.OwnerSnapshot> ownerPaints = bracket.paint.owners().stream()
                    .filter(p -> p.owner() == owner || SwingUtilities.isDescendingFrom(p.owner(), owner)).toList();
            for (var actual : ownerPaints) {
                for (String reason : actual.unsupported()) unsupported.add(problem(journal.identity(actual.owner()), "paint.source", reason));
                for (SwingPaintJournal.ImageDraw draw : actual.images()) icons.add(icon(owner, draw, actual, toPng, assets, unsupported));
            }
            checkInstalledSources(owner, ownerPaints.stream().flatMap(p -> p.images().stream()).toList(), unsupported);
            if (isCard(owner)) cards.add(card(owner, paint, toPng, unsupported));
        }
        PaintObservation.Interaction actualInput = observeInteraction(bracket.input.modality(), bracket.input.gestureSequence(), bracket.input.gestureAcknowledged());
        if (!actualInput.gestureAcknowledged()) unsupported.add(problem(rootId, "gesture", "native gesture was not acknowledged"));
        if (!Objects.equals(bracket.input.focusOwner(), actualInput.focusOwner())
                || !Objects.equals(bracket.input.pointer(), actualInput.pointer()) || !bracket.input.activeRoot().equals(actualInput.activeRoot()))
            reasons.add("input observation changed");
        PaintObservation.Synchronization sync = new PaintObservation.Synchronization("bracketed-screen",
                bracket.paint.epoch(), after.epoch(), bracket.mutation, mutation, bracket.paint.revision(), after.revision(),
                bracket.mutation, mutation, rootId + ":" + bracket.paint.epoch(), bracket.fingerprint, fingerprintAfter,
                bracket.start, end, bracket.paint.finished() && after.finished(), reasons);
        PaintCaptureRequest r = bracket.request;
        return new PaintObservation(1, PaintObservation.KIND, new PaintObservation.Identity(r.runId(), r.captureId(), r.commandNumber(),
                "swing", r.scenario(), r.step(), r.attempt(), r.planSha256()), viewport, environment, sync, actualInput,
                surfaces, List.copyOf(assets.values()), icons, cards, unsupported);
    }

    /** Отменяет только свой подготовленный bracket; повторное использование handle запрещено. */
    public void abort(Bracket bracket) { live(); if (bracket != pending || bracket == null) throw new IllegalArgumentException("foreign bracket"); pending = null; }

    /** Снимает все слушатели, включая удалённые из дерева компоненты и заменённые документы. */
    @Override public void close() {
        edt(); if (closed) return;
        Toolkit.getDefaultToolkit().removeAWTEventListener(input);
        for (Component component : watched.keySet()) {
            component.removePropertyChangeListener(properties); component.removeComponentListener(geometry);
            if (component instanceof Container container) container.removeContainerListener(children);
        }
        for (Document document : documents.keySet()) document.removeDocumentListener(textChanges);
        watched.clear(); documents.clear(); pending = null; closed = true;
    }

    /** Неподделываемый одноразовый handle, хранящий снимок фактической эпохи до Robot. */
    public static final class Bracket {
        private final PaintCaptureRequest request;
        private final SwingPaintJournal.Snapshot paint;
        private final long mutation, start;
        private final String fingerprint;
        private final PaintObservation.Interaction input;
        private final int width, height;

        private Bracket(PaintCaptureRequest request, SwingPaintJournal.Snapshot paint, long mutation,
                        String fingerprint, long start, PaintObservation.Interaction input, int width, int height) {
            this.request = request; this.paint = paint; this.mutation = mutation; this.fingerprint = fingerprint;
            this.start = start; this.input = input; this.width = width; this.height = height;
        }
    }

    /** Собирает отдельный image draw с настоящими координатами, composite и идентичностью источника. */
    private PaintObservation.Icon icon(JComponent owner, SwingPaintJournal.ImageDraw draw, SwingPaintJournal.OwnerSnapshot paint,
                                       PaintObservation.Transform rootToPng, Map<String, PaintObservation.Asset> assets,
                                       List<PaintObservation.Unsupported> errors) {
        String id = "swing-draw-" + paint.epoch() + "-occurrence-" + draw.order();
        JComponent painter = draw.painter();
        boolean bound = painter instanceof AbstractButton || painter instanceof JLabel || painter instanceof JTextComponent;
        if (!bound)
            errors.add(problem(id, "owner", "draw is not bound to a real toolbar control"));
        if (isCard(owner)) errors.add(problem(id, "paint.source", "image occurrence in card requires separate source classification"));
        PaintObservation.Transform transform = ownerTransform(paint.owner(), draw.state().transform(), rootToPng);
        PaintObservation.Box local = draw.destination(); List<PaintObservation.Point> quad = quad(local, transform);
        PaintObservation.Box bounds = envelope(quad); boolean clipped = draw.state().clip() != null
                && !new java.awt.geom.Area(draw.state().clip()).contains(local.x(), local.y(), local.width(), local.height());
        List<String> clipping = clipping(paint.owner()); if (clipped) clipping = append(clipping, "graphics clip intersects image destination");
        boolean complete = bound && !isCard(owner) && paint.complete() && draw.drawn() && draw.asset() != null && draw.state().sourceOver()
                && draw.state().unsupported().isEmpty() && axisUnit(draw.state().transform()) && clipping.isEmpty();
        if (draw.asset() != null) assets.putIfAbsent(draw.asset().assetId(), draw.asset());
        if (!complete) errors.add(problem(id, "image", "unknown source, partial paint, clip, transform or composite"));
        if (draw.role().equals("unclassified") || draw.paintSource().equals("unclassified")) errors.add(problem(id, "role", "unclassified actual draw"));
        for (String reason : draw.state().unsupported()) errors.add(problem(id, "graphics", reason));
        return new PaintObservation.Icon(id, journal.identity(painter), boundSemantic(painter), draw.role(), path(painter), draw.paintSource(),
                draw.asset() == null ? null : draw.asset().assetId(), draw.sourceIdentity(), null, null, draw.state().argb(),
                local, transform, bounds, quad, null, clipping, !clipping.isEmpty(), owner.isShowing(), draw.order(),
                List.of(new PaintObservation.OpacityFactor(journal.identity(painter), draw.state().alpha(), "Graphics2D AlphaComposite")),
                draw.state().alpha(), draw.state().composite(), List.of(), List.of(), draw.state().sourceOver() ? "SRC_OVER" : draw.state().composite(), paint.epoch(), draw.occurrence(), complete);
    }

    /** Читает карточку независимо от желаемого состояния плана и журналирует раздельные fill/stroke. */
    private PaintObservation.Card card(JComponent owner, SwingPaintJournal.OwnerSnapshot paint,
                                       PaintObservation.Transform rootToPng, List<PaintObservation.Unsupported> errors) {
        String id = journal.identity(owner); List<PaintObservation.BackgroundLayer> backgrounds = new ArrayList<>();
        List<PaintObservation.BorderLayer> borders = new ArrayList<>();
        if (paint != null) for (SwingPaintJournal.ShapeDraw shape : paint.shapes()) {
            if (shape.fill()) {
                PaintObservation.BackgroundLayer layer = background(owner, shape, errors, id); if (layer != null) backgrounds.add(layer);
            } else {
                PaintObservation.BorderLayer layer = border(owner, shape, errors, id); if (layer != null) borders.add(layer);
            }
        }
        if (backgrounds.isEmpty()) errors.add(problem(id, "background", "no actual solid background fill recorded"));
        // Свойство Border отдельно от paintBorder: EmptyBorder не доказывает отсутствие рисуемой рамки.
        List<PaintObservation.BorderLayer> configured = configuredBorders(owner, errors, id);
        if (borders.isEmpty()) {
            borders.addAll(configured);
            errors.add(problem(id, "border", "no actual border draw recorded; configured layers diagnostic only"));
        }
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean within = focus != null && (focus == owner || SwingUtilities.isDescendingFrom(focus, owner));
        PaintObservation.Point pointer = pointer();
        Boolean hit = null;
        if (pointer != null && root.isShowing()) {
            Component physical = SwingUtilities.getDeepestComponentAt(root, (int) Math.floor(pointer.x()), (int) Math.floor(pointer.y()));
            hit = physical != null && (physical == owner || SwingUtilities.isDescendingFrom(physical, owner));
        } else errors.add(problem(id, "physicalHit", "physical pointer unavailable"));
        boolean hover = Boolean.TRUE.equals(hit) && owner.getMousePosition(true) != null;
        PaintObservation.Box local = new PaintObservation.Box(0, 0, owner.getWidth(), owner.getHeight());
        PaintObservation.Transform transform = ownerTransform(owner, identityTransform(), rootToPng);
        List<String> clipping = clipping(owner);
        if (!clipping.isEmpty()) errors.add(problem(id, "clip", "card clipped by component ancestry"));
        return new PaintObservation.Card(id, semantic(owner), local, transform, envelope(quad(local, transform)), owner.isShowing(),
                !clipping.isEmpty(), false, clipping, List.of(), backgrounds, borders, hover, owner.isFocusOwner(), null, within,
                focus == null ? null : journal.identity(focus), hit, owner.getClass().getName() + ":paintComponent",
                owner.getClass().getName() + ":paintBorder", paint == null ? 0 : paint.epoch());
    }

    /** Преобразует фактический roundRect fill, включая независимый от штриха радиус заливки. */
    static PaintObservation.BackgroundLayer background(JComponent owner, SwingPaintJournal.ShapeDraw draw,
                                                       List<PaintObservation.Unsupported> errors, String id) {
        if (!supportedShape(draw, errors, id)) return null;
        Rectangle2D box = draw.shape().getBounds2D(); PaintObservation.Transform t = draw.state().transform();
        return new PaintObservation.BackgroundLayer(draw.state().argb(), insets(owner, box, t, 0), radii(draw.shape(), 0), draw.implementation());
    }

    /** Выводит внешний контур centered stroke из диаметра roundRect и фактической толщины. */
    static PaintObservation.BorderLayer border(JComponent owner, SwingPaintJournal.ShapeDraw draw,
                                               List<PaintObservation.Unsupported> errors, String id) {
        if (!supportedShape(draw, errors, id) || draw.state().strokeWidth() == null || !draw.state().solidStroke()) return null;
        double width = draw.state().strokeWidth(); Rectangle2D box = draw.shape().getBounds2D();
        if (draw.shape() instanceof RoundRectangle2D r && Math.min(Math.abs(r.getArcWidth()), r.getWidth())
                != Math.min(Math.abs(r.getArcHeight()), r.getHeight()))
            errors.add(problem(id, "border.radius", "elliptical centered stroke has no circular outer contour"));
        return new PaintObservation.BorderLayer(repeat(draw.state().argb()), repeat(width), repeat("solid"),
                insets(owner, box, draw.state().transform(), width / 2), radii(draw.shape(), width / 2), radii(draw.shape(), 0),
                "centered", draw.implementation());
    }

    /** Не выдумывает геометрию неизвестного shape, Paint, composite или штриха. */
    private static boolean supportedShape(SwingPaintJournal.ShapeDraw draw, List<PaintObservation.Unsupported> errors, String id) {
        boolean supported = (draw.shape() instanceof Rectangle2D || draw.shape() instanceof RoundRectangle2D)
                && draw.state().argb() != null && draw.state().sourceOver() && draw.state().alpha() == 1
                && axisUnit(draw.state().transform()) && draw.state().unsupported().isEmpty();
        Rectangle2D bounds = draw.shape().getBounds2D();
        if (bounds.isEmpty()) supported = false;
        if (!draw.fill() && draw.state().strokeWidth() != null) {
            double half = draw.state().strokeWidth() / 2;
            bounds = new Rectangle2D.Double(bounds.getX() - half, bounds.getY() - half, bounds.getWidth() + 2 * half, bounds.getHeight() + 2 * half);
        }
        if (draw.state().clip() != null && !new java.awt.geom.Area(draw.state().clip()).contains(bounds)) supported = false;
        if (!supported) errors.add(problem(id, "shape", "unsupported primitive, paint, transform, clip or composite"));
        return supported;
    }

    /** Сохраняет raw диаметр и разрешённый геометрический радиус в порядке TL, TR, BL, BR. */
    private static List<PaintObservation.Radius> radii(Shape shape, double halfStroke) {
        double ax = 0, ay = 0, rx = 0, ry = 0;
        if (shape instanceof RoundRectangle2D round) {
            ax = Math.abs(round.getArcWidth()); ay = Math.abs(round.getArcHeight());
            rx = Math.min(ax, round.getWidth()) / 2; ry = Math.min(ay, round.getHeight()) / 2;
            if (rx > 0 && ry > 0) { rx += halfStroke; ry += halfStroke; }
        }
        return repeat(new PaintObservation.Radius(ax, ay, false, false, "roundRect-diameter-logical", rx, ry));
    }

    /** Вычисляет отступы геометрического контура в локальных координатах владельца. */
    private static PaintObservation.Insets insets(JComponent owner, Rectangle2D box, PaintObservation.Transform t, double halfStroke) {
        return new PaintObservation.Insets(box.getY() + t.ty() - halfStroke,
                owner.getWidth() - box.getMaxX() - t.tx() - halfStroke,
                owner.getHeight() - box.getMaxY() - t.ty() - halfStroke, box.getX() + t.tx() - halfStroke);
    }

    /** Рекурсивно читает реальные свойства Border, не принимая их за выполненную рамку painter. */
    static List<PaintObservation.BorderLayer> configuredBorders(JComponent owner, List<PaintObservation.Unsupported> errors, String id) {
        List<PaintObservation.BorderLayer> result = new ArrayList<>(); readBorder(owner, owner.getBorder(), new java.awt.Insets(0, 0, 0, 0), result, errors, id, 0); return result;
    }

    /** EmptyBorder даёт только отступы; неизвестные/скруглённые реализации сохраняют unsupported. */
    private static void readBorder(JComponent owner, Border border, java.awt.Insets inset, List<PaintObservation.BorderLayer> result,
                                   List<PaintObservation.Unsupported> errors, String id, int depth) {
        if (border == null) return;
        if (depth > 32) { errors.add(problem(id, "border", "border nesting limit")); return; }
        if (border.getClass() == CompoundBorder.class) {
            CompoundBorder compound = (CompoundBorder) border;
            readBorder(owner, compound.getOutsideBorder(), inset, result, errors, id, depth + 1);
            java.awt.Insets out = compound.getOutsideBorder() == null ? new java.awt.Insets(0, 0, 0, 0) : compound.getOutsideBorder().getBorderInsets(owner);
            readBorder(owner, compound.getInsideBorder(), new java.awt.Insets(inset.top + out.top, inset.left + out.left,
                    inset.bottom + out.bottom, inset.right + out.right), result, errors, id, depth + 1);
        } else if (border.getClass() == LineBorder.class && !((LineBorder) border).getRoundedCorners()) {
            LineBorder line = (LineBorder) border;
            result.add(new PaintObservation.BorderLayer(repeat(line.getLineColor().getRGB()), repeat((double) line.getThickness()),
                    repeat("solid"), new PaintObservation.Insets(inset.top, inset.right, inset.bottom, inset.left),
                    repeat(new PaintObservation.Radius(0, 0, false, false, "logical", 0, 0)), null, "inside", border.getClass().getName()));
        } else if (border.getClass() != EmptyBorder.class) errors.add(problem(id, "border", "unknown or rounded border implementation:" + border.getClass().getName()));
    }

    /** Проверяет замену установленной иконки и наличие её реального draw, включая disabledIcon. */
    private void checkInstalledSources(JComponent owner, List<SwingPaintJournal.ImageDraw> draws, List<PaintObservation.Unsupported> errors) {
        for (Component component : descendants(owner)) {
            javax.swing.Icon icon = component instanceof AbstractButton button ? installedIcon(button)
                    : component instanceof JLabel label ? (label.isEnabled() ? label.getIcon() : label.getDisabledIcon()) : null;
            if (icon == null) continue;
            String id = journal.identity(component);
            if (!(icon instanceof ImageIcon image)) errors.add(problem(id, "icon", "unknown installed Icon implementation:" + icon.getClass().getName()));
            else if (draws.stream().noneMatch(draw -> draw.painter() == component && draw.image() == image.getImage()))
                errors.add(problem(id, "icon", "installed icon absent from actual paint census"));
        }
    }

    /** Читает реально выбранный Icon стандартного ButtonUI, включая selected/pressed/rollover/disabled. */
    private static javax.swing.Icon installedIcon(AbstractButton button) {
        var model = button.getModel(); javax.swing.Icon icon = button.getIcon(), state = null;
        if (!model.isEnabled()) {
            if (model.isSelected()) state = button.getDisabledSelectedIcon();
            if (state == null) state = button.getDisabledIcon();
        } else {
            if (model.isSelected() && button.getSelectedIcon() != null) icon = button.getSelectedIcon();
            if (model.isPressed() && model.isArmed()) state = button.getPressedIcon();
            else if (button.isRolloverEnabled() && model.isRollover()) {
                if (model.isSelected()) state = button.getRolloverSelectedIcon();
                if (state == null) state = button.getRolloverIcon();
            }
        }
        return state == null ? icon : state;
    }

    /** Выбирает физические верхние владельцы toolbar и прямые карточки summary без списков модели. */
    private List<JComponent> targets() {
        List<JComponent> result = new ArrayList<>();
        for (Component component : descendants(root)) if (component instanceof JComponent parent
                && visibleInRoot(parent) && (semantic(parent).equals("toolbar") || semantic(parent).equals("summary"))) {
            for (Component child : parent.getComponents()) if (child instanceof JComponent target && child.isVisible()
                    && !(child instanceof javax.swing.Box.Filler) && !(child instanceof javax.swing.JSeparator)
                    && !"Spacer".equals(target.getClientProperty("cp.kind"))
                    && target.getWidth() > 0 && target.getHeight() > 0) result.add(target);
        }
        return result;
    }

    /** Распознаёт карточку по фактическому месту в дереве, не по желаемому token/state. */
    private static boolean isCard(JComponent owner) { return owner.getParent() instanceof JComponent p && semantic(p).equals("summary") && !(owner instanceof JLabel); }

    /** Проверяет видимость всей цепочки до корня, не требуя экрана в диагностическом режиме. */
    private boolean visibleInRoot(Component component) {
        for (Component c = component; c != null; c = c.getParent()) { if (!c.isVisible()) return false; if (c == root) return true; }
        return false;
    }

    /** Читает clip предков в реальном дереве; частично видимый владелец не становится полным. */
    private List<String> clipping(JComponent owner) {
        List<String> result = new ArrayList<>(); Rectangle full = new Rectangle(0, 0, owner.getWidth(), owner.getHeight());
        if (!owner.getVisibleRect().contains(full)) result.add("component visible rect"); return result;
    }

    /** Перечисляет поверхности текущего снимка; пересекающее окно делает capture неподдержанным. */
    private List<PaintObservation.Surface> surfaces(java.awt.Point origin, PaintObservation.Transform t, List<PaintObservation.Unsupported> errors) {
        List<PaintObservation.Surface> result = new ArrayList<>();
        result.add(new PaintObservation.Surface(journal.identity(root), "content", origin == null ? null : new PaintObservation.Point(origin.x, origin.y),
                new PaintObservation.Point(0, 0), new PaintObservation.Box(0, 0, root.getWidth() * t.a(), root.getHeight() * t.d()), 0, root.isShowing(), false));
        Window main = SwingUtilities.getWindowAncestor(root); if (origin == null) return result;
        Rectangle viewport = new Rectangle(origin.x, origin.y, root.getWidth(), root.getHeight());
        // JavaFX: PopupWindow/Dialog → Swing: Window/JWindow/JDialog → Web: popup/dialog surfaces.
        for (Window window : Window.getWindows()) if (window != main && window.isShowing() && window.getBounds().intersects(viewport)) {
            java.awt.Point point = window.getLocationOnScreen(); String id = journal.identity(window);
            result.add(new PaintObservation.Surface(id, window.getClass().getName(), new PaintObservation.Point(point.x, point.y),
                    new PaintObservation.Point((point.x - origin.x) * t.a(), (point.y - origin.y) * t.d()),
                    new PaintObservation.Box((point.x - origin.x) * t.a(), (point.y - origin.y) * t.d(), window.getWidth() * t.a(), window.getHeight() * t.d()),
                    result.size(), true, true));
            errors.add(problem(id, "occlusion", "overlapping application surface; z-order not proven"));
        }
        if (main instanceof javax.swing.RootPaneContainer panes) {
            // JavaFX: Popup/DialogPane → Swing: JLayeredPane/GlassPane → Web: overlay/popup.
            List<Component> overlays = new ArrayList<>(); overlays.add(panes.getGlassPane());
            overlays.addAll(List.of(panes.getLayeredPane().getComponents()));
            for (Component overlay : overlays) if (overlay.isShowing() && overlay != root && !SwingUtilities.isDescendingFrom(root, overlay)) {
                java.awt.Point point = overlay.getLocationOnScreen();
                if (!new Rectangle(point.x, point.y, overlay.getWidth(), overlay.getHeight()).intersects(viewport)) continue;
                String id = journal.identity(overlay);
                result.add(new PaintObservation.Surface(id, overlay.getClass().getName(), new PaintObservation.Point(point.x, point.y),
                        new PaintObservation.Point((point.x - origin.x) * t.a(), (point.y - origin.y) * t.d()),
                        new PaintObservation.Box((point.x - origin.x) * t.a(), (point.y - origin.y) * t.d(), overlay.getWidth() * t.a(), overlay.getHeight() * t.d()),
                        result.size(), true, true));
                errors.add(problem(id, "occlusion", "lightweight popup or glass overlay intersects content"));
            }
        }
        return result;
    }

    /** Измеряет физический указатель относительно содержимого; headless не заменяется тестовым hover. */
    private PaintObservation.Point pointer() {
        if (!root.isShowing() || java.awt.GraphicsEnvironment.isHeadless()) return null;
        PointerInfo pointer = MouseInfo.getPointerInfo(); if (pointer == null) return null;
        java.awt.Point p = pointer.getLocation(), origin = root.getLocationOnScreen();
        return new PaintObservation.Point(p.x - origin.x, p.y - origin.y);
    }

    /** Применяет перевод owner-local в корень и PNG ровно один раз, сохраняя дробные координаты. */
    private PaintObservation.Transform ownerTransform(JComponent owner, PaintObservation.Transform local, PaintObservation.Transform png) {
        java.awt.Point p = SwingUtilities.convertPoint(owner, 0, 0, root);
        return new PaintObservation.Transform(local.a() * png.a(), local.b() * png.d(), local.c() * png.a(), local.d() * png.d(),
                (p.x + local.tx()) * png.a(), (p.y + local.ty()) * png.d());
    }

    /** Сохраняет четыре фактических угла назначения изображения. */
    private static List<PaintObservation.Point> quad(PaintObservation.Box b, PaintObservation.Transform t) {
        return List.of(point(b.x(), b.y(), t), point(b.x() + b.width(), b.y(), t),
                point(b.x(), b.y() + b.height(), t), point(b.x() + b.width(), b.y() + b.height(), t));
    }

    /** Преобразует точку без округления. */
    private static PaintObservation.Point point(double x, double y, PaintObservation.Transform t) { return new PaintObservation.Point(t.a() * x + t.c() * y + t.tx(), t.b() * x + t.d() * y + t.ty()); }

    /** Вычисляет bounds, сохраняя quad для отдельного отказа при rotation/shear. */
    private static PaintObservation.Box envelope(List<PaintObservation.Point> points) {
        double x = points.stream().mapToDouble(PaintObservation.Point::x).min().orElseThrow(), y = points.stream().mapToDouble(PaintObservation.Point::y).min().orElseThrow();
        return new PaintObservation.Box(x, y, points.stream().mapToDouble(PaintObservation.Point::x).max().orElseThrow() - x,
                points.stream().mapToDouble(PaintObservation.Point::y).max().orElseThrow() - y);
    }

    /** Вычисляет fingerprint реальных свойств, включая installed icon и focus; ABA учитывают поколения. */
    private String fingerprint() {
        StringBuilder text = new StringBuilder();
        for (Component component : descendants(root)) {
            field(text, journal.identity(component)); field(text, component.getBounds()); field(text, component.isVisible());
            field(text, component.isEnabled()); field(text, component.getBackground()); field(text, component.getForeground());
            field(text, component.getFont()); field(text, component.isFocusOwner());
            if (component instanceof JComponent c) { field(text, c.getVisibleRect()); field(text, c.isOpaque()); field(text, c.getClientProperty("cp.id")); fingerprintBorder(c, c.getBorder(), text, 0); }
            if (component instanceof AbstractButton b) {
                field(text, b.getText()); field(text, b.isSelected()); field(text, b.getModel().isPressed()); field(text, b.getModel().isRollover());
                field(text, b.getIcon() == null ? "none" : journal.identity(b.getIcon()));
                field(text, b.getDisabledIcon() == null ? "none" : journal.identity(b.getDisabledIcon()));
                if (b.getIcon() instanceof ImageIcon i) field(text, journal.identity(i.getImage()));
                if (b.getDisabledIcon() instanceof ImageIcon i) field(text, journal.identity(i.getImage()));
            }
            if (component instanceof JLabel label) field(text, label.getText());
            if (component instanceof JTextComponent input) field(text, input.getText());
        }
        field(text, pointer()); Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        field(text, focus == null ? "none" : journal.identity(focus));
        // JavaFX: Window → Swing: Window → Web: window/popup visibility.
        for (Window window : Window.getWindows()) { field(text, journal.identity(window)); field(text, window.isShowing()); field(text, window.getBounds()); field(text, window.isActive()); field(text, window.getOpacity()); field(text, window.getShape()); }
        Window owner = SwingUtilities.getWindowAncestor(root);
        if (owner instanceof javax.swing.RootPaneContainer panes) for (Component component : descendants(panes.getRootPane())) {
            field(text, journal.identity(component)); field(text, component.isShowing()); field(text, component.getBounds());
        }
        return PaintObservationCodec.sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Читает изменяемые параметры Border, а не только идентичность экземпляра. */
    private void fingerprintBorder(JComponent owner, Border border, StringBuilder text, int depth) {
        if (border == null || depth > 32) { field(text, "none-or-depth-limit"); return; }
        field(text, journal.identity(border)); field(text, border.getBorderInsets(owner));
        if (border instanceof LineBorder line) { field(text, line.getLineColor()); field(text, line.getThickness()); field(text, line.getRoundedCorners()); }
        if (border instanceof CompoundBorder compound) { fingerprintBorder(owner, compound.getOutsideBorder(), text, depth + 1); fingerprintBorder(owner, compound.getInsideBorder(), text, depth + 1); }
    }

    /** Добавляет поле с длиной, чтобы разделители внутри текста не создали одинаковый fingerprint. */
    private static void field(StringBuilder out, Object value) { String text = String.valueOf(value); out.append(text.length()).append(':').append(text); }

    /** Подключает дерево, включая документы реально установленных текстовых компонентов. */
    private void watch(Component component) {
        if (watched.put(component, Boolean.TRUE) != null) return;
        component.addPropertyChangeListener(properties); component.addComponentListener(geometry);
        if (component instanceof JTextComponent text) watchDocument(text.getDocument());
        if (component instanceof Container container) { container.addContainerListener(children); for (Component child : container.getComponents()) watch(child); }
    }

    /** Следит и за заменённым документом, чтобы изменение с возвратом не потерялось. */
    private void watchDocument(Document document) { if (documents.put(document, Boolean.TRUE) == null) document.addDocumentListener(textChanges); }

    /** Учитывает события без бесконечного роста списка причин. */
    private void changed(String reason) { mutation++; if (pending != null && changes.size() < 128) changes.add(reason); }

    /** Обходит реальные компоненты в порядке дерева. */
    private static List<Component> descendants(Component root) {
        List<Component> result = new ArrayList<>(); result.add(root);
        if (root instanceof Container container) for (Component child : container.getComponents()) result.addAll(descendants(child)); return result;
    }

    /** Возвращает фактическую семантическую привязку, не ресурс и не ожидаемый paint. */
    private static String semantic(JComponent c) { Object id = c.getClientProperty("cp.id"); return id == null ? "unclassified-owner" : id.toString(); }

    /** Привязывает безымянную физическую кнопку split к ближайшему семантическому владельцу в дереве. */
    private static String boundSemantic(JComponent component) {
        for (Component c = component; c != null; c = c.getParent()) if (c instanceof JComponent parent && parent.getClientProperty("cp.id") != null) return semantic(parent);
        return "unclassified-owner";
    }

    /** Строит диагностический путь по физической цепочке предков. */
    private String path(Component component) { List<String> parts = new ArrayList<>(); for (Component c = component; c != null; c = c.getParent()) { parts.add(journal.identity(c)); if (c == root) break; } return String.join("/", parts.reversed()); }

    /** Возвращает ровно четыре стороны или угла без геометрической подмены. */
    private static <T> List<T> repeat(T value) { return List.of(value, value, value, value); }
    /** Добавляет диагностический clip к независимому списку. */
    private static List<String> append(List<String> values, String value) { List<String> result = new ArrayList<>(values); result.add(value); return List.copyOf(result); }
    /** Создаёт unsupported, обязательный для неизвестных источников DTO. */
    private static PaintObservation.Unsupported problem(String id, String property, String reason) { return new PaintObservation.Unsupported(id, property, reason); }
    /** Проверяет поддерживаемую геометрию, не округляя scale/shear. */
    private static boolean axisUnit(PaintObservation.Transform t) { return t.a() == 1 && t.d() == 1 && t.b() == 0 && t.c() == 0; }
    /** Возвращает локальное тождественное преобразование, до измеренного owner-offset. */
    private static PaintObservation.Transform identityTransform() { return new PaintObservation.Transform(1, 0, 0, 1, 0, 0); }
    /** Проверяет жизненный цикл временного наблюдателя. */
    private void live() { edt(); if (closed) throw new IllegalStateException("paint collector closed"); }
    /** Запрещает наблюдение Swing-свойств вне EDT. */
    private static void edt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("paint collector requires EDT"); }
}
