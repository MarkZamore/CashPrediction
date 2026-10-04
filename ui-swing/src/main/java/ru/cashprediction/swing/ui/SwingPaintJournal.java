package ru.cashprediction.swing.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.Image;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ImageObserver;
import java.awt.image.PixelGrabber;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import ru.cashprediction.core.ui.selftest.paint.PaintObservation;
import ru.cashprediction.core.ui.selftest.paint.PaintObservationCodec;

/**
 * Журнал настоящих вызовов painter, принадлежащий одному selftest-корню, без глобального состояния.
 * Интегратор открывает эпоху перед полным экранным repaint и PaintScope вокруг полного paint
 * каждого наблюдаемого владельца. Все drawImage/fill/draw этого владельца и его дочерних painter
 * должны проходить через scope, неизвестные операции вызывают unsupported. complete вызывается
 * только после нормального завершения всего paint, включая детей и border. close без complete
 * сохраняет незавершённую попытку. Отдельный scope только вокруг RasterGlyph не доказывает census.
 * Методы сами выполняют рисование на переданном Graphics2D; загрузка ресурса не считается paint.
 * BufferedImage-контекст допускается для модульных тестов, но никогда не признаётся экранным.
 */
public final class SwingPaintJournal {
    private final JComponent root;
    private final Map<Image, Source> sources = new IdentityHashMap<>();
    private final Map<Object, String> identities = new IdentityHashMap<>();
    private final Map<Component, OwnerPaint> owners = new IdentityHashMap<>();
    private final List<PaintScope> scopes = new ArrayList<>();
    private long epoch;
    private long revision;
    private int sequence;
    private int openScopes;
    private boolean collecting;
    private boolean finished;

    /** Привязывает отдельный журнал к фактическому корню содержимого. */
    public SwingPaintJournal(JComponent root) { this.root = Objects.requireNonNull(root); }

    /** Возвращает корень журнала, чтобы интегратор не смешал два окна. */
    public JComponent root() { return root; }

    /** Возвращает локальную идентичность объекта, без семантических токенов и hashCode пользователя. */
    public synchronized String identity(Object object) {
        Objects.requireNonNull(object);
        return identities.computeIfAbsent(object, ignored -> "swing-object-" + (identities.size() + 1));
    }

    /**
     * Декодирует захваченные при загрузке байты и возвращает именно зарегистрированный объект.
     * Вызывается вместо decode адаптера, а не по ключу cp.icon после снимка.
     */
    public BufferedImage decode(byte[] bytes, String origin) throws IOException {
        Objects.requireNonNull(bytes); Objects.requireNonNull(origin);
        if (bytes.length == 0 || bytes.length > PaintObservation.MAX_ASSET_BYTES) throw new IOException("asset size");
        byte[] retained = bytes.clone();
        if (retained.length < 24) throw new IOException("PNG header");
        ByteBuffer header = ByteBuffer.wrap(retained);
        int headerWidth = header.getInt(16), headerHeight = header.getInt(20);
        if (headerWidth <= 0 || headerHeight <= 0 || (long) headerWidth * headerHeight > 262144)
            throw new IOException("decoded asset size");
        BufferedImage image;
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(retained))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("undecodable PNG");
            var reader = readers.next();
            try {
                if (!reader.getFormatName().equalsIgnoreCase("png")) throw new IOException("not a PNG");
                reader.setInput(input, true, true); image = reader.read(0);
            } finally { reader.dispose(); }
        }
        if (image == null || (long) image.getWidth() * image.getHeight() > 262144) throw new IOException("decoded asset size");
        String id = identity(image);
        PaintObservation.Asset asset = new PaintObservation.Asset("asset-" + id, retained.length,
                PaintObservationCodec.sha256(retained), retained, origin, image.getWidth(), image.getHeight(),
                pixels(image), "decode-capture", id);
        Source provenance = new Source(asset, pixels(image), null, null);
        synchronized (this) { sources.put(image, provenance); }
        return image;
    }

    /**
     * Выполняет реальный getScaledInstance и сохраняет связь производного объекта с исходным decode.
     * Вне EDT изображение материализуется заранее; на EDT сохраняется штатная ленивая загрузка.
     * Неготовый draw остаётся неполным, ожидание загрузки на EDT не используется.
     */
    public Image scale(Image source, int width, int height, int hints) throws IOException {
        Source provenance;
        synchronized (this) { provenance = sources.get(source); }
        if (provenance == null || provenance.installedPixels == null || !provenance.installedPixels.equals(pixels(source))) throw new IOException("unknown or changed source");
        if (width <= 0 || height <= 0 || (long) width * height > 262144) throw new IOException("scaled asset size");
        Image scaled = source.getScaledInstance(width, height, hints);
        String digest = null;
        if (!SwingUtilities.isEventDispatchThread()) {
            PixelGrabber loaded = new PixelGrabber(scaled, 0, 0, width, height, true);
            try {
                if (!loaded.grabPixels(1000) || (loaded.getStatus() & (ImageObserver.ERROR | ImageObserver.ABORT)) != 0)
                    throw new IOException("scaled image not decoded");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            digest = pixels(scaled);
        }
        PaintObservation.Asset a = provenance.asset; String id = identity(scaled);
        Source derived = new Source(new PaintObservation.Asset("asset-" + id, a.byteLength(), a.sha256(),
                a.base64(), a.source(), a.decodedWidth(), a.decodedHeight(), a.decodedArgbSha256(),
                a.provenanceMethod(), id), digest, source, provenance.installedPixels);
        synchronized (this) { sources.put(scaled, derived); }
        return scaled;
    }

    /** Начинает свежую эпоху полного repaint; предыдущие вызовы не переносятся. */
    public void beginEpoch() {
        edt();
        if (collecting || openScopes != 0) throw new IllegalStateException("paint epoch already open");
        epoch++; revision++; sequence = 0; owners.clear(); collecting = true; finished = false;
    }

    /** Разрешает локальному context подключить callbacks только внутри открытой эпохи. */
    boolean collectingEpoch() { edt(); return collecting; }

    /**
     * Открывает один полный проход владельца; повторный проход заменяет старый, но его draws не сливаются.
     * Graphics должен находиться в системе координат владельца на входе в его paint.
     */
    public PaintScope openOwner(JComponent owner, Graphics2D graphics) {
        edt(); Objects.requireNonNull(owner); Objects.requireNonNull(graphics);
        if (!collecting || !SwingUtilities.isDescendingFrom(owner, root) && owner != root)
            throw new IllegalStateException("owner outside active epoch");
        if (owners.containsKey(owner) && !owners.get(owner).closed) throw new IllegalStateException("nested owner paint");
        OwnerPaint paint = new OwnerPaint(owner, epoch, graphics); owners.put(owner, paint);
        openScopes++; revision++;
        PaintScope scope = new PaintScope(paint); scopes.add(scope); return scope;
    }

    /**
     * Возвращает ближайший активный контекст для реального потомка: hook ImageIcon/TextLayout
     * получает его через привязанный к владельцу экземпляр журнала, без глобального singleton.
     * Отсутствие scope не допускает изготовления записи задним числом.
     */
    public PaintScope currentScope(Component component) {
        edt();
        for (int index = scopes.size() - 1; index >= 0; index--) {
            PaintScope scope = scopes.get(index); JComponent owner = scope.paint.owner;
            if (component == owner || SwingUtilities.isDescendingFrom(component, owner)) return scope;
        }
        return null;
    }

    /** Завершает эпоху только после возврата всех реальных painter, не создавая отсутствующие записи. */
    public void finishEpoch() {
        edt();
        if (!collecting || openScopes != 0) throw new IllegalStateException("unfinished paint scopes");
        collecting = false; finished = true; revision++;
    }

    /** Отмечает repaint вне собираемой эпохи, в том числе изменение с последующим возвратом. */
    public void paintInvalidated() { edt(); revision++; finished = false; }

    /** Снимает неизменяемый журнал на EDT; незавершённость остаётся наблюдаемой. */
    public Snapshot snapshot() {
        edt();
        List<OwnerSnapshot> result = new ArrayList<>();
        for (OwnerPaint paint : owners.values()) {
            for (ImageDraw image : paint.images) if (image.asset() != null) {
                Source source;
                synchronized (this) { source = sources.get(image.image()); }
                boolean current = false;
                try { current = source != null && source.installedPixels != null && source.installedPixels.equals(pixels(image.image()))
                        && (source.parent == null || source.parentPixels.equals(pixels(source.parent))); }
                catch (IOException failure) { /* Неизвестный источник остаётся причиной отказа. */ }
                if (!current && !paint.unsupported.contains("installed image changed after draw")) {
                    paint.unsupported.add("installed image changed after draw"); revision++; finished = false;
                }
            }
            result.add(new OwnerSnapshot(paint.owner, paint.epoch,
                paint.completed && paint.closed && paint.fullClip && paint.screen, paint.fullClip, paint.screen,
                List.copyOf(paint.images), List.copyOf(paint.shapes), List.copyOf(paint.unsupported)));
        }
        return new Snapshot(epoch, revision, finished && openScopes == 0, List.copyOf(result));
    }

    /** Одно фактическое изображение; nullable asset означает неизвестный или изменённый источник. */
    public record ImageDraw(JComponent painter, Image image, PaintObservation.Asset asset, String sourceIdentity, String role,
                            String paintSource, PaintObservation.Box destination, GraphicsState state,
                            int order, int occurrence, boolean drawn) { }

    /** Фактическая заливка или штрих, скопированные до выхода painter. */
    public record ShapeDraw(Shape shape, boolean fill, GraphicsState state, String implementation, int order) {
        /** Защищает геометрию от изменения исходного объекта после paint. */
        public ShapeDraw { shape = copyShape(shape); }
        /** Возвращает отдельную копию сохранённого пути. */
        @Override public Shape shape() { return copyShape(shape); }
    }

    /** Состояние Graphics2D во время вызова, относительно начального Graphics владельца. */
    public record GraphicsState(PaintObservation.Transform transform, Shape clip, Integer argb,
                                double alpha, String composite, boolean sourceOver, Double strokeWidth,
                                boolean solidStroke, List<String> unsupported) {
        /** Защищает clip и список неизвестных свойств. */
        public GraphicsState { clip = clip == null ? null : new Path2D.Double(clip); unsupported = List.copyOf(unsupported); }
        /** Возвращает копию clip. */
        @Override public Shape clip() { return clip == null ? null : new Path2D.Double(clip); }
    }

    /** Полный либо частичный проход одного владельца, без объединения исторических перерисовок. */
    public record OwnerSnapshot(JComponent owner, long epoch, boolean complete, boolean fullClip,
                                boolean screen, List<ImageDraw> images, List<ShapeDraw> shapes,
                                List<String> unsupported) { }

    /** Снимок одной эпохи, применимый только к конкретному журналу. */
    public record Snapshot(long epoch, long revision, boolean finished, List<OwnerSnapshot> owners) { }

    /**
     * Контекст полного paint владельца. Интегратор передаёт ему актуальный Graphics каждого draw,
     * включая копии и смещения TextLayout, а не Graphics до установки composite.
     */
    public final class PaintScope implements AutoCloseable {
        private final OwnerPaint paint;
        private boolean closed;
        private boolean completed;

        private PaintScope(OwnerPaint paint) { this.paint = paint; }

        /** Выполняет drawImage с фактическим прямоугольником, сохраняя повторы и результат загрузки. */
        public boolean drawImage(Graphics2D graphics, Image image, int x, int y, int width, int height,
                                 ImageObserver observer, String role, String source) {
            return drawImage(paint.owner, graphics, image, x, y, width, height, observer, role, source);
        }

        /**
         * Сохраняет реальный компонент ImageIcon.paintIcon/SwingIcons.paint, даже внутри split/filter.
         * Graphics остаётся текущим graphics потомка; transform хранит фактическое смещение в scope.
         */
        public boolean drawImage(JComponent painter, Graphics2D graphics, Image image, int x, int y, int width, int height,
                                 ImageObserver observer, String role, String source) {
            live();
            Objects.requireNonNull(painter);
            if (painter != paint.owner && !SwingUtilities.isDescendingFrom(painter, paint.owner))
                throw new IllegalArgumentException("image painter outside owner scope");
            if (paint.images.size() >= PaintObservation.MAX_ICONS) throw new IllegalStateException("owner image census limit");
            if (width < 0 || height < 0) { unsupported("flipped image destination"); throw new IllegalArgumentException("negative image extent"); }
            GraphicsState state = state(graphics, paint.base);
            boolean drawn = graphics.drawImage(image, x, y, width, height, observer);
            Source retained;
            synchronized (SwingPaintJournal.this) { retained = sources.get(image); }
            PaintObservation.Asset asset = null;
            if (retained != null) {
                try {
                    String actualPixels = pixels(image);
                    boolean originalCurrent = retained.parent == null || retained.parentPixels.equals(pixels(retained.parent));
                    if (originalCurrent && (retained.installedPixels == null || retained.installedPixels.equals(actualPixels))) {
                        asset = retained.asset;
                        if (retained.installedPixels == null) synchronized (SwingPaintJournal.this) {
                            sources.put(image, new Source(retained.asset, actualPixels, retained.parent, retained.parentPixels));
                        }
                    }
                }
                catch (IOException failure) { unsupported("installed image unreadable"); }
            }
            if (asset == null) unsupported("unknown or changed image identity");
            if (!List.of("glyph", "arrow", "clear", "unclassified").contains(role)) role = "unclassified";
            if (!List.of("icon-paint", "text-glyph", "image-node", "unclassified").contains(source)) source = "unclassified";
            paint.images.add(new ImageDraw(painter, image, asset, identity(image), role, source,
                    new PaintObservation.Box(x, y, width, height), state, sequence++, paint.images.size(), drawn));
            revision++; return drawn;
        }

        /** Выполняет фактическую заливку, отдельно от рамки и свойства component.background. */
        public void fill(Graphics2D graphics, Shape shape, String implementation) {
            live(); GraphicsState state = state(graphics, paint.base);
            if (paint.shapes.size() >= 64) throw new IllegalStateException("owner shape census limit");
            Shape retained = copyShape(shape); graphics.fill(shape);
            paint.shapes.add(new ShapeDraw(retained, true, state, implementation, sequence++)); revision++;
        }

        /** Выполняет фактический draw пути с текущим stroke, цветом, clip и composite. */
        public void draw(Graphics2D graphics, Shape shape, String implementation) {
            live(); GraphicsState state = state(graphics, paint.base);
            if (paint.shapes.size() >= 64) throw new IllegalStateException("owner shape census limit");
            Shape retained = copyShape(shape); graphics.draw(shape);
            paint.shapes.add(new ShapeDraw(retained, false, state, implementation, sequence++)); revision++;
        }

        /** Сохраняет неподдержанный путь painter вместо замены ожидаемыми параметрами. */
        public void unsupported(String reason) { live(); if (paint.unsupported.size() < 128) paint.unsupported.add(Objects.requireNonNull(reason)); revision++; }

        /** Отмечает нормальный возврат полного paint; finally/close сами успех не объявляют. */
        public void complete() { live(); completed = true; }

        /** Закрывает проход; частичный clip, offscreen и прерванный paint остаются неполными. */
        @Override public void close() {
            edt(); if (closed) return;
            if (scopes.isEmpty() || scopes.getLast() != this) throw new IllegalStateException("paint scopes must close in nesting order");
            scopes.removeLast();
            paint.closed = true; paint.completed = completed; closed = true; openScopes--; revision++;
        }

        private void live() { edt(); if (closed || completed || !collecting) throw new IllegalStateException("closed paint scope"); }
    }

    /** Внутреннее неизменяемое происхождение установленного объекта и его текущих пикселей. */
    private record Source(PaintObservation.Asset asset, String installedPixels, Image parent, String parentPixels) { }

    /** Изменяемый только на EDT проход владельца. */
    private static final class OwnerPaint {
        final JComponent owner;
        final long epoch;
        final AffineTransform base;
        final boolean fullClip, screen;
        final List<ImageDraw> images = new ArrayList<>();
        final List<ShapeDraw> shapes = new ArrayList<>();
        final List<String> unsupported = new ArrayList<>();
        boolean completed, closed;

        OwnerPaint(JComponent owner, long epoch, Graphics2D graphics) {
            this.owner = owner; this.epoch = epoch; base = graphics.getTransform();
            Shape clip = graphics.getClip();
            fullClip = clip == null || new Area(clip).contains(0, 0, owner.getWidth(), owner.getHeight());
            screen = owner.isShowing() && graphics.getDeviceConfiguration().getDevice().getType() == GraphicsDevice.TYPE_RASTER_SCREEN;
            if (base.getScaleX() != 1 || base.getScaleY() != 1 || base.getShearX() != 0 || base.getShearY() != 0
                    || base.getTranslateX() != Math.rint(base.getTranslateX()) || base.getTranslateY() != Math.rint(base.getTranslateY()))
                unsupported.add("unsupported initial owner graphics transform");
        }
    }

    /** Снимает фактические свойства graphics, не переводя неизвестный цвет в токен. */
    private static GraphicsState state(Graphics2D graphics, AffineTransform base) {
        List<String> errors = new ArrayList<>(); AffineTransform relative;
        try { relative = base.createInverse(); relative.concatenate(graphics.getTransform()); }
        catch (java.awt.geom.NoninvertibleTransformException failure) { throw new IllegalArgumentException("singular paint transform", failure); }
        double[] m = new double[6]; relative.getMatrix(m);
        Integer argb = graphics.getPaint() instanceof Color color ? color.getRGB() : null;
        if (argb == null) errors.add("non-solid paint");
        double alpha = 1; boolean over = false; String composite = graphics.getComposite().getClass().getName();
        if (graphics.getComposite() instanceof AlphaComposite value) {
            alpha = value.getAlpha(); over = value.getRule() == AlphaComposite.SRC_OVER;
            composite = "AlphaComposite:" + value.getRule();
        }
        if (!over) errors.add("non-SRC_OVER composite");
        Double width = null; boolean solid = false;
        if (graphics.getStroke() instanceof BasicStroke stroke) { width = (double) stroke.getLineWidth(); solid = stroke.getDashArray() == null; }
        if (!solid) errors.add("unsupported stroke");
        return new GraphicsState(new PaintObservation.Transform(m[0], m[1], m[2], m[3], m[4], m[5]),
                graphics.getClip(), argb, alpha, composite, over, width, solid, errors);
    }

    /** Копирует известные примитивы с сохранением диаметра roundRect; остальные пути остаются неизвестными. */
    private static Shape copyShape(Shape shape) {
        Objects.requireNonNull(shape);
        if (shape instanceof RoundRectangle2D r) return new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), r.getArcWidth(), r.getArcHeight());
        if (shape instanceof Rectangle2D r) return new Rectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight());
        return new Path2D.Double(shape);
    }

    /** Хеширует прямой ARGB установленного изображения, не рисуя его на проверочный canvas. */
    private static String pixels(Image image) throws IOException {
        int width = image.getWidth(null), height = image.getHeight(null);
        if (width <= 0 || height <= 0 || (long) width * height > 262144) throw new IOException("image not decoded or too large");
        int[] argb;
        if (image instanceof BufferedImage buffered) argb = buffered.getRGB(0, 0, width, height, null, 0, width);
        else {
            PixelGrabber grabber = new PixelGrabber(image, 0, 0, width, height, true);
            try {
                if (!grabber.grabPixels(SwingUtilities.isEventDispatchThread() ? 1 : 1000)
                        || (grabber.getStatus() & (ImageObserver.ERROR | ImageObserver.ABORT)) != 0)
                    throw new IOException("image pixels unavailable");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException(interrupted); }
            argb = (int[]) grabber.getPixels();
        }
        ByteBuffer bytes = ByteBuffer.allocate(argb.length * 4); for (int pixel : argb) bytes.putInt(pixel);
        return PaintObservationCodec.sha256(bytes.array());
    }

    /** Запрещает чтение или изменение paint-эпохи вне EDT. */
    private static void edt() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("paint journal requires EDT"); }
}
