package ru.cashprediction.fx.ui;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javafx.geometry.Insets;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.junit.jupiter.api.Test;
import ru.cashprediction.core.ui.dump.UiDump;
import ru.cashprediction.core.ui.selftest.paint.PaintCaptureRequest;
import ru.cashprediction.core.ui.selftest.paint.PaintObservation;
import ru.cashprediction.core.ui.selftest.paint.PaintObservation.*;
import ru.cashprediction.core.ui.selftest.paint.PaintObservationCodec;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Проверяет чтение настоящих Region/ImageView и исходных PNG без запуска окон или toolkit.
 * Эти unit-тесты не заменяют pulse, физический ввод, screenshot и интеграционный опыт S5.
 */
class FxPaintCollectorTest {
    private static final Transform UNIT = new Transform(1, 0, 0, 1, 0, 0);

    /** Сравнение collector не обвиняет неизменённое чтение, но сохраняет отказ на реальную мутацию. */
    @Test void generationComparisonDistinguishesReadFromRealMutation() {
        Pane root = new Pane();
        try (var journal = new FxCaptureJournal(() -> true)) {
            journal.attach(root); var before = journal.stamp();
            journal.readSnapshot(() -> root.getOpacity());
            assertTrue(FxPaintCollector.generationChanges(before, journal.stamp()).isEmpty());
            journal.readSnapshot(() -> { root.setOpacity(0.5); root.setOpacity(1); return root; });
            var changes = FxPaintCollector.generationChanges(before, journal.stamp());
            assertTrue(changes.contains("mutation journal changed"));
            assertTrue(changes.contains("render/pulse generation changed"));
            assertFalse(journal.stamp().mutationJournalComplete());
        }
    }

    /** Неизвестный числовой цвет, размеры и отступы читаются из Region без запасного токена. */
    @Test void readsRawBackgroundAndFractionalCardGeometry() {
        Pane card = card(); card.resize(231.25, 91.75); card.setTranslateX(11.125); card.setTranslateY(7.375);
        Color color = Color.rgb(19, 37, 83, 0.75);
        card.setBackground(new Background(new BackgroundFill(color, new CornerRadii(4), new Insets(1, 2, 3, 4))));
        var observed = observe(card);
        assertEquals(new Box(0, 0, 231.25, 91.75), observed.localBox());
        assertEquals(new Box(11.125, 7.375, 231.25, 91.75), observed.bounds());
        assertEquals(0xbf132553, observed.backgrounds().getFirst().argb());
        assertEquals(new PaintObservation.Insets(1, 2, 3, 4), observed.backgrounds().getFirst().insets());
        assertEquals(4, observed.backgrounds().getFirst().radii().getFirst().rx());
    }

    /** Квадратная заливка и округлая рамка остаются различными фактическими контурами. */
    @Test void squareFillIsNotReplacedWithRoundedBorderTokens() {
        Pane card = card();
        card.setBackground(new Background(new BackgroundFill(Color.WHITE, CornerRadii.EMPTY, Insets.EMPTY)));
        card.setBorder(new Border(new BorderStroke(Color.BLUE, BorderStrokeStyle.SOLID, new CornerRadii(6), new BorderWidths(1))));
        var actual = observe(card);
        assertEquals(0, actual.backgrounds().getFirst().radii().getFirst().rx());
        assertEquals(6, actual.borders().getFirst().pathRadii().getFirst().rx());
        // NGRegion рисует центрированный штрих на смещённом внутрь пути с радиусом 6.
        assertEquals(6.5, actual.borders().getFirst().radii().getFirst().rx());
        assertEquals("CENTERED;Region-path-offset=INSIDE", actual.borders().getFirst().strokePlacement());
    }

    /** Изменённая краска и ширина рамки считываются заново, а не наследуются из прошлого среза. */
    @Test void changedBorderAndFillAreReadAgain() {
        FxPaintCollector collector = new FxPaintCollector(); Pane card = card(); List<Unsupported> problems = new ArrayList<>();
        card.setBackground(new Background(new BackgroundFill(Color.RED, new CornerRadii(2), Insets.EMPTY)));
        card.setBorder(new Border(new BorderStroke(Color.BLUE, BorderStrokeStyle.SOLID, CornerRadii.EMPTY, new BorderWidths(3))));
        var first = collector.card(card, UNIT, null, physical(null), 7, problems);
        card.setBackground(new Background(new BackgroundFill(Color.GREEN, new CornerRadii(5), Insets.EMPTY)));
        card.setBorder(new Border(new BorderStroke(Color.RED, BorderStrokeStyle.SOLID, CornerRadii.EMPTY, new BorderWidths(2))));
        var second = collector.card(card, UNIT, null, physical(null), 8, problems);
        assertNotEquals(first.backgrounds(), second.backgrounds()); assertNotEquals(first.borders(), second.borders());
        assertEquals(List.of(2.0, 2.0, 2.0, 2.0), second.borders().getFirst().widths());
        assertEquals(List.of(0xffff0000, 0xffff0000, 0xffff0000, 0xffff0000), second.borders().getFirst().colors());
        assertEquals(8, second.lastPaintEpoch());
    }

    /** Четыре разных цвета и ширины не сводятся к одной ожидаемой рамке. */
    @Test void retainsFourSidesAndRejectsUnresolvedContour() {
        Pane card = card();
        card.setBorder(new Border(new BorderStroke(Color.RED, Color.GREEN, Color.BLUE, Color.BLACK,
                BorderStrokeStyle.SOLID, BorderStrokeStyle.SOLID, BorderStrokeStyle.SOLID, BorderStrokeStyle.SOLID,
                new CornerRadii(3), new BorderWidths(1, 2, 3, 4), Insets.EMPTY)));
        List<Unsupported> unsupported = new ArrayList<>();
        var actual = new FxPaintCollector().card(card, UNIT, null, physical(null), 3, unsupported);
        assertEquals(List.of(1.0, 2.0, 3.0, 4.0), actual.borders().getFirst().widths());
        assertEquals(List.of(0xffff0000, 0xff008000, 0xff0000ff, 0xff000000), actual.borders().getFirst().colors());
        assertTrue(has(unsupported, "borderContour")); assertNull(actual.borders().getFirst().pathRadii());
    }

    /** Радиусы идут TL, TR, BL, BR, даже когда исходный конструктор использует обход по часовой стрелке. */
    @Test void preservesAllCornersInRequiredOrder() {
        var values = FxPaintCollector.radii(new CornerRadii(2, 3, 5, 7, false), 300, 200, Insets.EMPTY, "card", new ArrayList<>());
        assertEquals(List.of(2.0, 3.0, 7.0, 5.0), values.stream().map(Radius::rx).toList());
    }

    /** Проценты разрешаются по реальным размерам слоя после его отступов. */
    @Test void resolvesPercentageRadiiUsingInsetBox() {
        var values = FxPaintCollector.radii(new CornerRadii(0.1, true), 200, 100, new Insets(5, 10, 5, 10), "card", new ArrayList<>());
        assertEquals(0.1, values.getFirst().rawRx()); assertTrue(values.getFirst().percentageX());
        assertEquals(18, values.getFirst().rx()); assertEquals(9, values.getFirst().ry());
    }

    /** Перекрывающиеся радиусы получают один фактический коэффициент Region.normalize. */
    @Test void normalizesOverlappingRadiiAndEmptyInsetBox() {
        var radii = FxPaintCollector.radii(new CornerRadii(20), 30, 20, Insets.EMPTY, "card", new ArrayList<>());
        assertEquals(20, radii.getFirst().rawRx()); assertEquals(10, radii.getFirst().rx()); assertEquals(10, radii.getFirst().ry());
        var empty = FxPaintCollector.radii(new CornerRadii(6), 10, 10, new Insets(6), "card", new ArrayList<>());
        assertEquals(0, empty.getFirst().rx()); assertEquals(0, empty.getFirst().ry());
    }

    /** focusWithin читается по владельцу фокуса, физический hit по дереву, isHover не из намерения. */
    @Test void observesFocusOwnerAndActualPickedAncestryIndependently() {
        Pane card = card(); Pane child = new Pane(); card.getChildren().add(child);
        var actual = new FxPaintCollector().card(card, UNIT, child, physical(child), 9, new ArrayList<>());
        assertTrue(actual.focusWithin()); assertFalse(actual.focused()); assertFalse(actual.hover());
        assertEquals(Boolean.TRUE, actual.physicalHit()); assertNotNull(actual.focusOwner()); assertNull(actual.focusVisible());
        var outside = new FxPaintCollector().card(card, UNIT, new Pane(), physical(new Pane()), 9, new ArrayList<>());
        assertFalse(outside.focusWithin()); assertEquals(Boolean.FALSE, outside.physicalHit());
    }

    /** Отсутствующий физический hit-test явно остаётся неизвестным и неподдержанным. */
    @Test void missingPhysicalHitIsUnsupported() {
        List<Unsupported> unsupported = new ArrayList<>();
        var actual = new FxPaintCollector().card(card(), UNIT, null, null, 0, unsupported);
        assertNull(actual.physicalHit()); assertTrue(has(unsupported, "physicalHit"));
    }

    /** Тень, clip, нестандартный painter и дополнительная альфа не маскируются токенами. */
    @Test void customPaintClipEffectAndOpacityAreUnsupported() {
        CustomCard card = new CustomCard(); card.getProperties().put("cp.id", "card.custom"); card.resize(200, 80);
        card.setEffect(new DropShadow()); card.setClip(new Rectangle(100, 40)); card.setOpacity(0.55);
        List<Unsupported> unsupported = new ArrayList<>();
        var actual = new FxPaintCollector().card(card, UNIT, null, physical(null), 4, unsupported);
        assertTrue(actual.clipped()); assertFalse(actual.effects().isEmpty());
        for (String property : List.of("clip", "effect", "opacity", "painter")) assertTrue(has(unsupported, property), property);
    }

    /** Правильный cp.icon не заменяет удержанные байты установленного объекта изображения. */
    @Test void semanticMarkerCannotManufactureProvenance() {
        Image image = image(4, 2, 0xff123456); ImageView view = arrow(image, 16); view.getProperties().put("cp.icon", "correct-marker");
        var actual = new FxPaintCollector().images(scope(view), new Sources(), UNIT);
        assertEquals(1, actual.icons().size()); assertNull(actual.icons().getFirst().assetId());
        assertEquals("correct-marker", actual.icons().getFirst().semanticKey()); assertTrue(actual.assets().isEmpty());
        assertTrue(has(actual.unsupported(), "provenance")); assertFalse(actual.icons().getFirst().complete());
    }

    /** Повторы одного Image остаются двумя occurrences, а исходные байты удерживаются один раз. */
    @Test void repeatedImageObjectsRetainDistinctOccurrencesAndExactBytes() {
        Image image = image(4, 2, 0xff123456); Sources sources = new Sources(); byte[] bytes = sources.retain(image);
        var actual = new FxPaintCollector().images(scope(arrow(image, 15), arrow(image, 17)), sources, UNIT);
        assertEquals(2, actual.icons().size()); assertEquals(1, actual.assets().size());
        assertNotEquals(actual.icons().get(0).instanceId(), actual.icons().get(1).instanceId());
        assertEquals(actual.icons().get(0).assetId(), actual.icons().get(1).assetId());
        assertEquals(15, actual.icons().get(0).localBox().width()); assertEquals(7.5, actual.icons().get(0).localBox().height());
        assertEquals(17, actual.icons().get(1).localBox().width()); assertEquals(8.5, actual.icons().get(1).localBox().height());
        assertArrayEquals(bytes, actual.assets().getFirst().base64());
        assertEquals(PaintObservationCodec.sha256(bytes), actual.assets().getFirst().sha256());
        assertEquals(actual.assets().getFirst().sourceObjectIdentity(), actual.icons().getFirst().sourceObjectIdentity());
    }

    /** Замена установленного Image не наследует provenance старого объекта даже с тем же маркером. */
    @Test void replacementImageDoesNotInheritIdentityProvenance() {
        Image original = image(3, 3, 0xff123456), replacement = image(3, 3, 0xffabcdef);
        Sources sources = new Sources(); sources.retain(original); ImageView view = arrow(original, 16); Pane root = scope(view);
        FxPaintCollector collector = new FxPaintCollector(); assertNotNull(collector.images(root, sources, UNIT).icons().getFirst().assetId());
        view.setImage(replacement);
        var changed = collector.images(root, sources, UNIT);
        assertNull(changed.icons().getFirst().assetId()); assertTrue(has(changed.unsupported(), "provenance"));
    }

    /** Даже identity-привязанные чужие байты не проходят сравнение с установленным PixelReader. */
    @Test void wrongRetainedBytesAreRejectedAgainstInstalledPixels() {
        Image original = image(3, 3, 0xff123456), installed = image(3, 3, 0xffabcdef);
        Sources sources = new Sources(); sources.values.put(installed, new FxPaintCollector.Source(installed, PngEncoder.encode(original), "decode://wrong"));
        var actual = new FxPaintCollector().images(scope(arrow(installed, 16)), sources, UNIT);
        assertNull(actual.icons().getFirst().assetId()); assertTrue(has(actual.unsupported(), "provenance"));
    }

    /** Мутация WritableImage обнаруживается при неизменных identity и исходных байтах. */
    @Test void writableImageMutationInvalidatesRetainedSource() {
        WritableImage installed = image(2, 2, 0xff123456); Sources sources = new Sources(); sources.retain(installed);
        installed.getPixelWriter().setArgb(1, 1, 0xff654321);
        var actual = new FxPaintCollector().images(scope(arrow(installed, 16)), sources, UNIT);
        assertNull(actual.icons().getFirst().assetId()); assertTrue(has(actual.unsupported(), "provenance"));
    }

    /** Crop, дробные координаты и дополнительная альфа сохраняются независимо от intrinsic-альфа PNG. */
    @Test void preservesCropFractionalTransformAndAncestorOpacity() {
        Image source = image(4, 2, 0x80123456); Sources sources = new Sources(); sources.retain(source);
        ImageView view = arrow(source, 17); view.setViewport(new Rectangle2D(1, 0, 2, 2));
        view.setTranslateX(0.125); view.setTranslateY(0.375); view.setOpacity(0.5); Pane root = scope(view); root.setOpacity(0.55);
        var actual = new FxPaintCollector().images(root, sources, new Transform(1, 0, 0, 1, 10.25, 20.5));
        var icon = actual.icons().getFirst();
        assertEquals(new Box(1, 0, 2, 2), icon.sourceViewport()); assertEquals(17, icon.localBox().height());
        assertEquals(10.375, icon.bounds().x()); assertEquals(20.875, icon.bounds().y());
        assertEquals(0.275, icon.effectiveOpacity(), 1e-12);
        assertEquals(List.of(0.5, 1.0, 0.55), icon.opacityFactors().stream().map(OpacityFactor::value).toList());
        assertEquals(FxPaintCollector.decodedHash(source), actual.assets().getFirst().decodedArgbSha256());
    }

    /** Quad вращения сохраняется, но envelope не выдаётся за поддержанную осевую геометрию. */
    @Test void rotatedImagePreservesQuadAndIsUnsupported() {
        Image image = image(4, 2, 0xff123456); Sources sources = new Sources(); sources.retain(image);
        ImageView view = arrow(image, 16); view.setRotate(30);
        var actual = new FxPaintCollector().images(scope(view), sources, UNIT);
        assertEquals(4, actual.icons().getFirst().quad().size());
        assertNotEquals(0, actual.icons().getFirst().localToPng().b()); assertTrue(has(actual.unsupported(), "transform"));
    }

    /** Фоновая стрелка скина читается как отдельный Image с реальным contain-размером NGRegion. */
    @Test void observesActualBackgroundImageAndEngineContainCeiling() {
        Image image = image(4, 2, 0xff123456); Sources sources = new Sources(); sources.retain(image);
        Pane arrow = new Pane(); arrow.getStyleClass().add("arrow"); arrow.resize(17, 16);
        BackgroundImage background = new BackgroundImage(image, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER, new BackgroundSize(-1, -1, false, false, true, false));
        arrow.setBackground(new Background(background)); Pane root = scope(arrow);
        var actual = new FxPaintCollector().images(root, sources, UNIT);
        assertEquals("css-background", actual.icons().getFirst().paintSource());
        assertEquals("arrow", actual.icons().getFirst().role());
        assertEquals(new Box(0, 3.5, 17, 9), actual.icons().getFirst().localBox());
        assertArrayEquals(PngEncoder.encode(image), actual.assets().getFirst().base64());
    }

    /** Cover и repeated-background не подменяются размером владельца или ожидаемым слотом. */
    @Test void unrecognizedBackgroundGeometryIsExplicitlyUnavailable() {
        Image image = image(4, 2, 0xff123456); Pane arrow = new Pane(); arrow.resize(16, 16);
        List<Unsupported> unsupported = new ArrayList<>();
        for (BackgroundImage background : List.of(
                new BackgroundImage(image, BackgroundRepeat.REPEAT, BackgroundRepeat.NO_REPEAT, BackgroundPosition.CENTER, BackgroundSize.DEFAULT),
                new BackgroundImage(image, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT, BackgroundPosition.CENTER,
                        new BackgroundSize(-1, -1, false, false, false, true)))) {
            assertEquals(new Box(0, 0, 0, 0), FxPaintCollector.backgroundBox(arrow, background, "image", unsupported));
        }
        assertTrue(has(unsupported, "geometry"));
    }

    /** Без маркера image-node всё равно учитывается; неузнанная роль явно неподдержана. */
    @Test void unexpectedImageWithoutMarkerIsNotDropped() {
        Image image = image(4, 2, 0xff123456); Sources sources = new Sources(); sources.retain(image);
        var actual = new FxPaintCollector().images(scope(new ImageView(image)), sources, UNIT);
        assertEquals(1, actual.icons().size()); assertEquals("unclassified", actual.icons().getFirst().role());
        assertTrue(has(actual.unsupported(), "role"));
    }

    /** Отсутствующая эпоха реальной краски имеет явный unavailable sentinel и Unsupported. */
    @Test void missingPaintEpochCannotBeClaimedComplete() {
        Image image = image(2, 2, 0xff123456); Sources sources = new Sources(); sources.retain(image); sources.paintEpoch = null;
        var actual = new FxPaintCollector().images(scope(arrow(image, 16)), sources, UNIT);
        assertEquals(0, actual.icons().getFirst().drawEpoch()); assertFalse(actual.icons().getFirst().complete());
        assertTrue(has(actual.unsupported(), "drawEpoch"));
    }

    /** Роль очистки читается из реального ancestry, а её PNG тоже требует identity-provenance. */
    @Test void clearImageIsEnumeratedByActualAncestry() {
        Image source = image(2, 2, 0xff123456); ImageView view = new ImageView(source);
        Pane clear = new Pane(view); clear.getStyleClass().add("clear-button");
        var actual = new FxPaintCollector().images(scope(clear), new Sources(), UNIT);
        assertEquals("clear", actual.icons().getFirst().role()); assertNull(actual.icons().getFirst().assetId());
        assertTrue(has(actual.unsupported(), "provenance"));
    }

    /** Фактический crop вне исходника не может объявить destination полностью известным. */
    @Test void cropOutsideSourceCannotBeComplete() {
        Image source = image(2, 2, 0xff123456); Sources sources = new Sources(); sources.retain(source);
        ImageView view = arrow(source, 16); view.setViewport(new Rectangle2D(-1, 0, 4, 2));
        var actual = new FxPaintCollector().images(scope(view), sources, UNIT);
        assertEquals(new Box(-1, 0, 4, 2), actual.icons().getFirst().sourceViewport());
        assertFalse(actual.icons().getFirst().complete()); assertTrue(has(actual.unsupported(), "viewport"));
    }

    /** Невекторный canvas и геометрическая фигура не исчезают из проверки paintSource. */
    @Test void additionalUnknownPaintSourcesAreUnsupported() {
        Pane root = new Pane(new Rectangle(10, 10), new javafx.scene.canvas.Canvas(10, 10));
        var actual = new FxPaintCollector().images(root, new Sources(), UNIT);
        assertEquals(2, actual.unsupported().stream().filter(f -> f.property().equals("paintSource")).count());
    }

    /** Результат копирует исходный PNG и не позволяет получить WidgetCapture при Unsupported. */
    @Test void diagnosticResultCannotBecomeSupportedCapture() {
        var request = new PaintCaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 0, "scenario", "step", 1,
                "0".repeat(64), Map.of(), System.nanoTime() + 1_000_000_000L);
        var identity = new Identity(request.runId(), request.captureId(), 0, "fx", "scenario", "step", 1, request.planSha256());
        String hash = "0".repeat(64);
        var observation = new PaintObservation(1, PaintObservation.KIND, identity,
                new Viewport(2, 2, 2, 2, UNIT, new Point(0, 0), 1, 1, null, "root"),
                new Environment("unit", "unit", "unit", 1, "unit", Map.of()),
                new Synchronization("fx-scene", 1, 1, 1, 1, 1, 1, 1, 1, "scene", hash, hash, 0, 0, true, List.of()),
                new Interaction(null, "none", null, "root", 0, false),
                List.of(new Surface("root", "root", new Point(0, 0), new Point(0, 0), new Box(0, 0, 2, 2), 0, true, false)),
                List.of(), List.of(), List.of(), List.of(new Unsupported("root", "provenance", "unit failure")));
        UiDump raw = new UiDump(1, "fx", "scenario", "step", new UiDump.Frame("unit", "os", null, 2, 2, Map.of()),
                List.of(), null, null, null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), Map.of());
        byte[] bytes = PngEncoder.encode(image(2, 2, 0xff123456)), original = bytes.clone();
        var result = new FxPaintCollector.Result(raw, bytes, observation); bytes[0] = 0; result.png()[1] = 0;
        assertArrayEquals(original, result.png());
        assertThrows(IllegalArgumentException.class, () -> result.requireSupported(request));
    }

    /** Декодированный хеш следует точному порядку байтов A,R,G,B, без повторной PNG-кодировки. */
    @Test void decodedHashUsesStraightArgbRowOrder() {
        WritableImage image = image(2, 1, 0xff123456); image.getPixelWriter().setArgb(1, 0, 0xffabcdef);
        byte[] pixels = ByteBuffer.allocate(8).putInt(0xff123456).putInt(0xffabcdef).array();
        assertEquals(PaintObservationCodec.sha256(pixels), FxPaintCollector.decodedHash(image));
    }

    /** Источник удерживает копии байтов и не позволяет менять provenance через accessor. */
    @Test void sourceBytesAreDefensivelyCopied() {
        Image image = image(2, 2, 0xff123456); byte[] bytes = PngEncoder.encode(image), retained = bytes.clone();
        var source = new FxPaintCollector.Source(image, bytes, "decode://unit"); bytes[0] = 0;
        source.png()[1] = 0; assertArrayEquals(retained, source.png());
    }

    /** Публичная транзакция немедленно отклоняет вызов с обычного потока, не обращаясь к зависимостям. */
    @Test void synchronousApiRequiresFxThread() {
        PaintCaptureRequest request = new PaintCaptureRequest(UUID.randomUUID(), UUID.randomUUID(), 0, "scenario", "step", 1,
                "0".repeat(64), Map.of(), System.nanoTime() + 1_000_000_000L);
        var error = assertThrows(IllegalStateException.class, () -> new FxPaintCollector().collect(request, null, null, null, new Sources()));
        assertEquals("FX capture thread required", error.getMessage());
    }

    /** Создаёт живую карточку без контролов, сцены и открытия окон. */
    private static Pane card() { Pane pane = new Pane(); pane.resize(240, 90); pane.getProperties().put("cp.id", "card.test"); return pane; }
    /** Читает карточку через тот же метод, которым пользуется транзакция сцены. */
    private static Card observe(Pane pane) { return new FxPaintCollector().card(pane, UNIT, null, physical(null), 7, new ArrayList<>()); }
    /** Создаёт только данные тестового hit-test, не утверждая наличие нативного ввода. */
    private static FxPaintCollector.Input physical(Node picked) { return new FxPaintCollector.Input(null, picked, true, "none", 0, true); }
    /** Создаёт реальный PixelReader для unit-опыта, без каталога ожидаемых значков. */
    private static WritableImage image(int width, int height, int argb) {
        WritableImage image = new WritableImage(width, height);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.getPixelWriter().setArgb(x, y, argb);
        return image;
    }
    /** Создаёт реальный ImageView с реальным ancestry-признаком стрелки. */
    private static ImageView arrow(Image image, double size) {
        ImageView view = new ImageView(image); view.setFitWidth(size); view.setFitHeight(size); view.setPreserveRatio(true);
        view.getStyleClass().add("arrow"); return view;
    }
    /** Создаёт дерево владельца и области; Pane не выдаётся за полноценный toolbar Control. */
    private static Pane scope(Node... nodes) {
        Pane owner = new Pane(nodes); owner.getProperties().put("cp.id", "tb.unit");
        return new Pane(owner);
    }
    /** Ищет диагностируемое свойство, не привязываясь к формулировке причины. */
    private static boolean has(List<Unsupported> failures, String property) { return failures.stream().anyMatch(f -> f.property().equals(property)); }

    /** Неузнанный пользовательский painter для проверки честного отказа. */
    private static final class CustomCard extends Pane { }

    /** Только source-зависимость unit-опыта; попытка снимать сцену этим адаптером запрещена. */
    private static final class Sources implements FxPaintCollector.Transaction {
        final IdentityHashMap<Image, FxPaintCollector.Source> values = new IdentityHashMap<>();
        Long paintEpoch = 7L;
        /** Удерживает bytes именно установленного тестового Image, без поиска по маркеру. */
        byte[] retain(Image image) {
            byte[] png = PngEncoder.encode(image); values.put(image, new FxPaintCollector.Source(image, png, "decode://unit")); return png;
        }
        /** Возвращает источник только при точном совпадении объекта. */
        @Override public FxPaintCollector.Source source(Image installed) { return values.get(installed); }
        /** Возвращает тестовую эпоху только для проверки записи и отсутствия hook. */
        @Override public Long lastPaintEpoch(Node node) { return paintEpoch; }
        /** Unit-адаптер не поставляет дамп настоящего приложения. */
        @Override public UiDump raw(PaintCaptureRequest request) { throw new UnsupportedOperationException("unit source only"); }
        /** Unit-адаптер не поставляет screenshot. */
        @Override public byte[] png() { throw new UnsupportedOperationException("unit source only"); }
        /** Unit-адаптер не поставляет геометрию композитора. */
        @Override public FxPaintCollector.Frame frame() { throw new UnsupportedOperationException("unit source only"); }
        /** Unit-адаптер не удостоверяет pulse или мутации. */
        @Override public FxPaintCollector.Stamp stamp() { throw new UnsupportedOperationException("unit source only"); }
        /** Unit-адаптер не удостоверяет физический ввод. */
        @Override public FxPaintCollector.Input input() { throw new UnsupportedOperationException("unit source only"); }
        /** Unit-адаптер не удостоверяет среду нативного захвата. */
        @Override public Environment environment() { throw new UnsupportedOperationException("unit source only"); }
    }
}
