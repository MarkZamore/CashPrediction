package ru.cashprediction.core.ui.selftest.paint;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Наблюдения настоящих виджетов одного захвата S5. Не содержит эталонов или ожидаемых токенов.
 * Неизвестные источники сохраняются с unsupported; такая запись не означает успешный захват.
 * Геометрия не округляется. Углы следуют порядку TL, TR, BL, BR; стороны - top, right, bottom, left.
 * Хеш декодирования описывает SHA-256 прямого ARGB: строки сверху вниз, байты A,R,G,B каждого пикселя.
 * @param schema версия независимой схемы наблюдений
 * @param kind вид наблюдения
 * @param identity идентичность опыта
 * @param viewport геометрия снимка
 * @param environment происхождение среды
 * @param synchronization барьер и поколения отрисовки
 * @param interaction фактический ввод
 * @param surfaces поверхности снимка
 * @param assets захваченные источники PNG
 * @param icons отдельные фактические рисования изображений
 * @param cards фактические карточки
 * @param unsupported неподдержанные свойства
 */
public record PaintObservation(int schema, String kind, Identity identity, Viewport viewport,
                               Environment environment, Synchronization synchronization,
                               Interaction interaction, List<Surface> surfaces, List<Asset> assets,
                               List<Icon> icons, List<Card> cards, List<Unsupported> unsupported) {
    /** Версия companion-схемы, независимо от UiDump. */
    public static final int SCHEMA = 1;
    /** Вид companion-наблюдения. */
    public static final String KIND = "widget-paint-observation";
    /** Максимум изображений в захвате. */
    public static final int MAX_ICONS = 128;
    /** Максимум карточек в захвате. */
    public static final int MAX_CARDS = 32;
    /** Общий предел исходных байтов ресурсов. */
    public static final int MAX_ASSET_BYTES = 1024 * 1024;

    /** Проверяет структуру, ссылки и ограниченные размеры, сохраняя ошибки отрисовки как наблюдения. */
    public PaintObservation {
        require(schema == SCHEMA && KIND.equals(kind), "paint schema/kind");
        Objects.requireNonNull(identity); Objects.requireNonNull(viewport); Objects.requireNonNull(environment);
        Objects.requireNonNull(synchronization); Objects.requireNonNull(interaction);
        surfaces = copy(surfaces, 64); assets = copy(assets, MAX_ICONS);
        icons = copy(icons, MAX_ICONS); cards = copy(cards, MAX_CARDS); unsupported = copy(unsupported, 512);
        Set<String> instances = new HashSet<>();
        surfaces.forEach(s -> require(instances.add(s.instanceId()), "duplicate instance"));
        icons.forEach(i -> require(instances.add(i.instanceId()), "duplicate instance"));
        Set<String> cardIds = new HashSet<>();
        cards.forEach(c -> {
            require(instances.add(c.instanceId()), "duplicate instance");
            require(cardIds.add(c.id()), "duplicate card id");
        });
        require(surfaces.stream().anyMatch(s -> s.instanceId().equals(viewport.rootInstance())), "missing root surface");
        Set<String> assetIds = new HashSet<>();
        int bytes = 0;
        for (Asset asset : assets) {
            require(assetIds.add(asset.assetId()), "duplicate asset");
            bytes = Math.addExact(bytes, asset.byteLength());
        }
        require(bytes <= MAX_ASSET_BYTES, "assets limit");
        for (Icon icon : icons) {
            require(icon.assetId() == null || assetIds.contains(icon.assetId()), "unresolved asset reference");
            if (icon.assetId() == null || !icon.complete() || icon.role().equals("unclassified")
                    || icon.paintSource().equals("unclassified"))
                require(unsupported.stream().anyMatch(u -> u.instanceId().equals(icon.instanceId())), "missing unsupported source");
            if (icon.assetId() != null) {
                Asset asset = assets.stream().filter(a -> a.assetId().equals(icon.assetId())).findFirst().orElseThrow();
                require(asset.sourceObjectIdentity().equals(icon.sourceObjectIdentity()), "installed source identity");
            }
        }
    }

    /** Идентичность записи; model не является источником настоящей краски. */
    public record Identity(UUID runId, UUID captureId, int commandNumber, String client,
                           String scenario, String step, int attempt, String planSha256) {
        /** Проверяет идентификаторы и безопасные имена. */
        public Identity {
            Objects.requireNonNull(runId); Objects.requireNonNull(captureId); nonnegative(commandNumber);
            choice(client, "fx", "swing", "web"); component(scenario); component(step);
            require(attempt > 0, "attempt"); hash(planSha256);
        }
    }

    /** Конечные координаты точки. В JSON представляется массивом [x,y]. */
    public record Point(double x, double y) {
        /** Проверяет конечность координат. */
        public Point { finite(x); finite(y); }
    }

    /** Прямоугольник, включая дробные координаты. */
    public record Box(double x, double y, double width, double height) {
        /** Не допускает отрицательных размеров и бесконечных краёв. */
        public Box { finite(x); finite(y); positiveOrZero(width); positiveOrZero(height); finite(x + width); finite(y + height); }
    }

    /** Преобразование x'=a*x+c*y+tx, y'=b*x+d*y+ty; JSON-массив [a,b,c,d,tx,ty]. */
    public record Transform(double a, double b, double c, double d, double tx, double ty) {
        /** Проверяет каждый коэффициент без округления. */
        public Transform { finite(a); finite(b); finite(c); finite(d); finite(tx); finite(ty); }
    }

    /** Размеры содержимого и PNG; масштаб не подменяется единичным. */
    public record Viewport(int logicalWidth, int logicalHeight, int pngWidth, int pngHeight,
                           Transform logicalToPng, Point screenContentOrigin, double outputScaleX,
                           double outputScaleY, Double browserDpr, String rootInstance) {
        /** Проверяет размеры и масштабы; отсутствие экранного начала допустимо для web. */
        public Viewport {
            dimension(logicalWidth); dimension(logicalHeight); dimension(pngWidth); dimension(pngHeight);
            Objects.requireNonNull(logicalToPng); positive(outputScaleX); positive(outputScaleY);
            if (browserDpr != null) positive(browserDpr); text(rootInstance);
        }
    }

    /** Диагностическое происхождение среды, без выбора эталона по клиенту. */
    public record Environment(String os, String runtime, String renderer, double contentScale,
                              String fontLoadStatus, Map<String, String> artifactDigests) {
        /** Копирует хеши артефактов. */
        public Environment {
            text(os); text(runtime); text(renderer); positive(contentScale); text(fontLoadStatus);
            artifactDigests = Map.copyOf(artifactDigests); require(artifactDigests.size() <= 128, "artifact limit");
            artifactDigests.forEach((name, digest) -> { text(name); hash(digest); });
        }
    }

    /**
     * Барьер actual-снимка. Счётчики событий должны обнаруживать и изменение с возвратом (ABA).
     * Времена nanoTime допускают знак и переход через границу long; в JSON это десятичные строки.
     * frameId - фактическая идентичность кадра/сцены, поколения читаются до и после захвата.
     */
    public record Synchronization(String mode, long epochBefore, long epochAfter,
                                   long layoutRevisionBefore, long layoutRevisionAfter,
                                   long paintRevisionBefore, long paintRevisionAfter,
                                   long renderGenerationBefore, long renderGenerationAfter,
                                   String frameId, String fingerprintBefore, String fingerprintAfter,
                                   long startNanos, long endNanos, boolean settled, List<String> changes) {
        /** Проверяет счётчики и копирует причины нестабильности. */
        public Synchronization {
            choice(mode, "fx-scene", "bracketed-screen", "bracketed-cdp");
            nonnegative(epochBefore); nonnegative(epochAfter); nonnegative(layoutRevisionBefore); nonnegative(layoutRevisionAfter);
            nonnegative(paintRevisionBefore); nonnegative(paintRevisionAfter);
            nonnegative(renderGenerationBefore); nonnegative(renderGenerationAfter);
            text(frameId); hash(fingerprintBefore); hash(fingerprintAfter);
            require(endNanos - startNanos >= 0, "monotonic interval");
            changes = copy(changes, 512); changes.forEach(PaintObservation::text);
        }

        /** Возвращает стабильность всех зарегистрированных поколений, не только равенство картинок. */
        public boolean stable() {
            return settled && changes.isEmpty() && epochBefore == epochAfter
                    && layoutRevisionBefore == layoutRevisionAfter && paintRevisionBefore == paintRevisionAfter
                    && renderGenerationBefore == renderGenerationAfter && fingerprintBefore.equals(fingerprintAfter);
        }
    }

    /** Фактическое состояние ввода, фокуса и подтверждённой последовательности жестов. */
    public record Interaction(Point pointer, String modality, String focusOwner, String activeRoot,
                              long gestureSequence, boolean gestureAcknowledged) {
        /** Проверяет вид ввода и номер подтверждения. */
        public Interaction {
            choice(modality, "none", "pointer", "keyboard"); optionalText(focusOwner); text(activeRoot); nonnegative(gestureSequence);
        }
    }

    /** Реальная поверхность, включённая в композицию PNG. */
    public record Surface(String instanceId, String kind, Point screenOrigin, Point pngOrigin,
                          Box box, int zOrder, boolean visible, boolean occluded) {
        /** Проверяет фактическое начало и рамку. */
        public Surface { text(instanceId); text(kind); Objects.requireNonNull(pngOrigin); Objects.requireNonNull(box); }
    }

    /** Исходные PNG-байты, захваченные при установке и декодировании реального объекта изображения. */
    public record Asset(String assetId, int byteLength, String sha256, byte[] base64, String source,
                        int decodedWidth, int decodedHeight, String decodedArgbSha256,
                        String provenanceMethod, String sourceObjectIdentity) {
        /** Защищает байты и проверяет их хеш; декодированные пиксели обязан измерить клиент. */
        public Asset {
            text(assetId); text(source); text(sourceObjectIdentity); hash(sha256); hash(decodedArgbSha256);
            choice(provenanceMethod, "decode-capture", "response-capture");
            Objects.requireNonNull(base64); require(base64.length <= MAX_ASSET_BYTES, "asset bytes limit"); base64 = base64.clone();
            require(byteLength == base64.length && byteLength > 0, "asset length");
            require(sha256.equals(PaintObservationCodec.sha256(base64)), "asset digest");
            PaintObservationCodec.pngDimensions(base64, decodedWidth, decodedHeight);
        }

        /** Возвращает копию исходных байтов; кодек записывает их каноническим base64. */
        @Override public byte[] base64() { return base64.clone(); }

        /** Сравнивает происхождение и содержимое байтов, а не адрес массива. */
        @Override public boolean equals(Object other) {
            return other instanceof Asset a && assetId.equals(a.assetId) && byteLength == a.byteLength
                    && sha256.equals(a.sha256) && java.util.Arrays.equals(base64, a.base64)
                    && source.equals(a.source) && decodedWidth == a.decodedWidth && decodedHeight == a.decodedHeight
                    && decodedArgbSha256.equals(a.decodedArgbSha256) && provenanceMethod.equals(a.provenanceMethod)
                    && sourceObjectIdentity.equals(a.sourceObjectIdentity);
        }

        /** Вычисляет хеш неизменяемого содержимого ресурса. */
        @Override public int hashCode() {
            return 31 * Objects.hash(assetId, byteLength, sha256, source, decodedWidth, decodedHeight,
                    decodedArgbSha256, provenanceMethod, sourceObjectIdentity) + java.util.Arrays.hashCode(base64);
        }
    }

    /** Отдельный дополнительный множитель альфа, без внутренней альфа PNG. */
    public record OpacityFactor(String sourceInstance, double value, String mechanism) {
        /** Проверяет множитель. */
        public OpacityFactor { text(sourceInstance); unit(value); text(mechanism); }
    }

    /** Реальное рисование изображения, включая inline-глифы и CSS-фоны; повторы остаются отдельными. */
    public record Icon(String instanceId, String ownerInstance, String ownerId, String role,
                       String widgetPath, String paintSource, String assetId, String sourceObjectIdentity,
                       String semanticKey, String variantToken, Integer rawArgb, Box localBox,
                       Transform localToPng, Box bounds, List<Point> quad, Box sourceViewport,
                       List<String> clipping, boolean clipped, boolean visible, int paintOrder,
                       List<OpacityFactor> opacityFactors, double effectiveOpacity, String compositeMode,
                       List<String> effects, List<String> filters, String blendMode,
                       long drawEpoch, int occurrenceIndex, boolean complete) {
        /** Копирует списки, проверяет фактическую геометрию и дополнительные альфа-множители. */
        public Icon {
            text(instanceId); text(ownerInstance); text(ownerId); choice(role, "glyph", "arrow", "clear", "unclassified");
            text(widgetPath); choice(paintSource, "image-node", "icon-paint", "text-glyph", "css-background", "pseudo-element", "unclassified");
            optionalText(assetId); text(sourceObjectIdentity); optionalText(semanticKey); optionalText(variantToken);
            Objects.requireNonNull(localBox); Objects.requireNonNull(localToPng); Objects.requireNonNull(bounds);
            quad = copy(quad, 4); require(quad.size() == 4, "image quad");
            clipping = strings(clipping); opacityFactors = copy(opacityFactors, 64); unit(effectiveOpacity);
            text(compositeMode); effects = strings(effects); filters = strings(filters); text(blendMode);
            nonnegative(drawEpoch); nonnegative(occurrenceIndex); nonnegative(paintOrder);
        }
    }

    /** Радиус геометрического внешнего контура, с исходными величинами и признаком процентов. */
    public record Radius(double rawRx, double rawRy, boolean percentageX, boolean percentageY,
                         String units, double rx, double ry) {
        /** Проверяет исходные и разрешённые радиусы. */
        public Radius { positiveOrZero(rawRx); positiveOrZero(rawRy); text(units); positiveOrZero(rx); positiveOrZero(ry); }
    }

    /** Отступы слоя по сторонам top, right, bottom, left. */
    public record Insets(double top, double right, double bottom, double left) {
        /** Допускает отрицательные отступы, но не бесконечность. */
        public Insets { finite(top); finite(right); finite(bottom); finite(left); }
    }

    /** Фактический слой заливки с собственной геометрией, отдельно от рамки. */
    public record BackgroundLayer(int argb, Insets insets, List<Radius> radii, String implementation) {
        /** Проверяет четыре угла и происхождение заливки. */
        public BackgroundLayer { Objects.requireNonNull(insets); radii = corners(radii); text(implementation); }
    }

    /**
     * Фактическая рамка. radii - внешний контур; pathRadii - радиусы пути до учёта штриха, nullable.
     * strokePlacement сохраняет способ штриха, чтобы преобразование диаметра roundRect было проверяемым.
     */
    public record BorderLayer(List<Integer> colors, List<Double> widths, List<String> styles,
                              Insets insets, List<Radius> radii, List<Radius> pathRadii,
                              String strokePlacement, String implementation) {
        /** Проверяет четыре стороны и угла, не сводя слои к ожидаемому токену. */
        public BorderLayer {
            colors = copy(colors, 4); widths = copy(widths, 4); styles = copy(styles, 4);
            require(colors.size() == 4 && widths.size() == 4 && styles.size() == 4, "border sides");
            widths.forEach(PaintObservation::positiveOrZero); styles.forEach(PaintObservation::text);
            Objects.requireNonNull(insets); radii = corners(radii); if (pathRadii != null) pathRadii = corners(pathRadii);
            text(strokePlacement); text(implementation);
        }
    }

    /** Карточка с фактическими слоями, физическим hit-test и независимо прочитанным фокусом. */
    public record Card(String instanceId, String id, Box localBox, Transform localToPng, Box bounds,
                       boolean visible, boolean clipped, boolean occluded, List<String> clipping,
                       List<String> effects, List<BackgroundLayer> backgrounds, List<BorderLayer> borders,
                       boolean hover, boolean focused, Boolean focusVisible, boolean focusWithin,
                       String focusOwner, Boolean physicalHit, String backgroundImplementation,
                       String borderImplementation, long lastPaintEpoch) {
        /** Копирует фактические слои и проверяет их идентичность. */
        public Card {
            text(instanceId); text(id); Objects.requireNonNull(localBox); Objects.requireNonNull(localToPng); Objects.requireNonNull(bounds);
            clipping = strings(clipping); effects = strings(effects); backgrounds = copy(backgrounds, 32); borders = copy(borders, 32);
            optionalText(focusOwner); text(backgroundImplementation); text(borderImplementation); nonnegative(lastPaintEpoch);
        }
    }

    /** Причина, по которой источник или свойство не могут служить свидетельством краски. */
    public record Unsupported(String instanceId, String property, String reason) {
        /** Проверяет диагностические сведения. */
        public Unsupported { text(instanceId); text(property); text(reason); }
    }

    static void require(boolean value, String reason) { if (!value) throw new IllegalArgumentException(reason); }
    static void text(String value) { require(value != null && !value.isBlank() && value.length() <= 4096, "text"); }
    static void optionalText(String value) { if (value != null) text(value); }
    static void component(String value) {
        text(value); require(value.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}") && !value.endsWith("."), "path component");
        String stem = value.split("\\.", 2)[0].toUpperCase(java.util.Locale.ROOT);
        require(!stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"), "reserved component");
    }
    static void hash(String value) { require(value != null && value.matches("[0-9a-f]{64}"), "sha256"); }
    static void finite(double value) { require(Double.isFinite(value), "finite number"); }
    static void nonnegative(long value) { require(value >= 0 && value <= PaintObservationCodec.MAX_SAFE_INTEGER, "safe nonnegative counter"); }
    static void positiveOrZero(double value) { finite(value); require(value >= 0, "nonnegative geometry"); }
    static void positive(double value) { finite(value); require(value > 0, "positive scale"); }
    static void unit(double value) { finite(value); require(value >= 0 && value <= 1, "opacity"); }
    static void dimension(int value) { require(value > 0 && value <= 32768, "dimension"); }
    static void choice(String value, String... choices) { require(value != null && Set.of(choices).contains(value), "enum value"); }
    static <T> List<T> copy(List<T> values, int max) {
        Objects.requireNonNull(values); require(values.size() <= max, "list limit"); return List.copyOf(values);
    }
    private static List<String> strings(List<String> values) { var result = copy(values, 64); result.forEach(PaintObservation::text); return result; }
    private static List<Radius> corners(List<Radius> values) { var result = copy(values, 4); require(result.size() == 4, "four corners"); return result; }
}
