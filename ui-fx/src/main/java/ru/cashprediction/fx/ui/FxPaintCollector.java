package ru.cashprediction.fx.ui;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.effect.BlendMode;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import javafx.stage.Window;
import ru.cashprediction.core.json.JsonParser;
import ru.cashprediction.core.json.JsonWriter;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.json.UiJson;
import ru.cashprediction.core.ui.selftest.paint.PaintCaptureRequest;
import ru.cashprediction.core.ui.selftest.paint.PaintObservation;
import ru.cashprediction.core.ui.selftest.paint.PaintObservation.*;
import ru.cashprediction.core.ui.selftest.paint.PaintObservationCodec;
import ru.cashprediction.core.ui.selftest.paint.WidgetCapture;

/**
 * Синхронно читает настоящую сцену FX вокруг исходного raw/PNG-захвата.
 * Вызывается на FX-потоке после внешнего post-layout барьера, без ожиданий и ввода.
 * Не загружает каталог по cp.icon и не использует намерения карточек как наблюдения.
 * Зависимости транзакции должны читать реальный журнал поколений и сохранять механизм
 * композиции FxUiDriver.screenshot; отсутствие доказательств возвращается как Unsupported.
 */
public final class FxPaintCollector {
    private final IdentityHashMap<Object, String> identities = new IdentityHashMap<>();
    private long nextIdentity;

    /** Создаёт локальный сборщик; глобального реестра изображений или записи файлов нет. */
    public FxPaintCollector() { }

    /**
     * Зависимости одной фактической транзакции. Все методы вызываются на FX-потоке.
     * raw и png не должны ожидать другой FX-задачи или подменять реальный снимок обрезкой.
     */
    public interface Transaction {
        /** Возвращает ненормализованный дамп живых виджетов с идентичностью запроса. */
        UiDump raw(PaintCaptureRequest request);
        /** Снимает исходный PNG всех поверхностей тем же композитором, что обычный screenshot. */
        byte[] png();
        /** Читает реальные размеры, преобразование и порядок композиции поверхностей. */
        Frame frame();
        /** Читает монотонные поколения журнала, включая изменения с возвратом состояния. */
        Stamp stamp();
        /** Читает физический указатель и подтверждение ввода; null означает отсутствие hook. */
        Input input();
        /** Ищет исходные байты только по идентичности установленного объекта Image. */
        Source source(Image installed);
        /** Читает эпоху последней реальной краски узла; null означает отсутствие журнала его рисования. */
        Long lastPaintEpoch(Node node);
        /** Возвращает измеренное происхождение сборки, шрифтов и среды данного запуска. */
        Environment environment();
    }

    /** Реальная поверхность композитора с точным применённым преобразованием и порядком. */
    public record SurfaceReading(Scene scene, Transform sceneToPng, int zOrder) {
        /** Проверяет обязательные ссылки, не вычисляя смещение из желаемой геометрии. */
        public SurfaceReading { Objects.requireNonNull(scene); Objects.requireNonNull(sceneToPng); }
    }

    /** Реальная геометрия композитора; первым элементом идёт основная сцена. */
    public record Frame(Transform logicalToPng, int pngWidth, int pngHeight, List<SurfaceReading> surfaces) {
        /** Копирует список поверхностей и проверяет ограничение числа сцен. */
        public Frame {
            Objects.requireNonNull(logicalToPng); surfaces = List.copyOf(surfaces);
            if (pngWidth <= 0 || pngHeight <= 0 || surfaces.isEmpty() || surfaces.size() > 64)
                throw new IllegalArgumentException("capture frame");
        }
    }

    /** Поколения мутаций/callbacks; чтение snapshot, полнота и native paint не выводятся из счётчика. */
    public record Stamp(long epoch, long layoutRevision, long paintRevision, long renderGeneration,
                        boolean postLayoutPulse, boolean mutationJournalComplete) {
        /** Запрещает отрицательные поколения. */
        public Stamp {
            if (epoch < 0 || layoutRevision < 0 || paintRevision < 0 || renderGeneration < 0)
                throw new IllegalArgumentException("capture counters");
        }
    }

    /**
     * Физический ввод: screenPointer прочитан у ОС, picked получен настоящим hit-test.
     * pickedKnown различает отсутствие цели и отсутствие проверенного hit-test.
     * Синтетические fireEvent и намерения плана не являются источниками этой записи.
     */
    public record Input(Point screenPointer, Node picked, boolean pickedKnown, String modality,
                        long gestureSequence, boolean gestureAcknowledged) {
        /** Проверяет обязательный вид ввода; остальные поля проверяет DTO взаимодействия. */
        public Input { Objects.requireNonNull(modality); }
    }

    /** Байты, удержанные hook декодирования именно этого Image; замена объекта не наследует запись. */
    public record Source(Image installed, byte[] png, String source) {
        /** Копирует исходные байты, ограничивая общий допустимый объём одного источника. */
        public Source {
            Objects.requireNonNull(installed); Objects.requireNonNull(source); png = png.clone();
            if (png.length == 0 || png.length > PaintObservation.MAX_ASSET_BYTES)
                throw new IllegalArgumentException("source PNG limit");
        }
        /** Возвращает копию исходных байтов без повторной загрузки ресурса. */
        @Override public byte[] png() { return png.clone(); }
    }

    /** Диагностическая попытка: неподдержанное наблюдение сохраняется, но не становится успешной парой. */
    public record Result(UiDump raw, byte[] png, PaintObservation observation) {
        /** Удерживает исходный PNG независимо от памяти композитора. */
        public Result {
            Objects.requireNonNull(raw); Objects.requireNonNull(observation); png = png.clone();
            if (png.length == 0 || png.length > PaintObservationCodec.MAX_PNG_BYTES)
                throw new IllegalArgumentException("capture PNG limit");
        }
        /** Возвращает копию оригинального PNG, без перекодирования. */
        @Override public byte[] png() { return png.clone(); }
        /** Выполняет строгую проверку ядра; unsupported/unstable диагностическая попытка отклоняется. */
        public WidgetCapture requireSupported(PaintCaptureRequest request) {
            WidgetCapture capture = new WidgetCapture(raw, png, observation);
            capture.requireRequest(request); return capture;
        }
    }

    /**
     * Читает raw, свойства и PNG в одном вызове FX-потока и повторяет чтение после снимка.
     * Возвращает также неуспешные наблюдения для диагностики; вызывающий код решает сохранение и повтор.
     * Дедлайн проверяется без блокировки, состояние интерфейса сборщик не меняет.
     */
    public Result collect(PaintCaptureRequest request, Scene scene, Parent toolbar, Parent summary,
                          Transaction transaction) {
        if (!Platform.isFxApplicationThread()) throw new IllegalStateException("FX capture thread required");
        Objects.requireNonNull(request); Objects.requireNonNull(transaction); Objects.requireNonNull(scene);
        if (!descendant(toolbar, scene.getRoot()) || !descendant(summary, scene.getRoot()))
            throw new IllegalArgumentException("capture roots do not belong to scene");
        identities.clear(); nextIdentity = 0;
        long start = System.nanoTime();
        Stamp beforeStamp = Objects.requireNonNull(transaction.stamp());
        UiDump raw = Objects.requireNonNull(transaction.raw(request));
        Environment environment = Objects.requireNonNull(transaction.environment());
        Identity identity = new Identity(request.runId(), request.captureId(), request.commandNumber(), "fx",
                request.scenario(), request.step(), request.attempt(), request.planSha256());
        Sample before = sample(scene, toolbar, summary, transaction, beforeStamp);
        String beforeHash = fingerprint(before.observation(identity, environment,
                synchronization(beforeStamp, beforeStamp, scene, "0".repeat(64), "0".repeat(64), start, start, List.of())), raw);
        byte[] originalPng = Objects.requireNonNull(transaction.png());
        if (originalPng.length > PaintObservationCodec.MAX_PNG_BYTES) throw new IllegalArgumentException("capture PNG limit");
        byte[] png = originalPng.clone();
        UiDump afterRaw = Objects.requireNonNull(transaction.raw(request));
        Sample after = sample(scene, toolbar, summary, transaction, Objects.requireNonNull(transaction.stamp()));
        Stamp afterStamp = Objects.requireNonNull(transaction.stamp());
        long end = System.nanoTime();
        String afterHash = fingerprint(after.observation(identity, environment,
                synchronization(afterStamp, afterStamp, scene, "0".repeat(64), "0".repeat(64), end, end, List.of())), afterRaw);
        List<String> changes = new ArrayList<>();
        if (!beforeHash.equals(afterHash)) changes.add("widget/frame fingerprint changed");
        changes.addAll(generationChanges(beforeStamp, afterStamp));
        if (start - request.deadlineNanos() >= 0 || end - request.deadlineNanos() >= 0) changes.add("capture deadline exceeded");
        LinkedHashSet<Unsupported> failures = new LinkedHashSet<>(before.unsupported);
        failures.addAll(after.unsupported);
        if (raw.schema() != UiDump.SCHEMA || !"fx".equals(raw.client()) || !request.scenario().equals(raw.scenario())
                || !request.step().equals(raw.step())) failures.add(new Unsupported(id(scene.getRoot()), "raw", "raw request identity mismatch"));
        if (!pngSizeMatches(png, after.viewport.pngWidth(), after.viewport.pngHeight()))
            failures.add(new Unsupported(id(scene.getRoot()), "png", "actual PNG dimensions differ from compositor geometry"));
        after.unsupported.clear(); after.unsupported.addAll(failures);
        return new Result(raw, png, after.observation(identity, environment,
                synchronization(beforeStamp, afterStamp, scene, beforeHash, afterHash, start, end, changes)));
    }

    /** Сравнивает реальные штампы без компенсации собственных чтений или подавления изменений ABA. */
    static List<String> generationChanges(Stamp before, Stamp after) {
        List<String> changes = new ArrayList<>();
        if (before.epoch() != after.epoch() || before.layoutRevision() != after.layoutRevision()
                || before.paintRevision() != after.paintRevision()) changes.add("mutation journal changed");
        if (before.renderGeneration() != after.renderGeneration()) changes.add("render/pulse generation changed");
        return List.copyOf(changes);
    }

    /** Собирает один моментальный срез настоящих узлов, без raw и ожиданий pulse. */
    private Sample sample(Scene scene, Parent toolbar, Parent summary, Transaction transaction, Stamp stamp) {
        Sample result = new Sample(); Frame frame = Objects.requireNonNull(transaction.frame());
        result.rootZ = frame.surfaces().getFirst().zOrder();
        Node root = scene.getRoot(); String rootId = id(root); Window window = scene.getWindow();
        double sx = window == null ? 1 : window.getOutputScaleX(), sy = window == null ? 1 : window.getOutputScaleY();
        Point origin = screenOrigin(scene);
        result.viewport = new Viewport(dimension(scene.getWidth()), dimension(scene.getHeight()), frame.pngWidth(), frame.pngHeight(),
                frame.logicalToPng(), origin, sx, sy, null, rootId);
        if (scene.getWidth() != 1200 || scene.getHeight() != 800 || frame.pngWidth() != 1200 || frame.pngHeight() != 800
                || sx != 1 || sy != 1 || !unit(frame.logicalToPng()) || window == null
                || window.getRenderScaleX() != 1 || window.getRenderScaleY() != 1)
            fail(result.unsupported, rootId, "viewport", "strict S5 requires an actual 1200x800 unit-scale scene and PNG");
        if (window == null || !window.isShowing() || !window.isFocused())
            fail(result.unsupported, rootId, "window", "main window is not showing and physically active");
        if (!stamp.mutationJournalComplete()) fail(result.unsupported, rootId, "journal", "complete mutation/paint journal hook absent");
        fail(result.unsupported, rootId, "nativePaint", "native render completion and presentation hook absent; post-layout is not paint");
        if (!stamp.postLayoutPulse()) fail(result.unsupported, rootId, "pulse", "post-layout pulse barrier not acknowledged");
        if (origin == null) fail(result.unsupported, rootId, "screenOrigin", "actual content screen origin unavailable");
        if (scene.getCamera() != null && !(scene.getCamera() instanceof javafx.scene.ParallelCamera))
            fail(result.unsupported, rootId, "camera", "perspective camera projection is unsupported");
        Input input = transaction.input();
        Point pointer = null;
        if (input == null) fail(result.unsupported, rootId, "input", "physical input provenance hook absent");
        else if (input.screenPointer() != null && origin != null)
            pointer = new Point(input.screenPointer().x() - origin.x(), input.screenPointer().y() - origin.y());
        Node focus = scene.getFocusOwner();
        result.interaction = new Interaction(pointer, input == null ? "none" : input.modality(), focus == null ? null : id(focus),
                window != null && window.isFocused() ? rootId : "none", input == null ? 0 : input.gestureSequence(),
                input != null && input.gestureAcknowledged());
        if (input != null && (!input.gestureAcknowledged() || !input.pickedKnown() || input.screenPointer() == null))
            fail(result.unsupported, rootId, "input", "physical pointer/hit-test or gesture acknowledgement unavailable");
        IdentityHashMap<Scene, Boolean> included = new IdentityHashMap<>();
        for (SurfaceReading surface : frame.surfaces()) {
            Scene s = surface.scene(); String instance = id(s.getRoot());
            if (included.put(s, true) != null) throw new IllegalArgumentException("duplicate compositor surface");
            result.surfaces.add(new Surface(instance, s == scene ? "root" : "overlay", screenOrigin(s), point(0, 0, surface.sceneToPng()),
                    bounds(quad(new Box(0, 0, s.getWidth(), s.getHeight()), surface.sceneToPng())), surface.zOrder(),
                    s.getWindow() != null && s.getWindow().isShowing(), false));
            if (s != scene) fail(result.unsupported, instance, "occlusion", "overlay composition requires target occlusion verification");
        }
        if (frame.surfaces().getFirst().scene() != scene) throw new IllegalArgumentException("main compositor surface required first");
        if (!frame.surfaces().getFirst().sceneToPng().equals(frame.logicalToPng()))
            fail(result.unsupported, rootId, "composition", "main composition origin differs from logical-to-PNG transform");
        for (Window shown : Window.getWindows()) if (shown.isShowing() && shown.getScene() != null && !included.containsKey(shown.getScene()))
            fail(result.unsupported, rootId, "composition", "showing scene absent from actual compositor census");
        checkLayout(root, result.unsupported);
        for (Node child : summary.getChildrenUnmodifiable()) {
            if (!visible(child)) continue;
            if (child instanceof Region card && !semanticId(card).isEmpty()) {
                if (result.cards.size() >= PaintObservation.MAX_CARDS) throw new IllegalArgumentException("card census limit");
                Long painted = transaction.lastPaintEpoch(card);
                if (painted == null) fail(result.unsupported, id(card), "lastPaintEpoch", "actual node paint epoch hook absent; zero is an unavailable sentinel");
                Card observed = card(card, frame.logicalToPng(), focus, input, painted == null ? 0 : painted, result.unsupported);
                boolean covered = false;
                for (Surface surface : result.surfaces) if (!surface.instanceId().equals(rootId) && surface.visible()
                        && surface.zOrder() > frame.surfaces().getFirst().zOrder() && intersects(observed.bounds(), surface.box())) covered = true;
                if (covered) {
                    fail(result.unsupported, observed.instanceId(), "occlusion", "target intersects an overlay; opaque coverage cannot be verified");
                    observed = new Card(observed.instanceId(), observed.id(), observed.localBox(), observed.localToPng(), observed.bounds(),
                            observed.visible(), observed.clipped(), true, observed.clipping(), observed.effects(), observed.backgrounds(), observed.borders(),
                            observed.hover(), observed.focused(), observed.focusVisible(), observed.focusWithin(), observed.focusOwner(), observed.physicalHit(),
                            observed.backgroundImplementation(), observed.borderImplementation(), observed.lastPaintEpoch());
                }
                result.cards.add(observed);
            } else if (!(child instanceof Labeled)) fail(result.unsupported, id(child), "card", "unrecognized summary painter");
        }
        walk(toolbar, toolbar, transaction, frame.logicalToPng(), result);
        walk(summary, summary, transaction, frame.logicalToPng(), result);
        return result;
    }

    /** Читает image-census отдельной живой области; используется также узкими тестами без окон. */
    ImageCensus images(Parent scope, Transaction transaction, Transform mapping) {
        Sample result = new Sample(); walk(scope, scope, transaction, mapping, result);
        return new ImageCensus(List.copyOf(result.icons), List.copyOf(result.assets.values()), List.copyOf(result.unsupported));
    }

    /** Ограниченный результат image-census, без утверждения о завершённом захвате сцены. */
    record ImageCensus(List<Icon> icons, List<Asset> assets, List<Unsupported> unsupported) { }

    /** Обходит также скины и unmanaged-детей; маркер cp.icon не ограничивает перепись. */
    private void walk(Node node, Parent scope, Transaction transaction, Transform mapping, Sample result) {
        if (!visible(node)) return;
        Node owner = owner(node, scope);
        if (node instanceof ImageView image) {
            Rectangle2D crop = image.getViewport();
            if (crop != null && image.getImage() != null && (crop.getMinX() < 0 || crop.getMinY() < 0
                    || crop.getMaxX() > image.getImage().getWidth() || crop.getMaxY() > image.getImage().getHeight()))
                fail(result.unsupported, id(node), "viewport", "source viewport extends beyond installed image; drawn destination requires clipping hook");
            Box sourceViewport = crop == null ? null : new Box(crop.getMinX(), crop.getMinY(), crop.getWidth(), crop.getHeight());
            addImage(node, owner, image.getImage(), box(image.getLayoutBounds()), sourceViewport, "image-node",
                    id(node), transaction, mapping, result, true);
        }
        if (node instanceof Region region) {
            if (region.getBackground() != null) {
                int index = 0;
                for (BackgroundImage background : region.getBackground().getImages()) {
                    String occurrence = id(node) + "/background-" + index++;
                    Box destination = backgroundBox(region, background, occurrence, result.unsupported);
                    addImage(node, owner, background.getImage(), destination, null, "css-background", occurrence,
                            transaction, mapping, result, destination.width() > 0 && destination.height() > 0);
                }
            }
            if (region.getBorder() != null && !region.getBorder().getImages().isEmpty())
                fail(result.unsupported, id(node), "borderImage", "nine-slice image painter is unsupported");
            if (region.getShape() != null) fail(result.unsupported, id(node), "shape", "Region shape painter is unsupported");
        }
        if (!(node instanceof Parent) && !(node instanceof ImageView) && !(node instanceof Text))
            fail(result.unsupported, id(node), "paintSource", "unrecognized canvas/vector paint source in capture scope");
        if (!(node instanceof ImageView) && node.getProperties().containsKey("cp.icon")
                && (!(node instanceof Region r) || r.getBackground() == null || r.getBackground().getImages().isEmpty()))
            fail(result.unsupported, id(node), "paintSource", "semantic image marker without an observed image painter");
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) walk(child, scope, transaction, mapping, result);
    }

    /** Сохраняет отдельное фактическое изображение, его quad и цепочку дополнительных альфа. */
    private void addImage(Node node, Node owner, Image image, Box local, Box crop, String sourceKind, String occurrence,
                          Transaction transaction, Transform mapping, Sample result, boolean geometryKnown) {
        if (result.icons.size() >= PaintObservation.MAX_ICONS) throw new IllegalArgumentException("image census limit");
        Transform transform = nodeTransform(node, mapping); List<Point> quad = quad(local, transform);
        String role = role(node, owner); String ownerId = owner == null ? "unbound" : semanticId(owner);
        if (ownerId.isEmpty()) ownerId = "unbound";
        if (owner == null || "unbound".equals(ownerId)) fail(result.unsupported, occurrence, "owner", "real semantic owner unavailable");
        if (!(owner instanceof Control)) fail(result.unsupported, occurrence, "owner", "closest semantic ancestor is not a real toolbar Control");
        if ("unclassified".equals(role)) fail(result.unsupported, occurrence, "role", "image role not recognized from actual ancestry");
        if (!axisAligned(transform)) fail(result.unsupported, occurrence, "transform", "non-axis-aligned or reflected image transform");
        if (!geometryKnown) fail(result.unsupported, occurrence, "geometry", "image destination cannot be resolved");
        List<String> clips = clips(node), effects = effects(node);
        if (!clips.isEmpty()) fail(result.unsupported, occurrence, "clip", "image clipping requires painter verification");
        if (!effects.isEmpty()) fail(result.unsupported, occurrence, "effect", "nonmultiplicative effect or blend is unsupported");
        for (Surface surface : result.surfaces) if (!surface.instanceId().equals(result.viewport.rootInstance())
                && surface.visible() && surface.zOrder() > result.rootZ && intersects(bounds(quad), surface.box()))
            fail(result.unsupported, occurrence, "occlusion", "image destination intersects composed overlay");
        Long painted = transaction.lastPaintEpoch(node);
        if (painted == null) fail(result.unsupported, occurrence, "drawEpoch", "actual node paint epoch hook absent; zero is an unavailable sentinel");
        String assetId = asset(image, transaction, occurrence, result);
        List<OpacityFactor> opacity = opacity(node); double alpha = 1;
        for (OpacityFactor factor : opacity) alpha *= factor.value();
        String key = node.getProperties().get("cp.icon") instanceof String value && !value.isBlank() ? value : null;
        result.icons.add(new Icon(occurrence, owner == null ? id(node) : id(owner), ownerId, role, path(node), sourceKind,
                assetId, image == null ? "absent" : id(image), key, null, null, local, transform, bounds(quad), quad, crop,
                clips, !clips.isEmpty(), visible(node), result.icons.size(), opacity, alpha, "SRC_OVER", effects,
                node instanceof ImageView view ? List.of("ImageView.smooth=" + view.isSmooth()) : List.of("Region-background-texture"),
                node.getBlendMode() == null ? "SRC_OVER" : node.getBlendMode().name(), painted == null ? 0 : painted, result.icons.size(),
                assetId != null && result.unsupported.stream().noneMatch(u -> u.instanceId().equals(occurrence))));
    }

    /** Проверяет исходные байты по identity и сравнивает декодирование с установленными пикселями. */
    private String asset(Image image, Transaction transaction, String occurrence, Sample result) {
        if (image == null) { fail(result.unsupported, occurrence, "image", "installed Image absent"); return null; }
        Source source = transaction.source(image);
        if (source == null || source.installed() != image) {
            fail(result.unsupported, occurrence, "provenance", "decode-captured bytes for installed Image identity absent"); return null;
        }
        try {
            String pixelHash = decodedHash(image); String assetId = id(image) + "/asset";
            byte[] bytes = source.png();
            Asset value = new Asset(assetId, bytes.length, PaintObservationCodec.sha256(bytes), bytes, source.source(),
                    dimension(image.getWidth()), dimension(image.getHeight()), pixelHash, "decode-capture", id(image));
            Image decoded = new Image(new ByteArrayInputStream(bytes));
            if (decoded.getWidth() != image.getWidth() || decoded.getHeight() != image.getHeight() || !decodedHash(decoded).equals(pixelHash))
                throw new IllegalArgumentException("installed pixels differ from retained PNG");
            Asset previous = result.assets.get(assetId);
            if (previous != null && !previous.equals(value)) throw new IllegalArgumentException("source changed within census");
            if (previous == null) {
                if (result.assetBytes + bytes.length > PaintObservation.MAX_ASSET_BYTES) throw new IllegalArgumentException("aggregate source limit");
                result.assets.put(assetId, value); result.assetBytes += bytes.length;
            }
            return assetId;
        } catch (IllegalArgumentException error) {
            fail(result.unsupported, occurrence, "provenance", "installed image/source cannot be verified: " + error.getMessage()); return null;
        }
    }

    /** Читает настоящие фон, рамку, фокус и радиусы Region; токены оформления не участвуют. */
    Card card(Region node, Transform mapping, Node focus, Input input, long epoch, List<Unsupported> unsupported) {
        String instance = id(node); Box local = new Box(0, 0, node.getWidth(), node.getHeight());
        Transform transform = nodeTransform(node, mapping); List<BackgroundLayer> fills = new ArrayList<>(); List<BorderLayer> borders = new ArrayList<>();
        if (node.getBackground() != null) for (var fill : node.getBackground().getFills()) {
            if (fill.getFill() instanceof Color color) fills.add(new BackgroundLayer(argb(color), insets(fill.getInsets()),
                    radii(fill.getRadii(), node.getWidth(), node.getHeight(), fill.getInsets(), instance, unsupported), "javafx.scene.layout.BackgroundFill"));
            else fail(unsupported, instance, "background", "non-solid background Paint: " + fill.getFill().getClass().getName());
        }
        if (node.getBorder() != null) for (BorderStroke border : node.getBorder().getStrokes()) {
            List<Paint> paints = List.of(border.getTopStroke(), border.getRightStroke(), border.getBottomStroke(), border.getLeftStroke());
            List<Integer> colors = new ArrayList<>(); boolean solid = true;
            for (Paint paint : paints) {
                if (paint instanceof Color c) colors.add(argb(c));
                else { solid = false; fail(unsupported, instance, "border", "non-solid border Paint: " + paint.getClass().getName()); }
            }
            if (!solid) continue;
            var widths = border.getWidths();
            List<Double> resolved = List.of(widths.getTop() * (widths.isTopAsPercentage() ? node.getHeight() : 1),
                    widths.getRight() * (widths.isRightAsPercentage() ? node.getWidth() : 1),
                    widths.getBottom() * (widths.isBottomAsPercentage() ? node.getHeight() : 1),
                    widths.getLeft() * (widths.isLeftAsPercentage() ? node.getWidth() : 1));
            if (resolved.stream().anyMatch(w -> w < 0)) { fail(unsupported, instance, "borderWidth", "unresolved automatic border width"); continue; }
            List<String> styles = List.of(borderStyle(border.getTopStyle()), borderStyle(border.getRightStyle()),
                    borderStyle(border.getBottomStyle()), borderStyle(border.getLeftStyle()));
            if (styles.contains("CUSTOM")) fail(unsupported, instance, "borderStyle", "custom/dashed border contour unsupported");
            List<Radius> normalized = radii(border.getRadii(), node.getWidth(), node.getHeight(), border.getInsets(), instance, unsupported);
            List<Radius> pathRadii = null, outer = normalized;
            String placement = "unresolved-outer-contour:" + border.getTopStyle().getType();
            if (border.isStrokeUniform() && uniformCircular(normalized) && styles.stream().allMatch(s -> s.equals("SOLID"))) {
                double stroke = (float) resolved.getFirst().doubleValue();
                double offset = switch (border.getTopStyle().getType()) { case INSIDE -> stroke / 2; case OUTSIDE -> -stroke / 2; case CENTERED -> 0; };
                float left = (float) border.getInsets().getLeft() + (float) offset, right = (float) border.getInsets().getRight() + (float) offset;
                float top = (float) border.getInsets().getTop() + (float) offset, bottom = (float) border.getInsets().getBottom() + (float) offset;
                double pathWidth = (float) node.getWidth() - left - right;
                double pathHeight = (float) node.getHeight() - top - bottom;
                double pathRadius = Math.max(0, Math.min((float) normalized.getFirst().rx(), Math.min(pathWidth, pathHeight) / 2));
                pathRadii = resolvedRadii(normalized, pathRadius);
                outer = resolvedRadii(normalized, pathRadius == 0 ? 0 : pathRadius + stroke / 2);
                placement = "CENTERED;Region-path-offset=" + border.getTopStyle().getType();
                if (pathWidth < 0 || pathHeight < 0) fail(unsupported, instance, "borderContour", "stroke path has negative extent");
            } else fail(unsupported, instance, "borderContour", "nonuniform border outer contour requires painter hook; radii remain normalized inputs");
            borders.add(new BorderLayer(colors, resolved, styles, insets(border.getInsets()), outer, pathRadii,
                    placement, "javafx.scene.layout.BorderStroke/NGRegion"));
        }
        List<String> clipping = clips(node), effects = effects(node);
        if (!clipping.isEmpty()) fail(unsupported, instance, "clip", "card clipping requires contour verification");
        if (!effects.isEmpty()) fail(unsupported, instance, "effect", "card effect/blend alters contour");
        if (!axisAligned(transform)) fail(unsupported, instance, "transform", "non-axis-aligned or reflected card transform");
        for (OpacityFactor factor : opacity(node)) if (factor.value() != 1) fail(unsupported, instance, "opacity", "card/ancestor has extra opacity");
        if (node.getShape() != null) fail(unsupported, instance, "shape", "custom Region shape changes card contour");
        if (node.getBackground() != null && !node.getBackground().getImages().isEmpty()) fail(unsupported, instance, "backgroundImage", "image fill changes card contour");
        if (node.getBorder() != null && !node.getBorder().getImages().isEmpty()) fail(unsupported, instance, "borderImage", "image border changes card contour");
        // Пользовательский подкласс может переопределять краску отдельно от Background/Border.
        if (!List.of(Region.class, javafx.scene.layout.Pane.class, javafx.scene.layout.VBox.class,
                javafx.scene.layout.HBox.class, javafx.scene.layout.StackPane.class, javafx.scene.layout.FlowPane.class,
                javafx.scene.layout.GridPane.class, javafx.scene.layout.BorderPane.class, javafx.scene.layout.TilePane.class,
                javafx.scene.layout.AnchorPane.class).contains(node.getClass())) fail(unsupported, instance, "painter", "custom card painter not recognized");
        Boolean hit = input == null || !input.pickedKnown() ? null : descendant(input.picked(), node);
        if (hit == null) fail(unsupported, instance, "physicalHit", "physical hit-test hook absent");
        return new Card(instance, semanticId(node), local, transform, bounds(quad(local, transform)), visible(node),
                !clipping.isEmpty(), false, clipping, effects, fills, borders, node.isHover(), node.isFocused(), null,
                descendant(focus, node), focus == null ? null : id(focus), hit, node.getClass().getName() + "/Background",
                node.getClass().getName() + "/Border", epoch);
    }

    /** Разрешает проценты и общий коэффициент перекрытия как Region.normalize установленного JavaFX. */
    static List<Radius> radii(CornerRadii r, double width, double height, javafx.geometry.Insets inset,
                              String instance, List<Unsupported> unsupported) {
        double w = Math.max(0, width - inset.getLeft() - inset.getRight()), h = Math.max(0, height - inset.getTop() - inset.getBottom());
        double[] x = {r.getTopLeftHorizontalRadius(), r.getTopRightHorizontalRadius(), r.getBottomLeftHorizontalRadius(), r.getBottomRightHorizontalRadius()};
        double[] y = {r.getTopLeftVerticalRadius(), r.getTopRightVerticalRadius(), r.getBottomLeftVerticalRadius(), r.getBottomRightVerticalRadius()};
        boolean[] px = {r.isTopLeftHorizontalRadiusAsPercentage(), r.isTopRightHorizontalRadiusAsPercentage(), r.isBottomLeftHorizontalRadiusAsPercentage(), r.isBottomRightHorizontalRadiusAsPercentage()};
        boolean[] py = {r.isTopLeftVerticalRadiusAsPercentage(), r.isTopRightVerticalRadiusAsPercentage(), r.isBottomLeftVerticalRadiusAsPercentage(), r.isBottomRightVerticalRadiusAsPercentage()};
        double[] rx = new double[4], ry = new double[4];
        for (int i = 0; i < 4; i++) { rx[i] = x[i] * (px[i] ? w : 1); ry[i] = y[i] * (py[i] ? h : 1); }
        double factor = w <= 0 || h <= 0 ? 0 : 1;
        if (rx[0] + rx[1] > w) factor = Math.min(factor, w / (rx[0] + rx[1]));
        if (rx[2] + rx[3] > w) factor = Math.min(factor, w / (rx[2] + rx[3]));
        if (ry[0] + ry[2] > h) factor = Math.min(factor, h / (ry[0] + ry[2]));
        if (ry[1] + ry[3] > h) factor = Math.min(factor, h / (ry[1] + ry[3]));
        List<Radius> values = new ArrayList<>();
        for (int i = 0; i < 4; i++) values.add(new Radius(x[i], y[i], px[i], py[i], "fx-local", rx[i] * factor, ry[i] * factor));
        return List.copyOf(values);
    }

    /** Различает круглый единый путь рамки от эллипсов и четырёх независимых углов. */
    private static boolean uniformCircular(List<Radius> values) {
        double radius = values.getFirst().rx();
        for (Radius value : values) if (value.rx() != radius || value.ry() != radius) return false;
        return true;
    }

    /** Сохраняет исходные радиусы, изменяя только разрешённый контур реально рисуемого пути. */
    private static List<Radius> resolvedRadii(List<Radius> values, double radius) {
        List<Radius> result = new ArrayList<>();
        for (Radius value : values) result.add(new Radius(value.rawRx(), value.rawRy(), value.percentageX(), value.percentageY(), value.units(), radius, radius));
        return List.copyOf(result);
    }

    /** Разрешает только неповторяющийся фоновый PNG; неизвестная геометрия обозначается пустым боксом. */
    static Box backgroundBox(Region region, BackgroundImage image, String instance, List<Unsupported> unsupported) {
        var size = image.getSize(); var position = image.getPosition(); Image source = image.getImage();
        double w = (float) region.getWidth(), h = (float) region.getHeight(), iw = source.getWidth(), ih = source.getHeight();
        if (iw <= 0 || ih <= 0 || image.getRepeatX() != BackgroundRepeat.NO_REPEAT || image.getRepeatY() != BackgroundRepeat.NO_REPEAT) {
            fail(unsupported, instance, "geometry", "repeated/unloaded background image destination unsupported"); return new Box(0, 0, 0, 0);
        }
        double dw, dh;
        if (size.isCover()) {
            fail(unsupported, instance, "geometry", "cover uses a cropped texture; source viewport hook required"); return new Box(0, 0, 0, 0);
        } else if (size.isContain()) {
            float scale = Math.min((float) w / (float) iw, (float) h / (float) ih);
            // NGRegion округляет contain-размеры вверх до paintTiles, это наблюдаемое правило движка.
            dw = Math.ceil(scale * (float) iw); dh = Math.ceil(scale * (float) ih);
        } else {
            dw = size.getWidth() < 0 ? -1 : size.getWidth() * (size.isWidthAsPercentage() ? w : 1);
            dh = size.getHeight() < 0 ? -1 : size.getHeight() * (size.isHeightAsPercentage() ? h : 1);
            if (dw < 0 && dh < 0) { dw = iw; dh = ih; }
            else if (dw < 0) dw = dh * iw / ih;
            else if (dh < 0) dh = dw * ih / iw;
        }
        double x = position.getHorizontalPosition() * (position.isHorizontalAsPercentage() ? w - dw : 1);
        double y = position.getVerticalPosition() * (position.isVerticalAsPercentage() ? h - dh : 1);
        if (position.getHorizontalSide() == Side.RIGHT) x = w - dw - x;
        if (position.getVerticalSide() == Side.BOTTOM) y = h - dh - y;
        if (x < 0 || y < 0 || x + dw > w || y + dh > h)
            fail(unsupported, instance, "clip", "background destination extends beyond Region paint area");
        return new Box((float) x, (float) y, (float) dw, (float) dh);
    }

    /** Сохраняет все дополнительные множители вплоть до корня, не смешивая их с альфа PNG. */
    List<OpacityFactor> opacity(Node node) {
        List<OpacityFactor> result = new ArrayList<>();
        for (Node n = node; n != null; n = n.getParent()) result.add(new OpacityFactor(id(n), n.getOpacity(), "Node.opacity"));
        return List.copyOf(result);
    }

    /** Хеширует прямой ARGB настоящего PixelReader, включая внутреннюю альфа по отдельности. */
    static String decodedHash(Image image) {
        if (image.isError() || image.getProgress() != 1 || image.getPixelReader() == null
                || image.getWidth() != Math.rint(image.getWidth()) || image.getHeight() != Math.rint(image.getHeight())
                || image.getWidth() * image.getHeight() > 1_048_576) throw new IllegalArgumentException("decoded image unavailable/too large");
        MessageDigest digest = sha256(); byte[] row = new byte[Math.multiplyExact(dimension(image.getWidth()), 4)];
        for (int y = 0; y < image.getHeight(); y++) {
            ByteBuffer buffer = ByteBuffer.wrap(row);
            for (int x = 0; x < image.getWidth(); x++) buffer.putInt(image.getPixelReader().getArgb(x, y));
            digest.update(row);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Перемножает local-to-scene и фактический scene-to-PNG без округления. */
    static Transform nodeTransform(Node node, Transform mapping) {
        var t = node.getLocalToSceneTransform();
        return new Transform(mapping.a() * t.getMxx() + mapping.c() * t.getMyx(), mapping.b() * t.getMxx() + mapping.d() * t.getMyx(),
                mapping.a() * t.getMxy() + mapping.c() * t.getMyy(), mapping.b() * t.getMxy() + mapping.d() * t.getMyy(),
                mapping.a() * t.getTx() + mapping.c() * t.getTy() + mapping.tx(), mapping.b() * t.getTx() + mapping.d() * t.getTy() + mapping.ty());
    }

    /** Сохраняет четыре угла рисования в порядке TL, TR, BL, BR. */
    static List<Point> quad(Box b, Transform t) {
        return List.of(point(b.x(), b.y(), t), point(b.x() + b.width(), b.y(), t),
                point(b.x(), b.y() + b.height(), t), point(b.x() + b.width(), b.y() + b.height(), t));
    }

    /** Применяет аффинную матрицу к одной точке. */
    private static Point point(double x, double y, Transform t) { return new Point(t.a() * x + t.c() * y + t.tx(), t.b() * x + t.d() * y + t.ty()); }

    /** Возвращает лишь envelope; исходный quad остаётся доступным и неподдержанные матрицы отклоняются. */
    static Box bounds(List<Point> quad) {
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
        for (Point p : quad) { minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y()); maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y()); }
        return new Box(minX, minY, maxX - minX, maxY - minY);
    }

    /** Проверяет разрешённую положительную двумерную матрицу без сдвига к целому пикселю. */
    private static boolean axisAligned(Transform t) { return t.b() == 0 && t.c() == 0 && t.a() > 0 && t.d() > 0; }
    /** Проверяет обязательное единичное преобразование строгого профиля. */
    private static boolean unit(Transform t) { return t.a() == 1 && t.d() == 1 && t.b() == 0 && t.c() == 0 && t.tx() == 0 && t.ty() == 0; }
    /** Проверяет пересечение фактических envelopes, не утверждая непрозрачность накрывающей сцены. */
    private static boolean intersects(Box a, Box b) { return a.x() < b.x() + b.width() && a.x() + a.width() > b.x() && a.y() < b.y() + b.height() && a.y() + a.height() > b.y(); }
    /** Копирует фактические Bounds без эффектов или округления. */
    private static Box box(Bounds b) { return new Box(b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()); }
    /** Сохраняет порядок отступов top, right, bottom, left. */
    private static Insets insets(javafx.geometry.Insets i) { return new Insets(i.getTop(), i.getRight(), i.getBottom(), i.getLeft()); }
    /** Читает числовой ARGB краски, без поиска или запасного токена. */
    static int argb(Color c) { return (int) Math.round(c.getOpacity() * 255) << 24 | (int) Math.round(c.getRed() * 255) << 16 | (int) Math.round(c.getGreen() * 255) << 8 | (int) Math.round(c.getBlue() * 255); }
    /** Различает стандартную сплошную рамку, отсутствие и неизвестный штрих. */
    private static String borderStyle(BorderStrokeStyle s) { return s.equals(BorderStrokeStyle.SOLID) ? "SOLID" : s.equals(BorderStrokeStyle.NONE) ? "NONE" : "CUSTOM"; }
    /** Выдаёт устойчивое внутри сборщика имя реального объекта, исключая коллизии identityHashCode. */
    private String id(Object object) { return identities.computeIfAbsent(object, key -> "fx-" + ++nextIdentity); }
    /** Читает только семантическую привязку живого узла, не краску модели. */
    private static String semanticId(Node node) { return Objects.toString(node.getProperties().get("cp.id"), ""); }
    /** Проверяет реальную цепочку предков, включая саму цель. */
    static boolean descendant(Node node, Node ancestor) {
        if (ancestor == null) return false;
        for (Node n = node; n != null; n = n.getParent()) if (n == ancestor) return true;
        return false;
    }
    /** Проверяет видимость всей цепочки, включая нулевую дополнительную альфа. */
    private static boolean visible(Node node) {
        for (Node n = node; n != null; n = n.getParent()) if (!n.isVisible() || n.getOpacity() == 0) return false;
        return true;
    }
    /** Находит ближайший реальный семантический контрол, иначе прямого владельца в области. */
    private static Node owner(Node node, Parent scope) {
        Node candidate = null;
        for (Node n = node; n != null && n != scope; n = n.getParent()) {
            if (!semanticId(n).isEmpty()) { if (n instanceof Control) return n; if (candidate == null) candidate = n; }
        }
        return candidate;
    }
    /** Определяет роль по реальному скину/graphic, а не ключу изображения. */
    private static String role(Node node, Node owner) {
        for (Node n = node; n != null && n != owner; n = n.getParent()) {
            if (n.getStyleClass().contains("arrow") || n.getStyleClass().contains("arrow-button")) return "arrow";
            if (n.getStyleClass().contains("clear-button")) return "clear";
        }
        return owner instanceof Labeled label && descendant(node, label.getGraphic()) ? "glyph" : "unclassified";
    }
    /** Сохраняет фактический путь и индексы детей, включая unmanaged-узлы скина. */
    private static String path(Node node) {
        List<String> parts = new ArrayList<>();
        for (Node n = node; n != null; n = n.getParent()) {
            int index = n.getParent() == null ? 0 : n.getParent().getChildrenUnmodifiable().indexOf(n);
            parts.addFirst(n.getClass().getName() + "[" + index + "]");
        }
        return String.join("/", parts);
    }
    /** Сохраняет ссылки на реальные clip-узлы всех предков. */
    private List<String> clips(Node node) {
        List<String> result = new ArrayList<>();
        for (Node n = node; n != null; n = n.getParent()) if (n.getClip() != null) result.add(id(n) + "/" + id(n.getClip()) + ":" + n.getClip().getClass().getName());
        return result;
    }
    /** Описывает реально установленные эффекты/смешивание; не считает их скалярной альфа. */
    private List<String> effects(Node node) {
        List<String> result = new ArrayList<>();
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.getEffect() != null) result.add(id(n) + "/effect:" + n.getEffect().getClass().getName());
            if (n.getBlendMode() != null && n.getBlendMode() != BlendMode.SRC_OVER) result.add(id(n) + "/blend:" + n.getBlendMode().name());
            if (n.getLocalToSceneTransform().getMxz() != 0 || n.getLocalToSceneTransform().getMyz() != 0
                    || n.getLocalToSceneTransform().getMzx() != 0 || n.getLocalToSceneTransform().getMzy() != 0
                    || n.getLocalToSceneTransform().getMzz() != 1 || n.getLocalToSceneTransform().getTz() != 0)
                result.add(id(n) + "/3d-transform");
        }
        return result;
    }
    /** Отмечает незавершённую раскладку в любой части снимаемой сцены. */
    private void checkLayout(Node node, List<Unsupported> unsupported) {
        if (node instanceof Parent parent) {
            if (parent.isNeedsLayout()) fail(unsupported, id(node), "layout", "layout is pending at observation");
            for (Node child : parent.getChildrenUnmodifiable()) checkLayout(child, unsupported);
        }
    }
    /** Читает начало содержимого сцены на экране, учитывая настоящие декорации окна. */
    private static Point screenOrigin(Scene scene) {
        var local = scene.getRoot().sceneToLocal(0, 0);
        if (local == null) return null;
        var screen = scene.getRoot().localToScreen(local);
        return screen == null ? null : new Point(screen.getX(), screen.getY());
    }
    /** Переводит только допустимый размер; дробный viewport отдельно отклоняется строгим профилем. */
    private static int dimension(double value) {
        if (!Double.isFinite(value) || value < 1 || value > 32768) throw new IllegalArgumentException("capture dimension");
        return (int) Math.round(value);
    }
    /** Проверяет размеры исходного PNG по заголовку; CRC и полную структуру проверит WidgetCapture. */
    private static boolean pngSizeMatches(byte[] bytes, int width, int height) {
        return bytes.length >= 24 && ByteBuffer.wrap(bytes).getLong() == 0x89504e470d0a1a0aL
                && ByteBuffer.wrap(bytes).getInt(16) == width && ByteBuffer.wrap(bytes).getInt(20) == height;
    }
    /** Добавляет честную причину неподдержанного свойства, без исключения всего наблюдения. */
    private static void fail(List<Unsupported> failures, String instance, String property, String reason) {
        Unsupported value = new Unsupported(instance, property, reason);
        if (!failures.contains(value)) failures.add(value);
    }
    /** Создаёт журнал фактических границ транзакции; equality fingerprint не заменяет полноту событий. */
    private Synchronization synchronization(Stamp before, Stamp after, Scene scene, String beforeHash, String afterHash,
                                             long start, long end, List<String> changes) {
        return new Synchronization("fx-scene", before.epoch(), after.epoch(), before.layoutRevision(), after.layoutRevision(),
                before.paintRevision(), after.paintRevision(), before.renderGeneration(), after.renderGeneration(), id(scene),
                beforeHash, afterHash, start, end, before.postLayoutPulse() && after.postLayoutPulse()
                && before.mutationJournalComplete() && after.mutationJournalComplete(), changes);
    }
    /** Хеширует actual-проекцию и raw; служебные часы/хеши Synchronization исключаются из собственной подписи. */
    @SuppressWarnings("unchecked")
    private static String fingerprint(PaintObservation observation, UiDump raw) {
        Map<String, Object> tree = new LinkedHashMap<>((Map<String, Object>) JsonParser.parse(new String(PaintObservationCodec.write(observation), StandardCharsets.UTF_8)));
        tree.remove("synchronization"); tree.put("raw", UiJson.toTree(raw));
        return PaintObservationCodec.sha256(JsonWriter.write(tree).getBytes(StandardCharsets.UTF_8));
    }
    /** Создаёт SHA-256, наличие которого гарантируется JDK. */
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Рабочие списки одного моментального среза; наружу возвращаются копии DTO. */
    private static final class Sample {
        Viewport viewport;
        Interaction interaction;
        final List<Surface> surfaces = new ArrayList<>();
        final Map<String, Asset> assets = new LinkedHashMap<>();
        final List<Icon> icons = new ArrayList<>();
        final List<Card> cards = new ArrayList<>();
        final List<Unsupported> unsupported = new ArrayList<>();
        int assetBytes;
        int rootZ;
        /** Привязывает actual-срез к текущему запросу и журналу, сохраняя неподдержанные сведения. */
        PaintObservation observation(Identity identity, Environment environment, Synchronization synchronization) {
            return new PaintObservation(PaintObservation.SCHEMA, PaintObservation.KIND, identity, viewport, environment,
                    synchronization, interaction, surfaces, new ArrayList<>(assets.values()), icons, cards, unsupported);
        }
    }
}
