package ru.cashprediction.fx.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TextField;
import javafx.scene.control.skin.TableViewSkin;
import javafx.scene.control.skin.TableColumnHeader;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.robot.Robot;
import javafx.scene.text.Text;
import javafx.scene.transform.Scale;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.cashprediction.core.ui.token.ColorToken;
import ru.cashprediction.core.ui.token.UiIcons;
import static org.junit.jupiter.api.Assertions.*;

/** Физический фокус, реальный VirtualFlow и пиксели скина; запускать отдельно при S5 на свободном рабочем столе. */
@EnabledIfSystemProperty(named = "fx.polishProof", matches = "true")
class FxPolishDesktopTest {
    @TempDir static Path home;
    private static FxUiPort port;
    private static final ConcurrentLinkedQueue<Throwable> uncaught = new ConcurrentLinkedQueue<>();
    private static Thread.UncaughtExceptionHandler previousExceptionHandler;

    /** Использует модель настоящего контроллера и изолированное окружение существующего стенда. */
    @BeforeAll static void start() throws Exception {
        FxComputedFontTest.temporaryHome = home;
        FxComputedFontTest.prepareNativeScenes();
        port = FxComputedFontTest.port;
        fx(() -> {
            previousExceptionHandler = Thread.currentThread().getUncaughtExceptionHandler();
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> uncaught.add(error));
            port.main.root.setCenter(port.main.table.root); port.stage.show(); port.stage.requestFocus(); return null;
        });
        await(() -> port.stage.isFocused());
        fx(() -> {
            String expectedScale = System.getProperty("fx.polishScale");
            if (expectedScale != null) {
                assertEquals(Double.parseDouble(expectedScale), port.stage.getOutputScaleX(), 0.001);
                assertEquals(Double.parseDouble(expectedScale), port.stage.getOutputScaleY(), 0.001);
            }
            return null;
        });
    }

    /** Закрывает только стенд и его планировщик. */
    @AfterAll static void stop() throws Exception {
        try { fx(() -> { Thread.currentThread().setUncaughtExceptionHandler(previousExceptionHandler); return null; }); }
        finally { FxComputedFontTest.releaseNativeScenes(); }
    }

    /** Ошибка обработчика toolkit не должна прятаться за успешной проверкой другого виджета. */
    @AfterEach void noUncaughtToolkitErrors() throws Exception {
        fx(() -> null);
        assertTrue(uncaught.isEmpty(), () -> "Uncaught JavaFX events: " + uncaught);
    }

    /** Щелчки Robot подтверждают focusedProperty и владельца фокуса; проверяются клипы настоящего редактора. */
    @Test void physicalFilterFocusAndContainment() throws Exception {
        TextField field = fx(() -> (TextField) port.main.toolbar.widgets.get("tb.filter"));
        for (double width : List.of(1200.0, 900.0)) {
            fx(() -> { port.stage.setWidth(width); field.clear(); return null; });
            await(() -> Math.abs(port.stage.getWidth() - width) < 1);
            await(() -> port.main.root.getWidth() > width - 60 && port.main.root.getWidth() <= width);
            click(field);
            await(() -> port.stage.isFocused() && field.isFocused() && field.getScene().getFocusOwner() == field);
            fx(() -> { prompt(field); return null; });
            click(port.main.table.root);
            await(() -> port.stage.isFocused() && !field.isFocused() && field.getScene().getFocusOwner() != field);
            fx(() -> { prompt(field); return null; });
        }
    }

    /** Реальная область стрелки открывает меню и сохраняет видимую графику в пределах кнопки. */
    @Test void toolbarArrowHitTargetsOpenActualMenus() throws Exception {
        fx(() -> { port.stage.setWidth(1200); return null; });
        await(() -> port.main.root.getWidth() > 1140 && port.main.root.getWidth() <= 1200);
        for (String id : List.of("tb.add", "tb.period", "tb.whatIf")) {
            // JavaFX: MenuButton/SplitMenuButton → Swing: JButton + JPopupMenu → Web: button + div[role=menu]
            var button = fx(() -> (javafx.scene.control.MenuButton) port.main.toolbar.widgets.get(id));
            Region arrow = fx(() -> (Region) button.lookup(".arrow"));
            Region hit = fx(() -> (Region) button.lookup(".arrow-button"));
            fx(() -> { visibleThroughClips(arrow); contains(hit.localToScene(hit.getLayoutBounds()), arrow.localToScene(arrow.getLayoutBounds()));
                paintedPng(arrow, "\u25be"); return null; });
            try { click(hit); await(button::isShowing); }
            finally { fx(() -> { button.hide(); return null; }); }
        }
    }

    /** Заголовки измеряются в узкой фиксированной колонке и при росте колонки операции. */
    @Test void headerTextFitsInsetsAndTwentyEightPixelRow() throws Exception {
        double[] growing = new double[2];
        int pass = 0;
        for (double width : List.of(1200.0, 1400.0)) {
            fx(() -> { port.stage.setWidth(width); return null; });
            await(() -> port.main.root.getWidth() > width - 60 && port.main.root.getWidth() <= width);
            final int index = pass++;
            fx(() -> {
                var table = port.main.table;
                table.root.applyCss(); table.root.layout();
                for (var spec : table.model.columns()) {
                    var header = table.root.lookupAll(".column-header").stream().filter(TableColumnHeader.class::isInstance)
                            .map(TableColumnHeader.class::cast).filter(h -> h.getTableColumn() == table.columns.get(spec.id()))
                            .findFirst().orElseThrow();
                    Label label = (Label) header.lookup(".label");
                    Text text = label.lookupAll(".text").stream().filter(Text.class::isInstance).map(Text.class::cast)
                            .filter(t -> t.getText().equals(spec.title())).findFirst().orElseThrow();
                    assertEquals(28, header.getHeight(), 0.01, spec.id());
                    assertEquals(spec.title(), text.getText(), spec.id());
                    Bounds ink = text.localToScene(text.getBoundsInLocal());
                    contains(header.localToScene(header.getLayoutBounds()), ink);
                    contains(label.localToScene(label.getLayoutBounds()), ink);
                    Bounds local = label.sceneToLocal(ink);
                    assertTrue(local.getMinX() >= label.getInsets().getLeft() - 0.75, spec.id());
                    assertTrue(local.getMaxX() <= label.getWidth() - label.getInsets().getRight() + 0.75, spec.id());
                    assertEquals(label.getHeight() / 2, (local.getMinY() + local.getMaxY()) / 2, 1.5, spec.id());
                    double left = label.getInsets().getLeft(), right = label.getWidth() - label.getInsets().getRight();
                    switch (spec.align()) {
                        case LEFT -> assertEquals(left, local.getMinX(), 1.5, spec.id());
                        case RIGHT -> assertEquals(right, local.getMaxX(), 1.5, spec.id());
                        case CENTER -> assertEquals((left + right) / 2, (local.getMinX() + local.getMaxX()) / 2, 1.5, spec.id());
                    }
                    visibleThroughClips(text);
                    if (spec.grows()) growing[index] = header.getWidth();
                    else {
                        assertEquals(spec.widthPx(), table.columns.get(spec.id()).getPrefWidth(), 0.01, spec.id());
                        // Нативный заголовок округляется вверх до физического пикселя: 130 CSS px при 125 %
                        // занимают 163 физических пикселя, то есть 130,4 CSS px, при неизменной ширине модели.
                        double scale = port.stage.getRenderScaleX();
                        assertEquals(Math.ceil(spec.widthPx() * scale) / scale,
                                table.columns.get(spec.id()).getWidth(), 0.01, spec.id());
                        assertEquals(Math.ceil(spec.widthPx() * scale) / scale, header.getWidth(), 0.01, spec.id());
                    }
                }
                return null;
            });
        }
        assertTrue(growing[1] > growing[0] + 100);
    }

    /** Подключение выполняет production-hook таблицы, включая поздние полосы и замену её скина. */
    @Test void owningTableScrollbarPngGeometryAndPhysicalActions() throws Exception {
        var table = port.main.table.root;
        try {
        fx(() -> { table.setMaxSize(640, 180); table.setMinSize(640, 180); table.setPrefSize(640, 180); return null; });
        for (int generation = 0; generation < 2; generation++) {
            if (generation > 0) fx(() -> { table.setSkin(new TableViewSkin<>(table)); return null; });
            await(() -> table.lookupAll(".scroll-bar").stream().filter(ScrollBar.class::isInstance)
                    .map(ScrollBar.class::cast).filter(Node::isVisible).filter(s -> s.getMax() > s.getMin()).count() == 2
                    && table.lookupAll(".scroll-bar .increment-arrow").stream().allMatch(n -> n.getProperties().containsKey("cp.icon")));
            fx(() -> {
                // Наличие свойства PNG ещё не означает завершённой CSS-раскладки после замены скина.
                port.main.root.applyCss(); port.main.root.layout();
                table.requestLayout(); table.layout(); return null;
            });
            for (Orientation orientation : Orientation.values()) {
                ScrollBar bar = fx(() -> table.lookupAll(".scroll-bar").stream().filter(ScrollBar.class::isInstance)
                        .map(ScrollBar.class::cast).filter(s -> s.getOrientation() == orientation).findFirst().orElseThrow());
                var regions = fx(() -> List.of(bar, (Region) bar.lookup(".increment-button"),
                        (Region) bar.lookup(".decrement-button"), (Region) bar.lookup(".thumb"),
                        (Region) bar.lookup(".increment-arrow"), (Region) bar.lookup(".decrement-arrow")));
                var before = fx(() -> regions.stream().map(Node::getBoundsInParent).toList());
                var skin = fx(bar::getSkin);
                fx(() -> { compareNativeOwnerGeometry(table, bar, orientation); return null; });
                fx(() -> {
                    for (int pass = 0; pass < 2; pass++) {
                        FxIcons.skinGraphics(table); table.applyCss(); table.layout();
                        assertSame(skin, bar.getSkin());
                        for (int i = 0; i < regions.size(); i++) assertEquals(before.get(i), regions.get(i).getBoundsInParent());
                    }
                    contains(table.localToScene(table.getLayoutBounds()), bar.localToScene(bar.getLayoutBounds()));
                    // Нативный Modena EndButton выступает на 1 px в область фона полосы, уже проверенную
                    // против реального эталона. layoutBounds не включают этот фон; проверяется painted bounds.
                    for (int i = 1; i <= 3; i++) contains(bar.localToScene(bar.getBoundsInLocal()), regions.get(i).localToScene(regions.get(i).getLayoutBounds()));
                    for (int i = 0; i < 2; i++) {
                        Region arrow = regions.get(4 + i), button = regions.get(1 + i);
                        contains(button.localToScene(button.getLayoutBounds()), arrow.localToScene(arrow.getLayoutBounds()));
                        assertTrue(button.getWidth() > 0 && button.getHeight() > 0);
                        visibleThroughClips(arrow);
                        String key = orientation == Orientation.VERTICAL ? (i == 0 ? "\u25be" : "\u2191") : (i == 0 ? "\u25b6" : "\u25c0");
                        paintedPng(arrow, key);
                    }
                    bar.setValue(bar.getMin()); return null;
                });
                var flow = fx(() -> table.lookup(".virtual-flow")); assertNotNull(flow);
                double old = fx(bar::getValue);
                var thumbBefore = fx(() -> regions.get(3).localToScene(regions.get(3).getLayoutBounds()));
                var contentBefore = fx(() -> viewportContent(table, orientation));
                // Один шаг на длинной таблице может быть меньше физического пикселя thumb при 100 % DPI.
                // Каждый щелчок обязан сдвигать содержимое; видимый сдвиг thumb проверяется после до четырёх шагов.
                click(regions.get(1)); await(() -> bar.getValue() > old
                        && !contentBefore.equals(viewportContent(table, orientation)));
                for (int step = 1; step < 4
                        && fx(() -> thumbBefore.equals(regions.get(3).localToScene(regions.get(3).getLayoutBounds()))); step++) {
                    double previous = fx(bar::getValue);
                    var previousContent = fx(() -> viewportContent(table, orientation));
                    click(regions.get(1));
                    await(() -> bar.getValue() > previous && !previousContent.equals(viewportContent(table, orientation)));
                }
                fx(() -> {
                    assertNotEquals(thumbBefore, regions.get(3).localToScene(regions.get(3).getLayoutBounds()));
                    assertNotEquals(contentBefore, viewportContent(table, orientation));
                    return null;
                });
                double incremented = fx(bar::getValue);
                click(regions.get(2)); await(() -> bar.getValue() < incremented);
            }
        }
        } finally {
            fx(() -> { table.setMinSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
                table.setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
                table.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE); return null; });
        }
    }

    /** Сравнивает геометрию с нативной таблицей той же модели и размеров, без подключения FxIcons. */
    private static void compareNativeOwnerGeometry(javafx.scene.control.TableView<Integer> table, ScrollBar bar, Orientation orientation) {
        var nativeTable = new javafx.scene.control.TableView<Integer>();
        nativeTable.getStyleClass().setAll(table.getStyleClass()); nativeTable.setStyle(table.getStyle());
        nativeTable.setFixedCellSize(table.getFixedCellSize()); nativeTable.setItems(table.getItems());
        for (var column : table.getColumns()) {
            var copy = new javafx.scene.control.TableColumn<Integer, String>(column.getText());
            copy.setPrefWidth(column.getWidth()); copy.setSortable(false); copy.setReorderable(false);
            nativeTable.getColumns().add(copy);
        }
        StackPane nativeOwner = new StackPane(nativeTable); FxStyles.root(nativeOwner);
        var nativeStage = new javafx.stage.Stage(javafx.stage.StageStyle.UNDECORATED);
        nativeStage.initOwner(port.stage); nativeStage.setX(port.stage.getX()); nativeStage.setY(port.stage.getY());
        // Показ создаёт настоящий peer на том же мониторе. Сцена без окна неправильно моделирует DPI и кеш insets.
        nativeStage.setScene(new javafx.scene.Scene(nativeOwner, table.getWidth(), table.getHeight()));
        try {
        FxComputedFontTest.css(nativeOwner, table.getWidth(), table.getHeight());
        nativeStage.show();
        assertEquals(port.stage.getOutputScaleX(), nativeStage.getOutputScaleX(), 0.001);
        assertEquals(port.stage.getOutputScaleY(), nativeStage.getOutputScaleY(), 0.001);
        FxComputedFontTest.css(nativeOwner, table.getWidth(), table.getHeight());
        nativeTable.requestLayout(); nativeTable.layout();
        var nativeBar = nativeTable.lookupAll(".scroll-bar").stream().filter(ScrollBar.class::isInstance)
                .map(ScrollBar.class::cast).filter(s -> s.getOrientation() == orientation).findFirst().orElseThrow();
        assertTrue(nativeBar.isVisible()); assertNull(nativeBar.lookup(".increment-arrow").getProperties().get("cp.icon"));
        nativeBar.setValue(bar.getValue()); nativeOwner.layout();
        for (var pair : List.of(table, nativeTable)) for (Node node : pair.lookupAll(".scroll-bar")) if (node instanceof ScrollBar observed) {
            Region arrow = (Region) observed.lookup(".increment-arrow");
            System.out.println("ALL_BAR " + (pair == table ? "actual" : "native") + " " + observed.getOrientation()
                    + " size=" + observed.getWidth() + "x" + observed.getHeight() + " pref="
                    + observed.prefWidth(-1) + "x" + observed.prefHeight(-1)
                    + " arrow=" + arrow.prefWidth(-1) + "x" + arrow.prefHeight(-1)
                    + " captured=" + arrow.getProperties().get("cp.iconSize"));
            for (String selector : List.of(".increment-button", ".decrement-button")) {
                Region end = (Region) observed.lookup(selector);
                System.out.println("END_BAR " + selector + " pref=" + end.prefWidth(-1) + "x" + end.prefHeight(-1)
                        + " snapped=" + end.snappedTopInset() + "," + end.snappedRightInset() + ","
                        + end.snappedBottomInset() + "," + end.snappedLeftInset() + " insets=" + end.getInsets());
            }
        }
        System.out.println("SCROLL_GEOMETRY " + orientation + " scale=" + port.stage.getOutputScaleX()
                + " actual=" + bar.getBoundsInParent() + " native=" + nativeBar.getBoundsInParent()
                + " amounts=" + bar.getVisibleAmount() + "/" + nativeBar.getVisibleAmount()
                + " max=" + bar.getMax() + "/" + nativeBar.getMax()
                + " columns=" + table.getColumns().stream().map(c -> Double.toString(c.getWidth())).toList()
                + "/" + nativeTable.getColumns().stream().map(c -> Double.toString(c.getWidth())).toList());
        for (String selector : List.of(".increment-button", ".decrement-button", ".increment-arrow", ".decrement-arrow")) {
            Region actual = (Region) bar.lookup(selector), original = (Region) nativeBar.lookup(selector);
            System.out.println("SCROLL_PART " + selector + " actual=" + actual.getLayoutBounds()
                    + " native=" + original.getLayoutBounds() + " captured=" + actual.getProperties().get("cp.iconSize")
                    + " prefs=" + actual.prefWidth(-1) + "x" + actual.prefHeight(-1)
                    + "/" + original.prefWidth(-1) + "x" + original.prefHeight(-1)
                    + " insets=" + actual.getInsets() + "/" + original.getInsets());
        }
        assertEquals(nativeBar.getBoundsInParent(), bar.getBoundsInParent());
        for (String selector : List.of(".increment-button", ".decrement-button", ".thumb", ".increment-arrow", ".decrement-arrow")) {
            Region actual = (Region) bar.lookup(selector), original = (Region) nativeBar.lookup(selector);
            assertEquals(original.getLayoutBounds(), actual.getLayoutBounds(), selector);
            assertEquals(original.getLayoutX(), actual.getLayoutX(), 0.01, selector);
            assertEquals(original.getLayoutY(), actual.getLayoutY(), 0.01, selector);
        }
        } finally { nativeStage.close(); nativeStage.setScene(null); port.stage.requestFocus(); }
    }

    /** Возвращает фактический сдвиг содержимого VirtualFlow, а не только значение полосы. */
    private static String viewportContent(javafx.scene.control.TableView<Integer> table, Orientation orientation) {
        if (orientation == Orientation.HORIZONTAL) {
            var cell = table.lookup(".table-cell"); assertNotNull(cell);
            return Double.toString(cell.localToScene(cell.getLayoutBounds()).getMinX());
        }
        var flow = (javafx.scene.control.skin.VirtualFlow<?>) table.lookup(".virtual-flow");
        var first = flow.getFirstVisibleCell(); assertNotNull(first);
        return first.getIndex() + ":" + first.localToScene(first.getLayoutBounds()).getMinY();
    }

    /** Проверяет локализованный prompt, вертикальный центр, ширину 220 и клипы вплоть до сцены. */
    private static void prompt(TextField field) {
        field.getScene().getRoot().applyCss(); field.getScene().getRoot().layout();
        var text = field.lookupAll(".text").stream().filter(Text.class::isInstance).map(Text.class::cast)
                .filter(t -> t.getText().equals(field.getPromptText())).findFirst().orElseThrow();
        assertEquals(220, field.getWidth(), 0.01); assertTrue(field.getText().isEmpty());
        assertEquals(Color.web(ColorToken.TEXT_MUTED.hex()), text.getFill());
        visibleThroughClips(text);
        Bounds local = field.sceneToLocal(text.localToScene(text.getBoundsInLocal()));
        assertTrue(local.getMinX() >= field.getInsets().getLeft() - 0.75);
        assertTrue(local.getMaxX() <= field.getWidth() - field.getInsets().getRight() + 0.75);
        assertEquals(field.getHeight() / 2, (local.getMinY() + local.getMaxY()) / 2, 1.5);
        contains(port.main.toolbar.root.localToScene(port.main.toolbar.root.getLayoutBounds()), field.localToScene(field.getLayoutBounds()));
    }

    /** Сопоставляет все реально растеризованные пиксели с неизменным общим цветовым PNG при четырёх масштабах. */
    static void paintedPng(Region arrow, String key) {
        System.out.println("ICON_RASTER " + key + " width=" + arrow.getWidth() + " height=" + arrow.getHeight()
                + " bounds=" + arrow.getBoundsInLocal() + " transform=" + arrow.getLocalToParentTransform()
                + " effect=" + arrow.getEffect() + " padding=" + arrow.getPadding());
        assertEquals(key, arrow.getProperties().get("cp.icon")); assertNull(arrow.getShape());
        assertEquals(1, arrow.getBackground().getImages().size());
        String source = arrow.getBackground().getImages().getFirst().getImage().getUrl();
        assertEquals("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(
                UiIcons.png(key, ColorToken.TEXT_PRIMARY).orElseThrow()), source);
        Image shared = new Image(new java.io.ByteArrayInputStream(UiIcons.png(key, ColorToken.TEXT_PRIMARY).orElseThrow()));
        Region reference = new Region(); reference.resize(arrow.getWidth(), arrow.getHeight());
        // Совпадающая дробная позиция сохраняет фазу растеризации; viewport не обрезает масштабированный узел.
        reference.getTransforms().setAll(arrow.getLocalToParentTransform());
        reference.setBackground(new Background(new BackgroundImage(shared, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                BackgroundPosition.CENTER, new BackgroundSize(100, 100, true, true, true, false))));
        for (double scale : List.of(1.0, 1.25, 1.5, 2.0)) {
            SnapshotParameters parameters = new SnapshotParameters(); parameters.setFill(Color.TRANSPARENT);
            parameters.setTransform(new Scale(scale, scale));
            var actual = arrow.snapshot(parameters, null); var expected = reference.snapshot(parameters, null);
            assertEquals(expected.getWidth(), actual.getWidth()); assertEquals(expected.getHeight(), actual.getHeight());
            assertTrue(actual.getWidth() >= Math.floor(arrow.getWidth() * scale));
            assertTrue(actual.getHeight() >= Math.floor(arrow.getHeight() * scale));
            int visible = 0;
            for (int y = 0; y < expected.getHeight(); y++) for (int x = 0; x < expected.getWidth(); x++) {
                int pixel = expected.getPixelReader().getArgb(x, y);
                assertEquals(pixel, actual.getPixelReader().getArgb(x, y), key + ":" + scale + ":" + x + ":" + y);
                if ((pixel >>> 24) > 0) visible++;
            }
            assertTrue(visible > 0);
        }
    }

    /** Проверяет видимость всей цепочки и полное попадание текста/стрелки в каждый настоящий клип. */
    private static void visibleThroughClips(Node node) {
        Bounds ink = node.localToScene(node.getBoundsInLocal());
        assertTrue(ink.getWidth() > 0 && ink.getHeight() > 0);
        for (Node ancestor = node; ancestor != null; ancestor = ancestor.getParent()) {
            assertTrue(ancestor.isVisible()); assertEquals(1, ancestor.getOpacity(), 0.001);
            if (ancestor.getClip() != null) contains(ancestor.localToScene(ancestor.getClip().getBoundsInParent()), ink);
        }
        contains(new javafx.geometry.BoundingBox(0, 0, node.getScene().getWidth(), node.getScene().getHeight()), ink);
    }

    /** Допускает только округление на границах пикселя, но не выход за область владельца. */
    static void contains(Bounds outer, Bounds inner) {
        assertTrue(inner.getMinX() >= outer.getMinX() - 0.75 && inner.getMaxX() <= outer.getMaxX() + 0.75
                && inner.getMinY() >= outer.getMinY() - 0.75 && inner.getMaxY() <= outer.getMaxY() + 0.75,
                () -> "outside: " + inner + " in " + outer);
    }

    /** Нажимает центр настоящего экранного hit target через Robot. */
    private static void click(Node node) throws Exception {
        fx(() -> {
            Bounds bounds = node.localToScreen(node.getLayoutBounds()); assertNotNull(bounds);
            Robot robot = new Robot(); robot.mouseMove((bounds.getMinX() + bounds.getMaxX()) / 2, (bounds.getMinY() + bounds.getMaxY()) / 2);
            robot.mouseClick(MouseButton.PRIMARY); return null;
        });
    }

    /** Ожидает физическое событие с конечным сроком вне FX-потока. */
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!fx(condition::getAsBoolean) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(fx(condition::getAsBoolean), "Native transition timeout");
    }

    /** Выполняет чтение и действия в FX-потоке с ограниченным ожиданием. */
    private static <T> T fx(Supplier<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action::get); javafx.application.Platform.runLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}
